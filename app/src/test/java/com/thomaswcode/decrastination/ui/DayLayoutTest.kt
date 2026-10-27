package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.ui.DayLayout.Box
import com.thomaswcode.decrastination.ui.DayLayout.Placed
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Day's grid, laid out (4.6.0, UI-17). */
class DayLayoutTest {
    private fun m(text: String) = text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()

    @Test
    fun `blocks at the same time stand side by side, a column reused once free, one widened where it's free right of it`() {
        // Two over each other: two columns.
        assertEquals(listOf(Placed(0, 2, 1), Placed(1, 2, 1)), DayLayout.columns(listOf(m("17:00") to m("18:00"), m("17:30") to m("18:30")), 15))
        // The first and the third don't overlap: the third takes the first's column again.
        assertEquals(listOf(Placed(0, 2, 1), Placed(1, 2, 1), Placed(0, 2, 1)), DayLayout.columns(listOf(m("17:00") to m("17:30"), m("17:10") to m("17:50"), m("17:30") to m("18:00")), 15))
        // Three columns; one in the second once its first block's over, widened over the third, free for all its time.
        val four = DayLayout.columns(listOf(m("17:00") to m("18:00"), m("17:00") to m("17:20"), m("17:00") to m("17:30"), m("17:40") to m("18:00")), 15)
        assertEquals(Placed(1, 3, 2), four[3])
        // Apart, each has the width.
        assertEquals(listOf(Placed(0, 1, 1), Placed(0, 1, 1)), DayLayout.columns(listOf(m("09:00") to m("09:30"), m("10:00") to m("10:30")), 15))
    }

    @Test
    fun `a short block is drawn taller, and that counts as overlap`() {
        // Five minutes from 17:00, drawn as fifteen: the block at 17:10 is beside it.
        assertEquals(listOf(Placed(0, 2, 1), Placed(1, 2, 1)), DayLayout.columns(listOf(m("17:00") to m("17:05"), m("17:10") to m("17:40")), 15))
        assertEquals(listOf(Placed(0, 1, 1), Placed(0, 1, 1)), DayLayout.columns(listOf(m("17:00") to m("17:05"), m("17:10") to m("17:40")), 5))
    }

    @Test
    fun `minutes and offsets agree at any zoom, and a drop snaps to five minutes within the day`() {
        for (hourPx in listOf(40f, 60f, 120f)) {
            assertEquals(m("15:30") * hourPx / 60f, DayLayout.yOf(m("15:30"), hourPx))
            assertEquals(m("15:30"), DayLayout.minuteAt(DayLayout.yOf(m("15:30"), hourPx), hourPx))
        }
        assertEquals(m("17:15"), DayLayout.snap(m("17:13")))
        assertEquals(m("17:10"), DayLayout.snap(m("17:12")))
        // A 25-minute piece dropped at 23:50 starts 23:35, over by midnight; one dropped before 00:00, at it.
        assertEquals(m("23:35"), DayLayout.dropStart(m("23:50"), 25))
        assertEquals(0, DayLayout.dropStart(-10, 25))
    }

    @Test
    fun `the strip's day under a finger is the chip it's in`() {
        val chips = mapOf(LocalDate.parse("2026-10-27") to Box(0f, 0f, 50f, 60f), LocalDate.parse("2026-10-28") to Box(50f, 0f, 100f, 60f))
        assertEquals(LocalDate.parse("2026-10-28"), DayLayout.dayAt(75f, 30f, chips))
        assertNull(DayLayout.dayAt(75f, 90f, chips))
    }

    @Test
    fun `a time's minute is its day's wall clock, on the day the clocks go back too, and an event across midnight is cut to the day`() {
        val day = LocalDate.parse("2026-10-25")
        assertEquals(m("09:30"), DayLayout.minuteOf(Fixtures.at("2026-10-25T09:30"), day, LONDON))
        // The hour repeated: both 01:30s are 01:30.
        val first = Fixtures.at("2026-10-25T01:30")
        assertEquals(m("01:30"), DayLayout.minuteOf(first, day, LONDON))
        assertEquals(m("01:30"), DayLayout.minuteOf(first + 3_600_000L, day, LONDON))
        assertEquals(0, DayLayout.minuteOf(Fixtures.at("2026-10-24T23:00"), day, LONDON))
        assertEquals(DayLayout.DAY_MINUTES, DayLayout.minuteOf(Fixtures.at("2026-10-26T00:00"), day, LONDON))
    }
}
