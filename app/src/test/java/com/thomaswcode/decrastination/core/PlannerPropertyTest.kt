package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planner's rules, held for any input (AR-04): a seeded generator builds a [Planner.Input] of up
 * to forty tasks, random hours, calendar busy time and limits, and every invariant below is checked
 * on its plan. A failure names its seed; [ITERATIONS] seeds run in CI, and
 * `PLANNER_PROPERTY_ITERATIONS=10000` runs more by hand.
 */
class PlannerPropertyTest {

    /** A random [Planner.Input] from [seed]: the same seed, the same input. */
    private class Gen(seed: Long) {
        private val random = Random(seed)

        val now: Long = Fixtures.at("2026-10-14T17:00") + random.nextLong(14) * DAY + random.nextLong(24) * HOUR
        private val today: LocalDate = Planner.date(now, LONDON)

        private fun <T> pick(vararg options: T): T = options[random.nextInt(options.size)]
        private fun chance(percent: Int) = random.nextInt(100) < percent
        private fun at(day: LocalDate, time: LocalTime) = day.atTime(time).atZone(LONDON).toInstant().toEpochMilli()

        /** A task as [PlannerTest]'s helper makes one, with what the planner reads drawn at random. */
        private fun task(index: Int): TaskItem {
            val source = pick(Source.Teams, Source.PowerPlanner, Source.Gmail, Source.Own)
            val effort = 5 + random.nextInt(296)
            // Steps whose minutes add up to the task's, each at least one.
            val steps = minOf(random.nextInt(7), effort).let { count ->
                shareOut(effort, List(count) { 1 + random.nextInt(10) }).mapIndexed { i, minutes -> SubStep("step ${i + 1}", minutes, where = pick(null, "home", "anywhere")) }
            }
            // Undated; due in the next month; or due a few days back, or today (PL-14's hard and soft dates gone by).
            val dueAt = when {
                chance(20) -> null
                chance(15) -> at(today.minusDays(random.nextLong(6)), pick(LocalTime.of(8, 30), LocalTime.of(23, 59)))
                else -> at(today.plusDays(1L + random.nextInt(30)), pick(LocalTime.of(8, 30), LocalTime.of(23, 59)))
            }
            // An email's date is a real deadline only where Claude quoted it (AI-12), in a reading of it as it is.
            val quoted = if (source == Source.Gmail && dueAt != null) chance(50) else null
            val task = TaskItem(
                id = TaskItem.id(source, "t$index"),
                source = source,
                sourceId = "t$index",
                title = "t$index",
                dueAt = dueAt,
                availableFrom = if (chance(10)) now + HOUR + random.nextLong(10 * DAY) else null,
                kind = pick(Kind.Homework, Kind.Revision, Kind.Admin),
                sourceEffortMin = effort,
                subSteps = steps,
                stepsPerDay = if (chance(20)) 1 + random.nextInt(3) else null,
                notBefore = if (chance(20)) now + random.nextLong(6) * DAY else null,
                // A day for all of it, or a time for its next piece, as you'd give them (PL-04): some gone by, some after its deadline.
                userOnDay = if (chance(5)) today.plusDays(random.nextLong(-1, 14)).toString() else null,
                userAtTime = if (chance(5)) at(today.plusDays(random.nextLong(14)), LocalTime.of(8 + random.nextInt(15), 5 * random.nextInt(12))) else null,
                firstSeenAt = now - random.nextLong(11) * DAY,
                lastSeenAt = now,
            )
            return quoted?.let { task.copy(enrichment = Enrichment(inputHash = Enrichments.inputHash(task), by = "test", at = now, deadline = dueAt, deadlineQuoted = it)) } ?: task
        }

        /** A stretch of [lo]..[hi] (minutes of the day), at least a quarter of an hour, on quarter hours. */
        private fun window(lo: Int, hi: Int): Window {
            val start = lo + 15 * random.nextInt((hi - lo) / 15)
            return Window(start, start + 15 * (1 + random.nextInt((hi - start) / 15)))
        }

