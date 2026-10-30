package com.thomaswcode.decrastination.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * One instruction you've written, in your words, about a task, a calendar event, a day, or
 * nothing in particular ("I can't do anything on Saturday", "this email is just an email"). Claude
 * reads it into [changes] (enrich/InstructionReader.kt), applied as soon as they're read (D-67) or
 * refused saying why, and laid over the plan until you take it back.
 */
@Serializable
data class Instruction(
    val id: String,
    val text: String,
    val about: About = About(),
    val at: Long,
    val state: InstructionStatus = InstructionStatus.Reading,
    val changes: List<Change> = emptyList(),
    /** Why it's waiting to be read, why it couldn't be, or what Claude found unclear. */
    val note: String? = null,
    val appliedAt: Long? = null,
    /**
     * A quick change made on a task, with no Claude ([com.thomaswcode.decrastination.ui.OwnChange]):
     * a newer one of the same kind for the same task replaces it, so they don't pile up.
     */
    val quick: Boolean = false,
    /**
     * Your answer to Felix's question about its task (AI-10), kept with the task too, which is read
     * again with it: one Claude can't make a change of (it tells, rather than changes) is let go,
     * not left for you to check.
     */
    val answer: Boolean = false,
    /**
     * Who set what it changes: you (the instructions screen, a sheet's quick change, your answer to
     * Felix's question) or Felix (a card of his in the chat). It only labels (D-4).
     */
    val by: By = By.You,
)

@Serializable
enum class InstructionStatus {
    /** Waiting for Claude to read it (it may be off, resting, or capped: [Instruction.note] says). */
    Reading,

    /** Read: applied at once (D-67); one left here (a stop between, say) waits for Apply or Discard. */
    Understood,

    /** Claude couldn't turn it into changes: [Instruction.note] says why. */
    Unclear,

    /** In use. */
    Applied,
}

/** What an instruction was written about: a task, a calendar event (by name), a day, or none of them. */
@Serializable
data class About(
    val taskId: String? = null,
    val taskTitle: String? = null,
    /** The event's name as its answers are kept ([com.thomaswcode.decrastination.learn.EventJudge.key]). */
    val eventKey: String? = null,
    val eventTitle: String? = null,
    val eventStart: Long? = null,
    /** A day, as YYYY-MM-DD. */
    val day: String? = null,
)

@Serializable
enum class ChangeType {
    /** The task isn't work to plan: off the plan and the list, like an email that's only to read. */
    NotATask,

    /** The task can't be started before [Change.time]. */
    StartFrom,

    /** The task can't be started until [Change.afterTaskId] is done. */
    After,

    /** The task is due by [Change.time] instead. */
    DueBy,

    /** The whole task takes [Change.minutes], as you reckon it. */
    Minutes,

    /** Every event named [Change.eventKey] takes [Change.eventAnswer] of your time ("free", "busy", "load:<minutes>"). */
    EventTime,

    /** At most [Change.freeMin] minutes of work on [Change.date], or every [Change.weekday]. */
    DayLimit,

    /** No work from [Change.startMin] to [Change.endMin] on [Change.date], or every [Change.weekday]. */
    BusyTime,

    /** No school from [Change.date] to [Change.endDate] (PL-16): kept in the school calendar, not the instruction. */
    NoSchool,

    /** School on [Change.date] ends at [Change.endMin] (PL-16): kept in the school calendar too. */
    HalfDay,

    /** All of the task's remaining work on [Change.date] (PL-04). */
    OnDay,

    /** The task's next piece starts at [Change.time]: [Change.date] at [Change.startMin] (PL-04). */
    AtTime,

    /**
     * A task's steps replaced with [Change.steps] (AI-19; any task's, but Anki's, which
     * are its cards): made on one of your own, laid over another's; its ticks kept by name.
     */
    Steps,

    /** The task is the same work as another listed one ([Change.afterTaskId]), which is kept (DX-15): this one's hidden. */
    SameAs,

    /** The task's name in the app is [Change.title]: made on one of your own; over another's, its source keeping its own. */
    Rename,

