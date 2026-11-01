package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.DeleteNote
import com.thomaswcode.decrastination.sync.protocol.DeleteOwnTask
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.LaterToday
import com.thomaswcode.decrastination.sync.protocol.ReopenOwnTask
import com.thomaswcode.decrastination.sync.protocol.StopTimer
import com.thomaswcode.decrastination.sync.protocol.TakeBackQuick
import com.thomaswcode.decrastination.sync.protocol.TickBlock
import com.thomaswcode.decrastination.sync.protocol.UndoKeep
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.Moves
import com.thomaswcode.decrastination.ui.OwnChange
import com.thomaswcode.decrastination.ui.TaskSheetModel
import com.thomaswcode.decrastination.ui.ownChange
import com.thomaswcode.decrastination.ui.stepLine
import com.thomaswcode.decrastination.ui.timerText
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * A task's sheet as the website draws it (W-14). What can be done with it comes ready made: each a label, what it sends, and
 * the question to ask first if there's one, so the page draws buttons and sends what they carry, and no rule about what a
 * task allows is written twice. In mirror mode nothing can be: the phone's seeds are what change the state.
 */
@Serializable
data class TaskView(
    val id: String,
    val title: String,
    val detail: String,
    val line: String,
    val minutes: String,
    val due: String,
    val overdue: Boolean,
    val own: Boolean,
    val tags: List<String>,
    /** The next piece, and how long it is. */
    val next: String? = null,
    val fields: List<TaskField>,
    val steps: List<StepLine>,
    /** Its pieces in the plan and why, as the phone says them. */
    val planned: List<String>,
    /** The changes in use on it, as you made them. */
    val instructions: List<String>,
    val notes: List<NoteLine>,
    val actions: List<TaskAction>,
    /** Whether a note can be added: an open task's. */
    val notable: Boolean,
    /** One of your own, to the form. */
    val edit: OwnTaskEdit? = null,
    /** Where its next piece can be moved to. */
    val move: MoveView? = null,
    /** Where yours can be done (PL-15), as it's said beside its switch. */
    val where: String? = null,
)

/** A value of a task's, as its sheet shows it: what it says now, whether it can be given here ([TaskAsk]), and the Clear or Undo that takes it back. */
@Serializable
data class TaskField(
    /** A [TaskAsk] kind: which value it is. */
    val kind: String,
    val label: String,
    val says: String,
    val note: String? = null,
    val changes: Boolean = false,
    val undo: TaskAction? = null,
)

@Serializable
data class StepLine(val line: String, val done: Boolean, val tick: Action? = null, val timer: TaskAction? = null)

@Serializable
data class NoteLine(val line: String, val delete: TaskAction? = null)

/**
 * A button: its words, what it does, and, where it can't be undone at a tap, the question first. It sends [action], or asks
 * [ask] of the server where that takes the server's rules; [undo] is what takes it back once it's gone through.
 */
@Serializable
data class TaskAction(
    val label: String,
    val action: Action? = null,
    val ask: TaskAsk? = null,
    val confirm: String? = null,
    val detail: String? = null,
    val undo: Action? = null,
)

/** One of your own as the form opens it: saved under the same id, so it stays the one task. */
@Serializable
data class OwnTaskEdit(val sourceId: String, val task: OwnTask)

/** Where a piece can go (UI-04): the coming days, each with what's planned on it, and why some can't be picked. */
@Serializable
data class MoveView(val piece: String, val days: List<MoveDay>, val notes: List<String>)

@Serializable
data class MoveDay(val date: String, val label: String, val allowed: Boolean)

object TaskViews {
    /** How many days ahead a piece can be moved to. */
    private const val MOVE_DAYS = 14L

