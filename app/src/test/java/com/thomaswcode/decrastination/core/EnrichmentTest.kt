package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.enrich.ReadingPrompts
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentTest {
    private val t0 = Fixtures.at("2026-10-09T17:00")

    private fun email(detail: String = "Please sign the trip form.") = TaskItem(
        id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip to Berlin", detail = detail,
        kind = Kind.Admin, sourceEffortMin = 15, firstSeenAt = t0, lastSeenAt = t0,
        extra = mapOf(GmailThreads.EXTRA_TEXT_READ_TO to "${GmailThreads.MAX_BODY_CHARS}", GmailThreads.EXTRA_TEXT_RULES to "${GmailThreads.TEXT_RULES}"),
    )

    private fun assignment(dueAt: Long? = Fixtures.at("2026-10-16T08:30")) = TaskItem(
        id = "teams:a1", source = Source.Teams, sourceId = "a1", title = "Prep", detail = "Pages 3-5 of the booklet.",
        dueAt = dueAt, kind = Kind.Homework, firstSeenAt = t0, lastSeenAt = t0,
    )

    /** A reading of [task] as it is, by the prompts as they are. */
    private fun enrichment(task: TaskItem, by: String = "claude-opus-5-5") = Enrichment(Enrichments.inputHash(task), by, t0, promptVersion = com.thomaswcode.decrastination.enrich.ReadingPrompts.VERSION)

    @Test
    fun `an email is a task whatever its reading's kind, and takes its deadline where the source has none`() {
        val task = email()
        val deadline = Fixtures.at("2026-10-12T09:00")
        // An older reading's Event is a task too (16 Oct): every starred email is one.
        val enriched = task.copy(enrichment = enrichment(task).copy(kind = Kind.Event, deadline = deadline, effortMin = 5)).enriched()
        assertEquals(Kind.Admin, enriched.kind)
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
        val stored = task.copy(enrichment = enrichment(task).copy(kind = Kind.Admin, effortMin = 2)).enriched()
        val fetched = Fetched("t1", "Trip to Berlin", Kind.Admin, detail = task.detail, sourceEffortMin = 15)
        val merged = Merge.apply(listOf(stored), Source.Gmail, listOf(fetched), t0 + 60_000L).tasks.single()
        assertEquals(Kind.Admin, merged.kind)
        assertEquals(2, merged.effortMin)
        assertEquals(stored.enrichment, merged.enrichment)
    }

    @Test
    fun `an email's attachments are part of what its reading is of`() {
        // Named once the conversation is read again (DX-01): a reading from before them is out of date.
        val task = email()
        val withFiles = task.copy(extra = task.extra + (GmailThreads.EXTRA_ATTACHMENTS to """[{"name":"a.pdf","type":"application/pdf"}]"""))
        assertNotEquals(Enrichments.inputHash(task), Enrichments.inputHash(withFiles))
        assertNull(Enrichments.current(withFiles.copy(enrichment = enrichment(task))))
    }

    @Test
    fun `an email read as only to read before starred-only reading isn't hidden, and is read again`() {
        // Starred, it's always something to do (AI-02): the old Info reading gives way to the rules' kind.
        val task = email()
        val old = task.copy(enrichment = enrichment(task).copy(kind = Kind.Info, effortMin = 2)).enriched()
        assertEquals(Kind.Admin, old.kind)
        assertFalse(old.hidden)
        assertTrue(Enrichments.stale(old, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        // A reading that says Admin isn't read again for that.
        assertFalse(Enrichments.stale(task.copy(enrichment = enrichment(task).copy(kind = Kind.Admin)), modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
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
        val later = task.withEnrichment(enrichment(task).copy(kind = Kind.Admin, effortMin = 3, actionableFrom = Fixtures.at("2026-10-20T00:00")))
        assertEquals(3, later.effortMin)
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
        assertEquals(false, Enrichments.stale(enriched, modelOn = false, rules = "rules", version = ReadingPrompts.VERSION))
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
        assertFalse(Enrichments.stale(done, modelOn = false, rules = "rules", version = ReadingPrompts.VERSION))
        assertTrue(Enrichments.stale(done, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        val changed = done.copy(detail = "Please sign the trip form by Monday.")
        assertNotEquals(Enrichments.inputHash(done), Enrichments.inputHash(changed))
        assertTrue(Enrichments.stale(changed, modelOn = false, rules = "rules", version = ReadingPrompts.VERSION))
    }

    @Test
    fun `a reading by older prompts is read again while the model's on, oldest and never read first`() {
        val task = email()
        // Stored before there was a version: the first.
        val old = task.copy(enrichment = Enrichment(Enrichments.inputHash(task), "claude-opus-5-5", t0))
        assertEquals(1, old.enrichment!!.promptVersion)
        assertTrue(Enrichments.stale(old, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        // Resting, or the task done: it stands. The rules' readings have no prompts to be out of date by.
        assertFalse(Enrichments.stale(old, modelOn = false, rules = "rules", version = ReadingPrompts.VERSION))
        assertFalse(Enrichments.stale(old.copy(status = Status.Done), modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        val now = task.copy(enrichment = enrichment(task).copy(promptVersion = com.thomaswcode.decrastination.enrich.ReadingPrompts.VERSION))
        assertFalse(Enrichments.stale(now, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        assertEquals(com.thomaswcode.decrastination.enrich.ReadingPrompts.VERSION, com.thomaswcode.decrastination.enrich.ReadingAnswers.parse(Enrichments.Job.Email, """{"kind":"Admin","actionableFrom":null,"deadline":null,"deadlineQuoted":false,"effortMin":10,"nextStep":"Reply","question":null,"reason":"","blocks":[]}""", task, "claude", t0, Fixtures.LONDON)?.promptVersion)
        val never = task.copy(id = "gmail:never", enrichment = null, dueAt = t0 + 5)
        val dueFirst = now.copy(id = "gmail:due", dueAt = t0)
        val oldLater = old.copy(id = "gmail:old", dueAt = t0 + 10)
        assertEquals(listOf("gmail:never", "gmail:old", "gmail:due"), listOf(dueFirst, oldLater, never).sortedWith(Enrichments.NEED).map { it.id })
    }

    @Test
    fun `what the model read is stale once Claude is switched off, the rules' isn't`() {
        val task = email()
        assertTrue(Enrichments.stale(task.copy(enrichment = enrichment(task)), modelOn = false, rules = "rules", modelOff = true, version = ReadingPrompts.VERSION))
        assertFalse(Enrichments.stale(task.copy(enrichment = enrichment(task, by = "rules")), modelOn = false, rules = "rules", modelOff = true, version = ReadingPrompts.VERSION))
        // Only resting (a failed call, the cap): what it read stands.
        assertFalse(Enrichments.stale(task.copy(enrichment = enrichment(task)), modelOn = false, rules = "rules", modelOff = false, version = ReadingPrompts.VERSION))
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
    fun `a task's enrichment forgotten takes the steps it gave, not the source's`() {
        val task = email()
        val given = task.withEnrichment(enrichment(task).copy(subSteps = listOf(SubStep("Apply", 30))))
        assertEquals(listOf("Apply"), given.subSteps.map { it.title })
        val forgotten = given.withoutEnrichment()
        assertNull(forgotten.enrichment)
        assertEquals(emptyList(), forgotten.subSteps)
        // The source's own steps stay.
        val own = task.copy(subSteps = listOf(SubStep("Read", 5))).copy(enrichment = enrichment(task))
        assertEquals(listOf("Read"), own.withoutEnrichment().subSteps.map { it.title })
    }

    @Test
    fun `what's enriched is emails, assignments and planner items, not done, derived or events`() {
        assertEquals(Enrichments.Job.Email, Enrichments.jobFor(email(), GmailThreads::textRead))
        // Its text still to be read, or read only as far as an older, lower limit went: it waits.
        assertEquals(null, Enrichments.jobFor(email().copy(extra = mapOf("textPending" to "true")), GmailThreads::textRead))
        assertEquals(null, Enrichments.jobFor(email("x".repeat(4_000)).copy(extra = emptyMap()), GmailThreads::textRead))
        assertEquals(Enrichments.Job.Assignment, Enrichments.jobFor(assignment(), GmailThreads::textRead))
        val item = assignment().copy(source = Source.PowerPlanner, id = "powerplanner:p")
        assertEquals(Enrichments.Job.Effort, Enrichments.jobFor(item, GmailThreads::textRead))
        assertEquals(null, Enrichments.jobFor(item.copy(kind = Kind.Event), GmailThreads::textRead))
        assertEquals(null, Enrichments.jobFor(assignment().copy(status = Status.Done), GmailThreads::textRead))
        assertEquals(null, Enrichments.jobFor(assignment().copy(source = Source.Anki, derived = true), GmailThreads::textRead))
    }

    @Test
    fun `a reading by older prompts with some of its steps done isn't read again for them alone`() {
        val task = assignment()
        val read = Enrichment(Enrichments.inputHash(task), "claude-opus-5-5", t0, effortMin = 50, subSteps = listOf(SubStep("Page 7-9", 30), SubStep("Page 10-11", 20)), promptVersion = 1)
        val given = task.withEnrichment(read)
        assertEquals(listOf("Page 7-9", "Page 10-11"), given.subSteps.map { it.title })
        assertTrue(Enrichments.stale(given, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        // One done: newer prompts could give its work as other steps, and its mark would go with it.
        val partDone = given.copy(subSteps = given.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        assertFalse(Enrichments.stale(partDone, modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
        assertFalse(Enrichments.staleOnlyByVersion(partDone, "rules", ReadingPrompts.VERSION))
        // Its text changed, it's read again, as any task is.
        assertTrue(Enrichments.stale(partDone.copy(detail = "Page 7-12 of booklet."), modelOn = true, rules = "rules", version = ReadingPrompts.VERSION))
    }

    @Test
    fun `a model's reading of the text as it is, by older prompts, is stale only by its version`() {
        val task = assignment()
        val read = com.thomaswcode.decrastination.core.Enrichment(Enrichments.inputHash(task), "claude-opus-5-5", 0, effortMin = 40, promptVersion = 1)
        assertTrue(Enrichments.staleOnlyByVersion(task.copy(enrichment = read), "rules", ReadingPrompts.VERSION))
        // By these prompts, by the rules, or of text since changed: not so.
        assertFalse(Enrichments.staleOnlyByVersion(task.copy(enrichment = read.copy(promptVersion = com.thomaswcode.decrastination.enrich.ReadingPrompts.VERSION)), "rules", ReadingPrompts.VERSION))
        assertFalse(Enrichments.staleOnlyByVersion(task.copy(enrichment = read.copy(by = "rules")), "rules", ReadingPrompts.VERSION))
        assertFalse(Enrichments.staleOnlyByVersion(task.copy(enrichment = read.copy(inputHash = "older")), "rules", ReadingPrompts.VERSION))
    }
}
