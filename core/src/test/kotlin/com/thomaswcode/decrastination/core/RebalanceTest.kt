package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Two of Felix's steps rebalanced to fit a free time at school (D-110). */
class RebalanceTest {
    private val now = Fixtures.at("2026-10-13T07:00")
    private val today = LocalDate.parse("2026-10-13")
    private val breakTime = Gap(Fixtures.at("2026-10-13T10:45"), 20)

    private fun step(title: String, minutes: Int, origin: StepOrigin = StepOrigin.Felix, done: Boolean = false, anyOrder: Boolean = false, where: String? = "anywhere") =
        SubStep(title, minutes, done = done, where = where, id = title.filter { it.isLetterOrDigit() }.take(8), origin = origin, anyOrder = anyOrder)

    private fun task(vararg steps: SubStep, workedMin: Int = 0) = TaskItem(
        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Worksheet", kind = Kind.Homework,
        firstSeenAt = now, lastSeenAt = now, subSteps = steps.toList(), workedMin = workedMin,
    )

    private fun plan(gaps: List<Gap> = listOf(breakTime), slots: List<Slot> = emptyList()) = Plan(now, today, listOf(DayBucket(today, 300, emptyList(), slots, gaps = gaps)), emptyList())

    private val pair = arrayOf(step("Questions 1 and 2", 40), step("Question 3", 20))

    @Test
    fun `a next step too long for a free time at school, with a step after it that needs it, is asked about`() {
        val c = Rebalance.candidates(plan(), listOf(task(*pair)), emptySet(), now).single()
        assertEquals(listOf<Any>("teams:sheet", 0, 20), listOf(c.taskId, c.index, c.gap.minutes))
    }

    @Test
    fun `none for steps not Felix's, begun, free of the one before, taken back, fitting already, or with none after`() {
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, StepOrigin.Reading), step("Question 3", 20, StepOrigin.Reading))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair, workedMin = 10)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40), step("Question 3", 20, anyOrder = true))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair)), setOf("teams:sheet"), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(listOf(Gap(breakTime.start, 45))), listOf(task(*pair)), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(*pair)), emptySet(), Fixtures.at("2026-10-13T11:00")))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40))), emptySet(), now))
        // For home only, its own or its task's (Teams homework's): no free time at school takes it, however it's cut.
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, where = "home"), step("Question 3", 20))), emptySet(), now))
        assertEquals(emptyList(), Rebalance.candidates(plan(), listOf(task(step("Questions 1 and 2", 40, where = null), step("Question 3", 20))), emptySet(), now))
        // Its slot today before the free time: nothing to gain.
        val chunk = Chunk(
            taskId = "teams:sheet", source = Source.Teams, kind = Kind.Homework, title = "Worksheet", step = "Questions 1 and 2", minutes = 40,
            dueAt = null, deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false,
            part = 1, parts = 2, taskMinutes = 60, stepIndex = 0, why = Why.Spread,
        )
        val early = Slot(SlotKind.Work, chunk, Fixtures.at("2026-10-13T08:50"), Fixtures.at("2026-10-13T09:30"), WindowKind.Study, pinned = false)
        assertEquals(emptyList(), Rebalance.candidates(plan(slots = listOf(early)), listOf(task(*pair)), emptySet(), now))
    }

    @Test
    fun `his answer becomes the task's steps with the two moved, or nothing where it isn't one`() {
        val t = task(step("Read the brief", 15, done = true), *pair)
        val c = Rebalance.candidates(plan(), listOf(t), emptySet(), now).single()
        assertEquals(1, c.index)
        val given = Rebalance.Given("teams:sheet", 1, "Question 1", 20, "Questions 2 and 3", 40)
        val steps = Rebalance.steps(t, c, given)!!
        assertEquals(listOf("Read the brief" to 15, "Question 1" to 20, "Questions 2 and 3" to 40), steps.map { it.title to it.minutes })
        // Their ids kept, so a slot's key and a tick follow them.
        assertEquals(t.subSteps.map { it.id }, steps.map { it.id })
        assertEquals("“Worksheet”: “Question 1” (20 min), then “Questions 2 and 3” (40 min)", Rebalance.line(c, given))
        // Longer than the free time, under 15, not adding up, another step, a title missing, or the steps changed since.
        assertNull(Rebalance.steps(t, c, given.copy(firstMinutes = 25, secondMinutes = 35)))
        assertNull(Rebalance.steps(t, c, given.copy(firstMinutes = 10, secondMinutes = 50)))
        assertNull(Rebalance.steps(t, c, given.copy(secondMinutes = 45)))
        assertNull(Rebalance.steps(t, c, given.copy(stepIndex = 0)))
        assertNull(Rebalance.steps(t, c, given.copy(firstTitle = " ")))
        assertNull(Rebalance.steps(t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 2) s.copy(minutes = 25) else s }), c, given))
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
