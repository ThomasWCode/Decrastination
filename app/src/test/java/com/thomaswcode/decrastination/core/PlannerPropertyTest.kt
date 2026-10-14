package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random
import kotlin.test.Test
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
                shareOut(effort, List(count) { 1 + random.nextInt(10) }).mapIndexed { i, minutes -> SubStep("step ${i + 1}", minutes) }
            }
            return TaskItem(
                id = TaskItem.id(source, "t$index"),
                source = source,
                sourceId = "t$index",
                title = "t$index",
                dueAt = if (chance(20)) null else at(today.plusDays(1L + random.nextInt(30)), pick(LocalTime.of(8, 30), LocalTime.of(23, 59))),
                availableFrom = if (chance(10)) now + HOUR + random.nextLong(10 * DAY) else null,
                kind = pick(Kind.Homework, Kind.Revision, Kind.Admin),
                sourceEffortMin = effort,
                subSteps = steps,
                stepsPerDay = if (chance(20)) 1 + random.nextInt(3) else null,
                notBefore = if (chance(20)) now + random.nextLong(6) * DAY else null,
                firstSeenAt = now - random.nextLong(11) * DAY,
                lastSeenAt = now,
            )
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
    }

    /** No day holds more than its time, but for overdue and due-today work, which may, and work flagged behind. */
    private fun checkCapacity(seed: Long, plan: Plan) {
        for (bucket in plan.buckets) {
            val held = bucket.chunks.filter { !it.behind && !it.overdue && !it.dueToday }.sumOf { it.minutes }
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
        fun after(p: Plan) = p.buckets.filter { it.date > today }.flatMap { b -> b.chunks.map { (it.taskId to it.part) to b.date } }.toMap()
        val before = after(plan)
        val next = after(Planner.plan(later))
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

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        /** Seeds checked: 300 in CI, well under a minute; more with PLANNER_PROPERTY_ITERATIONS. */
        val ITERATIONS: Long = System.getenv("PLANNER_PROPERTY_ITERATIONS")?.toLongOrNull() ?: 300
    }
}
