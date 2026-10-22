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

    /** Where you put it. */
    Pinned,

    /** Moved when its time passed. */
    Refitted,
    /** Kept on today, where the last plan had its slot, under way or still to come (PL-13). */
    Kept,

    /** No room before its deadline: flagged behind. */
    NoRoom,
}

/**
 * One piece of a task, placed on one day: a sub-step ("Q1–8"), or a box of the task's remaining
 * time when it has no steps (docs/spec/planner.md).
 */
data class Chunk(
    val taskId: String,
    val source: Source,
    val kind: Kind,
    val title: String,
    /** The sub-step's own title (a sitting's, its step's: [shown] says which), or "part 2 of 3"; null for a task done in one go. */
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
    /** Where it can be done (PL-15): anywhere, a free period at school included, or only at home. */
    val where: Where = Where.Anywhere,
    /** Its deadline is a real one (PL-14), which its slot on the deadline's day keeps before (PL-12). */
    val hard: Boolean = false,
    /**
     * Which of its step's sittings it is, where the planner cut a step over an hour as planned;
     * null for a step in one piece, a box, or a whole task.
     */
    val sitting: Sitting? = null,
) {
    /**
     * Which piece it is, across replans (PL-12): its task and its step's place (a sitting before its
     * step's last, with how many come after it); a piece of time with no step of its own, its part.
     * Not a step's part: that renumbers once a piece before it is kept, and its slot's kept start
     * (PL-13) would be lost with it.
     */
    val key: String get() = keyOf(taskId, stepIndex, part, sitting?.after)

    /** Its step as it's done in this piece: a sitting's title ("Continue: Write the essay"), else [step]. */
    val shown: String? get() = sitting?.title ?: step

    val label: String get() = shown?.let { "$title: $it" } ?: title

    /** The step keeping it ticks: [stepIndex], but none for a sitting before its step's last ([Sitting]). */
    val ticks: Int? get() = stepIndex?.takeIf { (sitting?.after ?: 0) == 0 }

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
            Why.Kept -> "Kept where today's slot had it"
            Why.NoRoom -> "Couldn't fit before its deadline"
        }
    }

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
        private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.UK)

        /**
         * A piece's [key] from what makes it, for the planner naming one before it's made (its pin,
         * its missed slot): the one place it's made, so the two can't come apart. A step's "id#2", a
         * sitting before its last "id#2#1" ([after] of its step's after it), a piece of time "id#-1#3".
         */
        fun keyOf(taskId: String, stepIndex: Int?, part: Int, after: Int? = null): String = when {
            stepIndex == null -> "$taskId#-1#$part"
            after != null && after > 0 -> "$taskId#$stepIndex#$after"
            else -> "$taskId#$stepIndex"
        }
    }

    /** Bad news, shown in red wherever it appears: overdue, due today, or behind. */
    val urgent: Boolean get() = overdue || dueToday || behind

    fun startable(now: Long): Boolean = availableAt == null || availableAt <= now

    /** Written homework, which a photo can show done: not a deck's cards, an email or an event. */
    val photoCheckable: Boolean get() = kind == Kind.Homework && source != Source.Anki

    /** What lets a kept slot of it earn (BL-08), with pieces from [photoFromMin] minutes needing more than a tick. */
    fun earnsBy(photoFromMin: Int): EarnsBy = when {
        minutes < photoFromMin -> EarnsBy.Tick
        photoCheckable -> EarnsBy.Photo
        source == Source.Own -> EarnsBy.Nothing
        else -> EarnsBy.Source
    }
}

/**
 * One of a step's sittings ([Chunk.sitting]), as the planner cuts a step over an hour as planned
 * ([Sittings]): [after], how many of its step's come after it, so keeping one
 * renumbers none of the rest; and its [title]. Its last is the step's, keeping it ticks the step;
 * keeping one before it counts its minutes as worked on the step, as a session stopped early does.
 */
data class Sitting(val after: Int, val title: String)

/** What lets a kept slot earn (BL-08). */
enum class EarnsBy {
    /** Short: its tick. */
    Tick,

    /** Longer, written work: a photo check of it. */
    Photo,

    /** Longer, not written work: its source saying it's done (Anki's cards counted, Teams handed in). */
    Source,

    /** Longer, one of your own that isn't written work: nothing can check it. */
    Nothing,
}

data class DayBucket(
    val date: LocalDate,
    val capacityMin: Int,
    val chunks: List<Chunk>,
    /** Its pieces at their times, and outside breaks (PL-12): on a day with windows. */
    val slots: List<Slot> = emptyList(),
    /** Its pieces no free time holds whole, in order. */
    val unslotted: List<Chunk> = emptyList(),
) {
    val plannedMin: Int get() = chunks.sumOf { it.minutes }

    /** [chunk]'s slot on the day, if it has one. */
    fun slotOf(chunk: Chunk): Slot? = slots.firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == chunk.key }
}

/** What a slot holds (PL-12): a piece of work, or a break outside. */
enum class SlotKind { Work, Outside }

/** A time on a day (PL-12): a piece's, or an outside break's; [pinned] where you put it. Epoch millis. */
data class Slot(val kind: SlotKind, val chunk: Chunk?, val start: Long, val end: Long, val window: WindowKind, val pinned: Boolean) {
    val key: String get() = chunk?.key ?: "outside:$start"
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
     * Something is planned today: blocking is strict (docs/spec/blocking-and-protection.md). Today's bucket alone,
     * since later work is spread over its window (PL-01) and tomorrow always holds a share of it.
     */
    val pressure: Boolean get() = todayBucket?.chunks.orEmpty().isNotEmpty()