        /** Somewhere in the next fortnight, for 30 minutes to three hours. */
        private fun busy(): Busy {
            val start = at(today.plusDays(random.nextLong(14)), LocalTime.MIDNIGHT) + random.nextLong(24 * 60) * MINUTE
            return Busy(start, start + (30 + random.nextInt(151)) * MINUTE)
        }

        private fun day(): LocalDate = today.plusDays(random.nextLong(14))

        val input = Planner.Input(
            tasks = List(1 + random.nextInt(40)) { task(it) },
            now = now,
            zone = LONDON,
            settings = Settings(
                boxMin = pick(25, 30, 45, 60),
                marginDays = random.nextInt(3),
                weekdayHours = window(16 * 60, 22 * 60 + 30),
                weekendHours = window(8 * 60, 23 * 60),
            ),
            busy = List(random.nextInt(6)) { busy() },
            dayLoads = List(random.nextInt(2)) { day() to random.nextInt(241) }.toMap(),
            dayCaps = List(random.nextInt(3)) { day() to random.nextInt(301) }.toMap(),
            hardBusy = List(random.nextInt(3)) { busy() },
            // Kinds of day found to use only part of their free time (PL-06).
            calibration = Calibration(yields = DayKind.entries.filter { chance(25) }.associateWith { 0.5 + random.nextInt(51) / 100.0 }),
            // School days as windows, some of the time (PL-15): break, lunch, maybe a study period, the evening.
            windows = if (!chance(50)) emptyMap() else (0L..30L).map { today.plusDays(it) }.associateWith { day ->
                val weekend = day.dayOfWeek == java.time.DayOfWeek.SATURDAY || day.dayOfWeek == java.time.DayOfWeek.SUNDAY
                if (weekend) {
                    listOf(DayWindow(8 * 60, 22 * 60, WindowKind.Evening))
                } else {
                    listOfNotNull(
                        DayWindow(645, 675, WindowKind.Break),
                        DayWindow(795, 820, WindowKind.Lunch),
                        DayWindow(9 * 60 + random.nextInt(4) * 60, 9 * 60 + random.nextInt(4) * 60 + 55, WindowKind.Study).takeIf { chance(40) && it.endMin <= 795 && it.startMin >= 675 },
                        DayWindow(16 * 60 + 45, 22 * 60, WindowKind.Evening),
                    ).sortedBy { it.startMin }
                }
            },
        )
    }

    @Test
    fun `every plan keeps the planner's rules, whatever it's given`() {
        for (seed in 0L until ITERATIONS) check(seed)
    }

    /** Builds [seed]'s input, plans it, and checks every invariant, naming the seed if one fails. */
    private fun check(seed: Long) {
        val input = Gen(seed).input
        val plan = Planner.plan(input)
        checkCapacity(seed, plan)
        checkDeadlines(seed, plan, input)
        checkWindowedSteps(seed, plan, input)
        checkPerDayLimit(seed, plan, input)
        checkOrder(seed, plan)
        checkCompleteness(seed, plan, input)
        checkDeterminism(seed, plan, input)
        checkStability(seed, plan, input)
        checkMinimumTouch(seed, plan, input)
        checkRevisionGap(seed, plan, input)
        checkWhy(seed, plan, input)
        checkOverdue(seed, plan, input)
        checkWontFit(seed, plan, input)
        checkHome(seed, plan, input)
        checkSlots(seed, plan, input, fresh = true)
        // Laid out again with pins and the last plan's starts drawn on it (PL-12): the same rules, and the pins kept.
        if (input.windows.isNotEmpty()) {
            val again = input.copy(pins = drawPins(seed, plan, input), previousSlots = drawPrevious(seed, plan, input))
            val replanned = Planner.plan(again)
            checkSlots(seed, replanned, again, fresh = false)
            checkPins(seed, replanned, again)
        }
    }

