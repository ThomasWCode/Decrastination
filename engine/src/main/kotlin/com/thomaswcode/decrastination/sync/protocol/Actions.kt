package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.protect.SettingsChanges
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Your own tasks.

/** [fromForm] and [wasIndexes] are what `OwnTask` holds only in memory: which of its steps each was, so a renamed one keeps its tick. */
@Serializable
@SerialName("SaveOwnTask")
data class SaveOwnTask(val sourceId: String, val task: OwnTask, val fromForm: Boolean = false, val wasIndexes: List<Int?> = emptyList()) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean {
        val steps = task.steps.mapIndexed { i, step -> step.copy(wasIndex = wasIndexes.getOrNull(i)) }
        return owners.ownTasks.saveOwnTask(sourceId, task.copy(steps = steps, fromForm = fromForm))
    }

    companion object {
        /** [task] as the form left it, what's only in memory carried with it. */
        fun of(sourceId: String, task: OwnTask) = SaveOwnTask(sourceId, task, task.fromForm, task.steps.map { it.wasIndex })
    }
}

@Serializable
@SerialName("FinishOwnTask")
data class FinishOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.finishOwnTask(taskId)
}

@Serializable
@SerialName("ReopenOwnTask")
data class ReopenOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.reopenOwnTask(taskId)
}

@Serializable
@SerialName("DeleteOwnTask")
data class DeleteOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.deleteOwnTask(taskId)
}

// Instructions, and the changes of yours they hold.

@Serializable
@SerialName("AddOwnChange")
data class AddOwnChange(val id: String, val text: String, val about: About, val change: Change) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.addOwnChange(id, text, about, change)
}

@Serializable
@SerialName("LaterToday")
data class LaterToday(val key: String) : Action, Answers<Long?> {
    override suspend fun run(owners: Owners): Long? = owners.instructions.laterToday(key)
}

@Serializable
@SerialName("TakeBackQuick")
data class TakeBackQuick(val taskId: String, val kind: ChangeType) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.takeBackQuick(taskId, kind)
}

@Serializable
@SerialName("ApplyInstruction")
data class ApplyInstruction(val id: String) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.instructions.applyInstruction(id)
}

@Serializable
@SerialName("DeleteInstruction")
data class DeleteInstruction(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.deleteInstruction(id)
}

@Serializable
@SerialName("RereadInstruction")
data class RereadInstruction(val id: String, val text: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.rereadInstruction(id, text)
}

@Serializable
@SerialName("NotSame")
data class NotSame(val a: String, val b: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.notSame(a, b)
}

// School's days.

@Serializable
@SerialName("AddNoSchool")
data class AddNoSchool(val entry: NoSchool) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.addSchoolDays(entry)
}

@Serializable
@SerialName("AddHalfDay")
data class AddHalfDay(val entry: HalfDay) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.addSchoolDays(entry)
}

@Serializable
@SerialName("DeleteNoSchool")
data class DeleteNoSchool(val entry: NoSchool) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.deleteSchoolDays(entry)
}

@Serializable
@SerialName("DeleteHalfDay")
data class DeleteHalfDay(val entry: HalfDay) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.deleteSchoolDays(entry)
}

// The day's slots and the timer.

@Serializable
@SerialName("KeepSlot")
data class KeepSlot(val key: String, val seenAt: Long? = null) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.keepSlot(key, seenAt = seenAt)
}

@Serializable
@SerialName("UndoKeep")
data class UndoKeep(val key: String, val since: Long) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.undoKeep(key, since)
}

@Serializable
@SerialName("TickBlock")
data class TickBlock(val taskId: String, val index: Int, val title: String) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.tickBlock(taskId, index, title)
}

@Serializable
@SerialName("StartTimer")
data class StartTimer(val taskId: String, val stepIndex: Int? = null, val key: String? = null) : Action, Answers<PieceTimer?> {
    override suspend fun run(owners: Owners): PieceTimer? = owners.focus.startTimer(taskId, stepIndex, key)
}

@Serializable
@SerialName("StopTimer")
data object StopTimer : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.stopTimer()
}

@Serializable
@SerialName("NoteActivity")
data class NoteActivity(val taskIds: List<String>) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.looks.noteActivity(taskIds)
}

// The chat.

/** [id] is the client's, so a message sent twice is one. */
@Serializable
@SerialName("SendMessage")
data class SendMessage(val text: String, val about: About = About(), val id: String, val replyTo: String? = null, val takeItsSubject: Boolean = true) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.conversation.ask(text, about, id, replyTo, takeItsSubject)
}

