package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.LocalDate
import java.time.ZoneId

/**
 * A day as the Day's grid shows it (UI-17, UI-02, UI-08, 4.6.0): its windows; its all-day events; its blocks by time (its
 * calendar's timed events, your busy times, its slots under way, to come or gone by, and its slots on record), each cut to the
 * day; the moment it is (today's); the pieces it has no time for, and what was done; its load; and where a day not today opens.
 * Pure.
 */
data class DayModel(
    val date: LocalDate,
    val windows: List<Row.Window>,
    val allDay: List<CalendarEvent>,
    val blocks: List<Block>,
    /** The moment it is: today's only. */
    val now: Long?,
    val unslotted: List<Chunk>,
    /** Its slots kept (UI-08). */
    val done: List<SlotRecord>,
    /** The minutes planned in it, and its free time (UI-02). */
    val plannedMin: Int,
    val freeMin: Int,
    /** The minutes worked in its kept slots (UI-08). */
    val doneMin: Int,
    /** A line about the real deadlines its days can't hold (PL-02), today's. */
    val wontFit: String?,
    /** Where a day not today opens, its minute: half an hour before the earlier of its first window and first block. */
    val opensAt: Int,
) {
    sealed interface Row {
        /** A window of the day's time to work in: break, lunch, a study period, the evening. */
        data class Window(val window: DayWindow, val start: Long, val end: Long) : Row

        /** An event of the day's calendar. */
        data class Event(val event: CalendarEvent) : Row

        /** A slot of the plan's: under way, to come, or gone by and not moved yet. */
        data class Planned(val slot: Slot, val state: State) : Row

        /** A slot on record: kept, kept late, or missed; a missed one with the plan's [piece] of its key, where it's still planned. */
        data class Recorded(val record: SlotRecord, val piece: Chunk? = null) : Row

        /** A busy time of yours (D-117), as your calendar has it. */
        data class Busy(val block: BusyBlock) : Row
    }

    /** A block of the grid: [item] from [start] to [end], cut to the day (an event across midnight). */
    data class Block(val start: Long, val end: Long, val item: Row)

    enum class State { Gone, Now, Ahead }

    companion object {
        /** How long before a day's first window or block it opens, in minutes. */
        const val OPEN_BEFORE_MIN = 30

        /** Where a day with no window or block opens: half an hour before 08:00. */
        private const val FIRST_MIN = 8 * 60

        /**
         * [date] as [plan] has it, with [events] (those on the day are kept), [records] (the day's slots on record; a move of
         * yours is neither), your [busy] times, and its [windows], at [now]. Blocks by time, the day's clock time on a day the
         * clocks change too.
         */
        fun from(plan: Plan, date: LocalDate, events: List<CalendarEvent>, records: List<SlotRecord>, windows: List<DayWindow>, now: Long, zone: ZoneId, busy: List<BusyBlock> = emptyList()): DayModel {
            val bucket = plan.bucket(date)
            val today = date == plan.today
            fun at(minute: Int) = date.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
            val dayStart = at(0)
            val dayEnd = date.plusDays(1).atStartOfDay().atZone(zone).toInstant().toEpochMilli()
            fun cut(start: Long, end: Long, item: Row) = Block(maxOf(start, dayStart), minOf(end, dayEnd), item)
            val recorded = records.filter { it.date == date.toString() && it.outcome != SlotOutcome.Moved }
            val onDay = events.filter { e -> date in EventJudge.days(e, zone) }
            val blocks = buildList {
                onDay.filterNot { it.allDay }.forEach { e -> add(cut(e.start, e.end, Row.Event(e))) }
                busy.filter { it.date == date }.forEach { b -> add(Block(at(b.startMin), at(b.endMin), Row.Busy(b))) }
                bucket?.slots.orEmpty().forEach { s ->
                    val state = when {
                        !today -> State.Ahead
                        now >= s.end -> State.Gone
                        now >= s.start -> State.Now
                        else -> State.Ahead
                    }
                    add(Block(s.start, s.end, Row.Planned(s, state)))
                }
                recorded.forEach { r -> add(cut(r.start, r.end, Row.Recorded(r, plan.ordered.firstOrNull { it.key == r.key }?.takeIf { r.outcome == SlotOutcome.Missed }))) }
            }.sortedWith(compareBy({ it.start }, { it.end }))
            val first = listOfNotNull(windows.minOfOrNull { it.startMin }, blocks.minOfOrNull { DayLayout.minuteOf(it.start, date, zone) }).minOrNull() ?: FIRST_MIN
            val done = recorded.filter { it.outcome.kept }
            val short = plan.wontFit.takeIf { today && it.isNotEmpty() }
            return DayModel(
                date = date,
                windows = windows.map { w -> Row.Window(w, at(w.startMin), at(w.endMin)) },
                allDay = onDay.filter { it.allDay },
                blocks = blocks,
                now = now.takeIf { today },
                unslotted = bucket?.unslotted.orEmpty(),
                done = done,
                plannedMin = bucket?.plannedMin ?: 0,
                freeMin = bucket?.capacityMin ?: 0,
                doneMin = done.sumOf { it.workedMin },
                wontFit = short?.let { "${it.size} won't fit before ${if (it.size == 1) "its deadline" else "their deadlines"}" },
                opensAt = (first - OPEN_BEFORE_MIN).coerceIn(0, DayLayout.DAY_MINUTES - 60),
            )
        }
    }
}

/** Each block's key, its own however alike two blocks are: a slot kept twice is two records, told apart by when. */
fun blockKey(block: DayModel.Block): String = when (val item = block.item) {
    is DayModel.Row.Window -> "window-${item.start}"
    is DayModel.Row.Event -> "event-${item.event.id}@${item.event.start}"
    is DayModel.Row.Planned -> "slot-${item.slot.key}-${item.slot.start}"
    is DayModel.Row.Recorded -> "record-${item.record.key}-${item.record.start}-${item.record.outcome}-${item.record.at}"
    is DayModel.Row.Busy -> "busy-${item.block.date}-${item.block.key}"
}

/** A window's name, as its band says it. */
fun windowName(kind: WindowKind): String = when (kind) {
    WindowKind.Evening -> "Your time"
    WindowKind.Study -> "Study period"
    WindowKind.Break -> "Break"
    WindowKind.Lunch -> "Lunch"
}

/** A slot's outcome, in a word or two. */
fun outcomeWords(outcome: SlotOutcome): String = when (outcome) {
    SlotOutcome.Kept -> "Kept"
    SlotOutcome.KeptLate -> "Kept late"
    SlotOutcome.Missed -> "Missed"
    SlotOutcome.Moved -> "Moved"
}