    /**
     * No day holds more than its time, but for overdue work and today's work dated by today (a real
     * deadline or a plan's date: PL-14), which may, and work flagged behind.
     */
    private fun checkCapacity(seed: Long, plan: Plan) {
        for (bucket in plan.buckets) {
            val byToday = { c: Chunk -> bucket.date == plan.today && !c.soft && Planner.date(c.deadline, LONDON) <= plan.today }
            val held = bucket.chunks.filter { !it.behind && !it.overdue && !byToday(it) }.sumOf { it.minutes }
            assertTrue(held <= bucket.capacityMin, "seed=$seed: ${bucket.date} holds $held minutes of its ${bucket.capacityMin}")
        }
    }

    /** A dated task's piece is on or before its deadline's day, or flagged behind or overdue. */
    private fun checkDeadlines(seed: Long, plan: Plan, input: Planner.Input) {
        for (bucket in plan.buckets) for (chunk in bucket.chunks.filter { !it.soft }) {
            val due = Planner.date(chunk.deadline, input.zone)
            assertTrue(bucket.date <= due || chunk.behind || chunk.overdue, "seed=$seed: ${chunk.label} on ${bucket.date}, due $due, not flagged")
        }
    }

    /** A piece that can't start yet is on a day from when it can. */
    private fun checkWindowedSteps(seed: Long, plan: Plan, input: Planner.Input) {
        for (bucket in plan.buckets) for (chunk in bucket.chunks) {
            val from = chunk.availableAt?.let { Planner.date(it, input.zone) } ?: continue
            assertTrue(bucket.date >= from, "seed=$seed: ${chunk.label} on ${bucket.date}, before it can start on $from")
        }
    }

    /** A task limited to so many steps a day has no more than that on any day. */
    private fun checkPerDayLimit(seed: Long, plan: Plan, input: Planner.Input) {
        val limits = input.tasks.mapNotNull { task -> task.stepsPerDay?.let { task.id to it } }.toMap()
        for (bucket in plan.buckets) for ((taskId, pieces) in bucket.chunks.groupBy { it.taskId }) {
            val limit = limits[taskId] ?: continue
            assertTrue(pieces.size <= limit, "seed=$seed: $taskId has ${pieces.size} pieces on ${bucket.date}, $limit a day at most")
        }
    }

    /** A task's pieces are planned in their order: a later day never holds an earlier part. */
    private fun checkOrder(seed: Long, plan: Plan) {
        val days = plan.buckets.flatMap { bucket -> bucket.chunks.map { it to bucket.date } }.groupBy({ it.first.taskId }, { it })
        for ((taskId, placed) in days) {
            val parts = placed.sortedWith(compareBy({ it.second }, { it.first.part })).map { it.first.part }
            assertTrue(parts == parts.sorted(), "seed=$seed: $taskId's parts by day are $parts")
        }
    }

    /** Open work that can be planned is planned, or said to be left out: none goes without a word. */
    private fun checkCompleteness(seed: Long, plan: Plan, input: Planner.Input) {
        val byId = input.tasks.associateBy { it.id }
        val accounted = plan.ordered.map { it.taskId }.toSet() + plan.unplanned.map { it.taskId }
        for (task in input.tasks) {
            if (!task.isOpen || task.kind == Kind.Event || task.hidden || Instructions.waiting(task, byId)) continue
            assertTrue(task.id in accounted, "seed=$seed: ${task.id} is neither planned nor said to be left out")
        }
    }

    /** The same input, the same plan. */
    private fun checkDeterminism(seed: Long, plan: Plan, input: Planner.Input) {
        assertTrue(plan == Planner.plan(input), "seed=$seed: planned twice, two plans")
    }

