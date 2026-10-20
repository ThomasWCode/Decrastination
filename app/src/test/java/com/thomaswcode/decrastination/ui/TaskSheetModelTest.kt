package com.thomaswcode.decrastination.ui

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
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a task's sheet says of its pieces and its instructions (UI-09). */
class TaskSheetModelTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val evening = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    /** Monday's plan as made at 16:00: two 30-minute pieces in the evening, both due tonight. */
    private val plan = Planner.plan(Planner.Input(listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = evening))

    @Test
    fun `a task with all its work done but its source's word still has its last minutes planned, and is said to`() {
        val done = task("a", 30).copy(workedMin = 30)
        assertTrue(worked(done, 1.0))
        assertFalse(worked(task("a", 30).copy(workedMin = 25), 1.0))
        // Calibrated: 30 minutes taken as 33.3, 33 worked; the 0.3 left rounds to none, as the planner rounds it.
        assertTrue(worked(task("a", 30).copy(workedMin = 33), 1.11))
        assertFalse(worked(task("a", 30).copy(workedMin = 32), 1.11))
        // Planned all the same, a last few minutes, till its source says it's done (planner.md).
        val p = Planner.plan(Planner.Input(listOf(done), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = evening))
        assertEquals(5, p.ordered.single().minutes)
        // With steps: when every one is ticked.
        val steps = task("b", 30).copy(subSteps = listOf(SubStep("Plan", 10, done = true), SubStep("Write", 20)))
        assertFalse(worked(steps, 1.0))
        assertTrue(worked(steps.copy(subSteps = steps.subSteps.map { it.copy(done = true) }), 1.0))
    }

    @Test
    fun `a task's pieces say when they're planned and why`() {
        val p = TaskSheetModel.planned(plan, "teams:a", LONDON).single()
        val slot = plan.todaySlots.single { it.chunk?.taskId == "teams:a" }
        assertEquals(Triple(monday, slot.start, slot.end), Triple(p.day, p.start, p.end))
        assertEquals("Today ${Format.span(slot.start, slot.end, LONDON)}: due today", TaskSheetModel.line(p, monday, LONDON))
        assertEquals(p.chunk, TaskSheetModel.next(plan, "teams:a"))
        // A later day's, with no slot laid yet: its day, then the date past a week.
        val later = p.copy(day = monday.plusDays(3), start = null, end = null, why = "Spread over the 12 days to its deadline")
        assertEquals("Thu: spread over the 12 days to its deadline", TaskSheetModel.line(later, monday, LONDON))
        assertEquals("Thu 22 Oct: spread over the 12 days to its deadline", TaskSheetModel.line(later.copy(day = monday.plusDays(10)), monday, LONDON))
        // One of several pieces is named.
        val part = later.copy(chunk = p.chunk.copy(part = 2, parts = 3))
        assertEquals("Tomorrow, part 2 of 3: due today", TaskSheetModel.line(part.copy(day = monday.plusDays(1), why = "Due today"), monday, LONDON))
    }

    @Test
    fun `a task's work can't go past its real deadline's day, nor before it can start`() {
        val a = task("a", 30)
        val now = Fixtures.at("2026-10-12T16:00")
        assertTrue(TaskSheetModel.canGo(a, monday, now, LONDON))
        assertFalse(TaskSheetModel.canGo(a, monday.plusDays(1), now, LONDON))
        val later = a.copy(dueAt = Fixtures.at("2026-10-20T23:59"), notBefore = Fixtures.at("2026-10-14T00:00"))
        assertFalse(TaskSheetModel.canGo(later, monday.plusDays(1), now, LONDON))
        assertTrue(TaskSheetModel.canGo(later, monday.plusDays(2), now, LONDON))
        // Overdue, its deadline gone by, it can go to any day (D-30).
        assertTrue(TaskSheetModel.canGo(a, monday.plusDays(1), Fixtures.at("2026-10-13T08:00"), LONDON))
    }

    @Test
    fun `a task's notes are listed, its own then the standing ones it matches, each with who wrote it and when, none deleted`() {
        val now = Fixtures.at("2026-10-12T18:00")
        val sheet = task("sheet", 30).copy(className = "12X/Ph1")
        val notes = listOf(
            Note("n2", "on sheets, not in the book", Fixtures.at("2026-10-10T17:00"), By.You, scope = NoteScope(subject = "Physics")),
            Note("n1", "show the working", Fixtures.at("2026-10-12T17:00"), By.Felix, taskId = sheet.id),
            Note("n3", "deleted", Fixtures.at("2026-10-12T17:00"), By.You, taskId = sheet.id, deletedAt = now),
            Note("n4", "another task's", Fixtures.at("2026-10-12T17:00"), By.You, taskId = "teams:other"),
        )
        assertEquals(
            listOf(TaskSheetModel.Noted("n1", "Fe, today 17:00: show the working"), TaskSheetModel.Noted("n2", "Note for every Physics task: on sheets, not in the book (yours, Sat 10 Oct 17:00)")),
            TaskSheetModel.notes(sheet, notes, mapOf("12X/Ph1" to "Physics"), "Fe", now, LONDON),
        )
    }

    @Test
    fun `the instructions on a task are those about it or changing it, the newest first`() {
        fun applied(id: String, text: String, about: String?, changes: List<Change> = emptyList(), at: Long) =
            Instruction(id, text, About(taskId = about), at = at, state = InstructionStatus.Applied, changes = changes, appliedAt = at)
        val all = listOf(
            applied("1", "It's due Friday", "teams:a", at = 1),
            applied("2", "Do b after a", "teams:b", listOf(Change(ChangeType.After, taskId = "teams:a", afterTaskId = "teams:b")), at = 2),
            applied("3", "Nothing on Sunday", null, at = 3),
        )
        assertEquals(listOf("Do b after a", "It's due Friday"), TaskSheetModel.instructions(all, "teams:a"))
        assertEquals(listOf("Do b after a"), TaskSheetModel.instructions(all, "teams:b"))
    }
}
