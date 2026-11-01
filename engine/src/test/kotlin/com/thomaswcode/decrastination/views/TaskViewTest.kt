package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.NoteScope
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.sync.protocol.TakeBackQuick
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A task's sheet as the website gets it (W-14): what can be done comes ready made, and in mirror mode nothing can. */
class TaskViewTest {
    private val now = Fixtures.at("2026-10-12T17:00")
    private val monday = LocalDate.parse("2026-10-12")

    private fun task(id: String = "a", due: String? = "2026-10-20T12:00", source: Source = Source.Teams, status: Status = Status.Open, steps: List<SubStep> = emptyList(), kind: Kind = Kind.Homework) = TaskItem(
        id = "${source.name.lowercase()}:$id", source = source, sourceId = id, title = "The $id", dueAt = due?.let { Fixtures.at(it) }, kind = kind,
        sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now, status = status, subSteps = steps, doneAt = if (status == Status.Done) now else null,
    )

    private fun view(task: TaskItem, writable: Boolean = true, applied: List<Instruction> = emptyList(), notes: List<Note> = emptyList()): TaskView {
        val plan = Planner.plan(Planner.Input(listOf(task), now, LONDON, Settings(), windows = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))))
        return TaskViews.of(task, mapOf(task.id to task), plan, Settings(), applied, notes, now, LONDON, writable)
    }

    private fun TaskView.labels() = actions.map { it.label }

    @Test
    fun `a source's open task can be done, put off, and called not a task, which asks first`() {
        val sheet = view(task())
        assertEquals(listOf("Done", "Later today", "Tomorrow", "Not a task"), sheet.labels())
        assertEquals(TaskAsk.DONE, sheet.actions[0].ask?.kind)
        assertNotNull(sheet.actions[0].undo, "a Done can be taken back")
        assertNull(sheet.actions[1].confirm)
        assertEquals("Not a task?", sheet.actions.last().confirm)
        assertNull(sheet.edit, "only yours goes to the form")
    }

    @Test
    fun `one of your own can be finished, placed and deleted, and goes to the form`() {
        val sheet = view(task(source = Source.Own))
        assertEquals(listOf("Done", "Later today", "Tomorrow", "All of it done", "Not a task", "Home only", "Delete"), sheet.labels())
        assertEquals("Delete “The a”?", sheet.actions.last().confirm)
        assertEquals("a", sheet.edit?.sourceId)
        assertEquals("Planned anywhere, a free period at school too", sheet.where)
    }

    @Test
    fun `yours done can be opened again, and nothing else`() {
        val sheet = view(task(source = Source.Own, status = Status.Done))
        assertEquals(listOf("Not done", "Delete"), sheet.labels())
        assertNull(sheet.edit)
        assertFalse(sheet.notable)
        assertTrue(sheet.fields.none { it.changes })
    }

    @Test
    fun `in mirror mode it's all there to read, and there's nothing to press`() {
        val sheet = view(task(source = Source.Own, steps = listOf(SubStep("First", 15))), writable = false, notes = listOf(Note("n", "Bring the book", now, By.You, taskId = "own:a")))
        assertTrue(sheet.actions.isEmpty())
        assertFalse(sheet.notable)
        assertNull(sheet.edit)
        assertNull(sheet.move)
        assertNull(sheet.where)
        assertTrue(sheet.fields.none { it.changes || it.undo != null })
        assertNull(sheet.steps.single().tick)
        assertNull(sheet.notes.single().delete)
        assertEquals("The a", sheet.title)
        assertTrue(sheet.notes.single().line.contains("Bring the book"), "what it says is all still there")
    }

    @Test
    fun `work due today can't go tomorrow`() {
        val sheet = view(task(due = "2026-10-12T22:00"))
        assertFalse("Tomorrow" in sheet.labels())
    }

    @Test
    fun `its fields say what they do, an event by its time alone`() {
        assertEquals(listOf(TaskAsk.DUE, TaskAsk.MINUTES, TaskAsk.START), view(task()).fields.map { it.kind })
        val event = view(task(source = Source.Own, kind = Kind.Event)).fields.single()
        assertEquals("When", event.label)
        assertTrue(event.changes)
        assertFalse(view(task(kind = Kind.Event)).fields.single().changes, "a source's event says its own time")
    }

    @Test
    fun `a value of yours can be cleared, and a quick change on a source's undone`() {
        val own = view(task(source = Source.Own)).fields.first { it.kind == TaskAsk.DUE }
        assertEquals("Clear", own.undo?.label)
        assertEquals(TaskAsk(TaskAsk.CLEAR_DUE), own.undo?.ask)
        assertNull(own.undo?.action, "asked of the task as it is, not sent as a copy of it")
        val source = task()
        val quick = Instruction("i", "Due Friday (set in Tasks)", About(taskId = source.id), now, InstructionStatus.Applied, changes = listOf(Change(ChangeType.DueBy, taskId = source.id, time = now)), quick = true)
        val undo = view(source, applied = listOf(quick)).fields.first { it.kind == TaskAsk.DUE }.undo
        assertEquals("Undo", undo?.label)
        assertEquals(TakeBackQuick(source.id, ChangeType.DueBy), undo?.action)
        assertNull(view(source).fields.first { it.kind == TaskAsk.DUE }.undo, "with nothing of yours on it, there's nothing to undo")
    }

    @Test
    fun `a standing note says it goes from every task it's for`() {
        val standing = Note("n", "On sheets", now, By.You, scope = NoteScope(source = Source.Teams))
        assertEquals("Delete the note for every task from Teams?", view(task(), notes = listOf(standing)).notes.single().delete?.confirm)
        val own = Note("m", "Bring the book", now, By.You, taskId = "teams:a")
        assertNull(view(task(), notes = listOf(own)).notes.single().delete?.confirm, "one on this task alone goes at a tap")
    }

    @Test
    fun `a move offers the fortnight, not the days past its deadline`() {
        val move = assertNotNull(view(task(due = "2026-10-14T12:00")).move)
        assertEquals(14, move.days.size)
        assertEquals(listOf(true, true, true, false), move.days.take(4).map { it.allowed })
        assertTrue(move.days[0].label.startsWith("Today"))
        assertEquals(listOf("No day after Wed 14: due before then."), move.notes)
    }

    @Test
    fun `work that's finished has nothing to move`() {
        assertNull(view(task(status = Status.Done)).move)
    }
}