    /**
     * A minute passing moves nothing planned after today, where it leaves today's time as it was
     * (before today's hours or after them). Within them it can: a day full to the minute loses room
     * for its last piece, which goes to tomorrow and moves what tomorrow held (seed 1702 of 10 000:
     * Saturday 19:00, today's 75 minutes full). That's the plan having no memory of the last one,
     * which PL-13's `previous` (phase 3) gives it; the check takes in today's hours then.
     */
    private fun checkStability(seed: Long, plan: Plan, input: Planner.Input) {
        val later = input.copy(now = input.now + MINUTE)
        val today = Planner.date(input.now, input.zone)
        if (Planner.capacity(today, later) != Planner.capacity(today, input)) return
        val laterPlan = Planner.plan(later)
        // A date the app set passing in that minute plans its work again (PL-14): things move then.
        fun deadlines(p: Plan) = p.ordered.map { it.taskId to it.deadline }.toSet()
        if (deadlines(laterPlan) != deadlines(plan)) return
        fun after(p: Plan) = p.buckets.filter { it.date > today }.flatMap { b -> b.chunks.map { (it.taskId to it.part) to b.date } }.toMap()
        val before = after(plan)
        val next = after(laterPlan)
        for ((piece, day) in before) {
            assertTrue(next[piece] == day, "seed=$seed: ${piece.first} part ${piece.second} moved from $day to ${next[piece]} as a minute passed")
        }
    }

    /**
     * The days a task spread over its window (PL-01) had, and its pieces by day: dated work due after
     * tomorrow, all of it placed and none of it behind (where the window couldn't hold it, it's placed
     * backwards instead, and may leave some out).
     */
    private class Spread(val task: TaskItem, val window: List<LocalDate>, val pieces: List<Pair<LocalDate, Chunk>>)

    private fun spreads(plan: Plan, input: Planner.Input): List<Spread> {
        val zone = input.zone
        val today = Planner.date(input.now, zone)
        val placed = plan.buckets.flatMap { b -> b.chunks.map { b.date to it } }.groupBy { it.second.taskId }
        val leftOut = plan.unplanned.map { it.taskId }.toSet()
        return input.tasks.mapNotNull { task ->
            // Put on a day or at a time (PL-04): where you put it, not spread.
            if (task.userOnDay != null || task.userAtTime != null) return@mapNotNull null
            val due = task.dueAt?.takeIf { task.id !in leftOut } ?: return@mapNotNull null
            val pieces = placed[task.id].orEmpty()
            if (pieces.isEmpty() || pieces.any { (_, c) -> c.soft || c.behind || c.overdue || c.dueToday || c.dueSoon }) return@mapNotNull null
            val first = task.notBefore?.takeIf { it > input.now }?.let { maxOf(today, Planner.date(it, zone)) } ?: today
            val dueAt = java.time.Instant.ofEpochMilli(due).atZone(zone)
            val margin = input.settings.marginDays.toLong()
            val last = dueAt.toLocalDate().minusDays(margin).let { if (margin == 0L && dueAt.toLocalTime() < LocalTime.NOON) it.minusDays(1) else it }
            Spread(task, generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList(), pieces.sortedWith(compareBy({ it.first }, { it.second.part })))
        }
    }

    /** Whether a day had room left for [chunk] of [task] once all was planned: then it had at its turn too. */
    private fun hadRoom(plan: Plan, input: Planner.Input, task: TaskItem, day: LocalDate, chunk: Chunk): Boolean {
        val bucket = plan.bucket(day) ?: return false
        if (task.notBefore?.let { it > input.now && Planner.date(it, input.zone) == day } == true) return false
        if (task.stepsPerDay?.let { limit -> bucket.chunks.count { it.taskId == task.id } >= limit } == true) return false
        // Work for home has the evening's time (PL-15): what home work took of it, and what work done
        // anywhere took past school's time.
        if (chunk.where == Where.Home) {
            val cap = Planner.capacityOf(day, input)
            val anywhere = bucket.chunks.filter { it.where != Where.Home }.sumOf { it.minutes }
            val evening = cap.homeMin - bucket.chunks.filter { it.where == Where.Home }.sumOf { it.minutes } - maxOf(0, anywhere - (cap.anyMin - cap.homeMin))
            if (evening < chunk.minutes) return false
        }
        return bucket.capacityMin - bucket.plannedMin >= chunk.minutes
    }

