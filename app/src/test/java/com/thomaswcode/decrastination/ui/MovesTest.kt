package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The moves the move sheet and the Day's drag make (UI-04): their pure parts. */
class MovesTest {
    @Test
    fun `a time on a day the clocks change is that day's clock time, as the planner lays it`() {
        // Sunday 25 October 2026 in London, the clocks gone back at 02:00; Sunday 29 March 2026, gone forward at 01:00.
        assertEquals(Fixtures.at("2026-10-25T09:30"), Moves.timeOf(LocalDate.parse("2026-10-25"), 9 * 60 + 30, LONDON))
        assertEquals(Fixtures.at("2026-03-29T17:15"), Moves.timeOf(LocalDate.parse("2026-03-29"), 17 * 60 + 15, LONDON))
        assertEquals(Fixtures.at("2026-10-27T17:15"), Moves.timeOf(LocalDate.parse("2026-10-27"), 17 * 60 + 15, LONDON))
    }
}
