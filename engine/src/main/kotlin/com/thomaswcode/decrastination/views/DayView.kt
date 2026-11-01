package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.MicroPlan
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.LaterToday
import com.thomaswcode.decrastination.sync.protocol.StopTimer
import com.thomaswcode.decrastination.sync.protocol.UndoKeep
import com.thomaswcode.decrastination.ui.DayLayout
import com.thomaswcode.decrastination.ui.DayModel
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.blockKey
import com.thomaswcode.decrastination.ui.outcomeWords
import com.thomaswcode.decrastination.ui.timerText
import com.thomaswcode.decrastination.ui.windowName
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * A day as the website draws it (W-14): what [DayModel] says of it, in the phone's words, with the minutes it's laid out
 * by. The website lays it out and holds no rule of its own about a task, a slot or a day.
 */
@Serializable
data class DayView(
    val date: String,
    val title: String,
    /** Whether it's today. */
    val today: Boolean,
    /** The minute it is, today's only: the line across the day. */
    val nowMin: Int? = null,
    /** Where the day opens, its minute. */
    val opensAt: Int,
    val windows: List<WindowView>,
    val allDay: List<EventChip>,
    val blocks: List<BlockView>,
    val unslotted: List<PieceChip>,
    val done: List<PieceChip>,
    /** "2 h 30 of 4 h", what's planned in it and its free time. */
    val load: String,
    val doneLine: String? = null,
    val wontFit: String? = null,
    /** Today's next thing to do, with what comes after it. */
    val next: NextView? = null,
    /** The week around it, Monday first. */
    val week: List<WeekDay>,
    /** When the phone last told the server anything, for the line that says how fresh this is. */
    val asOf: Long? = null,
    /** Whether anything can be changed here: the server applies commands. */
    val canChange: Boolean = false,
    /** What the form takes, for a task's sheet opened from here. */
    val form: OwnTaskFormView? = null,
    /** A timer running, on whichever piece. */
    val timer: TimerView? = null,
)

/** A piece's drop on the Day: the slot of [key] lifted from [date], let go on the strip's day [onto], or on the grid with its top at [minute]. */
@Serializable
data class DayDrop(val key: String, val date: String, val onto: String? = null, val minute: Int? = null)

/** A timer running (D-112): its piece, its time in the phone's words till [nextAt], and what stops it. */
@Serializable
data class TimerView(val label: String, val says: String, val nextAt: Long, val stop: Action? = null)

/** A window of the day's time to work in. */
@Serializable
data class WindowView(val name: String, val startMin: Int, val endMin: Int)

/** An all-day event. */
@Serializable
data class EventChip(val title: String, val colour: Int? = null)

/** A piece of work with no time, or one done. */
@Serializable
data class PieceChip(val label: String, val minutes: String, val urgent: Boolean = false)

/** The chosen week's day on the strip. */
@Serializable
data class WeekDay(val date: String, val weekday: String, val day: Int, val load: String?, val today: Boolean, val chosen: Boolean)

/** Today's next thing to do, and what's to be done with it (its [taskId]'s, by [TaskAction.ask]). */
@Serializable
data class NextView(val label: String, val first: String? = null, val then: String? = null, val taskId: String? = null, val actions: List<TaskAction> = emptyList())

/** What a block can be dragged as: its slot's [key], and how long it is. */
@Serializable
data class DragView(val key: String, val minutes: Int)

/** A block of the grid: [kind] one of piece, outside, event, record, busy. */
@Serializable
data class BlockView(
    val key: String,
    val kind: String,
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val times: String,
    /** A piece's: now, gone or ahead. */
    val state: String? = null,
    val urgent: Boolean = false,
    /** Its first step, or an event's place. */
    val note: String? = null,
    /** An event's colour (ARGB), and whether the plan counts it free. */
    val colour: Int? = null,
    val free: Boolean = false,
    /** A record's outcome: kept, late, missed. */
    val outcome: String? = null,
    /** Where it's laid among what's at the same time: its column of [columns], and how many it spans ([DayLayout.columns]). */
    val column: Int = 0,
    val columns: Int = 1,
    val span: Int = 1,
    /** A piece's task, whose sheet it opens. */
    val taskId: String? = null,
    /** Set where it can be dragged. */
    val drag: DragView? = null,
)