    /** Over a window of a week or more, no gap of more than a week between pieces where a day within it had room. */
    private fun checkMinimumTouch(seed: Long, plan: Plan, input: Planner.Input) {
        for (spread in spreads(plan, input).filter { it.window.size >= 7 }) {
            for (i in 1 until spread.pieces.size) {
                val (before, _) = spread.pieces[i - 1]
                val (day, chunk) = spread.pieces[i]
                if (java.time.temporal.ChronoUnit.DAYS.between(before, day) <= 7) continue
                val week = generateSequence(before.plusDays(1)) { it.plusDays(1) }.take(7).filter { it in spread.window }
                assertTrue(week.none { hadRoom(plan, input, spread.task, it, chunk) }, "seed=$seed: ${spread.task.id} part ${chunk.part} on $day, more than a week after $before, with room in between")
            }
        }
    }

    /**
     * Revision's last piece isn't on the day of the one before where a later day in its window had
     * room. Only the last: an earlier piece may rightly keep to its day so the ones after it can follow.
     */
    private fun checkRevisionGap(seed: Long, plan: Plan, input: Planner.Input) {
        for (spread in spreads(plan, input).filter { it.task.kind == Kind.Revision && it.pieces.size >= 2 }) {
            val (before, _) = spread.pieces[spread.pieces.lastIndex - 1]
            val (day, chunk) = spread.pieces.last()
            if (day != before) continue
            val later = spread.window.filter { it > day }
            assertTrue(later.none { hadRoom(plan, input, spread.task, it, chunk) }, "seed=$seed: ${spread.task.id} part ${chunk.part} on $day with the piece before it, a later day having room")
        }
    }

    /**
     * Every piece says why it's on its day (PL-07), as its flags do: overdue work is overdue, undated
     * work soft, work past its deadline short of room, work due soon due soon, the rest spread.
     */
    private fun checkWhy(seed: Long, plan: Plan, input: Planner.Input) {
        val placing = setOf(Why.DueSoon, Why.Spread, Why.MinimumTouch, Why.RevisionGap, Why.MostRoom, Why.Packed)
        val byId = input.tasks.associateBy { it.id }
        for (bucket in plan.buckets) for (chunk in bucket.chunks) {
            // Where you put it (PL-04): only a task you put on a day or at a time, and never overdue work.
            if (chunk.why == Why.Pinned) {
                val task = byId.getValue(chunk.taskId)
                assertTrue((task.userOnDay != null || task.userAtTime != null) && !chunk.overdue, "seed=$seed: ${chunk.taskId} part ${chunk.part} says Pinned")
                continue
            }
            val allowed = when {
                chunk.overdue -> setOf(Why.Overdue)
                chunk.soft -> setOf(Why.Soft)
                // Behind the deadline itself, no room; behind only the safety margin, the reason it was placed.
                chunk.behind -> placing + Why.NoRoom
                chunk.dueSoon -> setOf(Why.DueSoon)
                else -> placing - Why.DueSoon
            }
            assertTrue(chunk.why in allowed, "seed=$seed: ${chunk.taskId} part ${chunk.part} says ${chunk.why}, not one of $allowed")
            // A day apart is revision's alone (no test dates are drawn here).
            assertTrue(chunk.why != Why.RevisionGap || chunk.kind == Kind.Revision, "seed=$seed: ${chunk.taskId} part ${chunk.part}, ${chunk.kind}, says RevisionGap")
            // A piece alone in its run: a task in one piece, or the rest of one after a piece at a time you gave (PL-04).
            assertTrue(chunk.why != Why.MostRoom || chunk.parts == 1 || byId.getValue(chunk.taskId).userAtTime != null, "seed=$seed: ${chunk.taskId} part ${chunk.part} of ${chunk.parts} says MostRoom")
            // Placed after its deadline's own last day: there was no room before it.
            val lastDay = Planner.lastDayBefore(chunk.deadline, input)
            if (chunk.behind && !chunk.soft && bucket.date > lastDay) assertTrue(chunk.why == Why.NoRoom, "seed=$seed: ${chunk.taskId} part ${chunk.part} on ${bucket.date}, after its deadline's day, says ${chunk.why}")
        }
    }

