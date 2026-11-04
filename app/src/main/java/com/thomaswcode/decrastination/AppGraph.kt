package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import com.thomaswcode.decrastination.block.AnkiWatch
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.block.Slots
import com.thomaswcode.decrastination.block.TimerAlarms
import com.thomaswcode.decrastination.calendar.CalendarWriter
import com.thomaswcode.decrastination.calendar.SlotCalendar
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.ReplayClock
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.AndroidBackupFolder
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.enrich.AndroidModelAlerts
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ReadingPrompts
import com.thomaswcode.decrastination.enrich.Readings
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.felix.AndroidFelixPorts
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixNotifications
import com.thomaswcode.decrastination.felix.FelixRules
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.Calibrator
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.DailyAlarms
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.notify.AndroidTray
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notifier
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sources.anki.AnkiSource
import com.thomaswcode.decrastination.sources.gmail.GmailSource
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerApi
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSource
import com.thomaswcode.decrastination.sources.teams.TeamsSource
import com.thomaswcode.decrastination.sync.AndroidSyncPorts
import com.thomaswcode.decrastination.sync.AndroidGmailPorts
import com.thomaswcode.decrastination.sync.GmailFinishes
import com.thomaswcode.decrastination.sync.Replica
import com.thomaswcode.decrastination.sync.ReplicaWorker
import com.thomaswcode.decrastination.sync.Sync
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.Answers
import com.thomaswcode.decrastination.sync.protocol.AskAboutEvent
import com.thomaswcode.decrastination.sync.protocol.AttachPhoto
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.ConfirmGmail
import com.thomaswcode.decrastination.sync.protocol.FinishGmail
import com.thomaswcode.decrastination.sync.protocol.SortGmail
import com.thomaswcode.decrastination.sync.protocol.UnfinishGmail
import com.thomaswcode.decrastination.sync.protocol.NoteSource
import com.thomaswcode.decrastination.sync.protocol.ObserveCalendar
import com.thomaswcode.decrastination.sync.protocol.ObserveSources
import com.thomaswcode.decrastination.sync.protocol.Owners
import com.thomaswcode.decrastination.sync.protocol.ParentCodeSet
import com.thomaswcode.decrastination.sync.protocol.RecordBlock
import com.thomaswcode.decrastination.sync.protocol.RecordProtection
import com.thomaswcode.decrastination.sync.protocol.ResetData
import com.thomaswcode.decrastination.sync.protocol.SettleEvent
import com.thomaswcode.decrastination.sync.protocol.SpendFreeTime
import com.thomaswcode.decrastination.sync.protocol.TimerOver
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import com.thomaswcode.decrastination.ui.Moves
import com.thomaswcode.decrastination.ui.PieceSays
import com.thomaswcode.decrastination.widget.WidgetUpdater
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The app's singletons. The sync, the focus service, the widget and the screens all run in the
 * app's one process and share them, so every part sees the same stores.
 */
@OptIn(FlowPreview::class)
class AppGraph private constructor(context: Context) : Owners {
    val app: Context = context.applicationContext
    val clock: WallClock = ReplayClock(DeviceClock(app))

    /** Work that outlives the screen or receiver that started it; a failure is logged, not fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(TAG, "Background work failed", error) })

    /**
     * Every store, made first, each with its version and its migrations ([Stores]): a file of an older
     * shape brought to this one as it's read. A settings file that can't be read at all starts armed
     * while the device admin is on, as only arming turns it on (IMP-P1-003).
     */
    val stores = Stores(app.filesDir, KeystoreCipher(), armedIfUnreadable = { Watchdog.isAdminActive(app) })
    override val tasks = stores.tasks
    val settings = stores.settings
    val secrets = stores.secrets
    val runtime = stores.runtime
    val log = stores.log

    /**
     * Whether a change goes to the server as a command (D-140), the two keeping in step by commands: what only this phone can know
     * goes as one too (W-24), as a pull takes the server's stores whole, and what the server runs isn't run here. Till then the
     * stores carry it all, whole.
     */
    val sendsCommands: () -> Boolean = { stores.sync.value.mode == SyncState.COMMANDS }

    /** Every notification the app posts, held and counted by the rules (LR-07): the one place they're posted ([NoticeId] names them). */
    val notifier = Notifier(AndroidTray(app), runtime, settings, clock, scope)

