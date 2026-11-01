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
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.ui.Moves
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Why an action about [taskId] can't go ahead: the task's gone. */
private fun Owners.gone(taskId: String?): String? = if (taskId != null && tasks.value.tasks.none { it.id == taskId }) "That task had gone" else null

// Your own tasks.

/** [fromForm] and [wasIndexes] are what `OwnTask` holds only in memory: which of its steps each was, so a renamed one keeps its tick. */
@Serializable
@SerialName("SaveOwnTask")
data class SaveOwnTask(val sourceId: String, val task: OwnTask, val fromForm: Boolean = false, val wasIndexes: List<Int?> = emptyList()) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean {
        val steps = task.steps.mapIndexed { i, step -> step.copy(wasIndex = wasIndexes.getOrNull(i)) }
        return owners.ownTasks.saveOwnTask(sourceId, task.copy(steps = steps, fromForm = fromForm))
    }

    override fun stale(owners: Owners) = OwnTaskRules.problem(task)

    companion object {
        /** [task] as the form left it, what's only in memory carried with it. */
        fun of(sourceId: String, task: OwnTask) = SaveOwnTask(sourceId, task, task.fromForm, task.steps.map { it.wasIndex })
    }
}

@Serializable
@SerialName("FinishOwnTask")
data class FinishOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.finishOwnTask(taskId)

    override fun stale(owners: Owners) = owners.gone(taskId)
}

@Serializable
@SerialName("ReopenOwnTask")
data class ReopenOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.reopenOwnTask(taskId)

    override fun stale(owners: Owners) = owners.gone(taskId)
}

@Serializable
@SerialName("DeleteOwnTask")
data class DeleteOwnTask(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.ownTasks.deleteOwnTask(taskId)

    override fun stale(owners: Owners) = owners.gone(taskId)
}

// Instructions, and the changes of yours they hold.

@Serializable
@SerialName("AddOwnChange")
data class AddOwnChange(val id: String, val text: String, val about: About, val change: Change) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.instructions.addOwnChange(id, text, about, change)

    override fun stale(owners: Owners) = owners.gone(about.taskId)
}

@Serializable
@SerialName("LaterToday")
data class LaterToday(val key: String) : Action, Answers<Long?> {
    override suspend fun run(owners: Owners): Long? = owners.instructions.laterToday(key)

    override fun refused(answer: Any?) = "No time left today for it".takeIf { answer == null }
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

    override fun refused(answer: Any?) = "It couldn't be applied".takeIf { answer == false }
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

    override fun refused(answer: Any?) = "That piece isn't there now".takeIf { answer == false }
}

@Serializable
@SerialName("UndoKeep")
data class UndoKeep(val key: String, val since: Long) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.undoKeep(key, since)

    override fun refused(answer: Any?) = "That can't be taken back now".takeIf { answer == false }
}

@Serializable
@SerialName("TickBlock")
data class TickBlock(val taskId: String, val index: Int, val title: String) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.tickBlock(taskId, index, title)

    override fun stale(owners: Owners) = owners.gone(taskId)

    override fun refused(answer: Any?) = "That step has changed".takeIf { answer == false }
}

@Serializable
@SerialName("StartTimer")
data class StartTimer(val taskId: String, val stepIndex: Int? = null, val key: String? = null) : Action, Answers<PieceTimer?> {
    override suspend fun run(owners: Owners): PieceTimer? = owners.focus.startTimer(taskId, stepIndex, key)

    override fun stale(owners: Owners) = owners.gone(taskId)

    override fun refused(answer: Any?) = Moves.CHANGED.takeIf { answer == null }
}

@Serializable
@SerialName("StopTimer")
data object StopTimer : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.focus.stopTimer()

    override fun refused(answer: Any?) = "No timer was running".takeIf { answer == false }
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

