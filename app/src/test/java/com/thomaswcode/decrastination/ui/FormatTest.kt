package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    private val now = Fixtures.at("2026-10-07T22:00")

    @Test
    fun `times read relative to today`() {
        assertEquals("today 23:59", Format.at(Fixtures.at("2026-10-07T23:59"), now, LONDON))
        assertEquals("tomorrow 08:30", Format.at(Fixtures.at("2026-10-08T08:30"), now, LONDON))
        assertEquals("yesterday 21:00", Format.at(Fixtures.at("2026-10-06T21:00"), now, LONDON))
        assertEquals("Fri 08:30", Format.at(Fixtures.at("2026-10-09T08:30"), now, LONDON))
        assertEquals("Wed 14 Oct 16:00", Format.at(Fixtures.at("2026-10-14T16:00"), now, LONDON))
    }

    @Test
    fun `deadlines say when they're past`() {
        assertEquals("Overdue: due Mon 5 Oct 14:30", Format.due(Fixtures.at("2026-10-05T14:30"), now, LONDON))
        assertEquals("Due today 23:59", Format.due(Fixtures.at("2026-10-07T23:59"), now, LONDON))
        assertEquals("No deadline", Format.due(null, now, LONDON))
    }

    @Test
    fun `durations`() {
        assertEquals("45 min", Format.minutes(45))
        assertEquals("2 h", Format.minutes(120))
        assertEquals("1 h 30 min", Format.minutes(90))
        assertEquals("5 min ago", Format.ago(now - 5 * 60_000, now))
    }

    @Test
    fun `a day's load says what's left free, or how far over`() {
        assertEquals("1 h planned, 4 h 15 min free", Format.load(60, 315))
        assertEquals("7 h planned, 1 h 45 min over", Format.load(420, 315))
    }
}
