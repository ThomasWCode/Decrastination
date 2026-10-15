package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sources.powerplanner.ClassesResponse
import com.thomaswcode.decrastination.sources.powerplanner.AgendaResponse
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerItems
import com.thomaswcode.decrastination.sources.powerplanner.SemesterResponse
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {
    // The box these tests were written for (30 since PL-12).
    private val settings = Settings(boxMin = 45)

    private fun task(
        id: String,
        dueAt: Long?,
        effort: Int = 40,
        kind: Kind = Kind.Homework,
        firstSeen: Long = Fixtures.at("2026-10-07T12:00"),
        steps: List<SubStep> = emptyList(),
        worked: Int = 0,
        available: Long? = null,
    ) = TaskItem(
        id = "teams:$id",
        source = Source.Teams,
        sourceId = id,
        title = id,
        dueAt = dueAt,
        availableFrom = available,
        kind = kind,
        sourceEffortMin = effort,
        workedMin = worked,
        subSteps = steps,
        firstSeenAt = firstSeen,
        lastSeenAt = firstSeen,
    )

    private fun plan(tasks: List<TaskItem>, at: String, busy: List<Busy> = emptyList(), loads: Map<LocalDate, Int> = emptyMap()) =
        Planner.plan(Planner.Input(tasks, Fixtures.at(at), LONDON, settings, busy = busy, dayLoads = loads))

    private fun Plan.dayOf(id: String): List<LocalDate> = buckets.filter { b -> b.chunks.any { it.taskId == "teams:$id" } }.map { it.date }

    // --- Tonight's real data: Wednesday 7 Oct 2026, 22:00, every evening hour gone. ---

    private fun fixtureTasks(): List<TaskItem> {
        val now = Fixtures.at("2026-10-07T22:00")
        val json = Json { ignoreUnknownKeys = true }
        val teams = json.parseToJsonElement(Fixtures.text("teams_widget_state.json")).jsonObject["assignments"]!!.jsonArray.map { element ->
            val a = element.jsonObject
            TeamsRows.fetched(
                mapOf(
                    "key" to a["key"]!!.jsonPrimitive.content,
                    "title" to a["title"]!!.jsonPrimitive.content,
                    "class_name" to a["className"]?.jsonPrimitive?.content,
                    "description" to a["description"]?.jsonPrimitive?.content,
                    "due_at" to a["dueAt"]?.jsonPrimitive?.longOrNull,
                ),
            )!!
        }
        val pp = json.parseToJsonElement(Fixtures.text("powerplanner_agenda.json")).jsonObject
        val semester = json.decodeFromJsonElement(SemesterResponse.serializer(), pp["selectedSemester"]!!).selectedSemesterId!!
        val classes = json.decodeFromJsonElement(ClassesResponse.serializer(), pp["classesAndSchedules"] as JsonObject)
        val agenda = json.decodeFromJsonElement(AgendaResponse.serializer(), pp["agenda"]!!).items!!
            .mapNotNull { PowerPlannerItems.fetched(it, semester, Timetable(classes.weekOneStartsOn, classes.classes!!), LONDON) }
        val quota = Fetched("quota:2026-10-07", "Anki: 100 new", Kind.Revision, dueAt = Fixtures.at("2026-10-07T21:30"), sourceEffortMin = 42, derived = true)
        return Merge.apply(emptyList(), Source.Teams, teams, now).tasks +
            Merge.apply(emptyList(), Source.PowerPlanner, agenda, now).tasks +
            Merge.apply(emptyList(), Source.Anki, listOf(quota), now).tasks
    }

    private val tonight by lazy { Planner.plan(Planner.Input(fixtureTasks(), Fixtures.at("2026-10-07T22:00"), LONDON, settings)) }

    private fun Plan.titlesOn(date: String) = bucket(LocalDate.parse(date))!!.chunks.map { it.title }

    @Test
    fun `tonight, everything overdue and due today is today's, and nothing fits`() {
        val today = tonight.titlesOn("2026-10-07")
        listOf(
            "Pg 60&61", "Gefahren in den sozialen Netzwerken. Vor- und Nachteile", "Familie und Ehe", "Statics Prep",
            "Vocabulary and translation", "Binomial Expansion", "Anki: 100 new", "Prep 30/09/2026 Chapter 17 review",
        ).forEach { assertTrue(it in today, "$it should be today's") }
        assertEquals(0, tonight.todayBucket!!.capacityMin)
        assertTrue(tonight.pressure)
    }

    @Test
    fun `tonight, work due today or tomorrow comes before overdue work, the oldest of that first`() {
        val order = tonight.todayBucket!!.chunks.map { it.title }.distinct()
        // Due tonight at 23:59, then tomorrow morning's two.
        assertEquals("Prep 30/09/2026 Chapter 17 review", tonight.next!!.title)
        assertTrue(tonight.next!!.dueToday)
        val soon = tonight.todayBucket!!.chunks.takeWhile { it.dueSoon }.map { it.title }.distinct()
        assertEquals(listOf("Prep 30/09/2026 Chapter 17 review", "Dr. Frost - Using F=ma - Week 4", "Prep and Assessment preparation"), soon)
        // Then what's overdue, the oldest deadline first.
        assertEquals("Pg 60&61", order[soon.size])
        assertTrue(tonight.todayBucket!!.chunks.first { it.title == "Pg 60&61" }.overdue)
        // Why each is there (PL-07): tonight's is due today; what's overdue says since when.
        val wednesday = LocalDate.parse("2026-10-07")
        assertEquals(Why.DueSoon, tonight.next!!.why)
        assertEquals("Due today", tonight.next!!.whyLine(wednesday, LONDON))
        val pg = tonight.todayBucket!!.chunks.first { it.title == "Pg 60&61" }
        assertEquals(Why.Overdue, pg.why)
        assertEquals("Overdue since " + Planner.date(pg.deadline, LONDON).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)), pg.whyLine(wednesday, LONDON))
    }

    @Test
    fun `an email that only needs reading, or tells of an event, isn't planned or reminded of`() {
        fun email(id: String, kind: Kind) = task(id, null, effort = 10, kind = kind).copy(id = "gmail:$id", source = Source.Gmail)
        val call = task("call", Fixtures.at("2026-10-09T17:00"), kind = Kind.Event).copy(id = "powerplanner:call", source = Source.PowerPlanner)
        val plan = plan(listOf(email("note", Kind.Admin), email("linkedin", Kind.Info), email("openday", Kind.Event), call), "2026-10-08T17:00")
        assertEquals(setOf("gmail:note"), plan.ordered.map { it.taskId }.toSet())
        // A planner item that's an event is still a reminder; an email about one isn't.
        assertEquals(listOf("powerplanner:call"), plan.events.map { it.id })
    }

    @Test
    fun `work due today or tomorrow takes today's time before overdue work`() {
        // Thursday 17:00: the evening's hours left; an overdue task bigger than all of them.
        val late = task("late", dueAt = Fixtures.at("2026-10-07T17:00"), effort = 400)
        val soon = task("soon", dueAt = Fixtures.at("2026-10-09T08:30"), effort = 40)
        val tonight = task("tonight", dueAt = Fixtures.at("2026-10-08T23:00"), effort = 30)
        val plan = plan(listOf(late, soon, tonight), "2026-10-08T17:00")
        val today = plan.todayBucket!!.chunks
        assertEquals(listOf("tonight", "soon", "late"), today.map { it.title }.distinct())
        // Tomorrow morning's work fits tonight, before the overdue work fills it: not behind.
        assertTrue(plan.chunksOf("teams:soon").none { it.behind })
        assertTrue(plan.chunksOf("teams:soon").all { it.dueSoon })
        assertTrue(plan.chunksOf("teams:late").none { it.dueSoon })
    }

    @Test
    fun `a task's overdue block brought along doesn't go ahead of other work due sooner`() {
        // An email's September reply, brought along by its application due tomorrow; homework due tonight.
        val steps = listOf(SubStep("Reply", 30, dueAt = Fixtures.at("2026-09-30T23:59")), SubStep("Apply", 30, dueAt = Fixtures.at("2026-10-09T23:59")))
        val email = task("e", dueAt = null, steps = steps)
        val homework = task("hw", dueAt = Fixtures.at("2026-10-08T23:00"), effort = 30)
        val plan = plan(listOf(email, homework), "2026-10-08T17:00")
        assertEquals(listOf("hw", "Reply", "Apply"), plan.todayBucket!!.chunks.map { it.step ?: it.title })
        assertEquals("teams:hw", plan.next!!.taskId)
    }

    @Test
    fun `a task's block due tomorrow doesn't go ahead of other work due tonight`() {
        // One block due at 18:00, another tomorrow; homework due at 19:00 in between.
        val steps = listOf(SubStep("First", 20, dueAt = Fixtures.at("2026-10-08T18:00")), SubStep("Second", 20, dueAt = Fixtures.at("2026-10-09T23:59")))
        val email = task("e", dueAt = null, steps = steps)
        val homework = task("hw", dueAt = Fixtures.at("2026-10-08T19:00"), effort = 20)
        val plan = plan(listOf(email, homework), "2026-10-08T17:00")
        assertEquals(listOf("First", "hw", "Second"), plan.todayBucket!!.chunks.map { it.step ?: it.title })
    }

    @Test
    fun `a task due soon brings its overdue blocks along, ahead of it in their order`() {
        val steps = listOf(SubStep("Reply", 30, dueAt = Fixtures.at("2026-10-07T23:59")), SubStep("Apply", 30, dueAt = Fixtures.at("2026-10-09T23:59")))
        val email = task("e", dueAt = null, steps = steps)
        val other = task("x", dueAt = Fixtures.at("2026-10-06T17:00"), effort = 30)
        val plan = plan(listOf(email, other), "2026-10-08T17:00")
        val today = plan.todayBucket!!.chunks.map { it.step ?: it.title }
        assertEquals(listOf("Reply", "Apply", "x"), today)
    }

    @Test
    fun `tomorrow morning's deadlines are today's, behind, since tonight is gone`() {
        val frost = tonight.todayBucket!!.chunks.single { it.title.startsWith("Dr. Frost") }
        assertTrue(frost.behind)
        assertEquals(Why.NoRoom, frost.why)
        assertEquals("Couldn't fit before its deadline", frost.whyLine(LocalDate.parse("2026-10-07"), LONDON))
        assertTrue(tonight.todayBucket!!.chunks.single { it.title == "Prep and Assessment preparation" }.behind)
    }

    @Test
    fun `later deadlines take the day with most room before them`() {
        // Spread (PL-01): a single piece goes to the roomiest day of its window. Friday 08:30's last
        // usable day is Thursday, the only one with time; the test Monday 11:00 gets Saturday, the
        // first of the weekend; PREP 2 (Tuesday 08:00) Sunday, Saturday having less now; the call
        // (Wednesday 16:00) Saturday, roomier than any weekday still.
        assertTrue("Gefahren im Internet. Wie kann man sich schuetzen? Verbot?" in tonight.titlesOn("2026-10-08"))
        assertEquals(listOf("Test", "Call with a friend after school."), tonight.titlesOn("2026-10-10"))
        assertEquals(listOf("PREP 2"), tonight.titlesOn("2026-10-11"))
    }

    // --- The rules, one at a time. ---

    @Test
    fun `a big task spreads over its window by each day's free time`() {
        // 135 minutes due Monday 09:00, Thursday 17:00: three 45s where its shares of the window's
        // time fall, Thursday's 300 minutes, Friday's 315 and the weekend's 840 each: Friday,
        // Saturday and Sunday.
        val plan = plan(listOf(task("big", Fixtures.at("2026-10-12T09:00"), effort = 135)), "2026-10-08T17:00")
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11").map(LocalDate::parse), plan.dayOf("big"))
        assertEquals(listOf(1, 2, 3), plan.chunksOf("teams:big").map { it.part })
        // Nothing of it is today's, so today isn't strict for it: pressure is today's bucket alone.
        assertFalse(plan.pressure)
        assertEquals(listOf("part 1 of 3"), plan.dueSoon.map { it.step })
    }

    @Test
    fun `a task cut into boxes says the box, one in a single piece doesn't`() {
        val due = Fixtures.at("2026-10-20T09:00")
        val plan = plan(listOf(task("long", due, effort = 90), task("short", due, effort = 10)), "2026-10-08T17:00")
        assertEquals(listOf(45, 45), plan.chunksOf("teams:long").map { it.box })
        assertEquals(listOf(null), plan.chunksOf("teams:short").map { it.box })
    }

    @Test
    fun `blocks with dates of their own are each planned in their own window`() {
        // One item, two applications: one by the 12th; one opening on the 20th, due by the 25th.
        val steps = listOf(
            SubStep("Apply to STEM Potential", 60, dueAt = Fixtures.at("2026-10-12T23:59")),
            SubStep("Apply to RAL", 60, from = Fixtures.at("2026-10-20T00:00"), dueAt = Fixtures.at("2026-10-25T23:59")),
        )
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        fun dayOf(step: String) = plan.buckets.first { b -> b.chunks.any { it.step == step } }.date
        assertTrue(dayOf("Apply to STEM Potential") <= LocalDate.parse("2026-10-11"))
        assertTrue(dayOf("Apply to RAL") in LocalDate.parse("2026-10-20")..LocalDate.parse("2026-10-24"))
        assertEquals(Fixtures.at("2026-10-25T23:59"), plan.chunksOf("teams:e").single { it.step == "Apply to RAL" }.dueAt)
    }

    @Test
    fun `a task's runs of blocks keep their order, and their parts numbers of their own`() {
        // Two long blocks due the same day, the second opening later: one fits a day, in order.
        val steps = listOf(
            SubStep("Draft", 200, dueAt = Fixtures.at("2026-10-16T23:59")),
            SubStep("Final", 200, from = Fixtures.at("2026-10-10T00:00"), dueAt = Fixtures.at("2026-10-16T23:59")),
        )
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        fun dayOf(step: String) = plan.buckets.first { b -> b.chunks.any { it.step == step } }.date
        // Saturday holds both now (840 minutes): the draft first in the day, as it's first in order.
        assertTrue(dayOf("Draft") <= dayOf("Final"), "draft ${dayOf("Draft")}, final ${dayOf("Final")}")
        assertEquals(listOf(1, 2), plan.chunksOf("teams:e").map { it.part })
        assertEquals(setOf(2), plan.chunksOf("teams:e").map { it.parts }.toSet())
    }

    @Test
    fun `a block opening after an undated task's soft deadline keeps its own start`() {
        val steps = listOf(SubStep("Apply", 60, from = Fixtures.at("2026-11-15T00:00")))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        val day = plan.buckets.first { b -> b.chunks.any { it.step == "Apply" } }.date
        assertTrue(day >= LocalDate.parse("2026-11-15"), "planned $day")
    }

    @Test
    fun `an undated block after a dated one waits for it`() {
        // An old undated item: its own soft deadline long gone.
        val steps = listOf(SubStep("Apply", 30, from = Fixtures.at("2026-10-20T00:00")), SubStep("Follow up", 30))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps, firstSeen = Fixtures.at("2026-09-01T12:00"))), "2026-10-08T17:00")
        fun dayOf(step: String) = plan.buckets.first { b -> b.chunks.any { it.step == step } }.date
        assertTrue(dayOf("Apply") >= LocalDate.parse("2026-10-20"))
        assertTrue(dayOf("Follow up") >= dayOf("Apply"), "apply ${dayOf("Apply")}, follow up ${dayOf("Follow up")}")
    }

    @Test
    fun `a step ticked by hand takes nothing back from minutes worked on the next`() {
        // The first ticked by hand; 20 minutes timed on the second.
        val steps = listOf(SubStep("A", 30, done = true, byHand = true), SubStep("B", 30))
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps, worked = 20)), "2026-10-08T17:00")
        assertEquals(listOf(10), plan.chunksOf("teams:t").map { it.minutes })
    }

    @Test
    fun `minutes timed on a block before it was ticked by hand aren't taken off the next`() {
        // Ten minutes on A, stopped, then A ticked by hand: it kept them.
        val steps = listOf(SubStep("A", 30, done = true, byHand = true, timedMin = 10), SubStep("B", 30))
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps, worked = 10)), "2026-10-08T17:00")
        assertEquals(listOf(30), plan.chunksOf("teams:t").map { it.minutes })
    }

    @Test
    fun `a run is planned to be done by any later run's deadline, and in order`() {
        // Listed first but due later than the block after it: done by then, before it.
        val steps = listOf(SubStep("First", 30, dueAt = Fixtures.at("2026-10-16T23:59")), SubStep("Second", 30, dueAt = Fixtures.at("2026-10-12T23:59")))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        val chunks = plan.buckets.flatMap { it.chunks }.filter { it.taskId == "teams:e" }
        assertEquals(listOf("First", "Second"), chunks.map { it.step })
        // Each still shows its own date.
        assertEquals(Fixtures.at("2026-10-16T23:59"), chunks.first().dueAt)
    }

    @Test
    fun `a block with only a start, after a dated one, stays behind it`() {
        // An old undated item: its soft deadline long gone; the follow-up's start already past.
        val steps = listOf(SubStep("Apply", 30, dueAt = Fixtures.at("2026-10-20T23:59")), SubStep("Follow up", 30, from = Fixtures.at("2026-10-01T00:00")))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps, firstSeen = Fixtures.at("2026-09-01T12:00"))), "2026-10-08T17:00")
        fun dayOf(step: String) = plan.buckets.first { b -> b.chunks.any { it.step == step } }.date
        assertTrue(dayOf("Follow up") >= dayOf("Apply"), "apply ${dayOf("Apply")}, follow up ${dayOf("Follow up")}")
    }

    @Test
    fun `a block after one opening past the plan's reach waits with it`() {
        val steps = listOf(
            SubStep("First", 30, from = Fixtures.at("2027-01-16T00:00")),
            SubStep("Second", 30, dueAt = Fixtures.at("2027-02-05T23:59")),
        )
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        assertEquals(emptyList(), plan.chunksOf("teams:e"))
    }

    @Test
    fun `a task's runs all carry its whole length, for the shorter-first order`() {
        val steps = listOf(SubStep("A", 60, dueAt = Fixtures.at("2026-10-12T23:59")), SubStep("B", 60, from = Fixtures.at("2026-10-10T00:00"), dueAt = Fixtures.at("2026-10-14T23:59")))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        assertEquals(setOf(120), plan.chunksOf("teams:e").map { it.taskMinutes }.toSet())
    }

    @Test
    fun `a block opening past the plan's reach isn't planned yet`() {
        val steps = listOf(SubStep("Apply in spring", 60, from = Fixtures.at("2027-03-01T00:00"), dueAt = Fixtures.at("2027-03-20T23:59")))
        val plan = plan(listOf(task("e", dueAt = null, steps = steps)), "2026-10-08T17:00")
        assertEquals(emptyList(), plan.chunksOf("teams:e"))
    }

    @Test
    fun `boxes are even`() {
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), effort = 100)), "2026-10-08T17:00")
        assertEquals(listOf(34, 33, 33), plan.chunksOf("teams:t").map { it.minutes })
        assertEquals("part 1 of 3", plan.chunksOf("teams:t").first().step)
    }

    @Test
    fun `when the evenings before a deadline are full, chunks share a day, then fall behind into today`() {
        // Two 300-minute tasks due Saturday 09:00 (last usable day Friday); only Thursday and Friday
        // evenings, 285 + 315 minutes, remain after 17:00 on Thursday.
        val a = task("a", Fixtures.at("2026-10-10T09:00"), effort = 300)
        val b = task("b", Fixtures.at("2026-10-10T09:00"), effort = 300)
        val plan = plan(listOf(a, b), "2026-10-08T17:00")
        val behind = plan.todayBucket!!.chunks.filter { it.behind }
        assertTrue(behind.isNotEmpty())
        assertTrue(behind.all { it.why == Why.NoRoom })
        assertTrue(plan.ordered.all { it.part <= it.parts })
        assertEquals(600, plan.ordered.sumOf { it.minutes })
    }

    @Test
    fun `minutes already worked come off, down to a last five`() {
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), effort = 40, worked = 50)), "2026-10-08T17:00")
        assertEquals(listOf(5), plan.chunksOf("teams:t").map { it.minutes })
    }

    @Test
    fun `progress corrected downward brings its work back`() {
        // First seen at 50 %, then corrected to 20 %, no sessions: 80 minutes are left, not 50.
        val t = task("t", Fixtures.at("2026-10-20T09:00"), effort = 100).copy(sourceProgress = 0.2, firstProgress = 0.5)
        assertEquals(80, plan(listOf(t), "2026-10-08T17:00").chunksOf("teams:t").sumOf { it.minutes })
    }

    @Test
    fun `work a session did and the source's progress both show isn't taken off twice`() {
        // A 100-minute task: a 30-minute session, then Power Planner moved to 30 %.
        val t = task("t", Fixtures.at("2026-10-20T09:00"), effort = 100, worked = 30).copy(sourceProgress = 0.3, firstProgress = 0.0)
        assertEquals(70, plan(listOf(t), "2026-10-08T17:00").chunksOf("teams:t").sumOf { it.minutes })
        // The percentage not moved: the session's minutes come off.
        val unmoved = t.copy(sourceProgress = 0.0)
        assertEquals(70, plan(listOf(unmoved), "2026-10-08T17:00").chunksOf("teams:t").sumOf { it.minutes })
    }

    @Test
    fun `minutes of a session stopped early come off the next steps`() {
        val steps = listOf(SubStep("A", 30), SubStep("B", 30))
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps, worked = 20)), "2026-10-08T17:00")
        assertEquals(listOf(10, 30), plan.chunksOf("teams:t").map { it.minutes })
    }

    @Test
    fun `sub-steps are the chunks, done ones skipped`() {
        val steps = listOf(SubStep("Q1-8", 25, done = true), SubStep("Q9-16", 25), SubStep("mark", 10))
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps)), "2026-10-08T17:00")
        assertEquals(listOf("Q9-16", "mark"), plan.chunksOf("teams:t").map { it.step })
    }

    @Test
    fun `a vocabulary step is left out where an Anki deck task holds its sections, and planned where none does`() {
        val steps = listOf(SubStep("Learn vocabulary 2.2", 20, ankiSections = listOf("2.2")), SubStep("Exercise 4", 25))
        val homework = task("t", Fixtures.at("2026-10-20T09:00"), steps = steps)
        assertEquals(listOf("Learn vocabulary 2.2", "Exercise 4"), plan(listOf(homework), "2026-10-08T17:00").chunksOf("teams:t").map { it.step })
        val deck = TaskItem(
            id = "anki:deck:1",
            source = Source.Anki,
            sourceId = "deck:1",
            title = "Learn Anki deck 2.2",
            kind = Kind.Homework,
            derived = true,
            firstSeenAt = Fixtures.at("2026-10-07T12:00"),
            lastSeenAt = Fixtures.at("2026-10-07T12:00"),
            extra = mapOf("deckName" to "Textbook 1::2.2", "for" to "teams:t"),
        )
        assertEquals(listOf("Exercise 4"), plan(listOf(homework, deck), "2026-10-08T17:00").chunksOf("teams:t").map { it.step })
        // Another textbook's deck holds nothing for this one.
        val oldBook = deck.copy(extra = mapOf("deckName" to "Textbook 2::2.2", "for" to "teams:t"))
        assertEquals(listOf("Learn vocabulary 2.2", "Exercise 4"), plan(listOf(homework, oldBook), "2026-10-08T17:00").chunksOf("teams:t").map { it.step })
        // A deck task missed (the deck gone) holds nothing: the step is planned again.
        val missed = deck.copy(status = Status.Missed)
        assertEquals(listOf("Learn vocabulary 2.2", "Exercise 4"), plan(listOf(homework, missed), "2026-10-08T17:00").chunksOf("teams:t").map { it.step })
    }

    @Test
    fun `undated work has a soft deadline a week on, and goes after real deadlines`() {
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-02T09:00"))
        val homework = task("hw", Fixtures.at("2026-10-08T21:00"), effort = 30)
        val plan = plan(listOf(email, homework), "2026-10-08T17:00")
        // The email's soft deadline is tomorrow morning, a week after it was first seen: it's
        // today's, but after the homework.
        assertEquals(listOf("teams:hw", "teams:email"), plan.todayBucket!!.chunks.map { it.taskId })
        assertTrue(plan.todayBucket!!.chunks.last().soft)
        assertEquals(Fixtures.at("2026-10-09T09:00"), plan.todayBucket!!.chunks.last().deadline)
    }

    @Test
    fun `undated work first seen together spreads back over the week, an hour a day at most`() {
        val emails = (1..10).map { task("email$it", null, effort = 10, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-08T23:00")) }
        val plan = plan(emails, "2026-10-08T23:00")
        val perDay = plan.buckets.associate { b -> b.date to b.chunks.sumOf { it.minutes } }.filterValues { it > 0 }
        assertTrue(perDay.values.all { it <= 60 }, "$perDay")
        assertEquals(100, perDay.values.sum())
        // Due 15 Oct, so planned on the 14th first, then backwards.
        assertEquals(LocalDate.parse("2026-10-14"), perDay.keys.max())
        assertFalse(plan.pressure)
    }

    @Test
    fun `an overdue Anki deck goes a step a day, as the deck releases its cards`() {
        val steps = listOf(SubStep("20 new cards", 9), SubStep("20 new cards", 9), SubStep("5 new cards", 3))
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = steps).copy(stepsPerDay = 1)
        val plan = plan(listOf(deck), "2026-10-09T17:00")
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11").map(LocalDate::parse), plan.dayOf("deck"))
        assertTrue(plan.ordered.all { it.overdue })
    }

    @Test
    fun `a deck due before its steps can all be done runs past its deadline, behind, still in order`() {
        // Due Saturday 09:00: Friday is the last usable day, so only Thursday and Friday are before it.
        val steps = (1..3).map { SubStep("20 new cards", 9) }
        val deck = task("deck", Fixtures.at("2026-10-10T09:00"), steps = steps).copy(stepsPerDay = 1)
        val chunks = plan(listOf(deck), "2026-10-08T17:00").chunksOf("teams:deck")
        assertEquals(listOf(1, 2, 3), chunks.map { it.part })
        assertEquals(listOf(false, false, true), chunks.map { it.behind })
    }

    @Test
    fun `undated work past its allowance moves on, not into today`() {
        // Ten 10-minute emails whose soft deadline is today: an hour today, the rest tomorrow.
        val emails = (1..10).map { task("email$it", null, effort = 10, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-01T23:00")) }
        val plan = plan(emails, "2026-10-08T17:00")
        assertEquals(60, plan.todayBucket!!.plannedMin)
        assertEquals(40, plan.tomorrowBucket!!.plannedMin)
    }

    @Test
    fun `undated work whose week has gone by is planned within the next, never overdue`() {
        // First seen 24 Sep 09:00: its week ended on 1 Oct, the next on 8 Oct at 09:00. At 22:10 that
        // night it's in its third, to 15 Oct 09:00 (PL-14): planned by then, not tonight after its
        // hours, and not overdue.
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-09-24T09:00"))
        val plan = plan(listOf(email), "2026-10-08T22:10")
        val chunk = plan.ordered.single()
        assertEquals(Fixtures.at("2026-10-15T09:00"), chunk.deadline)
        assertFalse(chunk.overdue)
        assertEquals(Why.Soft, chunk.why)
        assertTrue(plan.dayOf("email").single() in LocalDate.parse("2026-10-09")..LocalDate.parse("2026-10-14"), "${plan.dayOf("email")}")
    }

    // --- Dates the app set are elastic; only a real deadline is overdue (PL-14) ---

    @Test
    fun `a step whose date has passed is re-fitted, not overdue`() {
        // Claude's blocks: the reply's date went by on Monday, the form's is next Friday, and the
        // task (from Teams) is due the Friday after. The reply is done with the task's own, before
        // the form, not overdue into today.
        val steps = listOf(SubStep("Reply", 20, dueAt = Fixtures.at("2026-10-05T23:59")), SubStep("Form", 30, dueAt = Fixtures.at("2026-10-16T23:59")))
        val plan = plan(listOf(task("e", dueAt = Fixtures.at("2026-10-23T23:59"), steps = steps)), "2026-10-08T17:00")
        fun piece(step: String) = plan.chunksOf("teams:e").single { it.step == step }
        fun dayOf(step: String) = plan.buckets.first { b -> b.chunks.any { it.step == step } }.date
        assertFalse(piece("Reply").overdue)
        assertEquals(Fixtures.at("2026-10-23T23:59"), piece("Reply").dueAt)
        assertTrue(dayOf("Reply") <= dayOf("Form"), "reply ${dayOf("Reply")}, form ${dayOf("Form")}")
        assertTrue(plan.ordered.none { it.overdue })
    }

    @Test
    fun `an email deadline the model guessed never shows overdue and is re-planned within a week when it passes`() {
        // Claude read Monday into it without quoting the email: a plan. Monday gone, it's undated
        // again, to the Monday after, and planned in that week.
        val guessed = Fixtures.at("2026-10-05T23:59")
        val unread = task("e", dueAt = guessed, kind = Kind.Admin, effort = 15).copy(id = "gmail:e", source = Source.Gmail)
        val reading = Enrichment(inputHash = Enrichments.inputHash(unread), by = "claude", at = Fixtures.at("2026-10-01T12:00"), kind = Kind.Admin, deadline = guessed, deadlineQuoted = false)
        val email = unread.copy(enrichment = reading)
        assertFalse(email.hardDeadline)
        val plan = plan(listOf(email), "2026-10-08T17:00")
        val chunk = plan.ordered.single()
        assertFalse(chunk.overdue)
        assertTrue(chunk.soft)
        assertEquals(Fixtures.at("2026-10-12T23:59"), chunk.deadline)
        val day = plan.buckets.single { b -> b.chunks.isNotEmpty() }.date
        assertTrue(day in LocalDate.parse("2026-10-08")..LocalDate.parse("2026-10-12"), "$day")
        // The same date quoted from the email is a deadline: overdue, and today's.
        val quoted = email.copy(enrichment = reading.copy(deadlineQuoted = true))
        assertTrue(quoted.hardDeadline)
        val late = plan(listOf(quoted), "2026-10-08T17:00").ordered.single()
        assertTrue(late.overdue)
        assertEquals(Why.Overdue, late.why)
        // A quote in a reading of what the email said before (a reply since): not the email's words now.
        assertFalse(quoted.copy(detail = "A reply came since").hardDeadline)
    }

    @Test
    fun `a test date Claude read before an assignment's deadline is a plan, the assignment's own holding once it passes`() {
        // Teams says Friday 23 Oct; Claude read a test on Tuesday 6 Oct into its instructions.
        val own = Fixtures.at("2026-10-23T23:59")
        val test = Fixtures.at("2026-10-06T08:30")
        val assignment = task("prep", test, effort = 60).copy(sourceValues = SourceValues(Kind.Homework, own, null))
        assertFalse(assignment.hardDeadline)
        assertEquals(own, assignment.realDeadline)
        // The deadline in force: the test until it passes, then Teams' own.
        assertEquals(test, assignment.deadlineAt(Fixtures.at("2026-10-05T17:00")))
        assertFalse(assignment.hardAt(Fixtures.at("2026-10-05T17:00")))
        assertEquals(own, assignment.deadlineAt(Fixtures.at("2026-10-08T17:00")))
        assertTrue(assignment.hardAt(Fixtures.at("2026-10-08T17:00")))
        // Before the test, it's planned by it; after, by Teams' own date, which can still be missed.
        assertTrue(plan(listOf(assignment), "2026-10-05T17:00").ordered.all { it.deadline == test && !it.overdue })
        val after = plan(listOf(assignment), "2026-10-08T17:00")
        assertTrue(after.ordered.isNotEmpty() && after.ordered.all { it.deadline == own && it.dueAt == own && !it.overdue && !it.soft })
        assertTrue(plan(listOf(assignment), "2026-10-24T17:00").ordered.all { it.overdue })
    }

    @Test
    fun `a Teams deadline passed is overdue and placed first`() {
        val late = task("late", Fixtures.at("2026-10-05T23:59"), effort = 30)
        val later = task("later", Fixtures.at("2026-10-20T23:59"), effort = 30)
        assertTrue(late.hardDeadline)
        val plan = plan(listOf(later, late), "2026-10-08T17:00")
        assertEquals("teams:late", plan.next!!.taskId)
        assertTrue(plan.chunksOf("teams:late").all { it.overdue && it.why == Why.Overdue })
        assertEquals(listOf(LocalDate.parse("2026-10-08")), plan.dayOf("late"))
    }

    @Test
    fun `an undated chunk bigger than the allowance still finds a day`() {
        val big = task("big", null, effort = 90, kind = Kind.Admin, steps = listOf(SubStep("all of it", 90)))
        val plan = plan(listOf(big), "2026-10-08T17:00")
        assertEquals(1, plan.ordered.size)
        assertFalse(plan.ordered.single().behind)
    }

    @Test
    fun `work that can't start yet is placed from when it can`() {
        val steps = listOf(SubStep("20 new cards", 9), SubStep("5 new cards", 3))
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = steps).copy(stepsPerDay = 1, notBefore = Fixtures.at("2026-10-09T04:00"))
        val plan = plan(listOf(deck), "2026-10-08T17:00")
        assertEquals(listOf("2026-10-09", "2026-10-10").map(LocalDate::parse), plan.dayOf("deck"))
    }

    @Test
    fun `a deck needing more days than the horizon still gets a step a day`() {
        // 400 unseen cards: 20 days of 20, overdue.
        val steps = (1..20).map { SubStep("20 new cards", 9) }
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = steps).copy(stepsPerDay = 1)
        val days = plan(listOf(deck), "2026-10-08T17:00").dayOf("deck")
        assertEquals(20, days.size)
        assertEquals(LocalDate.parse("2026-10-27"), days.last())
    }

    @Test
    fun `steps that run past the deadline stay in order, so the most is done before it`() {
        // Due Saturday 09:00, so Friday is the last usable day; Thursday and Friday have room for
        // one step each (a step a day): the first two, 40 cards, before the deadline, 5 after.
        val steps = listOf(SubStep("20 new cards", 9), SubStep("20 new cards", 9), SubStep("5 new cards", 3))
        val deck = task("deck", Fixtures.at("2026-10-10T09:00"), steps = steps).copy(stepsPerDay = 1)
        val plan = plan(listOf(deck), "2026-10-08T17:00")
        val byDay = plan.buckets.filter { it.chunks.isNotEmpty() }.map { it.date.toString() to it.chunks.single().minutes }
        assertEquals(listOf("2026-10-08" to 9, "2026-10-09" to 9, "2026-10-10" to 3), byDay)
        assertEquals(listOf(1, 2, 3), plan.chunksOf("teams:deck").map { it.part })
        assertEquals(listOf(false, false, true), plan.chunksOf("teams:deck").map { it.behind })
    }

    @Test
    fun `undated work never breaks its daily allowance, even at the horizon`() {
        // 61 quarter-hour emails seen at once: four a day fills all 15 days to the horizon, and
        // the 61st goes unplanned rather than onto a day already at its hour.
        val emails = (1..61).map { task("email$it", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-08T17:00")) }
        val plan = plan(emails, "2026-10-08T17:00")
        val perDay = plan.buckets.map { b -> b.chunks.sumOf { it.minutes } }
        assertTrue(perDay.all { it <= 60 }, "$perDay")
        assertEquals(15, plan.buckets.size)
        assertEquals(60, plan.ordered.size)
    }

    @Test
    fun `work after its calibrated last usable day is behind, even when that day has gone`() {
        // Due tomorrow evening with a two-day margin: its last usable day was yesterday.
        val margins = Calibration(marginDays = mapOf(Kind.Homework to 2))
        val essay = task("essay", Fixtures.at("2026-10-09T21:00"), effort = 30)
        val plan = Planner.plan(Planner.Input(listOf(essay), Fixtures.at("2026-10-08T17:00"), LONDON, settings, calibration = margins))
        assertEquals(listOf(LocalDate.parse("2026-10-08")), plan.dayOf("essay"))
        assertTrue(plan.ordered.single().behind)
    }

    @Test
    fun `a soft deadline is a week of calendar days, across the clocks changing`() {
        // First seen 23:30 on 22 March 2026; the clocks go forward on the 29th. A week on is
        // 23:30 on the 29th, not 00:30 on the 30th.
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-03-22T23:30"))
        val plan = plan(listOf(email), "2026-03-23T17:00")
        assertEquals(Fixtures.at("2026-03-29T23:30"), plan.ordered.single().deadline)
    }

    @Test
    fun `work that can't be started until later today isn't next until then`() {
        // 01:00: the deck's new cards come at 04:00, Anki's new day. It's still today's work, and
        // first, but the essay (due after tomorrow, so not ahead of the overdue deck) is the thing
        // to do now.
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = listOf(SubStep("20 new cards", 9))).copy(stepsPerDay = 1, notBefore = Fixtures.at("2026-10-09T04:00"))
        val essay = task("essay", Fixtures.at("2026-10-12T21:00"), effort = 30)
        val plan = plan(listOf(deck, essay), "2026-10-09T01:00")
        assertEquals("teams:deck", plan.todayBucket!!.chunks.first().taskId)
        assertEquals(Fixtures.at("2026-10-09T04:00"), plan.todayBucket!!.chunks.first().availableAt)
        assertEquals("teams:essay", plan.next!!.taskId)
        assertEquals("teams:deck", plan.then!!.taskId)
        // With nothing else to do, it's next all the same.
        assertEquals("teams:deck", plan(listOf(deck), "2026-10-09T01:00").next!!.taskId)
        // From 04:00 it can be started: nothing waits.
        assertNull(plan(listOf(deck), "2026-10-09T04:00").next!!.availableAt)
    }

    @Test
    fun `a per-day step that misses its deadline goes to a later day with time, not a full one`() {
        // 22:10 on Thursday: Thursday has no time left. Due Saturday 09:00, so Friday is the last
        // usable day: one step there, the other two on Saturday and Sunday, none tonight.
        val steps = (1..3).map { SubStep("20 new cards", 9) }
        val deck = task("deck", Fixtures.at("2026-10-10T09:00"), steps = steps).copy(stepsPerDay = 1)
        val days = plan(listOf(deck), "2026-10-08T22:10").dayOf("deck")
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11").map(LocalDate::parse), days)
    }

    @Test
    fun `an overdue deck's steps wait for a day with time, not tonight after its hours`() {
        // 22:10 on Thursday, the evening's hours gone: Friday, Saturday and Sunday.
        val steps = (1..3).map { SubStep("20 new cards", 9) }
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = steps).copy(stepsPerDay = 1)
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11").map(LocalDate::parse), plan(listOf(deck), "2026-10-08T22:10").dayOf("deck"))
    }

    @Test
    fun `work due today that can only start tomorrow is behind`() {
        // Due tonight, but the deck's next cards come at 04:00 tomorrow.
        val deck = task("deck", Fixtures.at("2026-10-08T23:00"), steps = listOf(SubStep("20 new cards", 9)))
            .copy(stepsPerDay = 1, notBefore = Fixtures.at("2026-10-09T04:00"))
        val plan = plan(listOf(deck), "2026-10-08T17:00")
        assertEquals(listOf(LocalDate.parse("2026-10-09")), plan.dayOf("deck"))
        val chunk = plan.ordered.single()
        assertTrue(chunk.behind)
        assertTrue(chunk.dueToday)
    }

    @Test
    fun `work whose last usable day comes first is placed first`() {
        // The essay is due later but has a three-day margin, so its last usable day (Wednesday)
        // comes before the other's (Friday): it claims its days first, Tuesday the earlier of its
        // two equal evenings, and the form's three pieces fit after it.
        val margins = Calibration(marginDays = mapOf(Kind.Admin to 0, Kind.Homework to 3))
        val admin = task("form", Fixtures.at("2026-10-16T23:00"), kind = Kind.Admin, steps = listOf(SubStep("a", 310), SubStep("b", 310), SubStep("c", 310)))
        val essay = task("essay", Fixtures.at("2026-10-17T09:00"), steps = listOf(SubStep("all", 310)))
        val input = Planner.Input(listOf(admin, essay), Fixtures.at("2026-10-13T16:45"), LONDON, settings, calibration = margins)
        val plan = Planner.plan(input)
        assertEquals(listOf(LocalDate.parse("2026-10-13")), plan.dayOf("essay"))
        assertTrue(plan.ordered.none { it.behind })
    }

    @Test
    fun `hours mean wall-clock times on the days the clocks change`() {
        // The clocks go back at 02:00 on Sunday 25 Oct 2026: 08:30-22:30 is still 14 hours.
        val sunday = LocalDate.parse("2026-10-25")
        val input = Planner.Input(emptyList(), Fixtures.at("2026-10-24T12:00"), LONDON, settings)
        assertEquals(14 * 60, Planner.capacity(sunday, input))
        // And on the day itself, at 09:30, an hour of it has gone.
        assertEquals(13 * 60, Planner.capacity(sunday, input.copy(now = Fixtures.at("2026-10-25T09:30"))))
    }

    @Test
    fun `overlapping calendar blocks are taken off once`() {
        val thursday = LocalDate.parse("2026-10-08")
        val busy = listOf(
            Busy(Fixtures.at("2026-10-08T18:00"), Fixtures.at("2026-10-08T19:00")),
            Busy(Fixtures.at("2026-10-08T18:30"), Fixtures.at("2026-10-08T19:30")),
        )
        val input = Planner.Input(emptyList(), Fixtures.at("2026-10-08T08:00"), LONDON, settings, busy = busy)
        assertEquals(315 - 90, Planner.capacity(thursday, input))
    }

    @Test
    fun `events and hidden tasks aren't placed`() {
        val event = task("open day", Fixtures.at("2026-10-10T09:00"), effort = 0, kind = Kind.Event)
        val hidden = task("later", Fixtures.at("2026-10-20T09:00"), available = Fixtures.at("2026-10-15T00:00"))
        val plan = plan(listOf(event, hidden), "2026-10-08T17:00")
        assertTrue(plan.ordered.isEmpty())
        assertEquals(listOf("teams:open day"), plan.events.map { it.id })
        assertNull(plan.next)
    }

    @Test
    fun `capacity is your hours, less the part of today gone and the calendar's`() {
        val input = { at: String, busy: List<Busy>, loads: Map<LocalDate, Int> ->
            Planner.Input(emptyList(), Fixtures.at(at), LONDON, settings, busy = busy, dayLoads = loads)
        }
        val thursday = LocalDate.parse("2026-10-08")
        val saturday = LocalDate.parse("2026-10-10")
        assertEquals(315, Planner.capacity(thursday, input("2026-10-08T08:00", emptyList(), emptyMap())))
        assertEquals(255, Planner.capacity(thursday, input("2026-10-08T17:45", emptyList(), emptyMap())))
        assertEquals(0, Planner.capacity(thursday, input("2026-10-08T22:30", emptyList(), emptyMap())))
        assertEquals(840, Planner.capacity(saturday, input("2026-10-08T08:00", emptyList(), emptyMap())))
        val drums = Busy(Fixtures.at("2026-10-08T18:00"), Fixtures.at("2026-10-08T19:00"))
        assertEquals(255, Planner.capacity(thursday, input("2026-10-08T08:00", listOf(drums), emptyMap())))
        assertEquals(660, Planner.capacity(saturday, input("2026-10-08T08:00", emptyList(), mapOf(saturday to 180))))
    }

    // --- Windows: study periods, break and lunch, the evening (PL-15) ---

    /** Tuesday 13 Oct as a school day: break, lunch either side of eating, a study period, the evening. */
    private val tuesday = LocalDate.parse("2026-10-13")
    private val schoolDay = listOf(
        DayWindow(645, 675, WindowKind.Break), DayWindow(795, 820, WindowKind.Lunch), DayWindow(835, 875, WindowKind.Lunch),
        DayWindow(875, 930, WindowKind.Study), DayWindow(1005, 1320, WindowKind.Evening),
    )

    private fun windowed(tasks: List<TaskItem>, at: String, windows: Map<LocalDate, List<DayWindow>> = mapOf(tuesday to schoolDay), busy: List<Busy> = emptyList()) =
        Planner.Input(tasks, Fixtures.at(at), LONDON, settings, busy = busy, windows = windows)

    @Test
    fun `a day with windows has each piece at a time, the next being the slot under way or the one after`() {
        // Tuesday 16:50, two half hours for home due tomorrow night: tonight from 16:50, a break between.
        val tasks = listOf(task("a", Fixtures.at("2026-10-14T23:59"), effort = 30), task("b", Fixtures.at("2026-10-14T23:59"), effort = 30))
        val plan = Planner.plan(windowed(tasks, "2026-10-13T16:50"))
        val slots = plan.todayBucket!!.slots
        fun hm(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(LONDON).toLocalTime().toString()
        assertEquals(listOf("16:50–17:20", "17:25–17:55"), slots.map { hm(it.start) + "–" + hm(it.end) })
        assertEquals(slots[0].chunk, plan.next)
        assertEquals(slots[1].chunk, plan.then)
        // At 17:05, with the first under way where the last plan put it: still the next, and kept there.
        val starts = slots.associate { it.chunk!!.key to it.start }
        val later = Planner.plan(windowed(tasks, "2026-10-13T17:05").copy(previousSlots = starts))
        assertEquals(slots.map { it.start }, later.todaySlots.map { it.start })
        assertEquals(slots[0].chunk!!.key, later.next!!.key)
        assertEquals(slots[1].chunk!!.key, later.then!!.key)
        // Without windows, no slots: the day's pieces in order, as before.
        assertTrue(Planner.plan(windowed(tasks, "2026-10-13T16:50", windows = emptyMap())).todayBucket!!.slots.isEmpty())
    }

    @Test
    fun `capacity sums a day's windows, the evening's being what home work has`() {
        // 30 + 25 + 40 + 55 at school, 315 in the evening.
        assertEquals(Planner.Capacity(315, 465), Planner.capacityOf(tuesday, windowed(emptyList(), "2026-10-13T08:00")))
        // A school window with less than 15 minutes left isn't used: a meeting through most of break.
        val meeting = Busy(Fixtures.at("2026-10-13T10:45"), Fixtures.at("2026-10-13T11:05"))
        assertEquals(Planner.Capacity(315, 435), Planner.capacityOf(tuesday, windowed(emptyList(), "2026-10-13T08:00", busy = listOf(meeting))))
        // After school, only the evening is left.
        assertEquals(Planner.Capacity(315, 315), Planner.capacityOf(tuesday, windowed(emptyList(), "2026-10-13T15:45")))
    }

    @Test
    fun `home work never takes school time, and work done anywhere takes school's first`() {
        // The evening taken by a lesson: only school's 150 minutes are free on Tuesday.
        val drums = Busy(Fixtures.at("2026-10-13T16:45"), Fixtures.at("2026-10-13T22:00"))
        val essay = task("essay", Fixtures.at("2026-10-13T23:59"), effort = 30)
        val plan = Planner.plan(windowed(listOf(essay), "2026-10-13T08:00", busy = listOf(drums)))
        // Teams homework is done at home: none of it at school, so it's behind tonight.
        assertTrue(plan.chunksOf("teams:essay").all { it.where == Where.Home })
        assertEquals(Planner.Capacity(0, 150), Planner.capacityOf(tuesday, windowed(emptyList(), "2026-10-13T08:00", busy = listOf(drums))))
        // A reading done anywhere, and the essay at home, both due tonight: the reading takes school's
        // time, so the evening holds the essay whole.
        val reading = task("reading", Fixtures.at("2026-10-13T23:59"), effort = 60, kind = Kind.Admin)
        val both = Planner.plan(windowed(listOf(reading, task("essay", Fixtures.at("2026-10-13T23:59"), effort = 300)), "2026-10-13T08:00"))
        assertTrue(both.chunksOf("teams:essay").none { it.behind }, "${both.chunksOf("teams:essay").map { it.minutes to it.behind }}")
        assertTrue(both.chunksOf("teams:reading").all { it.where == Where.Anywhere })
    }

    @Test
    fun `a step can say it's done anywhere, its task's other steps at home`() {
        val steps = listOf(SubStep("Read the chapter", 30, where = "anywhere"), SubStep("Write it up", 40))
        val plan = Planner.plan(windowed(listOf(task("t", Fixtures.at("2026-10-20T23:59"), steps = steps)), "2026-10-13T08:00"))
        assertEquals(listOf(Where.Anywhere, Where.Home), plan.chunksOf("teams:t").map { it.where })
        // In order still: the reading on or before the write-up's day.
        val days = plan.buckets.flatMap { b -> b.chunks.filter { it.taskId == "teams:t" }.map { b.date } }
        assertEquals(days.sorted(), days)
    }

    @Test
    fun `a same-day deadline is usable where a window comes before it`() {
        // No margin: due at 11:30, Tuesday's break (10:45) comes before it, so Tuesday is its day;
        // without windows, a morning deadline's day isn't one, and Monday it is.
        val noMargin = Calibration(marginDays = mapOf(Kind.Admin to 0))
        val form = task("form", Fixtures.at("2026-10-13T11:30"), effort = 20, kind = Kind.Admin)
        assertEquals(listOf(tuesday), Planner.plan(windowed(listOf(form), "2026-10-12T17:00").copy(calibration = noMargin)).dayOf("form"))
        assertEquals(listOf(LocalDate.parse("2026-10-12")), Planner.plan(windowed(listOf(form), "2026-10-12T17:00", windows = emptyMap()).copy(calibration = noMargin)).dayOf("form"))
        // Due at 10:30, before every window that day: Monday again.
        val early = form.copy(dueAt = Fixtures.at("2026-10-13T10:30"))
        assertEquals(listOf(LocalDate.parse("2026-10-12")), Planner.plan(windowed(listOf(early), "2026-10-12T17:00").copy(calibration = noMargin)).dayOf("form"))
    }

    @Test
    fun `capacity is scaled by the day's kind's yield`() {
        // Weekends found to use half their free time (PL-06): Saturday's 840 minutes plan as 420;
        // Thursday's evening is as it was. A limit you set is of the work, after the yield.
        val halfWeekends = Calibration(yields = mapOf(DayKind.Weekend to 0.5))
        fun input(caps: Map<LocalDate, Int> = emptyMap()) = Planner.Input(emptyList(), Fixtures.at("2026-10-08T08:00"), LONDON, settings, calibration = halfWeekends, dayCaps = caps)
        val thursday = LocalDate.parse("2026-10-08")
        val saturday = LocalDate.parse("2026-10-10")
        assertEquals(420, Planner.capacity(saturday, input()))
        assertEquals(315, Planner.capacity(thursday, input()))
        assertEquals(300, Planner.capacity(saturday, input(mapOf(saturday to 300))))
        assertEquals(420, Planner.capacity(saturday, input(mapOf(saturday to 600))))
        // The plan holds no more than that.
        val big = task("big", Fixtures.at("2026-10-12T08:30"), effort = 600, steps = listOf(SubStep("all", 600)))
        val plan = Planner.plan(input().copy(tasks = listOf(big)))
        assertEquals(420, plan.bucket(saturday)!!.capacityMin)
    }

    @Test
    fun `with no margin, a morning deadline's own day is never usable`() {
        // Due Sunday 09:00 with no margin, Friday 17:00, Saturday mostly taken: Sunday would be the
        // roomiest day, but a morning deadline's own day isn't one to work on. Friday has most.
        val noMargin = Planner.Input(
            listOf(task("t", Fixtures.at("2026-10-11T09:00"))),
            Fixtures.at("2026-10-09T17:00"),
            LONDON,
            settings,
            calibration = Calibration(marginDays = mapOf(Kind.Homework to 0)),
            dayLoads = mapOf(LocalDate.parse("2026-10-10") to 600),
        )
        assertEquals(LocalDate.parse("2026-10-09"), Planner.plan(noMargin).buckets.single { it.chunks.isNotEmpty() }.date)
    }

    @Test
    fun `calibration stretches a kind's estimate`() {
        val input = Planner.Input(
            listOf(task("t", Fixtures.at("2026-10-20T09:00"), effort = 40)),
            Fixtures.at("2026-10-08T17:00"),
            LONDON,
            settings,
            calibration = Calibration(multipliers = mapOf(Calibration.key(Kind.Homework, null) to 1.5)),
        )
        assertEquals(60, Planner.plan(input).ordered.sumOf { it.minutes })
    }

    @Test
    fun `the order within a day is stable and keeps a task's parts together`() {
        val tasks = listOf(
            task("b", Fixtures.at("2026-10-08T21:00"), effort = 90),
            task("a", Fixtures.at("2026-10-08T21:00"), effort = 30),
        )
        val first = plan(tasks, "2026-10-08T17:00").todayBucket!!.chunks.map { it.taskId to it.part }
        val second = plan(tasks.reversed(), "2026-10-08T17:00").todayBucket!!.chunks.map { it.taskId to it.part }
        assertEquals(listOf("teams:a" to 1, "teams:b" to 1, "teams:b" to 2), first)
        assertEquals(first, second)
    }

    @Test
    fun `a source's progress counts once its task has steps, as it does without them`() {
        // A 60-minute Power Planner item at 50 %, in two 30-minute blocks: 30 minutes are left either way.
        val item = task("pp", Fixtures.at("2026-10-20T09:00"), effort = 60, steps = listOf(SubStep("Part A", 30), SubStep("Part B", 30)))
            .copy(sourceProgress = 0.5, firstProgress = 0.0)
        assertEquals(30, plan(listOf(item), "2026-10-08T17:00").ordered.sumOf { it.minutes })
        assertEquals(30, plan(listOf(item.copy(subSteps = emptyList())), "2026-10-08T17:00").ordered.sumOf { it.minutes })
        // Progress and the sessions that made it are the same work: not taken off twice.
        assertEquals(30, plan(listOf(item.copy(workedMin = 30)), "2026-10-08T17:00").ordered.sumOf { it.minutes })
        // Without progress, the steps stand as they are.
        assertEquals(listOf(30, 30), plan(listOf(item.copy(sourceProgress = 0.0)), "2026-10-08T17:00").ordered.map { it.minutes })
    }

    @Test
    fun `a box is cut to fit days you've limited to less than one`() {
        // An hour's work due in ten days, and every day limited to 20 minutes: three pieces of 20,
        // rather than two of 30 that no day holds, and nothing planned.
        val today = LocalDate.parse("2026-10-08")
        val caps = (0L..30).associate { today.plusDays(it) to 20 }
        val input = Planner.Input(listOf(task("t", Fixtures.at("2026-10-18T09:00"), effort = 60)), Fixtures.at("2026-10-08T17:00"), LONDON, settings, dayCaps = caps)
        val plan = Planner.plan(input)
        assertEquals(listOf(20, 20, 20), plan.chunksOf("teams:t").map { it.minutes })
        assertTrue(plan.buckets.all { b -> b.chunks.sumOf { it.minutes } <= 20 })
        // A limit on only some days leaves the box as it is: the others hold it.
        val someDays = Planner.plan(input.copy(dayCaps = mapOf(today.plusDays(2) to 20)))
        assertEquals(listOf(30, 30), someDays.chunksOf("teams:t").map { it.minutes })
        // Limited only up to its deadline, roomy after: boxes that fit before it, not after it, behind.
        val toDeadline = Planner.plan(input.copy(dayCaps = (0L..10).associate { today.plusDays(it) to 20 }))
        val chunks = toDeadline.chunksOf("teams:t")
        assertEquals(listOf(20, 20, 20), chunks.map { it.minutes })
        assertTrue(chunks.none { it.behind })
        assertTrue(toDeadline.buckets.filter { b -> b.chunks.any { it.taskId == "teams:t" } }.all { it.date <= LocalDate.parse("2026-10-17") })
    }

    @Test
    fun `each piece knows its step's place in the list`() {
        val steps = listOf(SubStep("Practice", 30, done = true), SubStep("Practice", 30), SubStep("Write up", 20))
        val chunks = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps)), "2026-10-08T17:00").chunksOf("teams:t")
        assertEquals(listOf(1, 2), chunks.map { it.stepIndex })
    }


    // --- Spreading later work over its window (PL-01) ---

    /** Every day 17:00 to 21:00, weekday or weekend: four hours, so shares are even. */
    private val evenings = Settings(weekdayHours = Window(17 * 60, 21 * 60), weekendHours = Window(17 * 60, 21 * 60), boxMin = 30)

    private fun spread(tasks: List<TaskItem>, at: String, busy: List<Busy> = emptyList(), with: Settings = evenings) =
        Planner.plan(Planner.Input(tasks, Fixtures.at(at), LONDON, with, busy = busy))

    @Test
    fun `a task due in 12 days with 6 pieces gets one every second day, not the last six evenings`() {
        // Monday 08:00, due Saturday week (its last usable day the Thursday before): twelve evenings alike.
        val plan = spread(listOf(task("booklet", Fixtures.at("2026-10-24T23:59"), effort = 180)), "2026-10-12T08:00")
        assertEquals(listOf("2026-10-12", "2026-10-14", "2026-10-16", "2026-10-18", "2026-10-20", "2026-10-22").map(LocalDate::parse), plan.dayOf("booklet"))
        assertTrue(plan.ordered.none { it.behind })
        assertTrue(plan.ordered.all { it.why == Why.Spread })
        assertEquals("Spread over the 12 days to its deadline", plan.ordered.first().whyLine(LocalDate.parse("2026-10-12"), LONDON))
    }

    @Test
    fun `spreading follows free time, a day with a busy evening takes a smaller share`() {
        // Four pieces over four evenings, the last two three-quarters taken: they hold one piece between them.
        val busy = listOf("2026-10-14", "2026-10-15").map { Busy(Fixtures.at("${it}T17:00"), Fixtures.at("${it}T20:00")) }
        val plan = spread(listOf(task("t", Fixtures.at("2026-10-16T23:59"), effort = 120)), "2026-10-12T08:00", busy)
        val days = plan.dayOf("t")
        assertEquals(4, plan.chunksOf("teams:t").size)
        assertEquals(1, days.count { it >= LocalDate.parse("2026-10-14") })
        assertEquals(LocalDate.parse("2026-10-14"), days.last())
        assertTrue(plan.ordered.all { it.why == Why.Spread })
    }

    @Test
    fun `a task with a three-week window is touched at least once a week`() {
        // Two pieces over 21 even evenings: shares would fall ten days apart; the second comes within the week.
        val plan = spread(listOf(task("t", Fixtures.at("2026-11-02T23:59"), effort = 60)), "2026-10-12T08:00")
        val days = plan.dayOf("t")
        assertEquals(2, days.size)
        assertTrue(java.time.temporal.ChronoUnit.DAYS.between(days[0], days[1]) in 1..7, "$days")
        assertEquals(listOf(Why.Spread, Why.MinimumTouch), plan.ordered.map { it.why })
        assertEquals("Kept within a week of its last piece", plan.ordered.last().whyLine(LocalDate.parse("2026-10-12"), LONDON))
    }

    @Test
    fun `revision pieces are at least a day apart`() {
        // Saturday 08:00, three pieces due Tuesday night: Saturday's and Sunday's 840 minutes would
        // take two on Sunday; revision goes on to Monday instead.
        fun pieces(kind: Kind) = plan(listOf(task("t", Fixtures.at("2026-10-20T23:59"), effort = 135, kind = kind)), "2026-10-17T08:00").let { p ->
            p.buckets.flatMap { b -> b.chunks.filter { it.taskId == "teams:t" }.map { b.date.toString() to it.why } }
        }
        assertEquals(listOf("2026-10-17", "2026-10-18", "2026-10-18").map { it to Why.Spread }, pieces(Kind.Homework))
        assertEquals(listOf("2026-10-17" to Why.Spread, "2026-10-18" to Why.Spread, "2026-10-19" to Why.RevisionGap), pieces(Kind.Revision))
    }

    @Test
    fun `a single piece goes to the day with most room, the earlier on a tie`() {
        // Thursday 17:00, due Tuesday 08:30: Saturday and Sunday tie at 840 minutes, Saturday first.
        val due = Fixtures.at("2026-10-13T08:30")
        val roomiest = plan(listOf(task("t", due)), "2026-10-08T17:00")
        assertEquals(listOf(LocalDate.parse("2026-10-10")), roomiest.dayOf("t"))
        assertEquals(Why.MostRoom, roomiest.ordered.single().why)
        assertEquals("The day with most room before Tuesday", roomiest.ordered.single().whyLine(LocalDate.parse("2026-10-08"), LONDON))
        // Saturday mostly taken: Sunday.
        assertEquals(listOf(LocalDate.parse("2026-10-11")), plan(listOf(task("t", due)), "2026-10-08T17:00", loads = mapOf(LocalDate.parse("2026-10-10") to 600)).dayOf("t"))
    }

    @Test
    fun `work due tomorrow is still placed first and takes today's time`() {
        // Thursday 17:00: tomorrow night's essay is today's (its last usable day), before any spreading.
        val essay = task("essay", Fixtures.at("2026-10-09T23:59"), effort = 60)
        val far = task("far", Fixtures.at("2026-10-30T23:59"), effort = 300)
        val plan = plan(listOf(far, essay), "2026-10-08T17:00")
        assertEquals(listOf(LocalDate.parse("2026-10-08")), plan.dayOf("essay"))
        assertEquals("teams:essay", plan.todayBucket!!.chunks.first().taskId)
        assertTrue(plan.chunksOf("teams:essay").all { it.dueSoon && it.why == Why.DueSoon })
        assertEquals("Due tomorrow", plan.chunksOf("teams:essay").first().whyLine(LocalDate.parse("2026-10-08"), LONDON))
        assertTrue(plan.chunksOf("teams:far").none { it.why == Why.DueSoon })
    }

    @Test
    fun `undated work still fills from its soft week backwards`() {
        // First seen now: its soft deadline is a week on (15 Oct), and it sits on its last usable day.
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-08T17:00"))
        val plan = plan(listOf(email), "2026-10-08T17:00")
        assertEquals(listOf(LocalDate.parse("2026-10-14")), plan.dayOf("email"))
        assertEquals(Why.Soft, plan.ordered.single().why)
        assertEquals("Undated: fills spare time this week", plan.ordered.single().whyLine(LocalDate.parse("2026-10-08"), LONDON))
    }

    @Test
    fun `today's bucket alone is pressure`() {
        // Due Saturday morning, Thursday 22:10: tonight's hours are gone, so it's Friday's, not today's.
        val tomorrow = plan(listOf(task("t", Fixtures.at("2026-10-10T09:00"), effort = 30)), "2026-10-08T22:10")
        assertTrue(tomorrow.todayBucket!!.chunks.isEmpty() && tomorrow.tomorrowBucket!!.chunks.isNotEmpty())
        assertFalse(tomorrow.pressure)
        assertTrue(plan(listOf(task("t", Fixtures.at("2026-10-08T21:00"), effort = 30)), "2026-10-08T17:00").pressure)
    }

    // --- A real deadline its days can't hold, said early (PL-02) ---

    @Test
    fun `a task its days can't hold before its deadline is said, with the numbers`() {
        // Thursday 17:00, six hours due Saturday morning: Thursday and Friday have two hours each
        // once the calendar's taken its share. One line, with what's left and what's free.
        val loads = mapOf(LocalDate.parse("2026-10-08") to 180, LocalDate.parse("2026-10-09") to 195)
        val due = Fixtures.at("2026-10-10T09:00")
        val plan = plan(listOf(task("coursework", due, effort = 360)), "2026-10-08T17:00", loads = loads)
        assertEquals(listOf(WontFit("teams:coursework", "coursework", 360, 240, due)), plan.wontFit)
        // Undated work never is: its deadline is the app's own.
        assertEquals(emptyList(), plan(listOf(task("notes", null, effort = 2000, kind = Kind.Admin)), "2026-10-08T17:00", loads = loads).wontFit)
        // Nor is work that fits, or a plan's date (an email's Claude didn't quote).
        assertEquals(emptyList(), plan(listOf(task("short", due, effort = 120)), "2026-10-08T17:00", loads = loads).wontFit)
        val guessed = task("e", due, effort = 360).copy(id = "gmail:e", source = Source.Gmail, enrichment = Enrichment(inputHash = "", by = "claude", at = 0, deadline = due))
        assertEquals(emptyList(), plan(listOf(guessed), "2026-10-08T17:00", loads = loads).wontFit)
    }

    @Test
    fun `a task that opens late tonight weighs tonight by the time after it opens`() {
        // Thursday 17:00, an hour due Saturday 09:00 (its days Thursday and Friday), open from 21:00:
        // tonight has 60 minutes for it, Friday 300 (a quarter of an hour taken). Friday it is.
        val late = task("late", Fixtures.at("2026-10-10T09:00"), effort = 60).copy(notBefore = Fixtures.at("2026-10-08T21:00"))
        val friday = Busy(Fixtures.at("2026-10-09T17:00"), Fixtures.at("2026-10-09T17:15"))
        assertEquals(listOf(LocalDate.parse("2026-10-09")), plan(listOf(late), "2026-10-08T17:00", busy = listOf(friday)).dayOf("late"))
    }

    @Test
    fun `a deadline past the plan's reach is never said not to fit`() {
        // Six months off, more than the plan's 90 days hold: the days after them weren't counted.
        val big = task("big", Fixtures.at("2027-04-15T23:59"), effort = 50_000)
        assertEquals(emptyList(), plan(listOf(big), "2026-10-08T17:00").wontFit)
    }

    @Test
    fun `a task in runs is said not to fit for all of them, against the time they had`() {
        // Two hour-long blocks opening apart, both by Saturday 09:00, with an hour and a half free on
        // Thursday and Friday: 120 minutes left of 90, not the last run's 60 of 30.
        val steps = listOf(SubStep("A", 60, from = Fixtures.at("2026-10-08T00:00")), SubStep("B", 60, from = Fixtures.at("2026-10-08T12:00")))
        val loads = mapOf(LocalDate.parse("2026-10-08") to 255, LocalDate.parse("2026-10-09") to 270)
        val plan = plan(listOf(task("runs", Fixtures.at("2026-10-10T09:00"), steps = steps)), "2026-10-08T17:00", loads = loads)
        assertEquals(listOf(WontFit("teams:runs", "runs", 120, 90, Fixtures.at("2026-10-10T09:00"))), plan.wontFit)
    }

    @Test
    fun `a piece no single day before its deadline holds is said not to fit`() {
        // A 45-minute step, and half an hour free on each of Thursday and Friday before Saturday morning.
        val loads = mapOf(LocalDate.parse("2026-10-08") to 270, LocalDate.parse("2026-10-09") to 285)
        val essay = task("essay", Fixtures.at("2026-10-10T09:00"), steps = listOf(SubStep("Write it", 45)))
        val plan = plan(listOf(essay), "2026-10-08T17:00", loads = loads)
        assertEquals(listOf(WontFit("teams:essay", "essay", 45, 60, Fixtures.at("2026-10-10T09:00"))), plan.wontFit)
        assertEquals(Why.NoRoom, plan.chunksOf("teams:essay").single().why)
    }

    @Test
    fun `revision looks ahead for days to keep its pieces a day apart`() {
        // Ten minutes free on Thursday and on Friday, the evening's whole on Saturday: three ten-minute
        // pieces of revision due Sunday night go one a day, not all on Saturday where the time is.
        val loads = mapOf(LocalDate.parse("2026-10-08") to 290, LocalDate.parse("2026-10-09") to 305)
        val revision = task("rev", Fixtures.at("2026-10-11T23:59"), kind = Kind.Revision, steps = (1..3).map { SubStep("Topic $it", 10) })
        assertEquals(listOf("2026-10-08", "2026-10-09", "2026-10-10").map(LocalDate::parse), plan(listOf(revision), "2026-10-08T17:00", loads = loads).dayOf("rev"))
    }

    @Test
    fun `a homework deck's date is a deadline only where its assignments' is`() {
        val due = Fixtures.at("2026-10-12T09:00")
        val deck = TaskItem(
            id = "anki:deck", source = Source.Anki, sourceId = AnkiRules.DECK_PREFIX + "1", title = "Anki: Textbook 1::1.2", dueAt = due,
            kind = Kind.Revision, sourceEffortMin = 9, derived = true, extra = mapOf(AnkiRules.EXTRA_FOR to "teams:a"),
            sourceValues = SourceValues(Kind.Revision, due, null), firstSeenAt = 0, lastSeenAt = 0,
        )
        // Its date a test date Claude read for its assignment, none of theirs a deadline: a plan.
        assertNull(deck.realDeadline)
        assertFalse(deck.hardDeadline)
        // Its assignment's own deadline, read with it: a deadline, in force once passed.
        val hard = deck.copy(extra = deck.extra + (AnkiRules.EXTRA_REAL to due.toString()))
        assertEquals(due, hard.realDeadline)
        assertEquals(due, hard.deadlineAt(Fixtures.at("2026-10-13T17:00")))
        assertTrue(hard.hardAt(Fixtures.at("2026-10-13T17:00")))
    }

    @Test
    fun `work not open yet is said not to fit where its days from its opening can't hold it`() {
        // Opens Friday 17:00, due Saturday 09:00: Friday's 300 minutes from then, for six hours.
        val app = task("app", Fixtures.at("2026-10-10T09:00"), effort = 360, available = Fixtures.at("2026-10-09T17:00"))
        assertEquals(listOf(WontFit("teams:app", "app", 360, 300, Fixtures.at("2026-10-10T09:00"))), plan(listOf(app), "2026-10-08T17:00").wontFit)
        // Five hours fit.
        assertEquals(emptyList(), plan(listOf(app.copy(sourceEffortMin = 300)), "2026-10-08T17:00").wontFit)
    }

    @Test
    fun `work a per-day limit carries past its deadline is said not to fit, however much time there is`() {
        // Three steps of a deck one a day, due tomorrow night: the third goes to Saturday, past it.
        val deck = task("deck", Fixtures.at("2026-10-09T23:59"), steps = (1..3).map { SubStep("20 new cards", 9) }).copy(stepsPerDay = 1)
        val plan = plan(listOf(deck), "2026-10-08T17:00")
        assertEquals(listOf("teams:deck"), plan.wontFit.map { it.taskId })
        assertTrue(plan.wontFit.single().leftMin < plan.wontFit.single().freeMin)
    }

    @Test
    fun `work for a test is spaced only as Claude reads the task now`() {
        // Saturday 08:00, three pieces due Tuesday night (as revision pieces are spaced): for a test
        // Claude read in its instructions, a day apart; the reading out of date (the text changed since), not.
        val homework = task("t", Fixtures.at("2026-10-20T23:59"), effort = 135)
        val reading = Enrichment(inputHash = Enrichments.inputHash(homework), by = "claude", at = 0, testDate = Fixtures.at("2026-10-21T08:30"))
        fun days(task: TaskItem) = plan(listOf(task), "2026-10-17T08:00").let { p -> p.buckets.flatMap { b -> b.chunks.filter { it.taskId == "teams:t" }.map { b.date.toString() } } }
        assertEquals(listOf("2026-10-17", "2026-10-18", "2026-10-19"), days(homework.copy(enrichment = reading)))
        assertEquals(listOf("2026-10-17", "2026-10-18", "2026-10-18"), days(homework.copy(enrichment = reading, detail = "Changed since")))
    }

    @Test
    fun `a deck's deadline is the earliest of its assignments' in force`() {
        // A: a test date Claude read gone by, Teams' own on 23 Oct. B: a plan's date to come, 14 Oct.
        // The deck's vocabulary is for B's date first, not A's real one.
        val a = task("a", Fixtures.at("2026-10-06T08:30")).copy(sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-23T23:59"), null))
        val b = task("b", Fixtures.at("2026-10-14T08:30")).copy(sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-27T23:59"), null))
        val deck = TaskItem(
            id = "anki:deck", source = Source.Anki, sourceId = AnkiRules.DECK_PREFIX + "1", title = "Anki: Textbook 1::1.2", dueAt = Fixtures.at("2026-10-23T23:59"),
            kind = Kind.Revision, sourceEffortMin = 9, derived = true, subSteps = listOf(SubStep("20 new cards", 9)),
            extra = mapOf(AnkiRules.EXTRA_FOR to "teams:a,teams:b", AnkiRules.EXTRA_DECK_NAME to "Textbook 1::1.2"),
            firstSeenAt = Fixtures.at("2026-10-07T12:00"), lastSeenAt = Fixtures.at("2026-10-07T12:00"),
        )
        val chunk = plan(listOf(a, b, deck), "2026-10-08T17:00").chunksOf("anki:deck").single()
        assertEquals(Fixtures.at("2026-10-14T08:30"), chunk.deadline)
        assertFalse(chunk.overdue)
    }

    @Test
    fun `work inside its safety margin that still fits before its deadline isn't said not to fit`() {
        // Homework finished three days early, as learned: an hour due Saturday morning is behind
        // that margin on Thursday, but Thursday and Friday hold it before the deadline itself.
        val late = Calibration(marginDays = mapOf(Kind.Homework to 3))
        val input = Planner.Input(listOf(task("essay", Fixtures.at("2026-10-10T09:00"), effort = 60)), Fixtures.at("2026-10-08T17:00"), LONDON, settings, calibration = late)
        val plan = Planner.plan(input)
        assertTrue(plan.ordered.all { it.behind })
        assertEquals(emptyList(), plan.wontFit)
        // Behind only the margin: its pieces say why they're where they are, not that there's no room.
        assertTrue(plan.ordered.none { it.why == Why.NoRoom }, "${plan.ordered.map { it.why }}")
        // One that can only start at 21:00 has the hour after it, not the evening.
        val opening = task("late", Fixtures.at("2026-10-09T09:00"), effort = 120).copy(notBefore = Fixtures.at("2026-10-08T21:00"))
        assertEquals(listOf(WontFit("teams:late", "late", 120, 60, Fixtures.at("2026-10-09T09:00"))), Planner.plan(input.copy(tasks = listOf(opening))).wontFit)
        // Due this morning, with its hours tonight: none of its time is before its deadline.
        val morning = Planner.plan(input.copy(tasks = listOf(task("prep", Fixtures.at("2026-10-09T08:30"), effort = 60)), now = Fixtures.at("2026-10-09T01:00")))
        assertEquals(listOf(WontFit("teams:prep", "prep", 60, 0, Fixtures.at("2026-10-09T08:30"))), morning.wontFit)
    }

    // --- What's left out, and why (IMP-P1-002) ---

    @Test
    fun `work that can't start yet is listed with why, not dropped without a word`() {
        // Not open till the 15th at its source; and one you said waits for another, still open.
        val later = task("later", Fixtures.at("2026-10-20T09:00"), available = Fixtures.at("2026-10-15T00:00"))
        val first = task("first", Fixtures.at("2026-10-12T09:00"))
        val second = task("second", Fixtures.at("2026-10-13T09:00")).copy(userAfter = "teams:first")
        val plan = plan(listOf(later, first, second), "2026-10-08T17:00")
        assertEquals(listOf(Unplanned.Reason.NotOpen to "later", Unplanned.Reason.Waits to "second"), plan.unplanned.map { it.reason to it.title })
        assertEquals(Fixtures.at("2026-10-15T00:00"), plan.unplanned[0].at)
        assertEquals(40, plan.unplanned[0].minutes)
        assertEquals("first", plan.unplanned[1].after)
        // What's planned isn't listed.
        assertTrue(plan.chunksOf("teams:first").isNotEmpty())
        // Once what it waits for is done, it's planned, not listed.
        val done = plan(listOf(first.copy(status = Status.Done), second), "2026-10-08T17:00")
        assertTrue(done.unplanned.isEmpty())
        assertTrue(done.chunksOf("teams:second").isNotEmpty())
    }

    @Test
    fun `steps a day's limit can't fit by the plan's last day are listed as having no room`() {
        // 95 steps of a deck that goes a step a day, overdue: the plan's 91 days hold 91 of them.
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = (1..95).map { SubStep("5 new cards", 5) }).copy(stepsPerDay = 1)
        val plan = plan(listOf(deck), "2026-10-08T17:00")
        assertEquals(91, plan.chunksOf("teams:deck").size)
        val gap = plan.unplanned.single()
        assertEquals(Unplanned.Reason.NoRoom, gap.reason)
        assertEquals(4, gap.pieces)
        assertEquals(20, gap.minutes)
    }

    @Test
    fun `a run of blocks opening past the plan's reach is listed with when it opens`() {
        val steps = listOf(
            SubStep("Reply", 10),
            SubStep("Apply", 60, from = Fixtures.at("2027-02-01T00:00"), dueAt = Fixtures.at("2027-03-01T09:00")),
        )
        val email = task("email", null, kind = Kind.Admin, steps = steps)
        val plan = plan(listOf(email), "2026-10-08T17:00")
        assertEquals(1, plan.chunksOf("teams:email").size)
        val gap = plan.unplanned.single()
        assertEquals(Unplanned.Reason.OpensLater, gap.reason)
        assertEquals(Fixtures.at("2027-02-01T00:00"), gap.at)
        assertEquals(60, gap.minutes)
    }

    @Test
    fun `a session's piece is its own step, not its task's first`() {
        val steps = listOf(SubStep("Read", 20), SubStep("Write", 30))
        val plan = plan(listOf(task("t", Fixtures.at("2026-10-20T09:00"), steps = steps)), "2026-10-08T17:00")
        assertEquals("Write", plan.pieceOf("teams:t", 1, "Write")?.step)
        // Its step gone from the plan (ticked meanwhile): the task's first.
        assertEquals("Read", plan.pieceOf("teams:t", 7, "Gone")?.step)
    }

    @Test
    fun `a waiting assignment's gap keeps the vocabulary its deck would hold`() {
        // Its vocabulary is a deck's, and the deck waits with it: the gap is the deck's cards (9 minutes)
        // and handing it in (5), not 5 minutes alone.
        val steps = listOf(SubStep("Learn vocabulary 1.2", 40, ankiSections = listOf("1.2")))
        val first = task("first", Fixtures.at("2026-10-12T09:00"))
        val vocab = task("vocab", Fixtures.at("2026-10-13T09:00"), steps = steps).copy(userAfter = "teams:first")
        val deck = TaskItem(
            id = "anki:deck", source = Source.Anki, sourceId = AnkiRules.DECK_PREFIX + "1", title = "Anki: Textbook 1::1.2", dueAt = Fixtures.at("2026-10-13T09:00"),
            kind = Kind.Revision, sourceEffortMin = 40, derived = true, subSteps = listOf(SubStep("20 new cards", 9)),
            extra = mapOf(AnkiRules.EXTRA_FOR to "teams:vocab", AnkiRules.EXTRA_DECK_NAME to "Textbook 1::1.2"),
            firstSeenAt = Fixtures.at("2026-10-07T12:00"), lastSeenAt = Fixtures.at("2026-10-07T12:00"),
        )
        val plan = plan(listOf(first, vocab, deck), "2026-10-08T17:00")
        val gap = plan.unplanned.single { it.taskId == "teams:vocab" }
        assertEquals(14, gap.minutes)
        assertTrue(plan.chunksOf("anki:deck").isEmpty())
    }

    @Test
    fun `a waiting assignment's gap counts only the decks that wait with it`() {
        // One step for two sections: 1.2's deck is also another, open assignment's, and stays on the
        // plan; 1.3's is this one's alone, and waits. The gap is the writing and 1.3's cards.
        fun deck(section: String, forTasks: String) = TaskItem(
            id = "anki:$section", source = Source.Anki, sourceId = AnkiRules.DECK_PREFIX + section, title = "Anki: Textbook 1::$section",
            dueAt = Fixtures.at("2026-10-13T09:00"), kind = Kind.Revision, sourceEffortMin = 40, derived = true, subSteps = listOf(SubStep("20 new cards", 9)),
            extra = mapOf(AnkiRules.EXTRA_FOR to forTasks, AnkiRules.EXTRA_DECK_NAME to "Textbook 1::$section"),
            firstSeenAt = Fixtures.at("2026-10-07T12:00"), lastSeenAt = Fixtures.at("2026-10-07T12:00"),
        )
        val steps = listOf(SubStep("Learn vocabulary 1.2 and 1.3", 60, ankiSections = listOf("1.2", "1.3")), SubStep("Write it up", 30))
        val first = task("first", Fixtures.at("2026-10-12T09:00"))
        val vocab = task("vocab", Fixtures.at("2026-10-13T09:00"), steps = steps).copy(userAfter = "teams:first")
        val other = task("other", Fixtures.at("2026-10-14T09:00"), steps = listOf(SubStep("Learn vocabulary 1.2", 40, ankiSections = listOf("1.2"))))
        val plan = plan(listOf(first, vocab, other, deck("1.2", "teams:vocab,teams:other"), deck("1.3", "teams:vocab")), "2026-10-08T17:00")
        assertTrue(plan.chunksOf("anki:1.2").isNotEmpty())
        assertTrue(plan.chunksOf("anki:1.3").isEmpty())
        assertEquals(30 + 9, plan.unplanned.single { it.taskId == "teams:vocab" }.minutes)
    }

    @Test
    fun `a deck another assignment keeps on the plan isn't counted again in a waiting one's gap`() {
        // The deck is for both: the open one keeps it on the plan, so the waiting one's gap is the rest.
        val steps = listOf(SubStep("Learn vocabulary 1.2", 40, ankiSections = listOf("1.2")), SubStep("Write it up", 30))
        val first = task("first", Fixtures.at("2026-10-12T09:00"))
        val vocab = task("vocab", Fixtures.at("2026-10-13T09:00"), steps = steps).copy(userAfter = "teams:first")
        val other = task("other", Fixtures.at("2026-10-14T09:00"), steps = listOf(SubStep("Learn vocabulary 1.2", 40, ankiSections = listOf("1.2"))))
        val deck = TaskItem(
            id = "anki:deck", source = Source.Anki, sourceId = AnkiRules.DECK_PREFIX + "1", title = "Anki: Textbook 1::1.2", dueAt = Fixtures.at("2026-10-13T09:00"),
            kind = Kind.Revision, sourceEffortMin = 40, derived = true, subSteps = listOf(SubStep("20 new cards", 9)),
            extra = mapOf(AnkiRules.EXTRA_FOR to "teams:vocab,teams:other", AnkiRules.EXTRA_DECK_NAME to "Textbook 1::1.2"),
            firstSeenAt = Fixtures.at("2026-10-07T12:00"), lastSeenAt = Fixtures.at("2026-10-07T12:00"),
        )
        val plan = plan(listOf(first, vocab, other, deck), "2026-10-08T17:00")
        assertTrue(plan.chunksOf("anki:deck").isNotEmpty())
        assertEquals(30, plan.unplanned.single { it.taskId == "teams:vocab" }.minutes)
    }

    @Test
    fun `a task you said isn't one, an email only to read, and an event aren't listed as left out`() {
        val notATask = task("not", Fixtures.at("2026-10-20T09:00"), available = Fixtures.at("2026-10-15T00:00")).copy(userNotATask = true)
        val event = task("event", Fixtures.at("2026-10-20T09:00"), kind = Kind.Event, available = Fixtures.at("2026-10-15T00:00"))
        assertTrue(plan(listOf(notATask, event), "2026-10-08T17:00").unplanned.isEmpty())
    }
}

