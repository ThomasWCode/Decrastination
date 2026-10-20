package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each class's next lessons, and a class tied to a code by its subject (docs/plan_v4/15-sent-or-asked-for.md §Tests, G8). */
class LessonsTest {
    // Tuesday 20 Oct 2026, 09:00: week 1 of the timetable's two, its week one from Monday 7 Sep.
    private val now = Fixtures.at("2026-10-20T09:00")

    /** A lesson on [day] (.NET's: 1 Monday … 5 Friday) from [start] to [end], in [week] (1, 2, or 3 for both). */
    private fun at(day: Int, start: String, end: String, week: Int = 3) = PpSchedule("2026-09-05T$start:00", "2026-09-05T$end:00", day, week)

    private val physics = PpClass("p1", "Phys", listOf(at(2, "08:50", "09:45"), at(4, "12:20", "13:15")))
    private val chemistry = PpClass("c1", "Chem", listOf(at(3, "10:00", "11:00", week = 1), at(5, "10:00", "11:00", week = 2)))
    private val study = PpClass("s1", "Study Period", listOf(at(1, "11:15", "12:15")))
    private val timetable = Timetable("2026-09-07T00:00:00", listOf(physics, chemistry, study))

    private fun next(t: Timetable = timetable, school: SchoolCalendar = SchoolCalendar(), from: Long = now) = Lessons.next(t, from, LONDON, school, skip = setOf("Study Period"))

    private fun times(list: List<LessonAt>?) = list.orEmpty().map { it.start }

    @Test
    fun `each class's next three, today's begun passed over, the study period none of them`() {
        val lessons = next()
        // Tuesday's physics began at 08:50 at 09:00: Thursday's is next.
        assertEquals(listOf(Fixtures.at("2026-10-22T12:20"), Fixtures.at("2026-10-27T08:50"), Fixtures.at("2026-10-29T12:20")), times(lessons["Phys"]))
        assertEquals(Fixtures.at("2026-10-22T13:15"), lessons.getValue("Phys").first().end)
        assertTrue("Study Period" !in lessons)
    }

    @Test
    fun `a two-week timetable gives each week its own, and with week one unknown only both weeks' are sure`() {
        // Week 1 (from 19 Oct): Wednesday's chemistry; week 2 (from 26 Oct): Friday's.
        assertEquals(listOf(Fixtures.at("2026-10-21T10:00"), Fixtures.at("2026-10-30T10:00"), Fixtures.at("2026-11-04T10:00")), times(next()["Chem"]))
        assertTrue("Chem" !in next(timetable.copy(weekOneStartsOn = null)))
        assertEquals(3, next(timetable.copy(weekOneStartsOn = null))["Phys"]?.size)
    }

    @Test
    fun `none on a day of no school, none from a half day's end, and one past a fortnight's holiday found`() {
        val off = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-22", "2026-10-22", "Training day")), halfDays = listOf(HalfDay("2026-10-27", 9 * 60 + 30)))
        // Thursday off; the 27th ends at 09:30, its 08:50 lesson before it kept.
        assertEquals(listOf(Fixtures.at("2026-10-27T08:50"), Fixtures.at("2026-10-29T12:20"), Fixtures.at("2026-11-03T08:50")), times(next(school = off)["Phys"]))
        val early = SchoolCalendar(halfDays = listOf(HalfDay("2026-10-22", 12 * 60)))
        assertEquals(Fixtures.at("2026-10-27T08:50"), next(school = early).getValue("Phys").first().start)
        val holiday = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-21", "2026-11-06", "Half term")))
        assertEquals(Fixtures.at("2026-11-10T08:50"), next(school = holiday).getValue("Phys").first().start)
    }

    @Test
    fun `a class is tied to a Teams code by the subject the class map gives both, one with none is its own`() {
        val classMap = mapOf("Phys" to "Physics", "12X/Ph1" to "Physics", "12X/Ch1" to "Chemistry")
        val groups = Lessons.groups(next(), classMap)
        val physicsGroup = groups.single { it.subject == "Physics" }
        assertEquals(listOf("Phys", "12X/Ph1"), physicsGroup.names)
        // Chem has no subject: tied to no code, its own.
        assertEquals(listOf("Chem"), groups.single { it.subject == null }.names)
        // By their first lesson: Chem's Wednesday, then physics' Thursday.
        assertEquals(listOf(null, "Physics"), groups.map { it.subject })
        assertEquals(Fixtures.at("2026-10-22T12:20"), Lessons.nextOf("physics", groups, classMap)?.start)
        assertEquals(Fixtures.at("2026-10-22T12:20"), Lessons.nextOf("12X/Ph1", groups, classMap)?.start)
        assertEquals(Fixtures.at("2026-10-21T10:00"), Lessons.nextOf("Chem", groups, classMap)?.start)
        // A code whose subject no Power Planner class has: no lessons to find.
        assertNull(Lessons.nextOf("12X/Ch1", groups, classMap))
        assertNull(Lessons.nextOf("History", groups, classMap))
    }

    @Test
    fun `a timetable read over a week ago, or at no known time, is stale`() {
        assertTrue(!Lessons.stale(now - 60_000L, now) && !Lessons.stale(now - Lessons.STALE_MS, now))
        assertTrue(Lessons.stale(now - Lessons.STALE_MS - 1, now) && Lessons.stale(null, now))
    }
}
