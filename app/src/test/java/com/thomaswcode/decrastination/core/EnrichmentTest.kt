package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnrichmentTest {
    private val t0 = Fixtures.at("2026-10-09T17:00")

    private fun email(detail: String = "Please sign the trip form.") = TaskItem(
        id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip to Berlin", detail = detail,
        kind = Kind.Admin, sourceEffortMin = 15, firstSeenAt = t0, lastSeenAt = t0,
    )

    private fun assignment(dueAt: Long? = Fixtures.at("2026-10-16T08:30")) = TaskItem(
        id = "teams:a1", source = Source.Teams, sourceId = "a1", title = "Prep", detail = "Page 7-11 of booklet.",
        dueAt = dueAt, kind = Kind.Homework, firstSeenAt = t0, lastSeenAt = t0,
    )

    private fun enrichment(task: TaskItem, by: String = "claude-opus-5-5") = Enrichment(Enrichments.inputHash(task), by, t0)

    @Test
    fun `an email takes the enrichment's kind, and its deadline where the source has none`() {
        val task = email()
        val deadline = Fixtures.at("2026-10-12T09:00")
        val enriched = task.copy(enrichment = enrichment(task).copy(kind = Kind.Event, deadline = deadline, effortMin = 5)).enriched()
        assertEquals(Kind.Event, enriched.kind)
        assertEquals(deadline, enriched.dueAt)
        assertEquals(5, enriched.effortMin)
    }

    @Test
    fun `the source's deadline wins, but a test before it brings it forward`() {
        val task = assignment()
        val later = Fixtures.at("2026-10-20T09:00")
        assertEquals(task.dueAt, task.copy(enrichment = enrichment(task).copy(deadline = later)).enriched().dueAt)
        val test = Fixtures.at("2026-10-12T08:30")
        assertEquals(test, task.copy(enrichment = enrichment(task).copy(testDate = test)).enriched().dueAt)
        // An assignment's kind is the source's.
        assertEquals(Kind.Homework, task.copy(enrichment = enrichment(task).copy(kind = Kind.Info)).enriched().kind)
    }

    @Test
    fun `the later start counts, and the steps fill in only where the source gives none`() {
        val task = assignment()
        val opens = Fixtures.at("2026-10-12T00:00")
        val steps = listOf(SubStep("Pages 7-9", 30), SubStep("Pages 10-11", 20))
        val enriched = task.copy(enrichment = enrichment(task).copy(actionableFrom = opens, subSteps = steps)).enriched()
        assertEquals(opens, enriched.availableFrom)
        assertEquals(steps, enriched.subSteps)
        val own = listOf(SubStep("20 new cards", 9))
        assertEquals(own, task.copy(subSteps = own, enrichment = enrichment(task).copy(subSteps = steps)).enriched().subSteps)
    }

    @Test
    fun `a sync keeps the enrichment and lays it over the fresh values`() {
        val task = email()
        val stored = task.copy(enrichment = enrichment(task).copy(kind = Kind.Info, effortMin = 2)).enriched()
        val fetched = Fetched("t1", "Trip to Berlin", Kind.Admin, detail = task.detail, sourceEffortMin = 15)
        val merged = Merge.apply(listOf(stored), Source.Gmail, listOf(fetched), t0 + 60_000L).tasks.single()
        assertEquals(Kind.Info, merged.kind)
        assertEquals(2, merged.effortMin)
        assertEquals(stored.enrichment, merged.enrichment)
    }

    @Test
    fun `a fresh enrichment replaces the steps the last one gave, keeping what's done`() {
        val task = assignment()
        val first = task.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Pages 7-9", 30), SubStep("Pages 10-11", 20))))
        val worked = first.copy(subSteps = first.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val second = worked.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Pages 7-9", 25), SubStep("Pages 10-11", 25), SubStep("Check and hand in", 5))))
        assertEquals(listOf("Pages 7-9", "Pages 10-11", "Check and hand in"), second.subSteps.map { it.title })
        assertEquals(listOf(true, false, false), second.subSteps.map { it.done })
    }

    @Test
    fun `a task changed since it was enriched is stale, and so is one only the rules saw once the model is on`() {
        val task = email()
        val done = task.copy(enrichment = enrichment(task, by = "rules"))
        assertFalse(Enrichments.stale(done, modelOn = false, rules = "rules"))
        assertTrue(Enrichments.stale(done, modelOn = true, rules = "rules"))
        val changed = done.copy(detail = "Please sign the trip form by Monday.")
        assertNotEquals(Enrichments.inputHash(done), Enrichments.inputHash(changed))
        assertTrue(Enrichments.stale(changed, modelOn = false, rules = "rules"))
    }

    @Test
    fun `what's enriched is emails, assignments and planner items, not done, derived or events`() {
        assertEquals(Enrichments.Job.Email, Enrichments.jobFor(email()))
        assertEquals(Enrichments.Job.Assignment, Enrichments.jobFor(assignment()))
        val item = assignment().copy(source = Source.PowerPlanner, id = "powerplanner:p")
        assertEquals(Enrichments.Job.Effort, Enrichments.jobFor(item))
        assertEquals(null, Enrichments.jobFor(item.copy(kind = Kind.Event)))
        assertEquals(null, Enrichments.jobFor(assignment().copy(status = Status.Done)))
        assertEquals(null, Enrichments.jobFor(assignment().copy(source = Source.Anki, derived = true)))
    }
}
