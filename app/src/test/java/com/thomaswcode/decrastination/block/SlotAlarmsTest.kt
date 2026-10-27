package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
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

/** What the slot alarms ring for (PL-13). */
class SlotAlarmsTest {
    private val today = LocalDate.parse("2026-10-13")

    private fun work(step: String, start: String): Slot {
        val chunk = Chunk(
            taskId = "teams:essay", source = Source.Teams, kind = Kind.Homework, title = "Essay", step = step, minutes = 30, dueAt = Fixtures.at("2026-10-20T23:59"),
            deadline = Fixtures.at("2026-10-20T23:59"), soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 2, taskMinutes = 60, why = Why.Spread,
        )
        return Slot(SlotKind.Work, chunk, Fixtures.at(start), Fixtures.at(start) + 30 * 60_000L, WindowKind.Evening, pinned = false)
    }

    @Test
    fun `with nothing today or tomorrow, a slot later in the week still rings`() {
        val thursday = work("Draft", "2026-10-15T17:00")
        val days = listOf(DayBucket(today, 300, emptyList()), DayBucket(today.plusDays(1), 300, emptyList()), DayBucket(today.plusDays(2), 300, listOf(thursday.chunk!!), listOf(thursday)))
        val plan = Plan(Fixtures.at("2026-10-13T16:00"), today, days, emptyList())
        assertEquals((thursday to thursday.start) to thursday, SlotAlarms.next(plan, plan.now))
    }

    @Test
    fun `a slot under way rings at its end, the next at its start, and one in its grace at its end still`() {
        val first = work("Draft", "2026-10-13T17:00")
        val second = work("Finish", "2026-10-13T17:35")
        val plan = Plan(Fixtures.at("2026-10-13T17:10"), today, listOf(DayBucket(today, 300, listOf(first.chunk!!, second.chunk!!), listOf(first, second))), emptyList())
        assertEquals((second to second.start) to first, SlotAlarms.next(plan, plan.now))
        assertEquals(null to first, SlotAlarms.next(plan, Fixtures.at("2026-10-13T17:36")))
    }

    @Test
    fun `a break outside rings at its start and at its end, so the plan's made again as it's completed (D-113)`() {
        val outside = Slot(SlotKind.Outside, null, Fixtures.at("2026-10-13T13:00"), Fixtures.at("2026-10-13T13:20"), WindowKind.Evening, pinned = false, outsideAt = 780)
        val draft = work("Draft", "2026-10-13T17:00")
        val plan = Plan(Fixtures.at("2026-10-13T12:00"), today, listOf(DayBucket(today, 300, listOf(draft.chunk!!), listOf(outside, draft))), emptyList())
        assertEquals((outside to outside.start) to draft, SlotAlarms.next(plan, plan.now))
        // Under way: its end, before the next piece's start; the end alarm stays the piece's.
        assertEquals((outside to outside.end) to draft, SlotAlarms.next(plan, Fixtures.at("2026-10-13T13:05")))
        assertEquals((draft to draft.start) to draft, SlotAlarms.next(plan, Fixtures.at("2026-10-13T13:20")))
    }
}
