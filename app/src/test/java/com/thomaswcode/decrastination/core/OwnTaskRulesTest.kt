package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.ui.DueGroup
import com.thomaswcode.decrastination.ui.Groups
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnTaskRulesTest {
    // Monday 12 Oct 2026, 17:00.
    private val now = Fixtures.at("2026-10-12T17:00")

    private fun own(title: String = "UCAS statement", due: String? = "2026-10-16T17:00", minutes: Int = 90, steps: List<OwnStep> = emptyList(), from: String? = null) =
        OwnTaskRules.create(OwnTask(title = title, dueAt = due?.let(Fixtures::at), startFrom = from?.let(Fixtures::at), minutes = minutes, steps = steps), "u1", now)

    private fun teams(id: String, due: String?, effort: Int = 60, steps: List<SubStep> = emptyList()) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = due?.let(Fixtures::at), kind = Kind.Homework,
        sourceEffortMin = effort, subSteps = steps, firstSeenAt = now, lastSeenAt = now,
        sourceValues = SourceValues(Kind.Homework, due?.let(Fixtures::at), null),
    )

    private fun applied(vararg changes: Change) = Instruction("i", "words", at = now, state = InstructionStatus.Applied, changes = changes.toList(), appliedAt = now)

    private fun plan(tasks: List<TaskItem>, calibration: Calibration = Calibration()) = Planner.plan(Planner.Input(tasks, now, LONDON, Settings(), calibration = calibration))

    // --- Your own tasks ---

    @Test
    fun `one of your own is made as a source's task would be, with you as its source`() {
        val task = own()
        assertEquals("own:u1", task.id)
        assertEquals(Source.Own, task.source)
        assertEquals(Fixtures.at("2026-10-16T17:00"), task.dueAt)
        assertEquals(90, task.effortMin)
        // What an instruction is laid over, and comes back to.
        assertEquals(SourceValues(Kind.Homework, Fixtures.at("2026-10-16T17:00"), null, "UCAS statement"), task.sourceValues)
        assertTrue(task.isOpen)
        // Your minutes are planned as you gave them.
        assertTrue(task.yourMinutes)
    }

    @Test
    fun `with steps, its minutes are theirs together, and an event takes none`() {
        val stepped = own(steps = listOf(OwnStep("Draft", 60), OwnStep("Redraft", 45)))
        assertEquals(105, stepped.effortMin)
        assertEquals(listOf("Draft", "Redraft"), stepped.subSteps.map { it.title })
        val event = OwnTaskRules.create(OwnTask(title = "Dentist", kind = Kind.Event, dueAt = Fixtures.at("2026-10-14T16:00"), minutes = 30), "e1", now)
        assertEquals(0, event.effortMin)
    }

    @Test
    fun `what the form won't take is said`() {
        assertEquals("It needs a name", OwnTaskRules.problem(OwnTask(title = " ")))
        assertEquals("An event needs its date and time", OwnTaskRules.problem(OwnTask(title = "Party", kind = Kind.Event)))
        assertTrue(OwnTaskRules.problem(OwnTask(title = "x", minutes = 0))!!.startsWith("Its minutes"))
        assertEquals("It can't start after it's due", OwnTaskRules.problem(OwnTask(title = "x", dueAt = now, startFrom = now + 1)))
        assertNull(OwnTaskRules.problem(OwnTask(title = "x", minutes = 45)))
    }

    @Test
    fun `changed in the form, it keeps the work done on it and its steps done`() {
        val stepped = own(steps = listOf(OwnStep("Draft", 60), OwnStep("Redraft", 45)))
        val worked = stepped.copy(workedMin = 60, subSteps = stepped.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val edited = OwnTaskRules.edited(worked, OwnTaskRules.draftOf(worked).copy(title = "Personal statement", dueAt = Fixtures.at("2026-10-20T17:00")))
        assertEquals("Personal statement", edited.title)
        assertEquals(Fixtures.at("2026-10-20T17:00"), edited.dueAt)
        assertEquals(60, edited.workedMin)
        assertEquals(listOf(true, false), edited.subSteps.map { it.done })
        assertEquals(now, edited.firstSeenAt)
    }

    @Test
    fun `an instruction's date, start or minutes is made on one of your own, minutes scaling its steps`() {
        val stepped = own(steps = listOf(OwnStep("Draft", 60), OwnStep("Redraft", 30)))
        val due = OwnTaskRules.changed(stepped, Change(ChangeType.DueBy, taskId = stepped.id, time = Fixtures.at("2026-10-18T12:00")))
        assertEquals(Fixtures.at("2026-10-18T12:00"), due.dueAt)
        assertEquals(Fixtures.at("2026-10-18T12:00"), due.sourceValues?.dueAt)
        val longer = OwnTaskRules.changed(stepped, Change(ChangeType.Minutes, taskId = stepped.id, minutes = 180))
        assertEquals(listOf(120, 60), longer.subSteps.map { it.minutes })
        assertEquals(180, longer.effortMin)
        // Minutes that don't divide evenly still come to exactly yours.
        val three = own(steps = listOf(OwnStep("a", 10), OwnStep("b", 10), OwnStep("c", 10)))
        assertEquals(100, OwnTaskRules.changed(three, Change(ChangeType.Minutes, taskId = three.id, minutes = 100)).effortMin)
        val started = OwnTaskRules.changed(stepped, Change(ChangeType.StartFrom, taskId = stepped.id, time = Fixtures.at("2026-10-14T00:00")))
        assertEquals(Fixtures.at("2026-10-14T00:00"), started.notBefore)
        // Starting after it's due isn't made: the task is as it was.
        assertSame(stepped, OwnTaskRules.changed(stepped, Change(ChangeType.StartFrom, taskId = stepped.id, time = Fixtures.at("2026-10-30T00:00"))))
    }

    @Test
    fun `one of your own that can't start yet is planned from then, not hidden`() {
        val task = own(due = "2026-10-20T17:00", from = "2026-10-15T00:00")
        val plan = plan(listOf(task))
        val days = plan.buckets.filter { b -> b.chunks.any { it.taskId == task.id } }.map { it.date }
        assertTrue(days.isNotEmpty())
        assertTrue(days.all { it >= LocalDate.parse("2026-10-15") })
    }

    @Test
    fun `one of your own whose steps are all done leaves nothing to plan`() {
        val stepped = own(steps = listOf(OwnStep("Draft", 60)))
        val done = stepped.copy(subSteps = stepped.subSteps.map { it.copy(done = true) })
        assertTrue(plan(listOf(done)).ordered.none { it.taskId == done.id })
    }

    @Test
    fun `undoing a card puts back only what it changed`() {
        val was = OwnTask(title = "Essay", dueAt = Fixtures.at("2026-10-16T17:00"), minutes = 60)
        // Since the card moved the due date, another changed the minutes.
        val moved = Change(ChangeType.DueBy, taskId = "own:u1", time = Fixtures.at("2026-10-20T17:00"))
        val now = was.copy(dueAt = Fixtures.at("2026-10-20T17:00"), minutes = 90)
        val back = OwnTaskRules.restored(now, was, listOf(moved))
        assertEquals(Fixtures.at("2026-10-16T17:00"), back.dueAt)
        assertEquals(90, back.minutes)
        // Moved again since, by you: your date stands.
        val again = now.copy(dueAt = Fixtures.at("2026-10-21T17:00"))
        assertEquals(Fixtures.at("2026-10-21T17:00"), OwnTaskRules.restored(again, was, listOf(moved)).dueAt)
    }

    @Test
    fun `a split undone puts back the steps it replaced, unless they've been changed since`() {
        val was = OwnTask(title = "Essay", minutes = 90, steps = listOf(OwnStep("Write it", 90)))
        val split = Change(ChangeType.Steps, taskId = "own:u1", steps = listOf(OwnStep("Plan", 15), OwnStep("Draft", 60), OwnStep("Check", 15)))
        val now = was.copy(minutes = 90, steps = split.steps!!)
        assertEquals(was.steps, OwnTaskRules.restored(now, was, listOf(split)).steps)
        val since = now.copy(steps = listOf(OwnStep("Plan", 20), OwnStep("Draft", 70)), minutes = 90)
        assertEquals(since.steps, OwnTaskRules.restored(since, was, listOf(split)).steps)
        // A later card that changed only a step's mark (D-109) is a change since too: undoing the split leaves it.
        val marked = now.copy(steps = now.steps.mapIndexed { i, s -> if (i == 2) s.copy(anyOrder = true) else s })
        assertEquals(marked.steps, OwnTaskRules.restored(marked, was, listOf(split)).steps)
        // A later card that replaced the split: undoing the split first leaves it what the split replaced.
        assertEquals(was.steps, OwnTaskRules.relayered(now, listOf(split), was).steps)
    }

    @Test
    fun `undoing two cards in the opposite order leaves neither's value`() {
        val friday = OwnTask(title = "Essay", dueAt = Fixtures.at("2026-10-16T17:00"))
        val toSunday = Change(ChangeType.DueBy, taskId = "own:u1", time = Fixtures.at("2026-10-18T17:00"))
        val toMonday = Change(ChangeType.DueBy, taskId = "own:u1", time = Fixtures.at("2026-10-19T17:00"))
        val sunday = friday.copy(dueAt = toSunday.time)
        val monday = friday.copy(dueAt = toMonday.time)
        // Card A (Friday to Sunday) undone first: Monday stands, and B now replaced Friday.
        assertEquals(monday, OwnTaskRules.restored(monday, friday, listOf(toSunday)))
        val bWas = OwnTaskRules.relayered(sunday, listOf(toSunday), friday)
        assertEquals(friday.dueAt, bWas.dueAt)
        // Then card B: back to Friday, not A's Sunday.
        assertEquals(friday.dueAt, OwnTaskRules.restored(monday, bWas, listOf(toMonday)).dueAt)
    }

    @Test
    fun `fewer minutes than one of yours has steps is refused, saying why`() {
        val three = own(steps = listOf(OwnStep("a", 10), OwnStep("b", 10), OwnStep("c", 10)))
        val two = listOf(Change(ChangeType.Minutes, taskId = three.id, minutes = 2))
        assertEquals("It would leave “UCAS statement” as it can't be: its 3 steps need at least a minute each", OwnTaskRules.problemWith(listOf(three), two))
        assertEquals(emptyList(), OwnTaskRules.madeOn(listOf(three), two))
        assertSame(three, OwnTaskRules.changed(three, two.single()))
    }

    @Test
    fun `in the form, a renamed step keeps its tick, a new one of an old name has none, and where it's done goes on it`() {
        val task = own(steps = listOf(OwnStep("Draft", 60), OwnStep("Redraft", 45)))
        val firstDone = task.copy(subSteps = task.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        // The first renamed, the second needing home, and a new "Draft" after them.
        val draft = OwnTaskRules.draftOf(firstDone).let { d -> d.copy(steps = listOf(d.steps[0].copy(title = "First draft"), d.steps[1].copy(where = "home"), OwnStep("Draft", 20))) }
        val edited = OwnTaskRules.edited(firstDone, draft)
        assertEquals(listOf("First draft" to true, "Redraft" to false, "Draft" to false), edited.subSteps.map { it.title to it.done })
        assertEquals(listOf(null, "home", null), edited.subSteps.map { it.where })
        assertEquals(125, edited.sourceEffortMin)
    }

    @Test
    fun `steps of the same name keep their own ticks through an edit`() {
        val task = own(steps = listOf(OwnStep("Review", 20), OwnStep("Write", 30), OwnStep("Review", 20)))
        val firstDone = task.copy(subSteps = task.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val edited = OwnTaskRules.edited(firstDone, OwnTaskRules.draftOf(firstDone).copy(title = "Essay"))
        assertEquals(listOf(true, false, false), edited.subSteps.map { it.done })
    }

    @Test
    fun `one of yours with every step ticked opens again with its last step to do`() {
        val stepped = own(steps = listOf(OwnStep("Draft", 60), OwnStep("Redraft", 30)))
        val done = stepped.copy(status = Status.Done, doneAt = now, workedMin = 70, subSteps = stepped.subSteps.map { it.copy(done = true, byHand = true) })
        val again = OwnTaskRules.reopened(done)
        assertTrue(again.isOpen)
        assertEquals(listOf(true, false), again.subSteps.map { it.done })
        assertEquals(70, again.workedMin)
        // One without steps is just open again.
        assertEquals(Status.Open, OwnTaskRules.reopened(own().copy(status = Status.Done, doneAt = now)).status)
    }

    @Test
    fun `an instruction's changes to one of yours are made together, or not at all with why`() {
        val task = own(due = "2026-10-16T17:00")
        // A start and a due date both later: the start alone would be after the old due date.
        val later = listOf(
            Change(ChangeType.StartFrom, taskId = task.id, time = Fixtures.at("2026-10-20T00:00")),
            Change(ChangeType.DueBy, taskId = task.id, time = Fixtures.at("2026-10-25T17:00")),
        )
        assertNull(OwnTaskRules.problemWith(listOf(task), later))
        assertEquals(later, OwnTaskRules.madeOn(listOf(task), later))
        val made = OwnTaskRules.edited(task, OwnTaskRules.draftWithAll(task, later)!!)
        assertEquals(Fixtures.at("2026-10-20T00:00"), made.notBefore)
        assertEquals(Fixtures.at("2026-10-25T17:00"), made.dueAt)
        // A start after its due date isn't, and says why; another task's changes aren't touched.
        val after = listOf(Change(ChangeType.StartFrom, taskId = task.id, time = Fixtures.at("2026-10-20T00:00")))
        assertEquals("It would leave “UCAS statement” as it can't be: it can't start after it's due", OwnTaskRules.problemWith(listOf(task), after))
        assertEquals(emptyList(), OwnTaskRules.madeOn(listOf(task), after))
        assertNull(OwnTaskRules.problemWith(listOf(task), listOf(Change(ChangeType.DueBy, taskId = "teams:essay", time = now))))
    }

    @Test
    fun `undo is offered only where your quick change is what's in force`() {
        val quick = Instruction("q", "Takes 1 h in all (set in Tasks)", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 60)), appliedAt = now, quick = true)
        val claudes = Instruction("c", "it takes 2 hours", at = now, state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 120)), appliedAt = now + 1)
        assertTrue(InstructionRules.quickInForce(listOf(quick), "teams:essay", ChangeType.Minutes))
        assertFalse(InstructionRules.quickInForce(listOf(quick, claudes), "teams:essay", ChangeType.Minutes))
        assertFalse(InstructionRules.quickInForce(listOf(quick), "teams:essay", ChangeType.DueBy))
    }

    // --- Your minutes for any task ---

    @Test
    fun `your minutes for a task are laid over the estimate, and taken back`() {
        val essay = teams("essay", "2026-10-16T09:00", effort = 60)
        val overrides = InstructionRules.taskOverrides(listOf(applied(Change(ChangeType.Minutes, taskId = essay.id, minutes = 150))))
        val longer = essay.withMine(overrides.getValue(essay.id))
        assertEquals(150, longer.effortMin)
        assertEquals(60, longer.appEffortMin)
        assertEquals(60, longer.withMine(Mine.NONE).effortMin)
    }

    @Test
    fun `your minutes aren't scaled by what the app has learned of its own estimates`() {
        val slow = Calibration(multipliers = mapOf("Homework|" to 2.0))
        val essay = teams("essay", "2026-10-16T09:00", effort = 60)
        assertEquals(120, plan(listOf(essay), slow).chunksOf(essay.id).sumOf { it.minutes })
        val mine = essay.yours(minutes = 60)
        assertEquals(60, plan(listOf(mine), slow).chunksOf(mine.id).sumOf { it.minutes })
    }

    @Test
    fun `your minutes for a task with steps scale its steps to add up to them`() {
        val steps = listOf(SubStep("Q1-8", 30), SubStep("Q9-16", 30))
        val mine = teams("prep", "2026-10-16T09:00", steps = steps).yours(minutes = 120)
        val pieces = plan(listOf(mine)).chunksOf(mine.id)
        assertEquals(listOf("Q1-8", "Q9-16"), pieces.map { it.step })
        assertEquals(listOf(60, 60), pieces.map { it.minutes })
    }

    @Test
    fun `your minutes shared over steps add up to exactly yours`() {
        val steps = listOf(SubStep("a", 10), SubStep("b", 10), SubStep("c", 10))
        val mine = teams("prep", "2026-10-30T09:00", steps = steps).yours(minutes = 100)
        assertEquals(listOf(34, 33, 33), mine.plannedStepMinutes(1.0))
        assertEquals(100, plan(listOf(mine)).chunksOf(mine.id).sumOf { it.minutes })
        // Fewer minutes than steps can't be given: they couldn't add up to it.
        assertEquals(3..OwnTaskRules.MINUTES.last, OwnTaskRules.minutesFor(mine))
    }

    @Test
    fun `Claude's minutes for a listed task are read, in range`() {
        val essay = teams("essay", "2026-10-16T09:00")
        fun parse(minutes: Int) = InstructionAnswers.parse(
            """{"understood":true,"question":null,"changes":[{"type":"Minutes","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":$minutes,"date":null,"weekday":null,"time":null,"endTime":null}]}""",
            listOf(essay), emptyList(), About(), now, LONDON,
        )
        assertEquals(Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 45), assertIs<InstructionAnswers.Reading.Changes>(parse(45)).changes.single())
        assertIs<InstructionAnswers.Reading.Unclear>(parse(0))
        assertIs<InstructionAnswers.Reading.Unclear>(parse(100_000))
    }

    @Test
    fun `your minutes read in words`() {
        val essay = teams("essay", "2026-10-16T09:00", effort = 60)
        val change = Change(ChangeType.Minutes, taskId = essay.id, minutes = 90)
        assertEquals("“essay”: takes 1 h 30 min in all (now 1 h)", InstructionRules.describe(change, mapOf(essay.id to essay), LONDON))
        val inUse = essay.yours(minutes = 90)
        assertEquals("“essay”: takes 1 h 30 min in all (the app's estimate: 1 h)", InstructionRules.describe(change, mapOf(essay.id to inUse), LONDON))
    }

    // --- How Tasks and the Plan group them ---

    @Test
    fun `tasks are grouped by when they're due, soonest first, this week running to Sunday`() {
        val tasks = listOf(
            teams("late", "2026-10-12T09:00"),
            teams("tonight", "2026-10-12T23:59"),
            teams("tomorrow", "2026-10-13T08:30"),
            teams("friday", "2026-10-16T09:00"),
            teams("sunday", "2026-10-18T20:00"),
            teams("next-week", "2026-10-19T09:00"),
            teams("whenever", null),
            teams("earlier-friday", "2026-10-16T08:00"),
        )
        val groups = Groups.byDue(tasks, now, LONDON)
        assertEquals(
            listOf(
                DueGroup.Overdue to listOf("late"),
                DueGroup.Today to listOf("tonight"),
                DueGroup.Tomorrow to listOf("tomorrow"),
                DueGroup.ThisWeek to listOf("earlier-friday", "friday", "sunday"),
                DueGroup.Later to listOf("next-week"),
                DueGroup.NoDate to listOf("whenever"),
            ),
            groups.map { (g, ts) -> g to ts.map { it.title } },
        )
    }

    @Test
    fun `a date the app or Claude set that has gone by is today's, not overdue`() {
        // Claude's reading of an email, not quoting it, gave this morning (PL-14): a plan, planned again.
        val reading = Enrichment(inputHash = "", by = "claude", at = now, kind = Kind.Admin, deadline = Fixtures.at("2026-10-12T09:00"))
        val email = teams("email", "2026-10-12T09:00").copy(id = "gmail:email", source = Source.Gmail, enrichment = reading)
        val groups = Groups.byDue(listOf(teams("late", "2026-10-12T09:00"), email), now, LONDON)
        assertEquals(listOf(DueGroup.Overdue to listOf("late"), DueGroup.Today to listOf("email")), groups.map { (g, ts) -> g to ts.map { it.title } })
        // A test date Claude read gone by, before Teams' own next Monday: under the real deadline's group.
        val test = teams("prep", "2026-10-09T08:30").copy(sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-19T09:00"), null))
        assertEquals(listOf(DueGroup.Later to listOf("prep")), Groups.byDue(listOf(test), now, LONDON).map { (g, ts) -> g to ts.map { it.title } })
    }

    @Test
    fun `a day's pieces are a row a task, where its first piece comes, its pieces in order`() {
        fun chunk(task: String, part: Int) = Chunk(
            taskId = task, source = Source.Teams, kind = Kind.Homework, title = task, step = "part $part", minutes = 30,
            dueAt = null, deadline = now, soft = false, overdue = false, dueToday = false, behind = false, part = part, parts = 3, taskMinutes = 90,
            why = Why.Spread,
        )
        val rows = Groups.byTask(listOf(chunk("a", 1), chunk("b", 1), chunk("a", 2), chunk("a", 3)))
        assertEquals(listOf("a", "b"), rows.map { it.taskId })
        assertEquals(listOf(1, 2, 3), rows.first().pieces.map { it.part })
        assertEquals(90, rows.first().minutes)
    }

    @Test
    fun `a step made anew in the form after every old one went has no tick, even with an old one's name`() {
        val made = OwnTaskRules.create(OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 40))), "s", now)
        val ticked = made.copy(subSteps = made.subSteps.map { if (it.title == "Plan") it.copy(done = true) else it })
        val anew = OwnTaskRules.edited(ticked, OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 30)), fromForm = true))
        assertEquals(listOf("Plan" to false), anew.subSteps.map { it.title to it.done })
    }

    @Test
    fun `a done step deleted takes its worked minutes with it`() {
        val made = OwnTaskRules.create(OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 30))), "w", now)
        val ticked = made.copy(subSteps = made.subSteps.map { if (it.title == "Plan") it.copy(done = true) else it }, workedMin = 20)
        val draft = OwnTaskRules.draftOf(ticked)
        val left = OwnTaskRules.edited(ticked, draft.copy(steps = draft.steps.drop(1), fromForm = true))
        assertEquals(listOf("Write" to false), left.subSteps.map { it.title to it.done })
        assertEquals(0, left.workedMin)
    }
}
