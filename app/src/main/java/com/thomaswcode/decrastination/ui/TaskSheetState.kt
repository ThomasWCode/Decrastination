package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * What a task's sheet holds (docs/plan_v4/architecture.md §The rules; C-Q11), beside what it says ([TaskSheetModel]):
 * the picker or the question open over it ([picking], [confirming]), its last words ([message], what
 * went wrong; [said], what an action did), and whether the instructions on it are shown; and its
 * changes, through your instructions and your own tasks ([make], [undo], [later], [tomorrow],
 * [notATask], [finish], [reopen], [delete], [homeOnly]); your notes on it ([note], [unnote]). A plain class, tested without Compose: the
 * sheet keeps one through a turn of the phone ([saver]) and draws from it.
 */
class TaskSheetState(
    private val instructions: Instructions,
    private val ownTasks: OwnTasks,
    /** Your note on a task kept, or why it can't be ([com.thomaswcode.decrastination.felix.Conversation.addNote]). */
    private val addNote: suspend (taskId: String, text: String) -> String? = { _, _ -> null },
    /** A note deleted ([com.thomaswcode.decrastination.felix.Conversation.deleteNote]). */
    private val deleteNote: suspend (id: String) -> Unit = {},
) {
    /** The value being picked in a dialog over the sheet, or none. */
    var picking: OwnChange? by mutableStateOf(null)

    /** The question asked before a change that can't be undone at a tap ("delete", "not-a-task"), or none. */
    var confirming: String? by mutableStateOf(null)

    /** What went wrong, last. */
    var message: String? by mutableStateOf(null)

    /** What an action here said (a piece done, moved later), over the screen's last word. */
    var said: String? by mutableStateOf(null)

    /** Whether the instructions on the task are shown, unfolded. */
    var showInstructions: Boolean by mutableStateOf(false)

    /** Whether the box for a note of yours is open over the sheet. */
    var noting: Boolean by mutableStateOf(false)

    /** Your note [text] on [task] (D-100): kept, the box shut; or what's wrong, said. */
    suspend fun note(task: TaskItem, text: String) {
        message = addNote(task.id, text)
        if (message == null) noting = false
    }

    /** Note [id] off the sheet, and out of what Felix is told. */
    suspend fun unnote(id: String) = deleteNote(id)

    /**
     * [task], one of your own, changed as [change] says, where the form would take the result (else
     * what's wrong is said); a source's given it as a quick change of yours, in [text].
     */
    suspend fun make(task: TaskItem, change: Change, text: String) {
        if (task.source == Source.Own) {
            val draft = OwnTaskRules.draftWith(task, change) ?: return
            message = OwnTaskRules.problem(draft)
            if (message == null) ownTasks.saveOwnTask(task.sourceId, draft)
        } else {
            instructions.addOwnChange(UUID.randomUUID().toString(), text, About(taskId = task.id, taskTitle = task.title), change)
        }
    }

    /** Your quick change of [type] to [task] taken back: what its source says stands again. */
    suspend fun undo(task: TaskItem, type: ChangeType) = instructions.takeBackQuick(task.id, type)

    /** [piece] later today (UI-03), at the first free time after it, as it's said; or that today has none. */
    suspend fun later(piece: Chunk, now: Long, zone: ZoneId) {
        said = instructions.laterToday(piece.key)?.let { "Later today, at ${Format.at(it, now, zone)}: undone in the chat's Your changes" } ?: "No time left today for it"
    }

    /** [task]'s work on [tomorrow], as a change of yours. */
    suspend fun tomorrow(task: TaskItem, tomorrow: LocalDate) {
        instructions.addOwnChange(UUID.randomUUID().toString(), "Tomorrow", About(taskId = task.id, taskTitle = task.title), Change(ChangeType.OnDay, taskId = task.id, date = tomorrow.toString()))
        said = "Tomorrow: undone in the chat's Your changes"
    }

    /** [task] not a task, as a change of yours, made on [today]. */
    suspend fun notATask(task: TaskItem, today: LocalDate, zone: ZoneId) {
        val (change, text) = ownChange(task, OwnChange.NotATask, today, zone)
        instructions.addOwnChange(UUID.randomUUID().toString(), text, About(taskId = task.id, taskTitle = task.title), change)
    }

    /** [task], yours, all done, as you say. */
    suspend fun finish(task: TaskItem) = ownTasks.finishOwnTask(task.id)

    /** [task], yours and done, open again. */
    suspend fun reopen(task: TaskItem) = ownTasks.reopenOwnTask(task.id)

    /** [task], yours, gone. */
    suspend fun delete(task: TaskItem) = ownTasks.deleteOwnTask(task.id)

    /** Where [task], yours, can be done (PL-15): at home only ([on]), or anywhere, a free period at school too. */
    suspend fun homeOnly(task: TaskItem, on: Boolean) {
        ownTasks.saveOwnTask(task.sourceId, OwnTaskRules.draftOf(task).copy(where = if (on) "home" else "anywhere"))
    }

    companion object {
        /** The sheet's state through its being made again, [instructions], [ownTasks] and the notes' owner's [addNote] and [deleteNote] given to it again. */
        fun saver(
            instructions: Instructions,
            ownTasks: OwnTasks,
            addNote: suspend (String, String) -> String? = { _, _ -> null },
            deleteNote: suspend (String) -> Unit = {},
        ): Saver<TaskSheetState, Any> = listSaver(
            save = { listOf(it.picking?.name.orEmpty(), it.confirming.orEmpty(), it.message.orEmpty(), it.said.orEmpty(), it.showInstructions, it.noting) },
            restore = { saved ->
                TaskSheetState(instructions, ownTasks, addNote, deleteNote).apply {
                    picking = (saved[0] as String).takeIf { it.isNotEmpty() }?.let(OwnChange::valueOf)
                    confirming = (saved[1] as String).ifEmpty { null }
                    message = (saved[2] as String).ifEmpty { null }
                    said = (saved[3] as String).ifEmpty { null }
                    showInstructions = saved[4] as Boolean
                    noting = saved.getOrNull(5) as? Boolean ?: false
                }
            },
        )
    }
}