    /** Only a real deadline gone by is overdue (PL-14): never a date the app or Claude set. */
    private fun checkOverdue(seed: Long, plan: Plan, input: Planner.Input) {
        val byId = input.tasks.associateBy { it.id }
        for (chunk in plan.ordered.filter { it.overdue }) {
            val task = byId.getValue(chunk.taskId)
            assertTrue(task.hardDeadline && task.dueAt!! < input.now, "seed=$seed: ${chunk.taskId} part ${chunk.part} overdue, its deadline ${task.dueAt} ${if (task.hardDeadline) "real" else "a plan"}")
        }
    }

    /**
     * What's said won't fit (PL-02): a task with a real deadline still to come, once, with more left
     * than was free before it, or some of it fitting nowhere.
     */
    private fun checkWontFit(seed: Long, plan: Plan, input: Planner.Input) {
        val byId = input.tasks.associateBy { it.id }
        assertTrue(plan.wontFit.map { it.taskId }.let { it.size == it.toSet().size }, "seed=$seed: a task said twice not to fit")
        for (short in plan.wontFit) {
            val task = byId.getValue(short.taskId)
            assertTrue(task.hardDeadline && task.dueAt!! >= input.now, "seed=$seed: ${short.taskId} said not to fit, its deadline the app's or gone")
            val leftOut = plan.unplanned.any { it.taskId == task.id }
            // Or carried past its deadline's own last day (a per-day limit), whatever the minutes free.
            val late = plan.buckets.any { b -> b.chunks.any { c -> c.taskId == task.id && b.date > Planner.lastDayBefore(c.deadline, input) } }
            // Or a piece no single day held, put behind into the first.
            val forced = plan.chunksOf(task.id).any { it.behind }
            assertTrue(short.leftMin > short.freeMin || leftOut || late || forced, "seed=$seed: ${task.id} said not to fit: ${short.leftMin} min left, ${short.freeMin} free")
        }
    }

    /** Work only for home is in the evening's time (PL-15): never more of it on a day than that holds, but for work flagged as checkCapacity allows. */
    private fun checkHome(seed: Long, plan: Plan, input: Planner.Input) {
        for (bucket in plan.buckets) {
            val byToday = { c: Chunk -> bucket.date == plan.today && !c.soft && Planner.date(c.deadline, LONDON) <= plan.today }
            val home = bucket.chunks.filter { it.where == Where.Home && !it.behind && !it.overdue && !byToday(it) }.sumOf { it.minutes }
            val evening = Planner.capacityOf(bucket.date, input).homeMin
            assertTrue(home <= evening, "seed=$seed: ${bucket.date} holds $home minutes of home work in its evening's $evening")
        }
    }

    /** [minute] of [date] in London, epoch millis. */
    private fun at(date: LocalDate, minute: Int): Long = date.atStartOfDay().plusMinutes(minute.toLong()).atZone(LONDON).toInstant().toEpochMilli()

    /** [time] rounded up to the next five minutes, as the slotter starts today from it. */
    private fun onFive(time: Long): Long = Math.floorDiv(time + 5 * MINUTE - 1, 5 * MINUTE) * (5 * MINUTE)

    /** Up to three of [plan]'s pieces on days with windows pinned at a time within one of the day's windows, from now on, well apart. */
    private fun drawPins(seed: Long, plan: Plan, input: Planner.Input): Map<String, Long> {
        val random = Random(seed xor 0x5107L)
        // Not a task with a time of its own (PL-04), whose own pin holds its piece.
        val timed = input.tasks.filter { it.userAtTime != null }.map { it.id }.toSet()
        val choices = plan.buckets.filter { input.windows[it.date] != null }.flatMap { b -> b.chunks.filter { it.taskId !in timed }.map { b.date to it } }
        val pins = HashMap<String, Long>()
        val taken = mutableListOf<Pair<Long, Long>>()
        if (choices.isEmpty()) return pins
        repeat(3) {
            val (date, chunk) = choices[random.nextInt(choices.size)]
            val windows = input.windows.getValue(date).filter { it.minutes >= chunk.minutes }.takeIf { it.isNotEmpty() } ?: return@repeat
            val window = windows[random.nextInt(windows.size)]
            val start = at(date, window.startMin + 5 * random.nextInt((window.minutes - chunk.minutes) / 5 + 1))
            val around = start - 20 * MINUTE to start + (chunk.minutes + 20) * MINUTE
            if (start < input.now || chunk.key in pins || taken.any { it.first < around.second && around.first < it.second }) return@repeat
            pins[chunk.key] = start
            taken += around
        }
        return pins
    }

