package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.FelixPorts
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.sync.GmailFinishes
import com.thomaswcode.decrastination.sync.protocol.Owners
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import kotlinx.coroutines.CoroutineScope

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
 * left out. None of them is started: nothing here watches a store or runs by itself, so all they do is what a command
 * asks. In mirror mode no command comes, and their writes would go nowhere.
 */
class ServerOwners(
    private val stores: Stores,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val planning: Planning,
    private val calendarTime: () -> EventJudge.Time,
) : Owners {
    override val tasks: JsonStore<TaskState> = stores.tasks

    private val chatOutbox = Outbox(stores.chat, stores.chatArchive, stores.settings, NoNotices, NoDevice, chatOnScreen = { false }, chatSeen = { true }, clock)

    override val focus: Focus = Focus(
        stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock,
        onOwnTaskDone = { ownTasks.finishOwnTask(it) },
        schoolDay = { SchoolDays.kindOf(it, stores.school.value) == DayKind.Weekday },
    ) { state, settings, now -> planning.plan(state, settings, now) }

    override val looks: Looks = Looks(
        stores.tasks, stores.settings, stores.model, stores.log, stores.chat, stores.felix, clock, scope, gateway, focus, planning, NoDevice, chatOutbox,
        calendarTime = calendarTime,
        profile = { conversation.profile(it) },
        remember = { conversation.remember(it) },
        addInstruction = { text, about, answer, question -> instructions.addInstruction(text, about, answer, question) },
        assess = { taskId, doneAt, answer -> learning.recordAssessment(taskId, doneAt, answer) },
        applyProposal = { messageId, proposalId -> conversation.applyProposal(messageId, proposalId) },
    )

    override val conversation: Conversation = Conversation(
        stores.tasks, stores.settings, stores.learning, stores.log, stores.instructions, stores.chat, stores.school, stores.memory, stores.felix, clock, scope, gateway, NoNotices, focus, planning, NoDevice, chatOutbox,
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

    /** Work a task's finish completes, earning its free time and asked about, as `AppGraph` settles it. */
    private suspend fun settleCompletions() {
        focus.rewardCompletions { completed -> conversation.askHowItWent(Assessment.asks(completed, stores.log.value)) }
    }
}
