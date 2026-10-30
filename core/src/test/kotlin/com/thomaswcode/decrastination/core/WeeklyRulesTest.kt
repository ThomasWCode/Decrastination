package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekly work (D-124): the rules in force, each week's task, and what's closed, kept and made as the weeks turn. */
class WeeklyRulesTest {
    private val friday = Fixtures.at("2026-10-30T12:00")
    private val thisWeek = LocalDate.parse("2026-10-26")
    private val nextWeek = LocalDate.parse("2026-11-02")
    private val guitar = WeeklyRule("g", "Guitar practice", 180, where = "home", subject = "Music", setAt = friday)

    private fun rule(minutes: Int = 180, days: List<Int> = emptyList(), session: Int = 0, setAt: Long = Fixtures.at("2026-10-01T12:00")) =
        WeeklyRule("g", "Guitar practice", minutes, days, session, setAt = setAt)

    private fun applied(vararg changes: Change) = changes.mapIndexed { i, c -> Instruction("i$i", "", at = i.toLong(), state = InstructionStatus.Applied, changes = listOf(c), appliedAt = i.toLong()) }

    private fun roll(tasks: List<TaskItem> = emptyList(), made: Map<String, String> = emptyMap(), rules: List<WeeklyRule> = listOf(guitar), now: Long = friday) = WeeklyRules.roll(tasks, made, rules, now, LONDON)

    @Test
    fun `a rule changed later replaces it, and one ended is gone`() {
        val four = guitar.copy(minutes = 240)
        val other = WeeklyRule("r", "Reading", 60)
        assertEquals(listOf(four, other), WeeklyRules.inForce(applied(Change(ChangeType.Weekly, weekly = guitar), Change(ChangeType.Weekly, weekly = other), Change(ChangeType.Weekly, weekly = four))))
        assertEquals(listOf(other), WeeklyRules.inForce(applied(Change(ChangeType.Weekly, weekly = guitar), Change(ChangeType.Weekly, weekly = other), Change(ChangeType.WeeklyEnd, weekly = guitar))))
    }

    @Test
    fun `the week it's set in takes a share of the days left, and later weeks the whole`() {
        val tasks = roll().tasks
        assertEquals(listOf(thisWeek.toString(), nextWeek.toString()), tasks.map { it.weekly?.week })
        // Friday, Saturday and Sunday of three hours: 77 minutes, to the nearest five.
        assertEquals(listOf(75, 180), tasks.map { it.sourceEffortMin })
        assertEquals(listOf(25, 180), listOf(WeeklyRules.share(180, 1), WeeklyRules.share(180, 7)))
    }

    @Test
    fun `a task of a week is your own, for home as the rule says, with a plan's date`() {
        val task = roll().tasks.first()
        assertEquals(Source.Own, task.source)
        assertEquals(Kind.Revision, task.kind)
        assertEquals("Music", task.className)
        assertEquals("home", task.extra[OwnTaskRules.EXTRA_WHERE])
        assertEquals(Fixtures.at("2026-11-01T23:59"), task.dueAt)
        assertNull(task.realDeadline)
        assertFalse(task.hardDeadline)
        assertFalse(task.hardAt(Fixtures.at("2026-11-01T23:00")))
    }

    @Test
    fun `changing a week's task keeps its date a plan`() {
        val task = roll().tasks.first()
        val edited = OwnTaskRules.edited(task, OwnTaskRules.draftOf(task).copy(minutes = 90))
        assertEquals(90, edited.sourceEffortMin)
        assertEquals(task.dueAt, edited.dueAt)
        assertNull(edited.realDeadline)
    }

    @Test
    fun `next week's task starts on its Monday`() {
        val task = roll().tasks.last()
        assertEquals(Fixtures.at("2026-11-02T00:00"), task.notBefore)
        assertEquals(Fixtures.at("2026-11-08T23:59"), task.dueAt)
    }

    @Test
    fun `named days are a dated session each, only those still to come in the week it's set in`() {
        val rule = rule(90, days = listOf(2, 5, 6), setAt = friday)
        val first = assertNotNull(WeeklyRules.draft(rule, thisWeek, friday, LONDON))
        assertEquals(listOf("Friday", "Saturday"), first.steps.map { it.title })
        assertEquals(listOf(30, 30), first.steps.map { it.minutes })
        assertEquals(60, first.minutes)
        assertEquals(Fixtures.at("2026-10-30T00:00"), first.steps[0].from)
        assertEquals(Fixtures.at("2026-10-30T23:59"), first.steps[0].dueAt)
        assertEquals(3, WeeklyRules.draft(rule, nextWeek, friday, LONDON)?.steps?.size)
    }

    @Test
    fun `a rule set past its named days has nothing this week`() {
        val rule = rule(60, days = listOf(2), setAt = friday)
        assertNull(WeeklyRules.draft(rule, thisWeek, friday, LONDON))
        assertEquals(listOf(nextWeek.toString()), roll(rules = listOf(rule)).tasks.map { it.weekly?.week })
    }

