package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.sources.powerplanner.PpClass
import com.thomaswcode.decrastination.sources.powerplanner.PpSchedule
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** A school day's planning time as windows (PL-15). */
class SchoolDaysTest {
    private val settings = Settings()

    // Study periods on Tuesdays (both weeks) after lunch, and on Wednesdays one inside lunch; German isn't one.
    private val timetable = Timetable(
        "2026-08-24T00:00:00",
        listOf(
            PpClass("s", "Study Period ", listOf(PpSchedule("2026-09-05T14:35:00", "2026-09-05T15:30:00", dayOfWeek = 2), PpSchedule("2026-09-05T13:20:00", "2026-09-05T13:35:00", dayOfWeek = 3))),
            PpClass("g", "German", listOf(PpSchedule("2026-09-05T08:50:00", "2026-09-05T09:45:00", dayOfWeek = 2))),
        ),
    )

    private fun w(start: String, end: String, kind: WindowKind): DayWindow {
        fun minute(text: String) = text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()
        return DayWindow(minute(start), minute(end), kind)
    }

    @Test
    fun `a school day has its study periods, break, lunch either side of eating, and the evening`() {
        assertEquals(
            listOf(
                w("10:45", "11:15", WindowKind.Break), w("13:15", "13:40", WindowKind.Lunch), w("13:55", "14:35", WindowKind.Lunch),
                w("14:35", "15:30", WindowKind.Study), w("16:45", "22:00", WindowKind.Evening),
            ),
            SchoolDays.windows(LocalDate.parse("2026-10-13"), settings, timetable, SchoolCalendar()),
        )
        // With no timetable read yet: break, lunch and the evening.
        assertEquals(4, SchoolDays.windows(LocalDate.parse("2026-10-13"), settings, null, SchoolCalendar()).size)
    }

    @Test
    fun `a study period inside lunch is lunch's, overlaps merged`() {
        assertEquals(
            listOf(w("10:45", "11:15", WindowKind.Break), w("13:15", "13:40", WindowKind.Lunch), w("13:55", "14:35", WindowKind.Lunch), w("16:45", "22:00", WindowKind.Evening)),
            SchoolDays.windows(LocalDate.parse("2026-10-14"), settings, timetable, SchoolCalendar()),
        )
    }

    @Test
    fun `a half-day cuts school at its end, and the evening starts then`() {
        val school = SchoolCalendar(halfDays = listOf(HalfDay("2026-10-13", 12 * 60 + 30)))
        assertEquals(listOf(w("10:45", "11:15", WindowKind.Break), w("12:30", "22:00", WindowKind.Evening)), SchoolDays.windows(LocalDate.parse("2026-10-13"), settings, timetable, school))
    }

    @Test
    fun `a weekday with no school is a weekend's, its hours one window`() {
        val school = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-26", "2026-10-30", "Half term")))
        assertEquals(listOf(w("08:30", "22:30", WindowKind.Evening)), SchoolDays.windows(LocalDate.parse("2026-10-27"), settings, timetable, school))
        assertEquals(DayKind.Weekend, SchoolDays.kindOf(LocalDate.parse("2026-10-27"), school))
        assertEquals(DayKind.Weekday, SchoolDays.kindOf(LocalDate.parse("2026-11-02"), school))
        assertEquals(DayKind.Weekend, SchoolDays.kindOf(LocalDate.parse("2026-10-31"), school))
    }
}