    /**
     * Only the tasks given a [DayOnly] for [Change.date] are planned on it, [Change.taskId] among them; [Change.alsoAllow]
     * what else may come in (D-115).
     */
    DayOnly,

    /** [Change.date]'s break outside of [Change.outsideMin] (its own time) at [Change.startMin] (D-116). */
    OutsideAt,

    /** [Change.date]'s break outside of [Change.outsideMin] (its own time) not laid; -1, none of the day's (D-116). */
    NoOutside,

    /** A break outside at [Change.startMin] on [Change.date], whatever kind of day it is (D-116). */
    OutsideAdded,

    /** Work every week, as [Change.weekly] says (D-124): a rule of that id already there is replaced. */
    Weekly,

    /** [Change.weekly]'s rule ended (D-124). */
    WeeklyEnd,
}

/** One change an instruction makes. Which fields mean anything depends on [type]. */
@Serializable
data class Change(
    val type: ChangeType,
    val taskId: String? = null,
    /** [ChangeType.StartFrom]'s start, [ChangeType.DueBy]'s deadline, or [ChangeType.AtTime]'s start. */
    val time: Long? = null,
    val afterTaskId: String? = null,
    val eventKey: String? = null,
    val eventTitle: String? = null,
    /** As event answers are kept: "free", "busy" or "load:<minutes>". */
    val eventAnswer: String? = null,
    /** A day, as YYYY-MM-DD. */
    val date: String? = null,
    /** [ChangeType.NoSchool]'s last day, as YYYY-MM-DD. */
    val endDate: String? = null,
    /** Every such day, 1 (Monday) to 7 (Sunday). */
    val weekday: Int? = null,
    val freeMin: Int? = null,
    /** [ChangeType.Minutes]'s estimate of the whole task. */
    val minutes: Int? = null,
    /** Minutes after midnight. */
    val startMin: Int? = null,
    val endMin: Int? = null,
    /** [ChangeType.Steps]'s steps, in order (AI-19). */
    val steps: List<OwnStep>? = null,
    /** [ChangeType.Rename]'s new name. */
    val title: String? = null,
    /**
     * [ChangeType.Minutes]'s total is the app's own estimate at your pace, as Felix worked it out,
     * not your own reckoning; this is that estimate of the app's, its minutes then. Planned as given,
     * but what's finished still teaches the pace, measured against this, while the task is as it was
     * then ([pacedHash], [TaskItem.pacedTeaches]).
     */
    val pacedFrom: Int? = null,
    /** The task's content when it was paced ([Enrichments.inputHash]): read again for newer prompts alone, it's the same. */
    val pacedHash: String? = null,
    /**
     * [ChangeType.DayOnly]'s day also takes Anki's cards ([InstructionRules.ALSO_ANKI]), or those and work that would
     * otherwise miss its real deadline ([InstructionRules.ALSO_DEADLINES]); else nothing.
     */
    val alsoAllow: String? = null,
    /** [ChangeType.OutsideAt]'s and [ChangeType.NoOutside]'s break, by its own time (its `outsideTimes` minute); -1, none, or all of a NoOutside's day. */
    val outsideMin: Int = -1,
    /** [ChangeType.Weekly]'s rule, or the one [ChangeType.WeeklyEnd] ends, as it was (D-124). */
    val weekly: WeeklyRule? = null,
)

/** Your instructions. Kept in `instructions.json`. */
@Serializable
data class InstructionState(
    val instructions: List<Instruction> = emptyList(),
    /** The shape this is in ([com.thomaswcode.decrastination.data.Migration]): raised with each change of it. */
    val version: Int = 6,
) {
    /** Every step a change holds with an id ([withIds]): given as it's first stored, so the task steps it's laid as keep it. */
    fun withStepIds(): InstructionState {
        fun Change.ided(): Change = steps?.withIds()?.takeIf { it !== steps }?.let { copy(steps = it) } ?: this
        fun Instruction.ided(): Instruction = changes.map { it.ided() }.let { c -> if (c.indices.all { c[it] === changes[it] }) this else copy(changes = c) }
        val all = instructions.map { it.ided() }
        return if (all.indices.all { all[it] === instructions[it] }) this else copy(instructions = all)
    }

    /** Those in use, in the order they were applied: a later one's change wins over an earlier's. */
    val applied: List<Instruction> get() = instructions.filter { it.state == InstructionStatus.Applied }.sortedBy { it.appliedAt ?: it.at }
}