    /** [task]'s sheet in [plan] at [now], with what's in use on it. [writable]: the server applies commands, so it can be changed here. */
    fun of(task: TaskItem, byId: Map<String, TaskItem>, plan: Plan, settings: Settings, applied: List<Instruction>, notes: List<Note>, now: Long, zone: ZoneId, writable: Boolean, timer: PieceTimer? = null): TaskView {
        val own = task.source == Source.Own
        val changeable = writable && task.isOpen && !task.derived
        val next = TaskSheetModel.next(plan, task.id).takeIf { task.isOpen }
        val about = About(taskId = task.id, taskTitle = task.title)
        val tile = WorkViews.tile(task, now, zone, byId, null)
        val actions = buildList {
            if (!writable) return@buildList
            next?.let { piece ->
                // A task with no steps: a timer on its next piece (D-112); with steps, each has its own.
                if (task.subSteps.isEmpty()) add(timerAction(timer?.takeIf { it.running && it.key == piece.key }, piece.stepIndex, piece.key, now))
                add(TaskAction("Done", ask = TaskAsk(TaskAsk.DONE, at = now, key = piece.key), undo = UndoKeep(piece.key, now)))
                add(TaskAction("Later today", LaterToday(piece.key)))
                val tomorrow = plan.today.plusDays(1)
                if (TaskSheetModel.canGo(task, tomorrow, now, zone)) {
                    add(TaskAction("Tomorrow", AddOwnChange(Ids.next(), "Tomorrow", about, Change(ChangeType.OnDay, taskId = task.id, date = tomorrow.toString()))))
                }
            }
            if (own && task.isOpen) add(TaskAction("All of it done", FinishOwnTask(task.id)))
            if (own && task.status == Status.Done) add(TaskAction("Not done", ReopenOwnTask(task.id)))
            if (changeable && task.mine.notATask == null) {
                val (change, text) = ownChange(task, OwnChange.NotATask, plan.today, zone)
                add(TaskAction("Not a task", AddOwnChange(Ids.next(), text, about, change), confirm = "Not a task?", detail = "“${task.title}” comes off the plan and the list, as an email that's only to read does. It's listed under Not tasks, and taken back in Ask Claude, under Your changes."))
            }
            if (own && changeable) add(if (home(task)) TaskAction("Anywhere", ask = TaskAsk(TaskAsk.ANYWHERE)) else TaskAction("Home only", ask = TaskAsk(TaskAsk.HOME)))
            if (own) add(TaskAction("Delete", DeleteOwnTask(task.id), confirm = "Delete “${task.title}”?", detail = "It goes from the plan and the list. Its kept slots stay in the log."))
        }
        return TaskView(
            id = task.id,
            title = task.title,
            detail = task.detail,
            line = tile.line,
            minutes = tile.minutes,
            due = tile.due,
            overdue = tile.overdue,
            own = own,
            tags = tile.tags,
            next = next?.let { piece -> "Next: ${piece.shown ?: if (piece.parts > 1) "part ${piece.part} of ${piece.parts}" else piece.title}, ${Format.minutes(piece.minutes)}" },
            fields = fields(task, applied, changeable, now, zone),
            steps = task.subSteps.mapIndexed { index, step ->
                val timed = writable && task.isOpen && !task.hidden && !step.done
                StepLine(
                    stepLine(step.title, step.minutes, step.from, step.dueAt, step.anyOrder, now, zone),
                    step.done,
                    if (writable && Focus.tickable(task) && task.isOpen && !step.done) TickBlock(task.id, index, step.title) else null,
                    if (timed) timerAction(timer?.takeIf { it.running && it.taskId == task.id && it.stepIndex == index }, index, null, now) else null,
                )
            },
            planned = TaskSheetModel.planned(plan, task.id, zone).map { TaskSheetModel.line(it, plan.today, zone) },
            instructions = TaskSheetModel.instructions(applied, task.id),
            notes = TaskSheetModel.notes(task, notes, settings.classMap, settings.felixName, now, zone).map { note ->
                val delete = TaskAction("Delete", DeleteNote(note.id), confirm = note.every?.let { "Delete the note for $it?" }, detail = note.every?.let { "It goes from every task it's for, not only this one." })
                NoteLine(note.line, delete.takeIf { writable })
            },
            actions = actions,
            notable = writable && task.isOpen,
            edit = if (writable && own && task.isOpen) OwnTaskEdit(task.sourceId, OwnTaskRules.draftOf(task)) else null,
            move = if (writable) next?.let { piece -> move(task, piece.label, plan, now, zone) } else null,
            where = if (own && changeable) (if (home(task)) "Planned at home, not in a free period at school" else "Planned anywhere, a free period at school too") else null,
        )
    }

    private fun home(task: TaskItem) = OwnTaskRules.draftOf(task).where == "home"

    /** Stop for the [timer] running on a piece, with its time; else Start, on step [step]'s next, or on the piece [key]. */
    private fun timerAction(timer: PieceTimer?, step: Int?, key: String?, now: Long) =
        if (timer != null) TaskAction("Stop (${timerText(timer, now).removePrefix("Timer: ")})", StopTimer) else TaskAction("Start", ask = TaskAsk(TaskAsk.TIMER, key = key, step = step))

