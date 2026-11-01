package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.NoteActivity
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.sync.protocol.StartTimer
import com.thomaswcode.decrastination.ui.Moves
import com.thomaswcode.decrastination.ui.OwnChange
import com.thomaswcode.decrastination.ui.dueChange
import com.thomaswcode.decrastination.ui.minutesChange
import com.thomaswcode.decrastination.ui.ownChange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * What a page asks of a task that takes the phone's rules to turn into commands: a value for one of its fields ([at] a moment,
 * [minutes], [date] a day), one taken off it, where it's planned, a move ([date], with [minute] for a time), a timer on a piece
 * ([key], or its [step]'s next), or its next piece done ([key] the piece the page showed, and [at] when: it's that one that's done,
 * or none). The page says what you gave, and what's
 * made of it, in what words, is made once, here (D-137), of the task as it is then: a copy of it sent from a page drawn before
 * would put back what's changed since (D-4).
 */
@Serializable
data class TaskAsk(val kind: String, val at: Long? = null, val minutes: Int? = null, val date: String? = null, val minute: Int? = null, val key: String? = null, val step: Int? = null) {
    companion object {
        const val DONE = "done"
        const val DUE = "due"
        const val MINUTES = "minutes"
        const val START = "start"
        const val CLEAR_DUE = "clearDue"
        const val CLEAR_START = "clearStart"
        const val HOME = "home"
        const val ANYWHERE = "anywhere"
        const val TO_DAY = "toDay"
        const val TO_TIME = "toTime"
        const val TIMER = "timer"
    }
}

/** What came of a [TaskAsk]: whether it went through, and what's said of it, why not if it didn't. */
@Serializable
data class Asked(val done: Boolean, val said: String? = null)

object TaskAsks {
    /** [ask] of [task], whose next piece is [piece], as what's to be sent, or why it can't be. */
    fun decide(task: TaskItem, piece: Chunk?, ask: TaskAsk, moves: Moves, zone: ZoneId): Moves.Decided {
        val day = ask.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when (ask.kind) {
            TaskAsk.DUE -> ask.at?.let { at ->
                val (change, text) = dueChange(task, Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime(), zone)
                made(task, change, text)
            } ?: Moves.Decided(why = "That needs a date and a time")
            TaskAsk.MINUTES -> ask.minutes?.takeIf { it in OwnTaskRules.minutesFor(task) }?.let { minutes ->
                val (change, text) = minutesChange(task, minutes)
                made(task, change, text)
            } ?: OwnTaskRules.minutesFor(task).let { Moves.Decided(why = "Its minutes must be from ${it.first} to ${it.last}") }
            TaskAsk.START -> day?.let {
                val (change, text) = ownChange(task, OwnChange.StartFrom, it, zone)
                made(task, change, text)
            } ?: Moves.Decided(why = "That needs a date")
            TaskAsk.CLEAR_DUE -> cleared(task, ChangeType.DueBy)
            TaskAsk.CLEAR_START -> cleared(task, ChangeType.StartFrom)
            TaskAsk.HOME, TaskAsk.ANYWHERE -> if (task.source != Source.Own) NOT_ON_IT else Moves.Decided(
                actions = listOf(SaveOwnTask.of(task.sourceId, OwnTaskRules.draftOf(task).copy(where = if (ask.kind == TaskAsk.HOME) "home" else "anywhere"))),
            )
            // Something done on its task, too (D-112), so Felix's nudge for its slot stays off after a Stop.
            TaskAsk.TIMER -> Moves.Decided(actions = listOf(StartTimer(task.id, ask.step, ask.key), NoteActivity(listOf(task.id))))
            TaskAsk.TO_DAY, TaskAsk.TO_TIME -> when {
                piece == null -> Moves.Decided(why = "It has no piece in the plan to move")
                day == null -> Moves.Decided(why = "That needs a date")
                ask.kind == TaskAsk.TO_DAY -> moves.dayMove(piece, day, zone)
                ask.minute == null || ask.minute !in 0 until 24 * 60 -> Moves.Decided(why = "That needs a time")
                else -> moves.timeMove(piece, day, ask.minute, zone)
            }
            else -> Moves.Decided(why = "That can't be asked")
        }
    }

    /** [type]'s value taken off [task], yours. */
    private fun cleared(task: TaskItem, type: ChangeType): Moves.Decided =
        if (task.source != Source.Own) NOT_ON_IT else made(task, Change(type, taskId = task.id, time = null), "")

    /** [change] to [task], as its sheet makes one: yours changed itself, where the form would take the result; a source's given it as a quick change of yours, in [text]. */
    fun made(task: TaskItem, change: Change, text: String): Moves.Decided {
        if (task.source != Source.Own) return Moves.Decided(actions = listOf(AddOwnChange(Ids.next(), text, About(taskId = task.id, taskTitle = task.title), change)))
        val draft = OwnTaskRules.draftWith(task, change) ?: return NOT_ON_IT
        return OwnTaskRules.problem(draft)?.let { Moves.Decided(why = it) } ?: Moves.Decided(actions = listOf(SaveOwnTask.of(task.sourceId, draft)))
    }

    private val NOT_ON_IT = Moves.Decided(why = "That can't be changed on it")
}
