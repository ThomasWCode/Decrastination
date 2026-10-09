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
 * reads it into [changes] (enrich/InstructionReader.kt), which you see and apply or discard; once
 * applied they're laid over the plan until you delete it.
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
)

@Serializable
enum class InstructionStatus {
    /** Waiting for Claude to read it (it may be off, resting, or capped: [Instruction.note] says). */
    Reading,

    /** Read: its changes wait for you to apply or discard them. */
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

    /** Every event named [Change.eventKey] takes [Change.eventAnswer] of your time ("free", "busy", "load:<minutes>"). */
    EventTime,

    /** At most [Change.freeMin] minutes of work on [Change.date], or every [Change.weekday]. */
    DayLimit,

    /** No work from [Change.startMin] to [Change.endMin] on [Change.date], or every [Change.weekday]. */
    BusyTime,
}

/** One change an instruction makes. Which fields mean anything depends on [type]. */
@Serializable
data class Change(
    val type: ChangeType,
    val taskId: String? = null,
    /** [ChangeType.StartFrom]'s start or [ChangeType.DueBy]'s deadline. */
    val time: Long? = null,
    val afterTaskId: String? = null,
    val eventKey: String? = null,
    val eventTitle: String? = null,
    /** As event answers are kept: "free", "busy" or "load:<minutes>". */
    val eventAnswer: String? = null,
    /** A day, as YYYY-MM-DD. */
    val date: String? = null,
    /** Every such day, 1 (Monday) to 7 (Sunday). */
    val weekday: Int? = null,
    val freeMin: Int? = null,
    /** Minutes after midnight. */
    val startMin: Int? = null,
    val endMin: Int? = null,
)

/** Your instructions. Kept in `instructions.json`. */
@Serializable
data class InstructionState(val instructions: List<Instruction> = emptyList()) {
    /** Those in use, in the order they were applied: a later one's change wins over an earlier's. */
    val applied: List<Instruction> get() = instructions.filter { it.state == InstructionStatus.Applied }.sortedBy { it.appliedAt ?: it.at }
}

/** What applied instructions say about one task. */
data class TaskOverrides(val notATask: Boolean = false, val from: Long? = null, val after: String? = null, val dueAt: Long? = null) {
    companion object {
        val NONE = TaskOverrides()
    }
}

/**
 * Your instructions' changes, as the rest of the app takes them: a task's on the task itself (so a
 * sync keeps them, and every screen sees them), an event's with the calendar's answers, and a day's
 * with the planner's capacity. Pure.
 */
object Instructions {

    /** A change of a task's due date: once protection is armed, it needs a parent code to apply, or to take back. */
    fun needsCode(changes: List<Change>): Boolean = changes.any { it.type == ChangeType.DueBy }

    /**
     * Whether [task] waits, as you said, for another still open ([byId]: every task). Never for one
     * that, along its own waits, comes back to it: tasks waiting on each other in a circle would
     * never be planned, so neither waits.
     */
    fun waiting(task: TaskItem, byId: Map<String, TaskItem>): Boolean {
        val first = task.userAfter ?: return false
        if (byId[first]?.isOpen != true) return false
        val seen = hashSetOf(task.id)
        var next: String? = first
        while (next != null) {
            if (!seen.add(next)) return next != task.id
            next = byId[next]?.takeIf { it.isOpen }?.userAfter
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

    /** Each task's overrides from [applied] (in order: a later one's wins). */
    fun taskOverrides(applied: List<Instruction>): Map<String, TaskOverrides> {
        val out = HashMap<String, TaskOverrides>()
        for (change in applied.flatMap { it.changes }) {
            val id = change.taskId ?: continue
            val o = out[id] ?: TaskOverrides.NONE
            out[id] = when (change.type) {
                ChangeType.NotATask -> o.copy(notATask = true)
                ChangeType.StartFrom -> o.copy(from = change.time)
                ChangeType.After -> o.copy(after = change.afterTaskId)
                ChangeType.DueBy -> o.copy(dueAt = change.time)
                else -> o
            }
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

    /** The times [applied] says you can't work, from [from] to [to], where you are. */
    fun busy(applied: List<Instruction>, zone: ZoneId, from: LocalDate, to: LocalDate): List<Busy> {
        val times = applied.flatMap { it.changes }.filter { it.type == ChangeType.BusyTime && it.startMin != null && it.endMin != null && it.endMin > it.startMin }
        if (times.isEmpty()) return emptyList()
        val out = mutableListOf<Busy>()
        var day = from
        while (day <= to) {
            for (t in times) {
                val on = if (t.date != null) date(t.date) == day else t.weekday == day.dayOfWeek.value
                if (!on) continue
                val midnight = day.atStartOfDay()
                out += Busy(
                    midnight.plusMinutes(t.startMin!!.toLong()).atZone(zone).toInstant().toEpochMilli(),
                    midnight.plusMinutes(t.endMin!!.toLong()).atZone(zone).toInstant().toEpochMilli(),
                )
            }
            day = day.plusDays(1)
        }
        return out
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * [change] in words, as you check it before applying ([tasks]: to name them, and say what a
     * date was before).
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
                val inUse = task?.userDueAt != null && task.userDueAt == change.time
                val was = if (inUse) task?.sourceValues?.dueAt else task?.dueAt
                "${title(change.taskId)}: due ${at(change.time)?.format(DAY_TIME) ?: "on another date"}" +
                    when {
                        was == null -> " (it has no due date)"
                        inUse -> " (its source says ${at(was)!!.format(DAY_TIME)})"
                        else -> " (now ${at(was)!!.format(DAY_TIME)})"
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
            ChangeType.BusyTime -> "${day(change)}, ${clock(change.startMin)}–${clock(change.endMin)}: busy, no work"
        }
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
 * weren't yet, so taking an instruction back restores them.
 */
fun TaskItem.withOverrides(o: TaskOverrides): TaskItem =
    copy(
        sourceValues = sourceValues ?: SourceValues(kind, dueAt, availableFrom),
        userNotATask = o.notATask,
        userFrom = o.from,
        userAfter = o.after,
        userDueAt = o.dueAt,
    ).enriched()
