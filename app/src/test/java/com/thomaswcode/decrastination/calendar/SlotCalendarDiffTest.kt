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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // Moved out of the week (found by what it is): a move all the same.
        val far = SlotCalendarDiff.diff(listOf(want("a")), listOf(event(1, "a", start = five + 9 * 24 * 3_600_000L)), written)
        assertEquals(listOf(1L), far.handMoves.map { it.existing.id })
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
    fun `of two moves of one task's pieces the earlier is taken, the other written back and said`() {
        val hour = 3_600_000L
        val written = mapOf(want("a").uri to five, want("b").uri to half)
        // a dragged to 19:00, b to 18:00: b's the earlier, so its time is the task's.
        val diff = SlotCalendarDiff.diff(listOf(want("a"), want("b", start = half)), listOf(event(1, "a", start = five + 2 * hour), event(2, "b", start = five + hour)), written)
        val judged = SlotCalendarDiff.judge(diff.handMoves) { null }
        assertEquals(listOf(2L), judged.taken.map { it.existing.id })
        assertEquals(listOf(1L to SlotCalendarDiff.ONE_AT_A_TIME), judged.refused.map { it.first.existing.id to it.second })
        // One refused for its own reason leaves the task's next move free to be taken.
        val late = SlotCalendarDiff.judge(diff.handMoves) { if (it.existing.id == 2L) "it's due before then" else null }
        assertEquals(listOf(1L), late.taken.map { it.existing.id })
        assertEquals(listOf(2L to "it's due before then"), late.refused.map { it.first.existing.id to it.second })
    }

    @Test
    fun `a move taken counts as written only once its instruction is in`() {
        val written = mapOf(want("a").uri to five)
        val moved = listOf(event(1, "a", start = half))
        val diff = SlotCalendarDiff.diff(listOf(want("a")), moved, written)
        // The write leaves a at your 17:30; what's kept till its instruction is in is the app's 17:00.
        val pending = SlotCalendarDiff.pending(mapOf(want("a").uri to half), written, diff.handMoves)
        assertEquals(mapOf(want("a").uri to five), pending)
        // Stopped there, the next write finds the move again.
        assertEquals(listOf(1L), SlotCalendarDiff.diff(listOf(want("a")), moved, pending).handMoves.map { it.existing.id })
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
        // Every event's words are the app's: a copy that loses its marks is found by them.
        assertTrue(desired.all { SlotCalendarDiff.looksWritten(it.description) })
    }

    @Test
    fun `a copy of one of the app's events that lost its marks is deleted, found by its words`() {
        val copy = Existing(9, "", five, five + 30 * 60_000L, "Essay", "Teams. Spread over the 3 days to its deadline.", marked = false)
        val diff = SlotCalendarDiff.diff(listOf(want("a")), listOf(event(1, "a"), copy), mapOf(want("a").uri to five))
        assertEquals(listOf(9L), diff.deletes.map { it.id })
        assertEquals(emptyList(), diff.inserts)
        assertEquals(emptyList(), diff.handMoves)
        // The app's words: a piece's, its source first, or a break outside's; not an event of yours.
        assertTrue(SlotCalendarDiff.looksWritten("Yours. Undated: fills spare time this week. First: Open your vocab book"))
        assertTrue(SlotCalendarDiff.looksWritten("Power Planner. Due Mon 19 Oct."))
        assertTrue(SlotCalendarDiff.looksWritten("A break outside."))
        assertFalse(SlotCalendarDiff.looksWritten("Bring the signed form"))
        assertFalse(SlotCalendarDiff.looksWritten("Teamsheet for Saturday"))
        assertFalse(SlotCalendarDiff.looksWritten(""))
    }

    @Test
    fun `a copy is one the app wrote, under its title at its times, and an event of yours in its words isn't`() {
        val end = five + 30 * 60_000L
        val words = "Gmail. Due Mon 19 Oct."
        val versions = setOf(SlotCalendarDiff.version(22, "Essay", five, end))
        assertTrue(SlotCalendarDiff.isCopy(22, "Essay", words, five, end, versions))
        // Worded like one, but never written: yours, kept. Nor one at another time, of another name, or in another calendar.
        assertFalse(SlotCalendarDiff.isCopy(22, "Dentist", words, five, end, versions))
        assertFalse(SlotCalendarDiff.isCopy(22, "Essay", words, half, half + 30 * 60_000L, versions))
        assertFalse(SlotCalendarDiff.isCopy(7, "Essay", words, five, end, versions))
        // At a time the app wrote, but not in its words.
        assertFalse(SlotCalendarDiff.isCopy(22, "Essay", "Bring the signed form", five, end, versions))
        assertEquals(SlotCalendarDiff.version(22, "Essay", five, end), SlotCalendarDiff.version(22, "Essay", five, end))
    }

    @Test
    fun `an event of yours at a slot's title and times before it's written is never remembered, so never taken for a copy`() {
        // #48's third review: yours there first, in the app's words, under a slot's title at its times.
        val end = five + 30 * 60_000L
        val slot = want("a")
        val mine = Existing(9, "", five, end, "Essay", "Gmail. Mine, worded so.", marked = false)
        val version = SlotCalendarDiff.version(22, "Essay", five, end)
        val yours = SlotCalendarDiff.yours(22, listOf(slot), listOf(mine), versions = emptySet())
        assertEquals(setOf(version), yours)
        // Not remembered at this write, so the next doesn't take it for a copy.
        val remembered = SlotCalendarDiff.remembered(emptyList(), listOf(version).filterNot { it in yours })
        assertFalse(SlotCalendarDiff.isCopy(22, mine.title, mine.description, mine.start, mine.end, remembered.toSet()))
        // A copy of a version written before is still one, never yours; nor is an event of the app's.
        val earlier = SlotCalendarDiff.version(22, "Essay", half, half + 30 * 60_000L)
        val copy = Existing(10, "", half, half + 30 * 60_000L, "Essay", "Teams. Spread over the 3 days to its deadline.", marked = false)
        assertEquals(emptySet(), SlotCalendarDiff.yours(22, listOf(slot, want("b", start = half)), listOf(copy), setOf(earlier)))
        assertEquals(emptySet(), SlotCalendarDiff.yours(22, listOf(slot), listOf(event(11, "a")), emptySet()))
    }

    @Test
    fun `the versions written are remembered once each, the latest last, the oldest let go`() {
        assertEquals(listOf(1L, 3L, 2L, 4L), SlotCalendarDiff.remembered(listOf(1, 2, 3), listOf(2, 4, 4)))
        val full = (1L..SlotCalendarDiff.MAX_VERSIONS.toLong()).toList()
        val next = SlotCalendarDiff.remembered(full, listOf(0L))
        assertEquals(SlotCalendarDiff.MAX_VERSIONS, next.size)
        assertEquals(listOf(2L, 3L), next.take(2))
        assertEquals(0L, next.last())
        // Written again, nothing changes.
        assertEquals(next, SlotCalendarDiff.remembered(next, listOf(0L)))
    }
}
