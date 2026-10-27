package com.thomaswcode.decrastination.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where things go on the Day's grid (4.6.0, UI-17): the day's wall clock down it, 00:00 to 24:00, blocks at the same time side by
 * side, a drop's minute and the strip's day under a finger. Pure: every number in minutes, or in pixels handed in.
 */
internal object DayLayout {
    const val DAY_MINUTES = 24 * 60

    /** A block as laid: its [column] of the [columns] its cluster has, and how many it [span]s, widened right where they're free. */
    data class Placed(val column: Int, val columns: Int, val span: Int)

    /** A chip's bounds on the screen, as Compose gives them: left, top, right, bottom. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }

    /**
     * The minute of [date] that [at] is on its wall clock, so the clocks changing don't shift the grid: 0 before the day, the
     * day's end after it (an event across midnight, cut to the day).
     */
    fun minuteOf(at: Long, date: LocalDate, zone: ZoneId): Int {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        return when {
            time.toLocalDate() < date -> 0
            time.toLocalDate() > date -> DAY_MINUTES
            else -> time.hour * 60 + time.minute
        }
    }

    /**
     * [spans] (each a start and end minute) laid side by side: in clusters that overlap as drawn, a block's drawn end at least
     * its start and [minMinutes] (the 24 dp a short one is drawn at), each in the first column free at its start, then widened
     * right over the columns free for all its time. In [spans]' order.
     */
    fun columns(spans: List<Pair<Int, Int>>, minMinutes: Int): List<Placed> {
        val drawn = spans.map { (start, end) -> start to maxOf(end, start + minMinutes) }
        val order = drawn.indices.sortedWith(compareBy({ drawn[it].first }, { -drawn[it].second }))
        val placed = arrayOfNulls<Placed>(spans.size)
        var i = 0
        while (i < order.size) {
            // A cluster: blocks chained by overlap.
            val cluster = mutableListOf(order[i])
            var end = drawn[order[i]].second
            i++
            while (i < order.size && drawn[order[i]].first < end) {
                cluster += order[i]
                end = maxOf(end, drawn[order[i]].second)
                i++
            }
            val ends = mutableListOf<Int>()
            val column = HashMap<Int, Int>()
            for (b in cluster) {
                val (start, stop) = drawn[b]
                val c = ends.indexOfFirst { it <= start }.takeIf { it >= 0 } ?: ends.size.also { ends += 0 }
                ends[c] = stop
                column[b] = c
            }
            for (b in cluster) {
                val (start, stop) = drawn[b]
                val c = column.getValue(b)
                var span = 1
                while (c + span < ends.size && cluster.none { o -> o != b && column[o] == c + span && drawn[o].first < stop && start < drawn[o].second }) span++
                placed[b] = Placed(c, ends.size, span)
            }
        }
        return placed.map { it!! }
    }

    /** The offset down the grid of [minute], an hour [hourPx] tall. */
    fun yOf(minute: Int, hourPx: Float): Float = minute * hourPx / 60f

    /** The minute at [y] down the grid, an hour [hourPx] tall. */
    fun minuteAt(y: Float, hourPx: Float): Int = (y * 60f / hourPx).toInt()

    /** [minute] to the nearest five. */
    fun snap(minute: Int): Int = ((minute + 2) / 5) * 5

    /** Where a piece of [minutes] dropped at [minute] starts: snapped, and kept within the day, ending by its end. */
    fun dropStart(minute: Int, minutes: Int): Int = snap(minute).coerceIn(0, maxOf(0, DAY_MINUTES - minutes))

    /** The strip's day under a finger at [x], [y], of the [chips] by their bounds; null for none. */
    fun dayAt(x: Float, y: Float, chips: Map<LocalDate, Box>): LocalDate? = chips.entries.firstOrNull { it.value.contains(x, y) }?.key

    /** The most the DO NOW card folds: from what it shows unfolded, its [full] height or its [cap] where that's less, to its [line]. */
    fun foldMax(full: Float, cap: Float, line: Float): Float = (minOf(full, cap) - line).coerceAtLeast(0f)

    /**
     * How far the DO NOW card is folded once a scroll of the grid's, [dy] (on to later hours, negative), reaches it at [fold]:
     * folded from what it shows, so the scroll moves it at once, however much taller than its cap the card is.
     */
    fun folded(fold: Float, dy: Float, full: Float, cap: Float, line: Float): Float {
        val most = foldMax(full, cap, line)
        return (fold.coerceAtMost(most) - dy).coerceIn(0f, most)
    }
}