@Serializable
@SerialName("ReplyFromNotification")
data class ReplyFromNotification(val text: String, val id: String) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.conversation.replyFromNotification(text, id)
}

@Serializable
@SerialName("WithdrawQuestion")
data object WithdrawQuestion : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.withdrawQuestion()
}

@Serializable
@SerialName("CancelAnswer")
data object CancelAnswer : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.cancelAnswer()
}

@Serializable
@SerialName("MarkAnswered")
data class MarkAnswered(val ids: List<String>) : Action, Answers<Conversation.Marked> {
    override suspend fun run(owners: Owners): Conversation.Marked = owners.conversation.markAnswered(ids)
}

@Serializable
@SerialName("UnmarkAnswered")
data class UnmarkAnswered(val marked: Conversation.Marked) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.unmarkAnswered(marked)
}

@Serializable
@SerialName("RateMessage")
data class RateMessage(val id: String, val up: Boolean?, val feedback: String, val preferred: String = "") : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.rate(id, up, feedback, preferred)
}

@Serializable
@SerialName("ReadChat")
data class ReadChat(val now: Long) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.read(now)
}

@Serializable
@SerialName("ClearChat")
data object ClearChat : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.clearChat()
}

@Serializable
@SerialName("ApplyProposal")
data class ApplyProposal(val messageId: String, val proposalId: String, val edited: OwnTask? = null) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.applyProposal(messageId, proposalId, edited)
}

@Serializable
@SerialName("UndoProposal")
data class UndoProposal(val messageId: String, val proposalId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.undoProposal(messageId, proposalId)
}

@Serializable
@SerialName("DiscardProposal")
data class DiscardProposal(val messageId: String, val proposalId: String, val discarded: Boolean = true) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.discardProposal(messageId, proposalId, discarded)
}

@Serializable
@SerialName("UndoAct")
data class UndoAct(val messageId: String, val index: Int) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.conversation.undoAct(messageId, index)
}

@Serializable
@SerialName("UndoMemory")
data class UndoMemory(val messageId: String, val index: Int) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.conversation.undoMemory(messageId, index)
}

@Serializable
@SerialName("EndCheckInFlow")
data object EndCheckInFlow : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.endCheckInFlow()
}

// What Felix keeps.

@Serializable
@SerialName("Remember")
data class Remember(val given: List<Given>) : Action, Answers<List<MemoryEdit>> {
    override suspend fun run(owners: Owners): List<MemoryEdit> = owners.conversation.remember(given)
}

@Serializable
@SerialName("DeleteRule")
data class DeleteRule(val rule: Rule) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.deleteRule(rule)
}

@Serializable
@SerialName("DeleteFact")
data class DeleteFact(val fact: Fact) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.deleteFact(fact)
}

@Serializable
@SerialName("AddNote")
data class AddNote(val taskId: String, val text: String) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.conversation.addNote(taskId, text)
}

@Serializable
@SerialName("DeleteNote")
data class DeleteNote(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.deleteNote(id)
}

// Settings and protection.

/** The fields a change touched and their new values ([SettingsChanges.changed]): a change made elsewhere to any other stands. */
@Serializable
@SerialName("ChangeSettings")
data class ChangeSettings(val fields: Map<String, JsonElement>) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) {
        owners.protection.changeSettings { latest -> SettingsChanges.laidOver(latest, fields) }
    }
}

@Serializable
@SerialName("UseParentCode")
data class UseParentCode(val code: String, val unblockUntil: Long? = null) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.protection.useParentCode(code, unblockUntil)
}

@Serializable
@SerialName("ApplyChangeNow")
data class ApplyChangeNow(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) {
        owners.protection.applyNow(id)
    }
}

@Serializable
@SerialName("CancelChange")
data class CancelChange(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) {
        owners.protection.cancelChange(id)
    }
}

// The week's check-in and its review.

@Serializable
@SerialName("RecordCheckIn")
data class RecordCheckIn(val checkIn: CheckIn, val now: Long) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.learning.recordCheckIn(checkIn, now)
}

@Serializable
@SerialName("ForgetCalibration")
data class ForgetCalibration(val key: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.learning.forgetCalibration(key)
}

@Serializable
@SerialName("UndoReviewChange")
data class UndoReviewChange(val review: WeeklyReview, val change: ReviewInput.Change) : Action, Answers<String> {
    override suspend fun run(owners: Owners): String = owners.learning.undoReviewChange(review, change)
}

@Serializable
@SerialName("UndoTidy")
data class UndoTidy(val review: WeeklyReview) : Action, Answers<String> {
    override suspend fun run(owners: Owners): String = owners.learning.undoTidy(review)
}
