package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same work from two sources (DX-15). */
class DuplicatesTest {
    private fun task(id: String, source: Source, title: String, className: String?, due: String?) = TaskItem(
        id = "${source.name.lowercase()}:$id", source = source, sourceId = id, title = title, className = className,
        dueAt = due?.let(Fixtures::at), kind = Kind.Homework, firstSeenAt = 0, lastSeenAt = 0,
    )

    private val map = mapOf("12.2-PH3" to "Physics")

    @Test
    fun `work of one subject due within a day, its title's words shared, from two sources, looks the same`() {
        val teams = task("t", Source.Teams, "Chapter 17 questions", "12.2-PH3", "2026-10-14T08:30")
        val planner = task("p", Source.PowerPlanner, "Physics chapter 17 questions homework", "Physics", "2026-10-13T23:59")
        assertEquals(listOf(teams.id to planner.id), Duplicates.candidates(listOf(teams, planner), map).map { it.first.id to it.second.id })
        // Not the same: one source, another subject, days apart, other words, or said to be different.
        assertTrue(Duplicates.candidates(listOf(teams, planner.copy(id = "teams:p2", source = Source.Teams)), map).isEmpty())
        assertTrue(Duplicates.candidates(listOf(teams, planner.copy(className = "Maths")), map).isEmpty())
        assertTrue(Duplicates.candidates(listOf(teams, planner.copy(dueAt = Fixtures.at("2026-10-16T23:59"))), map).isEmpty())
        assertTrue(Duplicates.candidates(listOf(teams, planner.copy(title = "Revise for the mock")), map).isEmpty())
        assertTrue(Duplicates.candidates(listOf(teams, planner), map, listOf(Duplicates.key(planner.id, teams.id))).isEmpty())
        // Both undated is near enough.
        assertEquals(1, Duplicates.candidates(listOf(teams.copy(dueAt = null), planner.copy(dueAt = null)), map).size)
    }

    @Test
    fun `the same as another is hidden, the other kept, and two said the same as each other are both kept`() {
        val teams = task("t", Source.Teams, "Chapter 17 questions", "12.2-PH3", "2026-10-14T08:30")
        val planner = task("p", Source.PowerPlanner, "Physics chapter 17 questions", "Physics", "2026-10-13T23:59")
        fun applied(vararg changes: Change) = changes.mapIndexed { i, c -> Instruction("i$i", "same", at = i.toLong(), state = InstructionStatus.Applied, changes = listOf(c), appliedAt = i.toLong()) }
        val one = Instructions.taskOverrides(applied(Change(ChangeType.SameAs, taskId = planner.id, afterTaskId = teams.id)))
        assertTrue(planner.withOverrides(one.getValue(planner.id)).hidden)
        assertEquals(false, teams.withOverrides(one[teams.id] ?: TaskOverrides.NONE).hidden)
        val both = Instructions.taskOverrides(applied(Change(ChangeType.SameAs, taskId = planner.id, afterTaskId = teams.id), Change(ChangeType.SameAs, taskId = teams.id, afterTaskId = planner.id)))
        assertEquals(listOf(false, false), listOf(planner, teams).map { it.withOverrides(both.getValue(it.id)).hidden })
        assertEquals("“Physics chapter 17 questions” is the same work as “Chapter 17 questions”: only that one is planned", Instructions.describe(Change(ChangeType.SameAs, taskId = planner.id, afterTaskId = teams.id), mapOf(planner.id to planner, teams.id to teams), Fixtures.LONDON))
    }

    @Test
    fun `the one kept is planned with the same work's minutes worked as its own, the stored ones as they were`() {
        val teams = task("t", Source.Teams, "Chapter 17 questions", "12.2-PH3", "2026-10-14T08:30").copy(workedMin = 20)
        val planner = task("p", Source.PowerPlanner, "Physics chapter 17 questions", "Physics", "2026-10-13T23:59").copy(workedMin = 25, photoMin = 5)
        val linked = listOf(teams, planner.copy(userSameAs = teams.id))
        assertEquals(listOf(50, 25), Duplicates.credited(linked).map { it.workedMin })
        // Not linked (or the change taken back): each its own.
        assertEquals(listOf(20, 25), Duplicates.credited(listOf(teams, planner)).map { it.workedMin })
        assertEquals(listOf(20, 25), linked.map { it.workedMin })
    }

    @Test
    fun `a link to a task that's gone is let go, and the task's back`() {
        val teams = task("t", Source.Teams, "Chapter 17 questions", "12.2-PH3", "2026-10-14T08:30")
        val planner = task("p", Source.PowerPlanner, "Physics chapter 17 questions", "Physics", "2026-10-13T23:59").copy(userSameAs = teams.id)
        assertEquals(teams.id, Duplicates.linksPresent(listOf(teams, planner))[1].userSameAs)
        val alone = Duplicates.linksPresent(listOf(planner)).single()
        assertEquals(null, alone.userSameAs)
        assertEquals(false, alone.hidden)
    }
}

