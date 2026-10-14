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
 * Places every open task's remaining work into day buckets (docs/scheduler.md §3). Pure:
 * everything it depends on comes in through [Input].
 *
 * 1. Each study day from today has a capacity: your hours that day (Settings), less what's
 *    already gone today and what the calendar takes.
 * 2. A task is cut into chunks: its sub-steps, or its remaining time in even boxes of about
 *    `boxMin`. Remaining time is the estimate × your calibration multiplier × (1 − progress), less
 *    the minutes already worked, and never under 5 ("finish and hand in"). Sub-steps still to do
 *    go the same way: the minutes worked past the steps ticked off, or the source's progress
 *    where that leaves less, come off the next ones.
 * 3. Work due later today or tomorrow comes first, as its deadlines can still be met: it takes
 *    today's time before work already past its deadline. A task with some brings its overdue
 *    blocks along, ahead of it in their order. Then overdue work goes straight into today, oldest
 *    deadline first.
 * 4. Everything else with a deadline, its last usable day first (PL-01), is spread over its window,
 *    from the first day it can be done to its last usable day (the deadline's day less the safety
 *    margin; a morning deadline's day is never usable): each piece where its share of the window's
 *    free time falls, in order, within a week of the one before over a window of a week or more,
 *    revision a day apart, a single piece on the day with most room, and never on a day past which
 *    the pieces after it can't follow. What its window can't hold in order is placed backwards from
 *    its last usable day, as all dated work once was; a chunk that fits nowhere before its deadline
 *    goes into today, flagged behind.
 * 5. Undated work gets a soft deadline a week after it was first seen, and is placed after
 *    everything with a real deadline, so it fills spare time rather than displacing homework; at
 *    most `softMinPerDay` of it a day, so the whole inbox, first seen at once, spreads over the week.
 * 6. A task that can only go so far a day ([TaskItem.stepsPerDay]: an Anki deck, which releases
 *    20 new cards a day) never has more steps than that on one day, overdue or not.
 * 7. An email that's just an email ([TaskItem.justAnEmail]: only to read, or about an event) isn't
 *    planned, nor reminded of; nor is a task you've said isn't one, or one you've said waits for
 *    another still open ([Instructions]).
 * 8. A day you've limited ([Input.dayCaps]) holds no more work than that, and a box is never longer
 *    than the most any day its task can go on holds, so a task isn't left off for want of a day
 *    big enough for one box.
 * 9. Every piece says why it's on its day ([Chunk.why], PL-07), set as it's placed: due soon,
 *    overdue, spread, kept within a week, a day apart, the day with most room, undated, packed
 *    late, or behind with no room.
 *
 * Spreading means today's bucket holds a fair share of every task's days, not the whole of a far
 * task's work in its last ones; placing everything early would put every task in today and the
 * block, which reads today's bucket alone, would never lift.
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
        /** Minutes of focus sessions and photo checks today: they come off today's limit, where there's one. */
        val workedTodayMin: Int = 0,
        /**
         * Times you said you can't work (instructions): off a day's time as [busy] is, and, like a
         * day's limit, never overfilled, not even by overdue work.
         */
        val hardBusy: List<Busy> = emptyList(),
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
     * order. [from] and [due]: a block's own dates, where it has them. [index]: the sub-step's place
     * in its task's list, so finishing it ticks that one, whatever else shares its title.
     */
    private class Piece(val step: String?, val minutes: Int, val box: Int? = null, val from: Long? = null, val due: Long? = null, val index: Int? = null)

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
        val byId = input.tasks.associateBy { it.id }
        // The assignments a deck task was made for (its vocabulary is theirs, done in Anki).
        fun linked(task: TaskItem): List<TaskItem> =
            task.extra[AnkiRules.EXTRA_FOR]?.takeIf { task.source == Source.Anki }?.split(',')?.mapNotNull { byId[it]?.takeIf { a -> a.isOpen } }.orEmpty()
        // Off the plan as you said: a task you said isn't one, or waits for another; and a deck task
        // whose every assignment is, as its vocabulary is theirs.
        fun heldBack(task: TaskItem) = task.hidden || Instructions.waiting(task, byId)
        /** A deck task stays on the plan while any of its assignments does. */
        fun keptOn(task: TaskItem) = linked(task).let { a -> a.isEmpty() || !a.all(::heldBack) }
        val (events, work) = input.tasks
            .filter { it.isOpen && it.isAvailable(input.now) && !heldBack(it) && keptOn(it) }
            .partition { it.kind == Kind.Event }
        val decks = AnkiRules.heldDecks(input.tasks, input.settings.ankiTextbook)
        val held = decks.mapValues { it.value.keys }
        // What's left out is said, with why (IMP-P1-002): first the work that can't start yet, as
        // its source says or as you said (waiting for another task). Not a deck task, whose
        // assignments say it; nor one you said isn't a task, or an email that's only to read.
        val unplanned = mutableListOf<Unplanned>()
        for (task in input.tasks) {
            if (!task.isOpen || task.kind == Kind.Event || task.hidden || task.derived) continue
            val reason = when {
                Instructions.waiting(task, byId) -> Unplanned.Reason.Waits
                !task.isAvailable(input.now) -> Unplanned.Reason.NotOpen
                else -> continue
            }
            // Its own work as the plan would have it: a vocabulary step its decks hold is their cards.
            // Waiting, the decks no assignment still on the plan keeps there wait with it, and their
            // cards are counted here, rather than lost from both the plan and the list: once, under
            // the first of their assignments that waits. A deck another assignment keeps on the plan
            // is counted there, not again here.
            val waitingDecks = if (reason != Unplanned.Reason.Waits) emptyList() else decks[task.id].orEmpty().values.distinctBy { it.id }
                .filter { deck -> deck.isOpen && !keptOn(deck) && linked(deck).firstOrNull { !it.hidden && Instructions.waiting(it, byId) }?.id == task.id }
            val left = (pieces(task, input, held[task.id].orEmpty()) + waitingDecks.flatMap { pieces(it, input, emptySet()) }).takeIf { it.isNotEmpty() } ?: continue
            unplanned += Unplanned(
                task.id, task.source, task.title, left.sumOf { it.minutes }, left.size, reason,
                at = task.availableFrom.takeIf { reason == Unplanned.Reason.NotOpen },
                after = task.userAfter?.takeIf { reason == Unplanned.Reason.Waits }?.let { byId[it]?.title ?: it },
            )
        }
        // A start you gave (an instruction) places the work from then on, as a deck's next cards do;
        // a deck task's, from the earliest its assignments can start, where they all have one.
        fun startOf(task: TaskItem): Long? = task.userFrom ?: linked(task).takeIf { a -> a.isNotEmpty() && a.all { it.userFrom != null } }?.minOf { it.userFrom!! }
        // A deck task's deadline: the earliest of its assignments' as they are now (a due date you gave included).
        fun dueOf(task: TaskItem): Long? = linked(task).filterNot(::heldBack).mapNotNull { it.dueAt }.minOrNull() ?: task.dueAt
        val items = work.map { t -> startOf(t)?.let { from -> t.copy(notBefore = listOfNotNull(t.notBefore, from).maxOrNull()) } ?: t }
            .map { t -> if (linked(t).isEmpty()) t else t.copy(dueAt = dueOf(t)) }.flatMap { task ->
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

        /**
         * Piece [index] of [item]: every task's pieces are placed in order, so it's done ([index] + 1)th.
         * [why] it's there (PL-07); behind, with no room before its deadline, whatever placed it, but
         * undated work, whose deadline is the app's own.
         */
        fun chunk(item: Item, index: Int, behind: Boolean, why: Why): Chunk {
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
                stepIndex = piece.index,
                why = if (behind && !overdue && why != Why.Soft) Why.NoRoom else why,
            )
        }

        // Undated work has its own daily allowance, so a batch seen together spreads out. A day
        // with none yet takes one chunk however long, so no chunk is too big to place anywhere.
        val softUsed = HashMap<LocalDate, Int>()
        // Work that can't start till later in a day (a start you gave of 21:00) has only that day's
        // time from then: its own share of the day, its time after the start, less what it's taken.
        fun opening(item: Item): LocalDate? = item.notBefore?.takeIf { it > input.now }?.let { date(it, zone) }
        // Each item's minutes on its opening day, with its start: the time after a start is shared by
        // everything starting then or later that day (two tasks opening at 21:00 share one evening).
        val openingPlaced = HashMap<LocalDate, MutableList<Pair<Long, Int>>>()
        fun capacityFrom(day: LocalDate, start: Long) = capacity(day, input.copy(now = start, workedTodayMin = if (day == today) input.workedTodayMin else 0))
        fun openingRoom(item: Item): Int {
            val day = opening(item) ?: return Int.MAX_VALUE
            val start = item.notBefore!!
            val placedThere = openingPlaced[day].orEmpty()
            val after = capacityFrom(day, start)
            // What starts then or later uses this time; what starts earlier, as much as can't fit before it.
            val later = placedThere.filter { it.first >= start }.sumOf { it.second }
            val earlier = placedThere.filter { it.first < start }
            val spill = if (earlier.isEmpty()) 0 else maxOf(0, earlier.sumOf { it.second } - (capacityFrom(day, earlier.minOf { it.first }) - after))
            return after - later - spill
        }
        fun room(item: Item, day: LocalDate): Int {
            val left = free.getValue(day)
            val used = softUsed[day] ?: 0
            val mine = if (day == opening(item)) openingRoom(item) else Int.MAX_VALUE
            return minOf(if (item.soft && used > 0) minOf(left, input.settings.softMinPerDay - used) else left, mine)
        }
        fun take(item: Item, day: LocalDate, minutes: Int) {
            free[day] = free.getValue(day) - minutes
            if (item.soft) softUsed[day] = (softUsed[day] ?: 0) + minutes
            if (day == opening(item)) openingPlaced.getOrPut(day) { mutableListOf() } += item.notBefore!! to minutes
        }
        fun give(item: Item, day: LocalDate, minutes: Int) = take(item, day, -minutes)
        // A day you've limited ([Input.dayCaps]) is never overfilled, not even by overdue work,
        // which otherwise piles into today: it goes to the next day with the time.
        // Days you've limited: by a day's limit, or by busy times of yours within its hours.
        val limited = input.dayCaps.keys + if (input.hardBusy.isEmpty()) emptySet() else days.filter { capacity(it, input) < capacity(it, input.copy(hardBusy = emptyList())) }
        fun capped(day: LocalDate, minutes: Int) = day in limited && free.getValue(day) < minutes
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
            // Pieces with no day: there was no room for them by the plan's last day.
            val left = item.chunks.indices.filter { assigned[it] == null }
            if (left.isNotEmpty()) unplanned += Unplanned(item.task.id, item.task.source, item.task.title, left.sumOf { item.chunks[it].minutes }, left.size, Unplanned.Reason.NoRoom)
        }

        // A block whose window opens past the plan's reach waits to be planned until it's within it.
        val placeable = items.filter { item -> item.notBefore?.let { date(it, zone) <= horizon } ?: true }
        (items - placeable.toSet()).forEach { item ->
            unplanned += Unplanned(item.task.id, item.task.source, item.task.title, item.minutes, item.chunks.size, Unplanned.Reason.OpensLater, at = item.notBefore)
        }
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
                        (item.soft && room(item, day) < item.chunks[i].minutes) ||
                        capped(day, item.chunks[i].minutes)
                while (day < horizon && waits(day)) day = day.plusDays(1)
                // Beyond the horizon, the rest goes unplanned rather than breaking a per-day task's
                // limit or undated work's allowance.
                if (waits(day)) break
                assigned[i] = day
                take(item, day, item.chunks[i].minutes)
                // Due today but only possible from a later day (a deck whose next cards come
                // tomorrow): it misses its deadline, so it's behind.
                val why = if (item.deadline < input.now) Why.Overdue else if (item.soft) Why.Soft else Why.DueSoon
                placed.getValue(day) += chunk(item, i, behind = day > date(item.deadline, zone), why = why)
            }
            note(item, assigned)
        }

        // Due after today: backwards from its last usable day.
        fun placeBack(item: Item, why: Why) {
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
            // Nor can work behind crowd into a first day you've limited: it too is done in order from
            // the first day with the time, so its steps keep their order.
            val carriesOn = perDay != null || item.soft || earliest in limited
            for (i in item.chunks.indices.reversed()) {
                if (assigned[i] != null) continue
                val day = latest(assigned.getOrNull(i + 1) ?: lastUsable, i)
                if (day == null && carriesOn) break
                val chosen = day ?: earliest
                assigned[i] = chosen
                behind[i] = day == null
                take(item, chosen, item.chunks[i].minutes)
            }
            // Undated work, a task limited per day, and work on a day you've limited can't crowd into
            // today beyond what they allow. When they don't fit before the deadline, they're done in order from the first
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
                placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy, why = why)
            }
            note(item, assigned)
        }

        // Dated work not due by tomorrow (PL-01): its pieces spread over its window, from the first day
        // it can be done to its last usable day, each where its share of the window's free time falls,
        // so a far task moves on every week rather than arriving at once at its end. A single piece
        // goes to the day with most room (the earlier of two). In order, never before the piece before
        // it; and where there's room, within a week of it over a window of a week or more (the
        // minimum touch, Q-32), and a day after it for revision (spaced practice). A day without
        // room passes its piece to the nearest that has some, earlier first, but never to one past
        // which the pieces after it can't follow. Where the window can't hold the task in order at
        // all, it's placed backwards as before, behind where it must be.
        fun placeSpread(item: Item) {
            val earliest = maxOf(firstDay(item), floorOf(item) ?: today)
            val last = maxOf(earliest, minOf(horizon, lastUsableDay(item, input), ceilingOf(item) ?: horizon))
            val window = generateSequence(earliest) { it.plusDays(1) }.takeWhile { it <= last }.toList()
            val n = item.chunks.size
            // Each day's share: the free time it has left, none where a limit you set is reached.
            val weight = window.map { day -> if (capped(day, MIN_CHUNK)) 0 else free.getValue(day).coerceAtLeast(0) }
            val total = weight.sum()
            val perDay = item.task.stepsPerDay
            val spaced = item.task.kind == Kind.Revision || item.task.enrichment?.testDate != null
            val weekly = window.size >= 7
            val assigned = arrayOfNulls<LocalDate>(n)
            val whys = arrayOfNulls<Why>(n)
            fun fits(day: Int, i: Int) =
                room(item, window[day]) >= item.chunks[i].minutes && (perDay == null || countOn(assigned, window[day]) < perDay)
            // Where piece [i]'s share falls: the first day whose running total of free time reaches it.
            fun target(i: Int): Int {
                if (n == 1) return weight.indices.maxByOrNull { weight[it] } ?: 0
                if (total == 0) return i * window.size / n
                val goal = (i + 0.5) / n * total
                var sum = 0
                for (day in window.indices) {
                    sum += weight[day]
                    if (sum >= goal) return day
                }
                return window.lastIndex
            }
            // Whether pieces [i] on can still follow in order from day [from], each on the first day
            // with room for it: tried by placing them, then taken back.
            fun restFollows(from: Int, i: Int): Boolean {
                val tried = mutableListOf<Pair<Int, Int>>()
                var day = from
                var follows = true
                for (k in i until n) {
                    while (day <= window.lastIndex && !fits(day, k)) day++
                    if (day > window.lastIndex) {
                        follows = false
                        break
                    }
                    assigned[k] = window[day]
                    take(item, window[day], item.chunks[k].minutes)
                    tried += day to k
                }
                tried.forEach { (d, k) ->
                    give(item, window[d], item.chunks[k].minutes)
                    assigned[k] = null
                }
                return follows
            }
            // Whether piece [i] can go on [day] with the pieces after it still to follow in order: a
            // big last piece that only a weekend holds keeps the ones before it from passing it.
            fun usable(day: Int, i: Int): Boolean {
                if (!fits(day, i)) return false
                assigned[i] = window[day]
                take(item, window[day], item.chunks[i].minutes)
                val follows = restFollows(day, i + 1)
                give(item, window[day], item.chunks[i].minutes)
                assigned[i] = null
                return follows
            }
            // The nearest usable day to [at] for piece [i], between [lo] and [hi], earlier first.
            fun nearest(at: Int, lo: Int, hi: Int, i: Int): Int? {
                if (lo > hi) return null
                val start = at.coerceIn(lo, hi)
                for (step in 0..(hi - lo)) {
                    val earlier = start - step
                    if (earlier >= lo && usable(earlier, i)) return earlier
                    val later = start + step
                    if (step > 0 && later <= hi && usable(later, i)) return later
                }
                return null
            }
            var previous = -1
            for (i in 0 until n) {
                val from = maxOf(previous, 0)
                val apart = if (spaced && previous >= 0) previous + 1 else from
                val within = if (weekly && previous >= 0) minOf(window.lastIndex, previous + 7) else window.lastIndex
                val at = target(i)
                // The gap and the week kept where there's room; then the week given up; then the gap.
                val kept = nearest(minOf(at, within), apart, within, i) ?: nearest(at, apart, window.lastIndex, i)
                // Where it would go without them: what tells a piece the week or the gap moved.
                val bound = apart > from || within < window.lastIndex
                val loose = if (kept == null || bound) nearest(at, from, window.lastIndex, i) else kept
                val day = kept ?: loose
                if (day == null) {
                    // No day for it with the rest to follow: the window can't hold the task in order
                    // (only ever its first piece, as each piece's day was taken with the rest able to follow).
                    for (k in 0 until i) give(item, assigned[k]!!, item.chunks[k].minutes)
                    placeBack(item, Why.Packed)
                    return
                }
                whys[i] = when {
                    n == 1 -> Why.MostRoom
                    day == loose -> Why.Spread
                    at > within && day <= within -> Why.MinimumTouch
                    else -> Why.RevisionGap
                }
                assigned[i] = window[day]
                take(item, window[day], item.chunks[i].minutes)
                previous = day
            }
            // Behind only where it can't start before its last usable day is past (a start after it).
            val dueBy = lastUsableDay(item, input)
            item.chunks.indices.forEach { i -> placed.getValue(assigned[i]!!) += chunk(item, i, behind = assigned[i]!! > dueBy, why = whys[i]!!) }
            note(item, assigned)
        }

        // Real deadlines before undated work, then the oldest first (work due soon by its task's
        // soonest deadline to come, a task's own runs in their order).
        val byDeadline = compareBy<Item>({ it.soft }, { rankedBy(it) }, { it.deadline })
        // Earliest last usable day first, so the most constrained work claims its days first (a
        // later deadline with a bigger safety margin can be the more urgent).
        // A task's later runs of blocks first among equals: they leave the earlier runs the days
        // before them, so the order holds.
        val order = compareBy<Item>({ it.soft }, { lastUsableDay(it, input) }, { it.deadline }, { if (it.windowed) it.task.id else "" }, { -it.run })
        // Work due soon takes today's time first: what's due by today (a soon task's overdue blocks
        // among it), then what's due tomorrow; then what's overdue; then dated work spread over its
        // window; then undated work, backwards from its soft week.
        urgent.filter(::soon).sortedWith(byDeadline).forEach(::placeNow)
        later.filter(::soon).sortedWith(order).forEach { placeBack(it, Why.DueSoon) }
        urgent.filterNot(::soon).sortedWith(byDeadline).forEach(::placeNow)
        val (undated, dated) = later.filterNot(::soon).partition { it.soft }
        dated.sortedWith(order).forEach(::placeSpread)
        undated.sortedWith(order).forEach { placeBack(it, Why.Soft) }

        val buckets = days.map { DayBucket(it, capacity.getValue(it), placed.getValue(it).sortedWith(ORDER)) }
        // A task's runs left out for the same reason are one line: their pieces and minutes together, the soonest opening.
        val gaps = unplanned.groupBy { it.taskId to it.reason }.values.map { same ->
            same.first().copy(minutes = same.sumOf { it.minutes }, pieces = same.sumOf { it.pieces }, at = same.mapNotNull { it.at }.minOrNull())
        }.sortedWith(compareBy({ it.reason.ordinal }, { it.at ?: Long.MAX_VALUE }, { it.title }, { it.taskId }))
        return Plan(input.now, today, buckets, events.sortedWith(compareBy(nullsLast()) { it.dueAt }), gaps)
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
    private fun lastUsableDay(item: Item, input: Input): LocalDate = lastUsableDay(item.deadline, item.task.kind, input)

    private fun lastUsableDay(deadline: Long, kind: Kind, input: Input): LocalDate {
        val due = Instant.ofEpochMilli(deadline).atZone(input.zone)
        val margin = input.calibration.marginDays[kind] ?: input.settings.marginDays
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
        val multiplier = input.calibration.multiplierFor(task)
        if (task.subSteps.isNotEmpty()) {
            // Each step's minutes as planned; where you gave the whole task's minutes, shared out to add up to them.
            val planned = task.plannedStepMinutes(multiplier)
            // Its own steps, by their place in its list: not those its deck tasks hold.
            val own = task.subSteps.withIndex().filterNot { (_, step) -> step.ankiSections.isNotEmpty() && held.containsAll(step.ankiSections) }
            val left = own.filterNot { it.value.done }
            // All done: an assignment is still to hand in; an email's blocks done, nothing is left of it.
            // One of your own is finished when its steps are (AppGraph sees to it).
            if (left.isEmpty()) return if (task.source == Source.Gmail || task.source == Source.Own) emptyList() else listOf(Piece("finish and hand in", MIN_CHUNK))
            // Minutes worked beyond the steps ticked off (a session stopped early) come off the
            // next steps in order, each kept to at least a last few minutes, as it isn't done.
            // Steps ticked by hand take back only the timed minutes they kept when ticked (work on them
            // before the tick), the rest none: what's left goes to the next steps. Minutes the
            // source's own steps already leave out ([TaskItem.stepsWorkedMin]) come off none.
            var spare = (task.workedMin + task.photoMin - task.stepsWorkedMin - task.subSteps.withIndex().filter { it.value.done }.sumOf { (i, s) -> if (s.byHand) s.timedMin.toDouble() else planned[i].toDouble() })
                .roundToInt().coerceAtLeast(0)
            val pieces = left.map { (index, step) ->
                val full = planned[index]
                val off = minOf(spare, (full - MIN_CHUNK).coerceAtLeast(0))
                spare -= off
                Piece(step.title, full - off, from = step.from, due = step.dueAt, index = index)
            }
            // The source's own progress counts too (Power Planner's percentage): what it leaves is that
            // share of all the steps. Where that's less than the steps still to do, the difference
            // comes off them in order, as minutes worked do. The smaller of the two, as for a task
            // without steps ([remaining]): the same work isn't taken off twice.
            if (task.sourceProgress <= 0.0) return pieces
            val bySource = (own.sumOf { planned[it.index].toDouble() } * (1 - task.sourceProgress.coerceAtMost(1.0))).roundToInt()
            var over = pieces.sumOf { it.minutes } - bySource
            return pieces.map { piece ->
                val off = minOf(over, (piece.minutes - MIN_CHUNK).coerceAtLeast(0)).coerceAtLeast(0)
                over -= off
                if (off == 0) piece else Piece(piece.step, piece.minutes - off, from = piece.from, due = piece.due, index = piece.index)
            }
        }
        if (task.effortMin <= 0) return emptyList()
        val remaining = remaining(task, multiplier).roundToInt().coerceAtLeast(minOf(MIN_CHUNK, task.effortMin))
        val box = (input.calibration.boxMin[task.kind] ?: input.settings.boxMin).coerceAtLeast(MIN_CHUNK)
            // No longer than the most time any day it can go on holds, where you've limited days: a
            // box too long for all of them would be planned on none.
            .let { box -> roomiest(task, input)?.takeIf { it in MIN_CHUNK until box } ?: box }
        val count = ceil(remaining / box.toDouble()).toInt().coerceAtLeast(1)
        return (0 until count).map { i ->
            // Even boxes: 100 minutes is 34, 33 and 33, not 45, 45 and a stray 10.
            val minutes = remaining / count + if (i < remaining % count) 1 else 0
            // The box only where it cut the task: in one piece, its length was the task's own.
            Piece(null, minutes, box = box.takeIf { count > 1 })
        }
    }

    /**
     * Where your instructions limit days (a day's limit, busy times): the most time a day [task]
     * can be worked on holds, from when it can start to its last usable day (its deadline's, or for
     * undated work its soft deadline's), so its boxes fit before it, not only on a roomier day after
     * it. Where none of those days holds any (or it's overdue), the days on to the plan's least
     * reach, which it carries on into. Null where nothing's limited.
     */
    private fun roomiest(task: TaskItem, input: Input): Int? {
        if (input.dayCaps.isEmpty() && input.hardBusy.isEmpty()) return null
        val zone = input.zone
        val today = date(input.now, zone)
        val from = task.notBefore?.let { date(it, zone) }?.takeIf { it > today } ?: today
        fun most(until: LocalDate) = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.maxOfOrNull { capacity(it, input) } ?: 0
        val deadline = task.dueAt ?: Instant.ofEpochMilli(task.firstSeenAt).atZone(zone).plusDays(input.settings.softDeadlineDays.toLong()).toInstant().toEpochMilli()
        val lastUsable = lastUsableDay(deadline, task.kind, input)
        val before = most(lastUsable)
        if (before >= MIN_CHUNK) return before
        return most(minOf(maxOf(lastUsable, today.plusDays(MIN_HORIZON_DAYS)), today.plusDays(MAX_HORIZON_DAYS)))
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
        val clipped = (input.busy + input.hardBusy).map { maxOf(start, it.start) to minOf(end, it.end) }.filter { it.second > it.first }.sortedBy { it.first }
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
        // A limit is of the whole day's work: what's been done today already comes off it.
        val cap = input.dayCaps[day]?.let { if (day == date(input.now, input.zone)) it - input.workedTodayMin else it } ?: Int.MAX_VALUE
        return minOf(minutes, cap).coerceAtLeast(0)
    }

    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
