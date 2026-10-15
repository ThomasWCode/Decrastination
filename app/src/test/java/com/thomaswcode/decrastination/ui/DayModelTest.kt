package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.WontFit
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day as the Day screen shows it (UI-17, UI-02, UI-08). */
class DayModelTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val evening = listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    /** Monday's plan as made at 16:00: two pieces, the second's start kept at [secondAt] if given. */
    private fun plan(secondAt: String? = null) = Planner.plan(
        Planner.Input(
            listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(),
            windows = mapOf(monday to evening),
            previousSlots = secondAt?.let { mapOf("teams:b#-1#1" to Fixtures.at("2026-10-12T$it")) }.orEmpty(),
        ),
    )

    @Test
    fun `rows go by time, the window first, the slot under way marked and the now line after it`() {
        val p = plan()
        val model = DayModel.from(p, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T17:10"), LONDON)
        val kinds = model.rows.map { it::class.simpleName }
        assertEquals(listOf("Window", "Planned", "Now", "Planned"), kinds)
        assertEquals(DayModel.State.Now, (model.rows[1] as DayModel.Row.Planned).state)
        assertEquals(DayModel.State.Ahead, (model.rows[3] as DayModel.Row.Planned).state)
        assertEquals(60 to 300, model.plannedMin to model.freeMin)
        // Another day than today: no now line.
        val ahead = DayModel.from(p, monday.plusDays(1), emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T17:10"), LONDON)
        assertTrue(ahead.rows.none { it is DayModel.Row.Now })
    }

    @Test
    fun `free time between two slots is said, the short breaks aren't`() {
        val spaced = DayModel.from(plan(secondAt = "18:00"), monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON)
        val gap = spaced.rows.filterIsInstance<DayModel.Row.Gap>().single()
        assertEquals(30, gap.minutes)
        // Five minutes between pieces: not a gap to say.
        assertTrue(DayModel.from(plan(), monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).rows.none { it is DayModel.Row.Gap })
    }

    @Test
    fun `what was done is on record in its place and at the foot, with its minutes, and an event in its day`() {
        val p = plan()
        val slot = p.todaySlots.first { it.kind == SlotKind.Work }
        val kept = SlotRecord(
            date = "2026-10-12", key = "teams:x#-1#1", taskId = "teams:x", kind = Kind.Admin, label = "x", plannedMin = 20,
            start = slot.start - 3_600_000L, end = slot.start - 2_400_000L, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 25, at = slot.start - 2_400_000L,
        )
        val moved = kept.copy(key = "teams:y#-1#1", outcome = SlotOutcome.Moved)
        val match = CalendarEvent(1, "Match", Fixtures.at("2026-10-12T19:00"), Fixtures.at("2026-10-12T20:30"), false)
        val model = DayModel.from(p, monday, listOf(match), listOf(kept, moved), evening, Fixtures.at("2026-10-12T16:30"), LONDON)
        assertIs<DayModel.Row.Recorded>(model.rows.first())
        assertEquals(listOf(kept), (model.rows.last() as DayModel.Row.Done).records)
        assertEquals(25, model.doneMin)
        assertTrue(model.rows.any { it is DayModel.Row.Event && it.event == match })
        assertTrue(model.rows.none { it is DayModel.Row.Recorded && it.record.outcome == SlotOutcome.Moved })
    }

    @Test
    fun `today's real deadlines its days can't hold are said`() {
        val short = plan().copy(wontFit = listOf(WontFit("teams:a", "a", 120, 60, Fixtures.at("2026-10-13T09:00"))))
        assertEquals("1 won't fit before its deadline", DayModel.from(short, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).wontFit)
        assertNull(DayModel.from(plan(), monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).wontFit)
    }
}
