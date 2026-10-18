package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.block.Slots
import com.thomaswcode.decrastination.calendar.SlotCalendar
import com.thomaswcode.decrastination.calendar.SlotCalendarDiff
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.TaskOverrides
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.overrides
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.core.withOverrides
import com.thomaswcode.decrastination.data.AndroidBackupFolder
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.Backups
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AndroidModelAlerts
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.Failures
import com.thomaswcode.decrastination.enrich.Failure
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.ChatReader
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.Enricher
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.InstructionReader
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.notify.AndroidTray
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notifier
import com.thomaswcode.decrastination.plan.PlanWatcher
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.protect.CodeLock
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.protect.Totp
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sources.anki.AnkiSource
import com.thomaswcode.decrastination.sources.gmail.GmailSource
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerApi
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSource
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import com.thomaswcode.decrastination.sources.teams.TeamsSource
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.widget.WidgetUpdater
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's singletons. The sync, the focus service, the widget and the screens all run in the
 * app's one process and share them, so every part sees the same stores.
 */
@OptIn(FlowPreview::class)
class AppGraph private constructor(context: Context) {
    val app: Context = context.applicationContext
    val clock: WallClock = DeviceClock(app)

    /** Work that outlives the screen or receiver that started it; a failure is logged, not fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(TAG, "Background work failed", error) })

    /**
     * Every store, made first, each with its version and its migrations ([Stores]): a file of an older
     * shape brought to this one as it's read. A settings file that can't be read at all starts armed
     * while the device admin is on, as only arming turns it on (IMP-P1-003).
     */
    val stores = Stores(app.filesDir, KeystoreCipher(), armedIfUnreadable = { Watchdog.isAdminActive(app) })
    val tasks = stores.tasks
    val settings = stores.settings
    val secrets = stores.secrets
    val runtime = stores.runtime
    val log = stores.log

    /** Every notification the app posts, held and counted by the rules (LR-07): the one place they're posted ([NoticeId] names them). */
    val notifier = Notifier(AndroidTray(app), runtime, settings, clock, scope)

    /** Every call to Claude, its holds and its record, and its alerts ([ModelGateway]). */
    val gateway = ModelGateway(settings, { secrets[Secret.AnthropicApiKey] }, runtime, tasks, clock, AndroidModelAlerts(app), scope) { EnrichWorker.retryAfter(app, it) }

    /** Your instructions about tasks, events and days, and what Claude read them as ([Instructions]). */
    val instructions = stores.instructions

    /** Your chat with Felix: what you said, his answers, and what became of his cards. */
    val chat = stores.chat

    /** Days with no school and half-days (PL-16), told to the chat or added in Settings. */
    val school = stores.school

    /** What Felix remembers (AI-21): facts, and standing instructions. */
    val memory = stores.memory

    /** The versions of its events the app has written to calendars, so a copy of one is known ([SlotCalendarDiff.isCopy]). */
    val calendarVersions = stores.calendarVersions

    /**
     * Who the student is, as every model call sends it (AI-05): from the settings, the tasks, the
     * timetable, the school calendar and what's [remembered].
     */
    fun profile(now: Long = clock.now(), remembered: com.thomaswcode.decrastination.core.MemoryState = memory.value): com.thomaswcode.decrastination.enrich.Profile.Block {
        val s = settings.value
        val state = tasks.value
        val zone = clock.zone()
        return com.thomaswcode.decrastination.enrich.Profile.Block(
            com.thomaswcode.decrastination.enrich.Profile.yearGroup(now, zone, s.yearGroupStart),
            com.thomaswcode.decrastination.enrich.Profile.block(s, state.tasks, state.timetable, school.value, remembered, now, zone),
        )
    }

    init {
        // Files that couldn't be read whole at this start: kept for Setup to say, till you've seen them.
        // On record before anything else runs, and only then is a bad file replaced by what was kept
        // of it: a start cut short before then finds it again, and says it (once: its copy is the same).
        val found = stores.problems
        if (found.isNotEmpty()) {
            found.forEach { Log.w(TAG, "${it.file} couldn't be read whole; set aside as ${it.copy}") }
            runBlocking {
                runCatching {
                    runtime.update { it.withUnreadable(found) }
                }.onSuccess { stores.keepRecovered() }
                    .onFailure { Log.w(TAG, "Couldn't keep what was found unreadable", it) }
            }
        }
        // A calibration from before 1.8.0, kept by a class's first timed piece: under its rule now
        // (IMP-P2-016), not only from the next weekly review.
        if (Calibrator.upgraded(runtime.value.calibration, log.value.completions) != null) {
            scope.launch { runtime.update { state -> Calibrator.upgraded(state.calibration, log.value.completions)?.let { state.copy(calibration = it) } ?: state } }
        }
    }

    /** Takes [seen] off Setup's list of files found unreadable. */
    suspend fun forgetUnreadable(seen: JsonStore.Unreadable) = runtime.update { it.seenUnreadable(seen) }

    val syncer = Syncer(
        tasks = tasks,
        settings = settings,
        sources = listOf(
            TeamsSource(app.contentResolver),
            PowerPlannerSource(PowerPlannerApi(UrlConnectionHttp()), secrets),
            GmailSource(secrets),
            AnkiSource(app),
        ),
        clock = clock,
        onFailure = { source, error -> Log.w(TAG, "Reading ${source.label} failed", error) },
        // What a card takes, per deck, as learned (DX-11).
        ankiRates = { runtime.value.calibration.ankiSecondsPerCard },
    )

    /** The calendar's busy time and per-day loads (Phase 5), as last read and judged. */
    @Volatile
    /** The calendar as the planner counts it, from its last read: a plan made before that read is dropped with it. */
    var calendarTime: com.thomaswcode.decrastination.learn.EventJudge.Time = com.thomaswcode.decrastination.learn.EventJudge.Time(emptyList(), emptyMap(), emptyList())
        set(value) {
            field = value
            focus.forgetPlan()
            calendarState.value = value
        }

    /** [calendarTime] as it changes, for a screen open while the calendar is read again. */
    val calendarState = kotlinx.coroutines.flow.MutableStateFlow(calendarTime)

    /**
     * The plan ([Planning]): made from the stores as they are, made again as they change, today's slot
     * starts kept, and the work done ahead after each. A new plan's hooks are the app's: the slot
     * alarms, the calendar, the widget.
     */
    val planning = Planning(
        tasks, settings, runtime, log, instructions, school, clock, scope, gateway,
        calendarTime = { calendarTime },
        calendarChanges = calendarState,
        sweep = { slots.sweep() },
        hooks = listOf(
            { made -> SlotAlarms.arm(app, made, clock.now()) },
            // The calendar once plans have been quiet a while ([toWrite]).
            { made -> toWrite.tryEmit(made); Unit },
            { _ -> WidgetUpdater.update(app) },
        ),
        profile = { profile(it) },
        draftAhead = { made, now -> felix.draftAhead(made, now) },
        onFailure = { Log.w(TAG, "Replanning failed", it) },
    )

    /** [Planning.plan], till its callers call it there. */
    fun plan(
        state: TaskState = tasks.value,
        settings: Settings = this.settings.value,
        now: Long = clock.now(),
        applied: List<Instruction> = instructions.value.applied,
        time: com.thomaswcode.decrastination.learn.EventJudge.Time = calendarTime,
    ): Plan = planning.plan(state, settings, now, applied, time)

    /** The blocker's state and decisions (block/Focus.kt). */
    val focus = Focus(
        tasks, settings, runtime, log, clock,
        onOwnTaskDone = { finishOwnTask(it) },
        // A school day by the school calendar (BL-02): a day off blocks from the weekend's time.
        schoolDay = { com.thomaswcode.decrastination.core.SchoolDays.kindOf(it, school.value) == com.thomaswcode.decrastination.core.DayKind.Weekday },
    ) { state, s, now -> plan(state, s, now) }

    /** [Planning.watcher], till its callers call it there. */
    val watcher: PlanWatcher get() = planning.watcher

    /**
     * The plan to write to the calendar, once plans have been quiet [CALENDAR_WRITE_QUIET_MS] (17 Oct):
     * a burst of changes (a chat answer's, one after another) is one write, not an event inserted and
     * changed again while the calendar's sync still has it in hand, which brought copies of it back.
     */
    private val toWrite = MutableSharedFlow<Plan>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Today's slots as they pass: begun, or missed (PL-13). */
    val slots = Slots(this)

    /** Time in AnkiDroid as work (NF-03, DX-11). */
    val anki = com.thomaswcode.decrastination.block.AnkiWatch(this)

    /** The voice of the app's messages (LR-03). */
    val felix = com.thomaswcode.decrastination.felix.Looks(this)

    /**
     * [made]'s next seven days written to the calendar you picked (NF-07). A slot you've moved there
     * is taken as your time for its task's next piece, where its deadline allows; else it's put back,
     * and you're told.
     */
    private suspend fun writeSlots(made: Plan) {
        clearOldCalendars()
        val calendarId = settings.value.calendarWriteId ?: return
        if (!SlotCalendar.allowed(app)) return
        val zone = clock.zone()
        // The same slots as last written, and nothing changed in the calendar since (AR-08): it isn't read again.
        val desired = com.thomaswcode.decrastination.calendar.SlotCalendarDiff.desired(made, zone, runtime.value.microPlans)
        if (!calendarTouched && lastWritten == (calendarId to desired)) return
        calendarTouched = false
        val byId = tasks.value.tasks.associateBy { it.id }
        val before = runtime.value.calendarWritten
        // A copy is one of the versions earlier writes made ([SlotCalendarDiff.isCopy]), never one of
        // this write's: an event of yours under a slot's title at its times isn't the app's.
        val known = calendarVersions.value.written.toSet()
        val written = try {
            withContext(Dispatchers.IO) {
                SlotCalendar.write(app, calendarId, made, zone, before, known, runtime.value.microPlans) { move ->
                    val task = move.desired.taskId?.let(byId::get)
                    when {
                        task == null -> "its task is no longer there"
                        Instructions.latePin(SlotCalendar.change(move, zone), task, zone, clock.now()) -> "it's due before then"
                        else -> null
                    }
                }
            }
        } catch (e: Throwable) {
            // Not written: the calendar's read again at the next plan, the change seen before it with it.
            calendarTouched = true
            throw e
        }
        // Each event's version remembered once it's written (not a move of yours taken: its event is
        // at your time), so a copy the calendar's sync makes of it is known at the next write. Not
        // one an event of yours already had (#48's third review): remembered, the next write would
        // take yours for a copy.
        val moved = written.taken.map { it.desired.uri }.toSet()
        runCatching {
            val versions = desired.filter { d -> d.uri !in moved }.map { d -> SlotCalendarDiff.version(calendarId, d.title, d.start, d.end) }.filterNot { it in written.yours }
            calendarVersions.update { it.copy(written = SlotCalendarDiff.remembered(it.written, versions)) }
        }.onFailure { Log.w(TAG, "Remembering what was written to the calendar failed", it) }
        // A move taken counts as written only once its instruction is in: stopped before, the next
        // write finds it a move again, rather than putting it back.
        val held = SlotCalendarDiff.pending(written.starts, before, written.taken)
        if (held != before) runtime.update { it.copy(calendarWritten = held) }
        written.refused.forEach { (move, why) -> SlotCalendar.sayRefused(app, move, zone, why) }
        lastWritten = calendarId to desired
        for (move in written.taken) {
            val taskId = move.desired.taskId ?: continue
            addOwnChange(java.util.UUID.randomUUID().toString(), "Moved in the calendar", About(taskId = taskId, taskTitle = byId[taskId]?.title), SlotCalendar.change(move, zone))
            runtime.update { it.copy(calendarWritten = it.calendarWritten + (move.desired.uri to move.existing.start)) }
        }
    }

    /** The app's events taken out of calendars no longer written to, each kept on the list till that's done. */
    private suspend fun clearOldCalendars() {
        val waiting = runtime.value.calendarsToClear
        if (waiting.isEmpty()) return
        val versions = calendarVersions.value.written.toSet()
        val cleared = withContext(Dispatchers.IO) { waiting.filter { SlotCalendar.clear(app, it, versions) } }
        if (cleared.isNotEmpty()) runtime.update { it.copy(calendarsToClear = it.calendarsToClear - cleared.toSet()) }
    }

    /** Applies a settings change: at once, or, once armed, pending if it loosens blocking. */
    /**
     * Held while the settings and their pending changes are changed: two files, written in turn,
     * so each change finishes before the next reads them.
     */
    private val changing = Mutex()

