package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against the agenda and German timetable recorded on 8 Oct (fixtures/powerplanner_agenda.json). */
class PowerPlannerItemsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixture = json.parseToJsonElement(Fixtures.text("powerplanner_agenda.json")).jsonObject
    private val semester = json.decodeFromJsonElement(SemesterResponse.serializer(), fixture["selectedSemester"]!!).selectedSemesterId!!
    private val items = json.decodeFromJsonElement(AgendaResponse.serializer(), fixture["agenda"]!!).items!!
    private val classes = json.decodeFromJsonElement(ClassesResponse.serializer(), fixture["classesAndSchedules"] as JsonObject)
    private val timetable = Timetable(classes.weekOneStartsOn, classes.classes!!)

    private fun item(name: String) = items.single { it.name.startsWith(name) }

    @Test
    fun `a German task due before class is due at the class's start that day`() {
        // 25 Sep is a Friday of week 1, when German starts at 12:20 (docs/phase0-findings.md §2).
        val task = PowerPlannerItems.fetched(item("Pg 60"), semester, timetable, LONDON)!!
        assertEquals(Fixtures.at("2026-09-25T12:20"), task.dueAt)
        assertEquals(Kind.Homework, task.kind)
        assertEquals("German", task.className)
        assertEquals(false, task.done)
    }

    @Test
    fun `a task with no class at a set time is due then, and is admin`() {
        val task = PowerPlannerItems.fetched(item("Call with"), semester, timetable, LONDON)!!
        assertEquals(Fixtures.at("2026-10-14T16:00"), task.dueAt)
        assertEquals(Kind.Admin, task.kind)
        assertNull(task.className)
    }

    @Test
    fun `weeks alternate from WeekOneStartsOn`() {
        val weekOne = LocalDate.of(2026, 8, 24)
        assertEquals(1, PowerPlannerItems.scheduleWeek(LocalDate.of(2026, 8, 28), weekOne))
        assertEquals(2, PowerPlannerItems.scheduleWeek(LocalDate.of(2026, 8, 31), weekOne))
        assertEquals(1, PowerPlannerItems.scheduleWeek(LocalDate.of(2026, 9, 25), weekOne))
        assertEquals(2, PowerPlannerItems.scheduleWeek(LocalDate.of(2026, 8, 23), weekOne))
    }

    @Test
    fun `the timetable's slots are found by day and week`() {
        val german = timetable.classes.single()
        // 5–11 Oct is week 1, 12–18 Oct week 2. German: week 1 Wednesday 09:50 and Friday 12:20,
        // week 2 Monday 12:20 and Friday 08:50.
        assertEquals(LocalTime.of(8, 50) to LocalTime.of(9, 45), PowerPlannerItems.slot(german, LocalDate.of(2026, 10, 16), timetable))
        assertEquals(LocalTime.of(12, 20) to LocalTime.of(13, 15), PowerPlannerItems.slot(german, LocalDate.of(2026, 10, 9), timetable))
        assertEquals(LocalTime.of(9, 50) to LocalTime.of(10, 45), PowerPlannerItems.slot(german, LocalDate.of(2026, 10, 7), timetable))
        assertNull(PowerPlannerItems.slot(german, LocalDate.of(2026, 10, 14), timetable))
        assertNull(PowerPlannerItems.slot(german, LocalDate.of(2026, 10, 6), timetable))
    }

    private fun task(date: String, classId: String? = timetable.classes.single().identifier, percent: Double = 0.0, type: Int = 5, end: String? = null) =
        PowerPlannerItems.fetched(PpItem("id", "x", null, date, end, classId, type, percent), semester, timetable, LONDON)!!

    @Test
    fun `each time option resolves`() {
        // Friday 16 Oct, week 2: German 08:50–09:45.
        assertEquals(Fixtures.at("2026-10-16T08:50"), task("2026-10-16T00:00:00").dueAt)
        assertEquals(Fixtures.at("2026-10-16T08:50"), task("2026-10-16T00:00:02").dueAt)
        assertEquals(Fixtures.at("2026-10-16T09:45"), task("2026-10-16T00:00:03").dueAt)
        assertEquals(Fixtures.at("2026-10-16T17:30"), task("2026-10-16T17:30:04").dueAt)
        assertEquals(Fixtures.at("2026-10-16T23:59"), task("2026-10-16T00:00:07").dueAt)
        assertEquals("true", task("2026-10-16T00:00:07").extra[PowerPlannerItems.EXTRA_ALL_DAY])
    }

    @Test
    fun `a class-relative time on a day without the class is all day`() {
        assertEquals(Fixtures.at("2026-10-06T23:59"), task("2026-10-06T00:00:01").dueAt)
    }

    @Test
    fun `a classless task that isn't at a set time is due by the end of its day`() {
        assertEquals(Fixtures.at("2026-10-14T23:59"), task("2026-10-14T00:00:00", classId = semester).dueAt)
    }

    @Test
    fun `Power Planner's no-due-date date means no deadline`() {
        assertNull(task("1999-12-31T00:00:00").dueAt)
    }

    @Test
    fun `complete items are done`() {
        assertTrue(task("2026-10-09T00:00:00", percent = 1.0).done)
        assertEquals(0.5, task("2026-10-09T00:00:00", percent = 0.5).sourceProgress)
    }

    @Test
    fun `events are reminders, all day when their end time ends in 59`() {
        val allDay = task("2026-10-10T00:00:00", classId = semester, type = 6, end = "2026-10-10T23:59:59")
        assertEquals(Kind.Event, allDay.kind)
        assertEquals(Fixtures.at("2026-10-10T00:00"), allDay.dueAt)
        val timed = task("2026-10-10T14:00:00", classId = semester, type = 6, end = "2026-10-10T15:30:00")
        assertEquals(Fixtures.at("2026-10-10T14:00"), timed.dueAt)
        assertEquals(Fixtures.at("2026-10-10T15:30").toString(), timed.extra[PowerPlannerItems.EXTRA_END_AT])
    }

    @Test
    fun `other item types are skipped`() {
        assertNull(PowerPlannerItems.fetched(PpItem("id", "x", null, "2026-10-09T00:00:00", null, null, 3), semester, timetable, LONDON))
    }

    @Test
    fun `fractional seconds parse`() {
        assertEquals(46, PowerPlannerItems.parse("2026-09-23T09:46:46.98").second)
    }
}
