package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.block.AnkiWatch
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.block.Slots
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ReadingPrompts
import com.thomaswcode.decrastination.enrich.Readings
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixPorts
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.sources.gmail.GmailSource
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerApi
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSource
import com.thomaswcode.decrastination.sync.GmailFinishes
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.sync.protocol.Owners
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a server has no notifications for: nothing's posted, and none's held back. */
private object NoNotices : Notices {
    override fun cancel(id: NoticeId, tag: String?) = Unit

    override fun shown(channel: String): Boolean = false

    override suspend fun postHeld() = Unit
}

/** His notification alone is made here, for the browsers that asked: nothing else has anywhere to go. */
private object FelixOnly : Notices by NoNotices {
    override fun shown(channel: String): Boolean = channel == Channels.FELIX
}

/** Nowhere for a nightly file: a backup can still be had from the website. */
private object NoFolder : BackupService.Folder {
    override val available = false

    override fun write(name: String, text: String) = false

    override fun names(): List<String> = emptyList()

    override fun delete(name: String) = false
}

/** What Felix asks of the phone, which a server hasn't got. The phone's own notifications and alarms follow the state it pulls. */
private object NoDevice : FelixPorts {
    override fun show(notice: Outbox.Notice) = Unit

    override fun cancel() = Unit

    override fun armLook(at: Long?) = Unit

    override fun remindLater() = Unit

    override fun readAgain() = Unit

    override fun scheduleReminders() = Unit

    override fun review() = Unit

    override fun answerLater() = Unit

    override fun readLater() = Unit

    override fun remindForm() = Unit

    override suspend fun openSource(task: TaskItem): Pair<Boolean, String?> = false to null
}

/**
 * The owners, on the server's stores (docs/spec/server.md), wired as `AppGraph` wires the phone's, what touches the device
 * left out. Nothing here watches a store or runs by itself till [start], once the server applies commands: in mirror mode no
 * command comes, and their writes would go nowhere.
 */
