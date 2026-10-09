package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
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
        extra = mapOf(GmailThreads.EXTRA_TEXT_READ_TO to "${GmailThreads.MAX_BODY_CHARS}"),
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
    fun `a fresh enrichment without an estimate leaves none of the last one's`() {
        val task = email().copy(sourceValues = SourceValues(Kind.Admin, null, null))
        val estimated = task.withEnrichment(enrichment(task).copy(effortMin = 45))
        assertEquals(45, estimated.aiEffortMin)
        assertEquals(null, estimated.withEnrichment(enrichment(task).copy(effortMin = null)).aiEffortMin)
    }

    @Test
    fun `a step done is kept done by occurrence, not by title`() {
        val task = assignment()
        val first = task.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Exercise", 20), SubStep("Exercise", 20))))
        val oneDone = first.copy(subSteps = first.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val again = oneDone.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Exercise", 25), SubStep("Exercise", 25))))
        assertEquals(listOf(true, false), again.subSteps.map { it.done })
    }

    @Test
    fun `an enrichment of content that has since changed isn't laid over it`() {
        val task = email().copy(sourceValues = SourceValues(Kind.Admin, null, null))
        val later = task.withEnrichment(enrichment(task).copy(kind = Kind.Event, actionableFrom = Fixtures.at("2026-10-20T00:00")))
        assertEquals(Kind.Event, later.kind)
        // A new message in the thread: the source's say until it's enriched again.
        val changed = later.copy(detail = "Urgent: sign it today.").enriched()
        assertEquals(Kind.Admin, changed.kind)
        assertEquals(null, changed.availableFrom)
    }

    @Test
    fun `an email's deadline is the enrichment's reading, not the rules' guess`() {
        // The rules took "Appointment on 20 October" for its date; it asks for a reply today.
        val task = email().copy(sourceValues = SourceValues(Kind.Event, Fixtures.at("2026-10-20T00:00"), null))
        val read = task.withEnrichment(enrichment(task).copy(kind = Kind.Admin, deadline = Fixtures.at("2026-10-09T20:00")))
        assertEquals(Fixtures.at("2026-10-09T20:00"), read.dueAt)
    }

    @Test
    fun `an email the model has read takes its start, not the rules' guess`() {
        // The rules took "Appointment next week" for an event, available the day before it.
        val task = email().copy(sourceValues = SourceValues(Kind.Event, Fixtures.at("2026-10-16T09:00"), Fixtures.at("2026-10-15T00:00")))
        val read = task.withEnrichment(enrichment(task).copy(kind = Kind.Admin, deadline = Fixtures.at("2026-10-09T20:00")))
        assertEquals(null, read.availableFrom)
        // The rules' own enrichment (no kind of its own) leaves the rules' start.
        val rules = task.withEnrichment(enrichment(task, by = "rules"))
        assertEquals(Fixtures.at("2026-10-15T00:00"), rules.availableFrom)
    }

    @Test
    fun `a start after the deadline is dropped`() {
        val task = email().copy(sourceValues = SourceValues(Kind.Admin, null, null))
        val late = task.withEnrichment(enrichment(task).copy(deadline = Fixtures.at("2026-10-12T09:00"), actionableFrom = Fixtures.at("2026-10-14T00:00")))
        assertEquals(null, late.availableFrom)
        val fine = task.withEnrichment(enrichment(task).copy(deadline = Fixtures.at("2026-10-12T09:00"), actionableFrom = Fixtures.at("2026-10-11T00:00")))
        assertEquals(Fixtures.at("2026-10-11T00:00"), fine.availableFrom)
    }

    @Test
    fun `the hash reads the source's deadline, so the enrichment's own doesn't make it stale`() {
        val task = email().copy(sourceValues = SourceValues(Kind.Admin, null, null))
        val made = enrichment(task).copy(deadline = Fixtures.at("2026-10-12T09:00"))
        val enriched = task.withEnrichment(made)
        assertEquals(Fixtures.at("2026-10-12T09:00"), enriched.dueAt)
        assertEquals(false, Enrichments.stale(enriched, modelOn = false, rules = "rules"))
    }

    @Test
    fun `a new enrichment is laid over what the source said, not over the last one`() {
        val task = email().copy(sourceValues = SourceValues(Kind.Admin, null, null))
        val dated = task.withEnrichment(enrichment(task).copy(deadline = Fixtures.at("2026-10-12T09:00"), kind = Kind.Event))
        assertEquals(Fixtures.at("2026-10-12T09:00"), dated.dueAt)
        // The email changed: the new enrichment finds no deadline, and the old one's is gone.
        val undated = dated.withEnrichment(enrichment(task).copy(kind = Kind.Admin))
        assertEquals(null, undated.dueAt)
        assertEquals(Kind.Admin, undated.kind)
        // A test moved later than the due date no longer brings it forward.
        val assignment = assignment().copy(sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-16T08:30"), null))
        val early = assignment.withEnrichment(enrichment(assignment).copy(testDate = Fixtures.at("2026-10-12T08:30")))
        val later = early.withEnrichment(enrichment(assignment).copy(testDate = Fixtures.at("2026-10-20T08:30")))
        assertEquals(Fixtures.at("2026-10-16T08:30"), later.dueAt)
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
    fun `what the model read is stale once Claude is switched off, the rules' isn't`() {
        val task = email()
        assertTrue(Enrichments.stale(task.copy(enrichment = enrichment(task)), modelOn = false, rules = "rules", modelOff = true))
        assertFalse(Enrichments.stale(task.copy(enrichment = enrichment(task, by = "rules")), modelOn = false, rules = "rules", modelOff = true))
        // Only resting (a failed call, the cap): what it read stands.
        assertFalse(Enrichments.stale(task.copy(enrichment = enrichment(task)), modelOn = false, rules = "rules", modelOff = false))
    }

    @Test
    fun `steps an enrichment gave go when the content changes, the source's own stay`() {
        val task = assignment()
        val steps = listOf(SubStep("Pages 7-9", 30, done = true), SubStep("Pages 10-11", 20))
        val split = task.copy(subSteps = steps, enrichment = enrichment(task).copy(subSteps = steps.map { it.copy(done = false) }))
        assertEquals(steps, split.enriched().subSteps)
        // New instructions: the task's own estimate until it's split again.
        assertEquals(emptyList(), split.copy(detail = "Pages 7-15 of booklet.").enriched().subSteps)
        val own = listOf(SubStep("20 new cards", 9))
        val counted = task.copy(subSteps = own, enrichment = enrichment(task))
        assertEquals(own, counted.copy(detail = "Changed.").enriched().subSteps)
    }

    @Test
    fun `a block ticked by hand stays so through a fresh enrichment, at its minutes now`() {
        val task = email()
        val first = listOf(SubStep("Reply", 20))
        val ticked = task.copy(subSteps = listOf(SubStep("Reply", 20, done = true, byHand = true)), enrichment = enrichment(task).copy(subSteps = first))
        val again = ticked.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Reply", 25))))
        assertEquals(listOf(true), again.subSteps.map { it.byHand })
        assertEquals(25, again.handMin)
    }

    @Test
    fun `of two blocks of one name, a fresh enrichment keeps the mark on the one it was on`() {
        val task = email()
        val steps = listOf(SubStep("Apply", 10), SubStep("Apply", 60))
        val ticked = task.copy(subSteps = listOf(SubStep("Apply", 10), SubStep("Apply", 60, done = true, byHand = true)), enrichment = enrichment(task).copy(subSteps = steps))
        val again = ticked.withEnrichment(enrichment(task).copy(subSteps = steps))
        assertEquals(listOf(false, true), again.subSteps.map { it.done })
        assertEquals(60, again.handMin)
    }

    @Test
    fun `what's enriched is emails, assignments and planner items, not done, derived or events`() {
        assertEquals(Enrichments.Job.Email, Enrichments.jobFor(email()))
        // Its text still to be read, or read only as far as an older, lower limit went: it waits.
        assertEquals(null, Enrichments.jobFor(email().copy(extra = mapOf("textPending" to "true"))))
        assertEquals(null, Enrichments.jobFor(email("x".repeat(4_000)).copy(extra = emptyMap())))
        assertEquals(Enrichments.Job.Assignment, Enrichments.jobFor(assignment()))
        val item = assignment().copy(source = Source.PowerPlanner, id = "powerplanner:p")
        assertEquals(Enrichments.Job.Effort, Enrichments.jobFor(item))
        assertEquals(null, Enrichments.jobFor(item.copy(kind = Kind.Event)))
        assertEquals(null, Enrichments.jobFor(assignment().copy(status = Status.Done)))
        assertEquals(null, Enrichments.jobFor(assignment().copy(source = Source.Anki, derived = true)))
    }
}