    /**
     * Changes the settings as [change] says, from what's been asked for (the settings with what's
     * waiting applied), so a change to one field leaves the others' waiting changes be.
     */
    suspend fun changeSettings(change: (Settings) -> Settings) = changing.withLock {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDue(force = true)
        val state = runtime.value
        val proposed = change(SettingsChanges.requested(settings.value, state.pending))
        val outcome = SettingsChanges.propose(settings.value, proposed, state.pending, clock.now()) { java.util.UUID.randomUUID().toString() }
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
    suspend fun applyReviewChanges(changes: List<com.thomaswcode.decrastination.learn.ReviewInput.Change>) = changing.withLock {
        applyDue(force = true)
        val state = runtime.value
        // The settings it names: one it sets to the value already asked for applies now all the same.
        val bounds = com.thomaswcode.decrastination.learn.ReviewInput.bounds(settings.value)
        val fields = changes.map { it.setting }.filter { it in bounds }.map(com.thomaswcode.decrastination.learn.ReviewInput::fieldOf).toSet()
        val outcome = SettingsChanges.atOnce(settings.value, state.pending, fields) { com.thomaswcode.decrastination.learn.ReviewInput.apply(it, changes) }
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
            is CodeLock.Result.Locked -> "Too many wrong codes: try again ${com.thomaswcode.decrastination.ui.Format.at(result.until, now, clock.zone())}"
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
     * Reads every source now, as a job (it keeps its network after the screen that asked has
     * gone). With [teams], it first asks the Teams widget to sync Teams itself, which takes over
     * the screen for a minute, so only on a tap (Q21); what that finds comes back through the
     * widget's change notifications.
     */
    fun refreshAll(teams: Boolean) {
        if (teams) {
            scope.launch {
                // Refused (its service off, already syncing): say so where the widget shows it.
                // The next read of Teams replaces the note with the widget's own state.
                requestTeamsSync()?.let { reason ->
                    tasks.update { state -> state.copy(sources = state.sources + (Source.Teams to state.status(Source.Teams).copy(note = reason))) }
                }
            }
        }
        SyncWorker.syncNow(app)
    }

    /** Asks the Teams widget to sync Teams. Returns why it didn't start, or null if it did. */
    suspend fun requestTeamsSync(): String? = withContext(Dispatchers.IO) {
        val result = runCatching { TeamsProvider.call(app.contentResolver, TeamsProvider.METHOD_REQUEST_SYNC) }.getOrNull()
        when {
            result?.getBoolean(TeamsProvider.RESULT_STARTED) == true -> null
            result == null -> "The Teams widget didn't answer"
            else -> when (result.getString(TeamsProvider.RESULT_REASON)) {
                "service_off" -> "The Teams widget's sync service is off"
                "busy" -> TEAMS_BUSY
                else -> "The Teams widget didn't start a sync"
            }
        }
    }

    /**
     * The Teams widget announces every change to its list: a sync, or a hand-in it saw while
     * Teams was open. Reading it needs no network, so it's read here, once the changes stop, and
     * Anki with it: its homework decks come from Teams' assignments (Anki is read after Teams).
     */
    private val teamsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The calendar announces each change; it's read again once they stop. */
    private val calendarChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var calendarWatched = false

    /**
     * Whether the calendar has changed since the app last read the one it writes to (AR-08): till it
     * has, a plan that would write what was written last ([lastWritten]) doesn't read it again. A
     * change there (a slot moved or deleted by hand, or the app's own write) has it read again.
     */
    @Volatile
    private var calendarTouched = true

    /** The calendar last written to and the slots written there (AR-08). */
    @Volatile
    private var lastWritten: Pair<Long, List<com.thomaswcode.decrastination.calendar.SlotCalendarDiff.Desired>>? = null

    /**
     * Watches the calendar for changes, once it may be read: at start, and at each read of it
     * after (so allowing it later, in Setup or Android's settings, starts the watching too).
     */
    @Synchronized
    fun watchCalendar() {
        if (calendarWatched || !CalendarTime.allowed(app)) return
        runCatching {
            app.contentResolver.registerContentObserver(
                android.provider.CalendarContract.Events.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        calendarTouched = true
                        calendarChanged.tryEmit(Unit)
                    }
                },
            )
        }.onSuccess { calendarWatched = true }.onFailure { Log.w(TAG, "Can't watch the calendar", it) }
    }

