package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Why a piece is on its day (PL-07), set as it's placed: shown on its sheet, and given to Claude
 * with the plan so it explains the plan rather than guessing at it.
 */
enum class Why {
    /** Due later today or tomorrow: placed first, in today's time. */
    DueSoon,

    /** Past a deadline: into today, the oldest first. */
    Overdue,

    /** Where its share of its window's free time falls (PL-01). */
    Spread,

    /** Brought within a week of its task's piece before (the minimum touch, Q-32). */
    MinimumTouch,

    /** Moved a day on from its task's piece before: revision, spaced. */
    RevisionGap,

    /** A task in one piece: the day of its window with most room. */
    MostRoom,

    /** Undated: in spare time, backwards from its soft week. */
    Soft,

    /** Its window couldn't hold it in order: placed as late as its days allow. */
    Packed,

    /** Where you put it (phase 3). */
    Pinned,

    /** Moved when its time passed (phase 3). */
    Refitted,

    /** No room before its deadline: flagged behind. */
    NoRoom,
}

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
    /** The deadline it ranks by: its own, or for a task's overdue part brought along, its task's soonest one still to come. */
    val rankedBy: Long? = null,
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
    /** Its sub-step's place in its task's list, so finishing it ticks that one, not another of the same title. */
    val stepIndex: Int? = null,
    /** Why it's on its day (PL-07). */
    val why: Why,
) {
    val label: String get() = step?.let { "$title: $it" } ?: title

    /** [why] as its sheet says it, from [today]: "Spread over the 12 days to its deadline". */
    fun whyLine(today: LocalDate, zone: ZoneId): String {
        val due = Planner.date(deadline, zone)
        val days = ChronoUnit.DAYS.between(today, due)
        return when (why) {
            Why.DueSoon -> if (due == today) "Due today" else "Due tomorrow"
            Why.Overdue -> "Overdue since ${due.format(DAY)}"
            Why.Spread -> "Spread over the $days days to its deadline"
            Why.MinimumTouch -> "Kept within a week of its last piece"
            Why.RevisionGap -> "A day apart from the last revision piece"
            Why.MostRoom -> "The day with most room before " + if (days in 1..6) due.format(WEEKDAY) else due.format(DAY)
            Why.Soft -> "Undated: fills spare time this week"
            Why.Packed -> "Its days before the deadline are tight: as late as they allow"
            Why.Pinned -> "Where you put it"
            Why.Refitted -> "Moved when its time passed"
            Why.NoRoom -> "Couldn't fit before its deadline"
        }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
        val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.UK)
    }

    /** Bad news, shown in red wherever it appears: overdue, due today, or behind. */
    val urgent: Boolean get() = overdue || dueToday || behind

    fun startable(now: Long): Boolean = availableAt == null || availableAt <= now

    /** Written homework, which a photo can show done: not a deck's cards, an email or an event. */
    val photoCheckable: Boolean get() = kind == Kind.Homework && source != Source.Anki
}

data class DayBucket(val date: LocalDate, val capacityMin: Int, val chunks: List<Chunk>) {
    val plannedMin: Int get() = chunks.sumOf { it.minutes }
}

/**
 * Open work the plan leaves out, and why (IMP-P1-002): it can't start yet, waits for another
 * task, opens past the plan's reach, or has no room in it. [pieces] and [minutes] are of what's
 * left out, all its runs' together.
 */
data class Unplanned(
    val taskId: String,
    val source: Source,
    val title: String,
    val minutes: Int,
    val pieces: Int,
    val reason: Reason,
    /** When it opens: [Reason.NotOpen]'s and [Reason.OpensLater]'s. */
    val at: Long? = null,
    /** The task it waits for, by title: [Reason.Waits]'. */
    val after: String? = null,
) {
    enum class Reason {
        /** Its source (or Claude's reading of it) says it can't be started yet. */
        NotOpen,

        /** You said it waits until another task is done. */
        Waits,

        /** It, or a run of its blocks, opens after the plan's last day. */
        OpensLater,

        /** There's no room for it by the plan's last day, within each day's limits. */
        NoRoom,
    }
}

