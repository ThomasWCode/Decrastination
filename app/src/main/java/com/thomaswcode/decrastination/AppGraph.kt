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
import com.thomaswcode.decrastination.core.InstructionState
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
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.Backup
import com.thomaswcode.decrastination.data.Backups
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ChatPrompts
import com.thomaswcode.decrastination.enrich.ChatReader
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.Enricher
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.InstructionReader
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.net.UrlConnectionHttp
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

    val tasks = JsonStore(File(app.filesDir, "tasks.json"), TaskState.serializer(), ::TaskState)
    // A file that can't be read at all starts armed while the device admin is on, as only arming
    // turns it on: a bad file never disarms protection (IMP-P1-003).
    val settings = JsonStore(File(app.filesDir, "settings.json"), Settings.serializer(), ::Settings, unreadable = { Settings(armed = Watchdog.isAdminActive(app)) })
    val secrets = SecretStore(File(app.filesDir, "secrets.bin"), KeystoreCipher())
    val runtime = JsonStore(File(app.filesDir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState)
    val log = JsonStore(File(app.filesDir, "log.json"), ActivityLog.serializer(), ::ActivityLog)

    /** Your instructions about tasks, events and days, and what Claude read them as ([Instructions]). */
    val instructions = JsonStore(File(app.filesDir, "instructions.json"), InstructionState.serializer(), ::InstructionState)

    /** Your chat with Claude (2.0.0, Q29): what you said, its answers, and what became of its cards. */
    val chat = JsonStore(File(app.filesDir, "chat.json"), ChatState.serializer(), ::ChatState)

    /** Days with no school and half-days (PL-16), told to the chat or added in Settings. */
    val school = JsonStore(File(app.filesDir, "school.json"), SchoolCalendar.serializer(), ::SchoolCalendar)

    init {
        // Files that couldn't be read whole at this start: kept for Setup to say, till you've seen them.
        // On record before anything else runs, and only then is a bad file replaced by what was kept
        // of it: a start cut short before then finds it again, and says it (once: its copy is the same).
        val found = listOfNotNull(tasks.problem, settings.problem, secrets.problem, runtime.problem, log.problem, instructions.problem, chat.problem, school.problem)
        if (found.isNotEmpty()) {
            found.forEach { Log.w(TAG, "${it.file} couldn't be read whole; set aside as ${it.copy}") }
            runBlocking {
                runCatching {
                    runtime.update { it.withUnreadable(found) }
                }.onSuccess { listOf(tasks, settings, runtime, log, instructions, chat, school).forEach { it.keepRecovered() } }
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
    )

    /** The plan now, from the stored tasks and settings. Cheap: dozens of tasks. */
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

    /** The plan at [now], of [state] with [applied] instructions and the calendar's [time]: as they are, or a snapshot of them taken once ([chatContext]). */
    fun plan(
        state: TaskState = tasks.value,
        settings: Settings = this.settings.value,
        now: Long = clock.now(),
        applied: List<Instruction> = instructions.value.applied,
        time: com.thomaswcode.decrastination.learn.EventJudge.Time = calendarTime,
    ): Plan {
        val zone = clock.zone()
        // Your instructions' days and times, over as far as the plan can reach.
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val reach = today.plusDays(PLAN_REACH_DAYS)
        // Each task as its instructions say, whatever the task file (a stop between writing the two,
        // not yet put right): the instructions are what's kept.
        val overrides = Instructions.taskOverrides(applied)
        val tasks = state.tasks.map { task ->
            val o = overrides[task.id] ?: TaskOverrides.NONE
            if (o == task.overrides()) task else task.withOverrides(o)
        }
        // Each day's windows, from the timetable's study periods and the school calendar (PL-15).
        val days = generateSequence(today) { it.plusDays(1) }.takeWhile { it <= reach }.toList()
        val calendar = school.value
        val windows = days.associateWith { com.thomaswcode.decrastination.core.SchoolDays.windows(it, settings, state.timetable, calendar) }
        val dayKinds = days.associateWith { com.thomaswcode.decrastination.core.SchoolDays.kindOf(it, calendar) }
        return Planner.plan(
            Planner.Input(
                tasks = tasks,
                now = now,
                zone = zone,
                settings = settings,
                calibration = runtime.value.calibration,
                busy = time.busy,
                hardBusy = Instructions.busy(applied, zone, today, reach),
                dayLoads = time.dayLoads,
                dayCaps = Instructions.dayCaps(applied, today, reach),
                workedTodayMin = workedToday(now, zone),
                windows = windows,
                dayKinds = dayKinds,
                // Today's pieces where the last plan laid them, where that's still free, and those whose
                // slots went by undone (PL-13).
                previousSlots = runtime.value.slots.on(today).previous,
                missedSlots = runtime.value.slots.on(today).missed.map { it.key }.toSet(),
            ),
        )
    }

    /** Minutes worked in today's kept slots, as the day's record counts them (LR-09). */
    private fun workedToday(now: Long, zone: java.time.ZoneId): Int {
        val start = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return com.thomaswcode.decrastination.learn.Days.workedIn(log.value, start, now + 1).values.sum()
    }

    /** The blocker's state and decisions (block/Focus.kt). */
    val focus = Focus(
        tasks, settings, runtime, log, clock,
        onOwnTaskDone = { finishOwnTask(it) },
        // A school day by the school calendar (BL-02): a day off blocks from the weekend's time.
        schoolDay = { com.thomaswcode.decrastination.core.SchoolDays.kindOf(it, school.value) == com.thomaswcode.decrastination.core.DayKind.Weekday },
    ) { state, s, now -> plan(state, s, now) }

    /**
     * The plan made again as what it depends on changes (PL-13): today's slot starts kept, then the
     * slot alarms set to it and the widget redrawn.
     */
    val watcher = PlanWatcher(
        scope = scope,
        changes = merge(
            tasks.state,
            settings.state,
            instructions.state,
            calendarState,
            school.state,
            runtime.state.map { it.slots.previous to it.calibration }.distinctUntilChanged(),
        ),
        plan = { plan() },
        keep = ::keepSlots,
        hooks = listOf(
            { made -> SlotAlarms.arm(app, made, clock.now()) },
            ::writeSlots,
            { _ -> WidgetUpdater.update(app) },
        ),
        onFailure = { Log.w(TAG, "Replanning failed", it) },
    )

    /** Today's slots as they pass: begun, or missed (PL-13). */
    val slots = Slots(this)

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
        val byId = tasks.value.tasks.associateBy { it.id }
        val before = runtime.value.calendarWritten
        val written = withContext(Dispatchers.IO) {
            SlotCalendar.write(app, calendarId, made, zone, before) { move ->
                val task = move.desired.taskId?.let(byId::get)
                when {
                    task == null -> "its task is no longer there"
                    Instructions.latePin(SlotCalendar.change(move, zone), task, zone) -> "it's due before then"
                    else -> null
                }
            }
        }
        // A move taken counts as written only once its instruction is in: stopped before, the next
        // write finds it a move again, rather than putting it back.
        val held = SlotCalendarDiff.pending(written.starts, before, written.taken)
        if (held != before) runtime.update { it.copy(calendarWritten = held) }
        written.refused.forEach { (move, why) -> SlotCalendar.sayRefused(app, move, zone, why) }
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
        val cleared = withContext(Dispatchers.IO) { waiting.filter { SlotCalendar.clear(app, it) } }
        if (cleared.isNotEmpty()) runtime.update { it.copy(calendarsToClear = it.calendarsToClear - cleared.toSet()) }
    }

    /**
     * [made]'s today kept for the next plan (PL-13): its work slots' starts, and which missed pieces
     * went to another day. Written only where that changes something, so keeping them doesn't make
     * the plan again for nothing.
     */
    private suspend fun keepSlots(made: Plan) {
        // A slot whose grace went by before its alarm or a sweep could see it is missed first: once its
        // start is replaced by where this plan moved it, nothing would know it was.
        runCatching { slots.sweep() }.onFailure { Log.w(TAG, "Looking at missed slots failed", it) }
        val starts = made.todaySlots.filter { it.kind == SlotKind.Work }.associate { it.chunk!!.key to it.start }
        runtime.update { state ->
            val kept = state.slots.on(made.today).copy(previous = starts).reconciled(made)
            if (kept == state.slots) state else state.copy(slots = kept)
        }
    }

    /** Applies a settings change: at once, or, once armed, pending if it loosens blocking. */
    /**
     * Held while the settings and their pending changes are changed: two files, written in turn,
     * so each change finishes before the next reads them.
     */
    private val changing = Mutex()

    /**
     * Held for each call to the model, from checking it's under the cap to recording what it cost:
     * the enrichment, the weekly review and the photo check can't together pass the cap.
     */
    val modelCalls = Mutex()

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
        val fields = changes.map { it.setting }.filter { it in com.thomaswcode.decrastination.learn.ReviewInput.BOUNDS }.toSet()
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
        // Any a stop left ungiven: completions, and photo checks.
        scope.launch {
            focus.finishPhotos()
            settleCompletions()
        }
        // "How was it?" questions kept while notifications were off, once they're on.
        syncer.addAfterEverySync { Assessment.askLater(app) }
        // A task back after aging out of the list (or new) takes the overrides its instructions give.
        syncer.addAfterEverySync { layTaskOverrides() }
        // Your own tasks long done go, as a source's do.
        syncer.addAfterEverySync { pruneOwn() }
        // A question of yours still waiting (Claude was off, resting, or the app stopped): tried again.
        syncer.addAfterEverySync { if (chat.value.unanswered != null) answerChat() }
        // One of your own whose steps are all done (ticked, or kept in their slots) is finished.
        scope.launch {
            tasks.state.map { s -> s.tasks.filter { it.source == Source.Own && it.isOpen && it.subSteps.isNotEmpty() && it.subSteps.all { st -> st.done } }.map { it.id } }
                .distinctUntilChanged().collect { ids -> ids.forEach { finishOwnTask(it) } }
        }
        // Instructions left waiting for Claude, once it can be asked (a new month under the cap
        // changes nothing it watches).
        syncer.addAfterEverySync { if (instructions.value.instructions.any { it.state == InstructionStatus.Reading }) readInstructions() }
        // What's new or changed, even in place, is enriched.
        syncer.addAfterEverySync {
            val modelOn = modelAvailable()
            val modelOff = claudeKey() == null
            if (tasks.value.tasks.any { Enrichments.jobFor(it) != null && Enrichments.stale(it, modelOn, RuleEnricher.BY, modelOff) }) EnrichWorker.enqueue(app)
        }
        scope.launch { CalendarTime.refresh(app) }
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
            combine(settings.state, runtime.state) { _, _ -> modelAvailable() }.distinctUntilChanged().drop(1).collect { on ->
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
        // A key problem standing from before, whose alert couldn't be shown then.
        reconcileAlerts()
        // Dropped plans' warnings kept in step with the tasks, from the start.
        scope.launch {
            tasks.state.debounce(WIDGET_DEBOUNCE_MS).collect { state ->
                runCatching { droppedAlerts(state.tasks) }.onFailure { Log.w(TAG, "Dropped-plan warnings failed", it) }
            }
        }
        // Slots whose end went by with the phone off, as if their alarms had rung; then the plan, made
        // again as it changes, from now on (PL-13).
        scope.launch {
            runCatching { slots.sweep() }.onFailure { Log.w(TAG, "Looking at missed slots failed", it) }
            watcher.start()
        }
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

    /** A backup of what the sources can't give back, as text ([Backups]). */
    fun exportBackup(): String = Backups.encode(
        Backup(
            exportedAt = clock.now(),
            versionName = BuildConfig.VERSION_NAME,
            // As asked for: a change still waiting its delay is in it, and restored goes through
            // the same wait (or, restored here, keeps waiting) rather than being lost.
            settings = SettingsChanges.requested(settings.value, runtime.value.pending),
            log = log.value,
            calibration = runtime.value.calibration,
            eventAnswers = runtime.value.eventAnswers,
            aiUsage = runtime.value.aiUsage,
            instructions = instructions.value.applied,
            ownTasks = tasks.value.tasks.filter { it.source == Source.Own },
            school = school.value,
        ),
    )

    /**
     * Restores the backup [text] and says what happened. Nothing is restored from one that holds
     * what the app never writes ([Backups.problem]). The settings go through [changeSettings], so
     * once armed one that loosens blocking waits; the instructions as applying each here would go
     * ([restoreInstructions]); the log, the calibration and the calendar answers armed or not. They
     * were kept back while armed, as an edited file could teach the planner to plan less; but the
     * plan is yours to change at once now (12 Oct), and free time is earned in a ledger no backup holds.
     */
    suspend fun importBackup(text: String): String {
        val backup = Backups.decode(text) ?: return "That isn't a Decrastination backup (or it's from a newer version)."
        Backups.problem(backup)?.let { return "Nothing was restored: $it." }
        changeSettings { Backups.importedSettings(it, backup) }
        val (restored, unclear) = restoreInstructions(backup.instructions)
        // Your own tasks not here already: no source can give them back. The instructions' say is laid
        // over them afresh, from those in use here.
        val ownAdded = Backups.restoredOwnTasks(backup.ownTasks, tasks.value.tasks).map { it.id }.toSet()
        tasks.update { state -> state.copy(tasks = state.tasks + Backups.restoredOwnTasks(backup.ownTasks, state.tasks)) }
        if (ownAdded.isNotEmpty()) layTaskOverrides()
        // The backup's days off and half-days beside those here (PL-16).
        school.update { it.withAll(backup.school) }
        val own = ownAdded.size
        val told = buildList {
            if (own > 0) add("$own task${if (own == 1) "" else "s"} of your own")
            if (restored > 0) add("$restored instruction${if (restored == 1) "" else "s"} restored")
            if (unclear > 0) add("$unclear left unapplied under Ask Claude's Your changes, as ${if (unclear == 1) "it" else "they"} would have tasks wait for each other in a circle")
        }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " ", postfix = ".") ?: ""
        // This month's spend on Claude, armed or not: it can only rise, so the cap isn't given again.
        val month = AiUsage.monthOf(clock.now(), clock.zone())
        runtime.update { it.copy(aiUsage = Backups.mergeUsage(it.aiUsage, backup.aiUsage, month)) }
        // The reminders' alarms at the restored times, as a save in Settings sets them.
        Daily.schedule(app)
        val waiting = runtime.value.pending.size
        val waits = if (waiting > 0) " $waiting change${if (waiting == 1) " that loosens blocking waits" else "s that loosen blocking wait"} ${settings.value.loosenDelayHours} hours." else ""
        log.update { Backups.mergeLog(it, backup.log, clock.now()) }
        // Your answers on this phone win over the backup's.
        // One from before 1.8.0 under its rule now, as at a start, from the log as merged.
        val calibration = Calibrator.upgraded(backup.calibration, log.value.completions) ?: backup.calibration
        runtime.update { it.copy(calibration = calibration, eventAnswers = backup.eventAnswers + it.eventAnswers) }
        // Read with the restored answers, and today's record made again, as an answer given here does.
        scope.launch {
            CalendarTime.refresh(app)
            Briefing.replanToday(app)
        }
        return "Restored the settings, ${backup.log.completions.size} completions and ${backup.log.slots.size} slots, what the app had learned, and your calendar answers.$waits$told"
    }

    /**
     * A backup's instructions in use ([backed]) taken in, in the order they were applied, each as
     * applying it here would go: one already here is left as it is; one that would have tasks wait
     * for each other in a circle with those here comes back unclear, saying so. The rest are
     * applied, due dates too (yours to change, 12 Oct). Says how many were applied, and how many
     * are unclear.
     */
    private suspend fun restoreInstructions(backed: List<Instruction>): Pair<Int, Int> = applying.withLock {
        val added = Backups.restoredInstructions(backed, instructions.value.instructions)
        instructions.update { state -> state.copy(instructions = state.instructions + added.filter { a -> state.instructions.none { it.id == a.id } }) }
        val applied = added.count { it.state == InstructionStatus.Applied }
        if (applied > 0) layInstructions()
        applied to added.count { it.state == InstructionStatus.Unclear }
    }

    /**
     * Debug only (`ai-endpoint`): where the model's calls go instead of Anthropic, a stand-in on the
     * PC, so its failures and answers can be tried on the phone through the real paths without a
     * paid call. Never stored: a restart forgets it.
     */
    @Volatile
    var modelEndpoint: String? = null

    /** [modelEndpoint], said loudly each time a client is made with it. */
    private fun endpoint(): String? = modelEndpoint?.also { Log.w(TAG, "Claude's calls go to $it, not Anthropic (debug ai-endpoint)") }

    /** The photo check, while Claude is switched on and has its key; else null. */
    fun photoChecker(): PhotoChecker? = claudeKey()?.let { PhotoChecker(it, endpoint()) }

    /** The model's weekly review, while Claude is switched on and has its key; else null. */
    fun modelReviewer(): ClaudeReviewer? = claudeKey()?.let { ClaudeReviewer(it, endpoint()) }

    /** Claude's API key, while Claude is switched on and the key is in use; else null. */
    fun claudeKey(): String? {
        val s = settings.value
        if (!s.aiEnabled || !s.aiKeyActive) return null
        return secrets[Secret.AnthropicApiKey]?.takeIf { it.isNotBlank() }
    }

    fun modelEnricher(): Enricher? = claudeKey()?.let { ClaudeEnricher(it, clock.zone(), endpoint()) }

    /**
     * A failed model call, counted in [month]: the model rests ([ModelHold.Resting]), and one showing
     * that the key or the account can't be used ([KeyProblem]) is alerted, once a stretch of it,
     * not at each hourly retry. The rules stand in meanwhile.
     */
    suspend fun modelFailed(error: Throwable, month: String, at: Long) {
        val problem = KeyProblem.of(error)
        val after = runtime.update { state -> state.copy(aiUsage = state.aiUsage.forMonth(month).failure(error.message ?: error.javaClass.simpleName, at, problem)) }
        alertKeyProblem(after.aiUsage)
        // Once its hour's rest is over, it's tried again on what's still the rules' (a key topped up
        // or put right meanwhile works then), rather than at whatever next changes.
        EnrichWorker.retryAfter(app, ModelHold.REST_MS + RETRY_SLACK_MS)
    }

    /**
     * Shows the alerts not shown yet, a key problem and dropped plans, as notifications may have
     * come on since (allowed, or the channel switched back on): at the start, whenever the app comes
     * to the front (back from notification settings), and once the permission is granted.
     */
    fun reconcileAlerts() {
        scope.launch {
            runCatching {
                alertKeyProblem(runtime.value.aiUsage)
                droppedAlerts(tasks.value.tasks)
            }.onFailure { Log.w(TAG, "Showing waiting alerts failed", it) }
        }
    }

    /**
     * The dropped-plan warnings in step with [all]: those whose task no longer has one withdrawn
     * (done or gone, which the enrichment loop never sees again, or planned since), and any not
     * shown yet shown, once notifications can show it.
     */
    private suspend fun droppedAlerts(all: List<TaskItem>) {
        val standing = ModelAlerts.standing(all)
        ModelAlerts.withdrawExcept(app, standing.mapTo(HashSet()) { it.first.id })
        val shown = ModelAlerts.unshown(standing, runtime.value.droppedAlerted).filter { (task, why) -> ModelAlerts.dropped(app, task, why) }
        runtime.update { state ->
            val kept = state.droppedAlerted.filterKeys { id -> standing.any { it.first.id == id } }
            state.copy(droppedAlerted = kept + shown.mapNotNull { (task, _) -> task.enrichment?.let { task.id to it.inputHash } })
        }
    }

    /**
     * [usage]'s key problem alerted, if it hasn't been yet and can be seen: one that couldn't
     * (notifications off) is tried again at the next failed call and when the app starts.
     */
    private suspend fun alertKeyProblem(usage: AiUsage) {
        val problem = usage.keyProblem?.takeIf { usage.keyAlertDue } ?: return
        if (ModelAlerts.keyProblem(app, problem)) runtime.update { it.copy(aiUsage = it.aiUsage.alerted(problem)) }
    }

    /** A model call that went through, counted in [month]: its cost, and the key working again if it wasn't. */
    suspend fun modelWorked(costUsd: Double, refused: Boolean, month: String, at: Long) {
        var mended = false
        runtime.update { state ->
            val before = state.aiUsage.forMonth(month)
            mended = before.keyProblem != null
            state.copy(aiUsage = before.record(costUsd, refused, at))
        }
        if (mended) ModelAlerts.keyFixed(app)
    }

    /**
     * What stops the model being asked now ([ModelHold]), or null: off, resting after a failed
     * call, or no room under the cap. Each call checks it under [modelCalls] just before sending.
     */
    fun modelHold(): ModelHold? {
        val s = settings.value
        return ModelHold.of(claudeKey() != null, runtime.value.aiUsage, clock.now(), clock.zone(), s.aiMonthlyCapUsd)
    }

    /**
     * Whether the model can be asked now: on with its key in use, not resting after a failed call,
     * and with room under this month's cap for another. When it can't, what only the rules have
     * seen isn't due for it, so it isn't redone every sync while the cap is used up.
     */
    fun modelAvailable(): Boolean = modelHold() == null

    /**
     * Enriches every task that's new or changed since it was last enriched, and, while the model is
     * on, those only the rules have seen; soonest due first. The model does it while it's on and
     * the month's spend leaves room under the cap, at most [MAX_MODEL_CALLS] a run; the rules do
     * the rest, and stand in for a call that fails (the model is then left alone for the run) or
     * that the model declines (recorded as the model's, so it isn't asked again).
     */
    suspend fun enrichNow(model: Enricher? = modelEnricher(), only: String? = null) {
        // Not while it rests after a failed call (no network, a bad key), nor with no room under the
        // cap: then what only the rules have seen isn't due for it.
        var enricher = model.takeIf { modelAvailable() }
        var calls = 0
        var decksChanged = false
        val candidates = tasks.value.tasks
            // [only]: that task alone (the debug `enrich-task`), so nothing else is sent anywhere.
            .filter { only == null || it.id == only }
            .mapNotNull { task -> Enrichments.jobFor(task)?.let { task to it } }
            .filter { (task, _) -> Enrichments.stale(task, enricher != null, RuleEnricher.BY, modelOff = claudeKey() == null) }
            .sortedBy { (task, _) -> task.dueAt ?: Long.MAX_VALUE }
        for ((snapshot, _) in candidates) {
            // Read afresh: a sync since the run began may have closed or changed it.
            val task = tasks.value.tasks.firstOrNull { it.id == snapshot.id } ?: continue
            val job = Enrichments.jobFor(task) ?: continue
            if (!Enrichments.stale(task, enricher != null, RuleEnricher.BY, modelOff = claudeKey() == null)) continue
            val now = clock.now()
            // Switched off (or its key removed) while this runs: nothing more is sent.
            if (claudeKey() == null) enricher = null
            var enrichment = if (enricher != null && calls < MAX_MODEL_CALLS) modelCalls.withLock call@{
                // Checked again under the lock, just before sending, so every other call counts:
                // switched off, resting after a failed call (one made while this waited), or no
                // room under the cap. The rules then do the rest of the run.
                if (modelHold() != null) {
                    enricher = null
                    return@call null
                }
                calls++
                // Counted in the month it's made in, as the cap was checked for: it may have
                // waited for the lock past midnight.
                val at = clock.now()
                val month = AiUsage.monthOf(at, clock.zone())
                val result = runCatching { enricher!!.enrich(task, job, now) }
                    .onFailure { error ->
                        Log.w(TAG, "The model's enrichment failed; the rules stand in", error)
                        modelFailed(error, month, at)
                        enricher = null
                    }
                    .getOrNull()
                result?.let { r -> modelWorked(r.costUsd, r.refused, month, at) }
                when {
                    result?.enrichment != null -> result.enrichment
                    // Declined, or no answer it could read: the rules' say, under the model's name.
                    result != null -> rules.enrich(task, job, now).enrichment?.copy(by = enricher?.by ?: RuleEnricher.BY)
                    else -> null
                }
            } else {
                null
            }
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
            // A plan of the model's dropped (out of range, not adding up) is said once it's laid,
            // by droppedAlerts, as the tasks change.
            // Its deck tasks take their sections and due date from it.
            val after = task.withEnrichment(made)
            val sections = AnkiRules.sectionsOf(after)
            if (sections != AnkiRules.sectionsOf(task) || (sections.isNotEmpty() && after.dueAt != task.dueAt)) decksChanged = true
        }
        // Sections the deck pattern missed, or a deadline moved: their deck tasks come from reading Anki again, now.
        if (decksChanged) SyncWorker.syncNow(app, setOf(Source.Anki))
    }

    /** Held while instructions are read, so each is read once. */
    private val readingInstructions = Mutex()

    /**
     * Writes down a new instruction about [about] and has Claude read it. It changes nothing until
     * you apply it ([applyInstruction]).
     */
    suspend fun addInstruction(text: String, about: About): String {
        val id = java.util.UUID.randomUUID().toString()
        instructions.update { it.copy(instructions = it.instructions + Instruction(id, text.trim().take(InstructionPrompts.MAX_TEXT), about, clock.now())) }
        scope.launch { readInstructions() }
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
     * Your own task [taskId] done, as you say (2.0.0): finished through the queue a source's
     * confirmation goes through, so it's logged and asked about as any is; but it earns no free time ([Focus.onCompleted]), as nothing but you says it's done.
     */
    suspend fun finishOwnTask(taskId: String): Unit = withContext(NonCancellable) {
        val now = clock.now()
        var finished = false
        tasks.update { state ->
            val task = state.tasks.firstOrNull { it.id == taskId && it.source == Source.Own && it.isOpen } ?: return@update state
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
            state.copy(tasks = state.tasks.map { t -> if (t.id == taskId && t.source == Source.Own && !t.isOpen) OwnTasks.reopened(t) else t })
        }
    }

    /** Your own task [taskId] gone, as if it never was. */
    suspend fun deleteOwnTask(taskId: String) {
        tasks.update { state -> state.copy(tasks = state.tasks.filterNot { it.id == taskId && it.source == Source.Own }) }
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
        scope.launch { readInstructions() }
    }

    /**
     * Reads the instructions waiting for Claude, one call each, as the enrichment's calls are made:
     * under the cap, and resting after a failure. While Claude can't be asked, each says why.
     */
    suspend fun readInstructions() = readingInstructions.withLock {
        while (true) {
            val next = instructions.value.instructions.firstOrNull { it.state == InstructionStatus.Reading } ?: return@withLock
            val waiting = modelCalls.withLock call@{
                modelHold()?.let { return@call because(it) }
                val reader = instructionReader() ?: return@call because(ModelHold.Off)
                val at = clock.now()
                val month = AiUsage.monthOf(at, clock.zone())
                val result = runCatching { reader.read(next, tasks.value.tasks, calendarTime.events, at) }
                    .onFailure { error ->
                        Log.w(TAG, "Reading an instruction failed", error)
                        modelFailed(error, month, at)
                    }
                    .getOrNull() ?: return@call "Claude's call failed: it's tried again within the hour"
                modelWorked(result.costUsd, result.refused, month, at)
                val reading = result.reading ?: InstructionAnswers.Reading.Unclear(if (result.refused) "Claude declined to read it" else "Claude's answer couldn't be used")
                instructions.update { state ->
                    state.copy(
                        instructions = state.instructions.map { i ->
                            // Only as it was sent: written again meanwhile, it's read again.
                            if (i.id != next.id || i.state != InstructionStatus.Reading || i.text != next.text) {
                                i
                            } else {
                                when (reading) {
                                    is InstructionAnswers.Reading.Changes -> i.copy(state = InstructionStatus.Understood, changes = reading.changes, note = null)
                                    is InstructionAnswers.Reading.Unclear -> i.copy(state = InstructionStatus.Unclear, changes = emptyList(), note = reading.why)
                                }
                            }
                        },
                    )
                }
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
        ModelHold.Resting -> "Waiting for Claude, resting an hour after a failed call"
        ModelHold.Capped -> "Waiting for Claude: this month's cap is reached"
    }

    private fun instructionReader(): InstructionReader? = claudeKey()?.let { InstructionReader(it, clock.zone(), endpoint()) }

    /** Held while the chat is answered, so each message is answered once. */
    private val answering = Mutex()

    /**
     * Your [text] to Claude about [about] (2.0.0, Q29): kept, then answered ([answerChat]). Not while
     * one of yours still waits for its answer: false then, and nothing's kept.
     */
    suspend fun ask(text: String, about: About = About()): Boolean {
        val words = text.trim().take(ChatPrompts.MAX_TEXT)
        if (words.isEmpty()) return false
        var added = false
        chat.update { state ->
            if (state.unanswered != null) {
                state
            } else {
                added = true
                state.copy(messages = (state.messages + ChatMessage(java.util.UUID.randomUUID().toString(), Speaker.You, words, clock.now(), about)).takeLast(MAX_CHAT))
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
            known = ChatPrompts.context(state.tasks, time.events, applied, now, clock.zone()),
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
        val note = modelCalls.withLock call@{
            // Taken back (or the chat cleared) while this waited its turn: nothing to ask.
            if (chat.value.unanswered?.id != waiting.id) return@withLock
            modelHold()?.let { return@call because(it) }
            val key = claudeKey() ?: return@call because(ModelHold.Off)
            // Being asked now: whatever it said before (a failed call) is gone.
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(note = null, stuck = false) else it }) }
            val at = clock.now()
            val month = AiUsage.monthOf(at, clock.zone())
            // One snapshot of the tasks, the calendar and your changes: what Claude's shown is what its answer's checked against.
            val state = tasks.value
            val time = calendarTime
            val result = runCatching {
                ChatReader(key, clock.zone(), endpoint()).answer(chat.value.messages, state.tasks, time.events, chatContext(at, state, time, instructions.value.applied), at)
            }
                .onFailure { error ->
                    Log.w(TAG, "Claude's answer in the chat failed", error)
                    modelFailed(error, month, at)
                }
                .getOrNull() ?: return@call "Claude's call failed: it's tried again within the hour, or now with Try again"
            modelWorked(result.costUsd, result.refused, month, at)
            val reading = result.reading ?: run {
                stuck = true
                return@call if (result.refused) "Claude declined to answer that: Try again, or put it another way" else "Claude's answer couldn't be read: Try again"
            }
            val reply = ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                from = Speaker.Claude,
                text = reading.reply,
                at = clock.now(),
                proposals = reading.tasks.map { Proposal(java.util.UUID.randomUUID().toString(), task = it) } +
                    listOfNotNull(
                        reading.changes.takeIf { it.isNotEmpty() }?.let { changes ->
                            val byId = tasks.value.tasks.associateBy { it.id }
                            Proposal(java.util.UUID.randomUUID().toString(), changes = changes, lines = changes.map { Instructions.describe(it, byId, clock.zone()) })
                        },
                    ),
                note = reading.leftOut.takeIf { it.isNotEmpty() }?.let { "Left out, as the app can't use it: ${it.joinToString("; ")}" },
            )
            chat.update { s ->
                // Only while it's still the one waiting: taken back meanwhile, the answer goes with it.
                if (s.unanswered?.id != waiting.id) {
                    s
                } else {
                    s.copy(messages = (s.messages.map { if (it.id == waiting.id) it.copy(answeredAt = reply.at, note = null) else it } + reply).takeLast(MAX_CHAT))
                }
            }
            null
        }
        // Why it waits, said once: written only when it changes, as this runs after every sync.
        if (note != null && chat.value.unanswered?.let { it.id == waiting.id && (it.note != note || it.stuck != stuck) } == true) {
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = note, stuck = stuck) else it }) }
        }
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
            applyInstruction(id)
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
                            p.copy(state = ProposalState.Applied, made = made, before = before, note = null, task = task ?: p.task)
                        } else {
                            p.copy(note = note)
                        }
                    })
                },
            )
        }
    }

    /**
     * Card [proposalId] undone: the task it added deleted, or its changes taken back. One whose
     * changes were made on tasks of your own can't be (they're changed on the tasks' pages).
     */
    suspend fun undoProposal(messageId: String, proposalId: String): Unit = proposing.withLock {
        val proposal = chat.value.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId && it.state == ProposalState.Applied } ?: return@withLock
        val made = proposal.made
        if (made == null && proposal.before.isEmpty()) return@withLock
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
            val stuck = backs.mapNotNull { (t, back) -> OwnTasks.problem(back)?.let { "“${t.title}” can't go back: ${it.replaceFirstChar(Char::lowercase)}" } } +
                // And its instruction, which taking back mustn't leave tasks waiting for each other in a circle.
                listOfNotNull(made?.takeIf { m -> Instructions.makesCircle(emptyList(), instructions.value.applied.filterNot { it.id == m }) }
                    ?.let { "taking its changes back would bring back an earlier instruction that has tasks waiting for each other in a circle" })
            if (stuck.isNotEmpty()) {
                stuck.joinToString("; ")
            } else {
                backs.forEach { (t, back) -> saveOwnTask(t.sourceId, back) }
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
                            m.id == messageId && p.id == proposalId -> if (note == null) p.copy(state = ProposalState.Proposed, made = null, before = emptyMap()) else p.copy(note = note)
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
        if (instruction.changes.none { c -> c.taskId?.let(byId::get)?.let { Instructions.latePin(c, it, clock.zone()) } == true }) return false
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
            focus.plan().todaySlots.filter { it.kind == SlotKind.Work && it.chunk?.taskId in moved }
                .forEach { slot -> focus.moveSlot(slot.key, Focus.Laid(slot.start, slot.end, slot.window)) }
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
        // recalls it. What it made on your own tasks stands, still undone from the card.
        if (chat.value.messages.any { m -> m.proposals.any { it.made == id } }) {
            chat.update { s ->
                s.copy(
                    messages = s.messages.map { m ->
                        m.copy(proposals = m.proposals.map { p ->
                            when {
                                p.made != id -> p
                                p.before.isEmpty() -> p.copy(state = ProposalState.Proposed, made = null)
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
                },
            )
        }
    }

    companion object {
        const val TAG = "Decrastination"

        /** How far ahead your instructions' days and times are laid out for the plan: as far as it reaches. */
        private const val PLAN_REACH_DAYS = 90L

        /** Past the model's hour of rest, so the retry finds it over. */
        private const val RETRY_SLACK_MS = 60_000L

        /** [requestTeamsSync]'s answer when the widget is syncing already: as good as one started. */
        const val TEAMS_BUSY = "The Teams widget is already syncing"

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L
        private const val WIDGET_DEBOUNCE_MS = 1_000L

        private const val CALENDAR_QUIET_MS = 5_000L

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