    init {
        // Disarmed (once the wait is over, or at once by a parent's code): the device admin goes
        // too, so uninstalling is allowed again, as the protection screen says. Checked at start as
        // well as on each change, so a disarm the app stopped before seeing through is finished,
        // and so is an arming left part-way (the admin given, then the app stopped mid-wizard).
        scope.launch {
            var starting = true
            settings.state.map { it.armed }.distinctUntilChanged().collect { armed ->
                val wasArmed = runtime.value.adminArmed
                if (armed && !wasArmed) runtime.update { it.copy(adminArmed = true) }
                if (!armed && (wasArmed || (starting && Watchdog.isAdminActive(app)))) {
                    runCatching { app.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(app)) }
                    runtime.update { it.copy(adminArmed = false) }
                }
                starting = false
            }
        }
        // Work a source confirms done earns free time, is logged and asked about.
        syncer.addListener {
            settleCompletions()
            CalendarTime.refresh(app)
        }
        // Any a stop left ungiven: slots being kept, photo checks and completions; and a day with nothing to do's free time (BL-07).
        scope.launch {
            focus.finishKeeps()
            focus.finishPhotos()
            settleCompletions()
            // Only then is the day judged empty: a slot kept as the app stopped is work done.
            runCatching { focus.grantAllowanceIfDue() }.onFailure { Log.w(TAG, "Giving the day's free time failed", it) }
        }
        // "How was it?" questions kept while notifications were off, once they're on.
        syncer.addAfterEverySync { Assessment.askLater(app) }
        // A task back after aging out of the list (or new) takes the overrides its instructions give.
        syncer.addAfterEverySync { layTaskOverrides() }
        // Your own tasks long done go, as a source's do.
        syncer.addAfterEverySync { pruneOwn() }
        // A question of yours still waiting (Claude was off, resting, or the app stopped): tried again.
        syncer.addAfterEverySync { if (chat.value.unanswered != null) answerChat() }
        // One of your own whose steps are all done (ticked, or kept in their slots) is finished; and one
        // done by its tick (NF-06) given steps of yours by the chat (16 Oct).
        scope.launch {
            tasks.state.map { s -> s.tasks.filter { (it.source == Source.Own || it.tickDone) && it.isOpen && it.subSteps.isNotEmpty() && it.subSteps.all { st -> st.done } }.map { it.id } }
                .distinctUntilChanged().collect { ids -> ids.forEach { finishOwnTask(it) } }
        }
        // Instructions left waiting for Claude, once it can be asked (a new month under the cap
        // changes nothing it watches).
        syncer.addAfterEverySync { if (instructions.value.instructions.any { it.state == InstructionStatus.Reading }) readInstructions() }
        // What's new or changed, even in place, is enriched.
        syncer.addAfterEverySync {
            val modelOn = gateway.available()
            val modelOff = gateway.claudeKey() == null
            if (tasks.value.tasks.any { Enrichments.jobFor(it) != null && Enrichments.stale(it, modelOn, RuleEnricher.BY, modelOff) }) EnrichWorker.enqueue(app)
        }
        scope.launch { CalendarTime.refresh(app) }
        scope.launch(Dispatchers.IO) { runCatching { sweepPhotos() } }
        // A change in the calendar: read again, and the plan made again, so a slot you've moved or
        // deleted in the calendar written to (which isn't read) is seen (NF-07). The app's own write
        // brings one replan more, which writes nothing.
        scope.launch {
            calendarChanged.debounce(CALENDAR_QUIET_MS).collect {
                CalendarTime.refresh(app)
                watcher.poke()
            }
        }
        // The calendar written to, or those not read, changed (NF-07, DX-13): what was written to
        // one no longer is goes from it, and the calendar's read again.
        scope.launch {
            settings.state.map { it.calendarWriteId }.distinctUntilChanged().collect { id ->
                val was = runtime.value.calendarWrittenTo
                if (was == id) return@collect
                // The old one kept on the list to clear till it is: without the permission now, or
                // should the provider fail, it's tried again at the next replan.
                runtime.update { state ->
                    state.copy(calendarWrittenTo = id, calendarWritten = emptyMap(), calendarsToClear = (state.calendarsToClear + listOfNotNull(was)).distinct() - listOfNotNull(id).toSet())
                }
                clearOldCalendars()
                watcher.poke()
            }
        }
        scope.launch {
            settings.state.map { it.calendarWriteId to it.calendarsExcluded }.distinctUntilChanged().drop(1).collect { CalendarTime.refresh(app) }
        }
        // Anki's deadline or textbook changed (saved, or a waiting change fallen due): its tasks are
        // made with them, so it's read again now.
        scope.launch {
            settings.state.map { it.ankiDeadlineMin to it.ankiTextbook }.distinctUntilChanged().drop(1).collect { SyncWorker.syncNow(app, setOf(Source.Anki)) }
        }
        // Switched off: the rules go over what the model read, as its reading goes with it.
        scope.launch {
            settings.state.map { it.aiEnabled && it.aiKeyActive }.distinctUntilChanged().drop(1).collect { on -> if (!on) EnrichWorker.enqueue(app) }
        }
        // Able to be asked again (switched on, its key put to use, a higher cap or another exchange
        // rate saved, its rest after a failure over), the model goes over what only the rules have seen.
        scope.launch {
            combine(settings.state, runtime.state) { _, _ -> gateway.available() }.distinctUntilChanged().drop(1).collect { on ->
                if (on) {
                    EnrichWorker.enqueue(app)
                    readInstructions()
                    answerChat()
                }
            }
        }
        // Instructions left waiting for Claude (off, resting, or the app stopped mid-read); and the
        // tasks' overrides made what the applied ones say, should a stop have come between the two.
        scope.launch {
            layTaskOverrides()
            readInstructions()
            // A question of yours the app stopped before answering, once the calendar's read, as it's
            // told the fortnight's events.
            if (chat.value.unanswered != null) {
                runCatching { CalendarTime.refresh(app) }.onFailure { Log.w(TAG, "Reading the calendar failed", it) }
                answerChat()
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                TeamsProvider.root,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        teamsChanged.tryEmit(Unit)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Can't watch the Teams widget", it) }
        scope.launch {
            teamsChanged.debounce(TEAMS_QUIET_MS).collect { syncer.sync(setOf(Source.Teams, Source.Anki)) }
        }
        // The calendar written once plans have been quiet a while.
        scope.launch {
            toWrite.debounce(CALENDAR_WRITE_QUIET_MS).collect { made -> runCatching { writeSlots(made) }.onFailure { Log.w(TAG, "Writing the calendar failed", it) } }
        }
        // A key problem standing from before, whose alert couldn't be shown then.
        gateway.reconcileAlerts()
        // Dropped plans' warnings kept in step with the tasks, from the start.
        scope.launch {
            tasks.state.debounce(WIDGET_DEBOUNCE_MS).collect { state ->
                runCatching { gateway.droppedAlerts(state.tasks) }.onFailure { Log.w(TAG, "Dropped-plan warnings failed", it) }
            }
        }
        // Slots whose end went by with the phone off, as if their alarms had rung; then the plan, made
        // again as it changes, from now on (PL-13).
        scope.launch {
            runCatching { slots.sweep() }.onFailure { Log.w(TAG, "Looking at missed slots failed", it) }
            watcher.start()
        }
        // The files older shapes came from, a week after their migration, at a start that's got this far.
        scope.launch(Dispatchers.IO) { runCatching { stores.letGoOfOldShapes() }.onFailure { Log.w(TAG, "Letting go of old shapes failed", it) } }
        // Redraw the widget whenever what it shows may have changed.
        scope.launch {
            combine(tasks.state, settings.state, runtime.state) { _, _, _ -> }.drop(1).debounce(WIDGET_DEBOUNCE_MS).collect {
                runCatching { WidgetUpdater.update(app) }.onFailure { Log.w(TAG, "Widget update failed", it) }
            }
        }
    }

    /**
     * The completions syncs saved: free time and the log, then "How was it?" for finished homework
     * and revision (asked, or kept till notifications show), and only then off the queue, so a stop
     * part-way loses neither.
     */
    private suspend fun settleCompletions() {
        focus.rewardCompletions { completed ->
            runCatching { Assessment.ask(app, completed) }.onFailure { Log.w(TAG, "Couldn't ask how the work went", it) }
        }
    }

    /** The rules' enrichment: always there, and the fallback for the model. */
    private val rules = RuleEnricher()

    /** Backups and resets ([BackupService]): made when first asked for, as it takes the instructions' lock. */
    val backups: BackupService by lazy {
        BackupService(
            stores, clock, scope, AndroidBackupFolder(app.contentResolver), File(app.filesDir, RESET_COPIES), BuildConfig.VERSION_NAME,
            changeSettings = { change -> changeSettings(change) },
            applying = applying,
            layInstructions = { layInstructions() },
            layTaskOverrides = { layTaskOverrides() },
            schedule = { Daily.schedule(app) },
            reread = {
                CalendarTime.refresh(app)
                Briefing.replanToday(app)
            },
            cancelFelix = { com.thomaswcode.decrastination.felix.FelixNotifications.cancel(app) },
        )
    }

    /** Where tasks' photos are kept (AI-23): the app's own files, one a task. */
    private val photos: File get() = File(app.filesDir, "photos")

    /**
     * [jpeg], a photo of [taskId]'s work (AI-23), kept with it (in place of one before) and read
     * with it: its reading redone now, where it's one the model reads.
     */
    suspend fun attachPhoto(taskId: String, jpeg: ByteArray) {
        val file = File(photos, taskId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
        withContext(Dispatchers.IO) {
            photos.mkdirs()
            file.writeBytes(jpeg)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }
        tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(photo = com.thomaswcode.decrastination.core.TaskPhoto(file.path, hash, clock.now())) else it }) }
        scope.launch { runCatching { enrichNow(only = taskId) }.onFailure { Log.w(TAG, "Reading a task with its photo failed", it) } }
    }

    /** [jpeg] read into tasks of your own to check (NF-01): the reading, or why there's none. */
    suspend fun readPhotoTasks(jpeg: ByteArray): Pair<com.thomaswcode.decrastination.enrich.PhotoTasks.Reading?, String?> {
        val reader = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.PhotoToTask)?.let { com.thomaswcode.decrastination.enrich.PhotoTaskReader(it, profile = profile()) } ?: return null to "Claude is off: add it with New task."
        val now = clock.now()
        return when (val called = gateway.call(interactive = true, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.PhotoToTask, "a photo of homework") { r: com.thomaswcode.decrastination.enrich.PhotoTaskReader.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: com.thomaswcode.decrastination.enrich.PhotoTaskReader.Result -> r.costUsd to r.refused }) { reader.read(jpeg, now, clock.zone()) }) {
            is Called.Held -> null to "Claude can't be asked now: add it with New task."
            is Called.Failed -> null to "The photo couldn't be read (${called.error.message ?: "no connection"})."
            is Called.Answered -> called.value.reading?.let { it to null } ?: (null to if (called.value.refused) "Claude declined to read it." else "Claude couldn't read the photo: try a clearer one.")
        }
    }

    /**
     * A photo of homework read into tasks (NF-01): held here, not by a screen, so turning the phone or
     * leaving Work while the paid call runs loses nothing. [busy] while it's read; then what it read,
     * with the photo for the tasks made from it, or why not.
     */
    class PhotoReading(val busy: Boolean, val reading: com.thomaswcode.decrastination.enrich.PhotoTasks.Reading? = null, val jpeg: ByteArray? = null, val why: String? = null)

    /** The photo of homework being read, or read and not yet seen through (NF-01). */
    val photoReading = kotlinx.coroutines.flow.MutableStateFlow<PhotoReading?>(null)

    /** [file], a photo of homework just taken, read into tasks in the app's scope (NF-01), [photoReading] saying how it goes; the file let go. */
    fun readPhotoOf(file: java.io.File) {
        if (photoReading.value?.busy == true) return
        photoReading.value = PhotoReading(busy = true)
        scope.launch {
            val done = runCatching {
                val jpeg = withContext(Dispatchers.IO) { com.thomaswcode.decrastination.block.PhotoChecks.shrink(file) }
                if (jpeg == null) {
                    PhotoReading(busy = false, why = "Couldn't read the photo.")
                } else {
                    val (got, why) = readPhotoTasks(jpeg)
                    PhotoReading(busy = false, reading = got, jpeg = jpeg.takeIf { got != null }, why = why)
                }
            }.getOrElse { PhotoReading(busy = false, why = "The photo couldn't be read (${it.message ?: "no connection"}).") }
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            photoReading.value = done
        }
    }

    /** The photos no task holds any more let go (AI-23): a task done and gone takes its photo with it. */
    private fun sweepPhotos() {
        // A task the chat deleted keeps its photo while its Undo can bring it back: no other act's, nor one made again since.
        val messages = chat.value.messages
        val undoable = messages.flatMap { m ->
            m.acts.filter { it.action.type == com.thomaswcode.decrastination.core.ChatAction.Type.Delete && com.thomaswcode.decrastination.core.ChatActs.canUndo(it, tasks.value.tasks, settings.value, messages) }
                .mapNotNull { it.was?.photo?.path }
        }
        val held = tasks.value.tasks.mapNotNull { it.photo?.path }.toSet() + undoable
        photos.listFiles()?.filter { it.path !in held }?.forEach { it.delete() }
    }

    /**
     * Enriches every task that's new or changed since it was last enriched, and, while the model is
     * on, those only the rules have seen; soonest due first. The model does it while it's on and
     * the month's spend leaves room under the cap, at most [MAX_MODEL_CALLS] a run; the rules do
     * the rest, and stand in for a call that fails (the model is then left alone for the run) or
     * that the model declines (recorded as the model's, so it isn't asked again). With [force], [only]
     * is read again by the model whatever it was read by (AC-06), its reading kept unless the model
     * gives one; whether it did. [byModel] false, the rules alone, whatever's on.
     */
    suspend fun enrichNow(byModel: Boolean = true, only: String? = null, force: Boolean = false): Boolean {
        // Not while it rests after a failed call (no network, a bad key), nor with no room under the
        // cap: then what only the rules have seen isn't due for it.
        var enricher: Enricher? = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Email).takeIf { byModel }
            ?.let { ClaudeEnricher(it, clock.zone(), profile = { profile() }, decks = { tasks.value.deckTopics }) }?.takeIf { gateway.available() }
        var calls = 0
        var decksChanged = false
        // A task whose call the API wouldn't take as asked (AC-04): the rules' till it changes.
        fun refused(task: TaskItem) = runtime.value.enrichFailed[task.id] == Enrichments.inputHash(task)
        fun due(task: TaskItem) = (force && task.id == only) || Enrichments.stale(task, enricher != null && !refused(task), RuleEnricher.BY, modelOff = gateway.claudeKey() == null)
        // Whether [only]'s reading laid was the model's (for [force]).
        var readByModel = false
        val candidates = tasks.value.tasks
            // [only]: that task alone (the debug `enrich-task`), so nothing else is sent anywhere.
            .filter { only == null || it.id == only }
            .mapNotNull { task -> Enrichments.jobFor(task)?.let { task to it } }
            .filter { (task, _) -> due(task) }
            // Never read first, then the oldest prompts' readings (AC-06), soonest due first.
            .sortedWith(compareBy(Enrichments.NEED) { it.first })
        for ((snapshot, _) in candidates) {
            // Read afresh: a sync since the run began may have closed or changed it.
            val task = tasks.value.tasks.firstOrNull { it.id == snapshot.id } ?: continue
            val job = Enrichments.jobFor(task) ?: continue
            if (!due(task)) continue
            // Whether the model gave this one's reading, not the rules standing in.
            var fromModel = false
            // Stale only by the prompts' version (AC-06): its reading stays unless the model reads it now.
            val onlyVersion = Enrichments.staleOnlyByVersion(task, RuleEnricher.BY)
            val now = clock.now()
            // Switched off (or its key removed) while this runs: nothing more is sent.
            if (gateway.claudeKey() == null) enricher = null
            val model = enricher?.takeIf { calls < MAX_MODEL_CALLS && !refused(task) }
            var enrichment = if (model != null) {
                // Held now (switched off, resting after a failed call, no room under the cap): the
                // rules do the rest of the run.
                when (val called = gateway.call(interactive = false, task = task, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.jobOf(job), task.title) { r: Enricher.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r) }, cost = { r: Enricher.Result -> r.costUsd to r.refused }) { model.enrich(task, job, now) }) {
                    is Called.Held -> {
                        enricher = null
                        null
                    }
                    is Called.Failed -> {
                        calls++
                        Log.w(TAG, "The model's enrichment failed; the rules stand in", called.error)
                        // Only a request it won't take leaves the others to be asked.
                        if (Failures.classify(called.error) != Failure.BadRequest) enricher = null
                        null
                    }
                    // Declined, or no answer it could read: the rules' say, under the model's name.
                    is Called.Answered -> {
                        calls++
                        val first = called.value
                        // A check its answer failed (AI-01): put back once, as a call of its own (counted
                        // under the cap, held by a rest); the first's reading stands if that doesn't come back.
                        val again = first.repair?.takeIf { calls < MAX_MODEL_CALLS }?.let { repair ->
                            when (val second = gateway.call(interactive = false, task = task, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.jobOf(job), task.title) { r: Enricher.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r) }, cost = { r: Enricher.Result -> r.costUsd to r.refused }) { model.repair(task, job, now, repair) }) {
                                is Called.Answered -> second.value.also { calls++ }
                                is Called.Failed -> {
                                    calls++
                                    if (Failures.classify(second.error) != Failure.BadRequest) enricher = null
                                    null
                                }
                                is Called.Held -> {
                                    enricher = null
                                    null
                                }
                            }
                        }
                        // The rules' under the model's name is the model's answer by these prompts (AC-06), not asked for again till they change.
                        (again?.enrichment ?: first.enrichment)?.also { fromModel = true }
                            ?: when {
                                force -> null
                                // Declined by these prompts: the reading there kept, as theirs now, so it isn't asked for again till they change.
                                onlyVersion -> task.enrichment?.copy(promptVersion = com.thomaswcode.decrastination.enrich.Prompts.VERSION)
                                else -> rules.enrich(task, job, now).enrichment?.copy(by = model.by, promptVersion = com.thomaswcode.decrastination.enrich.Prompts.VERSION)
                            }
                    }
                }
            } else {
                null
            }
            // Read again on asking, or past this run's calls with a reading only older prompts made: the
            // reading there stands unless the model gave one.
            if (enrichment == null && (force || onlyVersion)) continue
            if (enrichment == null) enrichment = rules.enrich(task, job, now).enrichment
            val made = enrichment ?: continue
            // Laid only over the task as it was read (changed during the call, the next run does it).
            var laid = false
            tasks.update { state ->
                state.copy(
                    tasks = state.tasks.map {
                        if (it.id == task.id && Enrichments.inputHash(it) == made.inputHash) it.withEnrichment(made).also { laid = true } else it
                    },
                )
            }
            if (!laid) continue
            if (fromModel && task.id == only) readByModel = true
            // A question it couldn't settle from the text (AI-10): Felix puts it. One waiting from an
            // older reading goes, asked again as this one has it, or settled by it.
            felix.queueQuestion(task.id, made.question, now)
            // A plan of the model's dropped (out of range, not adding up) is said once it's laid,
            // by droppedAlerts, as the tasks change.
            // Its deck tasks take their sections and due date from it.
            val after = task.withEnrichment(made)
            val topics = tasks.value.deckTopics
            val sections = AnkiRules.sectionsOf(after, topics)
            if (sections != AnkiRules.sectionsOf(task, topics) || (sections.isNotEmpty() && after.dueAt != task.dueAt)) decksChanged = true
        }
        // Sections the deck pattern missed, or a deadline moved: their deck tasks come from reading Anki again, now.
        if (decksChanged) SyncWorker.syncNow(app, setOf(Source.Anki))
        return readByModel
    }

    /** How a read again on asking went (AC-06). */
    enum class ReadAgain { Read, NotNow, NoReading }

    /**
     * [taskId] read again by Claude as it is now (AC-06, the sheet's *Read again with Claude*), by
     * these prompts whatever it was read by, its steps shown it to keep by name (AI-08). The reading
     * there stands till the new one comes: [ReadAgain.NoReading] where none did (the call failed or
     * was held, Claude declined, the task changed meanwhile); [ReadAgain.NotNow], with nothing sent,
     * while Claude can't be asked (off, resting, at the cap).
     */
    suspend fun readAgain(taskId: String): ReadAgain = withContext(NonCancellable) {
        if (gateway.claudeKey() == null || !gateway.available()) return@withContext ReadAgain.NotNow
        if (enrichNow(only = taskId, force = true)) ReadAgain.Read else ReadAgain.NoReading
    }

    /** Said on a message whose answer you let go (AC-08). */
    private val CANCELLED = "Cancelled"

    /**
     * What's been learned about [key] (`Kind|class`) forgotten (LR-06): its figure goes now, the
     * kind's standing for it, and its work so far teaches nothing at the reviews after.
     */
    suspend fun forgetCalibration(key: String) {
        val now = clock.now()
        runtime.update { s -> s.calibration.let { c -> s.copy(calibration = c.copy(multipliers = c.multipliers - key, timed = c.timed - key, forgotten = c.forgotten + (key to now))) } }
    }

    /**
     * [change], one of [review]'s, put back (LR-01): its setting to its value before, as a change of
     * yours (once armed, one that loosens waits as yours do), while it's still what the review left.
     * What happened, in words.
     */
    suspend fun undoReviewChange(review: com.thomaswcode.decrastination.data.WeeklyReview, change: com.thomaswcode.decrastination.learn.ReviewInput.Change): String {
        val before = review.settingsBefore[change.setting] ?: return "What it was before isn't known."
        if (com.thomaswcode.decrastination.learn.ReviewInput.valueOf(settings.value, change.setting) != change.value) return "It's been changed since: left as it is."
        // One end of a school day's hours back alone can meet the other: then it can't be.
        if (!com.thomaswcode.decrastination.data.SettingsLimits.window(com.thomaswcode.decrastination.learn.ReviewInput.set(settings.value, change.setting, before).weekdayHours, overnight = false)) return "It can't go back on its own: the day's hours would end before they start. Change them in Settings."
        changeSettings { com.thomaswcode.decrastination.learn.ReviewInput.set(it, change.setting, before) }
        val now = com.thomaswcode.decrastination.learn.ReviewInput.valueOf(settings.value, change.setting)
        val label = com.thomaswcode.decrastination.learn.ReviewInput.label(change.setting)
        return if (now == before) "$label is back to ${com.thomaswcode.decrastination.learn.ReviewInput.valueText(change.setting, before)}." else "$label back to ${com.thomaswcode.decrastination.learn.ReviewInput.valueText(change.setting, before)} waits, as a change that loosens does once armed."
    }

    /** [review]'s tidy of what's remembered undone (LR-01): its facts as they were, while they're still as it left them. What happened, in words. */
    suspend fun undoTidy(review: com.thomaswcode.decrastination.data.WeeklyReview): String {
        val tidy = review.memoryTidy ?: return "That review tidied nothing."
        var done = false
        memory.update { state -> if (state.facts == tidy.after) state.copy(facts = tidy.before).also { done = true } else state }
        return if (done) "What's remembered is back as it was before the tidy." else "What's remembered has changed since: left as it is."
    }

    /**
     * The week's check-in asked by Felix (LR-02): its first question, unless the week has its check-in
     * already, or his notifications can't be shown or it's quiet hours (false then, and the form's
     * reminder goes instead).
     */
    suspend fun askCheckIn(): Boolean {
        val now = clock.now()
        val week = com.thomaswcode.decrastination.learn.Daily.checkInWeek(now, clock.zone(), settings.value.checkInMin)
        if (log.value.checkIns.any { it.weekOf == week }) return true
        if (!notifier.shown(com.thomaswcode.decrastination.notify.Channels.FELIX)) return false
        // In quiet hours the form's reminder goes instead, held as the routine ones are till the morning.
        if (com.thomaswcode.decrastination.block.BlockPolicy.isQuiet(now, clock.zone(), settings.value)) return false
        val draft = com.thomaswcode.decrastination.felix.CheckInFlow.Draft(week)
        runtime.update { it.copy(checkInDraft = draft) }
        askNext(draft, now)
        return true
    }

    /** [draft]'s question as Felix's message, with its replies: on record, so its reply comes back here. */
    private suspend fun askNext(draft: com.thomaswcode.decrastination.felix.CheckInFlow.Draft, now: Long, again: Boolean = false) {
        val flow = com.thomaswcode.decrastination.felix.CheckInFlow
        val text = (if (again) "A number from 1 to 5: " else "") + flow.question(draft)
        val record = com.thomaswcode.decrastination.data.FelixRecord(java.util.UUID.randomUUID().toString(), now, com.thomaswcode.decrastination.felix.FelixPrompts.Reason.CheckIn.name, text, flow.replies(draft, log.value, clock.zone()))
        log.update { it.copy(felix = it.felix + record).trimmed(now) }
        com.thomaswcode.decrastination.felix.FelixNotifications.post(app, record.id, settings.value.felixName, text, record.replies)
    }

    /**
     * [words], the answer to the check-in question [record] asked (LR-02): kept, and the next asked;
     * after the last, the week's check-in kept and its review begun. The form filled meanwhile, or the
     * week moved on, ends it.
     */
    private suspend fun checkInReply(record: com.thomaswcode.decrastination.data.FelixRecord, words: String): Boolean {
        val now = clock.now()
        val flow = com.thomaswcode.decrastination.felix.CheckInFlow
        log.update { l -> l.copy(felix = l.felix.map { if (it.id == record.id) it.copy(replyText = words, repliedAt = now) else it }) }
        val draft = runtime.value.checkInDraft
        val week = com.thomaswcode.decrastination.learn.Daily.checkInWeek(now, clock.zone(), settings.value.checkInMin)
        if (draft == null || draft.week != week || log.value.checkIns.any { it.weekOf == draft.week }) {
            runtime.update { it.copy(checkInDraft = null) }
            com.thomaswcode.decrastination.felix.FelixNotifications.cancel(app)
            return true
        }
        val next = flow.answered(draft, words) ?: run {
            askNext(draft, now, again = true)
            return true
        }
        if (!flow.done(next)) {
            runtime.update { it.copy(checkInDraft = next) }
            askNext(next, now)
            return true
        }
        var added = false
        log.update { l -> if (l.checkIns.any { it.weekOf == next.week }) l else l.copy(checkIns = l.checkIns + flow.checkIn(next, now)).trimmed(now).also { added = true } }
        runtime.update { it.copy(checkInDraft = null) }
        com.thomaswcode.decrastination.felix.FelixNotifications.post(app, record.id, settings.value.felixName, "Thanks: the week's review comes next.", emptyList())
        // Its review, unless the week has had it (the evening's ran first).
        if (added) com.thomaswcode.decrastination.learn.ReviewWorker.enqueue(app, ifDue = true)
        return true
    }

    /**
     * Felix's check-in let go (LR-02), the form having taken the week's answers: its answers so far,
     * and his question while it's the last thing he said.
     */
    suspend fun endCheckInFlow() {
        if (runtime.value.checkInDraft == null) return
        runtime.update { it.copy(checkInDraft = null) }
        if (log.value.felix.lastOrNull()?.reason == com.thomaswcode.decrastination.felix.FelixPrompts.Reason.CheckIn.name) com.thomaswcode.decrastination.felix.FelixNotifications.cancel(app)
    }

    /** Where a reset's copy goes when Documents can't take it. */
    private val RESET_COPIES = "backups"

    /**
     * The [week]'s summary for home (NF-02), written by the model after its review and kept with it;
     * then Felix's "The week's summary is ready" opens it in an email to send. Nothing while Claude
     * can't be asked, or the week has no review. The summary, or null.
     */
    suspend fun writeSummary(week: String): String? {
        val client = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Summary) ?: return null
        if (log.value.reviews.none { it.week == week }) return null
        val zone = clock.zone()
        val input = com.thomaswcode.decrastination.learn.Summary.input(log.value, instructions.value.applied, week, zone)
        val writer = com.thomaswcode.decrastination.enrich.SummaryWriter(client)
        val called = gateway.call(
            interactive = false,
            record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Summary, "the week's summary") { r: com.thomaswcode.decrastination.enrich.SummaryWriter.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.text == null) },
            cost = { r: com.thomaswcode.decrastination.enrich.SummaryWriter.Result -> r.costUsd to r.refused },
        ) { at -> writer.write(profile(at).text, input) }
        val text = (called as? Called.Answered)?.value?.text ?: return null
        log.update { l -> l.copy(reviews = l.reviews.map { if (it.week == week) it.copy(summary = text) else it }) }
        val now = clock.now()
        val record = com.thomaswcode.decrastination.data.FelixRecord(java.util.UUID.randomUUID().toString(), now, com.thomaswcode.decrastination.felix.FelixPrompts.Reason.Summary.name, SUMMARY_READY)
        log.update { it.copy(felix = it.felix + record).trimmed(now) }
        // In quiet hours on the week's channel, held with the routine ones till the morning (LR-07).
        val quiet = com.thomaswcode.decrastination.block.BlockPolicy.isQuiet(now, zone, settings.value)
        notifier.post(
            NoticeId.Summary,
            com.thomaswcode.decrastination.notify.Notify.builder(app, if (quiet) com.thomaswcode.decrastination.notify.Channels.WEEKLY else com.thomaswcode.decrastination.notify.Channels.FELIX)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle(settings.value.felixName)
                .setContentText(SUMMARY_READY)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$SUMMARY_READY\n\n$text"))
                // Through the Reviews page, which opens the email: a tap held through quiet hours is the app's own.
                .let { com.thomaswcode.decrastination.notify.Notify.opening(it, app, NoticeId.Summary.code(), android.content.Intent(app, com.thomaswcode.decrastination.ui.ReviewsActivity::class.java).putExtra(com.thomaswcode.decrastination.ui.ReviewsActivity.EXTRA_SEND, week).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .setAutoCancel(true)
                .build(),
        )
        return text
    }

    /** Felix's word that the summary's ready (NF-02). */
    private val SUMMARY_READY = "The week's summary is ready; send it?"

    /** Held while instructions are read, so each is read once. */
    private val readingInstructions = Mutex()

    /**
     * Writes down a new instruction about [about] and has Claude read it: applied once it's read
     * (your call, 16 Oct), and taken back under Your changes.
     */
    suspend fun addInstruction(text: String, about: About, answer: Boolean = false): String {
        val id = java.util.UUID.randomUUID().toString()
        instructions.update { it.copy(instructions = it.instructions + Instruction(id, text.trim().take(InstructionPrompts.MAX_TEXT), about, clock.now(), answer = answer)) }
        scope.launch { readInstructions(interactive = true) }
        return id
    }

    /**
     * A change you made yourself, with no Claude (IMP-P2-001: a task isn't one, can't start before
     * a day, is due on another): kept as an instruction already read, [text] its words, and applied
     * as you'd apply one, under [id]. Saved and applied together: the screen that asked going
     * meanwhile (turned, closed) can't leave it saved and not applied.
     */
    suspend fun addOwnChange(id: String, text: String, about: About, change: Change): Unit = withContext(NonCancellable) {
        val instruction = Instruction(id, text, about, clock.now(), state = InstructionStatus.Understood, changes = listOf(change), quick = true)
        instructions.update { state ->
            // An earlier quick change of the same kind to the same task is replaced, not piled under
            // this one; the new one is laid in its place as it's applied.
            state.copy(instructions = state.instructions.filterNot { it.id != id && isQuick(it, change.taskId, change.type) } + instruction)
        }
        applyInstruction(id)
    }

    /**
     * [key]'s piece later today (UI-03): to the first time after its slot (or now, or when its task
     * can start) with its minutes free, around today's other slots and busy times, as a time you
     * gave it (a change of yours, taken back in Your changes). Its task's next piece only, as a time
     * pins that one (PL-04), and only a time the plan keeps (before its real deadline). The start, or
     * null when today has none.
     */
    suspend fun laterToday(key: String): Long? {
        val now = clock.now()
        val zone = clock.zone()
        val plan = plan(now = now)
        val chunk = plan.ordered.firstOrNull { it.key == key } ?: return null
        if (plan.chunksOf(chunk.taskId).firstOrNull()?.key != key) return null
        val task = tasks.value.tasks.firstOrNull { it.id == chunk.taskId } ?: return null
        val own = plan.todaySlots.firstOrNull { it.chunk?.key == key }
        val windows = com.thomaswcode.decrastination.core.SchoolDays.windows(plan.today, settings.value, tasks.value.timetable, school.value)
        val busy = calendarTime.busy + Instructions.busy(instructions.value.applied, zone, plan.today, plan.today)
        val from = listOfNotNull(now, own?.end, task.notBefore).max()
        val start = com.thomaswcode.decrastination.core.Slotter.firstFreeAfter(
            plan.todaySlots.filter { it.chunk?.key != key }, windows, busy, from, chunk.minutes, chunk.where, plan.today, zone, settings.value.slotBreakMin,
        )?.takeIf { Planner.pinProblem(task, it, now, zone) == null } ?: return null
        val at = java.time.Instant.ofEpochMilli(start).atZone(zone)
        addOwnChange(
            java.util.UUID.randomUUID().toString(),
            "Later today",
            About(taskId = chunk.taskId, taskTitle = chunk.title),
            Change(ChangeType.AtTime, taskId = chunk.taskId, date = at.toLocalDate().toString(), startMin = at.hour * 60 + at.minute, time = start),
        )
        return start
    }

    /** Whether [instruction] is a quick change of [type] to [taskId] alone. */
    private fun isQuick(instruction: Instruction, taskId: String?, type: ChangeType): Boolean =
        instruction.quick && instruction.changes.singleOrNull()?.let { it.type == type && it.taskId == taskId } == true

    /**
     * Takes back your quick changes of [type] to [taskId]: what its source says (or an instruction of
     * Claude's reading) stands again.
     */
    suspend fun takeBackQuick(taskId: String, type: ChangeType): Unit = applying.withLock {
        val gone = instructions.value.instructions.filter { isQuick(it, taskId, type) }.map { it.id }.toSet()
        if (gone.isEmpty()) return@withLock
        instructions.update { state -> state.copy(instructions = state.instructions.filterNot { it.id in gone }) }
        layInstructions()
    }

    /**
     * Whether what's in force for [taskId]'s [type] is a quick change of yours: the last applied
     * instruction that changes it. One of Claude's applied since wins, and *Undo* would take back
     * nothing you'd see.
     */
    fun quickInForce(taskId: String, type: ChangeType): Boolean = Instructions.quickInForce(instructions.value.applied, taskId, type)

    /**
     * Keeps [task] as one of your own (2.0.0, Q28) under [sourceId]: made, or changed if it's there
     * already (changed in the form, or a second tap). False if it can't be kept ([OwnTasks.problem]).
     * Saved whole, whatever the screen that asked does meanwhile.
     */
    suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = withContext(NonCancellable) {
        if (OwnTasks.problem(task) != null) return@withContext false
        val id = TaskItem.id(Source.Own, sourceId)
        val overrides = Instructions.taskOverrides(instructions.value.applied)[id] ?: TaskOverrides.NONE
        val now = clock.now()
        tasks.update { state ->
            val old = state.tasks.firstOrNull { it.id == id }
            val made = (if (old == null) OwnTasks.create(task, sourceId, now) else OwnTasks.edited(old, task))
                .let { if (it.overrides() == overrides) it else it.withOverrides(overrides) }
            state.copy(tasks = if (old == null) state.tasks + made else state.tasks.map { if (it.id == id) made else it })
        }
        true
    }

    /**
     * Your own task [taskId] done, as you say (2.0.0), or one done by its tick (NF-06): finished
     * through the queue a source's confirmation goes through, so it's logged and asked about as any
     * is; but it earns no free time ([Focus.onCompleted]), as nothing but you says it's done.
     */
    suspend fun finishOwnTask(taskId: String): Unit = withContext(NonCancellable) {
        val now = clock.now()
        var finished = false
        tasks.update { state ->
            val task = state.tasks.firstOrNull { it.id == taskId && (it.source == Source.Own || it.tickDone) && it.isOpen } ?: return@update state
            val done = task.copy(status = Status.Done, doneAt = now, lastSeenAt = now)
            finished = true
            state.copy(tasks = state.tasks.map { if (it.id == taskId) done else it }, unrewarded = state.unrewarded + done)
        }
        if (finished) settleCompletions()
    }

    /**
     * Your own task [taskId], done, open again (done by mistake): its minutes worked stay, and so does
     * its record in the log, as a source's reopening leaves it. One with every step ticked has its
     * last step open again: something's left, or it would be finished again at once.
     */
    suspend fun reopenOwnTask(taskId: String) {
        tasks.update { state ->
            // Or one done by its tick (NF-06), which only you say is done: a sync leaves it open again.
            state.copy(tasks = state.tasks.map { t -> if (t.id == taskId && (t.source == Source.Own || t.tickDone) && !t.isOpen) OwnTasks.reopened(t) else t })
        }
    }

    /** Your own task [taskId] gone, as if it never was. */
    suspend fun deleteOwnTask(taskId: String) {
        // A task linked to it as the same work (DX-15) is back: the work it stood for has gone.
        tasks.update { state -> state.copy(tasks = com.thomaswcode.decrastination.core.Duplicates.linksPresent(state.tasks.filterNot { it.id == taskId && it.source == Source.Own })) }
        forgetCards(setOf(taskId))
    }

    /** Chat cards that added the tasks [gone] say so no more: they can be added again. */
    private suspend fun forgetCards(gone: Set<String>) {
        if (chat.value.messages.none { m -> m.proposals.any { it.made in gone } }) return
        chat.update { s ->
            s.copy(messages = s.messages.map { m -> m.copy(proposals = m.proposals.map { p -> if (p.made in gone) p.copy(state = ProposalState.Proposed, made = null) else p }) })
        }
    }

    /**
     * Your own tasks done more than a fortnight ago go, as a source's do once it no longer lists them
     * ([com.thomaswcode.decrastination.core.Merge.KEEP_FINISHED_MS]); nothing reads them to do it.
     */
    private suspend fun pruneOwn() {
        val now = clock.now()
        fun old(t: TaskItem) = t.source == Source.Own && !t.isOpen && now - (t.doneAt ?: t.lastSeenAt) >= com.thomaswcode.decrastination.core.Merge.KEEP_FINISHED_MS
        val gone = tasks.value.tasks.filter(::old).map { it.id }.toSet()
        if (gone.isEmpty()) return
        tasks.update { state -> state.copy(tasks = state.tasks.filterNot(::old)) }
        forgetCards(gone)
    }

    /** Has Claude read [id] again (written again, or after it found it unclear). */
    suspend fun rereadInstruction(id: String, text: String) {
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == id && it.state != InstructionStatus.Applied) it.copy(text = text.trim().take(InstructionPrompts.MAX_TEXT), state = InstructionStatus.Reading, changes = emptyList(), note = null) else it })
        }
        scope.launch { readInstructions(interactive = true) }
    }

    /**
     * Reads the instructions waiting for Claude, one call each, as the enrichment's calls are made:
     * under the cap, and resting after a failure. While Claude can't be asked, each says why.
     */
    suspend fun readInstructions(interactive: Boolean = false) = readingInstructions.withLock {
        while (true) {
            val next = instructions.value.instructions.firstOrNull { it.state == InstructionStatus.Reading } ?: return@withLock
            val waiting = run call@{
                val reader = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Instruction)?.let { InstructionReader(it, clock.zone(), profile = profile()) } ?: return@call because(ModelHold.Off)
                // Through a rest only for you waiting on it (AC-04): after a sync or an enrichment it waits.
                val result = when (val called = gateway.call(interactive = interactive, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Instruction, next.text) { r: InstructionReader.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: InstructionReader.Result -> r.costUsd to r.refused }) { at -> reader.read(next, tasks.value.tasks, calendarTime.events, at) }) {
                    is Called.Held -> return@call because(called.hold)
                    is Called.Failed -> {
                        Log.w(TAG, "Reading an instruction failed", called.error)
                        return@call "Claude's call failed: ${retryWords()}"
                    }
                    is Called.Answered -> called.value
                }
                val reading = result.reading ?: InstructionAnswers.Reading.Unclear(if (result.refused) "Claude declined to read it" else "Claude's answer couldn't be used")
                instructions.update { state ->
                    state.copy(
                        instructions = state.instructions.mapNotNull { i ->
                            // Only as it was sent: written again meanwhile, it's read again.
                            if (i.id != next.id || i.state != InstructionStatus.Reading || i.text != next.text) {
                                i
                            } else {
                                when (reading) {
                                    is InstructionAnswers.Reading.Changes -> i.copy(state = InstructionStatus.Understood, changes = reading.changes, note = null)
                                    // An answer to his question that changes nothing: it's with its task, and goes.
                                    is InstructionAnswers.Reading.Unclear -> if (i.answer) null else i.copy(state = InstructionStatus.Unclear, changes = emptyList(), note = reading.why)
                                }
                            }
                        },
                    )
                }
                // Made as soon as it's read (your call, 16 Oct: nothing of his waits for Apply), as the
                // chat's are; one that can't be is marked unclear, saying why. Taken back under Your changes.
                if (reading is InstructionAnswers.Reading.Changes) applyInstruction(next.id)
                null
            }
            if (waiting != null) {
                // Not now: each still waiting says why, and they're read when Claude can be asked.
                instructions.update { state -> state.copy(instructions = state.instructions.map { if (it.state == InstructionStatus.Reading) it.copy(note = waiting) else it }) }
                return@withLock
            }
        }
    }

    private fun because(hold: ModelHold): String = when (hold) {
        ModelHold.Off -> "Waiting for Claude, which is off (Settings)"
        ModelHold.Resting -> "Waiting for Claude, resting ${ModelHold.restLeftMin(runtime.value.aiUsage, clock.now())} min more after a failed call"
        ModelHold.Capped -> "Waiting for Claude: this month's cap is reached"
    }

    /** When a failed call is tried again (AC-04): after its rest, or at the next chance where it has none. */
    private fun retryWords(): String = ModelHold.restLeftMin(runtime.value.aiUsage, clock.now()).let { if (it > 0) "it's tried again in $it min" else "it's tried again at the next chance" }

    /** Held while the chat is answered, so each message is answered once. */
    private val answering = Mutex()

    private val sentAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** When the chat's call in flight was sent (AC-08), for its "thinking… 12 s"; null with none. */
    val answeringSince: kotlinx.coroutines.flow.StateFlow<Long?> get() = sentAt

    /** The message whose answer you let go while its call ran (AC-08): that answer, when it comes, goes unused. */
    @Volatile
    private var letGo: String? = null

    /**
     * What a message's answer has been given beyond its context, by the message's [id], kept till
     * he's answered with it: what a web search [found] (16 Oct), or why one made found nothing
     * ([notLooked]), and the tasks [read] in full (D-93). An answer with them that failed is asked
     * again with them, never searching or asking for them again, as each is paid for; a search held
     * back (Claude off or resting, the cap) isn't kept, so it can be made later.
     */
    private data class Gathered(val id: String, val found: com.thomaswcode.decrastination.enrich.Found?, val notLooked: String?, val read: List<String>)

    @Volatile
    private var gathered: Gathered? = null

    /**
     * The answer being waited for let go (AC-08): your message says so, with its Try again. A call once
     * sent is paid for and counted (AC-04), so it isn't closed: it comes back, its answer unused.
     */
    fun cancelAnswer() {
        val waiting = chat.value.unanswered ?: return
        if (sentAt.value == null) return
        letGo = waiting.id
        sentAt.value = null
        scope.launch { chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = CANCELLED, stuck = true) else it }) } }
    }

    /**
     * Your [text] to Claude about [about] (2.0.0, Q29): kept as message [id], then answered
     * ([answerChat]). Not while one of yours still waits for its answer: false then, and nothing's kept.
     */
    suspend fun ask(text: String, about: About = About(), id: String = java.util.UUID.randomUUID().toString()): Boolean {
        val words = text.trim().take(ChatPrompts.MAX_TEXT)
        if (words.isEmpty()) return false
        var added = false
        chat.update { state ->
            if (state.unanswered != null) {
                state
            } else {
                added = true
                state.copy(messages = (state.messages + ChatMessage(id, Speaker.You, words, clock.now(), about)).takeLast(MAX_CHAT))
            }
        }
        if (added) scope.launch { answerChat() }
        return added
    }

    /**
     * What the chat tells Claude the app knows at [now] ([ChatPrompts.context]): the open tasks, the
     * calendar and the changes in use; and beside them the plan as the Plan tab shows it then, the
     * slot under way included ([ChatPrompts.plan]). All of one [state], calendar [time] and
     * [applied] instructions, so the list and the plan agree, and with the answer's checks where the
     * caller passes it the same ones.
     */
    fun chatContext(
        now: Long = clock.now(),
        state: TaskState = tasks.value,
        time: com.thomaswcode.decrastination.learn.EventJudge.Time = calendarTime,
        applied: List<Instruction> = instructions.value.applied,
    ): ChatPrompts.Context {
        return ChatPrompts.Context(
            known = ChatPrompts.context(state.tasks, time.events, applied, now, clock.zone(), settings.value.classMap, runtime.value.notSame, settings.value, runtime.value.calibration),
            plan = ChatPrompts.plan(plan(state, now = now, applied = applied, time = time), clock.zone()),
        )
    }

    /**
     * Claude's answer to your message waiting for one: a call made as the enrichment's are, under the
     * cap and resting after a failure. While Claude can't be asked, your message says why, and it's
     * answered once it can be (the chat opened again, *Try again*, or Claude back from its rest).
     */
    suspend fun answerChat(retry: Boolean = false): Unit = answering.withLock {
        val waiting = chat.value.unanswered ?: return@withLock
        // Answered with nothing usable, it waits for your Try again ([retry]): never asked by itself.
        if (waiting.stuck && !retry) return@withLock
        var stuck = false
        val note = run call@{
            // Taken back (or the chat cleared) while this waited its turn: nothing to ask.
            if (chat.value.unanswered?.id != waiting.id) return@withLock
            val chatClient = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Chat) ?: return@call because(ModelHold.Off)
            val researchClient = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Research) ?: return@call because(ModelHold.Off)
            // One snapshot of the tasks, the calendar and your changes: what Claude's shown is what its answer's checked against.
            val state = tasks.value
            val time = calendarTime
            // His answer, with what a web search he asked for found ([found], 16 Oct), and the tasks whose
            // whole text he asked to [read] (D-93).
            suspend fun ask(found: com.thomaswcode.decrastination.enrich.Found?, read: List<TaskItem> = emptyList()) = gateway.call(interactive = true, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Chat, waiting.text) { r: ChatReader.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: ChatReader.Result -> r.costUsd to r.refused }) { at ->
                // Being asked now: whatever it said before (a failed call) is gone.
                chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(note = null, stuck = false) else it }) }
                sentAt.value = at
                try {
                    ChatReader(chatClient, clock.zone(), profile = profile(at), settings = settings.value).answer(chat.value.messages, state.tasks, time.events, chatContext(at, state, time, instructions.value.applied), at, found, read)
                } finally {
                    sentAt.value = null
                }
            }
            // The tasks of [ids], as they are in the snapshot his answers are checked against.
            fun tasksOf(ids: List<String>): List<TaskItem> = ids.mapNotNull { id -> state.tasks.firstOrNull { it.id == id } }
            // What it was given before, for an answer with it that failed ([Gathered]): asked again
            // with it, not looked up or asked for again.
            val before = gathered?.takeIf { it.id == waiting.id }
            var read = tasksOf(before?.read.orEmpty())
            val called = ask(before?.found, read)
            // Let go while it ran (AC-08): the message says so already, and the answer goes unused.
            if (letGo == waiting.id) {
                letGo = null
                return@withLock
            }
            val result = when (called) {
                is Called.Held -> return@call because(called.hold)
                is Called.Failed -> {
                    Log.w(TAG, "Claude's answer in the chat failed", called.error)
                    return@call "Claude's call failed: ${retryWords()}, or now with Try again"
                }
                is Called.Answered -> called.value
            }
            var reading = result.reading ?: run {
                stuck = true
                return@call if (result.refused) "Claude declined to answer that: Try again, or put it another way" else "Claude's answer couldn't be read: Try again"
            }
            // What he asked to look up on the web (16 Oct), in a call of its own, and the tasks whose
            // whole text he asked to read (D-93): he's asked again with them, and that's his answer.
            // A search the texts showed a need for, or texts the search did, take one more round
            // ([ChatRounds]); never more, as each is paid for. Not found, his answer stands, saying
            // why; his answer with them failed, the message waits for its Try again with them, as a
            // failed call leaves it.
            var found = before?.found
            var notLooked = before?.notLooked
            // Why a search made found nothing, kept with the rest; one held back isn't, so it can be made later.
            var tried = before?.notLooked
            for (round in 1..com.thomaswcode.decrastination.felix.ChatRounds.MAX_ROUNDS) {
                val next = com.thomaswcode.decrastination.felix.ChatRounds.next(reading, searched = found != null || notLooked != null, searches = settings.value.felixSearches, read = read.map { it.id }) ?: break
                var looks = false
                next.search?.let { question ->
                    val search = gateway.call(interactive = true, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Research, question) { r: com.thomaswcode.decrastination.enrich.WebResearcher.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.found == null) }, cost = { r: com.thomaswcode.decrastination.enrich.WebResearcher.Result -> r.costUsd to r.refused }) { at ->
                        sentAt.value = at
                        try {
                            com.thomaswcode.decrastination.enrich.WebResearcher(researchClient, year = profile(at).year).look(question, waiting.text, at, clock.zone())
                        } finally {
                            sentAt.value = null
                        }
                    }
                    if (letGo == waiting.id) {
                        letGo = null
                        return@withLock
                    }
                    val what = (search as? Called.Answered)?.value?.found
                    if (what == null) {
                        if (search is Called.Failed) Log.w(TAG, "Felix's web search failed", search.error)
                        notLooked = when (search) {
                            is Called.Held -> because(search.hold)
                            is Called.Failed -> "the search failed"
                            is Called.Answered -> when {
                                search.value.refused -> "Claude declined to search for it"
                                search.value.paused -> "the search ran too long"
                                else -> "the search found nothing"
                            }
                        }
                        if (search !is Called.Held) tried = notLooked
                    } else {
                        found = what
                        looks = true
                    }
                }
                val more = tasksOf(next.read)
                val reads = more.size > read.size
                if (reads) read = more
                // Nothing new to answer with (a search that found nothing, no texts asked for): his answer stands.
                if (!looks && !reads) break
                gathered = Gathered(waiting.id, found, tried, read.map { it.id })
                val again = ask(found, read)
                if (letGo == waiting.id) {
                    letGo = null
                    return@withLock
                }
                val second = when (again) {
                    is Called.Held -> return@call because(again.hold)
                    is Called.Failed -> {
                        Log.w(TAG, "Claude's answer with what it looked up or read failed", again.error)
                        return@call "Claude's call failed: ${retryWords()}, or now with Try again"
                    }
                    is Called.Answered -> again.value
                }
                reading = second.reading ?: run {
                    stuck = true
                    return@call if (second.refused) "Claude declined to answer that: Try again, or put it another way" else "Claude's answer couldn't be read: Try again"
                }
            }
            reading = reading.copy(research = null, details = emptyList())
            gathered = null
            val reply = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                from = Speaker.Claude,
                text = reading.reply,
                at = clock.now(),
                // Everything on a card made as it comes (your call, 16 Oct), each with its Undo.
                proposals = com.thomaswcode.decrastination.felix.ChatAnswers.cards(reading, tasks.value.tasks.associateBy { it.id }, clock.zone()),
                suggestedReplies = reading.suggestedReplies,
                searched = found?.let { it.searched.ifEmpty { listOf(it.question) } }.orEmpty(),
                sources = found?.sources.orEmpty(),
                note = reading.leftOut.takeIf { it.isNotEmpty() }?.let { "Left out, as the app can't use it: ${it.joinToString("; ")}" },
                // Asked to look something up, and it couldn't be: his first answer stands, saying why.
                // What he read in full to answer (D-93), said under it.
                done = listOfNotNull(notLooked?.let { "Not looked up: $it." }, read.takeIf { it.isNotEmpty() }?.let { r -> "Read in full: " + r.joinToString("; ") { "“${it.title}”" } + "." }),
            )
            var kept = false
            chat.update { s ->
                // Only while it's still the one waiting: taken back meanwhile, the answer goes with it.
                if (s.unanswered?.id != waiting.id) {
                    s
                } else {
                    kept = true
                    s.copy(messages = (s.messages.map { if (it.id == waiting.id) it.copy(answeredAt = reply.at, note = null) else it } + reply).takeLast(MAX_CHAT))
                }
            }
            // What it remembered (AI-21), with an answer kept only, and said under it with its Undo.
            val edits = if (kept) remember(reading.memories) else emptyList()
            if (edits.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(memoryEdits = edits) else it }) }
            // Its actions (AI-14; 16 Oct, everything he does), said under it with their Undo: before its
            // changes, as its steps are the ones it was shown.
            val acts = if (kept) act(reading.actions) else emptyList()
            if (acts.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = acts) else it }) }
            // Its cards, made now, as if Apply were tapped: Undo takes them back.
            if (kept) {
                val instant = reply.proposals.filter { it.instant }
                instant.forEach { applyProposal(reply.id, it.id) }
                // A time the plan couldn't keep (PL-04): where the piece went, said under the answer,
                // which names the time asked for.
                val moved = com.thomaswcode.decrastination.felix.ChatAnswers.movedPins(instant.flatMap { it.changes }, plan().buckets, clock.zone())
                // One that couldn't be made after all, said under the answer that says it was (16 Oct).
                val refused = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty()
                    .filter { p -> p.instant && p.state != ProposalState.Applied && p.note != null }.map { "Not made: ${it.note!!.replaceFirstChar(Char::lowercase)}." }
                val lines = refused + moved
                if (lines.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(done = it.done + lines) else it }) }
            }
            null
        }
        // Why it waits, said once: written only when it changes, as this runs after every sync.
        if (note != null && chat.value.unanswered?.let { it.id == waiting.id && (it.note != note || it.stuck != stuck) } == true) {
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = note, stuck = stuck) else it }) }
        }
    }

    /**
     * The chat's actions (AI-14; your call, 16 Oct: everything he does), made at once, as it alters
     * what exists (AI-24), each said under the answer with what its Undo needs ([undoAct]): a step
     * ticked (its piece kept where the plan has one, today or another day, earning as a tick does,
     * else ticked as on its sheet) or to do again; a task of yours done, open again or deleted; a
     * setting set. None for one that couldn't be.
     */
    private suspend fun act(actions: List<com.thomaswcode.decrastination.core.ChatAction>): List<com.thomaswcode.decrastination.core.Act> {
        fun said(line: String, a: com.thomaswcode.decrastination.core.ChatAction) = com.thomaswcode.decrastination.core.Act(line, a)
        return actions.mapNotNull { a ->
            val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
            when (a.type) {
                com.thomaswcode.decrastination.core.ChatAction.Type.Open -> null
                com.thomaswcode.decrastination.core.ChatAction.Type.TickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { !it.done } ?: return@mapNotNull null
                    said("Ticked: ${step.title}, of ${task.title}", a).takeIf { tick(task, index, step.title) }
                }
                com.thomaswcode.decrastination.core.ChatAction.Type.UntickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { it.done } ?: return@mapNotNull null
                    said("To do again: ${step.title}, of ${task.title}", a).takeIf { focus.untickBlock(task.id, index, step.title) }
                }
                com.thomaswcode.decrastination.core.ChatAction.Type.Done -> {
                    if (task?.isOpen != true) return@mapNotNull null
                    finishOwnTask(task.id)
                    said("Done: ${task.title}", a).takeIf { tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == false }
                }
                com.thomaswcode.decrastination.core.ChatAction.Type.Reopen -> {
                    if (task == null || task.isOpen) return@mapNotNull null
                    reopenOwnTask(task.id)
                    // As it was, finished, for its Undo: not finished anew, a second time on record.
                    com.thomaswcode.decrastination.core.Act("Open again: ${task.title}", a, was = task).takeIf { tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == true }
                }
                com.thomaswcode.decrastination.core.ChatAction.Type.Delete -> {
                    if (task?.source != Source.Own) return@mapNotNull null
                    // The cards that added it, which its going sets back: linked again by its Undo.
                    val cards = chat.value.messages.flatMap { m -> m.proposals.filter { it.made == task.id }.map { "${m.id}|${it.id}" } }
                    deleteOwnTask(task.id)
                    com.thomaswcode.decrastination.core.Act("Deleted: ${task.title}", a, was = task, cards = cards).takeIf { tasks.value.tasks.none { it.id == task.id } }
                }
                com.thomaswcode.decrastination.core.ChatAction.Type.Setting -> {
                    val field = com.thomaswcode.decrastination.data.FelixSettings.field(a.setting.orEmpty()) ?: return@mapNotNull null
                    var made: com.thomaswcode.decrastination.core.Act? = null
                    changeSettings { s ->
                        val (next, line) = com.thomaswcode.decrastination.data.FelixSettings.set(s, field.name, a.value.orEmpty()) ?: return@changeSettings s
                        made = com.thomaswcode.decrastination.core.Act(line, a, before = field.get(s), after = field.get(next))
                        next
                    }
                    // The reminders' alarms at their new times, as the Settings screen sets them.
                    if (made != null) com.thomaswcode.decrastination.learn.Daily.schedule(app)
                    made
                }
            }
        }
    }

    /**
     * The chat's tick of [task]'s step [index], still [title]: its piece kept where the plan has one,
     * today or another day (earning as a tick does; in sittings, its last, which ticks it), else
     * ticked as on its sheet, where that can be.
     */
    private suspend fun tick(task: TaskItem, index: Int, title: String): Boolean {
        val plan = plan()
        val piece = (plan.todaySlots.mapNotNull { it.chunk } + plan.ordered).firstOrNull { it.taskId == task.id && it.ticks == index }
        return if (piece != null) focus.keepSlot(piece.key) else runCatching { focus.tickBlock(task.id, index, title) }.getOrDefault(false)
    }

    /**
     * The [index]th of message [messageId]'s actions taken back ([act], its Undo): a step ticked to
     * do again (one of yours it finished open again with it), or one unticked ticked again as the
     * chat ticks one; a task done open again, or one opened back as it was, finished; one deleted
     * back as it was, with the cards that added it; a setting back to its value before. Not once
     * it's changed since (ticked, finished or deleted another way, set again): why, then.
     */
    suspend fun undoAct(messageId: String, index: Int): String? {
        val act = chat.value.messages.firstOrNull { it.id == messageId }?.acts?.getOrNull(index)?.takeIf { !it.undone } ?: return null
        val a = act.action
        val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
        val step = a.stepIndex?.let { i -> task?.subSteps?.getOrNull(i) }
        fun open(id: String) = tasks.value.tasks.firstOrNull { it.id == id }?.isOpen
        val undone = when (a.type) {
            com.thomaswcode.decrastination.core.ChatAction.Type.Open -> false
            // The step that finished one of yours: open again with it to do.
            com.thomaswcode.decrastination.core.ChatAction.Type.TickStep -> step?.done == true && focus.untickBlock(task!!.id, a.stepIndex!!, step.title, reopen = true)
            com.thomaswcode.decrastination.core.ChatAction.Type.UntickStep -> step?.done == false && task!!.isOpen && tick(task, a.stepIndex!!, step.title)
            com.thomaswcode.decrastination.core.ChatAction.Type.Done -> task?.isOpen == false && (task.source == Source.Own || task.tickDone) && run { reopenOwnTask(task.id); open(task.id) == true }
            com.thomaswcode.decrastination.core.ChatAction.Type.Reopen -> task?.isOpen == true && act.was != null && refinish(act.was)
            // Not once a card that had added it is made again: that's the task anew.
            com.thomaswcode.decrastination.core.ChatAction.Type.Delete -> act.was != null && task == null && !com.thomaswcode.decrastination.core.ChatActs.redone(act.cards, chat.value.messages) && run {
                restoreTask(act.was)
                relink(act.cards, act.was.id)
                open(act.was.id) != null
            }
            com.thomaswcode.decrastination.core.ChatAction.Type.Setting -> {
                val field = com.thomaswcode.decrastination.data.FelixSettings.field(a.setting.orEmpty())
                var back = false
                if (field != null && act.before != null) {
                    changeSettings { s ->
                        if (field.get(s) != act.after) return@changeSettings s
                        field.set(s, act.before)?.also { back = true } ?: s
                    }
                }
                if (back) com.thomaswcode.decrastination.learn.Daily.schedule(app)
                back
            }
        }
        if (!undone) return "It's changed since, so it can't be undone here"
        chat.update { s -> s.copy(messages = s.messages.map { m -> if (m.id != messageId) m else m.copy(acts = m.acts.mapIndexed { i, x -> if (i == index) x.copy(undone = true) else x }) }) }
        return null
    }

    /**
     * [was], a task the chat opened again, back as it was before, finished: its finish and its
     * steps' ticks as they were, not finished anew (a second completion on record). Only while it's as
     * opening it left it ([com.thomaswcode.decrastination.core.ChatActs.asReopened]): work on it
     * since, or a finish another way, stands, and false.
     */
    private suspend fun refinish(was: TaskItem): Boolean {
        var back = false
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != was.id || !com.thomaswcode.decrastination.core.ChatActs.asReopened(t, was)) return@map t
                    back = true
                    t.copy(status = was.status, doneAt = was.doneAt, subSteps = was.subSteps)
                },
            )
        }
        return back
    }

    /** The chat's [cards] ("message|card") that added task [id], made again by a deletion's Undo: added, as they were. */
    private suspend fun relink(cards: List<String>, id: String) {
        if (cards.isEmpty()) return
        chat.update { s ->
            s.copy(messages = s.messages.map { m -> m.copy(proposals = m.proposals.map { p -> if ("${m.id}|${p.id}" in cards && p.state == ProposalState.Proposed && p.made == null) p.copy(state = ProposalState.Applied, made = id) else p }) })
        }
    }

    /** [task], one of your own deleted, back as it was (a chat action's Undo), its instructions laid over it again. */
    private suspend fun restoreTask(task: TaskItem) {
        tasks.update { state -> if (state.tasks.any { it.id == task.id }) state else state.copy(tasks = com.thomaswcode.decrastination.core.Duplicates.linksPresent(state.tasks + task)) }
        layTaskOverrides()
    }

    /** [given] kept in what's remembered (AI-21), and what each thing did ([com.thomaswcode.decrastination.core.MemoryEdit.line] says it). */
    suspend fun remember(given: List<com.thomaswcode.decrastination.core.Given>): List<com.thomaswcode.decrastination.core.MemoryEdit> {
        if (given.isEmpty()) return emptyList()
        var edits = emptyList<com.thomaswcode.decrastination.core.MemoryEdit>()
        memory.update { state -> com.thomaswcode.decrastination.core.Memories.edit(state, given, clock.now()).also { edits = it.second }.first }
        return edits
    }

    /**
     * What the [index]th of message [messageId]'s remembering did, taken back (its Undo, your call,
     * 16 Oct): the fact gone and the one it replaced back, or what it forgot back. An answer from
     * before what each did was kept has its lines read against what's remembered now
     * ([com.thomaswcode.decrastination.core.Memories.editOf]). Null once it is; why not, if it can't be.
     */
    suspend fun undoMemory(messageId: String, index: Int): String? {
        val message = chat.value.messages.firstOrNull { it.id == messageId } ?: return null
        val edits = message.memoryEdits.ifEmpty {
            message.remembered.map { com.thomaswcode.decrastination.core.Memories.editOf(it, memory.value) ?: com.thomaswcode.decrastination.core.MemoryEdit(it) }
        }
        val edit = edits.getOrNull(index)?.takeIf { !it.undone } ?: return null
        var undone = false
        memory.update { state -> com.thomaswcode.decrastination.core.Memories.undo(state, edit)?.also { undone = true } ?: state }
        if (!undone) return "It's changed since: forget it in the list of what ${settings.value.felixName} remembers"
        chat.update { s -> s.copy(messages = s.messages.map { m -> if (m.id != messageId) m else m.copy(memoryEdits = edits.mapIndexed { i, e -> if (i == index) e.copy(undone = true) else e }) }) }
        return null
    }

    /** Whether the chat is on screen (AI-24): an answer there isn't a notification too. */
    @Volatile
    var chatInFront = false

    /**
     * Your [text] in reply to Felix's message [id], from its notification (AI-24): his message put in
     * the chat if it isn't there yet, your reply kept with it, and asked there; the answer, when it
     * comes, is his next notification while the chat isn't on screen. Returns once it's asked, not
     * answered. False, and the notification says why, when a message of yours is still waiting.
     */
    suspend fun replyToFelix(text: String, id: String): Boolean {
        val words = text.trim()
        if (words.isEmpty()) return false
        val now = clock.now()
        val name = settings.value.felixName
        val record = log.value.felix.firstOrNull { it.id == id }
        // An answer to a check-in question (LR-02): the next asked, or the check-in kept.
        if (record?.reason == com.thomaswcode.decrastination.felix.FelixPrompts.Reason.CheckIn.name) return checkInReply(record, words)
        // One of the three answers to how the day's completions went (LR-05): on them, and that's all.
        if (record != null && felix.answered(record, words)) {
            log.update { l -> l.copy(felix = l.felix.map { if (it.id == id) it.copy(replyText = words, repliedAt = now) else it }) }
            com.thomaswcode.decrastination.felix.FelixNotifications.cancel(app)
            return true
        }
        if (record != null) {
            val title = record.taskId?.let { t -> tasks.value.tasks.firstOrNull { it.id == t }?.title }
            chat.update { s ->
                if (s.messages.any { it.id == id }) s
                else s.copy(messages = (s.messages + ChatMessage(id, Speaker.Claude, record.text, record.at, About(taskId = record.taskId, taskTitle = title), reason = record.reason, suggestedReplies = record.replies)).takeLast(MAX_CHAT))
            }
            log.update { l -> l.copy(felix = l.felix.map { if (it.id == id) it.copy(replyText = words, repliedAt = now) else it }) }
        }
        val his = chat.value.messages.firstOrNull { it.id == id }
        val said = record?.text ?: his?.text.orEmpty()
        // The reply's own message in the chat, so its answer is told from any to what's said after it.
        val asked = java.util.UUID.randomUUID().toString()
        if (!ask(words, his?.about ?: About(), asked)) {
            com.thomaswcode.decrastination.felix.FelixNotifications.post(app, id, name, "$said\n\nYour last message to me is still waiting for its answer: send this again once it's come.", emptyList())
            return false
        }
        // Shown as sent, under his message, while he answers: as work of its own, as the receiver
        // that took the reply is let go once this returns, and the app may go with it.
        com.thomaswcode.decrastination.felix.FelixNotifications.post(app, id, name, said, emptyList(), sent = words)
        com.thomaswcode.decrastination.felix.FelixReplyWorker.enqueue(app, id, asked, words)
        return true
    }

    /**
     * The chat's answer to a reply from Felix's notification ([replyToFelix]), as
     * [com.thomaswcode.decrastination.felix.FelixReplyWorker] runs it: the answer to that reply's
     * message, [asked], not one to anything said after it, shown as his next notification, or why
     * there's none, where the reply was made; nothing with the chat in front, which shows it.
     */
    suspend fun answerReply(id: String, asked: String, words: String) {
        val name = settings.value.felixName
        val said = log.value.felix.firstOrNull { it.id == id }?.text ?: chat.value.messages.firstOrNull { it.id == id }?.text.orEmpty()
        answerChat()
        val messages = chat.value.messages
        val mine = messages.indexOfFirst { it.id == asked }
        // Taken back, or the chat cleared: nothing to tell.
        if (mine < 0) return
        // Its answer: his first after it, with nothing more of yours between.
        val later = messages.drop(mine + 1)
        val answer = later.takeWhile { it.from != Speaker.You }.firstOrNull { it.from == Speaker.Claude }
        when {
            chatInFront -> com.thomaswcode.decrastination.felix.FelixNotifications.cancel(app)
            answer != null -> tellAnswer(answer)
            // Not answered (Claude off, resting, a failed call): why, where the reply was made.
            later.none { it.from == Speaker.You } && messages[mine].note != null ->
                com.thomaswcode.decrastination.felix.FelixNotifications.post(app, id, name, "$said\n\n${messages[mine].note}", emptyList(), sent = words)
        }
    }

    /** [answer], Felix's in the chat to a reply from a notification, as his next notification (AI-24), with its replies and cards; and on record. */
    private suspend fun tellAnswer(answer: ChatMessage) {
        val record = com.thomaswcode.decrastination.data.FelixRecord(answer.id, answer.at, com.thomaswcode.decrastination.felix.FelixPrompts.Reason.Reply.name, answer.text, answer.suggestedReplies)
        log.update { it.copy(felix = it.felix.filterNot { r -> r.id == record.id } + record).trimmed(clock.now()) }
        // Only a card with something to make: one that shows a task (AI-14) opens it in the chat.
        val cards = answer.proposals.filter { it.state == ProposalState.Proposed && (it.task != null || it.changes.isNotEmpty()) }.map { answer.id to it }
        com.thomaswcode.decrastination.felix.FelixNotifications.post(app, answer.id, settings.value.felixName, answer.text, answer.suggestedReplies, cards)
    }

    /** Your message still waiting for Claude taken back, unanswered. */
    suspend fun withdrawQuestion() {
        chat.update { s -> s.unanswered?.let { w -> s.copy(messages = s.messages.filterNot { it.id == w.id }) } ?: s }
    }

    /** The chat cleared. What its cards made (your tasks, changes in use) stays. */
    suspend fun clearChat() {
        chat.update { ChatState() }
    }

    /**
     * Card [proposalId] of Claude's message [messageId] made, at once, armed or not (2.0.0): a task
     * of your own added (as [edited] if you changed it first), or its changes applied as an
     * instruction in the words that asked for them, so they're listed with the rest and taken back
     * there too. Says what went wrong on the card, if anything did.
     */
    suspend fun applyProposal(messageId: String, proposalId: String, edited: OwnTask? = null): Unit = withContext(NonCancellable) { proposing.withLock { apply(messageId, proposalId, edited) } }

    /** Held while a card is made, undone or set aside, so a second tap finds it as the first left it. */
    private val proposing = Mutex()

    private suspend fun apply(messageId: String, proposalId: String, edited: OwnTask?) {
        val messages = chat.value.messages
        val message = messages.firstOrNull { it.id == messageId } ?: return
        val proposal = message.proposals.firstOrNull { it.id == proposalId && it.state != ProposalState.Applied } ?: return
        // The message of yours it answered: its words name the instruction.
        val asked = messages.take(messages.indexOf(message)).lastOrNull { it.from == Speaker.You }
        var made: String? = null
        var note: String? = null
        var before = emptyMap<String, OwnTask>()
        // The days off and half-days it puts in the school calendar (PL-16), for its Undo.
        var schoolBefore: com.thomaswcode.decrastination.core.SchoolCalendar? = null
        var schoolAfter: com.thomaswcode.decrastination.core.SchoolCalendar? = null
        val task = edited ?: proposal.task
        if (task != null) {
            val id = java.util.UUID.randomUUID().toString()
            if (saveOwnTask(id, task)) made = TaskItem.id(Source.Own, id) else note = OwnTasks.problem(task) ?: "It couldn't be added"
        } else if (proposal.changes.isNotEmpty()) {
            val id = java.util.UUID.randomUUID().toString()
            // Your own tasks it changes, as they are: made on them, the changes are undone by putting them back.
            val own = tasks.value.tasks.filter { it.source == Source.Own }.associateBy { it.id }
            before = proposal.changes.filter { it.type in OwnTasks.MADE_ON_IT }.mapNotNull { it.taskId }.distinct()
                .mapNotNull { tid -> own[tid]?.let { tid to OwnTasks.draftOf(it) } }.toMap()
            val instruction = Instruction(id, asked?.text ?: message.text, asked?.about ?: About(), clock.now(), state = InstructionStatus.Understood, changes = proposal.changes)
            instructions.update { it.copy(instructions = it.instructions + instruction) }
            val calendar = school.value
            applyInstruction(id)
            if (school.value != calendar) {
                schoolBefore = calendar
                schoolAfter = school.value
            }
            val after = instructions.value.instructions.firstOrNull { it.id == id }
            when {
                // All made on tasks of your own, so there's nothing left of it to keep.
                after == null -> made = null
                after.state == InstructionStatus.Applied -> made = id
                else -> {
                    note = after.note ?: "It couldn't be applied"
                    before = emptyMap()
                    deleteInstruction(id)
                }
            }
        }
        chat.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id != messageId) m else m.copy(proposals = m.proposals.map { p ->
                        if (p.id != proposalId) {
                            p
                        } else if (note == null) {
                            // As added: changed first, it's yours that the card shows and the chat recalls.
                            p.copy(state = ProposalState.Applied, made = made, before = before, note = null, task = task ?: p.task, schoolBefore = schoolBefore, schoolAfter = schoolAfter)
                        } else {
                            p.copy(note = note)
                        }
                    })
                },
            )
        }
    }

    /**
     * Card [proposalId] undone: the task it added deleted, or its changes taken back, those made on
     * tasks of your own put back as they were, and its days off out of the school calendar.
     */
    suspend fun undoProposal(messageId: String, proposalId: String): Unit = proposing.withLock {
        val proposal = chat.value.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId && it.state == ProposalState.Applied } ?: return@withLock
        val made = proposal.made
        if (made == null && proposal.before.isEmpty() && proposal.schoolBefore == null) return@withLock
        val note = if (proposal.task != null) {
            made?.let { deleteOwnTask(it) }
            null
        } else {
            // What it changed on your own tasks put back as it was (no instruction held those changes):
            // only the fields that still hold what it set, so a later change of yours stands. One that
            // can't be (the form wouldn't take it now) keeps the card applied, saying why.
            // Every one checked before any is saved: all go back, or none and the card says why.
            val backs = proposal.before.mapNotNull { (id, was) ->
                val t = tasks.value.tasks.firstOrNull { it.id == id } ?: return@mapNotNull null
                val back = OwnTasks.restored(OwnTasks.draftOf(t), was, proposal.changes.filter { it.taskId == id })
                (t to back).takeIf { back != OwnTasks.draftOf(t) }
            }
            // Its days off and half-days out of the school calendar, unless one has gone from it since.
            val calendar = proposal.schoolBefore?.let { was -> school.value.undone(was, proposal.schoolAfter ?: return@let null) }
            val stuck = backs.mapNotNull { (t, back) -> OwnTasks.problem(back)?.let { "“${t.title}” can't go back: ${it.replaceFirstChar(Char::lowercase)}" } } +
                // And its instruction, which taking back mustn't leave tasks waiting for each other in a circle.
                listOfNotNull(made?.takeIf { m -> Instructions.makesCircle(emptyList(), instructions.value.applied.filterNot { it.id == m }) }
                    ?.let { "taking its changes back would bring back an earlier instruction that has tasks waiting for each other in a circle" }) +
                listOfNotNull("its days off have changed since: they're deleted in Settings → School".takeIf { proposal.schoolBefore != null && calendar == null })
            if (stuck.isNotEmpty()) {
                stuck.joinToString("; ").replaceFirstChar(Char::uppercase)
            } else {
                backs.forEach { (t, back) -> saveOwnTask(t.sourceId, back) }
                calendar?.let { c -> school.update { c } }
                made?.let { deleteInstruction(it) }
                made?.let { m -> instructions.value.instructions.firstOrNull { it.id == m }?.note }
            }
        }
        chat.update { s ->
            s.copy(
                // Every message's cards: a later one may be in a later reply.
                messages = s.messages.map { m ->
                    m.copy(proposals = m.proposals.map { p ->
                        when {
                            m.id == messageId && p.id == proposalId -> if (note == null) p.copy(state = ProposalState.Proposed, made = null, before = emptyMap(), schoolBefore = null, schoolAfter = null) else p.copy(note = note)
                            // Undone, its values are gone from the tasks: a card applied after it, which
                            // replaced them, puts back what this one replaced instead, if it's undone too.
                            note == null && p.state == ProposalState.Applied && p.before.keys.any { it in proposal.before } ->
                                p.copy(before = p.before.mapValues { (id, was) -> proposal.before[id]?.let { OwnTasks.relayered(was, proposal.changes.filter { it.taskId == id }, it) } ?: was })
                            else -> p
                        }
                    })
                },
            )
        }
    }

    /** Card [proposalId] set aside (or, [discarded] false, brought back). */
    suspend fun discardProposal(messageId: String, proposalId: String, discarded: Boolean = true): Unit = proposing.withLock {
        chat.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id != messageId) m else m.copy(proposals = m.proposals.map { p ->
                        if (p.id != proposalId || p.state == ProposalState.Applied) p else p.copy(state = if (discarded) ProposalState.Discarded else ProposalState.Proposed)
                    })
                },
            )
        }
    }

    /** Held from checking an instruction to applying it, so two applied at once can't both pass the check. */
    private val applying = Mutex()

    /**
     * Applies [id], read and checked by you, at once, armed or not: what an instruction changes is
     * the work, yours to change (12 Oct), never the blocker.
     */
    suspend fun applyInstruction(id: String): Unit = applying.withLock {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id && it.state == InstructionStatus.Understood } ?: return@withLock
        if (refuseCircle(instruction)) return@withLock
        if (refuseOnOwnTasks(instruction)) return@withLock
        if (refuseLate(instruction)) return@withLock
        setApplied(id)
    }

    /**
     * [instruction] would put a task's work after its real deadline (PL-04): it's marked unclear,
     * saying so, rather than applied.
     */
    private suspend fun refuseLate(instruction: Instruction): Boolean {
        val byId = tasks.value.tasks.associateBy { it.id }
        if (instruction.changes.none { c -> c.taskId?.let(byId::get)?.let { Instructions.latePin(c, it, clock.zone(), clock.now()) } == true }) return false
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = "It's due before then") else it })
        }
        return true
    }

    /**
     * [instruction] would leave one of your own tasks as the form wouldn't take it (a start after its
     * due date): it's marked unclear, saying why, rather than applied, as nothing of it would be.
     */
    private suspend fun refuseOnOwnTasks(instruction: Instruction): Boolean {
        val problem = OwnTasks.problemWith(tasks.value.tasks, instruction.changes) ?: return false
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = problem) else it })
        }
        return true
    }

    /**
     * [instruction] would have tasks wait for each other in a circle, with those applied: it's
     * marked unclear, saying so, rather than applied.
     */
    private suspend fun refuseCircle(instruction: Instruction): Boolean {
        if (!Instructions.makesCircle(instruction.changes, instructions.value.applied)) return false
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.map {
                    if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = "It would have tasks wait for each other in a circle, so none would ever be planned") else it
                },
            )
        }
        return true
    }

    private suspend fun setApplied(id: String) {
        makeOnSchool(id)
        makeOnOwnTasks(id)
        // A time, a day or a start you gave a task moves it on purpose: its slots today are recorded
        // moved (LR-09), as they were, and its missed slots forgotten (PL-13).
        val moved = instructions.value.instructions.firstOrNull { it.id == id }?.changes.orEmpty()
            .filter { it.type == ChangeType.AtTime || it.type == ChangeType.OnDay || it.type == ChangeType.StartFrom }.mapNotNull { it.taskId }
        if (moved.isNotEmpty()) {
            val changes = instructions.value.instructions.firstOrNull { it.id == id }?.changes.orEmpty()
            Instructions.movedSlots(changes, focus.plan()).forEach { slot -> focus.moveSlot(slot.key, Focus.Laid(slot.start, slot.end, slot.window)) }
            runtime.update { state -> state.copy(slots = moved.fold(state.slots) { s, taskId -> s.moved(taskId) }) }
        }
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == id && it.state == InstructionStatus.Understood) it.copy(state = InstructionStatus.Applied, appliedAt = clock.now()) else it })
        }
        layInstructions()
    }

    /**
     * [id]'s days off and half-days put in the school calendar (PL-16), and taken out of it: Settings
     * lists them, and deleting one there is how one is taken back. One left with nothing else to do
     * is done with, and goes.
     */
    private suspend fun makeOnSchool(id: String) {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return
        val made = instruction.changes.filter { Instructions.schoolEntry(it) != null }
        if (made.isEmpty()) return
        school.update { calendar ->
            made.mapNotNull(Instructions::schoolEntry).fold(calendar) { c, entry ->
                when (entry) {
                    is NoSchool -> c.with(entry)
                    is HalfDay -> c.with(entry)
                    else -> c
                }
            }
        }
        instructions.update { state ->
            state.copy(instructions = state.instructions.mapNotNull { i -> if (i.id != id) i else i.copy(changes = i.changes - made.toSet()).takeIf { it.changes.isNotEmpty() } })
        }
    }

    /**
     * [id]'s due dates, starts and minutes for your own tasks made on them ([OwnTasks.changed]), and
     * taken out of it: there's no source's value for taking it back to return to, and the form can
     * change them again. One left with nothing else to do is done with, and goes.
     */
    private suspend fun makeOnOwnTasks(id: String) {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return
        // Each task's together, as one draft: only those the form would take are made (a restored
        // instruction's others stay in it, laid over the task as any instruction is).
        val made = OwnTasks.madeOn(tasks.value.tasks, instruction.changes)
        if (made.isEmpty()) return
        tasks.update { state ->
            state.copy(tasks = state.tasks.map { t -> made.filter { it.taskId == t.id }.takeIf { it.isNotEmpty() }?.let { OwnTasks.draftWithAll(t, it) }?.let { OwnTasks.edited(t, it) } ?: t })
        }
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.mapNotNull { i ->
                    if (i.id != id) i else i.copy(changes = i.changes - made.toSet()).takeIf { it.changes.isNotEmpty() }
                },
            )
        }
    }

    /** Deletes [id]: discarded if it wasn't applied, taken back if it was. */
    suspend fun deleteInstruction(id: String): Unit = applying.withLock {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return@withLock
        if (instruction.state == InstructionStatus.Applied && refuseTakingBack(instruction)) return@withLock
        remove(id)
    }

    /**
     * Taking [instruction] back would leave tasks waiting for each other in a circle (an older
     * instruction it replaced coming back into force): it stays, saying so.
     */
    private suspend fun refuseTakingBack(instruction: Instruction): Boolean {
        val rest = instructions.value.applied.filterNot { it.id == instruction.id }
        if (!Instructions.makesCircle(emptyList(), rest)) return false
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.map {
                    if (it.id == instruction.id) it.copy(note = "Taking this back would bring back an earlier instruction that has tasks waiting for each other in a circle: take that one back first") else it
                },
            )
        }
        return true
    }

    private suspend fun remove(id: String) {
        val was = instructions.value.instructions.firstOrNull { it.id == id }
        instructions.update { state -> state.copy(instructions = state.instructions.filterNot { it.id == id }) }
        if (was?.state == InstructionStatus.Applied) layInstructions()
        // A chat card that made it, taken back here (under Your changes): the card says so, and the chat
        // recalls it. What it made on your own tasks, and its days off, stand, still undone from the card.
        if (chat.value.messages.any { m -> m.proposals.any { it.made == id } }) {
            chat.update { s ->
                s.copy(
                    messages = s.messages.map { m ->
                        m.copy(proposals = m.proposals.map { p ->
                            when {
                                p.made != id -> p
                                // Its days off stand too, still undone from the card.
                                p.before.isEmpty() && p.schoolBefore == null -> p.copy(state = ProposalState.Proposed, made = null)
                                else -> p.copy(made = null)
                            }
                        })
                    },
                )
            }
        }
    }

    /**
     * The applied instructions laid over everything they change: each task's overrides (on the
     * task, so syncs keep them), the calendar's answers, and the plan, with today's record made
     * again as an answer about an event does.
     */
    private suspend fun layInstructions() {
        layTaskOverrides()
        focus.forgetPlan()
        CalendarTime.refresh(app)
        Briefing.replanToday(app)
        // A day's or time's instruction changes no store the widget watches, and the calendar's read
        // (which redraws it) is skipped without its permission: so it's redrawn here.
        runCatching { WidgetUpdater.update(app) }.onFailure { Log.w(TAG, "Widget update failed", it) }
    }

    /**
     * Each task's overrides made what the applied instructions say: the instructions are what's
     * kept, the tasks follow. Done at start as well, so a stop between writing the one and the other
     * (an instruction applied but not yet laid, or taken back with its override still on the task)
     * is put right.
     */
    private suspend fun layTaskOverrides() {
        val overrides = Instructions.taskOverrides(instructions.value.applied)
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { task ->
                    val o = overrides[task.id] ?: TaskOverrides.NONE
                    val now = task.overrides()
                    if (o == now) task else task.withOverrides(o)
                }.let(com.thomaswcode.decrastination.core.Duplicates::linksPresent),
            )
        }
    }

    companion object {
        const val TAG = "Decrastination"

        /** [requestTeamsSync]'s answer when the widget is syncing already: as good as one started. */
        const val TEAMS_BUSY = "The Teams widget is already syncing"

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L
        private const val WIDGET_DEBOUNCE_MS = 1_000L

        private const val CALENDAR_QUIET_MS = 5_000L

        /** How long plans are quiet before the calendar's written ([toWrite]). */
        private const val CALENDAR_WRITE_QUIET_MS = 5_000L

        /** After a failed call, the model is left alone this long. */

        /** Unreadable files Setup lists at most: the latest. */

        /** The most model calls one enrichment run makes: the rest wait for the next. */
        const val MAX_MODEL_CALLS = 20

        /** The chat's messages kept: the newest. */
        private const val MAX_CHAT = 200

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
