package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Planner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's calendar as busy time (Q7, docs/spec/slots-and-calendar.md): the next fortnight's events, judged one
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

    /** The answers a question offers, in words and as they're kept: his chips on it, and on the Calendar page. */
    val ANSWERS: List<Pair<String, String>> = listOf(
        "All of it" to EventJudge.Judgement.Busy,
        "A few hours" to EventJudge.Judgement.Load(EventJudge.FEW_HOURS_MIN),
        "None" to EventJudge.Judgement.Free,
    ).map { (label, judgement) -> label to EventJudge.store(judgement)!! }

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    /** Of the events the rules can't judge, those to ask about now: not over, within the week, not asked before. */
    fun questions(candidates: List<CalendarEvent>, asked: Set<String>, now: Long): List<CalendarEvent> =
        candidates.filter { it.end > now && it.start < now + ASK_AHEAD_MS && EventJudge.key(it) !in asked }.distinctBy(EventJudge::key)

    /** The day a question names: an all-day event's date as stored (UTC), a timed one's where you are. */
    fun questionDay(event: CalendarEvent, zone: ZoneId): String =
        Instant.ofEpochMilli(event.start).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate().format(DAY)

}
