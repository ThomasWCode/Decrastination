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
    fun `a task's new steps let go of its starts still to come, one begun kept (D-110)`() {
        val kept = SlotState("2026-10-13", previous = mapOf("teams:a#0" to 30L, "teams:a#1" to 5L, "teams:a#1#1" to 40L, "teams:ab#0" to 50L))
        assertEquals(mapOf("teams:a#1" to 5L, "teams:ab#0" to 50L), kept.released("teams:a", now = 10L).previous)
        // A step its new steps leave as it was keeps its start, so the times said of it still hold.
        assertEquals(mapOf("teams:a#0" to 30L, "teams:a#1" to 5L, "teams:ab#0" to 50L), kept.released("teams:a", now = 10L, keep = setOf(0)).previous)
    }

    @Test
    fun `a step is as it was where its id, title and minutes as planned are`() {
        val task = TaskItem(
            id = "teams:a", source = Source.Teams, sourceId = "a", title = "Worksheet", kind = Kind.Homework, firstSeenAt = 0, lastSeenAt = 0,
            subSteps = listOf(SubStep("Questions 1 and 2", 40, id = "q12"), SubStep("Question 3", 20, id = "q3"), SubStep("Write it up", 30, id = "up")),
        )
        // Two rebalanced, the third as it was; at the reading's paced minutes, the third laid at 45 and given at 45.
        val steps = listOf(OwnStep("Question 1", 20, id = "q12"), OwnStep("Questions 2 and 3", 40, id = "q3"), OwnStep("Write it up", 45, id = "up"))
        assertEquals(setOf(2), SlotState.unchanged(task, steps, planned = listOf(60, 30, 45)))
        // Given afresh (a change Felix writes comes without ids, and gets new ones as it's stored), the same title and minutes at its place: as it was.
        assertEquals(setOf(0), SlotState.unchanged(task, listOf(OwnStep("Questions 1 and 2", 40)), planned = listOf(40, 20, 30)))
        assertEquals(setOf(0), SlotState.unchanged(task, listOf(OwnStep("Questions 1 and 2", 40, id = "fresh123")), planned = listOf(40, 20, 30)))
        // Another step at its place (another's id), a new title or new minutes: not as it was.
        assertEquals(emptySet(), SlotState.unchanged(task, steps.reversed(), planned = listOf(40, 20, 30)))
        assertEquals(emptySet(), SlotState.unchanged(task, listOf(OwnStep("Questions 1 and 2", 40, id = "q3")), planned = listOf(40, 20, 30)))
        assertEquals(emptySet(), SlotState.unchanged(task, listOf(OwnStep("Questions 1 and 2", 35, id = "q12")), planned = listOf(40, 20, 30)))
        assertEquals(emptySet(), SlotState.unchanged(task, listOf(OwnStep("Questions 1 to 3", 40, id = "q12")), planned = listOf(40, 20, 30)))
    }

    @Test
    fun `a missed piece in none of today's slots went to another day`() {
        // Today's one slot is b's: a's piece, missed, is on Wednesday.
        val reconciled = state.missed("teams:a#-1#1", "teams:a", 10L).missed("teams:b#-1#1", "teams:b", 11L).reconciled(plan(chunk("teams:b"), later = chunk("teams:a")))
        assertEquals(listOf(true, false), reconciled.missed.map { it.spilled })
    }

    @Test
    fun `a missed piece whose task has since finished went to no day, and one taken as gone isn't once it finishes`() {
        // a's piece missed, and a finished before the plan: it's in none of the plan's days, so it went nowhere.
        val missed = state.missed("teams:a#-1#1", "teams:a", 10L).missed("teams:b#-1#1", "teams:b", 11L)
        val withoutA = plan(chunk("teams:b"))
        assertEquals(listOf(false, false), missed.reconciled(withoutA).missed.map { it.spilled })
        // a's piece went to tomorrow (spilled), then a finished: no longer kept as spilled; its miss is still recorded.
        val spilled = missed.reconciled(plan(chunk("teams:b"), later = chunk("teams:a")))
        assertEquals(listOf(true, false), spilled.missed.map { it.spilled })
        val after = spilled.reconciled(withoutA)
        assertEquals(listOf(false, false), after.missed.map { it.spilled })
        assertEquals(listOf("teams:a#-1#1", "teams:b#-1#1"), after.missed.map { it.key })
        // Still open but planned on no day (no room): as before, taken as gone from today.
        val noRoom = withoutA.copy(unplanned = listOf(Unplanned("teams:a", Source.Teams, "a", 30, 1, Unplanned.Reason.NoRoom)))
        assertEquals(listOf(true, false), missed.reconciled(noRoom).missed.map { it.spilled })
        // Run twice, the same.
        assertEquals(after, after.reconciled(withoutA))
    }

    private fun chunk(taskId: String) = Chunk(
        taskId = taskId, source = Source.Teams, kind = Kind.Homework, title = taskId.removePrefix("teams:"), step = null, minutes = 30, dueAt = null,
        deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false, part = 1, parts = 1,
        taskMinutes = 30, why = Why.Spread,
    )

    /** Tuesday's plan: [today] in its one slot at 17:00, and [later] on Wednesday where given. */
    private fun plan(today: Chunk, later: Chunk? = null): Plan {
        val slot = Slot(SlotKind.Work, today, Fixtures.at("2026-10-13T17:00"), Fixtures.at("2026-10-13T17:30"), WindowKind.Evening, pinned = false)
        val buckets = listOf(DayBucket(tuesday, 315, listOf(today), listOf(slot))) +
            listOfNotNull(later?.let { DayBucket(tuesday.plusDays(1), 315, listOf(it)) })
        return Plan(Fixtures.at("2026-10-13T16:00"), tuesday, buckets, emptyList())
    }
}
