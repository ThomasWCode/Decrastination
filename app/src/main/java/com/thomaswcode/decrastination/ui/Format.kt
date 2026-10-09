package com.thomaswcode.decrastination.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How times read on screen. Pure, for the tests. */
object Format {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
    private val DATE = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /** "today 23:59", "tomorrow 08:30", "Fri 09:00" within a week, else "Mon 12 Oct 11:00". */
    fun at(time: Long, now: Long, zone: ZoneId): String {
        val then = Instant.ofEpochMilli(time).atZone(zone)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), then.toLocalDate())
        return when (days) {
            0L -> "today ${then.format(TIME)}"
            1L -> "tomorrow ${then.format(TIME)}"
            -1L -> "yesterday ${then.format(TIME)}"
            in 2L..6L -> then.format(WEEKDAY)
            else -> then.format(DATE)
        }
    }

    /** "Due today 23:59", or "Overdue: due yesterday 21:00". */
    fun due(dueAt: Long?, now: Long, zone: ZoneId): String = when {
        dueAt == null -> "No deadline"
        dueAt < now -> "Overdue: due ${at(dueAt, now, zone)}"
        else -> "Due ${at(dueAt, now, zone)}"
    }

    /** "45 min", "1 h 30 min". */
    fun minutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** A day's load: "1 h planned, 4 h 15 min free", or "7 h planned, 1 h 45 min over". */
    fun load(plannedMin: Int, capacityMin: Int): String {
        val left = capacityMin - plannedMin
        return "${minutes(plannedMin)} planned, " + if (left >= 0) "${minutes(left)} free" else "${minutes(-left)} over"
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(time: Long, now: Long): String {
        val elapsed = Duration.ofMillis((now - time).coerceAtLeast(0))
        return when {
            elapsed.toMinutes() < 1 -> "just now"
            elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()} min ago"
            elapsed.toHours() < 48 -> "${elapsed.toHours()} h ago"
            else -> "${elapsed.toDays()} days ago"
        }
    }
}