    /** Every chunk in the order it's meant to be done. */
    val ordered: List<Chunk> get() = buckets.flatMap { it.chunks }

    /**
     * The single next thing to do: the first chunk that can be started now, today's before the
     * nearest day's; if none can, the first.
     */
    val next: Chunk? get() = nextAfter(null)

    /** And the one after it, chosen as [next] is from what's left. */
    val then: Chunk? get() = next?.let { nextAfter(it) }

    /**
     * The next thing to do, [first] aside: the slot under way, else today's next slot, else today's
     * work with no slot (today's comes before tomorrow's), else tomorrow's first slot, else the
     * first that can be started, else the first.
     */
    private fun nextAfter(first: Chunk?): Chunk? {
        fun Chunk?.ok() = this != null && this != first
        slotAt(now)?.chunk?.takeIf { it.ok() }?.let { return it }
        todaySlots.firstOrNull { it.kind == SlotKind.Work && it.start >= now && it.chunk.ok() }?.chunk?.let { return it }
        todayBucket?.chunks.orEmpty().firstOrNull { it.ok() && it.startable(now) }?.let { return it }
        tomorrowBucket?.slots.orEmpty().firstOrNull { it.kind == SlotKind.Work && it.chunk.ok() }?.chunk?.let { return it }
        return ordered.filter { it.ok() }.let { rest -> rest.firstOrNull { it.startable(now) } ?: rest.firstOrNull() }
    }

    /** Today's slots, work and outside (PL-12). */
    val todaySlots: List<Slot> get() = todayBucket?.slots.orEmpty()

    /** Today's work slot at [time], if one is under way. */
    fun slotAt(time: Long): Slot? = todaySlots.firstOrNull { it.kind == SlotKind.Work && time >= it.start && time < it.end }

    /**
     * Free time between today's slots (BL-10): from a piece ticked done at [keptAt] till the next work
     * slot today starts. A kept piece leaves the plan, so a work slot of today's already begun is one
     * under way, or gone by undone: either closes the gap. Null outside one, or with no slot after.
     */
    fun gapUntil(now: Long, keptAt: Long?): Long? {
        if (keptAt == null || keptAt > now) return null
        val work = todaySlots.filter { it.kind == SlotKind.Work }
        if (work.any { it.start <= now }) return null
        return work.minOfOrNull { it.start }
    }

    /** The first work slot today at or after [time], else tomorrow's first. */
    fun nextSlot(time: Long): Slot? =
        todaySlots.firstOrNull { it.kind == SlotKind.Work && it.start >= time } ?: tomorrowBucket?.slots.orEmpty().firstOrNull { it.kind == SlotKind.Work }

    fun chunksOf(taskId: String): List<Chunk> = ordered.filter { it.taskId == taskId }
}

/**
 * What the app has learned about how long things take you (docs/spec/learning.md), applied by the
 * planner.
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
    /** How many timed pieces each multiplier's key has had (IMP-P2-016), a class's below the bar included: what Stats says it rests on. */
    val timed: Map<String, Int> = emptyMap(),
    /**
     * Learned since 1.8.0, with [timed] kept and its rule for a class's own multiplier: false for one
     * from before, made again under it ([com.thomaswcode.decrastination.learn.Calibrator.upgraded]).
     * Kept, not read from an empty [timed]: a review from your answers alone has none either.
     */
    val counted: Boolean = false,
    /** Seconds a card, per AnkiDroid deck (DX-11), learned from time in AnkiDroid: the deck tasks' minutes go by it. */
    val ankiSecondsPerCard: Map<Long, Double> = emptyMap(),
    /** How many stretches in AnkiDroid each of [ankiSecondsPerCard] rests on. */
    val ankiStretches: Map<Long, Int> = emptyMap(),
    /**
     * Kinds and classes (`Kind|class`) you've had forgotten (LR-06), each with when: their work
     * finished before then teaches nothing, so they start afresh, from the kind's figure.
     */
    val forgotten: Map<String, Long> = emptyMap(),
    /** Learned only from what came after this (LR-08's reset of the calibration): null, from all of the log. */
    val learnFrom: Long? = null,
) {
    fun multiplier(kind: Kind, className: String?): Double =
        multipliers["${kind.name}|${className.orEmpty()}"] ?: multipliers["${kind.name}|"] ?: 1.0

    /** What a day of [kind] holds of its free time, as learned (PL-06). */
    fun yieldFor(kind: DayKind): Double = yields[kind] ?: 1.0

    /**
     * What [task]'s estimate is multiplied by: what's been learned of the app's estimates for its
     * kind and class; your own minutes ([TaskItem.yourMinutes]), and steps you or Felix gave
     * ([TaskItem.yourSteps], D-34), as they are, as you meant them: the pace is for the
     * app's own estimates, and Felix is told it, to allow for it when he sets times.
     */
    fun multiplierFor(task: TaskItem): Double = if (task.yourMinutes || task.yourSteps) 1.0 else multiplier(task.kind, task.className)

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

    /** Saturday and Sunday (a day off school too). */
    Weekend("Weekends"),

    /** A school day's windows in the day. */
    School("School windows"),
    ;

    companion object {
        /** [date]'s kind by the calendar: Saturday and Sunday are the weekend. */
        fun of(date: LocalDate): DayKind = if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) Weekend else Weekday
    }
}

/** Time the calendar says is taken: a whole interval, epoch millis. */
data class Busy(val start: Long, val end: Long)