/**
 * Your instructions' changes, as the rest of the app takes them: a task's on the task itself (so a
 * sync keeps them, and every screen sees them), an event's with the calendar's answers, and a day's
 * with the planner's capacity. Pure.
 */
object InstructionRules {

    /** What a [ChangeType.DayOnly]'s day also takes ([Change.alsoAllow]): Anki's cards. */
    const val ALSO_ANKI = "anki"

    /** Anki's cards, and work that would otherwise miss its real deadline. */
    const val ALSO_DEADLINES = "anki-deadlines"

    /** The most of a busy time's name ([Change.title]) that's kept: a word or two (D-117). */
    const val MAX_BUSY_TITLE = 40

    /**
     * Whether what's in force for [taskId]'s [type] comes from a quick change ([Instruction.quick]):
     * the last of [applied] (in the order they were applied) to change it.
     */
    fun quickInForce(applied: List<Instruction>, taskId: String, type: ChangeType): Boolean =
        applied.lastOrNull { i -> i.changes.any { it.type == type && it.taskId == taskId } }?.let { it.quick && it.changes.size == 1 } == true

    /**
     * Whether [task] waits, as you said, for another still open ([byId]: every task). Never for one
     * that, along its own waits, comes back to it: tasks waiting on each other in a circle would
     * never be planned, so neither waits.
     */
    fun waiting(task: TaskItem, byId: Map<String, TaskItem>): Boolean {
        val first = task.mine.after?.value ?: return false
        // Done, gone, or one you said isn't a task (it may stay open for ever): nothing to wait for.
        if (byId[first]?.let { it.isOpen && !it.hidden } != true) return false
        val seen = hashSetOf(task.id)
        var next: String? = first
        while (next != null) {
            if (!seen.add(next)) return next != task.id
            next = byId[next]?.takeIf { it.isOpen && !it.hidden }?.mine?.after?.value
        }
        return true
    }

    /**
     * Whether [changes], with those already [applied], would have tasks wait for each other in a
     * circle: then it isn't applied.
     */
    fun makesCircle(changes: List<Change>, applied: List<Instruction>): Boolean {
        val after = HashMap<String, String>()
        (applied.flatMap { it.changes } + changes).filter { it.type == ChangeType.After && it.taskId != null && it.afterTaskId != null }
            .forEach { after[it.taskId!!] = it.afterTaskId!! }
        for (start in after.keys) {
            val seen = hashSetOf(start)
            var next = after[start]
            while (next != null) {
                if (next == start) return true
                if (!seen.add(next)) break
                next = after[next]
            }
        }
        return false
    }

    /**
     * Today's slots [changes] move on purpose (LR-09), each recorded moved: a time, its task's next
     * piece's; a day other than today, all of its task's today; a start, those before it. The rest
     * stay to be kept or missed.
     */
    fun movedSlots(changes: List<Change>, plan: Plan): List<Slot> {
        val work = plan.todaySlots.filter { it.kind == SlotKind.Work }
        return changes.flatMap { change ->
            val mine = work.filter { it.chunk?.taskId == change.taskId }
            when (change.type) {
                ChangeType.AtTime -> plan.chunksOf(change.taskId.orEmpty()).firstOrNull()?.let { next -> mine.filter { it.chunk?.key == next.key } }.orEmpty()
                ChangeType.OnDay -> if (change.date != plan.today.toString()) mine else emptyList()
                ChangeType.StartFrom -> mine.filter { slot -> change.time?.let { slot.start < it } == true }
                else -> emptyList()
            }
        }.distinct()
    }