    @Test
    fun `a session length is that many sessions of the week's minutes`() {
        val draft = assertNotNull(WeeklyRules.draft(rule(180, session = 45), nextWeek, friday, LONDON))
        assertEquals(List(4) { 45 }, draft.steps.map { it.minutes })
        assertEquals("Session 4", draft.steps.last().title)
        assertTrue(draft.steps.all { it.anyOrder })
        assertNull(OwnTaskRules.problem(draft))
    }

    @Test
    fun `a week's task is one task however often it's rolled`() {
        val first = roll()
        assertEquals(first, roll(first.tasks, first.weeksMade))
    }

    @Test
    fun `a week you've deleted stays gone`() {
        val first = roll()
        val rolled = roll(first.tasks.drop(1), first.weeksMade)
        assertEquals(listOf(nextWeek.toString()), rolled.tasks.map { it.weekly?.week })
    }

    @Test
    fun `a week that's over closes as missed with its minutes kept, and the next is made`() {
        val first = roll()
        val worked = first.tasks.map { if (it.weekly?.week == thisWeek.toString()) it.copy(workedMin = 60) else it }
        val rolled = roll(worked, first.weeksMade, now = Fixtures.at("2026-11-02T00:10"))
        val closed = rolled.tasks.first { it.weekly?.week == thisWeek.toString() }
        assertEquals(Status.Missed, closed.status)
        assertEquals(60, closed.workedMin)
        assertEquals(Fixtures.at("2026-11-01T23:59"), closed.doneAt)
        assertEquals(listOf("2026-11-02", "2026-11-09"), rolled.tasks.filter { it.isOpen }.map { it.weekly?.week })
    }

    @Test
    fun `no week's made for the weeks the phone was off`() {
        val first = roll()
        val rolled = roll(first.tasks, first.weeksMade, now = Fixtures.at("2026-11-18T09:00"))
        assertEquals(listOf("2026-11-16", "2026-11-23"), rolled.tasks.filter { it.isOpen }.map { it.weekly?.week })
    }

    @Test
    fun `a rule ended closes a week worked on and lets the rest go`() {
        val first = roll()
        val worked = first.tasks.map { if (it.weekly?.week == thisWeek.toString()) it.copy(workedMin = 30) else it }
        val rolled = roll(worked, first.weeksMade, rules = emptyList())
        assertEquals(listOf(Status.Missed), rolled.tasks.map { it.status })
        assertEquals(emptyMap(), rolled.weeksMade)
    }

    @Test
    fun `a rule brought back opens its week again and makes the next again`() {
        val first = roll()
        val worked = first.tasks.map { if (it.weekly?.week == thisWeek.toString()) it.copy(workedMin = 30) else it }
        val ended = roll(worked, first.weeksMade, rules = emptyList())
        val back = roll(ended.tasks, ended.weeksMade)
        assertEquals(listOf(Status.Open, Status.Open), back.tasks.map { it.status })
        assertEquals(30, back.tasks.first().workedMin)
        assertNull(back.tasks.first().doneAt)
    }

    @Test
    fun `a rule changed moves the week not begun, not the week under way`() {
        val first = roll()
        val rolled = roll(first.tasks, first.weeksMade, rules = listOf(guitar.copy(minutes = 240, setAt = friday + 1)))
        assertEquals(listOf(75, 240), rolled.tasks.map { it.sourceEffortMin })
    }

    @Test
    fun `what's set on next week's task stands while the rule doesn't change`() {
        val first = roll()
        val next = first.tasks.last()
        val edited = first.tasks.dropLast(1) + OwnTaskRules.edited(next, OwnTaskRules.draftOf(next).copy(minutes = 60))
        assertEquals(60, roll(edited, first.weeksMade).tasks.last().sourceEffortMin)
    }

    @Test
    fun `a week over isn't planned, even before it's closed`() {
        val task = roll().tasks.first()
        assertFalse(WeeklyRules.over(task, LocalDate.parse("2026-11-01")))
        assertTrue(WeeklyRules.over(task, LocalDate.parse("2026-11-02")))
        val plan = Planner.plan(Planner.Input(listOf(task), Fixtures.at("2026-11-02T17:00"), LONDON, Settings()))
        assertTrue(plan.ordered.isEmpty())
    }

    @Test
    fun `done minutes count time kept and steps ticked once each`() {
        val task = roll().tasks.first().copy(workedMin = 40, subSteps = listOf(SubStep("a", 45, done = true, timedMin = 40), SubStep("b", 30, done = true, byHand = true), SubStep("c", 45)))
        assertEquals(75, WeeklyRules.doneMin(task))
    }

