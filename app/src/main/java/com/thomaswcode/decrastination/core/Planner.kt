package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.sources.anki.AnkiRules
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
 * 3. Work due later today or tomorrow comes first, as its deadlines can still be met: it takes
 *    today's time before work already past its deadline. A task with some brings its overdue
 *    blocks along, ahead of it in their order. Then overdue work goes straight into today, oldest
 *    deadline first.
 * 4. Everything else, in deadline order, is placed backwards from its last usable day (the
 *    deadline's day less the safety margin; a morning deadline's day is never usable), one chunk
 *    per day while days allow, so a big task spreads over several evenings. A chunk that fits
 *    nowhere before its deadline goes into today, flagged behind.
 * 5. Undated work gets a soft deadline a week after it was first seen, and is placed after
 *    everything with a real deadline, so it fills spare time rather than displacing homework; at
 *    most `softMinPerDay` of it a day, so the whole inbox, first seen at once, spreads over the week.
 * 6. A task that can only go so far a day ([TaskItem.stepsPerDay]: an Anki deck, which releases
 *    20 new cards a day) never has more steps than that on one day, overdue or not.
 * 7. An email that's just an email ([TaskItem.justAnEmail]: only to read, or about an event) isn't
 *    planned, nor reminded of; nor is a task you've said isn't one, or one you've said waits for
 *    another still open ([Instructions]).
 * 8. A day you've limited ([Input.dayCaps]) holds no more work than that.
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
        /** The most minutes of work a day holds, where your instructions limit it (0: none). */
        val dayCaps: Map<LocalDate, Int> = emptyMap(),
    )

    private const val MIN_CHUNK = 5
    private const val MIN_HORIZON_DAYS = 14L
    private const val MAX_HORIZON_DAYS = 90L
    private val NOON: LocalTime = LocalTime.NOON

    private class Item(
        val task: TaskItem,
        val deadline: Long,
        val soft: Boolean,
        val chunks: List<Piece>,
        /** Not before then: the task's own start, or its blocks'. */
        val notBefore: Long? = task.notBefore,
        /** The real deadline its chunks show: the task's, or its blocks' own. */
        val dueAt: Long? = task.dueAt,
        /** One of several runs of its task's blocks ([windows]), and which, in order. */
        val windowed: Boolean = false,
        val run: Int = 0,
        /** Its first piece's place among all its task's, and how many those are: parts are numbered task-wide. */
        val offset: Int = 0,
        val taskParts: Int = chunks.size,
        /** The whole task's minutes left, all its runs': shorter tasks go first among equals. */
        val taskMinutes: Int = chunks.sumOf { it.minutes },
    ) {
        val minutes = chunks.sumOf { it.minutes }
    }

    /**
     * A sub-step, or (with [step] null and a [box]) a box of time, labelled by its place in the day
     * order. [from] and [due]: a block's own dates, where it has them.
     */
    private class Piece(val step: String?, val minutes: Int, val box: Int? = null, val from: Long? = null, val due: Long? = null)

    /**
     * [task]'s pieces as the planner places them: one item, or, where its blocks have dates of
     * their own (one email's calendar of deadlines), one item per run of blocks sharing them, each
     * in its own window: not before its `from`, and by its own deadline, never after the task's.
     * The windows keep the list's order: no run starts before the one before it, none is planned
     * to finish after a later one is due, and without a real deadline a run's soft one
     * ([softFrom], counted from when it opens) is no earlier than the run before it's.
     */
    private fun windows(task: TaskItem, deadline: Long, soft: Boolean, pieces: List<Piece>, softFrom: (Long) -> Long): List<Item> {
        if (pieces.all { it.from == null && it.due == null }) return listOf(Item(task, deadline, soft, pieces))
        val runs = mutableListOf<MutableList<Piece>>()
        for (piece in pieces) {
            val last = runs.lastOrNull()?.last()
            if (last != null && last.from == piece.from && last.due == piece.due) runs.last() += piece else runs += mutableListOf(piece)
        }
        // Each run's window from its own dates, in order: none starts before the run before it can
        // (so one after a run opening past the plan's reach waits too), and one without a real
        // deadline is due no earlier than the run before it...
        class Window(val from: Long?, val real: Long?, var end: Long, var soft: Boolean)
        val windows = ArrayList<Window>()
        for (run in runs) {
            val head = run.first()
            val previous = windows.lastOrNull()
            // A real deadline: its own (never after the task's), or the task's.
            val real = head.due?.let { d -> task.dueAt?.let { minOf(d, it) } ?: d } ?: task.dueAt
            val from = listOfNotNull(head.from, previous?.from).maxOrNull()
            val end = real ?: listOfNotNull(deadline, from?.let(softFrom), previous?.end).max()
            windows += Window(from, real, end, real == null)
        }
        // ...then planned to be done by the deadline of any run after it, as they're done in order;
        // each still shows its own.
        var cap: Long? = null
        for (window in windows.asReversed()) {
            cap?.takeIf { it < window.end }?.let {
                window.end = it
                window.soft = false
            }
            if (!window.soft) cap = cap?.let { minOf(it, window.end) } ?: window.end
        }
        var offset = 0
        return runs.mapIndexed { index, run ->
            val window = windows[index]
            Item(
                task = task,
                deadline = window.end,
                soft = window.soft,
                chunks = run,
                // Never opening after a real deadline: a start past it is held to it. A soft one is
                // the app's own, so a later start stands, and the plan's reach defers it.
                notBefore = listOfNotNull(task.notBefore, window.from?.let { f -> if (window.soft) f else minOf(f, window.end) }).maxOrNull(),
                dueAt = window.real,
                windowed = true,
                run = index,
                offset = offset,
                taskParts = pieces.size,
                taskMinutes = pieces.sumOf { it.minutes },
            ).also { offset += run.size }
        }
    }

    fun plan(input: Input): Plan {
        val zone = input.zone
        val today = date(input.now, zone)
        val open = input.tasks.filter { it.isOpen }.mapTo(HashSet()) { it.id }
        val (events, work) = input.tasks
            .filter { it.isOpen && it.isAvailable(input.now) && !it.hidden && !it.waiting(open) }
            .partition { it.kind == Kind.Event }
        val held = AnkiRules.heldSections(input.tasks, input.settings.ankiTextbook)
        val items = work.flatMap { task ->
            val soft = task.dueAt == null
            // Calendar days where you are, so a week is a week across the clocks changing.
            val deadline = task.dueAt
                ?: Instant.ofEpochMilli(task.firstSeenAt).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
            windows(task, deadline, soft, pieces(task, input, held[task.id].orEmpty())) { start ->
                Instant.ofEpochMilli(start).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
            }
        }.filter { it.chunks.isNotEmpty() }

        val lastDeadline = items.maxOfOrNull { date(it.deadline, zone) } ?: today
        // Far enough for the latest deadline, and for every step of a task limited per day to
        // have its own day (a deck of 300 unseen cards is 15 days of 20).
        val stepDays = items.maxOfOrNull { item ->
            val perDay = item.task.stepsPerDay ?: return@maxOfOrNull 0L
            val start = item.notBefore?.takeIf { it > input.now }?.let { java.time.temporal.ChronoUnit.DAYS.between(today, date(it, zone)) } ?: 0L
            start + (item.chunks.size + perDay - 1) / perDay
        } ?: 0L
        val horizon = minOf(maxOf(lastDeadline, today.plusDays(MIN_HORIZON_DAYS), today.plusDays(stepDays)), today.plusDays(MAX_HORIZON_DAYS))
        val days = generateSequence(today) { it.plusDays(1) }.takeWhile { it <= horizon }.toList()
        val capacity = days.associateWith { capacity(it, input) }
        val free = capacity.toMutableMap()
        val placed = days.associateWith { mutableListOf<Chunk>() }

        // Work due later today or tomorrow comes before work already past its deadline: those
        // deadlines can still be met. A task with some brings the runs of its blocks due by then
        // along, its overdue ones first, as they come before it in its order. Those overdue ones
        // rank by the task's soonest deadline still to come, not their own long gone: an email's old
        // reply, brought along with tomorrow's application, doesn't go ahead of homework due tonight.
        // Every other run ranks by its own, so tomorrow's doesn't go ahead of tonight's either.
        val tomorrow = today.plusDays(1)
        val soonBy = HashMap<String, Long>()
        items.filter { !it.soft && it.deadline >= input.now && date(it.deadline, zone) <= tomorrow }
            .forEach { soonBy.merge(it.task.id, it.deadline, ::minOf) }
        fun soon(item: Item) = !item.soft && item.task.id in soonBy && date(item.deadline, zone) <= tomorrow
        /** The deadline [item] ranks by: for an overdue run brought along, its task's soonest one to come; else its own. */
        fun rankedBy(item: Item): Long = if (soon(item) && item.deadline < input.now) soonBy.getValue(item.task.id) else item.deadline

        /** Piece [index] of [item]: every task's pieces are placed in order, so it's done ([index] + 1)th. */
        fun chunk(item: Item, index: Int, behind: Boolean): Chunk {
            val piece = item.chunks[index]
            val part = item.offset + index + 1
            val overdue = item.deadline < input.now
            return Chunk(
                taskId = item.task.id,
                source = item.task.source,
                kind = item.task.kind,
                title = item.task.title,
                step = if (piece.box != null) "part $part of ${item.taskParts}" else piece.step,
                minutes = piece.minutes,
                dueAt = item.dueAt,
                deadline = item.deadline,
                soft = item.soft,
                overdue = overdue,
                dueToday = !overdue && date(item.deadline, zone) == today,
                dueSoon = soon(item),
                rankedBy = rankedBy(item),
                behind = behind && !overdue,
                part = part,
                parts = item.taskParts,
                taskMinutes = item.taskMinutes,
                availableAt = item.notBefore?.takeIf { it > input.now },
                box = piece.box,
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
            item.notBefore?.takeIf { it > input.now }?.let { minOf(horizon, maxOf(today, date(it, zone))) } ?: today
        fun countOn(assigned: Array<LocalDate?>, day: LocalDate) = assigned.count { it == day }
        // A task's runs of blocks stay in order: a run goes no earlier than the last day of one
        // before it, and no later than the first day of one after it, where those are placed.
        val runDays = HashMap<String, MutableMap<Int, Pair<LocalDate, LocalDate>>>()
        fun floorOf(item: Item): LocalDate? = runDays[item.task.id]?.filterKeys { it < item.run }?.values?.maxOfOrNull { it.second }
        fun ceilingOf(item: Item): LocalDate? = runDays[item.task.id]?.filterKeys { it > item.run }?.values?.minOfOrNull { it.first }
        fun note(item: Item, assigned: Array<LocalDate?>) {
            val days = assigned.filterNotNull()
            if (item.windowed && days.isNotEmpty()) runDays.getOrPut(item.task.id) { HashMap() }[item.run] = days.min() to days.max()
        }

        // A block whose window opens past the plan's reach waits to be planned until it's within it.
        val placeable = items.filter { item -> item.notBefore?.let { date(it, zone) <= horizon } ?: true }
        val (urgent, later) = placeable.partition { date(it.deadline, zone) <= today }

        // Due by today (overdue, or later today): into today, from the first day it can be.
        fun placeNow(item: Item) {
            val perDay = item.task.stepsPerDay
            val assigned = arrayOfNulls<LocalDate>(item.chunks.size)
            var day = maxOf(firstDay(item), floorOf(item) ?: today)
            for (i in item.chunks.indices) {
                // Past its deadline it's due now, but a task that can only go so far a day carries
                // on over the next days, each step to a day with both a step and the time free (not
                // tonight once tonight's hours are gone), and undated work goes to a day with the time
                // free and within its daily allowance ([room] lets a day's first undated chunk be
                // bigger than the allowance, never bigger than the time left).
                fun waits(day: LocalDate) =
                    (perDay != null && (countOn(assigned, day) >= perDay || room(item, day) < item.chunks[i].minutes)) ||
                        (item.soft && room(item, day) < item.chunks[i].minutes)
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
            note(item, assigned)
        }

        // Due after today: backwards from its last usable day.
        fun placeBack(item: Item) {
            val earliest = maxOf(firstDay(item), floorOf(item) ?: today)
            // The day it's due by, as calibrated: work after it is behind, even when that day has
            // already gone (a margin longer than the time left).
            val dueBy = lastUsableDay(item, input)
            val lastUsable = maxOf(earliest, minOf(horizon, dueBy, ceilingOf(item) ?: horizon))
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
                // Not past a run of its task after it, where that's placed: what fits nowhere before
                // then goes unplanned, rather than out of order.
                val until = minOf(horizon, ceilingOf(item) ?: horizon)
                for (i in item.chunks.indices) {
                    val day = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.firstOrNull { fits(it, i) } ?: break
                    assigned[i] = day
                    take(item, day, item.chunks[i].minutes)
                    from = day
                }
            }
            item.chunks.indices.forEach { i ->
                val day = assigned[i] ?: return@forEach
                placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy)
            }
            note(item, assigned)
        }

        // Real deadlines before undated work, then the oldest first (work due soon by its task's
        // soonest deadline to come, a task's own runs in their order).
        val byDeadline = compareBy<Item>({ it.soft }, { rankedBy(it) }, { it.deadline })
        // Earliest last usable day first, so the most constrained work reserves its days first (a
        // later deadline with a bigger safety margin can be the more urgent).
        // A task's later runs of blocks first among equals: placed backwards, they leave the earlier
        // runs the days before them, so the order holds.
        val order = compareBy<Item>({ it.soft }, { lastUsableDay(it, input) }, { it.deadline }, { if (it.windowed) it.task.id else "" }, { -it.run })
        // Work due soon takes today's time first: what's due by today (a soon task's overdue blocks
        // among it), then what's due tomorrow; then what's overdue; then the rest.
        urgent.filter(::soon).sortedWith(byDeadline).forEach(::placeNow)
        later.filter(::soon).sortedWith(order).forEach(::placeBack)
        urgent.filterNot(::soon).sortedWith(byDeadline).forEach(::placeNow)
        later.filterNot(::soon).sortedWith(order).forEach(::placeBack)

        val buckets = days.map { DayBucket(it, capacity.getValue(it), placed.getValue(it).sortedWith(ORDER)) }
        return Plan(input.now, today, buckets, events.sortedWith(compareBy(nullsLast()) { it.dueAt }))
    }

    /**
     * Within a day: work with a real deadline before undated work; then work due later today or
     * tomorrow (with its task's overdue part first), overdue, behind, the rest; then the earliest
     * deadline (for a task's overdue part brought along, its task's soonest one to come, rather than
     * its own long gone), homework before revision before admin, the shorter
     * task, and the title, so the order never flickers. A task's parts stay in order.
     */
    val ORDER: Comparator<Chunk> = compareBy<Chunk>(
        { it.soft },
        {
            when {
                it.dueSoon -> 0
                it.overdue || it.dueToday -> 1
                it.behind -> 2
                else -> 3
            }
        },
        { it.rankedBy ?: it.deadline },
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

    /**
     * What's left of [task], in minutes of its calibrated estimate: the plan's pieces and a photo
     * check's cap both go by it. Two measures, and the smaller: the source's progress, which may
     * already show the sessions' work (Power Planner's percentage, updated), and the sessions'
     * minutes since the task was first seen. Taking both off would count the same work twice.
     */
    fun remaining(task: TaskItem, multiplier: Double): Double {
        val whole = task.effortMin * multiplier
        val bySource = whole * (1 - task.sourceProgress.coerceIn(0.0, 1.0))
        // From the lower of the first-seen and current progress: a percentage corrected downward
        // brings its work back, rather than the first-seen one capping what's left.
        val baseline = minOf(task.firstProgress ?: task.sourceProgress, task.sourceProgress)
        val bySessions = whole * (1 - baseline.coerceIn(0.0, 1.0)) - task.workedMin - task.photoMin
        return minOf(bySource, bySessions)
    }

    /**
     * The task's remaining work as ordered pieces. A vocabulary step whose sections its Anki deck
     * tasks hold ([held]) isn't among them: the decks' cards are that work.
     */
    private fun pieces(task: TaskItem, input: Input, held: Set<String>): List<Piece> {
        val multiplier = input.calibration.multiplier(task.kind, task.className)
        if (task.subSteps.isNotEmpty()) {
            val left = task.subSteps.filterNot { it.done || (it.ankiSections.isNotEmpty() && held.containsAll(it.ankiSections)) }
            // All done: an assignment is still to hand in; an email's blocks done, nothing is left of it.
            if (left.isEmpty()) return if (task.source == Source.Gmail) emptyList() else listOf(Piece("finish and hand in", MIN_CHUNK))
            // Minutes worked beyond the steps ticked off (a session stopped early) come off the
            // next steps in order, each kept to at least a last few minutes, as it isn't done.
            // Steps ticked by hand take back only the timed minutes they kept when ticked (work on them
            // before the tick), the rest none: what's left goes to the next steps.
            var spare = (task.workedMin + task.photoMin - task.subSteps.filter { it.done }.sumOf { if (it.byHand) it.timedMin.toDouble() else it.minutes * multiplier })
                .roundToInt().coerceAtLeast(0)
            return left.map { step ->
                val full = (step.minutes * multiplier).roundToInt().coerceAtLeast(1)
                val off = minOf(spare, (full - MIN_CHUNK).coerceAtLeast(0))
                spare -= off
                Piece(step.title, full - off, from = step.from, due = step.dueAt)
            }
        }
        if (task.effortMin <= 0) return emptyList()
        val remaining = remaining(task, multiplier).roundToInt().coerceAtLeast(minOf(MIN_CHUNK, task.effortMin))
        val box = (input.calibration.boxMin[task.kind] ?: input.settings.boxMin).coerceAtLeast(MIN_CHUNK)
        val count = ceil(remaining / box.toDouble()).toInt().coerceAtLeast(1)
        return (0 until count).map { i ->
            // Even boxes: 100 minutes is 34, 33 and 33, not 45, 45 and a stray 10.
            val minutes = remaining / count + if (i < remaining % count) 1 else 0
            // The box only where it cut the task: in one piece, its length was the task's own.
            Piece(null, minutes, box = box.takeIf { count > 1 })
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
        return minOf(minutes, input.dayCaps[day] ?: Int.MAX_VALUE).coerceAtLeast(0)
    }

    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