class ServerOwners(
    private val stores: Stores,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val planning: Planning,
    private val calendarTime: () -> EventJudge.Time,
    /** Where a task's photo is kept, as the phone sent it, by its hash (D-148): read here, a photo of homework is kept nowhere. */
    photosFolder: File,
    /** Where the nightly file goes, if it's set up. */
    nightly: BackupService.Folder?,
    /** Where a reset's copy is kept. */
    resetCopies: File,
    /** This build's version, which a backup names. */
    version: String,
    /** Your answers about events, as the phone last sent them. */
    eventAnswers: () -> Map<String, String>,
    /** A backup's answers about events taken in, those here winning. */
    answersBack: suspend (Map<String, String>) -> Unit,
    /** How the stores are read as one, with no command half done in memory. */
    settled: suspend (suspend () -> String) -> String,
    /** His notification, new, for the browsers that asked. */
    private val told: (Outbox.Notice) -> Unit = {},
    /** No command open: what a command sets off that reaches Claude waits here, so one rolled back finds nothing to ask. */
    private val afterCommands: suspend () -> Unit = {},
    /** The calendar as the phone last read it, kept, as the plan's made from it here (`ObserveCalendar`). */
    private val seenCalendar: suspend (CalendarRead) -> Unit = {},
    /** [block] with no command open, so what it writes is committed as it returns, a write made while one's open waiting for it. */
    private val committed: suspend (suspend () -> Boolean) -> Boolean = { it() },
    /** What only a phone reads (Teams, AnkiDroid) read now by each paired phone, through Firebase ([PhonePushes.askToRead]). */
    private val askPhoneToRead: (Set<Source>) -> Unit = {},
) : Owners {
    override val tasks: JsonStore<TaskState> = stores.tasks

    /**
     * What can't be done here left out, but what's left to be asked for afresh: work of its own, as a call mustn't hold up a job's turn,
     * at once, as the phone's workers do it.
     */
    val ports: FelixPorts = object : FelixPorts by NoDevice {
        override fun review() = reviewSoon()

        override fun show(notice: Outbox.Notice) {
            if (notice.alert) told(notice)
        }

        override fun remindLater() = later("Asking Felix for a reminder's words") { looks.remindNow() }

        override fun readAgain() = later("Reading a task again") { readings.enrichNow() }

        override fun answerLater() = later("Asking Claude") { conversation.answerChat() }

        override fun readLater() = later("Reading an instruction") { instructions.readInstructions(interactive = true) }
    }

    /** [work], which reaches Claude, on its own time: never inside a command or a job's turn, which a call would hold up. */
    private fun later(what: String, work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (failed: IOException) {
                Log.w(Log.TAG, "$what failed: ${failed::class.simpleName}")
            }
        }
    }

    /**
     * [sources] read now, as Felix's `sync` act asks (G2): what this server reads, read here on its own time; what only a phone reads,
     * asked of each paired phone (D-142).
     */
    internal fun readNow(sources: Set<Source>) {
        val here = sources.filterTo(HashSet()) { it in Source.SERVERS }
        if (here.isNotEmpty()) later("Reading ${here.joinToString { it.label }}") { syncer.sync(here) }
        askPhoneToRead(sources - here)
    }

    /**
     * [taskId] read again by Claude as it is now (AC-06), a phone's *Read again* once it sends commands, as Claude's the server's then
     * (D-142): on its own time, as a reading can take minutes, its reading reaching the phone as it's pulled. Whether Claude can be
     * asked now: nothing's sent where it can't.
     */
    fun readAgainSoon(taskId: String): Boolean {
        if (gateway.claudeKey() == null || !gateway.available()) return false
        later("Reading a task again") { readings.readAgain(taskId) }
        return true
    }

    private val chatOutbox = Outbox(stores.chat, stores.chatArchive, stores.settings, FelixOnly, ports, chatOnScreen = { false }, chatSeen = { true }, clock)

    override val focus: Focus = Focus(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock,
        onOwnTaskDone = { ownTasks.finishOwnTask(it) },
        // An email's work all done by a keep or a tick is finished in Gmail; taken back, so is its finish, once its work isn't all done.
        onWorked = { runCatching { gmailFinishes.finish(it, explicit = false) }.onFailure { e -> Log.w(Log.TAG, "Finishing an email in Gmail failed: ${e::class.simpleName}") } },
        onUnworked = { runCatching { gmailFinishes.unworked(it) }.onFailure { e -> Log.w(Log.TAG, "Taking back an email's finish failed: ${e::class.simpleName}") } },
        schoolDay = { SchoolDays.kindOf(it, stores.school.value) == DayKind.Weekday },
    ) { state, settings, now -> planning.plan(state, settings, now) }

    override val looks: Looks = Looks(
        stores.tasks, stores.settings, stores.model, stores.log, stores.chat, stores.felix, clock, scope, gateway, focus, planning, ports, chatOutbox,
        calendarTime = calendarTime,
        profile = { conversation.profile(it) },
        remember = { conversation.remember(it) },
        addInstruction = { text, about, answer, question -> instructions.addInstruction(text, about, answer, question) },
        assess = { taskId, doneAt, answer -> learning.recordAssessment(taskId, doneAt, answer) },
        applyProposal = { messageId, proposalId -> conversation.applyProposal(messageId, proposalId) },
        settled = afterCommands,
    )

    override val conversation: Conversation = Conversation(
        stores.tasks, stores.settings, stores.learning, stores.log, stores.instructions, stores.chat, stores.school, stores.memory, stores.felix, clock, scope, gateway, NoNotices, focus, planning, ports, chatOutbox,
        owners = object : Conversation.Owners {
            override suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = ownTasks.saveOwnTask(sourceId, task)
            override suspend fun finishOwnTask(taskId: String) { ownTasks.finishOwnTask(taskId) }
            override suspend fun reopenOwnTask(taskId: String) { ownTasks.reopenOwnTask(taskId) }
            override suspend fun deleteOwnTask(taskId: String) { ownTasks.deleteOwnTask(taskId) }
            override suspend fun applyInstruction(id: String) { instructions.applyInstruction(id) }
            override suspend fun deleteInstruction(id: String) { instructions.deleteInstruction(id) }
            override suspend fun layTaskOverrides() { instructions.layTaskOverrides() }
            override suspend fun changeSettings(change: (Settings) -> Settings) { protection.changeSettings(change) }
            override suspend fun answerEvent(key: String, answer: String) = Unit
            override suspend fun recordCheckIn(checkIn: CheckIn, now: Long): Boolean = learning.recordCheckIn(checkIn, now)
            override suspend fun finishGmail(taskId: String): GmailFinishes.Finish = gmailFinishes.finish(taskId, explicit = true)
            override suspend fun unfinishGmail(taskId: String, writeId: String?): Boolean = gmailFinishes.unfinish(taskId, writeId)
            override fun gmailWriteOf(taskId: String): String? = gmailFinishes.latestFinish(taskId)
        },
        calendarTime = calendarTime,
        answered = { record, words -> looks.answered(record, words) },
        draftReminders = { now -> looks.draftReminders(now) },
        requestSync = { sources -> readNow(sources) },
        teamsOnPhone = { true },
        settled = afterCommands,
    )

    override val instructions: Instructions = Instructions(
        stores.instructions, stores.tasks, stores.settings, stores.session, stores.school, stores.chat, clock, scope, gateway, focus, planning,
        calendarTime = calendarTime,
        profile = { conversation.profile() },
        // Today's record made again, as the phone's is; the calendar's the phone's to read, and it says what it reads.
        reread = { briefing.replanToday() },
        redraw = {},
        answerRead = { instruction, answers, changed -> looks.answerRead(instruction, answers, changed) },
        question = { looks.questionFor(it) },
        settled = afterCommands,
    )

    override val ownTasks: OwnTasks = OwnTasks(stores.tasks, stores.instructions, stores.chat, clock, scope) { settleCompletions() }

    override val protection: Protection = Protection(stores.settings, stores.protection, stores.device, stores.secrets, clock, scope, adminActive = { false }) {}

    override val learning: Learning = Learning(
        stores.log, stores.learning, stores.settings, stores.memory, stores.chat, stores.instructions, clock, gateway, focus, protection,
        resets = { backups.resets.get() },
        profile = { now, remembered -> conversation.profile(now, remembered) },
        forgetExpired = { now -> conversation.forgetExpired(now) },
        reviewed = { line -> conversation.reviewed(line) },
        summaryReady = { week -> conversation.summaryReady(week) },
        tasks = { stores.tasks.value.tasks },
    )

    /**
     * The Backup-format file, as the phone writes it, so the phone's Import can read it; and what takes one in, and the resets. A
     * reset's copy stays on the box, where it can be restored from, and goes on to the nightly place on its own time.
     */
    override val backups: BackupService = BackupService(
        stores, clock, scope, nightly ?: NoFolder, resetCopies, version,
        changeSettings = { change -> protection.changeSettings(change) },
        applying = instructions.applying,
        layInstructions = { instructions.layInstructions() },
        layTaskOverrides = { instructions.layTaskOverrides() },
        schedule = {},
        reread = { briefing.replanToday() },
        cancelFelix = {},
        eventAnswers = eventAnswers,
        answersBack = answersBack,
        settled = settled,
        restoredFrom = "the Backup page",
        sendCopy = { name, text -> nightly?.let { folder -> scope.launch(Dispatchers.IO) { folder.write(name, text) } } },
    )

    /** The tasks' readings, by Claude while it can be asked, by the rules otherwise. */
    /** Where a task's photo is kept here: the copy the phone sent ahead of the command that keeps it, by its hash (D-148). */
    private val photoFile: (TaskPhoto) -> File = { File(photosFolder, "${it.hash}.jpg") }

    val readings: Readings = Readings(
        stores.tasks, stores.model, clock, scope, gateway,
        profile = { conversation.profile() },
        queueQuestion = { taskId, question, now -> looks.queueQuestion(taskId, question, now) },
        readDecks = {},
        settled = afterCommands,
        photoFile = photoFile,
    )

    /** A photo of homework read into tasks of your own: the browser's shrunk it already. A task's photo, the phone's, kept with it by name. */
    override val photos: Photos = Photos(stores.tasks, stores.settings, stores.chat, clock, scope, gateway, focus, photosFolder, shrink = { null }, profile = { conversation.profile() }, fileOf = photoFile, read = {})

    /** Time in AnkiDroid as work, as the phone saw it and sent it (`AnkiCame`, `AnkiWent`): its decks read there, and nothing to read here. */
    override val ankiWatch: AnkiWatch = AnkiWatch(
        stores.anki, stores.learning, stores.tasks, clock, scope, focus, planning,
        readAnki = {},
        ankiPackage = "",
        decks = { null },
        noteActivity = { taskIds, now -> looks.noteActivity(taskIds, now) },
    )

    override suspend fun calendarSeen(read: CalendarRead) = seenCalendar(read)

    /** What's been asked of the calendar is the phone's to keep, where it's read (`calendar.json`): the server holds no record of it. */
    override suspend fun eventAsked(key: String) = Unit

    /** Today's slots as they pass. */
    val slots: Slots = Slots(stores.session, stores.settings, stores.tasks, stores.school, clock, planning, focus)

    /** The morning, its calendar what the phone last sent. */
    val briefing: Briefing = Briefing(planning, stores.log, stores.runtime, stores.school, clock, NoNotices, readCalendar = {}, looks, conversation)

    override val sources: Syncer get() = syncer

    /**
     * When the writes to Gmail are next due ([GmailFinishes.Ports.schedule]), for the job that makes them (`ServerGraph`'s `gmail`):
     * none, none's set. In memory, set again from the journal at the start ([start]).
     */
    @Volatile
    var gmailDueAt: Long? = null

    /**
     * Emails finished in Gmail (D-7 as amended, D-106), its writes made here by the server's own sign-in (D-143) once the phone
     * sends commands, as the phone made them while it mirrored. The journal's mirrored, so the phone's screens have it as it is.
     */
    val gmailFinishes: GmailFinishes = GmailFinishes(
        stores.gmailWrites,
        settings = { stores.settings.value },
        task = { id -> planning.asPlanned().firstOrNull { it.id == id } },
        multiplier = { stores.learning.value.calibration.multiplierFor(it) },
        clock = clock,
        ports = object : GmailFinishes.Ports {
            override fun schedule(at: Long) {
                gmailDueAt = at
            }

            override fun ensureScheduled(at: Long) {
                if (gmailDueAt == null) gmailDueAt = at
            }

            override fun syncGmail() = later("Reading Gmail") { syncer.sync(setOf(Source.Gmail)) }

            // Said on the website's Setup and the task's sheet, from the journal: its words aren't logged, as they name the email.
            override fun gaveUp(write: GmailWrite, words: String) {
                Log.w(Log.TAG, "A write to Gmail was given up")
            }

            override fun sorted(write: GmailWrite) = Unit
        },
        readAt = { stores.tasks.value.status(Source.Gmail).lastSuccessStartedAt },
        // What an email holds kept in the database before Gmail's changed: a restart then still knows what an Undo puts back.
        beforeChanging = committed,
    )

    override val gmail: GmailFinishes get() = gmailFinishes

    /** The sources that need no phone (D-133): read here, merged as the phone merges them. */
    val syncer: Syncer = Syncer(
        stores.tasks, stores.settings, listOf(PowerPlannerSource(PowerPlannerApi(UrlConnectionHttp()), stores.secrets), GmailSource(stores.secrets)), clock,
        onFailure = { source, error -> Log.w(Log.TAG, "Reading ${source.label} failed: ${error::class.simpleName}") },
        ankiRates = { stores.learning.value.calibration.ankiSecondsPerCard },
    )

    /** The week's review, unless the week's had it: on its own time, as it can take a minute, and never inside a command. */
    fun reviewSoon() {
        scope.launch {
            try {
                learning.review(ifDue = true)
            } catch (failed: IOException) {
                Log.w(Log.TAG, "The week's review failed: ${failed::class.simpleName}")
            }
        }
    }

    /**
     * What watches the stores, as `AppGraph` starts it, less what's the phone's: an own task with its steps done is finished; Gmail's
     * writes due set going again, from the journal; and finishing emails switched off, now or at any change, dropping a finish not
     * yet started.
     */
    fun start() {
        ownTasks.start()
        gmailFinishes.rearm()
        scope.launch {
            stores.settings.state.map { it.gmailFinishes }.distinctUntilChanged().collect { on ->
                if (!on) runCatching { gmailFinishes.switchedOff() }.onFailure { e -> Log.w(Log.TAG, "Dropping Gmail's writes failed: ${e::class.simpleName}") }
            }
        }
    }

    init {
        // What follows a read, as `AppGraph` has it, less what's the phone's: its calendar, its notifications.
        syncer.addListener { settleCompletions() }
        syncer.addAfterEverySync { conversation.afterSync() }
        syncer.addAfterEverySync { conversation.askKept() }
        syncer.addAfterEverySync { instructions.layTaskOverrides() }
        syncer.addAfterEverySync { ownTasks.pruneOwn() }
        syncer.addAfterEverySync { ownTasks.rollWeeks() }
        // Gmail's journal settled after each read: receipts let go once old, or once the read finds their email starred again.
        syncer.addAfterEverySync { gmailFinishes.settle() }
        // What reaches Claude on its own time: a read the phone sent, as a command, is merged inside it.
        syncer.addAfterEverySync { later("Answering what waits") { answerWhatWaits() } }
        syncer.addAfterEverySync {
            val on = gateway.available()
            val off = gateway.claudeKey() == null
            if (stores.tasks.value.tasks.any { Enrichments.jobFor(it, GmailThreads::textRead) != null && Enrichments.stale(it, on, RuleEnricher.BY, ReadingPrompts.VERSION, off) }) later("Reading the tasks") { readings.enrichNow() }
        }
    }

    /** A message or an instruction waiting for Claude: asked now. Never inside a command: a call mustn't hold the state's write lock. */
    suspend fun answerWhatWaits() {
        if (stores.chat.value.unanswered != null) conversation.answerChat()
        if (stores.instructions.value.instructions.any { it.state == InstructionStatus.Reading }) instructions.readInstructions()
    }

    /** Work a task's finish completes, earning its free time and asked about, as `AppGraph` settles it. */
    private suspend fun settleCompletions() {
        focus.rewardCompletions { completed -> conversation.askHowItWent(Assessment.asks(completed, stores.log.value)) }
    }
}
