package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.DailyRetry
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The app's own times of day: the morning briefing (07:00 on a school day, 08:30 on any
 * other, D-74), the Sunday check-in's reminder, and the weekly review after it.
 */
object Daily {
    const val ACTION_BRIEFING = "com.thomaswcode.decrastination.action.BRIEFING"
    const val ACTION_CHECK_IN = "com.thomaswcode.decrastination.action.CHECK_IN"
    const val ACTION_REVIEW = "com.thomaswcode.decrastination.action.REVIEW"

    /**
     * The next morning briefing after [now]: at the school days' time on a school day by [school]'s
     * calendar, as blocking reads it (BL-02), and at the weekends' on any other, a weekday off
     * included (D-74).
     */
    fun nextBriefing(now: Long, zone: ZoneId, settings: Settings, school: SchoolCalendar): Long {
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        while (true) {
            val minute = if (SchoolDays.kindOf(day, school) == DayKind.Weekday) settings.briefingWeekdayMin else settings.briefingWeekendMin
            val at = day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
            if (at > now) return at
            day = day.plusDays(1)
        }
    }

    /** The next Sunday at [minuteOfDay] after [now]; past midnight (a late check-in's review), on the Monday after. */
    fun nextSunday(now: Long, zone: ZoneId, minuteOfDay: Int): Long {
        // From the Sunday a time past midnight belongs to, so an alarm due tonight isn't moved a week.
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays((minuteOfDay / (24 * 60)).toLong())
        while (true) {
            if (day.dayOfWeek == DayOfWeek.SUNDAY) {
                val at = day.atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            day = day.plusDays(1)
        }
    }

    /** The latest Sunday check-in at or before [now]: still Sunday's, for a review that runs after midnight. */
    fun lastCheckIn(now: Long, zone: ZoneId, minuteOfDay: Int): Long {
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        while (true) {
            if (day.dayOfWeek == DayOfWeek.SUNDAY) {
                val at = day.atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()
                if (at <= now) return at
            }
            day = day.minusDays(1)
        }
    }

    /** The check-in's time on [now]'s day. */
    fun checkInOn(now: Long, zone: ZoneId, minuteOfDay: Int): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()

    /** The review runs this long after the check-in's reminder, with or without the answers. */
    const val REVIEW_AFTER_MIN = 90

    /** Tries after a failed run, [RETRY_MS] apart, before it's left to its next occurrence. */
    const val RETRIES = 3
    private const val RETRY_MS = 10 * 60_000L

    /** [retries] once [action]'s run failed at [now]: tried again in [RETRY_MS], up to [RETRIES] times. */
    fun failed(retries: Map<String, DailyRetry>, action: String, now: Long): Map<String, DailyRetry> {
        val tries = (retries[action]?.tries ?: 0) + 1
        return if (tries > RETRIES) retries - action else retries + (action to DailyRetry(now + RETRY_MS, tries))
    }

    /**
     * Where to look for an alarm's next time from: a little before [now], so an alarm Android is
     * still to deliver (inexact ones get ten minutes) keeps its time; after it last ran ([ranAt]);
     * and, if that was today, after today: one that ran today stands for today, even with its
     * time moved later since.
     */
    fun from(ranAt: Long?, now: Long, zone: ZoneId): Long {
        if (ranAt == null) return now - LATE_MS
        val endOfItsDay = Instant.ofEpochMilli(ranAt).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return maxOf(now - LATE_MS, ranAt, if (endOfItsDay >= now) endOfItsDay else Long.MIN_VALUE)
    }

    /** How late an inexact alarm can come: its window, and a little more. */
    private const val LATE_MS = 15 * 60_000L

    /**
     * The week a check-in made at [now] is about: that of the nearer Sunday check-in, before or
     * after. Answers given after midnight (a late check-in) or on Monday belong to the week that
     * ended; those given on Saturday, to the one ending.
     */
    fun checkInWeek(now: Long, zone: ZoneId, minuteOfDay: Int): String {
        val last = lastCheckIn(now, zone, minuteOfDay)
        val next = nextSunday(now, zone, minuteOfDay)
        return weekOf(if (now - last <= next - now) last else next, zone)
    }

    /** "Monday 5 October", the week a check-in is about. */
    fun weekOf(now: Long, zone: ZoneId): String {
        val date: ZonedDateTime = Instant.ofEpochMilli(now).atZone(zone)
        return date.toLocalDate().with(DayOfWeek.MONDAY).toString()
    }
}