    /** About half of today's pieces given a start in the last plan, within one of today's windows: some gone by, some under way, some to come. */
    private fun drawPrevious(seed: Long, plan: Plan, input: Planner.Input): Map<String, Long> {
        val random = Random(seed xor 0x9e3L)
        val today = plan.todayBucket ?: return emptyMap()
        val windows = input.windows[today.date] ?: return emptyMap()
        return today.chunks.filter { random.nextBoolean() }.mapNotNull { chunk ->
            val fits = windows.filter { it.minutes >= chunk.minutes }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val window = fits[random.nextInt(fits.size)]
            chunk.key to at(today.date, window.startMin + 5 * random.nextInt((window.minutes - chunk.minutes) / 5 + 1))
        }.toMap()
    }

    /**
     * Each day with windows is laid out in time (PL-12): every piece in a slot or unslotted, once;
     * slots clear of each other and of busy time, each in a window of its own kind, work for home
     * only in the evening (a pin aside: where you put it), with a break between pieces in one window;
     * breaks outside only on a weekend's kind of day, as many as allowed; nothing over before now
     * today, and with nothing kept, nothing started before it; a piece no earlier than it opens, and
     * one due that day at a real deadline over by then.
     */
    private fun checkSlots(seed: Long, plan: Plan, input: Planner.Input, fresh: Boolean) {
        val settings = input.settings
        val busy = input.busy + input.hardBusy
        val earliest = onFive(input.now)
        for (bucket in plan.buckets) {
            val windows = input.windows[bucket.date]
            if (windows == null) {
                assertTrue(bucket.slots.isEmpty() && bucket.unslotted.isEmpty(), "seed=$seed: ${bucket.date} is laid out with no windows")
                continue
            }
            val work = bucket.slots.filter { it.kind == SlotKind.Work }
            assertEquals(bucket.chunks.map { it.key }.sorted(), (work.map { it.chunk!!.key } + bucket.unslotted.map { it.key }).sorted(), "seed=$seed: ${bucket.date}'s pieces, slotted or not")
            for ((a, b) in bucket.slots.sortedBy { it.start }.zipWithNext()) assertTrue(a.end <= b.start, "seed=$seed: ${bucket.date} has ${a.key} and ${b.key} overlapping")
            // The part of a slot from now on: one under way (kept, or a pin) may have started before its window.
            fun windowOf(slot: Slot) = windows.indexOfFirst { at(bucket.date, it.startMin) <= (if (slot.end <= earliest) slot.start else maxOf(slot.start, earliest)) && slot.end <= at(bucket.date, it.endMin) }
            for (slot in bucket.slots) {
                // One just over, kept for its grace (PL-13): the past, not checked against now.
                if (slot.end <= earliest) {
                    assertTrue(slot.end + Slotter.GRACE_MS > input.now && slot.chunk != null, "seed=$seed: ${slot.key} is long over")
                    continue
                }
                val window = windows.getOrNull(windowOf(slot))
                assertTrue(window != null && window.kind == slot.window, "seed=$seed: ${bucket.date} ${slot.key} isn't within a window of its kind")
                val from = maxOf(slot.start, earliest)
                assertTrue(busy.none { it.start < slot.end && from < it.end }, "seed=$seed: ${bucket.date} ${slot.key} is in busy time")
                // A pin under way (a task's own time, PL-04) is kept where it started.
                if (fresh && !slot.pinned) assertTrue(slot.start >= earliest, "seed=$seed: ${slot.key} starts before now")
                val chunk = slot.chunk ?: continue
                if (slot.pinned) continue
                assertTrue(chunk.where != Where.Home || !slot.window.atSchool, "seed=$seed: ${chunk.key}, for home, is at school (${slot.window})")
                chunk.availableAt?.let { assertTrue(slot.start >= it, "seed=$seed: ${chunk.key} starts before it opens") }
                if (chunk.hard && Planner.date(chunk.deadline, LONDON) == bucket.date) assertTrue(slot.end <= chunk.deadline, "seed=$seed: ${chunk.key} is over after its deadline")
            }
            for ((_, slots) in work.groupBy(::windowOf)) for ((a, b) in slots.sortedBy { it.start }.zipWithNext()) {
                assertTrue(b.start - a.end >= settings.slotBreakMin * MINUTE, "seed=$seed: ${bucket.date} has no break between ${a.key} and ${b.key}")
            }
            val outside = bucket.slots.filter { it.kind == SlotKind.Outside }
            val weekend = (input.dayKinds[bucket.date] ?: DayKind.of(bucket.date)) == DayKind.Weekend
            assertTrue(weekend || outside.isEmpty(), "seed=$seed: ${bucket.date}, a school day, has a break outside")
            assertTrue(outside.size <= settings.outsideBreaks && outside.all { it.end - it.start == settings.outsideBreakMin * MINUTE }, "seed=$seed: ${bucket.date}'s breaks outside, $outside")
        }
    }

