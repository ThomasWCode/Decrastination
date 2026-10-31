package com.thomaswcode.decrastination.notify

import com.thomaswcode.decrastination.core.MessageKind

/**
 * A ceiling on the routine notifications (LR-07): a calendar move put back, the check-in's reminder and
 * Claude's go at most [CEILING] a day and never in quiet hours; those held back go at the next morning's
 * briefing. Felix (whose rules keep him quiet) and protection aren't held; of his messages, those of a
 * kind that waits for the morning are left out of his notification in quiet hours ([waitsForMorning]). Pure.
 */
object NotifyRules {
    val CEILINGED = setOf(Channels.CALENDAR, Channels.WEEKLY, Channels.MODEL)
    const val CEILING = 4

    /** The most held at once: the oldest go past it. */
    const val MAX_HELD = 20

    /** Whether one on [channel] goes now, with [sentToday] of the ceilinged gone today. */
    fun goesNow(channel: String, sentToday: Int, quiet: Boolean): Boolean =
        channel !in CEILINGED || (!quiet && sentToday < CEILING)

    /**
     * His messages of a kind the routine channels carried before they were his (the week's review and
     * summary, a calendar event's question): in the chat at once, and in his notification once quiet
     * hours are over, the morning's briefing making it again. The morning's own message is the
     * briefing, at its time (D-74), so it doesn't wait for itself.
     */
    val MORNING_KINDS = setOf(MessageKind.Review, MessageKind.Summary, MessageKind.EventQuestion)

    /** Whether a message of his of [kind] is left out of his notification now, [quiet] being whether it's quiet hours. */
    fun waitsForMorning(kind: MessageKind, quiet: Boolean): Boolean = quiet && kind in MORNING_KINDS

    /** [counts] with one more on [date]: only today's is kept. */
    fun counted(counts: Map<String, Int>, date: String): Map<String, Int> = mapOf(date to (counts[date] ?: 0) + 1)
}