object DayViews {
    private val TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)

    /** The shortest a block is drawn: the website's grid has a pixel to the minute. */
    private const val SHORTEST_MIN = 24

    /** [date] by [plan], with [events], [records], your [busy] times and its [windows], at [now]. */
    fun of(
        plan: Plan,
        date: LocalDate,
        events: List<CalendarEvent>,
        records: List<SlotRecord>,
        windows: List<DayWindow>,
        now: Long,
        zone: ZoneId,
        busy: List<BusyBlock>,
        judge: (CalendarEvent) -> EventJudge.Judgement,
        micro: Map<String, MicroPlan>,
        asOf: Long?,
        timer: PieceTimer? = null,
        writable: Boolean = false,
    ): DayView {
        val model = DayModel.from(plan, date, events, records, windows, now, zone, busy)
        val today = date == plan.today
        val next = plan.next?.takeIf { today }
        return DayView(
            date = date.toString(),
            title = date.format(TITLE),
            today = today,
            nowMin = model.now?.let { minuteOf(it, date, zone) },
            opensAt = model.opensAt,
            windows = model.windows.map { WindowView(windowName(it.window.kind), it.window.startMin, it.window.endMin) },
            allDay = model.allDay.map { EventChip(it.title.ifBlank { "(no title)" }, it.colour) },
            blocks = placed(model.blocks.mapNotNull { block(it, date, zone, judge, micro, writable) }),
            unslotted = model.unslotted.map(::chip),
            done = model.done.map { PieceChip(it.label, Format.minutes(it.workedMin)) },
            load = Format.load(model.plannedMin, model.freeMin),
            doneLine = model.doneMin.takeIf { it > 0 }?.let { "Done ${Format.minutes(it)}" },
            wontFit = model.wontFit,
            next = next?.let { piece ->
                NextView(piece.label, micro[piece.key]?.first?.takeIf(String::isNotEmpty), plan.then?.label, piece.taskId.takeIf { writable }, if (writable) actions(piece, timer, now) else emptyList())
            },
            week = week(plan, date),
            asOf = asOf,
            canChange = writable,
            form = WorkViews.FORM.takeIf { writable },
            timer = timer?.takeIf { it.running }?.let { running ->
                // Its words turn at each of its whole minutes from its start, not the page's.
                TimerView(running.label, timerText(running, now), now + 60_000L - Math.floorMod(now - running.startedAt, 60_000L), StopTimer.takeIf { writable })
            },
        )
    }

    private fun minuteOf(at: Long, date: LocalDate, zone: ZoneId): Int = DayLayout.minuteOf(at, date, zone)

    /** [blocks] side by side where they overlap, as the phone lays them, a short one as tall as [SHORTEST_MIN] so its words fit. */
    private fun placed(blocks: List<BlockView>): List<BlockView> {
        val spots = DayLayout.columns(blocks.map { it.startMin to it.endMin }, SHORTEST_MIN)
        return blocks.zip(spots) { block, spot -> block.copy(column = spot.column, columns = spot.columns, span = spot.span) }
    }

    private fun chip(chunk: Chunk) = PieceChip(chunk.label, Format.minutes(chunk.minutes), chunk.urgent)

    /** What can be done with [piece] from here, as the phone's *Do now* card has it; what's the phone's own (its source opened, a photo taken) stays there. */
    private fun actions(piece: Chunk, timer: PieceTimer?, now: Long) = buildList {
        val on = timer?.takeIf { it.running && it.key == piece.key }
        add(if (on != null) TaskAction("Stop", StopTimer) else TaskAction("Start", ask = TaskAsk(TaskAsk.TIMER, key = piece.key, step = piece.stepIndex)))
        add(TaskAction("Done", ask = TaskAsk(TaskAsk.DONE, at = now, key = piece.key), undo = UndoKeep(piece.key, now)))
        add(TaskAction("Later today", LaterToday(piece.key)))
        // Yours: nothing to ask, so all of it done is your say.
        if (piece.source == Source.Own) add(TaskAction("All of it done", FinishOwnTask(piece.taskId)))
    }

    internal fun block(block: DayModel.Block, date: LocalDate, zone: ZoneId, judge: (CalendarEvent) -> EventJudge.Judgement, micro: Map<String, MicroPlan>, writable: Boolean): BlockView? {
        val key = blockKey(block)
        val start = minuteOf(block.start, date, zone)
        val end = minuteOf(block.end, date, zone).coerceAtLeast(start)
        val times = Format.span(block.start, block.end, zone)
        return when (val item = block.item) {
            is DayModel.Row.Window -> null
            is DayModel.Row.Planned -> {
                val chunk = item.slot.chunk
                val state = item.state.name.lowercase(Locale.UK)
                if (item.slot.kind == SlotKind.Outside || chunk == null) {
                    // Not under way (D-113, D-116).
                    BlockView(key, "outside", start, end, "Outside", times, state, drag = DragView(item.slot.key, ((block.end - block.start) / 60_000L).toInt()).takeIf { writable && item.state == DayModel.State.Ahead })
                } else {
                    BlockView(key, "piece", start, end, chunk.label, times, state, chunk.urgent, micro[chunk.key]?.first?.takeIf(String::isNotEmpty), taskId = chunk.taskId, drag = DragView(item.slot.key, chunk.minutes).takeIf { writable })
                }
            }
            is DayModel.Row.Event -> {
                val judged = judge(item.event)
                BlockView(key, "event", start, end, item.event.title.ifBlank { "(no title)" }, times, note = item.event.location.takeIf(String::isNotEmpty), colour = item.event.colour, free = judged == EventJudge.Judgement.Free)
            }
            is DayModel.Row.Recorded -> {
                val outcome = when (item.record.outcome) {
                    SlotOutcome.Missed -> "missed"
                    SlotOutcome.KeptLate -> "late"
                    else -> "kept"
                }
                BlockView(key, "record", start, end, item.record.label, "$times · ${outcomeWords(item.record.outcome)}", outcome = outcome)
            }
            is DayModel.Row.Busy -> BlockView(key, "busy", start, end, item.block.title?.let { "Busy: $it" } ?: "Busy", times)
        }
    }

    private fun week(plan: Plan, chosen: LocalDate): List<WeekDay> {
        val monday = chosen.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L until 7L).map { monday.plusDays(it) }.map { day ->
            val bucket = plan.bucket(day)
            WeekDay(day.toString(), day.format(WEEKDAY), day.dayOfMonth, bucket?.takeIf { it.plannedMin > 0 }?.let { Format.minutes(it.plannedMin) }, day == plan.today, day == chosen)
        }
    }
}
