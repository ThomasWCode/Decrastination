package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * One piece of a task, placed on one day: a sub-step ("Q1–8"), or a box of the task's remaining
 * time when it has no steps (docs/scheduler.md §3).
 */
data class Chunk(
    val taskId: String,
    val source: Source,
    val kind: Kind,
    val title: String,
    /** The sub-step's own title, or "part 2 of 3"; null for a task done in one go. */
    val step: String?,
    val minutes: Int,
    /** The task's real deadline, if it has one. */
    val dueAt: Long?,
    /** The deadline it's planned against: [dueAt], or a soft one for undated work. */
    val deadline: Long,
    /** Undated work, planned against a soft deadline and after everything with a real one. */
    val soft: Boolean,
    val overdue: Boolean,
    val dueToday: Boolean,
    /**
     * Work due later today or tomorrow, which comes before overdue work as its deadline can still
     * be met; or its task's overdue part, which comes before it.
     */
    val dueSoon: Boolean = false,
    /** Couldn't fit before its deadline, so it's today's. */
    val behind: Boolean,
    /** 1-based, of [parts]. */
    val part: Int,
    val parts: Int,
    /** The whole task's remaining minutes: shorter tasks go first among equals. */
    val taskMinutes: Int,
    /** When it can be started, if that's later than the plan's now (an Anki deck's cards come at 04:00). */
    val availableAt: Long? = null,
    /** The box length that cut it, when its task was split into boxes; null for a step or a task in one piece. */
    val box: Int? = null,
) {
    val label: String get() = step?.let { "$title: $it" } ?: title

    /** Bad news, shown in red wherever it appears: overdue, due today, or behind. */
    val urgent: Boolean get() = overdue || dueToday || behind

    fun startable(now: Long): Boolean = availableAt == null || availableAt <= now

    /** Written homework, which a photo can show done: not a deck's cards, an email or an event. */
    val photoCheckable: Boolean get() = kind == Kind.Homework && source != Source.Anki
}

data class DayBucket(val date: LocalDate, val capacityMin: Int, val chunks: List<Chunk>) {
    val plannedMin: Int get() = chunks.sumOf { it.minutes }
}

/** The plan at [now]: one bucket per study day from today, each with the chunks placed on it. */
data class Plan(
    val now: Long,
    val today: LocalDate,
    val buckets: List<DayBucket>,
    /** Events (a call, an open day): reminders, not work to place. */
    val events: List<TaskItem>,
) {
    fun bucket(date: LocalDate): DayBucket? = buckets.firstOrNull { it.date == date }

    val todayBucket: DayBucket? get() = bucket(today)
    val tomorrowBucket: DayBucket? get() = bucket(today.plusDays(1))

    /** What must happen today or tomorrow for every deadline to be met: by definition, what's due soon. */
    val dueSoon: List<Chunk> get() = todayBucket?.chunks.orEmpty() + tomorrowBucket?.chunks.orEmpty()

    /** Something is due today or tomorrow: blocking is strict (docs/scheduler.md §4). */
    val pressure: Boolean get() = dueSoon.isNotEmpty()

    /** Every chunk in the order it's meant to be done. */
    val ordered: List<Chunk> get() = buckets.flatMap { it.chunks }

    /**
     * The single next thing to do: the first chunk that can be started now, today's before the
     * nearest day's; if none can, the first.
     */
    val next: Chunk? get() = ordered.firstOrNull { it.startable(now) } ?: ordered.firstOrNull()

    /** And the one after it, chosen the same way. */
    val then: Chunk? get() = next?.let { first -> ordered.filter { it !== first }.let { rest -> rest.firstOrNull { it.startable(now) } ?: rest.firstOrNull() } }

    fun chunksOf(taskId: String): List<Chunk> = ordered.filter { it.taskId == taskId }
}

/**
 * What the app has learned about how long things take you (docs/scheduler.md §5), applied by the
 * planner. Phase 5 learns it; until then every value is its default.
 */
@Serializable
data class Calibration(
    /** `actual / estimate` per kind and class, keyed `Kind|class` (or `Kind|` for the whole kind). */
    val multipliers: Map<String, Double> = emptyMap(),
    /** Safety margin in days, per kind. */
    val marginDays: Map<Kind, Int> = emptyMap(),
    /** Box length in minutes, per kind. */
    val boxMin: Map<Kind, Int> = emptyMap(),
) {
    fun multiplier(kind: Kind, className: String?): Double =
        multipliers["${kind.name}|${className.orEmpty()}"] ?: multipliers["${kind.name}|"] ?: 1.0

    companion object {
        fun key(kind: Kind, className: String?): String = "${kind.name}|${className.orEmpty()}"
    }
}

/** Time the calendar says is taken (Phase 5): a whole interval, epoch millis. */
data class Busy(val start: Long, val end: Long)
