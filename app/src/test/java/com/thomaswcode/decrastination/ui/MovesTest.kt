package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayBreaks
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The moves the move sheet and the Day's drag make (UI-04): their pure parts. */
class MovesTest {
    @Test
    fun `a time on a day the clocks change is that day's clock time, as the planner lays it`() {
        // Sunday 25 October 2026 in London, the clocks gone back at 02:00; Sunday 29 March 2026, gone forward at 01:00.
        assertEquals(Fixtures.at("2026-10-25T09:30"), Moves.timeOf(LocalDate.parse("2026-10-25"), 9 * 60 + 30, LONDON))
        assertEquals(Fixtures.at("2026-03-29T17:15"), Moves.timeOf(LocalDate.parse("2026-03-29"), 17 * 60 + 15, LONDON))
        assertEquals(Fixtures.at("2026-10-27T17:15"), Moves.timeOf(LocalDate.parse("2026-10-27"), 17 * 60 + 15, LONDON))
    }

    @Test
    fun `a break outside dragged on its day goes where nothing refuses it, else says why (D-116)`() {
        // Saturday's three breaks, the 13:00 one dragged at 11:00 on the day.
        val saturday = LocalDate.parse("2026-10-31")
        val breaks = DayBreaks(mapOf(570 to 570, 780 to 780, 960 to 960), 20)
        val now = Fixtures.at("2026-10-31T11:00")
        fun problem(minute: Int, day: DayBreaks = breaks) = Moves.outsideProblem(day, 780, minute, Moves.timeOf(saturday, minute, LONDON), now)
        assertNull(problem(14 * 60 + 30))
        assertEquals("That time's gone", problem(10 * 60 + 30))
        assertEquals("Another break outside is then", problem(15 * 60 + 50))
        assertEquals("A break outside is over by midnight", problem(23 * 60 + 50))
        assertEquals("That break outside has begun", problem(14 * 60 + 30, breaks.copy(begun = setOf(780))))
        // Its own time again is no clash with itself.
        assertNull(problem(13 * 60))
    }

    @Test
    fun `a day is open to a piece from the day it can start to its real deadline's, overdue work to any`() {
        val now = Fixtures.at("2026-10-27T17:00")
        val task = com.thomaswcode.decrastination.core.TaskItem(
            id = "teams:a", source = com.thomaswcode.decrastination.core.Source.Teams, sourceId = "a", title = "a", dueAt = Fixtures.at("2026-10-29T09:00"),
            kind = com.thomaswcode.decrastination.core.Kind.Homework, sourceEffortMin = 60, firstSeenAt = now, lastSeenAt = now, notBefore = Fixtures.at("2026-10-28T08:00"),
        )
        assertEquals(listOf(false, true, true, false), (0L..3L).map { Moves.dayAllowed(task, LocalDate.parse("2026-10-27").plusDays(it), now, LONDON) })
        // Overdue: any day from today.
        assertEquals(true, Moves.dayAllowed(task.copy(dueAt = Fixtures.at("2026-10-26T09:00"), notBefore = null), LocalDate.parse("2026-11-03"), now, LONDON))
    }
}
