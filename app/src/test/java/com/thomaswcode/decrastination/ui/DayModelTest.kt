package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.WontFit
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day as the Day's grid shows it (UI-17, UI-02, UI-08, 4.6.0). */
class DayModelTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val evening = listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    /** Monday's plan as made at 16:00: two pieces. */
    private fun plan() = Planner.plan(Planner.Input(listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = mapOf(monday to evening)))

    @Test
    fun `blocks go by time, the windows apart, the slot under way marked, and the moment today's alone`() {
        val p = plan()
        val model = DayModel.from(p, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T17:10"), LONDON)
        assertEquals(listOf(DayModel.State.Now, DayModel.State.Ahead), model.blocks.map { (it.item as DayModel.Row.Planned).state })
        assertEquals(listOf(Fixtures.at("2026-10-12T17:00") to Fixtures.at("2026-10-12T22:00")), model.windows.map { it.start to it.end })
        assertEquals(Fixtures.at("2026-10-12T17:10"), model.now)
        assertEquals(60 to 300, model.plannedMin to model.freeMin)
        // Another day than today: no moment, and it opens half an hour before its window.
        val ahead = DayModel.from(p, monday.plusDays(1), emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T17:10"), LONDON)
        assertNull(ahead.now)
        assertEquals(16 * 60 + 30, ahead.opensAt)
    }

    @Test
    fun `all-day events are kept apart, an event across midnight is cut to each day, and an earlier block opens the day earlier`() {
        val p = plan()
        val trip = CalendarEvent(2, "Trip", Instant.parse("2026-10-12T00:00:00Z").toEpochMilli(), Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(), true)
        val late = CalendarEvent(3, "Late film", Fixtures.at("2026-10-12T23:00"), Fixtures.at("2026-10-13T01:00"), false)
        val model = DayModel.from(p, monday, listOf(trip, late), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON)
        assertEquals(listOf(trip), model.allDay)
        assertEquals(Fixtures.at("2026-10-12T23:00") to Fixtures.at("2026-10-13T00:00"), model.blocks.single { it.item is DayModel.Row.Event }.let { it.start to it.end })
        val next = DayModel.from(p, monday.plusDays(1), listOf(late), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON)
        assertEquals(Fixtures.at("2026-10-13T00:00") to Fixtures.at("2026-10-13T01:00"), next.blocks.single().let { it.start to it.end })
        // Tuesday's block from 00:00 opens it at 00:00, not before.
        assertEquals(0, next.opensAt)
        val early = CalendarEvent(4, "Swim", Fixtures.at("2026-10-13T07:00"), Fixtures.at("2026-10-13T08:00"), false)
        assertEquals(6 * 60 + 30, DayModel.from(p, monday.plusDays(1), listOf(early), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).opensAt)
    }

    @Test
    fun `what was done is on record in its place and listed, with its minutes, a move of yours neither, and your busy times are blocks too`() {
        val p = plan()
        val slot = p.todaySlots.first { it.kind == SlotKind.Work }
        val kept = SlotRecord(
            date = "2026-10-12", key = "teams:x#-1#1", taskId = "teams:x", kind = Kind.Admin, label = "x", plannedMin = 20,
            start = slot.start - 3_600_000L, end = slot.start - 2_400_000L, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 25, at = slot.start - 2_400_000L,
        )
        val moved = kept.copy(key = "teams:y#-1#1", outcome = SlotOutcome.Moved)
        val busy = BusyBlock(monday, 19 * 60, 20 * 60, "Football")
        val model = DayModel.from(p, monday, emptyList(), listOf(kept, moved), evening, Fixtures.at("2026-10-12T16:30"), LONDON, busy = listOf(busy))
        assertTrue(model.blocks.first().item is DayModel.Row.Recorded)
        assertEquals(listOf(kept), model.done)
        assertEquals(25, model.doneMin)
        assertTrue(model.blocks.none { (it.item as? DayModel.Row.Recorded)?.record?.outcome == SlotOutcome.Moved })
        assertEquals(Fixtures.at("2026-10-12T19:00") to Fixtures.at("2026-10-12T20:00"), model.blocks.single { it.item is DayModel.Row.Busy }.let { it.start to it.end })
    }

    @Test
    fun `a slot kept twice at one start is two blocks, each with its own key, and the pieces with no time are listed`() {
        val p = plan()
        val slot = p.todaySlots.first { it.kind == SlotKind.Work }
        val kept = SlotRecord(
            date = "2026-10-12", key = "anki:quota#-1#1", taskId = "anki:quota", kind = Kind.Revision, label = "Anki", plannedMin = 5,
            start = slot.start + 7_200_000L, end = slot.start + 7_500_000L, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 5, at = slot.start - 60_000L,
        )
        val again = kept.copy(at = kept.at + 4_500L)
        val model = DayModel.from(p, monday, emptyList(), listOf(kept, again), evening, Fixtures.at("2026-10-12T16:30"), LONDON)
        val keys = model.blocks.map(::blockKey)
        assertEquals(2, model.blocks.count { it.item is DayModel.Row.Recorded })
        assertEquals(keys.size, keys.toSet().size)
        val piece = p.ordered.first()
        val unslotted = p.copy(buckets = p.buckets.map { if (it.date == monday) it.copy(unslotted = listOf(piece)) else it })
        assertEquals(listOf(piece), DayModel.from(unslotted, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).unslotted)
    }

    @Test
    fun `today's real deadlines its days can't hold are said`() {
        val short = plan().copy(wontFit = listOf(WontFit("teams:a", "a", 120, 60, Fixtures.at("2026-10-13T09:00"))))
        assertEquals("1 won't fit before its deadline", DayModel.from(short, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).wontFit)
        assertNull(DayModel.from(plan(), monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T16:30"), LONDON).wontFit)
    }
}