    @Test
    fun `weekly work takes Sunday, gives way to what's dated, and what doesn't fit is said, not crowded in`() {
        val hours = Settings(boxMin = 60, marginDays = 1, weekendHours = Window(10 * 60, 13 * 60))
        // Sunday afternoon's three hours, all taken by homework: nothing of the week's is laid, none behind.
        val week = WeeklyRules.make(rule(120), thisWeek, Fixtures.at("2026-11-01T09:00"), LONDON)!!
        val alone = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-11-01T09:00"), LONDON, hours))
        assertEquals(listOf(LocalDate.parse("2026-11-01")), alone.buckets.filter { it.chunks.isNotEmpty() }.map { it.date }.distinct())
        val homework = TaskItem("teams:h", Source.Teams, "h", "h", dueAt = Fixtures.at("2026-11-01T23:59"), kind = Kind.Homework, sourceEffortMin = 180, sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-11-01T23:59")), firstSeenAt = 0, lastSeenAt = 0)
        val full = Planner.plan(Planner.Input(listOf(homework, week), Fixtures.at("2026-11-01T09:00"), LONDON, hours))
        assertEquals(setOf("teams:h"), full.ordered.map { it.taskId }.toSet())
        assertEquals(listOf(Unplanned.Reason.NoRoom), full.unplanned.filter { it.taskId == week.id }.map { it.reason })
    }

    @Test
    fun `a session on a named day that's gone by is dropped, and the days after it stay`() {
        val hours = Settings(boxMin = 60, weekdayHours = Window(17 * 60, 21 * 60))
        val week = WeeklyRules.make(rule(120, days = listOf(2, 4)), thisWeek, Fixtures.at("2026-10-26T09:00"), LONDON)!!
        val plan = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-10-28T09:00"), LONDON, hours))
        assertEquals(listOf(LocalDate.parse("2026-10-29") to "Thursday"), plan.buckets.flatMap { b -> b.chunks.map { b.date to it.step } })
        assertTrue(plan.unplanned.isEmpty())
    }

    @Test
    fun `a session on a named day can go later that day, never to the next`() {
        val hours = Settings(boxMin = 60, weekdayHours = Window(17 * 60, 21 * 60))
        val week = WeeklyRules.make(rule(60, days = listOf(2)), thisWeek, Fixtures.at("2026-10-26T09:00"), LONDON)!!
        val later = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-10-27T19:00"), LONDON, hours))
        assertEquals(listOf(LocalDate.parse("2026-10-27")), later.buckets.filter { it.chunks.isNotEmpty() }.map { it.date })
        val full = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-10-27T20:50"), LONDON, hours))
        assertTrue(full.ordered.none { it.taskId == week.id })
        assertEquals(listOf(Unplanned.Reason.NoRoom), full.unplanned.map { it.reason })
    }

    @Test
    fun `work the app put on a day moves on through the week`() {
        val hours = Settings(boxMin = 60, weekdayHours = Window(17 * 60, 21 * 60))
        val week = WeeklyRules.make(rule(180), thisWeek, Fixtures.at("2026-10-26T09:00"), LONDON)!!
        val plan = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-10-30T22:00"), LONDON, hours))
        assertEquals(180, plan.ordered.sumOf { it.minutes })
        assertTrue(plan.buckets.filter { it.chunks.isNotEmpty() }.all { it.date >= LocalDate.parse("2026-10-31") && it.date <= LocalDate.parse("2026-11-01") })
    }

    @Test
    fun `a time given a week's session keeps it past its day`() {
        val hours = Settings(boxMin = 60, weekdayHours = Window(17 * 60, 21 * 60))
        val pin = Fixtures.at("2026-10-28T18:00")
        val week = WeeklyRules.make(rule(60, days = listOf(2)), thisWeek, Fixtures.at("2026-10-26T09:00"), LONDON)!!.copy(mine = Mine(atTime = SetBy(pin, By.You)))
        val plan = Planner.plan(Planner.Input(listOf(week), Fixtures.at("2026-10-28T09:00"), LONDON, hours))
        assertEquals(listOf(LocalDate.parse("2026-10-28")), plan.buckets.filter { it.chunks.isNotEmpty() }.map { it.date })
    }

    @Test
    fun `a session on a named day doesn't go ahead of homework due later that week`() {
        val hours = Settings(boxMin = 60, weekdayHours = Window(17 * 60, 18 * 60))
        val now = Fixtures.at("2026-10-27T09:00")
        val week = WeeklyRules.make(rule(60, days = listOf(2)), thisWeek, now, LONDON)!!
        val homework = TaskItem("teams:h", Source.Teams, "h", "h", dueAt = Fixtures.at("2026-10-27T23:59"), kind = Kind.Homework, sourceEffortMin = 60, sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-27T23:59")), firstSeenAt = 0, lastSeenAt = 0)
        val plan = Planner.plan(Planner.Input(listOf(week, homework), now, LONDON, hours))
        assertEquals(listOf("teams:h"), plan.ordered.filter { it.minutes == 60 }.map { it.taskId }.take(1))
        assertFalse(plan.ordered.any { it.taskId == week.id && it.behind })
    }
}
