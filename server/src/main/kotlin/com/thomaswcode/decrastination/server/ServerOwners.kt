package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.block.Slots
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a server has no notifications for: nothing's posted, and none's held back. */
private object NoNotices : Notices {
    override fun cancel(id: NoticeId, tag: String?) = Unit

    override fun shown(channel: String): Boolean = false

    override suspend fun postHeld() = Unit
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
    /** Where a task's photo would be kept: read here, a photo of homework is kept nowhere. */
    photosFolder: File,
) : Owners {
    override val tasks: JsonStore<TaskState> = stores.tasks

    /** What can't be done here left out, but what's left to be asked for afresh: work of its own, as a call mustn't hold up a job's turn. */
    val ports: FelixPorts = object : FelixPorts by NoDevice {
        override fun review() = reviewSoon()

        override fun remindLater() {
            scope.launch {
                try {
                    looks.remindNow()
                } catch (failed: IOException) {
                    Log.w(Log.TAG, "Asking Felix for a reminder's words failed: ${failed::class.simpleName}")
                }
            }
        }
    }

    private val chatOutbox = Outbox(stores.chat, stores.chatArchive, stores.settings, NoNotices, ports, chatOnScreen = { false }, chatSeen = { true }, clock)

    override val focus: Focus = Focus(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock,
        onOwnTaskDone = { ownTasks.finishOwnTask(it) },
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
            override suspend fun finishGmail(taskId: String): GmailFinishes.Finish = GmailFinishes.Finish.Off
            override suspend fun unfinishGmail(taskId: String, writeId: String?): Boolean = false
            override fun gmailWriteOf(taskId: String): String? = null
        },
        calendarTime = calendarTime,
        answered = { record, words -> looks.answered(record, words) },
        draftReminders = { now -> looks.draftReminders(now) },
    )

    override val instructions: Instructions = Instructions(
        stores.instructions, stores.tasks, stores.settings, stores.session, stores.school, stores.chat, clock, scope, gateway, focus, planning,
        calendarTime = calendarTime,
        profile = { conversation.profile() },
        reread = {},
        redraw = {},
        answerRead = { instruction, answers, changed -> looks.answerRead(instruction, answers, changed) },
        question = { looks.questionFor(it) },
    )

    override val ownTasks: OwnTasks = OwnTasks(stores.tasks, stores.instructions, stores.chat, clock, scope) { settleCompletions() }

    override val protection: Protection = Protection(stores.settings, stores.protection, stores.device, stores.secrets, clock, scope, adminActive = { false }) {}

    override val learning: Learning = Learning(
        stores.log, stores.learning, stores.settings, stores.memory, stores.chat, stores.instructions, clock, gateway, focus, protection,
        resets = { 0 },
        profile = { now, remembered -> conversation.profile(now, remembered) },
        forgetExpired = { now -> conversation.forgetExpired(now) },
        reviewed = { line -> conversation.reviewed(line) },
        summaryReady = { week -> conversation.summaryReady(week) },
        tasks = { stores.tasks.value.tasks },
    )

    /** The tasks' readings, by Claude while it can be asked, by the rules otherwise. */
    val readings: Readings = Readings(
        stores.tasks, stores.model, clock, scope, gateway,
        profile = { conversation.profile() },
        queueQuestion = { taskId, question, now -> looks.queueQuestion(taskId, question, now) },
        readDecks = {},
    )

    /** A photo of homework read into tasks of your own: the browser's shrunk it already. */
    val photos: Photos = Photos(stores.tasks, stores.settings, stores.chat, clock, scope, gateway, focus, photosFolder, shrink = { null }, profile = { conversation.profile() }, read = {})

    /** Today's slots as they pass. */
    val slots: Slots = Slots(stores.session, stores.settings, stores.tasks, stores.school, clock, planning, focus)

    /** The morning, its calendar what the phone last sent. */
    val briefing: Briefing = Briefing(planning, stores.log, stores.runtime, stores.school, clock, NoNotices, readCalendar = {}, looks, conversation)

    /** The sources that need no phone (D-133): read here, merged as the phone merges them. */
    val syncer: Syncer = Syncer(
        stores.tasks, stores.settings, listOf(PowerPlannerSource(PowerPlannerApi(UrlConnectionHttp()), stores.secrets), GmailSource(stores.secrets)), clock,
        onFailure = { source, error -> Log.w(Log.TAG, "Reading ${source.label} failed: ${error::class.simpleName}") },
        ankiRates = { stores.learning.value.calibration.ankiSecondsPerCard },
    )

    /** Whether the week's review is being made: one at a time, as the phone's job is, as it can be a paid call. */
    private val reviewing = AtomicBoolean()

    /** The week's review, unless the week's had it or it's being made: on its own time, as it can take a minute, and never inside a command. */
    fun reviewSoon() {
        if (!reviewing.compareAndSet(false, true)) return
        scope.launch {
            try {
                learning.review(ifDue = true)
            } catch (failed: IOException) {
                Log.w(Log.TAG, "The week's review failed: ${failed::class.simpleName}")
            } finally {
                reviewing.set(false)
            }
        }
    }

    /** What watches the stores, as `AppGraph` starts it, less what's the phone's: an own task with its steps done is finished. */
    fun start() = ownTasks.start()

    init {
        // What follows a read, as `AppGraph` has it, less what's the phone's: its calendar, its notifications.
        syncer.addListener { settleCompletions() }
        syncer.addAfterEverySync { conversation.afterSync() }
        syncer.addAfterEverySync { conversation.askKept() }
        syncer.addAfterEverySync { instructions.layTaskOverrides() }
        syncer.addAfterEverySync { ownTasks.pruneOwn() }
        syncer.addAfterEverySync { ownTasks.rollWeeks() }
        syncer.addAfterEverySync { answerWhatWaits() }
        syncer.addAfterEverySync {
            val on = gateway.available()
            val off = gateway.claudeKey() == null
            if (stores.tasks.value.tasks.any { Enrichments.jobFor(it, GmailThreads::textRead) != null && Enrichments.stale(it, on, RuleEnricher.BY, ReadingPrompts.VERSION, off) }) readings.enrichNow()
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