    /**
     * Every call to Claude, its holds and its record, and its alerts ([ModelGateway]). None from here once the phone sends commands,
     * whatever asks: Claude's the server's then (D-142), one place, one cap, one record.
     */
    val gateway = ModelGateway(settings, { secrets[Secret.AnthropicApiKey].takeUnless { sendsCommands() } }, stores.model, tasks, clock, AndroidModelAlerts(app), scope) { EnrichWorker.retryAfter(app, it) }

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
        if (Calibrator.upgraded(stores.learning.value.calibration, log.value.completions) != null) {
            scope.launch { stores.learning.update { state -> Calibrator.upgraded(state.calibration, log.value.completions)?.let { state.copy(calibration = it) } ?: state } }
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
        ankiRates = { stores.learning.value.calibration.ankiSecondsPerCard },
        observing = sendsCommands,
        observed = { seen -> commands.act(ObserveSources(seen)) },
    )

    override val sources: Syncer get() = syncer

    /**
     * What the app reads ([Sync]): the sources through the syncer, the Teams widget asked and watched,
     * and the calendar as busy time. Made before the plan, which is made again as the calendar's read.
     */
    val sync: Sync = Sync(
        settings, stores.calendar, stores.instructions, clock, scope, syncer, AndroidSyncPorts(app),
        forgetPlan = { focus.forgetPlan() },
        watchCalendar = { calendarWriter.watchCalendar() },
        // What this phone's calendar and what it's asked decide, as a command once the two keep in step by them (W-24).
        // On record as asked once asked: here at once, or as its command's applied here ([eventAsked]).
        askAboutEvent = { key, title, day, start, answers ->
            if (sendsCommands()) {
                commands.send(AskAboutEvent(key, title, day, start, answers))
            } else {
                conversation.askAboutEvent(key, title, day, start, answers)
                sync.eventAsked(key)
            }
        },
        settleEvent = { key -> settleEvent(key) },
        replan = { planning.watcher.poke() },
        noteSource = { source, note -> if (sendsCommands()) commands.send(NoteSource(source, note)) else syncer.note(source, note) },
    )

    /**
     * The plan ([Planning]): made from the stores as they are, made again as they change, today's slot
     * starts kept, and the work done ahead after each. A new plan's hooks are the app's: the blocker's
     * plan let go, the slot alarms, your reminders and their alarm, the calendar, the widget.
     */
    val planning: Planning = Planning(
        tasks, settings, stores.session, stores.learning, stores.model, log, stores.instructions, school, clock, scope, gateway,
        calendarTime = { sync.calendarTime },
        calendarChanges = sync.calendarState,
        sweep = { slots.sweep() },
        hooks = listOf(
            // The blocker's plan kept for the minute let go: this one may come of what that isn't kept by (your pace, the
            // session, the school calendar, your instructions), so his looks and a reminder's alarm don't go by the last.
            { _ -> focus.forgetPlan() },
            // The timer on a piece of a task finished, deleted or marked not a task anywhere (your own, a sync) ended with it.
            { _ -> focus.settleTimer() },
            { made -> SlotAlarms.arm(app, made, clock.now()) },
            // Your reminders: any this plan lets go (its task closed) or brings due said and sent, with no call (one
            // whose words are to be asked for afresh is left to work of its own), then their alarm set by it, as a
            // slot's or a deadline's moment moves with it: not at once for one waiting for his words, as its moment's gone by.
            { made ->
                val asking = runCatching { looks.sweepReminders(plan = made) }.onFailure { Log.w(TAG, "Sending the reminders due failed", it) }.getOrNull() == true
                armReminder(made, after = if (asking) clock.now() + SlotAlarms.REMINDER_RETRY_MS else null)
            },
            // The calendar once plans have been quiet a while ([CalendarWriter.planned]).
            // Only from slots the server's confirmed (W-15): with a change of the phone's still to reach it, this plan is the phone's
            // alone, so the calendar waits; it's written once that's gone and the plan's made again.
            { made -> if (!sendsCommands() || stores.outbox.value.commands.isEmpty()) calendarWriter.planned(made) },
            { _ -> WidgetUpdater.update(app) },
        ),
        profile = { conversation.profile(it) },
        draftAhead = { made, now -> looks.draftAhead(made, now) },
        onFailure = { Log.w(TAG, "Replanning failed", it) },
        notes = { task -> Notes.forTask(task, stores.felix.value.notes, settings.value.classMap).map { it.text } },
    )

