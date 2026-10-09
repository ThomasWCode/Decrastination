package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
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
    private val settings = Settings()

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
    fun `tonight, the oldest deadline comes first`() {
        assertEquals("Pg 60&61", tonight.next!!.title)
        assertTrue(tonight.next!!.overdue)
        val chapter17 = tonight.todayBucket!!.chunks.single { it.title.startsWith("Prep 30/09") }
        assertTrue(chapter17.dueToday)
    }

    @Test
    fun `tomorrow morning's deadlines are today's, behind, since tonight is gone`() {
        val frost = tonight.todayBucket!!.chunks.single { it.title.startsWith("Dr. Frost") }
        assertTrue(frost.behind)
        assertTrue(tonight.todayBucket!!.chunks.single { it.title == "Prep and Assessment preparation" }.behind)
    }

    @Test
    fun `later deadlines sit on their last usable day`() {
        // Friday 08:30 → Thursday; the test Monday 11:00 → Sunday; PREP 2 Tuesday 08:00 → Monday;
        // the call Wednesday 14 Oct 16:00 → Tuesday.
        assertTrue("Gefahren im Internet. Wie kann man sich schuetzen? Verbot?" in tonight.titlesOn("2026-10-08"))
        assertEquals(listOf("Test"), tonight.titlesOn("2026-10-11"))
        assertEquals(listOf("PREP 2"), tonight.titlesOn("2026-10-12"))
        assertEquals(listOf("Call with a friend after school."), tonight.titlesOn("2026-10-13"))
    }

    // --- The rules, one at a time. ---

    @Test
    fun `a big task spreads one chunk per evening, backwards from its last usable day`() {
        // 135 minutes due Monday 09:00: three 45s on Friday, Saturday and Sunday.
        val plan = plan(listOf(task("big", Fixtures.at("2026-10-12T09:00"), effort = 135)), "2026-10-08T17:00")
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11").map(LocalDate::parse), plan.dayOf("big"))
        assertEquals(listOf(1, 2, 3), plan.chunksOf("teams:big").map { it.part })
        // Its first evening is tomorrow, so it's already due soon: spreading is what makes a
        // Monday deadline press on Thursday.
        assertTrue(plan.pressure)
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
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-10-01T09:00"))
        val homework = task("hw", Fixtures.at("2026-10-08T21:00"), effort = 30)
        val plan = plan(listOf(email, homework), "2026-10-08T17:00")
        // The email's soft deadline (8 Oct 09:00) has passed: it's overdue, but still after the homework.
        assertEquals(listOf("teams:hw", "teams:email"), plan.todayBucket!!.chunks.map { it.taskId })
        assertTrue(plan.todayBucket!!.chunks.last().soft)
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
    fun `overdue undated work waits for a day with time, not tonight after its hours`() {
        // Its soft deadline passed a week ago; at 22:10 tonight's hours are gone: tomorrow.
        val email = task("email", null, effort = 15, kind = Kind.Admin, firstSeen = Fixtures.at("2026-09-24T09:00"))
        assertEquals(listOf(LocalDate.parse("2026-10-09")), plan(listOf(email), "2026-10-08T22:10").dayOf("email"))
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
        // first, but the essay is the thing to do now.
        val deck = task("deck", Fixtures.at("2026-10-01T08:30"), steps = listOf(SubStep("20 new cards", 9))).copy(stepsPerDay = 1, notBefore = Fixtures.at("2026-10-09T04:00"))
        val essay = task("essay", Fixtures.at("2026-10-10T21:00"), effort = 30)
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
        // comes before the other's (Friday): it gets Wednesday, its own last usable day.
        val margins = Calibration(marginDays = mapOf(Kind.Admin to 0, Kind.Homework to 3))
        val admin = task("form", Fixtures.at("2026-10-16T23:00"), kind = Kind.Admin, steps = listOf(SubStep("a", 310), SubStep("b", 310), SubStep("c", 310)))
        val essay = task("essay", Fixtures.at("2026-10-17T09:00"), steps = listOf(SubStep("all", 310)))
        val input = Planner.Input(listOf(admin, essay), Fixtures.at("2026-10-13T16:45"), LONDON, settings, calibration = margins)
        val plan = Planner.plan(input)
        assertEquals(listOf(LocalDate.parse("2026-10-14")), plan.dayOf("essay"))
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

    @Test
    fun `with no margin, a morning deadline's own day is never usable`() {
        val noMargin = Planner.Input(
            listOf(task("t", Fixtures.at("2026-10-12T09:00"))),
            Fixtures.at("2026-10-08T17:00"),
            LONDON,
            settings,
            calibration = Calibration(marginDays = mapOf(Kind.Homework to 0)),
        )
        assertEquals(LocalDate.parse("2026-10-11"), Planner.plan(noMargin).buckets.single { it.chunks.isNotEmpty() }.date)
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
}
