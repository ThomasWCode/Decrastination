package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a missed slot's sheet offers (docs/spec/day.md §A missed slot's sheet). */
class MissedActionsTest {
    private val monday = LocalDate.parse("2026-10-12")

    private fun task(source: Source = Source.Teams, status: Status = Status.Open) = TaskItem(
        id = "t", source = source, sourceId = "t", title = "Essay", dueAt = Fixtures.at("2026-10-14T23:59"), kind = Kind.Homework,
        sourceEffortMin = 30, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"), status = status,
    )

    /** The task's piece in a plan made as it is. */
    private fun piece(task: TaskItem): Chunk = Planner.plan(
        Planner.Input(listOf(task), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))),
    ).ordered.first()

    private fun miss(start: String, end: String, at: String = end) = SlotRecord(
        date = "2026-10-12", key = "t#-1#1", taskId = "t", kind = Kind.Homework, label = "Essay", plannedMin = 30,
        start = Fixtures.at(start), end = Fixtures.at(end), window = WindowKind.Evening, outcome = SlotOutcome.Missed, workedMin = 0, at = Fixtures.at(at),
    )

    private val slot = miss("2026-10-12T16:00", "2026-10-12T16:45")

    @Test
    fun `a missed slot with its piece in the plan has Done, Open, Check it's done and Ask Felix about it`() {
        val task = task()
        assertEquals(
            MissedActions(done = true, doneOnLater = false, check = true, allDone = false, ask = true, open = true, since = false),
            MissedActions.of(slot, listOf(slot), piece(task), task),
        )
    }

    @Test
    fun `one of your own has All of it done in Check it's done's place`() {
        val own = task(Source.Own)
        val actions = MissedActions.of(slot, listOf(slot), piece(own), own)
        assertTrue(actions.allDone)
        assertFalse(actions.check)
        assertTrue(actions.done)
    }

    @Test
    fun `with a later miss of its key that day, Done is on that one, and said so here`() {
        val task = task()
        val later = miss("2026-10-12T17:00", "2026-10-12T17:45")
        val earlier = MissedActions.of(slot, listOf(slot, later), piece(task), task)
        assertFalse(earlier.done)
        assertTrue(earlier.doneOnLater)
        assertTrue(earlier.check && earlier.ask && earlier.open)
        assertTrue(MissedActions.of(later, listOf(slot, later), piece(task), task).done)
    }

    @Test
    fun `the last miss is the log's, as Focus keeps it, whatever their times, and another day's or a kept one doesn't count`() {
        val task = task()
        val later = miss("2026-10-12T17:00", "2026-10-12T17:45")
        // An Undo puts a miss back at the log's end, so the earlier slot can be its last.
        assertTrue(MissedActions.of(slot, listOf(later, slot), piece(task), task).done)
        assertTrue(MissedActions.of(slot, listOf(slot, later.copy(outcome = SlotOutcome.KeptLate)), piece(task), task).done)
        assertTrue(MissedActions.of(slot, listOf(slot, later.copy(date = "2026-10-11")), piece(task), task).done)
        assertTrue(MissedActions.of(slot, listOf(slot, later.copy(key = "t#-1#2")), piece(task), task).done)
    }

    @Test
    fun `a task done since has only Open, and says so`() {
        val done = task(status = Status.Done)
        assertEquals(
            MissedActions(done = false, doneOnLater = false, check = false, allDone = false, ask = false, open = true, since = true),
            MissedActions.of(slot, listOf(slot), piece(task()), done),
        )
    }

    @Test
    fun `an open task whose piece has left the plan has only Open too, and a task gone has nothing`() {
        assertEquals(
            MissedActions(done = false, doneOnLater = false, check = false, allDone = false, ask = false, open = true, since = true),
            MissedActions.of(slot, listOf(slot), null, task()),
        )
        assertEquals(
            MissedActions(done = false, doneOnLater = false, check = false, allDone = false, ask = false, open = false, since = true),
            MissedActions.of(slot, listOf(slot), null, null),
        )
    }
}