    /** Each task's values of yours from [applied] (in order: a later one's wins), each with who set it ([Instruction.by]). */
    fun taskOverrides(applied: List<Instruction>): Map<String, Mine> {
        val out = HashMap<String, Mine>()
        // The day of each task's time in force: a day given after it keeps it where it's that day.
        val timeOn = HashMap<String, String?>()
        for (instruction in applied) for (change in instruction.changes) {
            val id = change.taskId ?: continue
            val by = instruction.by
            fun <T> set(value: T?): SetBy<T>? = value?.let { SetBy(it, by) }
            val o = out[id] ?: Mine.NONE
            out[id] = when (change.type) {
                ChangeType.NotATask -> o.copy(notATask = set(true))
                ChangeType.StartFrom -> o.copy(from = set(change.time))
                ChangeType.After -> o.copy(after = set(change.afterTaskId))
                ChangeType.DueBy -> o.copy(dueAt = set(change.time))
                ChangeType.Minutes -> o.copy(minutes = change.minutes?.let { SetMinutes(it, by, change.pacedFrom?.let { from -> Paced(from, change.pacedHash) }) })
                // The later of a day for all of it and a time for its next piece wins, but a time on the day
                // it's kept to stays, whichever came first.
                ChangeType.OnDay -> o.copy(onDay = set(change.date), atTime = o.atTime?.takeIf { timeOn[id] == change.date })
                ChangeType.AtTime -> o.copy(atTime = set(change.time), onDay = o.onDay?.takeIf { it.value == change.date }).also { timeOn[id] = change.date }
                ChangeType.SameAs -> o.copy(sameAs = set(change.afterTaskId))
                ChangeType.Steps -> o.copy(steps = change.steps?.let { SetSteps(it, by) })
                ChangeType.Rename -> o.copy(title = set(change.title))
                else -> o
            }
        }
        // The same as one that's itself the same as another (or as this): not hidden, so no two
        // said the same as each other are both off the plan.
        val laid = out.toMap()
        for ((id, o) in laid) {
            val kept = o.sameAs?.value ?: continue
            if (kept == id || laid[kept]?.sameAs != null) out[id] = o.copy(sameAs = null)
        }
        return out
    }

    /** The calendar answers [applied] gives, by event name, over your answers to its questions. */
    fun eventAnswers(applied: List<Instruction>): Map<String, String> =
        applied.flatMap { it.changes }.filter { it.type == ChangeType.EventTime && it.eventKey != null && it.eventAnswer != null }
            .associate { it.eventKey!! to it.eventAnswer!! }

