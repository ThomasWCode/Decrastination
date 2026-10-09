package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.data.Settings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Places every open task's remaining work into day buckets, backwards from its deadline
 * (docs/scheduler.md §3). Pure: everything it depends on comes in through [Input].
 *
 * 1. Each study day from today has a capacity: your hours that day (Settings), less what's
 *    already gone today and what the calendar takes.
 * 2. A task is cut into chunks: its sub-steps, or its remaining time in even boxes of about
 *    `boxMin`. Remaining time is the estimate × your calibration multiplier × (1 − progress), less
 *    the minutes already worked, and never under 5 ("finish and hand in").
 * 3. Overdue work and work due today go straight into today, oldest deadline first.
 * 4. Everything else, in deadline order, is placed backwards from its last usable day (the
 *    deadline's day less the safety margin; a morning deadline's day is never usable), one chunk
 *    per day while days allow, so a big task spreads over several evenings. A chunk that fits
 *    nowhere before its deadline goes into today, flagged behind.
 * 5. Undated work gets a soft deadline a week after it was first seen, and is placed after
 *    everything with a real deadline, so it fills spare time rather than displacing homework; at
 *    most `softMinPerDay` of it a day, so the whole inbox, first seen at once, spreads over the week.
 * 6. A task that can only go so far a day ([TaskItem.stepsPerDay]: an Anki deck, which releases
 *    20 new cards a day) never has more steps than that on one day, overdue or not.
 *
 * Placing backwards means today's bucket holds exactly what must happen today for every deadline
 * to be met; placing everything early would put every task in today and the block would never lift.
 */
object Planner {

    data class Input(
        val tasks: List<TaskItem>,
        val now: Long,
        val zone: ZoneId,
        val settings: Settings,
        val calibration: Calibration = Calibration(),
        val busy: List<Busy> = emptyList(),
        /** Minutes of a day taken by something with no set time (an all-day van hire's few hours). */
        val dayLoads: Map<LocalDate, Int> = emptyMap(),
    )

    private const val MIN_CHUNK = 5
    private const val MIN_HORIZON_DAYS = 14L
    private const val MAX_HORIZON_DAYS = 90L
    private val NOON: LocalTime = LocalTime.NOON

    private class Item(val task: TaskItem, val deadline: Long, val soft: Boolean, val chunks: List<Piece>) {
        val minutes = chunks.sumOf { it.minutes }
    }

    /** A sub-step, or (with [step] null and [boxed]) a box of time, labelled by its place in the day order. */
    private class Piece(val step: String?, val minutes: Int, val boxed: Boolean = false)

