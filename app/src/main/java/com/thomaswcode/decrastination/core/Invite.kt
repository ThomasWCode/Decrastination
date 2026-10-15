package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A calendar invite an email carries (DX-02): what it's called, when it starts and ends, and where,
 * from its `text/calendar` part ([com.thomaswcode.decrastination.sources.gmail.Mime.invite]). Its
 * start is the email's time, exact: the task is that event, unless Claude read work to do by a
 * date the email gives ([enriched]). [cancelled]: the message calls it off, so the conversation has
 * none; never stored.
 */
data class Invite(val summary: String, val start: Long, val end: Long, val location: String? = null, val cancelled: Boolean = false) {
    /** As its task keeps it ([EXTRA]): `summary|start|end|location`, a bar or a line break in the text made a slash or a space. */
    val stored: String get() = listOf(summary.plain(), start.toString(), end.toString(), location.orEmpty().plain()).joinToString("|")

    /** From midnight to a midnight in [zone]: a day's event, or days'. */
    fun allDay(zone: ZoneId): Boolean {
        val from = Instant.ofEpochMilli(start).atZone(zone)
        val to = Instant.ofEpochMilli(end).atZone(zone)
        return from.toLocalTime() == LocalTime.MIDNIGHT && to.toLocalTime() == LocalTime.MIDNIGHT && to.toLocalDate() > from.toLocalDate()
    }

    companion object {
        /** Where an email's task keeps its invite. */
        const val EXTRA = "invite"

        /** An invite as [stored], or null where it isn't one. */
        fun read(stored: String): Invite? {
            val parts = stored.split('|')
            if (parts.size != 4) return null
            val start = parts[1].toLongOrNull() ?: return null
            val end = parts[2].toLongOrNull() ?: return null
            return Invite(parts[0], start, maxOf(start, end), parts[3].ifEmpty { null })
        }

        private fun String.plain() = replace('|', '/').replace('\r', ' ').replace('\n', ' ')
    }
}

/** The invite this email carries (DX-02), where it carries one. */
val TaskItem.invite: Invite? get() = if (source == Source.Gmail) extra[Invite.EXTRA]?.let(Invite::read) else null