    /**
     * The most minutes of work each day from [from] to [to] holds, where [applied] limits it: a date's
     * own limit over a weekday's, and a later instruction's over an earlier's.
     */
    fun dayCaps(applied: List<Instruction>, from: LocalDate, to: LocalDate): Map<LocalDate, Int> {
        val limits = applied.flatMap { it.changes }.filter { it.type == ChangeType.DayLimit && it.freeMin != null }
        val byDate = limits.filter { it.date != null }.mapNotNull { c -> date(c.date)?.let { it to c.freeMin!! } }.toMap()
        val byWeekday = limits.filter { it.date == null && it.weekday in 1..7 }.associate { DayOfWeek.of(it.weekday!!) to it.freeMin!! }
        if (byDate.isEmpty() && byWeekday.isEmpty()) return emptyMap()
        val out = HashMap<LocalDate, Int>()
        var day = from
        while (day <= to) {
            (byDate[day] ?: byWeekday[day.dayOfWeek])?.let { out[day] = it.coerceIn(0, 24 * 60) }
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * The days from [from] to [to] kept for only some tasks (D-115), by [applied] (in the order applied): each date's tasks,
     * those of its [ChangeType.DayOnly] changes, with those a move put on it since its first (an OnDay for it, or an AtTime
     * on it: a later move wins, Q83, the move in force for it, its latest); and what its latest DayOnly lets in besides. A task finished stays among them, so a day
     * whose tasks are all done holds no work.
     */
    fun onlyDays(applied: List<Instruction>, from: LocalDate, to: LocalDate): Map<LocalDate, OnlyDay> {
        val changes = applied.flatMap { it.changes }
        val first = HashMap<LocalDate, Int>()
        val last = HashMap<LocalDate, Int>()
        changes.forEachIndexed { i, c ->
            if (c.type != ChangeType.DayOnly || c.taskId == null) return@forEachIndexed
            val day = date(c.date)?.takeIf { it in from..to } ?: return@forEachIndexed
            first.putIfAbsent(day, i)
            last[day] = i
        }
        // Each task's move in force, its latest OnDay or AtTime: one moved on since doesn't stay where it was.
        val moves = changes.withIndex().filter { (_, c) -> (c.type == ChangeType.OnDay || c.type == ChangeType.AtTime) && c.taskId != null }.associateBy { it.value.taskId!! }
        return first.keys.sorted().associateWith { day ->
            val named = changes.filter { it.type == ChangeType.DayOnly && date(it.date) == day }.mapNotNull { it.taskId }.toSet()
            val moved = moves.filterValues { (i, c) -> i > first.getValue(day) && date(c.date) == day }.keys
            val allow = changes[last.getValue(day)].alsoAllow
            OnlyDay(named + moved, anki = allow == ALSO_ANKI || allow == ALSO_DEADLINES, deadlines = allow == ALSO_DEADLINES, joined = moved - named)
        }
    }

    /**
     * What a move of [taskId] onto [day] says, the day kept for only [named] (D-115): "Thu 29 Oct was only for “Essay”:
     * “Maths” joins it" ([tasks]: to name them).
     */
    fun joinLine(day: LocalDate, named: Collection<String>, taskId: String, tasks: Map<String, TaskItem>): String {
        fun title(id: String) = "“" + (tasks[id]?.title ?: "a task no longer listed") + "”"
        return "${day.format(DAY)} was only for ${named.joinToString(", ") { title(it) }}: ${title(taskId)} joins it"
    }

    /**
     * The breaks outside you or Felix gave the days from [from] to [to] (D-116), by [applied] (in the order applied): each
     * date's moved, off and added, the latest change for a break winning (a NoOutside for all of a day taking every break
     * given it before).
     */
    fun outsideDays(applied: List<Instruction>, from: LocalDate, to: LocalDate): Map<LocalDate, OutsideDay> {
        val out = HashMap<LocalDate, OutsideDay>()
        for (c in applied.flatMap { it.changes }) {
            if (c.type != ChangeType.OutsideAt && c.type != ChangeType.NoOutside && c.type != ChangeType.OutsideAdded) continue
            val day = date(c.date)?.takeIf { it in from..to } ?: continue
            val was = out[day] ?: OutsideDay.NONE
            out[day] = when (c.type) {
                ChangeType.OutsideAt -> c.startMin?.takeIf { c.outsideMin >= 0 }?.let { at -> was.copy(moved = was.moved + (c.outsideMin to at), off = was.off - c.outsideMin) } ?: was
                ChangeType.NoOutside -> if (c.outsideMin < 0) OutsideDay(noneAtAll = true) else was.copy(moved = was.moved - c.outsideMin, off = was.off + c.outsideMin, added = was.added - c.outsideMin)
                else -> c.startMin?.let { at -> was.copy(added = (was.added - at) + at) } ?: was
            }
        }
        return out
    }

    /**
     * Who placed each break outside [applied] moves or adds, from [from] to [to] (D-116), by `<date>/<key>` as the plan keys its
     * slots: the latest change for a break wins, and one taken off has none.
     */
    fun outsideBy(applied: List<Instruction>, from: LocalDate, to: LocalDate): Map<String, By> {
        val out = HashMap<String, By>()
        for (i in applied) for (c in i.changes) {
            val day = date(c.date)?.takeIf { it in from..to } ?: continue
            when (c.type) {
                // A move names a settings break, or one added that day before it, by its own time.
                ChangeType.OutsideAt -> if (c.outsideMin >= 0 && c.startMin != null) {
                    val added = "$day/${Slot.ADDED}${c.outsideMin}"
                    out[if (added in out) added else "$day/${Slot.OUTSIDE}${c.outsideMin}"] = i.by
                }
                ChangeType.OutsideAdded -> c.startMin?.let { out["$day/${Slot.ADDED}$it"] = i.by }
                ChangeType.NoOutside -> if (c.outsideMin < 0) {
                    out.keys.removeAll { it.startsWith("$day/") }
                } else {
                    out -= "$day/${Slot.OUTSIDE}${c.outsideMin}"
                    out -= "$day/${Slot.ADDED}${c.outsideMin}"
                }
                else -> {}
            }
        }
        return out
    }

    /**
     * [day]'s breaks outside as [applied] leaves them (D-116): the settings' own times where the day has them ([withBreaks]: a
     * weekend or a day off school), each at its time or where it was moved, but those taken off; and those added, each at its
     * minute or where it was moved.
     */
    fun dayBreaks(day: LocalDate, settings: Settings, withBreaks: Boolean, applied: List<Instruction>): DayBreaks {
        val given = outsideDays(applied, day, day)[day] ?: OutsideDay.NONE
        val reserved = if (withBreaks) settings.outsideTimes.toSet() else emptySet()
        val own = reserved.filter { it !in given.off && (!given.noneAtAll || it in given.moved) }.associateWith { given.moved[it] ?: it }
        // An added one at a time the day's own are named by isn't laid, as the planner has it.
        val added = given.added.filterNot { it in reserved }.associateWith { given.moved[it] ?: it }
        return DayBreaks(own + added, settings.outsideBreakMin, reserved = reserved)
    }

    /** The times [applied] says you can't work, from [from] to [to], where you are. */
    fun busy(applied: List<Instruction>, zone: ZoneId, from: LocalDate, to: LocalDate): List<Busy> = busyBlocks(applied, from, to).map { b ->
        // The day's times as the calendar's events have them (D-117), so the time kept clear is what they block out.
        val midnight = b.date.atStartOfDay()
        Busy(midnight.plusMinutes(b.startMin.toLong()).atZone(zone).toInstant().toEpochMilli(), midnight.plusMinutes(b.endMin.toLong()).atZone(zone).toInstant().toEpochMilli())
    }

    /**
     * The busy times [applied] gives from [from] to [to], each day's as it's written to your calendar (D-117): one a day for
     * each start and end, named by the title of the latest change that gives it one.
     */
    fun busyBlocks(applied: List<Instruction>, from: LocalDate, to: LocalDate): List<BusyBlock> {
        val times = applied.flatMap { it.changes }.filter { it.type == ChangeType.BusyTime && it.startMin != null && it.endMin != null && it.endMin > it.startMin }
        if (times.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, BusyBlock>()
        var day = from
        while (day <= to) {
            for (t in times) {
                val on = if (t.date != null) date(t.date) == day else t.weekday == day.dayOfWeek.value
                if (!on) continue
                val block = BusyBlock(day, t.startMin!!, t.endMin!!, t.title?.trim()?.takeIf { it.isNotEmpty() })
                val was = out["$day/${block.key}"]
                out["$day/${block.key}"] = if (block.title == null && was != null) was else block
            }
            day = day.plusDays(1)
        }
        return out.values.sortedWith(compareBy({ it.date }, { it.startMin }, { it.endMin }))
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * [change] in words, as it's said once made, with its Undo (D-60, D-67) ([tasks]: to name them, and say
     * what a date was before).
     */
    fun describe(change: Change, tasks: Map<String, TaskItem>, zone: ZoneId): String {
        fun title(id: String?) = "“" + (id?.let { tasks[it]?.title } ?: "a task no longer listed") + "”"
        fun at(time: Long?) = time?.let { Instant.ofEpochMilli(it).atZone(zone) }
        fun day(c: Change) = c.date?.let { d -> date(d)?.format(DAY) ?: d }
            ?: c.weekday?.takeIf { it in 1..7 }?.let { "Every " + DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.UK) }
            ?: "A day"
        return when (change.type) {
            ChangeType.NotATask -> "${title(change.taskId)}: not a task, so off the plan and the list"
            ChangeType.StartFrom -> {
                val start = at(change.time)
                val shown = start?.let { if (it.hour == 0 && it.minute == 0) it.format(DAY) else it.format(DAY_TIME) } ?: "a date"
                "${title(change.taskId)}: can't be started before $shown"
            }
            ChangeType.After -> "${title(change.taskId)}: waits until ${title(change.afterTaskId)} is done"
            ChangeType.DueBy -> {
                val task = change.taskId?.let { tasks[it] }
                // Against the date as it is now; once this is the date in use, against the source's.
                val inUse = task?.mine?.dueAt != null && task.mine.dueAt.value == change.time
                val was = if (inUse) task?.sourceValues?.dueAt else task?.dueAt
                "${title(change.taskId)}: due ${at(change.time)?.format(DAY_TIME) ?: "on another date"}" +
                    when {
                        was == null -> " (it has no due date)"
                        inUse -> " (its source says ${at(was)!!.format(DAY_TIME)})"
                        else -> " (now ${at(was)!!.format(DAY_TIME)})"
                    }
            }
            ChangeType.Minutes -> {
                val task = change.taskId?.let { tasks[it] }
                // Against the estimate as it is now; once this is the one in use, against the app's own.
                val inUse = task?.mine?.minutes != null && task.mine.minutes.minutes == change.minutes
                val was = if (inUse) task?.appEffortMin else task?.effortMin
                "${title(change.taskId)}: takes ${hours(change.minutes ?: 0)} in all" + (if (change.pacedFrom != null) " at your pace" else "") +
                    when {
                        was == null -> ""
                        inUse -> " (the app's estimate: ${hours(was)})"
                        else -> " (now ${hours(was)})"
                    }
            }
            ChangeType.EventTime -> {
                val name = "“" + (change.eventTitle ?: change.eventKey ?: "an event") + "”"
                when {
                    change.eventAnswer == "free" -> "$name: you can work through it"
                    change.eventAnswer == "busy" -> "$name: takes all its time"
                    change.eventAnswer?.startsWith("load:") == true -> "$name: takes ${hours(change.eventAnswer.removePrefix("load:").toIntOrNull() ?: 0)} of its day"
                    else -> "$name: ${change.eventAnswer}"
                }
            }
            ChangeType.DayLimit -> if ((change.freeMin ?: 0) == 0) "${day(change)}: no work" else "${day(change)}: at most ${hours(change.freeMin!!)} of work"
            ChangeType.BusyTime -> "${day(change)}, ${clock(change.startMin)}–${clock(change.endMin)}: busy" + (change.title?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ", no work"
            ChangeType.NoSchool -> {
                val first = date(change.date)
                val last = date(change.endDate) ?: first
                when {
                    first == null -> "No school on a day"
                    last == null || last == first -> "No school on ${first.format(DAY)}"
                    else -> "No school from ${first.format(DAY)} to ${last.format(DAY)}"
                }
            }
            ChangeType.HalfDay -> "Half-day on ${day(change)}, ends ${clock(change.endMin)}"
            ChangeType.OnDay -> "All of ${title(change.taskId)} on ${day(change)}"
            ChangeType.AtTime -> "${title(change.taskId)} at ${clock(change.startMin)} on ${day(change)}"
            ChangeType.Steps -> "${title(change.taskId)} in steps: " + change.steps.orEmpty().joinToString(", ") { "${it.title} (${it.minutes} min${if (it.anyOrder) ", in any order" else ""})" }
            ChangeType.SameAs -> "${title(change.taskId)} is the same work as ${title(change.afterTaskId)}: only that one is planned"
            ChangeType.Rename -> {
                val task = change.taskId?.let { tasks[it] }
                // Against its name as it is now; once this is the name in use, against its source's.
                val was = task?.let { t -> t.sourceValues?.title?.takeIf { t.mine.title != null && t.mine.title.value == change.title } ?: t.title } ?: "a task no longer listed"
                "“$was” renamed “${change.title}”"
            }
            ChangeType.DayOnly -> "${day(change)}: only ${title(change.taskId)}" + when (change.alsoAllow) {
                ALSO_ANKI -> "; Anki too"
                ALSO_DEADLINES -> "; Anki, and work that would miss its deadline, too"
                else -> ""
            }
            ChangeType.OutsideAt -> "${day(change)}: the ${clock(change.outsideMin)} break outside at ${clock(change.startMin)}"
            ChangeType.NoOutside -> if (change.outsideMin < 0) "${day(change)}: no breaks outside" else "${day(change)}: no ${clock(change.outsideMin)} break outside"
            ChangeType.OutsideAdded -> "${day(change)}: a break outside at ${clock(change.startMin)}"
            ChangeType.Weekly -> change.weekly?.let(::weeklyLine) ?: "Work every week"
            ChangeType.WeeklyEnd -> "“${change.weekly?.title ?: "A weekly rule"}” no longer comes round every week"
        }
    }

    /**
     * Whether [change] puts [task]'s work after its real deadline (PL-04): all of it on a day after
     * the deadline's, or its next piece at or after the deadline. Then it isn't applied: it's due
     * before then. Not once the deadline has gone by at [now]: overdue work can't be put before it,
     * and any time from now is as soon as it can be (an overdue task's card wouldn't apply).
     */
    fun latePin(change: Change, task: TaskItem, zone: ZoneId, now: Long): Boolean {
        val real = task.realDeadline?.takeIf { it > now } ?: return false
        return when (change.type) {
            ChangeType.OnDay -> date(change.date)?.let { it > Instant.ofEpochMilli(real).atZone(zone).toLocalDate() } == true
            ChangeType.AtTime -> change.time?.let { it >= real } == true
            else -> false
        }
    }

    /** What [change] puts in the school calendar (PL-16), or null for one of another kind or without its dates. */
    fun schoolEntry(change: Change): Any? = when (change.type) {
        ChangeType.NoSchool -> change.date?.let { from -> NoSchool(from, maxOf(from, change.endDate ?: from)) }
        ChangeType.HalfDay -> change.date?.let { date -> change.endMin?.let { HalfDay(date, it) } }
        else -> null
    }

    /** [rule] in a line: what, how much a week, on which days or in what sessions, and where. */
    fun weeklyLine(rule: WeeklyRule): String = buildString {
        append("“${rule.title}”: ${hours(rule.minutes)} a week")
        if (rule.days.isNotEmpty()) append(", " + rule.days.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.UK) })
        else if (rule.sessionMin > 0) append(", in ${hours(rule.sessionMin)} sessions")
        if (rule.where == "home") append(", at home")
    }

    private fun clock(minutes: Int?): String = minutes?.let { "%02d:%02d".format(Locale.UK, it / 60, it % 60) } ?: "?"

    private fun hours(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    private fun date(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

/**
 * The task with [o] as its instructions' say: kept on it, and laid over what the source and the
 * enrichment say at every merge ([enriched]). The source's own values are kept first, if they
 * weren't yet, so taking an instruction back restores them. Steps and a name are laid over a
 * source's task, never one derived from Anki's counts (its steps are its cards, its name their
 * count); one of your own has them made on it ([OwnTaskRules.MADE_ON_IT]).
 */
fun TaskItem.withMine(o: Mine): TaskItem {
    val laidOver = source != Source.Own && !derived
    val steps = o.steps?.takeIf { laidOver && it.steps.isNotEmpty() }
    val laid = copy(
        sourceValues = (sourceValues ?: SourceValues(kind, dueAt, availableFrom)).let { if (it.title == null) it.copy(title = title) else it },
        mine = o.copy(steps = steps, title = o.title?.takeIf { laidOver }),
    )
    // Your steps taken back: the enrichment's come back, as they're laid where there are none, their ticks kept by name.
    if (mine.steps == null || steps != null) return laid.enriched()
    return laid.copy(subSteps = emptyList()).enriched().let { it.copy(subSteps = carryMarks(it.subSteps, subSteps)) }
}
