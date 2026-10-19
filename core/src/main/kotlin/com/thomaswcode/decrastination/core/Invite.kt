package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A calendar invite an email carries (DX-02): what it's called, when it starts and ends, and where,
 * from its `text/calendar` part ([com.thomaswcode.decrastination.sources.gmail.Mime.invite]). Its
 * start is the email's time, exact: the task is that event, unless Claude read work to do by a
 * date the email gives ([enriched]). [cancelled]: the message calls it off, so the conversation has
 * none; never stored.
 */
data class Invite(val summary: String, val start: Long, val end: Long, val location: String? = null, val cancelled: Boolean = false, val days: Pair<LocalDate, LocalDate>? = null) {
    /**
     * As its task keeps it ([EXTRA]): `summary|start|end|location|days`, a bar or a line break in the
     * text made a slash or a space; [days] as `first/after`, empty for a timed one.
     */
    val stored: String get() = listOf(summary.plain(), start.toString(), end.toString(), location.orEmpty().plain(), days?.let { "${it.first}/${it.second}" }.orEmpty()).joinToString("|")

    /**
     * A day's event, or days': one the invite gave by [days] (its own dates, whatever zone the phone
     * is in now), or from midnight to a midnight in [zone].
     */
    fun allDay(zone: ZoneId): Boolean {
        if (days != null) return true
        val from = Instant.ofEpochMilli(start).atZone(zone)
        val to = Instant.ofEpochMilli(end).atZone(zone)
        return from.toLocalTime() == LocalTime.MIDNIGHT && to.toLocalTime() == LocalTime.MIDNIGHT && to.toLocalDate() > from.toLocalDate()
    }

    /** Its first day and the day after its last, as all-day: its own [days], or its times' in [zone]; null for a timed one. */
    fun dayRange(zone: ZoneId): Pair<LocalDate, LocalDate>? =
        days ?: if (allDay(zone)) Instant.ofEpochMilli(start).atZone(zone).toLocalDate() to Instant.ofEpochMilli(end).atZone(zone).toLocalDate() else null

    /**
     * Its start and end as the calendar app is to be given them (DX-02): an all-day one's at UTC
     * midnight of its first and last-plus-one days, as Android's calendar wants all-day times;
     * another's as they are.
     */
    fun calendarTimes(zone: ZoneId): Pair<Long, Long> {
        val (first, after) = dayRange(zone) ?: return start to end
        fun utc(day: LocalDate) = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return utc(first) to utc(after)
    }

    companion object {
        /** Where an email's task keeps its invite. */
        const val EXTRA = "invite"

        /** An invite as [stored], or null where it isn't one. */
        fun read(stored: String): Invite? {
            val parts = stored.split('|')
            if (parts.size != 4 && parts.size != 5) return null
            val start = parts[1].toLongOrNull() ?: return null
            val end = parts[2].toLongOrNull() ?: return null
            // Its days, kept since #41 (none in one kept before, nor in a timed one).
            val days = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.split('/')?.takeIf { it.size == 2 }?.let { (a, b) -> runCatching { LocalDate.parse(a) to LocalDate.parse(b) }.getOrNull() }
            return Invite(parts[0], start, maxOf(start, end), parts[3].ifEmpty { null }, days = days)
        }

        private fun String.plain() = replace('|', '/').replace('\r', ' ').replace('\n', ' ')
    }
}

/** The invite this email carries (DX-02), where it carries one. */
val TaskItem.invite: Invite? get() = if (source == Source.Gmail) extra[Invite.EXTRA]?.let(Invite::read) else null