    /** Its values as the sheet has them: an event by its time; the rest by when it's due, how long it takes and when it can start. */
    private fun fields(task: TaskItem, applied: List<Instruction>, changeable: Boolean, now: Long, zone: ZoneId): List<TaskField> {
        val own = task.source == Source.Own
        if (task.kind == Kind.Event) return listOf(TaskField(TaskAsk.DUE, "When", task.dueAt?.let { Format.at(it, now, zone) } ?: "No time", changes = own && changeable))
        fun quick(type: ChangeType) = !own && changeable && InstructionRules.quickInForce(applied, task.id, type)
        fun undo(type: ChangeType) = TaskAction("Undo", TakeBackQuick(task.id, type))
        fun clear(kind: String) = TaskAction("Clear", ask = TaskAsk(kind))
        val due = task.deadlineAt(now) ?: task.dueAt
        val start = if (own) task.notBefore else task.mine.from?.value
        val hiddenUntil = task.availableFrom?.takeIf { it > now && !own }
        return listOf(
            TaskField(
                TaskAsk.DUE,
                "Due",
                when {
                    due == null -> "No due date"
                    // A date the app or Claude set moves on; only a real deadline is overdue (PL-14).
                    due >= now || task.hardAt(now) -> (if (due < now) "Overdue: " else "") + Format.at(due, now, zone).replaceFirstChar(Char::uppercase)
                    else -> "Planned again: its date was " + Format.at(due, now, zone)
                },
                note = listOfNotNull(
                    if (!own && task.mine.dueAt?.value != null) "Yours: ${task.source.label} says ${task.sourceValues?.dueAt?.let { Format.at(it, now, zone) } ?: "no date"}" else null,
                    Enrichments.current(task)?.reason?.let { "Claude: “$it” in ${if (task.source == Source.Gmail) "the email" else "the instructions"}" },
                ).joinToString(". ").takeIf { it.isNotEmpty() },
                changes = changeable,
                undo = when {
                    own && changeable && task.dueAt != null -> clear(TaskAsk.CLEAR_DUE)
                    quick(ChangeType.DueBy) -> undo(ChangeType.DueBy)
                    else -> null
                },
            ),
            TaskField(
                TaskAsk.MINUTES,
                "Takes",
                "${Format.minutes(task.effortMin)} in all" + if (task.workedMin > 0) ", ${Format.minutes(task.workedMin)} worked" else "",
                changes = changeable,
                undo = if (quick(ChangeType.Minutes)) undo(ChangeType.Minutes) else null,
            ),
            TaskField(
                TaskAsk.START,
                "Can't start before",
                start?.takeIf { it > now }?.let { Format.at(it, now, zone) } ?: "Any time",
                note = hiddenUntil?.let { "Hidden from the plan until ${Format.at(it, now, zone)}, ${task.source.label} says" },
                changes = changeable,
                undo = when {
                    own && changeable && task.notBefore != null -> clear(TaskAsk.CLEAR_START)
                    quick(ChangeType.StartFrom) -> undo(ChangeType.StartFrom)
                    else -> null
                },
            ),
        )
    }

    /** Its next piece's days: the next fortnight, each with its load, one its task can't be on not to be picked, and why. */
    private fun move(task: TaskItem, piece: String, plan: Plan, now: Long, zone: ZoneId): MoveView {
        val last = task.realDeadline?.takeIf { it > now }?.let { Planner.date(it, zone) }
        val first = task.notBefore?.let { Planner.date(it, zone) }
        return MoveView(
            piece,
            (0L until MOVE_DAYS).map { plan.today.plusDays(it) }.map { day ->
                val load = Format.minutes(plan.bucket(day)?.plannedMin ?: 0)
                MoveDay(day.toString(), "${if (day == plan.today) "Today" else day.format(Moves.CHIP_DAY)}, $load", Moves.dayAllowed(task, day, now, zone))
            },
            listOfNotNull(
                last?.let { "No day after ${it.format(Moves.CHIP_DAY)}: due before then." },
                first?.takeIf { it > plan.today }?.let { "No day before ${it.format(Moves.CHIP_DAY)}: it can't be started till then." },
            ),
        )
    }
}
