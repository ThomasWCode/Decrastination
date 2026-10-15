package com.thomaswcode.decrastination.notify

/**
 * A ceiling on the routine notifications (LR-07): the briefing's, the calendar's questions, the
 * week's and Claude's go at most [CEILING] a day and never in quiet hours; those held back go at
 * the next morning's briefing. Felix (whose rules keep him quiet) and protection aren't held. Pure.
 */
object NotifyRules {
    val CEILINGED = setOf(Channels.BRIEFING, Channels.CALENDAR, Channels.WEEKLY, Channels.MODEL)
    const val CEILING = 4

    /** The most held at once: the oldest go past it. */
    const val MAX_HELD = 20

    /**
     * Whether one on [channel] goes now, with [sentToday] of the ceilinged gone today. The briefing
     * isn't held by quiet hours: its time is yours, and held, it would wait for itself.
     */
    fun goesNow(channel: String, sentToday: Int, quiet: Boolean): Boolean =
        channel !in CEILINGED || ((!quiet || channel == Channels.BRIEFING) && sentToday < CEILING)

    /** [counts] with one more on [date]: only today's is kept. */
    fun counted(counts: Map<String, Int>, date: String): Map<String, Int> = mapOf(date to (counts[date] ?: 0) + 1)
}
