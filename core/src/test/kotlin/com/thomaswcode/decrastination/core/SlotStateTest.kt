package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Today's slots kept between plans, and the missed ones (PL-13). */
class SlotStateTest {
    private val tuesday = LocalDate.parse("2026-10-13")
    private val state = SlotState("2026-10-13", previous = mapOf("teams:a#-1#1" to 1L, "teams:b#-1#1" to 2L))

    @Test
    fun `a new day starts empty`() {
        assertEquals(state, state.on(tuesday))
        assertEquals(SlotState("2026-10-14"), state.on(tuesday.plusDays(1)))
    }

    @Test
    fun `a missed slot is recorded once, and its start no longer kept`() {
        val missed = state.missed("teams:a#-1#1", "teams:a", 10L).missed("teams:a#-1#1", "teams:a", 20L)
        assertEquals(listOf(MissedSlot("teams:a#-1#1", "teams:a", 10L)), missed.missed)
        assertEquals(mapOf("teams:b#-1#1" to 2L), missed.previous)
    }

    @Test
    fun `a slot kept after all leaves both, and a task moved on purpose forgets its misses`() {
        val missed = state.missed("teams:a#-1#1", "teams:a", 10L)
        // a kept: off the starts; b missed (off the starts), then kept: off the missed too.
        assertEquals(SlotState("2026-10-13", previous = mapOf("teams:b#-1#1" to 2L)), state.kept("teams:a#-1#1"))
        assertEquals(SlotState("2026-10-13"), state.kept("teams:a#-1#1").missed("teams:b#-1#1", "teams:b", 5L).kept("teams:b#-1#1"))
        assertEquals(emptyList(), missed.moved("teams:a").missed)
        assertEquals(missed.missed, missed.moved("teams:b").missed)
    }

    @Test
    fun `a missed piece in none of today's slots went to another day`() {
        // Today's one slot is b's: a's piece, missed, is somewhere else.
        val chunk = Chunk(
            taskId = "teams:b", source = Source.Teams, kind = Kind.Homework, title = "b", step = null, minutes = 30, dueAt = null,
            deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 1,
            taskMinutes = 30, why = Why.Spread,
        )
        val slot = Slot(SlotKind.Work, chunk, Fixtures.at("2026-10-13T17:00"), Fixtures.at("2026-10-13T17:30"), WindowKind.Evening, pinned = false)
        val plan = Plan(Fixtures.at("2026-10-13T16:00"), tuesday, listOf(DayBucket(tuesday, 315, listOf(chunk), listOf(slot))), emptyList())
        val reconciled = state.missed("teams:a#-1#1", "teams:a", 10L).missed("teams:b#-1#1", "teams:b", 11L).reconciled(plan)
        assertEquals(listOf(true, false), reconciled.missed.map { it.spilled })
    }
}
