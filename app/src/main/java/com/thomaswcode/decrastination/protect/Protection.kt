package com.thomaswcode.decrastination.protect

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.ui.Format
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Protection (docs/spec/blocking-and-protection.md; docs/plan_v4, Stage 6b), moved out of `AppGraph`:
 * a settings change applied at once, or, once armed, pending if it loosens blocking ([changeSettings]);
 * the weekly review's bounded changes at once ([applyReviewChanges]); the waits counted and what's due
 * applied ([applyDueChanges]); a parent code used ([useParentCode]), and a waiting change cancelled or
 * applied by one ([cancelChange], [applyNow]); and the device admin given up once disarmed ([start]).
 * Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and small callbacks
 * for what Android does.
 */
class Protection(
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    /** Where your dad's authenticator's secret is kept ([Secret.TotpSecret]). */
    private val secrets: SecretStore,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** Whether the device admin is on ([Watchdog.isAdminActive]). */
    private val adminActive: () -> Boolean,
    /** The device admin given up, so uninstalling is allowed again. */
    private val removeAdmin: () -> Unit,
) {
    /**
     * Held while the settings and their pending changes are changed: two files, written in turn,
     * so each change finishes before the next reads them.
     */
    private val changing = Mutex()

    /** The changes waiting their hours, once armed, that loosen blocking (layer 4). */
    val pending: List<PendingChange> get() = runtime.value.pending

    /** What's been asked for: the settings in force, with what's waiting applied. */
    val asked: Settings get() = SettingsChanges.requested(settings.value, runtime.value.pending)

    /** How long a change that loosens blocking waits, by the settings in force. */
    val loosenDelayHours: Int get() = settings.value.loosenDelayHours

    /**
     * Changes the settings as [change] says, from what's been asked for (the settings with what's
     * waiting applied), so a change to one field leaves the others' waiting changes be.
     */
    suspend fun changeSettings(change: (Settings) -> Settings) = changing.withLock {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDue(force = true)
        val state = runtime.value
        val proposed = change(SettingsChanges.requested(settings.value, state.pending))
        val outcome = SettingsChanges.propose(settings.value, proposed, state.pending, clock.now()) { UUID.randomUUID().toString() }
        // Nothing was waiting: the count starts now, whatever an old mark says.
        val mark = if (state.pending.isEmpty()) clock.uptime() else state.uptimeMark ?: clock.uptime()
        // The pending list first: stopped between the two, what applies now is lost (and seen to
        // be), but a waiting change this one replaced can't come back.
        runtime.update { it.copy(pending = outcome.pending, uptimeMark = if (outcome.pending.isEmpty()) null else mark) }
        settings.update { outcome.settings }
    }

    /**
     * The weekly review's [changes] to the planner's numbers, within their bounds, applied at once,
     * armed or not: you said they needn't wait (11 Oct, IMP-P2-015). Only the fields
     * [ReviewInput.apply] sets can change this way; everything else waits as ever.
     */
    suspend fun applyReviewChanges(changes: List<ReviewInput.Change>) = changing.withLock {
        applyDue(force = true)
        val state = runtime.value
        // The settings it names: one it sets to the value already asked for applies now all the same.
        val bounds = ReviewInput.bounds(settings.value)
        val fields = changes.map { it.setting }.filter { it in bounds }.map(ReviewInput::fieldOf).toSet()
        val outcome = SettingsChanges.atOnce(settings.value, state.pending, fields) { ReviewInput.apply(it, changes) }
        runtime.update { it.copy(pending = outcome.pending, uptimeMark = if (outcome.pending.isEmpty()) null else it.uptimeMark) }
        settings.update { outcome.settings }
    }

    /**
     * Counts the uptime since the last count towards the pending changes and applies those whose
     * wait is over. Called every half minute; it writes (and so counts) only every few minutes, or
     * when a change falls due, unless [force]d.
     */
    suspend fun applyDueChanges(force: Boolean = false) {
        if (runtime.value.pending.isEmpty()) return
        changing.withLock { applyDue(force) }
    }

    /** [applyDueChanges], with [changing] held. */
    private suspend fun applyDue(force: Boolean) {
        val state = runtime.value
        if (state.pending.isEmpty()) return
        val uptime = clock.uptime()
        val elapsed = SettingsChanges.counting(state.pending, state.uptimeMark, uptime, force, settings.value) ?: return
        val outcome = SettingsChanges.applyDue(settings.value, state.pending, clock.now(), elapsed)
        val waiting = outcome.pending.map { it.id }.toSet()
        val due = state.pending.filter { it.id !in waiting }
        // The settings first: stopped before the pending list is saved, a change that fell due is
        // applied again (to the same value), never lost.
        if (due.isNotEmpty()) settings.update { latest -> due.fold(latest, SettingsChanges::apply) }
        runtime.update { it.copy(pending = outcome.pending, uptimeMark = if (outcome.pending.isEmpty()) null else uptime) }
    }

    /**
     * Checks a parent [code] from your dad's authenticator, using it up if it's right (with an
     * hour's unblock until [unblockUntil], if that's what it's for). Returns what went wrong, or null.
     */
    suspend fun useParentCode(code: String, unblockUntil: Long? = null): String? {
        val secret = secrets[Secret.TotpSecret]?.let(Totp::fromBase32) ?: return "No parent code is set"
        val now = clock.now()
        var outcome: CodeLock.Result = CodeLock.Result.Reused
        runtime.update { state ->
            val (result, lock) = state.codeLock.attempt(secret, code, now)
            outcome = result
            if (result is CodeLock.Result.Accepted && unblockUntil != null) state.copy(codeLock = lock, overrideUntil = unblockUntil) else state.copy(codeLock = lock)
        }
        return when (val result = outcome) {
            is CodeLock.Result.Accepted -> null
            is CodeLock.Result.Wrong -> "Wrong code: ${result.triesLeft} ${if (result.triesLeft == 1) "try" else "tries"} left"
            is CodeLock.Result.Locked -> "Too many wrong codes: try again ${Format.at(result.until, now, clock.zone())}"
            CodeLock.Result.Reused -> "That code has been used: wait for the next one"
        }
    }

    /** Drops the pending change [id]: what it would loosen stays as it is. Never waits, since it tightens. */
    suspend fun cancelChange(id: String) = changing.withLock {
        runtime.update { state ->
            val rest = state.pending.filterNot { it.id == id }
            state.copy(pending = rest, uptimeMark = if (rest.isEmpty()) null else state.uptimeMark)
        }
    }

    /** Applies the pending change [id] now (a parent's code allowed it), if it's still waiting. */
    suspend fun applyNow(id: String) = changing.withLock {
        val change = runtime.value.pending.firstOrNull { it.id == id } ?: return@withLock
        // Saved before its pending entry goes, as in [applyDue].
        settings.update { SettingsChanges.apply(it, change) }
        runtime.update { state ->
            val rest = state.pending.filterNot { it.id == id }
            state.copy(pending = rest, uptimeMark = if (rest.isEmpty()) null else state.uptimeMark)
        }
    }

    /**
     * [secret], a new one for your dad's authenticator, kept once a code from it checked out at [step]
     * (arming's wizard). Only the code checked is used up: the next one his app shows works.
     */
    suspend fun newParentCode(secret: ByteArray, step: Long) {
        secrets.put(Secret.TotpSecret, Totp.base32(secret))
        runtime.update { it.copy(codeLock = CodeLock(lastUsedStep = step)) }
    }

    /** No parent code: one kept from an earlier arming would still unblock. */
    suspend fun noParentCode() {
        secrets.put(Secret.TotpSecret, null)
    }

    /** The device admin kept in step with arming, from the app's start. */
    fun start() {
        // Disarmed (once the wait is over, or at once by a parent's code): the device admin goes
        // too, so uninstalling is allowed again, as the protection screen says. Checked at start as
        // well as on each change, so a disarm the app stopped before seeing through is finished,
        // and so is an arming left part-way (the admin given, then the app stopped mid-wizard).
        scope.launch {
            var starting = true
            settings.state.map { it.armed }.distinctUntilChanged().collect { armed ->
                val wasArmed = runtime.value.adminArmed
                if (armed && !wasArmed) runtime.update { it.copy(adminArmed = true) }
                if (!armed && (wasArmed || (starting && adminActive()))) {
                    runCatching { removeAdmin() }
                    runtime.update { it.copy(adminArmed = false) }
                }
                starting = false
            }
        }
    }
}
