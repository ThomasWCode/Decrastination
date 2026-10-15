package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.LocalDate
import java.time.ZoneId

/**
 * A day as the Day screen shows it (UI-17, UI-02, UI-08): its windows, its calendar's events, its
 * slots (on record, under way, to come), the free time between them, the moment it is, the pieces
 * with no time in it, and what was done; and its load. Pure.
 */
data class DayModel(
    val date: LocalDate,
    val rows: List<Row>,
    /** The minutes planned in it, and its free time (UI-02). */
    val plannedMin: Int,
    val freeMin: Int,
    /** The minutes worked in its kept slots (UI-08). */
    val doneMin: Int,
    /** A line about the real deadlines its days can't hold (PL-02), today's. */
    val wontFit: String?,
) {
    sealed interface Row {
        /** A window of the day's time to work in, as a heading: break, lunch, a study period, the evening. */
        data class Window(val window: DayWindow, val start: Long, val end: Long) : Row

        /** An event of the day's calendar. */
        data class Event(val event: CalendarEvent) : Row

        /** A slot of the plan's: under way, to come, or gone by and not moved yet. */
        data class Planned(val slot: Slot, val state: State) : Row

        /** A slot on record: kept, kept late, or missed. */
        data class Recorded(val record: SlotRecord) : Row

        /** Free time between two slots in one window, long enough to say. */
        data class Gap(val start: Long, val end: Long) : Row {
            val minutes: Int get() = ((end - start) / 60_000L).toInt()
        }

        /** The moment it is: today's only. */
        data class Now(val at: Long) : Row

        /** The pieces the day has no time for, under their heading. */
        data class Unslotted(val chunks: List<Chunk>) : Row

        /** What was done, at the foot (UI-08). */
        data class Done(val records: List<SlotRecord>) : Row
    }

    enum class State { Gone, Now, Ahead }

    companion object {
        /** The least free time between two slots that's said: the breaks between pieces are shorter. */
        const val GAP_MIN = 15

        /**
         * [date] as [plan] has it, with [events] (those on the day are kept), [records] (the day's
         * slots on record; a move of yours is neither) and its [windows], at [now]. Rows by time;
         * at the same moment, a window's heading first, then the now line.
         */
        fun from(plan: Plan, date: LocalDate, events: List<CalendarEvent>, records: List<SlotRecord>, windows: List<DayWindow>, now: Long, zone: ZoneId): DayModel {
            val bucket = plan.bucket(date)
            val today = date == plan.today
            fun at(minute: Int) = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
            val recorded = records.filter { it.date == date.toString() && it.outcome != SlotOutcome.Moved }
            val timed = mutableListOf<Pair<Long, Row>>()
            windows.forEach { w -> timed += at(w.startMin) to Row.Window(w, at(w.startMin), at(w.endMin)) }
            events.filter { e -> date in EventJudge.days(e, zone) }.forEach { e -> timed += (if (e.allDay) at(0) else maxOf(e.start, at(0))) to Row.Event(e) }
            bucket?.slots.orEmpty().forEach { s ->
                val state = when {
                    !today -> State.Ahead
                    now >= s.end -> State.Gone
                    now >= s.start -> State.Now
                    else -> State.Ahead
                }
                timed += s.start to Row.Planned(s, state)
            }
            recorded.forEach { r -> timed += r.start to Row.Recorded(r) }
            // Free time between one slot and the next in one window: today's still to come.
            val spans = (bucket?.slots.orEmpty().map { it.start to it.end } + recorded.filter { it.outcome.kept }.map { it.start to it.end }).sortedBy { it.first }
            spans.zipWithNext().forEach { (a, b) ->
                val inOne = windows.any { w -> a.second >= at(w.startMin) && b.first <= at(w.endMin) }
                if (b.first - a.second >= GAP_MIN * 60_000L && inOne && (!today || b.first > now)) timed += a.second to Row.Gap(a.second, b.first)
            }
            if (today) timed += now to Row.Now(now)
            val rows = timed.sortedWith(compareBy({ it.first }, { order(it.second) })).map { it.second }.toMutableList()
            bucket?.unslotted?.takeIf { it.isNotEmpty() }?.let { rows += Row.Unslotted(it) }
            val done = recorded.filter { it.outcome.kept }
            if (done.isNotEmpty()) rows += Row.Done(done)
            val short = plan.wontFit.takeIf { today && it.isNotEmpty() }
            return DayModel(
                date = date,
                rows = rows,
                plannedMin = bucket?.plannedMin ?: 0,
                freeMin = bucket?.capacityMin ?: 0,
                doneMin = done.sumOf { it.workedMin },
                wontFit = short?.let { "${it.size} won't fit before ${if (it.size == 1) "its deadline" else "their deadlines"}" },
            )
        }

        private fun order(row: Row): Int = when (row) {
            is Row.Window -> 0
            is Row.Now -> 1
            is Row.Event -> 2
            is Row.Recorded -> 3
            is Row.Planned -> 4
            is Row.Gap -> 5
            else -> 6
        }
    }
}
