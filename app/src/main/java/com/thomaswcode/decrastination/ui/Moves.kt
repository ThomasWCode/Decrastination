package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBreaks
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * A piece moved by hand (UI-04), as the move sheet makes the move: its task's next piece to a time on a day ([toTime]), or all
 * of its task to a day ([toDay]), each a change of yours, taken back in Your changes; the days a piece can go to are
 * [dayAllowed]'s. A break outside, dragged on the Day, to a time on its own day ([outsideTo], D-116). Each is checked first, and
 * refused saying why, so none's said done that the plan would drop.
 */
internal object Moves {
    /** What a move came to: made, and said so; or refused, and why. */
    sealed interface Moved {
        data class Done(val said: String) : Moved

        data class Refused(val why: String) : Moved
    }

    val CHIP_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.UK)

    /**
     * Whether [task]'s work can go to [day] at [now]: not after its real deadline's day still to come (overdue work can go to any,
     * D-30), nor before the day it can start, as the plan keeps a day.
     */
    fun dayAllowed(task: TaskItem?, day: LocalDate, now: Long, zone: ZoneId): Boolean {
        val last = task?.realDeadline?.takeIf { it > now }?.let { Planner.date(it, zone) }
        val first = task?.notBefore?.let { Planner.date(it, zone) }
        return !(last != null && day > last) && !(first != null && day < first)
    }

    /** [chunk], its task's next piece, at [minute] of [day]: an `AtTime` change of yours, unless the plan wouldn't keep it. */
    fun toTime(graph: AppGraph, chunk: Chunk, day: LocalDate, minute: Int, zone: ZoneId): Moved {
        val task = graph.tasks.value.tasks.firstOrNull { it.id == chunk.taskId }
        val time = timeOf(day, minute, zone)
        val now = graph.clock.now()
        // Only a time the plan will keep: none it would drop is said done.
        val problem = when (task?.let { Planner.pinProblem(it, time, now, zone, chunk.minutes) }) {
            null -> null
            Planner.PinProblem.DayGone -> "That time's gone: pick a later one."
            Planner.PinProblem.NotYet -> "It can't be started till ${Format.at(task.notBefore!!, now, zone)}: pick a later time."
            Planner.PinProblem.PastDeadline -> "It's due ${Format.at(task.realDeadline!!, now, zone)}: pick a time before then."
        }
        if (problem != null) return Moved.Refused(problem)
        val said = "At ${day.format(CHIP_DAY)} ${"%02d:%02d".format(Locale.UK, minute / 60, minute % 60)}"
        val joining = joins(graph, chunk, day)
        graph.scope.launch { graph.instructions.addOwnChange(UUID.randomUUID().toString(), said, About(taskId = chunk.taskId, taskTitle = chunk.title), Change(ChangeType.AtTime, taskId = chunk.taskId, date = day.toString(), startMin = minute, time = time)) }
        return Moved.Done(said + joining)
    }

    /**
     * All of [chunk]'s task on [day], any time that day: an `OnDay` change of yours, a time you gave it here before taken back,
     * as the plan would keep it on its day; refused where a time from the chat holds it that day.
     */
    fun toDay(graph: AppGraph, chunk: Chunk, day: LocalDate, zone: ZoneId): Moved {
        val task = graph.tasks.value.tasks.firstOrNull { it.id == chunk.taskId }
        val applied = graph.instructions.value.applied
        val quick = InstructionRules.quickInForce(applied, chunk.taskId, ChangeType.AtTime)
        val pinned = task?.mine?.atTime?.value?.takeIf { Planner.date(it, zone) == day }
        // A time from the chat holds it that day, over a day given after it: it's taken back where it was made.
        if (pinned != null && !quick) return Moved.Refused("A time you gave in the chat holds it at ${Format.at(pinned, graph.clock.now(), zone)}: take that back in the chat's Your changes first.")
        // Any time: a time you gave it here before goes, as the plan would keep it on its day.
        graph.scope.launch {
            if (quick) graph.instructions.takeBackQuick(chunk.taskId, ChangeType.AtTime)
            graph.instructions.addOwnChange(UUID.randomUUID().toString(), "All of it on ${day.format(CHIP_DAY)}", About(taskId = chunk.taskId, taskTitle = chunk.title), Change(ChangeType.OnDay, taskId = chunk.taskId, date = day.toString()))
        }
        return Moved.Done("All of it on ${day.format(CHIP_DAY)}" + joins(graph, chunk, day))
    }

    /**
     * A break outside ([slot]) to [minute] of its own [day] (D-116): an `OutsideAt` change of yours naming it by its own time,
     * kept to the minute as a pin is; refused where [outsideProblem] says why.
     */
    fun outsideTo(graph: AppGraph, slot: Slot, day: LocalDate, minute: Int, zone: ZoneId): Moved {
        outsideProblem(graph.planning.breaksOn(day), slot.outsideAt, minute, timeOf(day, minute, zone), graph.clock.now())?.let { return Moved.Refused(it) }
        val said = "Outside at ${day.format(CHIP_DAY)} ${"%02d:%02d".format(Locale.UK, minute / 60, minute % 60)}"
        graph.scope.launch { graph.instructions.addOwnChange(UUID.randomUUID().toString(), said, About(day = day.toString()), Change(ChangeType.OutsideAt, date = day.toString(), outsideMin = slot.outsideAt, startMin = minute)) }
        return Moved.Done(said)
    }

    /**
     * Why the break outside named [own] can't go to [minute] (its moment [at]) among its day's [breaks] at [now]: its time
     * gone, the break begun (D-113), past midnight, or over another of the day's; null where it can.
     */
    fun outsideProblem(breaks: DayBreaks, own: Int, minute: Int, at: Long, now: Long): String? = when {
        at < now -> "That time's gone"
        own in breaks.begun -> "That break outside has begun"
        minute + breaks.length > DayLayout.DAY_MINUTES -> "A break outside is over by midnight"
        breaks.over(minute, own) -> "Another break outside is then"
        else -> null
    }

    /** [minute] of [day] by its clock, as the planner lays it: on a day the clocks change too. */
    fun timeOf(day: LocalDate, minute: Int, zone: ZoneId): Long = day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()

    /** A day kept for only other tasks (D-115): the move makes [chunk]'s task one of them, said after the move's words. */
    private fun joins(graph: AppGraph, chunk: Chunk, day: LocalDate): String =
        InstructionRules.onlyDays(graph.instructions.value.applied, day, day)[day]?.takeIf { chunk.taskId !in it.taskIds }
            ?.let { " (" + InstructionRules.joinLine(day, it.taskIds - it.joined, chunk.taskId, graph.tasks.value.tasks.associateBy { t -> t.id }) + ")" }.orEmpty()
}