    /**
     * A starred email finished here (D-7 as amended): its write to Gmail journaled, made by its worker
     * after the grace, taken back; the tasks as the plan takes them, and the Gmail read after a write asked of Sync.
     */
    val gmailFinishes = GmailFinishes(
        stores.gmailWrites,
        settings = { settings.value },
        task = { id -> planning.asPlanned().firstOrNull { it.id == id } },
        multiplier = { stores.learning.value.calibration.multiplierFor(it) },
        clock = clock,
        ports = AndroidGmailPorts(app, notifier, clock) { sources -> sync.syncNow(sources) },
        readAt = { tasks.value.status(Source.Gmail).lastSuccessStartedAt },
    )

    override val gmail: GmailFinishes get() = gmailFinishes

    /**
     * Whether this phone makes the journal's writes to Gmail, and keeps the journal itself (settled after a read, set going again
     * at a start, emptied of what's not started when finishing's switched off): while it mirrors. Once it sends commands, the server
     * does, by its own sign-in (D-143), and the journal's pulled; journaled here still, inside the commands that finish an email.
     */
    fun gmailWrites(): Boolean = !sendsCommands()

    /** [taskId], a starred email, finished at your word (a task's sheet): a command once the phone sends them ([FinishGmail]). */
    suspend fun finishGmail(taskId: String): GmailFinishes.Finish = if (sendsCommands()) commands.act(FinishGmail(taskId)) else gmailFinishes.finish(taskId, explicit = true)

    /** [taskId]'s finish taken back (its sheet's Undo): a command once the phone sends them ([UnfinishGmail]). */
    suspend fun unfinishGmail(taskId: String): Boolean = if (sendsCommands()) commands.act(UnfinishGmail(taskId)) else gmailFinishes.unfinish(taskId)

    /** [taskId]'s write given up, sorted (its sheet's It's sorted): a command once the phone sends them ([SortGmail]). */
    suspend fun sortGmail(taskId: String) = if (sendsCommands()) commands.act(SortGmail(taskId)) else gmailFinishes.sorted(taskId)

    /** The very first finish's OK given, or refused: a command once the phone sends them ([ConfirmGmail]). */
    suspend fun confirmGmail(confirmed: Boolean) {
        if (sendsCommands()) commands.act(ConfirmGmail(confirmed)) else if (confirmed) gmailFinishes.confirm() else gmailFinishes.refuse()
    }

    /** The blocker's state and decisions (block/Focus.kt). */
    override val focus: Focus = Focus(
        tasks, settings, stores.session, stores.learning, stores.protection, log, clock,
        onOwnTaskDone = { ownTasks.finishOwnTask(it) },
        // An email's work all done by a keep or a tick is finished in Gmail; taken back, so is its finish, once its work isn't all done.
        // Journaled here as on the server, inside the command that does the work, once the phone sends them: the journal's the same on
        // both, and its writes to Gmail are the server's ([gmailWrites]).
        onWorked = { runCatching { gmailFinishes.finish(it, explicit = false) }.onFailure { e -> Log.w(TAG, "Finishing an email in Gmail failed", e) } },
        onUnworked = { runCatching { gmailFinishes.unworked(it) }.onFailure { e -> Log.w(TAG, "Taking back an email's finish failed", e) } },
        // A school day by the school calendar (BL-02): a day off blocks from the weekend's time.
        schoolDay = { SchoolDays.kindOf(it, school.value) == DayKind.Weekday },
        forceActiveUntil = { stores.device.value.forceActiveUntil },
        // Free time spent, sent once the two keep in step by commands, merged with the one before till it goes (W-24).
        saveSpend = { ms, day -> if (sendsCommands()) commands.send(SpendFreeTime(day.toString(), ms)) else focus.spend(ms, day) },
    ) { state, s, now -> planning.plan(state, s, now) }

