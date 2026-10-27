package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Busy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One event from the phone's calendar, as read. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    /** Shown as busy in the calendar (its availability), not free. */
    val busy: Boolean = true,
    /** The calendar it's in ("Holidays in United Kingdom", "Birthdays"). */
    val calendar: String = "",
    /** Where it is, and what it says of itself (DX-12): its description, cut short. */
    val location: String = "",
    val description: String = "",
    /** Its colour as the calendar app shows it (ARGB): its own, else its calendar's; null where the provider gives none. */
    val colour: Int? = null,
)

/**
 * How a calendar event bears on study time (Q7), judged event by event: a drum lesson takes its
 * whole slot; you can work on a train; an all-day van hire takes a few hours. What the rules can't
 * tell (an all-day event, or a timed one of four hours or more), you're asked, and your answer
 * holds for every event of that name.
 */
object EventJudge {

    sealed interface Judgement {
        /** You can work through it. */
        data object Free : Judgement

        /** It takes its slot. */
        data object Busy : Judgement

        /** It takes this much of its day, at no set time. */
        data class Load(val minutes: Int) : Judgement

        /** The rules can't tell: ask. */
        data object Ask : Judgement
    }

    /** "A few hours", the middle answer to an all-day event. */
    const val FEW_HOURS_MIN = 180

    /** A timed event this long may or may not take all its time: asked about, like an all-day one. */
    private const val LONG_MS = 4 * 3_600_000L

    private val TRAVEL = Regex("""\b(train|bus|coach|flight|plane|ferry|tube|metro)\b""", RegexOption.IGNORE_CASE)
    private val NOT_YOURS = Regex("""holiday|birthday|observance|week number""", RegexOption.IGNORE_CASE)
    /** A timed event saying it may not be on, or not need you (DX-12). */
    private val MAYBE = Regex("""\b(optional|tentative)\b""", RegexOption.IGNORE_CASE)

    /**
     * The key your answer is kept under: its name, so a weekly lesson is asked about once; one
     * with no name, its own event, so untitled events aren't all answered as one.
     */
    fun key(event: CalendarEvent): String = event.title.trim().lowercase().ifEmpty { "#${event.id}" }

    /** Your answers as stored: "free", "busy", or "load:<minutes>". */
    fun parse(answer: String?): Judgement? = when {
        answer == null -> null
        answer == "free" -> Judgement.Free
        answer == "busy" -> Judgement.Busy
        answer.startsWith("load:") -> answer.removePrefix("load:").toIntOrNull()?.let { Judgement.Load(it.coerceIn(0, 24 * 60)) }
        else -> null
    }

    fun store(judgement: Judgement): String? = when (judgement) {
        Judgement.Free -> "free"
        Judgement.Busy -> "busy"
        is Judgement.Load -> "load:${judgement.minutes}"
        Judgement.Ask -> null
    }

    fun judge(event: CalendarEvent, answers: Map<String, String>): Judgement {
        parse(answers[key(event)])?.let { return it }
        return when {
            // Marked free, a public holiday or a birthday, or travel you can work on.
            !event.busy || NOT_YOURS.containsMatchIn(event.calendar) || TRAVEL.containsMatchIn(event.title) -> Judgement.Free
            // Optional, or tentative: how much of your time it takes is yours to say (DX-12).
            !event.allDay && MAYBE.containsMatchIn(event.description) -> Judgement.Ask
            // All day, or most of one (an open day, a van hire booked 9 to 6): anything from nothing
            // to all of it. Ask.
            event.allDay || event.end - event.start >= LONG_MS -> Judgement.Ask
            else -> Judgement.Busy
        }
    }

    /**
     * What the planner takes: the busy intervals, and the minutes of each day taken at no set time;
     * and the events as read, for the Day timeline and your instructions about them. One to ask
     * about takes all its time until you answer (D-44), so the plan counts on no time it may not
     * have; one that would be asked about but starts after [askUntil] (DX-14: questions keep to the
     * week ahead) counts as a few hours of its day meanwhile, so the plan beyond it leaves it room.
     * [events] as last read, judged; [read] from when to when that read ran (null where the calendar's never been read), as a failed read keeps the one before.
     */
    data class Time(val busy: List<Busy>, val dayLoads: Map<LocalDate, Int>, val toAsk: List<CalendarEvent>, val events: List<CalendarEvent> = emptyList(), val read: Pair<Long, Long>? = null)

    fun time(events: List<CalendarEvent>, answers: Map<String, String>, zone: ZoneId, askUntil: Long): Time {
        val busy = mutableListOf<Busy>()
        val loads = HashMap<LocalDate, Int>()
        val ask = mutableListOf<CalendarEvent>()
        // All its time: all of each of an all-day event's days, a timed one's own hours.
        fun takeAll(event: CalendarEvent) {
            if (event.allDay) days(event, zone).forEach { loads[it] = 24 * 60 } else busy += Busy(event.start, event.end)
        }
        for (event in events) {
            val judged = judge(event, answers)
            when (val judgement = if (judged == Judgement.Ask && event.start > askUntil) Judgement.Load(FEW_HOURS_MIN) else judged) {
                Judgement.Free -> Unit
                Judgement.Busy -> takeAll(event)
                // An all-day event's load on each of its days; a timed one's once, on the day it starts.
                is Judgement.Load -> (if (event.allDay) days(event, zone) else days(event, zone).take(1)).forEach { day ->
                    loads[day] = ((loads[day] ?: 0) + judgement.minutes).coerceAtMost(24 * 60)
                }
                Judgement.Ask -> {
                    ask += event
                    takeAll(event)
                }
            }
        }
        // Every one to ask about: which of them is asked (not over yet, one per name) is decided
        // with the time, so an ended occurrence can't stand in for the next.
        return Time(busy, loads, ask, events)
    }

    /**
     * The days an event covers. All-day events are stored from midnight UTC to midnight UTC, so
     * their dates are read in UTC; timed ones in [zone].
     */
    fun days(event: CalendarEvent, zone: ZoneId): List<LocalDate> {
        val z = if (event.allDay) ZoneId.of("UTC") else zone
        val first = Instant.ofEpochMilli(event.start).atZone(z).toLocalDate()
        val last = Instant.ofEpochMilli((event.end - 1).coerceAtLeast(event.start)).atZone(z).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
    }
}
