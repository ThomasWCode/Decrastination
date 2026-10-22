package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Two of a task's steps, Felix's or the reading's, rebalanced to fit a free time at school (D-110). */
class RebalanceTest {
    private val now = Fixtures.at("2026-10-13T07:00")
    private val today = LocalDate.parse("2026-10-13")
    private val breakTime = Gap(Fixtures.at("2026-10-13T10:45"), 20)

    private fun step(title: String, minutes: Int, origin: StepOrigin = StepOrigin.Felix, done: Boolean = false, anyOrder: Boolean = false, where: String? = "anywhere") =
        SubStep(title, minutes, done = done, where = where, id = title.filter { it.isLetterOrDigit() }.take(8), origin = origin, anyOrder = anyOrder)

    private fun task(vararg steps: SubStep, workedMin: Int = 0, id: String = "teams:sheet") = TaskItem(
        id = id, source = Source.Teams, sourceId = id.substringAfter(':'), title = "Worksheet", kind = Kind.Homework,
        firstSeenAt = now, lastSeenAt = now, subSteps = steps.toList(), workedMin = workedMin,
    )

    /** Step [index] of the worksheet (or task [taskId]) as a piece of today's. */
    private fun piece(index: Int = 0, taskId: String = "teams:sheet") = Chunk(
        taskId = taskId, source = Source.Teams, kind = Kind.Homework, title = "Worksheet", step = "Questions 1 and 2", minutes = 40,
        dueAt = null, deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false,
        part = index + 1, parts = 2, taskMinutes = 60, stepIndex = index, why = Why.Spread,
    )

    private fun plan(gaps: List<Gap> = listOf(breakTime), slots: List<Slot> = emptyList(), chunks: List<Chunk> = listOf(piece(), piece(1))) =
        Plan(now, today, listOf(DayBucket(today, 300, chunks, slots, gaps = gaps)), emptyList())

    private val pair = arrayOf(step("Questions 1 and 2", 40), step("Question 3", 20))

    @Test
    fun `a next step too long for a free time at school, with a step after it that needs it, is asked about`() {
        val c = Rebalance.candidates(plan(), listOf(task(*pair)), emptySet(), now).single()
        assertEquals(listOf<Any>("teams:sheet", 0, 20), listOf(c.taskId, c.index, c.gap.minutes))
    }