    /**
     * The plan written to the calendar you picked ([CalendarWriter]): once plans have been quiet a
     * while, a slot you've moved there taken as your time; a calendar no longer written to cleared; the
     * calendar watched for changes; and your answers about its events.
     */
    val calendarWriter = CalendarWriter(
        tasks, settings, stores.model, stores.calendar, clock, scope, SlotCalendar(app),
        sayRefused = { move, zone, why -> SlotCalendar.sayRefused(app, move, zone, why) },
        // A slot moved by hand in the calendar: what only this phone sees, as a command once the two keep in step by them.
        addOwnChange = { id, text, about, change -> if (sendsCommands()) commands.send(AddOwnChange(id, text, about, change)) else instructions.addOwnChange(id, text, about, change) },
        replan = { planning.watcher.poke() },
        // An answer here (the Day's event sheet, his question's chips): his questions on it settled on the server too.
        settleEvent = { key -> settleEvent(key) },
        reread = {
            sync.refreshCalendar()
            briefing.replanToday()
        },
        busyTimes = { from, to -> InstructionRules.busyBlocks(instructions.value.applied, from, to) },
        sayPutBack = { want, deleted, why -> SlotCalendar.sayPutBack(app, want, deleted, why) },
    )

    /** Today's slots as they pass: begun, or missed (PL-13). */
    val slots = Slots(stores.session, settings, tasks, school, clock, planning, focus)

    /** Time in AnkiDroid as work (NF-03, DX-11). */
    val anki = AnkiWatch(
        stores.anki, stores.learning, tasks, clock, scope, focus, planning,
        readAnki = { sync.sync(setOf(Source.Anki)) },
        ankiPackage = AnkiProvider.PACKAGE,
        decks = { if (AnkiProvider.hasPermission(app)) AnkiProvider.decks(app.contentResolver) else null },
        noteActivity = { taskIds, now -> looks.noteActivity(taskIds, now) },
        observing = sendsCommands,
        send = { commands.send(it) },
    )

    override val ankiWatch: AnkiWatch get() = anki

    /** What Felix's service asks of Android: his notification, and the work and alarms that outlive what asked. */
    private val felixPorts = AndroidFelixPorts(app)

    /** Where every message in Felix's name is written, the chat first, and his one notification made from it. */
    private val outbox = Outbox(chat, stores.chatArchive, settings, notifier, felixPorts, chatOnScreen = { conversation.chatInFront }, chatSeen = { conversation.chatSeen }, clock)

    /** The voice of the app's messages (LR-03). */
    override val looks: Looks = Looks(
        tasks, settings, stores.model, log, chat, stores.felix, clock, scope, gateway, focus, planning, felixPorts, outbox,
        calendarTime = { sync.calendarTime },
        profile = { conversation.profile(it) },
        remember = { conversation.remember(it) },
        addInstruction = { text, about, answer, question -> instructions.addInstruction(text, about, answer, question) },
        assess = { taskId, doneAt, answer -> learning.recordAssessment(taskId, doneAt, answer) },
        applyProposal = { messageId, proposalId -> conversation.applyProposal(messageId, proposalId) },
        byServer = sendsCommands,
    )

