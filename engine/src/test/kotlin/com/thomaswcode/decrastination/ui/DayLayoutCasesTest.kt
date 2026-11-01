package com.thomaswcode.decrastination.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The grid's maths held to the cases `web/src/dayLayout.test.ts` holds the website's to, so a block lands where the page shows it will. */
class DayLayoutCasesTest {
    @Serializable
    private data class MinuteAt(val y: Double, val hourPx: Double, val minute: Int)

    @Serializable
    private data class YOf(val minute: Int, val hourPx: Double, val y: Double)

    @Serializable
    private data class Snap(val minute: Int, val snapped: Int)

    @Serializable
    private data class DropStart(val minute: Int, val minutes: Int, val start: Int)

    @Serializable
    private data class Cases(val minuteAt: List<MinuteAt>, val yOf: List<YOf>, val snap: List<Snap>, val dropStart: List<DropStart>)

    private val cases: Cases = Json.decodeFromString(Cases.serializer(), File("../fixtures/web/day-layout.json").readText())

    @Test
    fun `a position's minute`() = cases.minuteAt.forEach { assertEquals(it.minute, DayLayout.minuteAt(it.y.toFloat(), it.hourPx.toFloat()), "$it") }

    @Test
    fun `a minute's position`() = cases.yOf.forEach { assertEquals(it.y.toFloat(), DayLayout.yOf(it.minute, it.hourPx.toFloat()), "$it") }

    @Test
    fun `a minute to the nearest five`() = cases.snap.forEach { assertEquals(it.snapped, DayLayout.snap(it.minute), "$it") }

    @Test
    fun `where a drop lands`() = cases.dropStart.forEach { assertEquals(it.start, DayLayout.dropStart(it.minute, it.minutes), "$it") }
}
