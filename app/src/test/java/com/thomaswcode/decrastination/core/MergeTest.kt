package com.thomaswcode.decrastination.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeTest {
    private val t0 = 1_000_000L
    private val later = t0 + 60_000

    private fun fetched(id: String, title: String = id, done: Boolean = false, derived: Boolean = false, dueAt: Long? = null) =
        Fetched(sourceId = id, title = title, kind = Kind.Homework, done = done, derived = derived, dueAt = dueAt)

    private fun first(vararg items: Fetched) = Merge.apply(emptyList(), Source.Teams, items.toList(), t0).tasks

    @Test
    fun `new items are added open, first seen now`() {
        val result = Merge.apply(emptyList(), Source.Teams, listOf(fetched("a"), fetched("b")), t0)
        assertEquals(listOf("teams:a", "teams:b"), result.tasks.map { it.id })
        assertTrue(result.tasks.all { it.isOpen && it.firstSeenAt == t0 && it.lastSeenAt == t0 })
        assertEquals(2, result.added.size)
    }

    @Test
    fun `an item already finished when first seen is not added`() {
        val result = Merge.apply(emptyList(), Source.PowerPlanner, listOf(fetched("a", done = true)), t0)
        assertTrue(result.tasks.isEmpty())
        assertTrue(result.completed.isEmpty())
    }

    @Test
    fun `an update keeps the app's own bookkeeping`() {
        val stored = first(fetched("a", dueAt = 5)).map {
            it.copy(workedMin = 25, userEffortMin = 60, subSteps = listOf(SubStep("Q1-8", 25, done = true)))
        }
        val result = Merge.apply(stored, Source.Teams, listOf(fetched("a", title = "Renamed", dueAt = 9)), later)
        val task = result.tasks.single()
        assertEquals("Renamed", task.title)
        assertEquals(9L, task.dueAt)
        assertEquals(25, task.workedMin)
        assertEquals(60, task.effortMin)
        assertEquals(listOf(SubStep("Q1-8", 25, done = true)), task.subSteps)
        assertEquals(t0, task.firstSeenAt)
        assertEquals(later, task.lastSeenAt)
    }

    @Test
    fun `an open task the source stops listing is done`() {
        val result = Merge.apply(first(fetched("a"), fetched("b")), Source.Teams, listOf(fetched("b")), later)
        val gone = result.tasks.single { it.sourceId == "a" }
        assertEquals(Status.Done, gone.status)
        assertEquals(later, gone.doneAt)
        assertEquals(listOf("teams:a"), result.completed.map { it.id })
    }

    @Test
    fun `a task listed as finished is done`() {
        val result = Merge.apply(first(fetched("a")), Source.Teams, listOf(fetched("a", done = true)), later)
        assertEquals(Status.Done, result.tasks.single().status)
        assertEquals(1, result.completed.size)
    }

    @Test
    fun `finished through its own progress, it's reported with the progress it had`() {
        val open = Merge.apply(emptyList(), Source.PowerPlanner, listOf(Fetched("p", "Essay", Kind.Homework, sourceProgress = 0.5)), t0).tasks
        val result = Merge.apply(open, Source.PowerPlanner, listOf(Fetched("p", "Essay", Kind.Homework, sourceProgress = 1.0, done = true)), t0 + 1)
        assertEquals(0.5, result.completed.single().sourceProgress)
        assertEquals(1.0, result.tasks.single().sourceProgress)
        assertEquals(Status.Done, result.tasks.single().status)
    }

    @Test
    fun `a done task listed again reopens, as a snoozed email does`() {
        val done = Merge.apply(first(fetched("a")), Source.Teams, emptyList(), later).tasks
        val result = Merge.apply(done, Source.Teams, listOf(fetched("a")), later + 1)
        assertEquals(Status.Open, result.tasks.single().status)
        assertEquals(null, result.tasks.single().doneAt)
        assertEquals(1, result.reopened.size)
    }

    @Test
    fun `a derived task that stops being listed was missed, not done`() {
        val stored = first(fetched("quota:2026-10-07", derived = true))
        val result = Merge.apply(stored, Source.Teams, listOf(fetched("quota:2026-10-08", derived = true)), later)
        assertEquals(Status.Missed, result.tasks.single { it.sourceId == "quota:2026-10-07" }.status)
        assertTrue(result.completed.isEmpty())
        assertEquals(1, result.missed.size)
    }

    @Test
    fun `a derived task once done stays done when its counts waver`() {
        val stored = Merge.apply(first(fetched("q", derived = true)), Source.Teams, listOf(fetched("q", derived = true, done = true)), later).tasks
        val result = Merge.apply(stored, Source.Teams, listOf(fetched("q", derived = true, done = false)), later + 1)
        assertEquals(Status.Done, result.tasks.single().status)
        assertTrue(result.reopened.isEmpty())
    }

    @Test
    fun `other sources' tasks are left alone`() {
        val gmail = Merge.apply(emptyList(), Source.Gmail, listOf(fetched("x")), t0).tasks
        val result = Merge.apply(gmail, Source.Teams, emptyList(), later)
        assertEquals(gmail, result.tasks)
    }

    @Test
    fun `finished tasks are forgotten two weeks on`() {
        val done = Merge.apply(first(fetched("a")), Source.Teams, emptyList(), later).tasks
        assertEquals(1, Merge.apply(done, Source.Teams, emptyList(), later + Merge.KEEP_FINISHED_MS - 1).tasks.size)
        assertTrue(Merge.apply(done, Source.Teams, emptyList(), later + Merge.KEEP_FINISHED_MS).tasks.isEmpty())
    }

    @Test
    fun `a repeated id counts once`() {
        val result = Merge.apply(emptyList(), Source.Teams, listOf(fetched("a", title = "one"), fetched("a", title = "two")), t0)
        assertEquals(listOf("one"), result.tasks.map { it.title })
    }
}