    @Test
    fun `none for steps not Felix's nor the reading's, begun, free of the one before, taken back, fitting already, or with none after`() {
        // Yours, as you gave them (D-4), your own task's, the rules' sections, the source's own; or two of different hands.
        for (origin in listOf(StepOrigin.Yours, StepOrigin.OwnTask, StepOrigin.Rules, StepOrigin.Source)) {
            assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, origin), step("Question 3", 20, origin))), emptySet(), now))
        }
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, StepOrigin.Reading), step("Question 3", 20, StepOrigin.Rules))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair, workedMin = 10)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40), step("Question 3", 20, anyOrder = true))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair)), setOf("teams:sheet"), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(listOf(Gap(breakTime.start, 45))), listOf(task(*pair)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair)), emptySet(), Fixtures.at("2026-10-13T11:00")))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40))), emptySet(), now))
        // For home only, its own or its task's (Teams homework's): no free time at school takes it, however it's cut.
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, where = "home"), step("Question 3", 20))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, where = null), step("Question 3", 20))), emptySet(), now))
        // Not today's work: a task for another day isn't rewritten for today's free time.
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = emptyList()), listOf(task(*pair)), emptySet(), now))
        // What moved would be planned later: past the first's own date still to come (the second's later, none,
        // or gone by and dropped), or waiting for the second to open; or done where it couldn't.
        val wednesday = Fixtures.at("2026-10-14T23:59")
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40).copy(dueAt = wednesday), step("Question 3", 20).copy(dueAt = Fixtures.at("2026-10-16T23:59")))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40).copy(dueAt = wednesday), step("Question 3", 20))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40).copy(dueAt = wednesday), step("Question 3", 20).copy(dueAt = Fixtures.at("2026-10-12T23:59")))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40), step("Question 3", 20).copy(from = Fixtures.at("2026-10-14T00:00")))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40), step("Question 3", 20, where = "home"))), emptySet(), now))
        // A piece not open till after the free time, or due that day before it ends: it couldn't go there.
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(availableAt = Fixtures.at("2026-10-13T16:00")), piece(1))), listOf(task(*pair)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(hard = true, deadline = Fixtures.at("2026-10-13T10:50")), piece(1))), listOf(task(*pair)), emptySet(), now))
        // Laid at other minutes than the step's own (shared out to a total you gave, or shortened by work): his wouldn't be the ones laid.
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(minutes = 30), piece(1))), listOf(task(*pair)), emptySet(), now))
        // Held at a time you gave later today: the pin would keep it there.
        val pinned = Slot(SlotKind.Work, piece(), Fixtures.at("2026-10-13T17:00"), Fixtures.at("2026-10-13T17:40"), WindowKind.Evening, pinned = true)
        assertEquals(emptyList(), Rebalance.candidates(plan(slots = listOf(pinned)), listOf(task(*pair)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(why = Why.Pinned), piece(1))), listOf(task(*pair)), emptySet(), now))
        // Its slot today before the free time: nothing to gain.
        val early = Slot(SlotKind.Work, piece(), Fixtures.at("2026-10-13T08:50"), Fixtures.at("2026-10-13T09:30"), WindowKind.Study, pinned = false)
        assertEquals(emptyList(), Rebalance.candidates(plan(slots = listOf(early)), listOf(task(*pair)), emptySet(), now))
    }

    @Test
    fun `the reading's steps are asked about too, at their minutes as the pace plans them, and written so`() {
        // Read at 10, 30 and 20 minutes, the first ticked, a pace of 1.5 learned for homework: laid at 15, 45 and 30.
        val paced = Calibration(multipliers = mapOf("Homework|" to 1.5))
        val t = task(step("Read the brief", 10, StepOrigin.Reading, done = true), step("Questions 1 and 2", 30, StepOrigin.Reading), step("Question 3", 20, StepOrigin.Reading), workedMin = 15)
        val chunks = listOf(piece(1).copy(minutes = 45), piece(2).copy(minutes = 30))
        val c = Rebalance.candidates(plan(chunks = chunks), listOf(t), emptySet(), now, paced).single()
        assertEquals(listOf(1, 45, 30), listOf(c.index, c.firstMinutes, c.secondMinutes))
        // Every step written at its minutes as planned, the ticked one's too: his from then on, not paced again, so
        // the task takes what it was laid at, and the minutes worked still cover the ticked step.
        val given = Rebalance.Given("teams:sheet", 1, "Question 1", 20, "Questions 2 and 3", 55)
        assertEquals(listOf(15, 20, 55), Rebalance.steps(t, c, given)!!.map { it.minutes })
        // His two adding up to the steps as read, not as laid: not the work's time.
        assertNull(Rebalance.steps(t, c, given.copy(secondMinutes = 30)))
        // Without the pace its pieces aren't laid at the steps' minutes as planned, and the ticked one's 15 worked
        // minutes look like a step begun: not asked about.
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = chunks), listOf(t), emptySet(), now))
        // One of its steps still to do over an hour as planned: laid in sittings, which his never are, so not asked about.
        val long = task(step("Questions 1 and 2", 30, StepOrigin.Reading), step("Question 3", 20, StepOrigin.Reading), step("Write it up", 50, StepOrigin.Reading))
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(minutes = 45), piece(1).copy(minutes = 30))), listOf(long), emptySet(), now, paced))
        // Shared out to a total you gave for the task (D-4): not asked about, even laid at the minutes it's shared out to.
        val yours = task(*pair).copy(mine = Mine(minutes = SetMinutes(90, By.You)))
        val shared = yours.plannedStepMinutes(1.0)
        assertEquals(emptyList(), Rebalance.candidates(plan(chunks = listOf(piece().copy(minutes = shared[0]), piece(1).copy(minutes = shared[1]))), listOf(yours), emptySet(), now))
    }

    @Test
    fun `steps with dates of their own are asked about where the work moved is planned no later`() {
        val friday = Fixtures.at("2026-10-16T23:59")
        fun asked(first: SubStep, second: SubStep) = Rebalance.candidates(plan(), listOf(task(first, second)), emptySet(), now).size
        // The first's own date gone by: a plan's, dropped (PL-14), so its work was held to the second's already.
        assertEquals(1, asked(step("Questions 1 and 2", 40).copy(dueAt = Fixtures.at("2026-10-12T23:59")), step("Question 3", 20).copy(dueAt = friday)))
        // None of its own: done by the second's, as the steps go in order.
        assertEquals(1, asked(step("Questions 1 and 2", 40), step("Question 3", 20).copy(dueAt = friday)))
        // Both by the same day still to come, the second open already though it opened a day after the first.
        assertEquals(1, asked(
            step("Questions 1 and 2", 40).copy(from = Fixtures.at("2026-10-11T00:00"), dueAt = friday),
            step("Question 3", 20).copy(from = Fixtures.at("2026-10-12T00:00"), dueAt = friday),
        ))
    }

    @Test
    fun `each free time is one task's, two not rebalanced for the same`() {
        val other = task(*pair, id = "teams:other")
        val both = listOf(piece(), piece(1), piece(0, "teams:other"), piece(1, "teams:other"))
        assertEquals(listOf("teams:sheet"), Rebalance.candidates(plan(chunks = both), listOf(task(*pair), other), emptySet(), now).map { it.taskId })
        // Taken in the day's order of their pieces, the more urgent first, not the store's.
        val urgentFirst = listOf(piece(0, "teams:other"), piece(1, "teams:other"), piece(), piece(1))
        assertEquals(listOf("teams:other"), Rebalance.candidates(plan(chunks = urgentFirst), listOf(task(*pair), other), emptySet(), now).map { it.taskId })
        // With lunch free too, the second has it.
        val lunch = Gap(Fixtures.at("2026-10-13T13:15"), 25)
        val two = Rebalance.candidates(plan(gaps = listOf(breakTime, lunch), chunks = both), listOf(task(*pair), other), emptySet(), now)
        assertEquals(listOf("teams:sheet" to breakTime, "teams:other" to lunch), two.map { it.taskId to it.gap })
    }

    @Test
    fun `his answer becomes the task's steps with the two moved, or nothing where it isn't one`() {
        val t = task(step("Read the brief", 15, done = true), *pair)
        val c = Rebalance.candidates(plan(chunks = listOf(piece(1), piece(2))), listOf(t), emptySet(), now).single()
        assertEquals(1, c.index)
        val given = Rebalance.Given("teams:sheet", 1, "Question 1", 20, "Questions 2 and 3", 40)
        val steps = Rebalance.steps(t, c, given)!!
        assertEquals(listOf("Read the brief" to 15, "Question 1" to 20, "Questions 2 and 3" to 40), steps.map { it.title to it.minutes })
        // Their ids kept, so a slot's key and a tick follow them.
        assertEquals(t.subSteps.map { it.id }, steps.map { it.id })
        assertEquals("“Worksheet”: “Question 1” (20 min), then “Questions 2 and 3” (40 min)", Rebalance.line(c, given))
        // A title longer than a step's can be is cut, on its card as on its step.
        val long = given.copy(firstTitle = "Question 1 " + "x".repeat(OwnTaskRules.MAX_TITLE))
        val cut = Rebalance.steps(t, c, long)!![1].title
        assertEquals(OwnTaskRules.MAX_TITLE, cut.length)
        assertEquals("“Worksheet”: “$cut” (20 min), then “Questions 2 and 3” (40 min)", Rebalance.line(c, long))
        // Longer than the free time, under 15, not adding up, another step, a title missing, or the steps changed since.
        assertNull(Rebalance.steps(t, c, given.copy(firstMinutes = 25, secondMinutes = 35)))
        assertNull(Rebalance.steps(t, c, given.copy(firstMinutes = 10, secondMinutes = 50)))
        assertNull(Rebalance.steps(t, c, given.copy(secondMinutes = 45)))
        assertNull(Rebalance.steps(t, c, given.copy(stepIndex = 0)))
        assertNull(Rebalance.steps(t, c, given.copy(firstTitle = " ")))
        assertNull(Rebalance.steps(t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 2) s.copy(minutes = 25) else s }), c, given))
    }

    @Test
    fun `a plan tried with a rebalance is as offered where its first step is laid in the free time, its rest kept`() {
        val c = Rebalance.candidates(plan(), listOf(task(*pair)), emptySet(), now).single()
        val settings = Settings()
        fun laid(start: String, end: String, step: Int = 0, kind: WindowKind = WindowKind.Break) = Slot(SlotKind.Work, piece(step), Fixtures.at("2026-10-13T$start"), Fixtures.at("2026-10-13T$end"), kind, pinned = false)
        fun tried(vararg slots: Slot) = Plan(now, today, listOf(DayBucket(today, 300, emptyList(), slots.toList())), emptyList())
        assertEquals(true, Rebalance.laidAsOffered(tried(laid("10:45", "11:05")), c, settings))
        // Laid at lunch, or not laid: not the time his message names.
        assertEquals(false, Rebalance.laidAsOffered(tried(laid("13:15", "13:35")), c, settings))
        assertEquals(false, Rebalance.laidAsOffered(tried(), c, settings))
        // After 80 minutes' work it makes a run long enough for a long rest: five minutes before the next is too few, fifteen enough.
        val before = laid("09:20", "10:40", step = 1, kind = WindowKind.Study)
        assertEquals(false, Rebalance.laidAsOffered(tried(before, laid("10:45", "11:05"), laid("11:10", "11:30", step = 1)), c, settings))
        assertEquals(true, Rebalance.laidAsOffered(tried(before, laid("10:45", "11:05"), laid("11:20", "11:40", step = 1)), c, settings))
    }

    @Test
    fun `a rebalance taken back, or never made, isn't asked about again`() {
        val change = Change(ChangeType.Steps, taskId = "teams:sheet", steps = listOf(OwnStep("Question 1", 20)))
        fun morning(state: ProposalState) = ChatMessage("m", Speaker.Felix, "morning.", now, kind = MessageKind.Morning, proposals = listOf(Proposal("p", changes = listOf(change), state = state, instant = true)))
        assertEquals(setOf("teams:sheet"), Rebalance.declined(listOf(morning(ProposalState.Proposed))))
        assertEquals(emptySet(), Rebalance.declined(listOf(morning(ProposalState.Applied))))
        // A change in a reply in the chat isn't one.
        assertEquals(emptySet(), Rebalance.declined(listOf(morning(ProposalState.Proposed).copy(kind = MessageKind.Chat))))
    }
}
