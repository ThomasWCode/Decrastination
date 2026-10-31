package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day as the website gets it (W-14): the phone's model in the phone's words, with the minutes it's laid out by. */
class DayViewTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val evening = listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    private fun plan() = Planner.plan(Planner.Input(listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = mapOf(monday to evening)))

    private fun view(
        date: LocalDate = monday,
        events: List<CalendarEvent> = emptyList(),
        records: List<SlotRecord> = emptyList(),
        now: Long = Fixtures.at("2026-10-12T17:10"),
        judge: (CalendarEvent) -> EventJudge.Judgement = { EventJudge.Judgement.Busy },
    ) = DayViews.of(plan(), date, events, records, evening, now, LONDON, emptyList(), judge, emptyMap(), asOf = 5L)

    @Test
    fun `today has its moment, its next thing and each piece by the minute, with the phone's words`() {
        val day = view()
        assertTrue(day.today)
        assertEquals("Monday 12 October", day.title)
        assertEquals(17 * 60 + 10, day.nowMin)
        assertEquals(listOf(WindowView("Your time", 17 * 60, 22 * 60)), day.windows)
        assertEquals(listOf("now", "ahead"), day.blocks.map { it.state })
        assertEquals(listOf("piece", "piece"), day.blocks.map { it.kind })
        assertEquals(17 * 60, day.blocks.first().startMin)
        assertTrue(day.blocks.first().endMin > day.blocks.first().startMin)
        assertEquals("a", assertNotNull(day.next).label.take(1))
        assertEquals(5L, day.asOf)
    }

    @Test
    fun `another day has neither moment nor next, and the week's strip says which is chosen`() {
        val day = view(date = monday.plusDays(2))
        assertFalse(day.today)
        assertNull(day.nowMin)
        assertNull(day.next)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), day.week.map { it.weekday })
        assertEquals(listOf(14), day.week.filter { it.chosen }.map { it.day })
        assertEquals(listOf(12), day.week.filter { it.today }.map { it.day })
    }

    @Test
    fun `an event over a piece goes beside it, as the phone lays them`() {
        val first = plan().todaySlots.first { it.kind == SlotKind.Work }
        val event = CalendarEvent(1, "Club", first.start, first.end, false)
        val day = view(events = listOf(event))
        val piece = day.blocks.first { it.kind == "piece" && it.startMin == 17 * 60 }
        val beside = day.blocks.single { it.kind == "event" }
        assertEquals(2, beside.columns)
        assertEquals(2, piece.columns)
        assertTrue(beside.column != piece.column)
    }

    @Test
    fun `an event says where it is, a blank one has a name, and what the plan counts free is marked`() {
        val at = Fixtures.at("2026-10-12T12:00")
        val day = view(
            events = listOf(CalendarEvent(1, "", at, at + 3_600_000L, false, location = "A room"), CalendarEvent(2, "Lunch", at + 7_200_000L, at + 10_800_000L, false)),
            judge = { if (it.id == 2L) EventJudge.Judgement.Free else EventJudge.Judgement.Busy },
        )
        val (blank, lunch) = day.blocks.filter { it.kind == "event" }
        assertEquals("(no title)", blank.title)
        assertEquals("A room", blank.note)
        assertFalse(blank.free)
        assertTrue(lunch.free)
    }

    @Test
    fun `what's on record says how it went`() {
        val slot = plan().todaySlots.first { it.kind == SlotKind.Work }
        fun record(key: String, outcome: SlotOutcome, hours: Long) = SlotRecord(
            date = "2026-10-12", key = key, taskId = "teams:x", kind = Kind.Admin, label = "x", plannedMin = 20,
            start = slot.start - hours * 3_600_000L, end = slot.start - (hours - 1) * 3_600_000L, window = WindowKind.Evening, outcome = outcome, workedMin = 25, at = slot.start,
        )
        val day = view(records = listOf(record("1", SlotOutcome.Kept, 3), record("2", SlotOutcome.Missed, 5), record("3", SlotOutcome.KeptLate, 7)))
        assertEquals(setOf("kept", "missed", "late"), day.blocks.filter { it.kind == "record" }.mapNotNull { it.outcome }.toSet())
    }
}
