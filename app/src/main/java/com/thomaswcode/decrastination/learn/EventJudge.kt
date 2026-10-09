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

    /** The key your answer is kept under: its name, so a weekly lesson is asked about once. */
    fun key(event: CalendarEvent): String = event.title.trim().lowercase()

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
            // All day, or most of one (an open day, a van hire booked 9 to 6): anything from nothing
            // to all of it. Ask.
            event.allDay || event.end - event.start >= LONG_MS -> Judgement.Ask
            else -> Judgement.Busy
        }
    }

    /** What the planner takes: the busy intervals, and the minutes of each day taken at no set time. */
    data class Time(val busy: List<Busy>, val dayLoads: Map<LocalDate, Int>, val toAsk: List<CalendarEvent>)

    fun time(events: List<CalendarEvent>, answers: Map<String, String>, zone: ZoneId): Time {
        val busy = mutableListOf<Busy>()
        val loads = HashMap<LocalDate, Int>()
        val ask = mutableListOf<CalendarEvent>()
        for (event in events) {
            when (val judgement = judge(event, answers)) {
                Judgement.Free -> Unit
                Judgement.Busy -> if (event.allDay) {
                    // All of each of its days.
                    days(event, zone).forEach { loads[it] = 24 * 60 }
                } else {
                    busy += Busy(event.start, event.end)
                }
                is Judgement.Load -> days(event, zone).forEach { day -> loads[day] = ((loads[day] ?: 0) + judgement.minutes).coerceAtMost(24 * 60) }
                Judgement.Ask -> ask += event
            }
        }
        return Time(busy, loads, ask.distinctBy { key(it) })
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