/** [id] the message the page showed: one sent since isn't the one taken back. */
@Serializable
@SerialName("WithdrawQuestion")
data class WithdrawQuestion(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.withdrawQuestion(id)
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

    override fun undoing(answer: Any?): Action? = (answer as? Conversation.Marked)?.takeIf { it.ids.isNotEmpty() }?.let { UnmarkAnswered(it) }
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

    override fun refused(answer: Any?) = answer as? String
}

@Serializable
@SerialName("UndoMemory")
data class UndoMemory(val messageId: String, val index: Int) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.conversation.undoMemory(messageId, index)

    override fun refused(answer: Any?) = answer as? String
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

    override fun stale(owners: Owners) = owners.gone(taskId)

    override fun refused(answer: Any?) = answer as? String
}

@Serializable
@SerialName("DeleteNote")
data class DeleteNote(val id: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.conversation.deleteNote(id)
}

// Settings and protection.

/**
 * The fields a change touched and their new values ([SettingsChanges.changed]): a change made elsewhere to any other stands.
 * What's been asked is held to what the Settings screen takes, as a client can send anything; a value out of range that's already
 * there doesn't stop another being changed.
 */
@Serializable
@SerialName("ChangeSettings")
data class ChangeSettings(val fields: Map<String, JsonElement>) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) {
        owners.protection.changeSettings { latest -> SettingsChanges.laidOver(latest, fields) }
    }

    override fun stale(owners: Owners): String? {
        if (VERSION in fields) return "That can't be changed"
        val asked = owners.protection.asked
        val laid = try {
            SettingsChanges.laidOver(asked, fields)
        } catch (_: IllegalArgumentException) {
            return "That isn't a value it can have"
        }
        return (SettingsLimits.problems(laid) - SettingsLimits.problems(asked).toSet()).firstOrNull()?.let { "That's out of range: $it" }
    }

    private companion object {
        /** The settings' shape, raised by a migration, never by a change. */
        const val VERSION = "version"
    }
}

@Serializable
@SerialName("UseParentCode")
data class UseParentCode(val code: String, val unblockUntil: Long? = null) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.protection.useParentCode(code, unblockUntil)

    override fun refused(answer: Any?) = answer as? String
}

/** The waiting change [id] applied at once, by a parent's [code]: one action, so a client can't ask for the change without it. */
@Serializable
@SerialName("ApplyChangeNow")
data class ApplyChangeNow(val id: String, val code: String) : Action, Answers<String?> {
    override suspend fun run(owners: Owners): String? = owners.protection.applyWithCode(id, code)

    /** Before the code's used up: there's nothing here to spend it on. */
    override fun stale(owners: Owners) = "That change isn't waiting now".takeIf { owners.protection.pending.none { it.id == id } }

    override fun refused(answer: Any?) = answer as? String
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

    // As a backup holds them: one that isn't would stop every backup after it.
    override fun stale(owners: Owners) = when {
        checkIn.feel !in 1..5 -> "How the week felt is from 1 to 5"
        runCatching { LocalDate.parse(checkIn.weekOf) }.isFailure -> "That isn't a week"
        else -> null
    }

    override fun refused(answer: Any?) = "That week's check-in is done".takeIf { answer == false }
}

@Serializable
@SerialName("ForgetCalibration")
data class ForgetCalibration(val key: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.learning.forgetCalibration(key)

    override fun stale(owners: Owners) = "That's nothing it's learned now".takeUnless { owners.learning.canForget(key) }
}

@Serializable
@SerialName("UndoReviewChange")
data class UndoReviewChange(val reviewAt: Long, val setting: String) : Action, Answers<String> {
    override suspend fun run(owners: Owners): String = owners.learning.undoReviewChange(reviewAt, setting)

    override fun said(answer: Any?) = answer as? String
}

@Serializable
@SerialName("UndoTidy")
data class UndoTidy(val reviewAt: Long) : Action, Answers<String> {
    override suspend fun run(owners: Owners): String = owners.learning.undoTidy(reviewAt)

    override fun said(answer: Any?) = answer as? String
}