    fun plan(input: Input): Plan {
        val zone = input.zone
        val today = date(input.now, zone)
        val (events, work) = input.tasks
            .filter { it.isOpen && it.isAvailable(input.now) }
            .partition { it.kind == Kind.Event }
        val items = work.map { task ->
            val soft = task.dueAt == null
            // Calendar days where you are, so a week is a week across the clocks changing.
            val deadline = task.dueAt
                ?: Instant.ofEpochMilli(task.firstSeenAt).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
            Item(task, deadline, soft, pieces(task, input))
        }.filter { it.chunks.isNotEmpty() }

        val lastDeadline = items.maxOfOrNull { date(it.deadline, zone) } ?: today
        // Far enough for the latest deadline, and for every step of a task limited per day to
        // have its own day (a deck of 300 unseen cards is 15 days of 20).
        val stepDays = items.maxOfOrNull { item ->
            val perDay = item.task.stepsPerDay ?: return@maxOfOrNull 0L
            val start = item.task.notBefore?.takeIf { it > input.now }?.let { java.time.temporal.ChronoUnit.DAYS.between(today, date(it, zone)) } ?: 0L
            start + (item.chunks.size + perDay - 1) / perDay
        } ?: 0L
        val horizon = minOf(maxOf(lastDeadline, today.plusDays(MIN_HORIZON_DAYS), today.plusDays(stepDays)), today.plusDays(MAX_HORIZON_DAYS))
        val days = generateSequence(today) { it.plusDays(1) }.takeWhile { it <= horizon }.toList()
        val capacity = days.associateWith { capacity(it, input) }
        val free = capacity.toMutableMap()
        val placed = days.associateWith { mutableListOf<Chunk>() }

        /** Piece [index] of [item]: every task's pieces are placed in order, so it's done ([index] + 1)th. */
        fun chunk(item: Item, index: Int, behind: Boolean): Chunk {
            val piece = item.chunks[index]
            val part = index + 1
            val overdue = item.deadline < input.now
            return Chunk(
                taskId = item.task.id,
                source = item.task.source,
                kind = item.task.kind,
                title = item.task.title,
                step = if (piece.boxed) "part $part of ${item.chunks.size}" else piece.step,
                minutes = piece.minutes,
                dueAt = item.task.dueAt,
                deadline = item.deadline,
                soft = item.soft,
                overdue = overdue,
                dueToday = !overdue && date(item.deadline, zone) == today,
                behind = behind && !overdue,
                part = part,
                parts = item.chunks.size,
                taskMinutes = item.minutes,
                availableAt = item.task.notBefore?.takeIf { it > input.now },
            )
        }

        // Undated work has its own daily allowance, so a batch seen together spreads out. A day
        // with none yet takes one chunk however long, so no chunk is too big to place anywhere.
        val softUsed = HashMap<LocalDate, Int>()
        fun room(item: Item, day: LocalDate): Int {
            val left = free.getValue(day)
            val used = softUsed[day] ?: 0
            return if (item.soft && used > 0) minOf(left, input.settings.softMinPerDay - used) else left
        }
        fun take(item: Item, day: LocalDate, minutes: Int) {
            free[day] = free.getValue(day) - minutes
            if (item.soft) softUsed[day] = (softUsed[day] ?: 0) + minutes
        }
        fun give(item: Item, day: LocalDate, minutes: Int) = take(item, day, -minutes)
        // The first day a task's work can start: today, or later if it says so (an Anki deck
        // whose new cards for today are used up).
        fun firstDay(item: Item): LocalDate =
            item.task.notBefore?.takeIf { it > input.now }?.let { minOf(horizon, maxOf(today, date(it, zone))) } ?: today
        fun countOn(assigned: Array<LocalDate?>, day: LocalDate) = assigned.count { it == day }

        val (urgent, later) = items.partition { date(it.deadline, zone) <= today }
        for (item in urgent.sortedWith(compareBy({ it.soft }, { it.deadline }))) {
            val perDay = item.task.stepsPerDay
            val assigned = arrayOfNulls<LocalDate>(item.chunks.size)
            var day = firstDay(item)
            for (i in item.chunks.indices) {
                // Past its deadline it's due now, but a task that can only go so far a day carries
                // on over the next days, each step to a day with both a step and the time free (not
                // tonight once tonight's hours are gone), and undated work keeps to its daily allowance.
                fun waits(day: LocalDate) =
                    (perDay != null && (countOn(assigned, day) >= perDay || room(item, day) < item.chunks[i].minutes)) ||
                        (item.soft && room(item, day) < item.chunks[i].minutes && (softUsed[day] ?: 0) > 0)
                while (day < horizon && waits(day)) day = day.plusDays(1)
                // Beyond the horizon, the rest goes unplanned rather than breaking a per-day task's
                // limit or undated work's allowance.
                if (waits(day)) break
                assigned[i] = day
                take(item, day, item.chunks[i].minutes)
                // Due today but only possible from a later day (a deck whose next cards come
                // tomorrow): it misses its deadline, so it's behind.
                placed.getValue(day) += chunk(item, i, behind = day > date(item.deadline, zone))
            }
        }

        // Earliest last usable day first, so the most constrained work reserves its days first (a
        // later deadline with a bigger safety margin can be the more urgent).
        for (item in later.sortedWith(compareBy({ it.soft }, { lastUsableDay(it, input) }, { it.deadline }))) {
            val earliest = firstDay(item)
            // The day it's due by, as calibrated: work after it is behind, even when that day has
            // already gone (a margin longer than the time left).
            val dueBy = lastUsableDay(item, input)
            val lastUsable = maxOf(earliest, minOf(horizon, dueBy))
            val perDay = item.task.stepsPerDay
            val assigned = arrayOfNulls<LocalDate>(item.chunks.size)
            val behind = BooleanArray(item.chunks.size)
            fun fits(day: LocalDate, i: Int) =
                room(item, day) >= item.chunks[i].minutes && (perDay == null || countOn(assigned, day) < perDay)
            fun latest(from: LocalDate, i: Int): LocalDate? =
                generateSequence(from) { it.minusDays(1) }.takeWhile { it >= earliest }.firstOrNull { fits(it, i) }

            // Backwards, one chunk per day while there are days with room.
            var cursor = lastUsable
            for (i in item.chunks.indices.reversed()) {
                val day = latest(cursor, i) ?: break
                assigned[i] = day
                take(item, day, item.chunks[i].minutes)
                cursor = day.minusDays(1)
            }
            // Then sharing days, still in order. What fits nowhere before the deadline is today's,
            // and behind; except for undated work and a task limited per day, below.
            val carriesOn = perDay != null || item.soft
            for (i in item.chunks.indices.reversed()) {
                if (assigned[i] != null) continue
                val day = latest(assigned.getOrNull(i + 1) ?: lastUsable, i)
                if (day == null && carriesOn) break
                val chosen = day ?: earliest
                assigned[i] = chosen
                behind[i] = day == null
                take(item, chosen, item.chunks[i].minutes)
            }
            // Undated work and a task limited per day can't crowd into today beyond what they
            // allow. When they don't fit before the deadline, they're done in order from the first
            // day they can be, so that as much as can be is done by the deadline and the rest
            // follows it; what fits nowhere within the horizon goes unplanned.
            if (carriesOn && assigned.any { it == null }) {
                for (i in item.chunks.indices) {
                    assigned[i]?.let { give(item, it, item.chunks[i].minutes) }
                    assigned[i] = null
                }
                var from = earliest
                for (i in item.chunks.indices) {
                    val day = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= horizon }.firstOrNull { fits(it, i) } ?: break
                    assigned[i] = day
                    take(item, day, item.chunks[i].minutes)
                    from = day
                }
            }
            item.chunks.indices.forEach { i ->
                val day = assigned[i] ?: return@forEach
                placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy)
            }
        }

        val buckets = days.map { DayBucket(it, capacity.getValue(it), placed.getValue(it).sortedWith(ORDER)) }
        return Plan(input.now, today, buckets, events.sortedWith(compareBy(nullsLast()) { it.dueAt }))
    }

    /**
     * Within a day: work with a real deadline before undated work; then overdue, due today,
     * behind, the rest; then the earliest deadline, homework before revision before admin, the
     * shorter task, and the title, so the order never flickers. A task's parts stay together, in order.
     */
    val ORDER: Comparator<Chunk> = compareBy<Chunk>(
        { it.soft },
        {
            when {
                it.overdue -> 0
                it.dueToday -> 1
                it.behind -> 2
                else -> 3
            }
        },
        { it.deadline },
        { it.kind.ordinal },
        { it.taskMinutes },
        { it.title },
        { it.taskId },
        { it.part },
    )

    /** The deadline's day less the margin; with no margin, a morning deadline's day is never usable. */
    private fun lastUsableDay(item: Item, input: Input): LocalDate {
        val due = Instant.ofEpochMilli(item.deadline).atZone(input.zone)
        val margin = input.calibration.marginDays[item.task.kind] ?: input.settings.marginDays
        val last = due.toLocalDate().minusDays(margin.toLong())
        return if (margin == 0 && due.toLocalTime() < NOON) last.minusDays(1) else last
    }

    /** The task's remaining work as ordered pieces. */
    private fun pieces(task: TaskItem, input: Input): List<Piece> {
        val multiplier = input.calibration.multiplier(task.kind, task.className)
        if (task.subSteps.isNotEmpty()) {
            val left = task.subSteps.filterNot { it.done }
            if (left.isEmpty()) return listOf(Piece("finish and hand in", MIN_CHUNK))
            return left.map { Piece(it.title, (it.minutes * multiplier).roundToInt().coerceAtLeast(1)) }
        }
        if (task.effortMin <= 0) return emptyList()
        val estimate = task.effortMin * multiplier * (1 - task.sourceProgress.coerceIn(0.0, 1.0))
        val remaining = (estimate.roundToInt() - task.workedMin).coerceAtLeast(minOf(MIN_CHUNK, task.effortMin))
        val box = (input.calibration.boxMin[task.kind] ?: input.settings.boxMin).coerceAtLeast(MIN_CHUNK)
        val count = ceil(remaining / box.toDouble()).toInt().coerceAtLeast(1)
        return (0 until count).map { i ->
            // Even boxes: 100 minutes is 34, 33 and 33, not 45, 45 and a stray 10.
            val minutes = remaining / count + if (i < remaining % count) 1 else 0
            Piece(null, minutes, boxed = count > 1)
        }
    }

    /**
     * Your hours that day, less what's gone (today) and what the calendar takes. The hours are
     * wall-clock times, so on the days the clocks change they still mean 08:30 to 22:30; the
     * calendar's blocks are merged first, so two that overlap aren't taken off twice.
     */
    fun capacity(day: LocalDate, input: Input): Int {
        val window = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) input.settings.weekendHours else input.settings.weekdayHours
        val midnight = day.atStartOfDay()
        val end = midnight.plusMinutes(window.endMin.toLong()).atZone(input.zone).toInstant().toEpochMilli()
        val start = maxOf(midnight.plusMinutes(window.startMin.toLong()).atZone(input.zone).toInstant().toEpochMilli(), minOf(end, input.now))
        if (end <= start) return 0
        val clipped = input.busy.map { maxOf(start, it.start) to minOf(end, it.end) }.filter { it.second > it.first }.sortedBy { it.first }
        var taken = 0L
        var reached = start
        for ((from, to) in clipped) {
            val begin = maxOf(from, reached)
            if (to > begin) {
                taken += to - begin
                reached = to
            }
        }
        val minutes = ((end - start - taken) / 60_000L).toInt() - (input.dayLoads[day] ?: 0)
        return minutes.coerceAtLeast(0)
    }

    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
