package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.block.Slots
import com.thomaswcode.decrastination.calendar.CalendarWriter
import com.thomaswcode.decrastination.calendar.SlotCalendar
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.withEnrichment
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
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Readings
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
import com.thomaswcode.decrastination.sync.AndroidSyncPorts
import com.thomaswcode.decrastination.sync.Sync
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

    private val syncer = Syncer(
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

    /**
     * What the app reads ([Sync]): the sources through the syncer, the Teams widget asked and watched,
     * and the calendar as busy time. Made before the plan, which is made again as the calendar's read.
     */
    val sync: Sync = Sync(
        tasks, settings, runtime, stores.instructions, clock, scope, syncer, AndroidSyncPorts(app),
        forgetPlan = { focus.forgetPlan() },
        watchCalendar = { calendarWriter.watchCalendar() },
        askAboutEvent = { key, title, day, start, answers -> conversation.askAboutEvent(key, title, day, start, answers) },
        settleEvent = { conversation.settleEvent(it) },
        replan = { planning.watcher.poke() },
    )

    /**
     * The plan ([Planning]): made from the stores as they are, made again as they change, today's slot
     * starts kept, and the work done ahead after each. A new plan's hooks are the app's: the slot
     * alarms, the calendar, the widget.
     */
    val planning: Planning = Planning(
        tasks, settings, runtime, log, stores.instructions, school, clock, scope, gateway,
        calendarTime = { sync.calendarTime },
        calendarChanges = sync.calendarState,
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
            sync.refreshCalendar()
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
        calendarTime = { sync.calendarTime },
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
        calendarTime = { sync.calendarTime },
        answered = { record, words -> looks.answered(record, words) },
    )

    /**
     * Your instructions ([Instructions]): written and read by Claude, or quick changes of yours; applied,
     * refused, taken back, and laid over everything they change. Made after the plan and the focus, which it uses.
     */
    val instructions: Instructions = Instructions(
        stores.instructions, tasks, settings, runtime, school, chat, clock, scope, gateway, focus, planning,
        calendarTime = { sync.calendarTime },
        profile = { conversation.profile() },
        reread = {
            sync.refreshCalendar()
            Briefing.replanToday(app)
        },
        redraw = { WidgetUpdater.update(app) },
    )

    /** Your own tasks ([OwnTasks]): kept, finished, opened again, deleted, and let go once long done. */
    val ownTasks: OwnTasks = OwnTasks(tasks, stores.instructions, chat, clock, scope) { settleCompletions() }

    /** The tasks' readings ([Readings]): by Claude while it can be asked, by the rules otherwise; one read again on asking. */
    val readings: Readings = Readings(
        tasks, runtime, clock, scope, gateway,
        profile = { conversation.profile() },
        queueQuestion = { taskId, question, now -> looks.queueQuestion(taskId, question, now) },
        readDecks = { SyncWorker.syncNow(app, setOf(Source.Anki)) },
    )

    /** Photos ([Photos]): of a task's work, of homework to make tasks from, and of written work checked. */
    val photos: Photos = Photos(
        tasks, settings, chat, clock, scope, gateway, focus, File(app.filesDir, "photos"),
        profile = { conversation.profile() },
        read = { taskId -> readings.enrichNow(only = taskId) },
    )

    /** Protection ([Protection]): settings changes, waiting once armed if they loosen blocking; the parent code; the device admin. */
    val protection = Protection(settings, runtime, secrets, clock, scope, adminActive = { Watchdog.isAdminActive(app) }) {
        app.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(app))
    }

    init {
        // The device admin kept in step with arming ([Protection.start]).
        protection.start()
        // Work a source confirms done earns free time, is logged and asked about.
        syncer.addListener {
            settleCompletions()
            sync.refreshCalendar()
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
        scope.launch { sync.refreshCalendar() }
        scope.launch(Dispatchers.IO) { runCatching { photos.sweepPhotos() } }
        // What's read again as it changes: the calendar, the calendars picked, Anki's settings, the Teams widget's list ([Sync.start]).
        sync.start(calendarWriter.calendarChanged)
        // The calendar picked, and each plan once plans have been quiet a while, written ([CalendarWriter.start]).
        calendarWriter.start()
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
                runCatching { sync.refreshCalendar() }.onFailure { Log.w(TAG, "Reading the calendar failed", it) }
                conversation.answerChat()
            }
        }
        // A key problem standing from before, whose alert couldn't be shown then.
        gateway.reconcileAlerts()
        // Dropped plans' warnings kept in step with the tasks ([Readings.start]).
        readings.start()
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
                sync.refreshCalendar()
                Briefing.replanToday(app)
            },
            cancelFelix = { FelixNotifications.cancel(app) },
        )
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

        private const val WIDGET_DEBOUNCE_MS = 1_000L

        /** After a failed call, the model is left alone this long. */

        /** Unreadable files Setup lists at most: the latest. */

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