/** The plan at [now]: one bucket per study day from today, each with the chunks placed on it. */
data class Plan(
    val now: Long,
    val today: LocalDate,
    val buckets: List<DayBucket>,
    /** Events (a call, an open day): reminders, not work to place. */
    val events: List<TaskItem>,
    /** Open work that isn't in [buckets], with why: it doesn't vanish without a word. */
    val unplanned: List<Unplanned> = emptyList(),
    /** Work with a real deadline that the time before it can't hold (PL-02), one a task. */
    val wontFit: List<WontFit> = emptyList(),
) {
    fun bucket(date: LocalDate): DayBucket? = buckets.firstOrNull { it.date == date }

    val todayBucket: DayBucket? get() = bucket(today)
    val tomorrowBucket: DayBucket? get() = bucket(today.plusDays(1))

    /** What's planned today and tomorrow: what the next two days hold, for ordering and the briefing. */
    val dueSoon: List<Chunk> get() = todayBucket?.chunks.orEmpty() + tomorrowBucket?.chunks.orEmpty()

    /**
     * Something is planned today: blocking is strict (docs/scheduler.md §4). Today's bucket alone,
     * since later work is spread over its window (PL-01) and tomorrow always holds a share of it.
     */
    val pressure: Boolean get() = todayBucket?.chunks.orEmpty().isNotEmpty()

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

    /**
     * The piece of [taskId] whose step is [stepIndex] and [step] (a focus session's), or, where the
     * plan has moved on under it (a step ticked meanwhile), the task's first.
     */
    fun pieceOf(taskId: String, stepIndex: Int?, step: String?): Chunk? =
        chunksOf(taskId).let { pieces -> pieces.firstOrNull { it.stepIndex == stepIndex && it.step == step } ?: pieces.firstOrNull() }
}

/**
 * What the app has learned about how long things take you (docs/scheduler.md §5), applied by the
 * planner. Phase 5 learns it; until then every value is its default.
 */
@Serializable
data class Calibration(
    /**
     * The share of a day's free time that's used, per kind of day (PL-06): learned from the days'
     * records, between a half and all of it. None for a kind with too few days yet: all of it.
     */
    val yields: Map<DayKind, Double> = emptyMap(),
    /** How many days each of [yields] rests on, for Stats. */
    val yieldDays: Map<DayKind, Int> = emptyMap(),
    /** `actual / estimate` per kind and class, keyed `Kind|class` (or `Kind|` for the whole kind). */
    val multipliers: Map<String, Double> = emptyMap(),
    /** Safety margin in days, per kind. */
    val marginDays: Map<Kind, Int> = emptyMap(),
    /** Box length in minutes, per kind. */
    val boxMin: Map<Kind, Int> = emptyMap(),
    /** How many timed pieces each multiplier's key has had (IMP-P2-016), a class's below the bar included: what Stats says it rests on. */
    val timed: Map<String, Int> = emptyMap(),
    /**
     * Learned since 1.8.0, with [timed] kept and its rule for a class's own multiplier: false for one
     * from before, made again under it ([com.thomaswcode.decrastination.learn.Calibrator.upgraded]).
     * Kept, not read from an empty [timed]: a review from your answers alone has none either.
     */
    val counted: Boolean = false,
) {
    fun multiplier(kind: Kind, className: String?): Double =
        multipliers["${kind.name}|${className.orEmpty()}"] ?: multipliers["${kind.name}|"] ?: 1.0

    /** What a day of [kind] holds of its free time, as learned (PL-06). */
    fun yieldFor(kind: DayKind): Double = yields[kind] ?: 1.0

    /**
     * What [task]'s estimate is multiplied by: what's been learned of the app's estimates for its
     * kind and class; your own minutes ([TaskItem.yourMinutes]) as they are, as you meant them.
     */
    fun multiplierFor(task: TaskItem): Double = if (task.yourMinutes) 1.0 else multiplier(task.kind, task.className)

    /** What each of [task]'s steps' minutes is multiplied by: [multiplierFor], with its steps scaled to your minutes. */
    fun stepMultiplierFor(task: TaskItem): Double = multiplierFor(task) * task.stepScale

    companion object {
        fun key(kind: Kind, className: String?): String = "${kind.name}|${className.orEmpty()}"
    }
}

/**
 * A task with a real deadline its days can't hold (PL-02), said the day it's so: [leftMin] of it
 * to do, and [freeMin] free before [deadline] itself at its turn, before it took any.
 */
data class WontFit(val taskId: String, val title: String, val leftMin: Int, val freeMin: Int, val deadline: Long)

/** The kinds of day that each learn how much of their free time is used (PL-06). */
@Serializable
enum class DayKind(val label: String) {
    /** A school day's evening. */
    Weekday("Weekday evenings"),

    /** Saturday and Sunday (phase 3: a day off school too). */
    Weekend("Weekends"),

    /** A school day's windows in the day (phase 3): no records of its own until phase 4's slots. */
    School("School windows"),
    ;

    companion object {
        /** [date]'s kind by the calendar: Saturday and Sunday are the weekend. */
        fun of(date: LocalDate): DayKind = if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) Weekend else Weekday
    }
}

/** Time the calendar says is taken (Phase 5): a whole interval, epoch millis. */
data class Busy(val start: Long, val end: Long)