    /**
     * Felix's conversation ([Conversation]): the chat, his answers and what they make, what he
     * remembers, the replies from his notification, the week's check-in, and what the app says in his name.
     */
    override val conversation: Conversation = Conversation(
        tasks, settings, stores.learning, log, stores.instructions, chat, school, memory, stores.felix, clock, scope, gateway, notifier, focus, planning, felixPorts, outbox,
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
            override suspend fun recordCheckIn(checkIn: CheckIn, now: Long): Boolean = learning.recordCheckIn(checkIn, now)
            override suspend fun finishGmail(taskId: String): GmailFinishes.Finish = gmailFinishes.finish(taskId, explicit = true)
            override suspend fun unfinishGmail(taskId: String, writeId: String?): Boolean = gmailFinishes.unfinish(taskId, writeId)
            override fun gmailWriteOf(taskId: String): String? = gmailFinishes.latestFinish(taskId)
        },
        calendarTime = { sync.calendarTime },
        answered = { record, words -> looks.answered(record, words) },
        draftReminders = { now -> looks.draftReminders(now) },
        requestSync = { sources -> sync.syncNow(sources) },
        requestTeamsSync = { sync.requestTeamsSync() },
        byServer = sendsCommands,
    )

    /** The morning: the day's record, what the night held back, and his message on the day ([Briefing]). */
    val briefing: Briefing = Briefing(planning, log, runtime, school, clock, notifier, { sync.refreshCalendar() }, looks, conversation, byServer = sendsCommands)

    /**
     * Your instructions ([Instructions]): written and read by Claude, or quick changes of yours; applied,
     * refused, taken back, and laid over everything they change. Made after the plan and the focus, which it uses.
     */
    override val instructions: Instructions = Instructions(
        stores.instructions, tasks, settings, stores.session, school, chat, clock, scope, gateway, focus, planning,
        calendarTime = { sync.calendarTime },
        profile = { conversation.profile() },
        reread = {
            sync.refreshCalendar()
            briefing.replanToday()
        },
        redraw = { WidgetUpdater.update(app) },
        answerRead = { instruction, answers, changed -> looks.answerRead(instruction, answers, changed) },
        question = { looks.questionFor(it) },
        byServer = sendsCommands,
    )

    /** Where a screen's changes go (D-140): each a command, applied through its owner. */
    val commands = Commands(this, clock, stores.outbox, queuing = { stores.sync.value.mode == SyncState.COMMANDS }, unfinished = { replica.pullEverything() })

    /** [action] applied as a command, its answer the owner's. */
    suspend fun <R, A> act(action: A): R where A : Action, A : Answers<R> = commands.act(action)

    /** A block as the blocker saw it, on record: sent as a command once the two keep in step by them (W-24). */
    suspend fun recordBlock(target: Focus.Target, reason: BlockPolicy.Reason) =
        if (sendsCommands()) commands.send(RecordBlock(target.name, reason.name)) else focus.recordBlock(target, reason)

    /** The timer's zero reached and not yet told: told, and sent as a command once the two keep in step by them. Whether it was. */
    suspend fun timerOver(now: Long): Boolean = if (sendsCommands()) commands.act(TimerOver) else focus.timerOver(now)

    /** Protection as the watchdog found it, on record, sent likewise. */
    suspend fun recordProtection(problems: List<String>, repaired: Boolean) =
        if (sendsCommands()) commands.send(RecordProtection(problems, repaired)) else focus.recordProtection(problems, repaired)

    /** A piece moved by hand ([Moves]). */
    val moves: Moves = Moves(tasks, instructions, commands, planning, clock, scope)

    /** What's said of a piece as it's done. */
    val pieceSays: PieceSays = PieceSays(focus, log, settings, stores.learning, planning, clock)

    /** Your own tasks ([OwnTasks]): kept, finished, opened again, deleted, and trimmed to their record once long done. */
    override val ownTasks: OwnTasks = OwnTasks(tasks, stores.instructions, chat, clock, scope) { settleCompletions() }

    /**
     * The phone and the server ([Replica]): this phone paired, and what's changed sent. What Felix said on the server, pulled, is
     * heard as his notification, as what he says here is ([Outbox.heard]).
     */
    val replica: Replica = Replica(
        stores, UrlConnectionHttp(), clock, BuildConfig.VERSION_NAME, calendar = { sync.calendarRead() }, commands,
        photoHttp = UrlConnectionHttp(readTimeoutMs = Replica.PHOTO_WAIT_MS),
    ) { fresh -> outbox.heard(clock.now(), fresh) }

    /** The tasks' readings ([Readings]): by Claude while it can be asked, by the rules otherwise; one read again on asking. */
    val readings: Readings = Readings(
        tasks, stores.model, clock, scope, gateway,
        profile = { conversation.profile() },
        queueQuestion = { taskId, question, now -> looks.queueQuestion(taskId, question, now) },
        readDecks = { SyncWorker.syncNow(app, setOf(Source.Anki)) },
        byServer = sendsCommands,
    )

    /** Photos ([Photos]): of a task's work, of homework to make tasks from, and of written work checked. */
    override val photos: Photos = Photos(
        tasks, settings, chat, clock, scope, gateway, focus, File(app.filesDir, "photos"), PhotoChecks::shrink,
        profile = { conversation.profile() },
        read = { taskId -> readings.enrichNow(only = taskId) },
        // Kept with its task as a command once the two keep in step by them, or a pull would take it away, and the file with it.
        keeping = { taskId, photo -> if (sendsCommands()) commands.send(AttachPhoto(taskId, photo)) else photos.keep(taskId, photo) },
        // A photo of homework read by the server once the phone sends commands, as Claude's the server's then (D-142).
        readByServer = { jpeg -> if (sendsCommands()) replica.readPhotoOnServer(jpeg) else null },
    )

    /** The calendar's this phone's, read here: nothing to keep of what it sent. */
    override suspend fun calendarSeen(read: CalendarRead) = Unit

    /** An event asked about, on record as asked here, where the calendar's read ([Sync.eventAsked]). */
    override suspend fun eventAsked(key: String) = sync.eventAsked(key)

    /**
     * His questions about the events named [key] settled, an answer or an instruction of yours having answered them: as a command
     * once the phone sends them, so the server's copies of them settle too and a pull doesn't bring them back unanswered.
     */
    private suspend fun settleEvent(key: String) = if (sendsCommands()) commands.send(SettleEvent(key)) else conversation.settleEvent(key)

    /** Protection ([Protection]): settings changes, waiting once armed if they loosen blocking; the parent code; the device admin. */
    override val protection: Protection = Protection(
        settings, stores.protection, stores.device, secrets, clock, scope, adminActive = { Watchdog.isAdminActive(app) }, byServer = sendsCommands,
        // A new parent code's lock a command once the phone sends them, as the server's lock is what a pull brings.
        locking = { step -> if (sendsCommands()) commands.send(ParentCodeSet(step)) else protection.freshLock(step) },
    ) {
        app.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(app))
    }

    /**
     * What's learned from the log ([Learning]): the week's review, a figure forgotten, a review's change
     * or tidy put back, the week's summary; and the check-ins and completions' answers recorded.
     */
    override val learning: Learning = Learning(
        log, stores.learning, settings, memory, chat, stores.instructions, clock, gateway, focus, protection,
        resets = { backups.resets.get() },
        profile = { now, remembered -> conversation.profile(now, remembered) },
        forgetExpired = { now -> conversation.forgetExpired(now) },
        reviewed = { line -> conversation.reviewed(line) },
        summaryReady = { week -> conversation.summaryReady(week) },
        tasks = { tasks.value.tasks },
        byServer = sendsCommands,
    )

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
            // A timer whose piece was done while the app was down ended.
            focus.settleTimer()
            // Only then is the day judged empty: a slot kept as the app stopped is work done.
            runCatching { focus.grantAllowanceIfDue() }.onFailure { Log.w(TAG, "Giving the day's free time failed", it) }
        }
        // A task Felix asked a source to read about, changed by the read: said in his note (G2).
        syncer.addAfterEverySync { conversation.afterSync() }
        // "How was it?" questions kept for his evening question, asked in the app's words once Claude's off.
        syncer.addAfterEverySync { conversation.askKept() }
        // A task back after aging out of the list (or new) takes the overrides its instructions give.
        syncer.addAfterEverySync { instructions.layTaskOverrides() }
        // Your own tasks long done trimmed to their record, as a source's are.
        syncer.addAfterEverySync { ownTasks.pruneOwn() }
        syncer.addAfterEverySync { ownTasks.rollWeeks() }
        // Gmail's journal settled after each read: receipts let go once old, or once the read finds their email starred again.
        syncer.addAfterEverySync { if (gmailWrites()) gmailFinishes.settle() }
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
            if (tasks.value.tasks.any { Enrichments.jobFor(it, GmailThreads::textRead) != null && Enrichments.stale(it, modelOn, RuleEnricher.BY, ReadingPrompts.VERSION, modelOff) }) EnrichWorker.enqueue(app)
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
            combine(settings.state, stores.model.state) { _, _ -> gateway.available() }.distinctUntilChanged().drop(1).collect { on ->
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
            // Your reminders gone by while the app wasn't running (the phone off): sent now, marked late.
            runCatching { looks.sweepReminders() }.onFailure { Log.w(TAG, "Sending the reminders gone by failed", it) }
        }
        // Your reminders' alarm set again at every change to them, as each plan sets it too.
        scope.launch { stores.felix.state.drop(1).collect { runCatching { armReminder() }.onFailure { e -> Log.w(TAG, "Setting the reminders' alarm failed", e) } } }
        // A note added or deleted: the plan made again, so the first steps of its task's pieces are written with it ([Ahead]).
        scope.launch { stores.felix.state.map { it.notes }.distinctUntilChanged().drop(1).collect { planning.watcher.poke() } }
        // A day off added or taken away: the briefing's next time with it, as a day off has the weekends' (D-74).
        scope.launch { school.state.drop(1).collect { runCatching { DailyAlarms.schedule(app) }.onFailure { e -> Log.w(TAG, "Setting the daily alarms failed", e) } } }
        // The files older shapes came from, a week after their migration, at a start that's got this far.
        scope.launch(Dispatchers.IO) { runCatching { stores.letGoOfOldShapes() }.onFailure { Log.w(TAG, "Letting go of old shapes failed", it) } }
        // Gmail's journal settled (receipts let go once old, or once their email's starred again), and a write due
        // set going again: a stop may have come between a finish's journal and its worker.
        scope.launch { if (gmailWrites()) runCatching { gmailFinishes.settle(); gmailFinishes.rearm() }.onFailure { Log.w(TAG, "Setting Gmail's writes going again failed", it) } }
        // Finishing emails switched off, now or at any change: a finish not yet started dropped, the first's OK with it.
        scope.launch { settings.state.map { it.gmailFinishes }.distinctUntilChanged().collect { on -> if (!on && gmailWrites()) runCatching { gmailFinishes.switchedOff() }.onFailure { e -> Log.w(TAG, "Dropping Gmail's writes failed", e) } } }
        // The notifications Felix's messages have taken the place of, one still showing or held from
        // before let go; then his own, made from the chat as it is.
        scope.launch {
            runCatching { notifier.retire(NoticeId.RETIRED) }.onFailure { Log.w(TAG, "Letting go of the retired notifications failed", it) }
            outbox.notify(clock.now())
        }
        // The timer's notifications and alarms kept to it (D-112): at this start, after a restart or a boot, shown
        // again and set again, a zero gone by meanwhile told now; and at each change, wherever it came from (a Start, a
        // Stop, its piece done).
        scope.launch {
            stores.session.state.map { it.timer }.distinctUntilChanged().collect {
                runCatching { TimerAlarms.sync(app) }.onFailure { e -> Log.w(TAG, "Keeping the timer's notification failed", e) }
            }
        }
        // Anything the server keeps a copy of changed, the calendar as read and the chat's archive among it: sent within the minute, by work that keeps its network.
        // Not once the phone sends commands, whose stores change as it pulls: what's to go then is its outbox's.
        scope.launch {
            merge(stores.mirrored.map { it.state.drop(1) }.merge(), sync.calendarState.drop(1), stores.chatArchive.changes.drop(1)).collect { if (replica.paired && !sendsCommands()) ReplicaWorker.soon(app) }
        }
        // A change made here sent at once, once the phone sends commands, so the website has it in about a second; the half-minute
        // spends of free time within the minute, merged meanwhile (W-24). The outbox empty again: the plan made again, so the
        // calendar's written from what the server's confirmed (W-15).
        scope.launch {
            var before = stores.outbox.value.commands
            stores.outbox.state.drop(1).collect { state ->
                val now = state.commands
                val added = now.filter { command -> before.none { it.id == command.id } }
                val emptied = before.isNotEmpty() && now.isEmpty()
                before = now
                if (!sendsCommands() || !replica.paired) return@collect
                when {
                    added.any { it.action !is SpendFreeTime } -> ReplicaWorker.again(app)
                    added.isNotEmpty() -> ReplicaWorker.soon(app)
                }
                if (emptied) planning.watcher.poke()
            }
        }
        // The calendar as read, which the server plans by and can't read, sent as it changes once the two keep in step by commands
        // (W-24): a seed carried it before. The same as last sent isn't sent again; nor the nothing a start has before its first read,
        // which would take the server's last from it ([Sync.calendarKnown]).
        scope.launch {
            var sent: CalendarRead? = null
            combine(sync.calendarState, sync.calendarKnown) { _, known -> known }.collect { known ->
                if (!sendsCommands() || !replica.paired || !known) return@collect
                val read = sync.calendarRead()
                if (read != sent) runCatching { commands.send(ObserveCalendar(read)) }.onSuccess { sent = read }.onFailure { e -> Log.w(TAG, "Sending the calendar as read failed", e) }
            }
        }
        // Redraw the widget whenever what it shows may have changed.
        scope.launch {
            combine(tasks.state, settings.state, stores.session.state, stores.device.state, stores.model.state) { _, _, _, _, _ -> }.drop(1).debounce(WIDGET_DEBOUNCE_MS).collect {
                runCatching { WidgetUpdater.update(app) }.onFailure { Log.w(TAG, "Widget update failed", it) }
            }
        }
    }

    /**
     * Your reminders' alarm at the next moment by [plan] that one comes, or that one not yet written for has
     * his window to write it in begin ([Reminders.nextWake]), so he writes for it with the phone asleep; none with
     * none to come. Not before [after], where a sweep failed ([SlotAlarms.REMINDER_RETRY_MS]).
     */
    fun armReminder(plan: Plan = planning.plan(), after: Long? = null) =
        SlotAlarms.armReminder(app, Reminders.nextWake(stores.felix.value.reminders, plan, tasks.value.tasks.associateBy { it.id }, clock.now(), FelixRules.AHEAD_MS, after))

    /**
     * The completions syncs saved: free time and the log, then "How was it?" for finished homework
     * and revision (asked, or kept till notifications show), and only then off the queue, so a stop
     * part-way loses neither.
     */
    private suspend fun settleCompletions() {
        focus.rewardCompletions { completed ->
            runCatching { conversation.askHowItWent(Assessment.asks(completed, log.value)) }.onFailure { Log.w(TAG, "Couldn't ask how the work went", it) }
        }
    }

    /**
     * [text], a backup, restored (Import, a reset's copy): here while mirroring; on the server once the phone sends commands, as an
     * Import here alone would go at the next pull, then pulled whole, its calendar answers kept here, as only this phone reads the
     * calendar. What came of it, in words.
     */
    suspend fun importBackup(text: String): String {
        if (!sendsCommands()) return backups.importBackup(text)
        val asked = replica.importOnServer(text)
        if (asked.said?.startsWith(BackupService.RESTORED) == true) {
            backups.answersOf(text)
            ReplicaWorker.now(app)
        }
        return asked.said ?: BackupService.RESTORED
    }

    /** [name], a reset's copy kept in the app's own files, restored as [importBackup] restores a backup. */
    suspend fun restoreResetCopy(name: String): String {
        if (!sendsCommands()) return backups.restoreResetCopy(name)
        val (text, why) = backups.resetCopy(name)
        return if (text == null) why.orEmpty() else importBackup(text)
    }

    /** [what] reset (LR-08): here while mirroring; a command once the phone sends them, as the server's copy is what a pull brings. */
    suspend fun reset(what: Reset): String = if (sendsCommands()) commands.act(ResetData(what)) else backups.reset(what)

    /**
     * Your words waiting for Felix, asked of him again (the chat's Try again): by the server once the phone sends commands (D-142).
     * Why the server couldn't be asked, or null: asked here, his answer, or why there's none, is in the chat.
     */
    suspend fun tryAgain(): String? {
        if (!sendsCommands()) {
            conversation.answerChat(retry = true)
            return null
        }
        return if (replica.askAgainOnServer()) null else "The server couldn't be reached: try again in a moment."
    }

    /** Backups and resets ([BackupService]): made when first asked for, as it takes the instructions' lock. */
    override val backups: BackupService by lazy {
        BackupService(
            stores, clock, scope, AndroidBackupFolder(app.contentResolver), File(app.filesDir, RESET_COPIES), BuildConfig.VERSION_NAME,
            changeSettings = { change -> protection.changeSettings(change) },
            applying = instructions.applying,
            layInstructions = { instructions.layInstructions() },
            layTaskOverrides = { instructions.layTaskOverrides() },
            schedule = { DailyAlarms.schedule(app) },
            reread = {
                sync.refreshCalendar()
                briefing.replanToday()
            },
            cancelFelix = { FelixNotifications.cancel(app) },
        )
    }

    /** Where a reset's copy goes when Documents can't take it. */
    private val RESET_COPIES = "backups"

    companion object {
        const val TAG = "Decrastination"

        private const val WIDGET_DEBOUNCE_MS = 1_000L

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
