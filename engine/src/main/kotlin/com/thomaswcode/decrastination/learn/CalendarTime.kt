package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Planner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's calendar as busy time (Q7, docs/spec/slots-and-calendar.md): its events as far as the plan's days run ([readUntil]), judged one
 * by one ([EventJudge]), given to the planner; an all-day event the rules can't judge is asked
 * about once, as Felix's message in the chat, within the week before it, and takes all its time till answered.
 */
object CalendarTime {
    const val DAY_MS = 24 * 3_600_000L
    /**
     * The end of the plan's last day (DX-14): its days run to today + [Planner.MAX_HORIZON_DAYS], each
     * whole, so the calendar is read to that day's midnight, where you are; a trip in the plan's third
     * week takes its day's hours out of the spread.
     */
    fun readUntil(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(Planner.MAX_HORIZON_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
    const val ASK_AHEAD_MS = 7 * DAY_MS

    /**
     * The answers a question offers, in words and as they're kept, for an all-day event or a timed one of
     * four hours or more: his chips on it, and on the event's sheet.
     */
    val ANSWERS: List<Pair<String, String>> = listOf(
        "All of it" to EventJudge.Judgement.Busy,
        "A few hours" to EventJudge.Judgement.Load(EventJudge.FEW_HOURS_MIN),
        "None" to EventJudge.Judgement.Free,
    ).map { (label, judgement) -> label to EventJudge.store(judgement)!! }

    /**
     * [event]'s answers (Q149): a timed one under four hours has *Half of it* in place of *A few hours*,
     * kept as half its length in minutes, so another event of its name takes the same minutes.
     */
    fun answers(event: CalendarEvent): List<Pair<String, String>> =
        if (EventJudge.halves(event)) listOf(ANSWERS[0], "Half of it" to EventJudge.store(EventJudge.middle(event))!!, ANSWERS[2]) else ANSWERS

    /**
     * The answers a question about the event named [key] starting at [start] was asked with, worked out
     * from the [events] as last read, as its message keeps the event's name and start, not its length; one
     * no longer among them has [ANSWERS], so a reply of *Half of it* to it goes to Felix as words.
     */
    fun answers(key: String, start: Long?, events: List<CalendarEvent>): List<Pair<String, String>> =
        events.firstOrNull { EventJudge.key(it) == key && it.start == start }?.let(::answers) ?: ANSWERS

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    /** Of the events the rules can't judge, those to ask about now: not over, within the week, not asked before. */
    fun questions(candidates: List<CalendarEvent>, asked: Set<String>, now: Long): List<CalendarEvent> =
        candidates.filter { it.end > now && it.start < now + ASK_AHEAD_MS && EventJudge.key(it) !in asked }.distinctBy(EventJudge::key)

    /** The day a question names: an all-day event's date as stored (UTC), a timed one's where you are. */
    fun questionDay(event: CalendarEvent, zone: ZoneId): String =
        Instant.ofEpochMilli(event.start).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate().format(DAY)

}