    /**
     * A pin whose time is free holds its piece then (PL-12): within one window, clear of busy time
     * and of breaks outside, in free time long enough for its kind of window to be used.
     */
    private fun checkPins(seed: Long, plan: Plan, input: Planner.Input) {
        val busy = input.busy + input.hardBusy
        val earliest = onFive(input.now)
        for (bucket in plan.buckets) for (chunk in bucket.chunks) {
            val pin = input.pins[chunk.key]?.takeIf { Planner.date(it, LONDON) == bucket.date } ?: continue
            val end = pin + chunk.minutes * MINUTE
            val window = input.windows[bucket.date].orEmpty().firstOrNull { at(bucket.date, it.startMin) <= pin && end <= at(bucket.date, it.endMin) } ?: continue
            // Nor one its piece can't be done at: work for home in a school window, ending after its
            // deadline that day, or before its task opens. It goes to the next start that can.
            if (chunk.where == Where.Home && window.kind.atSchool) continue
            if (chunk.hard && Planner.date(chunk.deadline, LONDON) == bucket.date && end > chunk.deadline) continue
            if (chunk.availableAt?.let { pin < onFive(it) } == true) continue
            if (busy.any { it.start < end && pin < it.end } || bucket.slots.any { it.kind == SlotKind.Outside && it.start < end && pin < it.end }) continue
            // Nor near another pinned piece (a task's own time, PL-04), which may come first.
            if (bucket.slots.any { it.pinned && it.chunk?.key != chunk.key && it.start < end + 20 * MINUTE && pin - 20 * MINUTE < it.end }) continue
            val lo = maxOf(at(bucket.date, window.startMin), earliest, busy.filter { it.end <= pin }.maxOfOrNull { it.end } ?: Long.MIN_VALUE)
            val hi = minOf(at(bucket.date, window.endMin), busy.filter { it.start >= end }.minOfOrNull { it.start } ?: Long.MAX_VALUE)
            if ((hi - lo) / MINUTE < if (window.kind.atSchool) input.settings.minSchoolSlotMin else 5) continue
            val slot = bucket.slots.firstOrNull { it.chunk?.key == chunk.key }
            assertTrue(slot != null && slot.start == pin && slot.pinned, "seed=$seed: ${chunk.key} pinned at ${java.time.Instant.ofEpochMilli(pin)}, laid at ${slot?.start?.let { java.time.Instant.ofEpochMilli(it) }}")
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        /** Seeds checked: 300 in CI, well under a minute; more with PLANNER_PROPERTY_ITERATIONS. */
        val ITERATIONS: Long = System.getenv("PLANNER_PROPERTY_ITERATIONS")?.toLongOrNull() ?: 300
    }
}
