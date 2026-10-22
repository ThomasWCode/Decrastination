package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Invite
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
        assertEquals("Overdue: due Mon 5 Oct 14:30", Format.due(Fixtures.at("2026-10-05T14:30"), hard = true, now, LONDON))
        assertEquals("Due today 23:59", Format.due(Fixtures.at("2026-10-07T23:59"), hard = true, now, LONDON))
        assertEquals("No deadline", Format.due(null, hard = false, now, LONDON))
        // A date the app or Claude set isn't overdue once it's gone by: the work is planned again (PL-14).
        assertEquals("Planned again: its date was Mon 5 Oct 14:30", Format.due(Fixtures.at("2026-10-05T14:30"), hard = false, now, LONDON))
        assertEquals("Due today 23:59", Format.due(Fixtures.at("2026-10-07T23:59"), hard = false, now, LONDON))
    }

    @Test
    fun `a moment's time of day, as under a chat message`() {
        assertEquals("16:45", Format.time(Fixtures.at("2026-10-18T16:45"), LONDON))
        assertEquals("09:05", Format.time(Fixtures.at("2026-12-01T09:05"), LONDON))
    }

    @Test
    fun `durations`() {
        assertEquals("45 min", Format.minutes(45))
        assertEquals("2 h", Format.minutes(120))
        assertEquals("1 h 30 min", Format.minutes(90))
        assertEquals("5 min ago", Format.ago(now - 5 * 60_000, now))
    }

    @Test
    fun `times are typed as 16 45 or 1645, and nonsense is refused`() {
        assertEquals(16 * 60 + 45, parseTime("16:45"))
        assertEquals(16 * 60 + 45, parseTime("1645"))
        assertEquals(7 * 60, parseTime("7:00"))
        assertEquals(null, parseTime("24:00"))
        assertEquals(null, parseTime("16:75"))
        assertEquals(null, parseTime("soon"))
    }

    @Test
    fun `a day's load says what's left free, or how far over`() {
        assertEquals("1 h planned, 4 h 15 min free", Format.load(60, 315))
        assertEquals("7 h planned, 1 h 45 min over", Format.load(420, 315))
    }

    @Test
    fun `an invite says its day, its times and its place`() {
        val invite = Invite("Open evening", Fixtures.at("2026-10-16T18:00"), Fixtures.at("2026-10-16T20:00"), "Main Hall")
        assertEquals("Fri 16 Oct 18:00–20:00, Main Hall", Format.invite(invite, LONDON))
        assertEquals("Fri 16 Oct 18:00 to Sat 17 Oct 10:00", Format.invite(invite.copy(end = Fixtures.at("2026-10-17T10:00"), location = null), LONDON))
        assertEquals("Mon 26 Oct, all day", Format.invite(invite.copy(start = Fixtures.at("2026-10-26T00:00"), end = Fixtures.at("2026-10-27T00:00"), location = null), LONDON))
        assertEquals("Mon 26 Oct to Fri 30 Oct", Format.invite(invite.copy(start = Fixtures.at("2026-10-26T00:00"), end = Fixtures.at("2026-10-31T00:00"), location = null), LONDON))
    }
}
