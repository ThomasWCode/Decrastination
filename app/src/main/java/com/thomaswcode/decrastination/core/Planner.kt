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
 * 10. Only a real deadline ([TaskItem.hardDeadline]: a source's, yours, an email's quoted) is ever
 *    overdue (PL-14). A date the app or Claude set is a plan: gone by, its work is undated again,
 *    its week counted on from that date, and a step's own dates gone by are dropped; undated work's
 *    week, ended undone, is followed by the next.
 * 11. Work with a real deadline still to come that has more left than the free time before it is
 *    said ([Plan.wontFit], PL-02), the day it becomes so: what's left, and what's free before the
 *    deadline itself (the safety margin aside); work not open yet, where its days from its opening
 *    can't hold it.
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
        /**
         * Each day's planning time as windows (PL-15): study periods, break, lunch and the evening on
         * a school day. A day without any has your hours that day as one evening, as before.
         */
        val windows: Map<LocalDate, List<DayWindow>> = emptyMap(),
        /** Each day's kind for its yield (PL-06): a day off school is a weekend's. By the date where absent. */
        val dayKinds: Map<LocalDate, DayKind> = emptyMap(),
        /** Pieces you put at a time (PL-04), by [Chunk.key]: their slots start then. */
        val pins: Map<String, Long> = emptyMap(),
        /** Today's slots' starts in the last plan (PL-13), by [Chunk.key]: kept where still free. */
        val previousSlots: Map<String, Long> = emptyMap(),
        /** Today's slots missed, their pieces undone (PL-13), by [Chunk.key]: moved, and saying so. */
        val missedSlots: Set<String> = emptySet(),
    )

    /** A day's time for work (PL-15): at home in the evening ([homeMin]), and all of it, school's included ([anyMin]). */
    data class Capacity(val homeMin: Int, val anyMin: Int)

    private const val MIN_CHUNK = 5
    private const val MIN_HORIZON_DAYS = 14L
    /** The furthest the plan reaches, in days: the calendar is read as far (DX-14). */
    const val MAX_HORIZON_DAYS = 90L
    private val NOON: LocalTime = LocalTime.NOON

    private class Item(
        val task: TaskItem,
        val deadline: Long,
        val soft: Boolean,
        val chunks: List<Piece>,
        /** Its deadline is a real one, which can be missed (PL-14): the task's own, where it's hard. */
        val hard: Boolean = false,
        /** All its pieces can only be done at home, in the evening's time (PL-15): its share of a day is the evening's. */
        val home: Boolean = false,
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
        /** The day you put it on (PL-04): its last usable day, whatever the margin. */
        val pinnedDay: LocalDate? = null,
    ) {
        /** On [day], as you put it (PL-04), with what that changes. */
        fun pinnedTo(day: LocalDate, deadline: Long = this.deadline, soft: Boolean = this.soft, chunks: List<Piece> = this.chunks, hard: Boolean = this.hard, notBefore: Long? = this.notBefore) =
            Item(task, deadline, soft, chunks, hard, home, notBefore, dueAt, windowed, run, offset, taskParts, taskMinutes, pinnedDay = day)

        val minutes = chunks.sumOf { it.minutes }
    }

    /**
     * A sub-step, or (with [step] null and a [box]) a box of time, labelled by its place in the day
     * order. [from] and [due]: a block's own dates, where it has them. [index]: the sub-step's place
     * in its task's list, so finishing it ticks that one, whatever else shares its title.
     */
    private class Piece(
        val step: String?,
        val minutes: Int,
        val box: Int? = null,
        val from: Long? = null,
        val due: Long? = null,
        val index: Int? = null,
        val where: Where = Where.Anywhere,
        /** On a day you put it on, or at a time you gave ([at]) (PL-04). */
        val pinned: Boolean = false,
        val at: Long? = null,
    ) {
        /** Without its own dates: a step's dates gone by were a plan's (PL-14), so it's done with its task's own. */
        fun undated() = Piece(step, minutes, box, index = index, where = where)

        /** On the day you put it on (PL-04). */
        fun pinned() = Piece(step, minutes, box, from, due, index, where, pinned = true, at = at)
    }

    /**
     * [task]'s pieces as the planner places them: one item, or, where its blocks have dates of
     * their own (one email's calendar of deadlines), one item per run of blocks sharing them, each
     * in its own window: not before its `from`, and by its own deadline, never after the task's.
     * The windows keep the list's order: no run starts before the one before it, none is planned
     * to finish after a later one is due, and without a real deadline a run's soft one
     * ([softFrom], counted from when it opens) is no earlier than the run before it's. A run is
     * [hard] where its deadline is the task's own, hard one: a step's own date is a plan (PL-14).
     */
    private fun windows(task: TaskItem, deadline: Long, soft: Boolean, hard: Boolean, pieces: List<Piece>, softFrom: (Long) -> Long): List<Item> {
        if (pieces.all { it.from == null && it.due == null }) return listOf(Item(task, deadline, soft, pieces, hard = hard, home = pieces.isNotEmpty() && pieces.all { it.where == Where.Home }))
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
                hard = hard && window.real == task.dueAt,
                home = run.all { it.where == Where.Home },
                // Never opening after a real deadline: a start past it is held to it. A soft one is
                // the app's own, so a later start stands, and the plan's reach defers it.
                notBefore = listOfNotNull(task.notBefore, window.from?.let { f -> if (window.soft) f else minOf(f, window.end) }).maxOrNull(),
                // A piece at a time you gave shows its task's deadline, not the end of its day (PL-04).
                dueAt = if (run.first().at != null) task.dueAt else window.real,
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
        // A deck task's deadline: the earliest of its assignments' in force now (a due date you gave
        // included; a plan's gone by, the real one behind it, PL-14).
        fun dueOf(task: TaskItem): Long? = linked(task).filterNot(::heldBack).mapNotNull { it.deadlineAt(input.now) }.minOrNull() ?: task.dueAt
        // A deck task's real deadline is its assignments', as its deadline is (dueOf, PL-14).
        fun realOf(task: TaskItem): Long? = linked(task).filterNot(::heldBack).takeIf { it.isNotEmpty() }?.mapNotNull { it.realDeadline }?.minOrNull() ?: task.realDeadline
        // The day [time] is on, from its start to its last moment.
        fun dayOf(time: Long): Pair<Long, Long> {
            val day = date(time, zone)
            return day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        // A time you gave for a task's next piece (PL-04): that piece a run of its own on the time's day,
        // from the time, so the rest follow it; none where its day has gone, or the time is at or past
        // its real deadline [real].
        fun pinnedAt(task: TaskItem, pieces: List<Piece>, real: Long?): List<Piece> {
            val at = pinnedTime(task, real, input) ?: return pieces
            val first = pieces.firstOrNull() ?: return pieces
            return listOf(Piece(first.step, first.minutes, first.box, from = at, due = dayOf(at).second, index = first.index, where = first.where, pinned = true, at = at)) + pieces.drop(1)
        }
        // A day you gave for all of a task (PL-04): each run of it from that day's start and by its end,
        // its last usable day whatever the margin, a deadline still only where its own is that day;
        // none where the day has gone, or is after its real deadline's [real].
        fun keptTo(task: TaskItem, runs: List<Item>, real: Long?): List<Item> {
            // A run at a time you gave is on its day, whatever the margin.
            val timed = runs.map { item -> item.chunks.firstNotNullOfOrNull { it.at }?.let { item.pinnedTo(date(it, zone)) } ?: item }
            val day = keptDay(task, real, input) ?: return timed
            val (start, end) = dayOf(day.atStartOfDay(zone).toInstant().toEpochMilli())
            return timed.map { item ->
                val within = item.deadline in start..end
                item.pinnedTo(day, if (within) item.deadline else end, soft = false, item.chunks.map { it.pinned() }, item.hard && within, maxOf(item.notBefore ?: start, start))
            }
        }
        val items = work.map { t -> startOf(t)?.let { from -> t.copy(notBefore = listOfNotNull(t.notBefore, from).maxOrNull()) } ?: t }
            .map { t -> if (linked(t).isEmpty()) t else t.copy(dueAt = dueOf(t)) }.flatMap { task ->
            // A date the app or Claude set is a plan (PL-14): once it has passed, the real deadline
            // behind it holds (an assignment's own, after a test date Claude read before it); with
            // none, the work is undated again, its week counted on from that date, so it's planned
            // within the next week rather than overdue. Only a real deadline gone by is overdue.
            val real = realOf(task)
            // A time you gave past a plan's date (PL-04) makes the date moot: the work is undated from
            // the time, its week counted from it, as from a plan's date gone by.
            val at = pinnedTime(task, real, input)
            val due = plannedDue(task.dueAt, real, input.now)?.takeIf { d -> at == null || d == real || d >= at }
            val planned = if (due == task.dueAt) task else task.copy(dueAt = due)
            // A step's own dates gone by were a plan's too: it's done with its task's own.
            val pieces = pieces(task, input, held[task.id].orEmpty()).map { p -> if (p.due != null && p.due < input.now) p.undated() else p }
            val deadline = due ?: softWeek(at ?: task.dueAt ?: task.firstSeenAt, input)
            keptTo(task, windows(planned, deadline, soft = due == null, hard = due != null && due == real, pinnedAt(task, pieces, real)) { start -> softWeek(start, input) }, real)
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
        val capacities = days.associateWith { capacityOf(it, input) }
        val capacity = capacities.mapValues { it.value.anyMin }
        val free = capacity.toMutableMap()
        // Of each day's free time, the evening's at home (PL-15): work only for home takes that alone;
        // work that can be done anywhere takes school's time first, and the evening's only past it.
        val freeHome = capacities.mapValues { it.value.homeMin }.toMutableMap()
        // How much of the evening each piece placed took, so giving it back puts that back.
        val homeTaken = HashMap<Pair<Item, Int>, Int>()
        // Each task's pieces on each day, all its runs' together, so a deck's step a day holds across
        // runs where its work splits (home and anywhere, PL-15; blocks with their own dates).
        val stepsOn = HashMap<Pair<String, LocalDate>, Int>()
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
        // Pieces at a time you gave (PL-04), by [Chunk.key], for the slotter.
        val atPins = HashMap<String, Long>()
        /** The deadline [item] ranks by: for an overdue run brought along, its task's soonest one to come; else its own. */
        fun rankedBy(item: Item): Long = if (soon(item) && item.deadline < input.now) soonBy.getValue(item.task.id) else item.deadline

        /**
         * Piece [index] of [item]: every task's pieces are placed in order, so it's done ([index] + 1)th.
         * [why] it's there (PL-07); behind with [noRoom] before its deadline itself, that, whatever
         * placed it, but for undated work, whose deadline is the app's own. Behind only the safety
         * margin, it keeps the reason it was placed for.
         */
        fun chunk(item: Item, index: Int, behind: Boolean, why: Why, noRoom: Boolean): Chunk {
            val piece = item.chunks[index]
            val part = item.offset + index + 1
            // Only a real deadline can be missed (PL-14): the app's own dates move on instead.
            val overdue = item.hard && item.deadline < input.now
            val key = "${item.task.id}#${piece.index ?: -1}#$part"
            // Where you put it (PL-04): its time is the slotter's to keep.
            if (piece.at != null) atPins[key] = piece.at
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
                dueToday = item.hard && !overdue && date(item.deadline, zone) == today,
                dueSoon = soon(item),
                rankedBy = rankedBy(item),
                behind = behind && !overdue,
                part = part,
                parts = item.taskParts,
                taskMinutes = item.taskMinutes,
                availableAt = item.notBefore?.takeIf { it > input.now },
                box = piece.box,
                stepIndex = piece.index,
                why = when {
                    piece.pinned && !overdue -> Why.Pinned
                    behind && !overdue && why != Why.Soft && noRoom -> Why.NoRoom
                    // Its slot went by undone (PL-13): moved on, and saying so.
                    !overdue && key in input.missedSlots -> Why.Refitted
                    else -> why
                },
                where = piece.where,
                hard = item.hard,
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
        fun capacityFrom(day: LocalDate, start: Long, home: Boolean) =
            capacityOf(day, input.copy(now = start, workedTodayMin = if (day == today) input.workedTodayMin else 0)).let { if (home) it.homeMin else it.anyMin }
        fun openingRoom(item: Item): Int {
            val day = opening(item) ?: return Int.MAX_VALUE
            val start = item.notBefore!!
            val placedThere = openingPlaced[day].orEmpty()
            val after = capacityFrom(day, start, item.home)
            // What starts then or later uses this time; what starts earlier, as much as can't fit before it.
            val later = placedThere.filter { it.first >= start }.sumOf { it.second }
            val earlier = placedThere.filter { it.first < start }
            val spill = if (earlier.isEmpty()) 0 else maxOf(0, earlier.sumOf { it.second } - (capacityFrom(day, earlier.minOf { it.first }, item.home) - after))
            return after - later - spill
        }
        /**
         * The time [day] has for [item]'s [piece] (PL-15): a piece only for home, the evening's left; any
         * other, all the day's. With no piece named, the item's: the evening's where all its pieces are home's.
         */
        // Each day's time before a deadline on it, and each item's minutes put on its real deadline's own
        // day: all of them, and those of the evening's (a piece for home has only the evening's time, which
        // its school work leaves it).
        val beforeDeadline = HashMap<Pair<LocalDate, Long>, Capacity>()
        val onDueDay = HashMap<Item, Int>()
        val onDueDayHome = HashMap<Item, Int>()
        fun room(item: Item, day: LocalDate, piece: Int? = null): Int {
            val home = piece?.let { item.chunks[it].where == Where.Home } ?: item.home
            val left = (if (home) freeHome else free).getValue(day)
            val used = softUsed[day] ?: 0
            val mine = if (day == opening(item)) openingRoom(item) else Int.MAX_VALUE
            // On its real deadline's own day, only the time before the deadline (its slot must end by it),
            // less what it's put there already.
            val beforeDue = if (item.hard && day == date(item.deadline, zone)) {
                val before = beforeDeadline.getOrPut(day to item.deadline) { capacitiesBefore(day, item.deadline, input) }
                if (home) before.homeMin - (onDueDayHome[item] ?: 0) else before.anyMin - (onDueDay[item] ?: 0)
            } else {
                Int.MAX_VALUE
            }
            return minOf(if (item.soft && used > 0) minOf(left, input.settings.softMinPerDay - used) else left, mine, beforeDue)
        }
        /** [item]'s piece [piece] placed on [day]: its minutes off the day's time, the evening's only as far as they must be. */
        fun take(item: Item, day: LocalDate, piece: Int) {
            val minutes = item.chunks[piece].minutes
            val school = (free.getValue(day) - freeHome.getValue(day)).coerceAtLeast(0)
            val home = if (item.chunks[piece].where == Where.Home) minutes else (minutes - school).coerceAtLeast(0)
            if (item.hard && day == date(item.deadline, zone)) {
                onDueDay[item] = (onDueDay[item] ?: 0) + minutes
                onDueDayHome[item] = (onDueDayHome[item] ?: 0) + home
            }
            free[day] = free.getValue(day) - minutes
            freeHome[day] = freeHome.getValue(day) - home
            homeTaken[item to piece] = home
            stepsOn.merge(item.task.id to day, 1, Int::plus)
            if (item.soft) softUsed[day] = (softUsed[day] ?: 0) + minutes
            if (day == opening(item)) openingPlaced.getOrPut(day) { mutableListOf() } += item.notBefore!! to minutes
        }
        /** [take] undone: its minutes back where they were taken from. */
        fun give(item: Item, day: LocalDate, piece: Int) {
            val minutes = item.chunks[piece].minutes
            val home = homeTaken.remove(item to piece) ?: 0
            if (item.hard && day == date(item.deadline, zone)) {
                onDueDay[item] = (onDueDay[item] ?: 0) - minutes
                onDueDayHome[item] = (onDueDayHome[item] ?: 0) - home
            }
            free[day] = free.getValue(day) + minutes
            freeHome[day] = freeHome.getValue(day) + home
            stepsOn.merge(item.task.id to day, -1, Int::plus)
            if (item.soft) softUsed[day] = (softUsed[day] ?: 0) - minutes
            if (day == opening(item)) openingPlaced.getOrPut(day) { mutableListOf() } += item.notBefore!! to -minutes
        }
        // A day you've limited ([Input.dayCaps]) is never overfilled, not even by overdue work,
        // which otherwise piles into today: it goes to the next day with the time.
        // Days you've limited: by a day's limit, or by busy times of yours within its hours.
        // By the time they take from a day's windows itself, before the shortest school time used is
        // applied: that threshold, crossed as a minute passes, mustn't make the day limited or not.
        val limited = input.dayCaps.keys + if (input.hardBusy.isEmpty()) emptySet() else days.filter { workMinutes(it, input) < workMinutes(it, input.copy(hardBusy = emptyList())) }
        fun capped(day: LocalDate, minutes: Int) = day in limited && free.getValue(day) < minutes
        // The first day a task's work can start: today, or later if it says so (an Anki deck
        // whose new cards for today are used up).
        fun firstDay(item: Item): LocalDate =
            item.notBefore?.takeIf { it > input.now }?.let { minOf(horizon, maxOf(today, date(it, zone))) } ?: today
        fun countOn(item: Item, day: LocalDate) = stepsOn[item.task.id to day] ?: 0
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
        // Work you put on a day or at a time (PL-04) is placed first: it has its day's time before the rest.
        val (pinned, unpinned) = placeable.partition { it.pinnedDay != null }
        val (urgent, later) = unpinned.partition { date(it.deadline, zone) <= today }

        // Work with a real deadline its days can't hold (PL-02): said, once a task, with the numbers:
        // a task in runs (its blocks' dates) for all of its runs with the deadline, against the time free
        // before the first of them was placed.
        val wontFit = mutableListOf<WontFit>()
        val runsMinutes = items.filter { it.hard }.groupBy { it.task.id to it.deadline }.mapValues { (_, runs) -> runs.sumOf { it.minutes } }
        val runsFree = HashMap<Pair<String, Long>, Int>()
        /**
         * The free time [item] has before its deadline itself, at its turn (before it takes any): from
         * the first day it can be done to its deadline's day, or the day before for a morning
         * deadline. Not to its last usable day: the safety margin is the app's own, and work inside
         * it, flagged behind, may still be done in time.
         */
        fun freeUntilDue(item: Item, from: LocalDate): Int {
            val last = minOf(horizon, lastDayBefore(item, input))
            val dueDay = date(item.deadline, zone)
            // As much of each day as it can have: on a day it can only start late, the time after its
            // start; on its deadline's own day, only the time before the deadline.
            return generateSequence(from) { it.plusDays(1) }.takeWhile { it <= last }.sumOf { day ->
                val room = room(item, day).coerceAtLeast(0)
                if (day == dueDay) minOf(room, capacityBefore(day, item.deadline, input)) else room
            }
        }
        /** [item]'s deadline is within the plan's reach: only then were all its days before it counted. */
        fun reachable(item: Item): Boolean = lastDayBefore(item, input) <= horizon
        /**
         * Whether a piece of [item] behind on [day] had no room before its deadline itself: on a day
         * after it, its task [short] of time, or a margin no stricter than the deadline, so behind its
         * last usable day is behind the deadline. Else it's behind the safety margin only.
         */
        fun noRoom(item: Item, day: LocalDate, short: Boolean): Boolean =
            day > lastDayBefore(item, input) || short || lastUsableDay(item, input) >= lastDayBefore(item, input)
        /** [item], [short] of time before its deadline still to come: said, once a task. */
        fun sayIfShort(item: Item, free: Int, short: Boolean) {
            // The time free before the first of its runs with this deadline took any: theirs together.
            val key = item.task.id to item.deadline
            val before = runsFree.getOrPut(key) { free }
            if (!item.hard || item.deadline < input.now || wontFit.any { it.taskId == item.task.id }) return
            if (short) wontFit += WontFit(item.task.id, item.task.title, runsMinutes[key] ?: item.minutes, before, item.deadline)
        }

        // Due by today (overdue, or later today): into today, from the first day it can be.
        fun placeNow(item: Item) {
            val perDay = item.task.stepsPerDay
            val assigned = arrayOfNulls<LocalDate>(item.chunks.size)
            var day = maxOf(firstDay(item), floorOf(item) ?: today)
            val freeBefore = freeUntilDue(item, day)
            // Short of time before its deadline, or (found as it's placed) carried past it by a per-day limit.
            var short = reachable(item) && item.minutes > freeBefore
            for (i in item.chunks.indices) {
                // Past its deadline it's due now, but a task that can only go so far a day carries
                // on over the next days, each step to a day with both a step and the time free (not
                // tonight once tonight's hours are gone), and undated work goes to a day with the time
                // free and within its daily allowance ([room] lets a day's first undated chunk be
                // bigger than the allowance, never bigger than the time left).
                fun waits(day: LocalDate) =
                    (perDay != null && (countOn(item, day) >= perDay || room(item, day, i) < item.chunks[i].minutes)) ||
                        (item.soft && room(item, day, i) < item.chunks[i].minutes) ||
                        capped(day, item.chunks[i].minutes)
                while (day < horizon && waits(day)) day = day.plusDays(1)
                // Beyond the horizon, the rest goes unplanned rather than breaking a per-day task's
                // limit or undated work's allowance.
                if (waits(day)) break
                assigned[i] = day
                take(item, day, i)
                // Due today but only possible from a later day (a deck whose next cards come
                // tomorrow): it misses its deadline, so it's behind.
                val why = if (item.hard && item.deadline < input.now) Why.Overdue else if (item.soft) Why.Soft else Why.DueSoon
                if (day > lastDayBefore(item, input)) short = true
                placed.getValue(day) += chunk(item, i, behind = day > date(item.deadline, zone), why = why, noRoom = noRoom(item, day, short))
            }
            // Due later today and short of the time before it: said (overdue work has missed it already).
            sayIfShort(item, freeBefore, short || assigned.any { it == null })
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
            val freeBefore = freeUntilDue(item, earliest)
            fun fits(day: LocalDate, i: Int) =
                room(item, day, i) >= item.chunks[i].minutes && (perDay == null || countOn(item, day) < perDay)
            fun latest(from: LocalDate, i: Int): LocalDate? =
                generateSequence(from) { it.minusDays(1) }.takeWhile { it >= earliest }.firstOrNull { fits(it, i) }

            // Backwards, one chunk per day while there are days with room.
            var cursor = lastUsable
            for (i in item.chunks.indices.reversed()) {
                val day = latest(cursor, i) ?: break
                assigned[i] = day
                take(item, day, i)
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
                take(item, chosen, i)
            }
            // Undated work, a task limited per day, and work on a day you've limited can't crowd into
            // today beyond what they allow. When they don't fit before the deadline, they're done in order from the first
            // day they can be, so that as much as can be is done by the deadline and the rest
            // follows it; what fits nowhere within the horizon goes unplanned.
            if (carriesOn && assigned.any { it == null }) {
                for (i in item.chunks.indices) {
                    assigned[i]?.let { give(item, it, i) }
                    assigned[i] = null
                }
                var from = earliest
                // Not past a run of its task after it, where that's placed: what fits nowhere before
                // then goes unplanned, rather than out of order.
                val until = minOf(horizon, ceilingOf(item) ?: horizon)
                for (i in item.chunks.indices) {
                    val day = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.firstOrNull { fits(it, i) } ?: break
                    assigned[i] = day
                    take(item, day, i)
                    from = day
                }
            }
            // Short of time before its deadline: a piece with no day, one carried past the deadline's day,
            // or one no single day before it held (put into the first, over its time), where the margin
            // is no stricter than the deadline, so its days were the deadline's.
            val forced = behind.any { it } && lastUsableDay(item, input) >= lastDayBefore(item, input)
            val short = reachable(item) && (item.minutes > freeBefore || forced || assigned.any { it == null || it > lastDayBefore(item, input) })
            item.chunks.indices.forEach { i ->
                val day = assigned[i] ?: return@forEach
                placed.getValue(day) += chunk(item, i, behind = behind[i] || day > dueBy, why = why, noRoom = noRoom(item, day, short))
            }
            sayIfShort(item, freeBefore, short)
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
            // For a test: as Claude read it of the task as it is now, not of what it said before.
            val spaced = item.task.kind == Kind.Revision || Enrichments.current(byId[item.task.id] ?: item.task)?.testDate != null
            val earliest = maxOf(firstDay(item), floorOf(item) ?: today)
            val last = maxOf(earliest, minOf(horizon, lastUsableDay(item, input), ceilingOf(item) ?: horizon))
            val window = generateSequence(earliest) { it.plusDays(1) }.takeWhile { it <= last }.toList()
            val n = item.chunks.size
            // Each day's share: the free time it has left, none where a limit you set is reached.
            val weight = window.map { day -> if (capped(day, MIN_CHUNK)) 0 else room(item, day).coerceAtLeast(0) }
            val total = weight.sum()
            val perDay = item.task.stepsPerDay
            val weekly = window.size >= 7
            val assigned = arrayOfNulls<LocalDate>(n)
            val whys = arrayOfNulls<Why>(n)
            val freeBefore = freeUntilDue(item, earliest)
            fun fits(day: Int, i: Int) =
                room(item, window[day], i) >= item.chunks[i].minutes && (perDay == null || countOn(item, window[day]) < perDay)
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
            // with room for it, a day after the one before where [apart] (revision's gap): tried by
            // placing them, then taken back.
            fun restFollows(from: Int, i: Int, apart: Boolean): Boolean {
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
                    take(item, window[day], k)
                    tried += day to k
                    if (apart) day++
                }
                tried.forEach { (d, k) ->
                    give(item, window[d], k)
                    assigned[k] = null
                }
                return follows
            }
            // Whether piece [i] can go on [day] with the pieces after it still to follow in order: a
            // big last piece that only a weekend holds keeps the ones before it from passing it.
            fun usable(day: Int, i: Int, apart: Boolean = false): Boolean {
                if (!fits(day, i)) return false
                assigned[i] = window[day]
                take(item, window[day], i)
                val follows = restFollows(if (apart) day + 1 else day, i + 1, apart)
                give(item, window[day], i)
                assigned[i] = null
                return follows
            }
            // The nearest usable day to [at] for piece [i], between [lo] and [hi], earlier first; [apart],
            // with the pieces after it still able to keep revision's day apart.
            fun nearest(at: Int, lo: Int, hi: Int, i: Int, apart: Boolean = false): Int? {
                if (lo > hi) return null
                val start = at.coerceIn(lo, hi)
                for (step in 0..(hi - lo)) {
                    val earlier = start - step
                    if (earlier >= lo && usable(earlier, i, apart)) return earlier
                    val later = start + step
                    if (step > 0 && later <= hi && usable(later, i, apart)) return later
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
                // Revision's look-ahead keeps its gap where a day allows (else as without it).
                val kept = (if (spaced) nearest(minOf(at, within), apart, within, i, apart = true) ?: nearest(at, apart, window.lastIndex, i, apart = true) else null)
                    ?: nearest(minOf(at, within), apart, within, i) ?: nearest(at, apart, window.lastIndex, i)
                // Where it would go without them: what tells a piece the week or the gap moved.
                val bound = apart > from || within < window.lastIndex
                val loose = if (kept == null || bound) nearest(at, from, window.lastIndex, i) else kept
                val day = kept ?: loose
                if (day == null) {
                    // No day for it with the rest to follow: the window can't hold the task in order
                    // (only ever its first piece, as each piece's day was taken with the rest able to follow).
                    for (k in 0 until i) give(item, assigned[k]!!, k)
                    placeBack(item, Why.Packed)
                    return
                }
                whys[i] = when {
                    n == 1 -> Why.MostRoom
                    day == loose -> Why.Spread
                    // Moved by the gap where revision's would have gone without it (before the day after its last piece).
                    spaced && (loose == null || loose < apart) -> Why.RevisionGap
                    else -> Why.MinimumTouch
                }
                assigned[i] = window[day]
                take(item, window[day], i)
                previous = day
            }
            // Behind only where it can't start before its last usable day is past (a start after it).
            val dueBy = lastUsableDay(item, input)
            val short = reachable(item) && (item.minutes > freeBefore || assigned.any { it!! > lastDayBefore(item, input) })
            item.chunks.indices.forEach { i -> placed.getValue(assigned[i]!!) += chunk(item, i, behind = assigned[i]!! > dueBy, why = whys[i]!!, noRoom = noRoom(item, assigned[i]!!, short)) }
            sayIfShort(item, freeBefore, short)
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
        pinned.sortedWith(byDeadline).forEach { if (date(it.deadline, zone) <= today) placeNow(it) else placeBack(it, Why.Pinned) }
        urgent.filter(::soon).sortedWith(byDeadline).forEach(::placeNow)
        later.filter(::soon).sortedWith(order).forEach { placeBack(it, Why.DueSoon) }
        urgent.filterNot(::soon).sortedWith(byDeadline).forEach(::placeNow)
        val (undated, dated) = later.filterNot(::soon).partition { it.soft }
        dated.sortedWith(order).forEach(::placeSpread)
        undated.sortedWith(order).forEach { placeBack(it, Why.Soft) }

        // Work not open yet with a real deadline: said not to fit where even its days from its opening,
        // all of their time its own, can't hold it, so the warning comes before it opens.
        for (gap in unplanned.filter { it.reason == Unplanned.Reason.NotOpen }) {
            val task = byId[gap.taskId] ?: continue
            val opens = task.availableFrom ?: continue
            val deadline = task.deadlineAt(input.now)?.takeIf { task.hardAt(input.now) && it > opens } ?: continue
            // Work only for home has only the evening's time (PL-15).
            val home = pieces(task, input, held[task.id].orEmpty()).let { it.isNotEmpty() && it.all { piece -> piece.where == Where.Home } }
            val last = lastDayBefore(deadline, input, home)
            if (last > horizon || wontFit.any { it.taskId == task.id }) continue
            val first = date(opens, zone)
            // All of each day's time it can use (the most it could have): on its opening day the time
            // after it opens, on its deadline's own day only the time before the deadline.
            val room = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.sumOf { day ->
                val whole = if (day == first) capacityFrom(day, opens, home) else capacities.getValue(day).let { if (home) it.homeMin else it.anyMin }
                if (day == date(deadline, zone)) minOf(whole, capacitiesBefore(day, deadline, input).let { if (home) it.homeMin else it.anyMin }) else whole
            }
            if (gap.minutes > room) wontFit += WontFit(task.id, task.title, gap.minutes, room, deadline)
        }

        // Each day with windows laid out in time (PL-12): a slot for each piece, breaks, outside breaks
        // on a weekend or a day off, your pins, and today's starts kept from the last plan.
        val buckets = days.map { day ->
            val chunks = placed.getValue(day).sortedWith(ORDER)
            val windows = input.windows[day] ?: return@map DayBucket(day, capacity.getValue(day), chunks)
            val (slots, unslotted) = Slotter.slot(
                day, chunks, windows, input.busy + input.hardBusy, input.pins + atPins,
                previous = if (day == today) input.previousSlots else emptyMap(),
                now = input.now, zone = zone, settings = input.settings,
                outside = (input.dayKinds[day] ?: DayKind.of(day)) == DayKind.Weekend,
            ) { chunk ->
                // A real deadline on this day: its piece done before the deadline's time.
                val due = Instant.ofEpochMilli(chunk.deadline).atZone(zone)
                if (chunk.hard && due.toLocalDate() == day) due.hour * 60 + due.minute else null
            }
            DayBucket(day, capacity.getValue(day), chunks, slots, unslotted)
        }
        // A task's runs left out for the same reason are one line: their pieces and minutes together, the soonest opening.
        val gaps = unplanned.groupBy { it.taskId to it.reason }.values.map { same ->
            same.first().copy(minutes = same.sumOf { it.minutes }, pieces = same.sumOf { it.pieces }, at = same.mapNotNull { it.at }.minOrNull())
        }.sortedWith(compareBy({ it.reason.ordinal }, { it.at ?: Long.MAX_VALUE }, { it.title }, { it.taskId }))
        return Plan(input.now, today, buckets, events.sortedWith(compareBy(nullsLast()) { it.dueAt }), gaps, wontFit)
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
    private fun lastUsableDay(item: Item, input: Input): LocalDate = item.pinnedDay ?: run {
        val margin = input.calibration.marginDays[item.task.kind] ?: input.settings.marginDays
        if (margin == 0) lastDayBefore(item, input) else lastUsableDay(item.deadline, item.task.kind, input)
    }

    /** [day]'s time for work before [deadline] (on that day): its windows, or your hours, cut at the deadline's minute. */
    private fun capacityBefore(day: LocalDate, deadline: Long, input: Input): Int = capacitiesBefore(day, deadline, input).anyMin

    /** [day]'s time before [deadline], the evening's and all of it: its windows, or your hours, cut at the deadline's minute. */
    private fun capacitiesBefore(day: LocalDate, deadline: Long, input: Input): Capacity {
        val due = Instant.ofEpochMilli(deadline).atZone(input.zone)
        val minute = due.hour * 60 + due.minute
        val hours = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) input.settings.weekendHours else input.settings.weekdayHours
        val windows = (input.windows[day] ?: listOf(DayWindow(hours.startMin, hours.endMin, WindowKind.Evening)))
            .mapNotNull { w -> w.takeIf { it.startMin < minute }?.copy(endMin = minOf(w.endMin, minute)) }
        return capacityOf(day, input.copy(windows = input.windows + (day to windows)))
    }

    /**
     * The last day work can be done by [deadline] itself, the safety margin aside: its day where it
     * has time before the deadline (PL-15, Q-52: a window starting early enough, as break does before
     * a 13:00 deadline; without windows, an afternoon or evening deadline's day), else the day before.
     */
    fun lastDayBefore(deadline: Long, input: Input): LocalDate = lastDayBefore(deadline, input, home = false)

    /** [lastDayBefore] for work only for [home] (PL-15): its day counts only by an evening window before it. */
    private fun lastDayBefore(deadline: Long, input: Input, home: Boolean): LocalDate {
        val due = Instant.ofEpochMilli(deadline).atZone(input.zone)
        val minute = due.hour * 60 + due.minute
        val usable = input.windows[due.toLocalDate()]?.any { (!home || it.kind == WindowKind.Evening) && it.startMin + MIN_CHUNK <= minute } ?: (due.toLocalTime() >= NOON)
        return if (usable) due.toLocalDate() else due.toLocalDate().minusDays(1)
    }

    /** [item]'s [lastDayBefore]: by the windows its work can use. */
    private fun lastDayBefore(item: Item, input: Input): LocalDate = lastDayBefore(item.deadline, input, item.home)

    private fun lastUsableDay(deadline: Long, kind: Kind, input: Input): LocalDate {
        val due = Instant.ofEpochMilli(deadline).atZone(input.zone)
        val margin = input.calibration.marginDays[kind] ?: input.settings.marginDays
        // With no margin, the deadline's own day where it has time before the deadline.
        return if (margin == 0) lastDayBefore(deadline, input) else due.toLocalDate().minusDays(margin.toLong())
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
        val where = whereOf(task)
        if (task.subSteps.isNotEmpty()) {
            // Each step's minutes as planned; where you gave the whole task's minutes, shared out to add up to them.
            val planned = task.plannedStepMinutes(multiplier)
            // Its own steps, by their place in its list: not those its deck tasks hold.
            val own = task.subSteps.withIndex().filterNot { (_, step) -> step.ankiSections.isNotEmpty() && held.containsAll(step.ankiSections) }
            val left = own.filterNot { it.value.done }
            // All done: an assignment is still to hand in; an email's blocks done, nothing is left of it.
            // One of your own is finished when its steps are (AppGraph sees to it).
            if (left.isEmpty()) return if (task.source == Source.Gmail || task.source == Source.Own) emptyList() else listOf(Piece("finish and hand in", MIN_CHUNK, where = where))
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
                Piece(step.title, full - off, from = step.from, due = step.dueAt, index = index, where = Where.of(step.where) ?: where)
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
                if (off == 0) piece else Piece(piece.step, piece.minutes - off, from = piece.from, due = piece.due, index = piece.index, where = piece.where)
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
            Piece(null, minutes, box = box.takeIf { count > 1 }, where = where)
        }
    }

    /**
     * Where [task]'s work can be done, where its steps don't say (PL-15): your own task's as you set it;
     * an Anki deck's anywhere; Teams homework at home (the laptop, the books); the rest anywhere.
     */
    private fun whereOf(task: TaskItem): Where = when (task.source) {
        Source.Own -> Where.of(task.extra[OwnTasks.EXTRA_WHERE]) ?: Where.Anywhere
        Source.Teams -> if (task.kind == Kind.Homework) Where.Home else Where.Anywhere
        else -> Where.Anywhere
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
        // A day you gave for all of it is the only day it goes on (PL-04).
        val kept = keptDay(task, task.realDeadline, input)
        val from = kept ?: task.notBefore?.let { date(it, zone) }?.takeIf { it > today } ?: today
        fun most(until: LocalDate) = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.maxOfOrNull { capacity(it, input) } ?: 0
        val lastUsable = kept ?: lastUsableDay(deadlineOf(task, input), task.kind, input)
        val before = most(lastUsable)
        if (before >= MIN_CHUNK) return before
        return most(minOf(maxOf(lastUsable, today.plusDays(MIN_HORIZON_DAYS)), today.plusDays(MAX_HORIZON_DAYS)))
    }

    /**
     * The deadline [due] is held to (PL-14): itself where it's the [real] one, even gone by, or still
     * to come; gone by, the real one behind it, if there is one; else none, and the work is undated.
     */
    private fun plannedDue(due: Long?, real: Long?, now: Long): Long? = due?.takeIf { it == real || it >= now } ?: real

    /**
     * The deadline [task] is planned against: [plannedDue], a plan's date moot past a time you gave
     * (PL-04), or for undated work its soft week's end.
     */
    private fun deadlineOf(task: TaskItem, input: Input): Long {
        val real = task.realDeadline
        val at = pinnedTime(task, real, input)
        return plannedDue(task.dueAt, real, input.now)?.takeIf { d -> at == null || d == real || d >= at } ?: softWeek(at ?: task.dueAt ?: task.firstSeenAt, input)
    }

    /**
     * The day you gave for all of [task] (PL-04), where it's still to come, not before the day it can
     * start, and not after its real deadline's ([real]).
     */
    private fun keptDay(task: TaskItem, real: Long?, input: Input): LocalDate? {
        val day = task.userOnDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val start = task.notBefore?.let { date(it, input.zone) }
        return day.takeIf { it >= date(input.now, input.zone) && (start == null || it >= start) && (real == null || it <= date(real, input.zone)) }
    }

    /**
     * The time you gave for [task]'s next piece (PL-04), where its day hasn't gone, it's no earlier
     * than the task can start, and it's before its real deadline ([real]).
     */
    private fun pinnedTime(task: TaskItem, real: Long?, input: Input): Long? {
        val at = task.userAtTime ?: return null
        val end = date(at, input.zone).plusDays(1).atStartOfDay(input.zone).toInstant().toEpochMilli() - 1
        return at.takeIf { end >= input.now && (task.notBefore == null || it >= task.notBefore) && (real == null || it < real) }
    }

    /**
     * A soft deadline: `softDeadlineDays` on from [from], in calendar days where you are (so a week
     * is a week across the clocks changing), and a week more at a time until it's still to come.
     * Undated work's week, gone by undone, is followed by the next rather than overdue (PL-14); it
     * doesn't move a day at a time, so it still comes round by its week's end.
     */
    private fun softWeek(from: Long, input: Input): Long {
        val start = Instant.ofEpochMilli(from).atZone(input.zone)
        val days = input.settings.softDeadlineDays.toLong()
        var weeks = 1L
        while (start.plusDays(weeks * days).toInstant().toEpochMilli() < input.now) weeks++
        return start.plusDays(weeks * days).toInstant().toEpochMilli()
    }

    /**
     * Your hours that day, less what's gone (today) and what the calendar takes. The hours are
     * wall-clock times, so on the days the clocks change they still mean 08:30 to 22:30; the
     * calendar's blocks are merged first, so two that overlap aren't taken off twice.
     */
    fun capacity(day: LocalDate, input: Input): Int = capacityOf(day, input).anyMin

    /**
     * [day]'s time for work (PL-15): each of its windows (or your hours that day, where it has none)
     * less what's gone today and what the calendar takes; a free stretch of a school window under
     * `minSchoolSlotMin` isn't used, as the slotter lays nothing in one. Time taken at no set time ([Input.dayLoads]) comes off the
     * evening first. Each part is what a day of its kind holds of it, as learned (PL-06): the evening's
     * by the day's kind, school's by school's. A limit you set is of the whole day's work, after.
     */
    fun capacityOf(day: LocalDate, input: Input): Capacity {
        val hours = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) input.settings.weekendHours else input.settings.weekdayHours
        val windows = input.windows[day] ?: listOf(DayWindow(hours.startMin, hours.endMin, WindowKind.Evening))
        var evening = 0
        var school = 0
        for (window in windows) {
            if (!window.kind.atSchool) evening += freeIn(day, window.startMin, window.endMin, input)
            else school += freeIn(day, window.startMin, window.endMin, input, minStretch = input.settings.minSchoolSlotMin)
        }
        val load = input.dayLoads[day] ?: 0
        val homeLeft = (evening - load).coerceAtLeast(0)
        val schoolLeft = (school - (load - evening).coerceAtLeast(0)).coerceAtLeast(0)
        val home = (homeLeft * input.calibration.yieldFor(input.dayKinds[day] ?: DayKind.of(day))).roundToInt()
        val atSchool = (schoolLeft * input.calibration.yieldFor(DayKind.School)).roundToInt()
        // A limit is of the whole day's work: what's been done today already comes off it.
        val cap = input.dayCaps[day]?.let { if (day == date(input.now, input.zone)) it - input.workedTodayMin else it } ?: Int.MAX_VALUE
        val any = minOf(home + atSchool, cap).coerceAtLeast(0)
        return Capacity(minOf(home, any), any)
    }

    /** [day]'s free minutes in its windows (or your hours that day), as they are: no threshold, yield or limit. */
    private fun workMinutes(day: LocalDate, input: Input): Int {
        val hours = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) input.settings.weekendHours else input.settings.weekdayHours
        return (input.windows[day] ?: listOf(DayWindow(hours.startMin, hours.endMin, WindowKind.Evening))).sumOf { freeIn(day, it.startMin, it.endMin, input) }
    }

    /**
     * Minutes of [day] from [startMin] to [endMin] (wall-clock times, so on the days the clocks change
     * they still mean those times) less what's gone, today, and what the calendar takes: its blocks
     * merged first, so two that overlap aren't taken off twice.
     */
    private fun freeIn(day: LocalDate, startMin: Int, endMin: Int, input: Input, minStretch: Int = 0): Int {
        val midnight = day.atStartOfDay()
        val end = midnight.plusMinutes(endMin.toLong()).atZone(input.zone).toInstant().toEpochMilli()
        val start = maxOf(midnight.plusMinutes(startMin.toLong()).atZone(input.zone).toInstant().toEpochMilli(), minOf(end, input.now))
        if (end <= start) return 0
        val clipped = (input.busy + input.hardBusy).map { maxOf(start, it.start) to minOf(end, it.end) }.filter { it.second > it.first }.sortedBy { it.first }
        // Each free stretch between busy times, counted where it's [minStretch] minutes or more.
        var free = 0L
        var reached = start
        fun stretch(until: Long) {
            if (until - reached >= minStretch * 60_000L) free += until - reached
        }
        for ((from, to) in clipped) {
            if (from > reached) stretch(from)
            reached = maxOf(reached, to)
        }
        if (end > reached) stretch(end)
        return (free / 60_000L).toInt()
    }

    fun date(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
