package com.thomaswcode.decrastination.calendar

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.calendar.SlotCalendarDiff.Desired
import com.thomaswcode.decrastination.calendar.SlotCalendarDiff.Existing
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.Why
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plan's slots written to the calendar (NF-07): what a write changes. */
class SlotCalendarDiffTest {
    private val five = Fixtures.at("2026-10-13T17:00")
    private val half = Fixtures.at("2026-10-13T17:30")

    private fun want(key: String, start: Long = five, title: String = "Essay", taskId: String? = "teams:essay") =
        Desired("decrastination://slot/2026-10-13/$key", title, "Teams. Spread over the 3 days to its deadline.", start, start + 30 * 60_000L, taskId)

    private fun event(id: Long, key: String, start: Long = five, title: String = "Essay") =
        Existing(id, "decrastination://slot/2026-10-13/$key", start, start + 30 * 60_000L, title, "Teams. Spread over the 3 days to its deadline.")

    @Test
    fun `a slot not there is inserted, one changed updated, one gone deleted, one as it was left`() {
        val written = mapOf(want("a").uri to five, want("b").uri to five)
        val diff = SlotCalendarDiff.diff(
            desired = listOf(want("a"), want("b", title = "Essay: part 2"), want("c")),
            existing = listOf(event(1, "a"), event(2, "b"), event(3, "old")),
            written = written,
        )
        assertEquals(listOf(want("c")), diff.inserts)
        assertEquals(listOf(2L), diff.updates.map { it.existing.id })
        assertEquals(listOf(3L), diff.deletes.map { it.id })
        assertEquals(emptyList(), diff.handMoves)
    }

    @Test
    fun `an event you moved is a hand move, not an update, and one you deleted is written again`() {
        val written = mapOf(want("a").uri to five, want("b").uri to five)
        // a dragged to 17:30; b deleted, so not there.
        val diff = SlotCalendarDiff.diff(listOf(want("a"), want("b")), listOf(event(1, "a", start = half)), written)
        assertEquals(listOf(1L), diff.handMoves.map { it.existing.id })
        assertEquals(emptyList(), diff.updates)
        assertEquals(listOf(want("b")), diff.inserts)
        // A break outside moved is put back: it's no piece's time to take.
        val outside = SlotCalendarDiff.diff(listOf(want("outside:1", title = "Outside", taskId = null)), listOf(event(4, "outside:1", start = half, title = "Outside")), mapOf(want("outside:1").uri to five))
        assertEquals(listOf(4L), outside.updates.map { it.existing.id })
    }

    @Test
    fun `an event moved where the plan now has it is left be, and one the app moved isn't yours`() {
        // The plan moved a to 17:30 and the event is there already: nothing to do.
        assertEquals(SlotCalendarDiff.Diff(emptyList(), emptyList(), emptyList(), emptyList()), SlotCalendarDiff.diff(listOf(want("a", start = half)), listOf(event(1, "a", start = half)), mapOf(want("a").uri to five)))
        // The plan moved a and the event's where the app wrote it: the app's to update.
        val moved = SlotCalendarDiff.diff(listOf(want("a", start = half)), listOf(event(1, "a")), mapOf(want("a").uri to five))
        assertEquals(listOf(1L), moved.updates.map { it.existing.id })
        assertEquals(emptyList(), moved.handMoves)
        // A second copy of an event goes.
        assertEquals(listOf(2L), SlotCalendarDiff.diff(listOf(want("a")), listOf(event(1, "a"), event(2, "a")), mapOf(want("a").uri to five)).deletes.map { it.id })
    }

    @Test
    fun `the next seven days' slots are the events, a piece's and a break outside's`() {
        val today = LocalDate.parse("2026-10-13")
        val chunk = Chunk(
            taskId = "teams:essay", source = Source.Teams, kind = Kind.Homework, title = "Essay", step = "Draft", minutes = 30, dueAt = Fixtures.at("2026-10-16T23:59"),
            deadline = Fixtures.at("2026-10-16T23:59"), soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 2, taskMinutes = 60, why = Why.Spread,
        )
        val work = Slot(SlotKind.Work, chunk, five, half, WindowKind.Evening, pinned = false)
        val outside = Slot(SlotKind.Outside, null, Fixtures.at("2026-10-17T09:30"), Fixtures.at("2026-10-17T09:50"), WindowKind.Evening, pinned = false)
        val far = Slot(SlotKind.Work, chunk, Fixtures.at("2026-10-20T17:00"), Fixtures.at("2026-10-20T17:30"), WindowKind.Evening, pinned = false)
        val plan = Plan(
            five - 60_000L, today,
            listOf(DayBucket(today, 300, listOf(chunk), listOf(work)), DayBucket(today.plusDays(4), 840, emptyList(), listOf(outside)), DayBucket(today.plusDays(7), 300, listOf(chunk), listOf(far))),
            emptyList(),
        )
        val desired = SlotCalendarDiff.desired(plan, LONDON)
        assertEquals(listOf("decrastination://slot/2026-10-13/teams:essay#-1#1", "decrastination://slot/2026-10-17/outside:${outside.start}"), desired.map { it.uri })
        assertEquals(listOf("Essay: Draft", "Outside"), desired.map { it.title })
        assertEquals("Teams. Spread over the 3 days to its deadline.", desired.first().description)
        assertEquals(listOf("teams:essay", null), desired.map { it.taskId })
    }
}
