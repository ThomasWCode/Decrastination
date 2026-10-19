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
import com.thomaswcode.decrastination.calendar.CalendarWriter
import com.thomaswcode.decrastination.calendar.SlotCalendar
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.ProposalState
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
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.AndroidModelAlerts
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.Failures
import com.thomaswcode.decrastination.enrich.Failure
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.Enricher
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.InstructionReader
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.felix.AndroidFelixPorts
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixNotifications
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.notify.AndroidTray
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notifier
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.protect.Protection
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
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
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

    /** Your chat with Felix: what you said, his answers, and what became of his cards. */
    val chat = stores.chat

    /** The chat before its live window (D-96): kept for you, shown on scrolling back and in the record's export. */
    val chatArchive = stores.chatArchive

    /** Days with no school and half-days (PL-16), told to the chat or added in Settings. */
    val school = stores.school

    /** What Felix remembers (AI-21): facts, and standing instructions. */
    val memory = stores.memory

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
        // The chat's archive is read when it's first wanted, not here: a file found unreadable then is put
        // on record, and what was kept of it written, as one found at the start is.
        stores.chatArchive.onProblem = { found ->
            Log.w(TAG, "${found.file} couldn't be read whole; set aside as ${found.copy}")
            scope.launch {
                runCatching { runtime.update { it.withUnreadable(listOf(found)) } }
                    .onSuccess { stores.keepRecovered() }
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
    val planning: Planning = Planning(
        tasks, settings, runtime, log, stores.instructions, school, clock, scope, gateway,
        calendarTime = { calendarTime },
        calendarChanges = calendarState,
        sweep = { slots.sweep() },
        hooks = listOf(
            { made -> SlotAlarms.arm(app, made, clock.now()) },
            // The calendar once plans have been quiet a while ([CalendarWriter.planned]).
            { made -> calendarWriter.planned(made) },
            { _ -> WidgetUpdater.update(app) },
        ),
        profile = { conversation.profile(it) },
        draftAhead = { made, now -> looks.draftAhead(made, now) },
        onFailure = { Log.w(TAG, "Replanning failed", it) },
    )

    /** The blocker's state and decisions (block/Focus.kt). */
    val focus = Focus(
        tasks, settings, runtime, log, clock,
        onOwnTaskDone = { ownTasks.finishOwnTask(it) },
        // A school day by the school calendar (BL-02): a day off blocks from the weekend's time.
        schoolDay = { com.thomaswcode.decrastination.core.SchoolDays.kindOf(it, school.value) == com.thomaswcode.decrastination.core.DayKind.Weekday },
    ) { state, s, now -> planning.plan(state, s, now) }

    /**
     * The plan written to the calendar you picked ([CalendarWriter]): once plans have been quiet a
     * while, a slot you've moved there taken as your time; a calendar no longer written to cleared; the
     * calendar watched for changes; and your answers about its events.
     */
    val calendarWriter = CalendarWriter(
        tasks, settings, runtime, stores.calendarVersions, clock, scope, SlotCalendar(app),
        sayRefused = { move, zone, why -> SlotCalendar.sayRefused(app, move, zone, why) },
        addOwnChange = { id, text, about, change -> instructions.addOwnChange(id, text, about, change) },
        replan = { planning.watcher.poke() },
        settleEvent = { conversation.settleEvent(it) },
        reread = {
            CalendarTime.refresh(app)
            Briefing.replanToday(app)
        },
    )

    /** Today's slots as they pass: begun, or missed (PL-13). */
    val slots = Slots(this)

    /** Time in AnkiDroid as work (NF-03, DX-11). */
    val anki = com.thomaswcode.decrastination.block.AnkiWatch(this)

    /** What Felix's service asks of Android: his notification, and the work and alarms that outlive what asked. */
    private val felixPorts = AndroidFelixPorts(app)

    /** Where every message in Felix's name is written, the chat first, and his one notification made from it. */
    private val outbox = Outbox(chat, stores.chatArchive, settings, notifier, felixPorts, chatOnScreen = { conversation.chatInFront }, clock)

    /** The voice of the app's messages (LR-03). */
    val looks: Looks = Looks(
        tasks, settings, runtime, log, chat, clock, scope, gateway, focus, planning, felixPorts, outbox,
        calendarTime = { calendarTime },
        profile = { conversation.profile(it) },
        remember = { conversation.remember(it) },
        addInstruction = { text, about, answer -> instructions.addInstruction(text, about, answer) },
        assess = { taskId, doneAt, answer -> Assessment.record(app, taskId, doneAt, answer) },
    )

    /**
     * Felix's conversation ([Conversation]): the chat, his answers and what they make, what he
     * remembers, the replies from his notification, the week's check-in, and what the app says in his name.
     */
    val conversation: Conversation = Conversation(
        tasks, settings, runtime, log, stores.instructions, chat, school, memory, clock, scope, gateway, notifier, focus, planning, felixPorts, outbox,
        owners = object : Conversation.Owners {
            override suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = ownTasks.saveOwnTask(sourceId, task)
            override suspend fun finishOwnTask(taskId: String) { ownTasks.finishOwnTask(taskId) }
            override suspend fun reopenOwnTask(taskId: String) { ownTasks.reopenOwnTask(taskId) }
            override suspend fun deleteOwnTask(taskId: String) { ownTasks.deleteOwnTask(taskId) }
            override suspend fun applyInstruction(id: String) { instructions.applyInstruction(id) }
            override suspend fun deleteInstruction(id: String) { instructions.deleteInstruction(id) }
            override suspend fun layTaskOverrides() { instructions.layTaskOverrides() }
            override suspend fun changeSettings(change: (Settings) -> Settings) { protection.changeSettings(change) }
            override suspend fun answerEvent(key: String, answer: String) {
                calendarWriter.answer(key, answer)
            }
        },
        calendarTime = { calendarTime },
        answered = { record, words -> looks.answered(record, words) },
    )

    /**
     * Your instructions ([Instructions]): written and read by Claude, or quick changes of yours; applied,
     * refused, taken back, and laid over everything they change. Made after the plan and the focus, which it uses.
     */
    val instructions: Instructions = Instructions(
        stores.instructions, tasks, settings, runtime, school, chat, clock, scope, gateway, focus, planning,
        calendarTime = { calendarTime },
        profile = { conversation.profile() },
        reread = {
            CalendarTime.refresh(app)
            Briefing.replanToday(app)
        },
        redraw = { WidgetUpdater.update(app) },
    )

    /** Your own tasks ([OwnTasks]): kept, finished, opened again, deleted, and let go once long done. */
    val ownTasks: OwnTasks = OwnTasks(tasks, stores.instructions, chat, clock, scope) { settleCompletions() }

    // [Instructions]'s and [OwnTasks]'s functions, till their callers call them there.
    suspend fun addInstruction(text: String, about: About, answer: Boolean = false): String = instructions.addInstruction(text, about, answer)
    suspend fun addOwnChange(id: String, text: String, about: About, change: Change): Unit = instructions.addOwnChange(id, text, about, change)
    suspend fun laterToday(key: String): Long? = instructions.laterToday(key)
    suspend fun takeBackQuick(taskId: String, type: ChangeType): Unit = instructions.takeBackQuick(taskId, type)
    fun quickInForce(taskId: String, type: ChangeType): Boolean = instructions.quickInForce(taskId, type)
    suspend fun rereadInstruction(id: String, text: String) = instructions.rereadInstruction(id, text)
    suspend fun readInstructions(interactive: Boolean = false) = instructions.readInstructions(interactive)
    suspend fun applyInstruction(id: String): Unit = instructions.applyInstruction(id)
    suspend fun deleteInstruction(id: String): Unit = instructions.deleteInstruction(id)
    suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = ownTasks.saveOwnTask(sourceId, task)
    suspend fun finishOwnTask(taskId: String): Unit = ownTasks.finishOwnTask(taskId)
    suspend fun reopenOwnTask(taskId: String) = ownTasks.reopenOwnTask(taskId)
    suspend fun deleteOwnTask(taskId: String) = ownTasks.deleteOwnTask(taskId)

    /** Protection ([Protection]): settings changes, waiting once armed if they loosen blocking; the parent code; the device admin. */
    val protection = Protection(settings, runtime, secrets, clock, scope, adminActive = { Watchdog.isAdminActive(app) }) {
        app.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(app))
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

    init {
        // The device admin kept in step with arming ([Protection.start]).
        protection.start()
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
        // "How was it?" questions kept for his evening question, asked in the app's words once Claude's off.
        syncer.addAfterEverySync { conversation.askKept() }
        // A task back after aging out of the list (or new) takes the overrides its instructions give.
        syncer.addAfterEverySync { instructions.layTaskOverrides() }
        // Your own tasks long done go, as a source's do.
        syncer.addAfterEverySync { ownTasks.pruneOwn() }
        // A question of yours still waiting (Claude was off, resting, or the app stopped): tried again.
        syncer.addAfterEverySync { if (chat.value.unanswered != null) conversation.answerChat() }
        // One of your own whose steps are all done is finished ([OwnTasks.start]).
        ownTasks.start()
        // Instructions left waiting for Claude, once it can be asked (a new month under the cap
        // changes nothing it watches).
        syncer.addAfterEverySync { if (instructions.value.instructions.any { it.state == InstructionStatus.Reading }) instructions.readInstructions() }
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
            calendarWriter.calendarChanged.debounce(CALENDAR_QUIET_MS).collect {
                CalendarTime.refresh(app)
                planning.watcher.poke()
            }
        }
        // The calendar picked, and each plan once plans have been quiet a while, written ([CalendarWriter.start]).
        calendarWriter.start()
        // The calendar written to, or those not read, changed (NF-07, DX-13): the calendar's read again.
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
                    instructions.readInstructions()
                    conversation.answerChat()
                }
            }
        }
        // Instructions left waiting for Claude (off, resting, or the app stopped mid-read); and the
        // tasks' overrides made what the applied ones say, should a stop have come between the two.
        scope.launch {
            instructions.layTaskOverrides()
            instructions.readInstructions()
            // A question of yours the app stopped before answering, once the calendar's read, as it's
            // told the fortnight's events.
            if (chat.value.unanswered != null) {
                runCatching { CalendarTime.refresh(app) }.onFailure { Log.w(TAG, "Reading the calendar failed", it) }
                conversation.answerChat()
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
            planning.watcher.start()
        }
        // The files older shapes came from, a week after their migration, at a start that's got this far.
        scope.launch(Dispatchers.IO) { runCatching { stores.letGoOfOldShapes() }.onFailure { Log.w(TAG, "Letting go of old shapes failed", it) } }
        // The notifications Felix's messages have taken the place of, one still showing or held from
        // before let go; then his own, made from the chat as it is.
        scope.launch {
            runCatching { notifier.retire(NoticeId.RETIRED) }.onFailure { Log.w(TAG, "Letting go of the retired notifications failed", it) }
            outbox.notify(clock.now())
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

    /** Backups and resets ([BackupService]): made when first asked for, as it takes the instructions' lock. */
    val backups: BackupService by lazy {
        BackupService(
            stores, clock, scope, AndroidBackupFolder(app.contentResolver), File(app.filesDir, RESET_COPIES), BuildConfig.VERSION_NAME,
            changeSettings = { change -> protection.changeSettings(change) },
            applying = instructions.applying,
            layInstructions = { instructions.layInstructions() },
            layTaskOverrides = { instructions.layTaskOverrides() },
            schedule = { Daily.schedule(app) },
            reread = {
                CalendarTime.refresh(app)
                Briefing.replanToday(app)
            },
            cancelFelix = { FelixNotifications.cancel(app) },
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
        val reader = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.PhotoToTask)?.let { com.thomaswcode.decrastination.enrich.PhotoTaskReader(it, profile = conversation.profile()) } ?: return null to "Claude is off: add it with New task."
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
            m.acts.filter { it.action?.type == com.thomaswcode.decrastination.core.ChatAction.Type.Delete && com.thomaswcode.decrastination.core.ChatActs.canUndo(it, tasks.value.tasks, settings.value, messages) }
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
            ?.let { ClaudeEnricher(it, clock.zone(), profile = { conversation.profile() }, decks = { tasks.value.deckTopics }) }?.takeIf { gateway.available() }
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
            looks.queueQuestion(task.id, made.question, now)
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
        protection.changeSettings { com.thomaswcode.decrastination.learn.ReviewInput.set(it, change.setting, before) }
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

    /** Where a reset's copy goes when Documents can't take it. */
    private val RESET_COPIES = "backups"

    /**
     * The [week]'s summary for home (NF-02), written by the model after its review and kept with it;
     * then Felix's offer to send it, in the chat with the button that opens it in an email. Nothing
     * while Claude can't be asked, or the week has no review. The summary, or null.
     */
    suspend fun writeSummary(week: String): String? {
        val client = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Summary) ?: return null
        if (log.value.reviews.none { it.week == week }) return null
        val zone = clock.zone()
        val input = com.thomaswcode.decrastination.learn.Summary.input(log.value, instructions.value.applied, week, zone, chat.value.messages)
        val writer = com.thomaswcode.decrastination.enrich.SummaryWriter(client)
        val called = gateway.call(
            interactive = false,
            record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Summary, "the week's summary") { r: com.thomaswcode.decrastination.enrich.SummaryWriter.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.text == null) },
            cost = { r: com.thomaswcode.decrastination.enrich.SummaryWriter.Result -> r.costUsd to r.refused },
        ) { at -> writer.write(conversation.profile(at).text, input) }
        val text = (called as? Called.Answered)?.value?.text ?: return null
        log.update { l -> l.copy(reviews = l.reviews.map { if (it.week == week) it.copy(summary = text) else it }) }
        // His offer in the chat, held from his notification in quiet hours till the morning (LR-07).
        conversation.summaryReady(week)
        return text
    }

    companion object {
        const val TAG = "Decrastination"

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

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
