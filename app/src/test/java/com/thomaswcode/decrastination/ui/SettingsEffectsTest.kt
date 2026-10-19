package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.core.Window
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What each setting does, in a sentence (UI-13). */
class SettingsEffectsTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val settings = Settings()

    private fun line(field: String, s: Settings = settings, timetable: Timetable? = null) = SettingsEffects.line(field, s, timetable, SchoolCalendar(), monday)

    @Test
    fun `the hours say the evening, and the school day with most time at school besides`() {
        assertTrue(line("weekdayHours")!!.startsWith("5 h 15 min of evening on a school day; with school's free times, about "))
        val week = Timetable("2026-08-24T00:00:00", listOf(PpClass("s", "Study Period", listOf(PpSchedule("2026-09-05T14:35:00", "2026-09-05T15:30:00", dayOfWeek = 2)))))
        assertTrue(line("weekdayHours", timetable = week)!!.endsWith("on a Tuesday"), line("weekdayHours", timetable = week))
        assertEquals("8 h on a weekend day or a day off", line("weekendHours", settings.copy(weekendHours = Window(9 * 60, 17 * 60))))
    }

    @Test
    fun `the margin, the pieces, the ratio and the photo say what they do`() {
        assertEquals("Work due Thursday 08:30 is planned by Tuesday", line("marginDays"))
        assertEquals("Work due Thursday 08:30 is planned by Wednesday", line("marginDays", settings.copy(marginDays = 0)))
        assertEquals("Work with no steps of its own is cut into 30-minute pieces", line("boxMin"))
        assertEquals("A 30-minute piece earns 10 min of free time", line("workMinPerFreeMin"))
        assertTrue(line("photoFromMin")!!.startsWith("Written work of 20 minutes or more earns its free time with a photo"))
        assertEquals("A day with nothing to do starts with 1 h free", line("freeAllowanceMin"))
        assertEquals("Three 20-minute outside breaks on a free Saturday", line("outsideBreaks"))
        assertEquals("One 30-minute outside break on a free Saturday", line("outsideBreakMin", settings.copy(outsideBreaks = 1, outsideBreakMin = 30)))
        assertNull(line("ankiTextbook"))
    }
}
