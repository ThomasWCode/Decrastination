package com.thomaswcode.decrastination.notify


/** The app's notification channels, by their ids ([AndroidChannels] makes them on the phone). */
object Channels {
    const val SYNC = "sync"
    const val PROTECTION = "protection"

    // Each its own purpose (IMP-P2-019), so a routine one can be silenced and a question kept.
    /** The morning's list, now Felix's message, on his channel: deleted once nothing's left on it. */
    const val BRIEFING = "briefing"
    /** A move of yours in the calendar put back; its questions were here before they were Felix's. */
    const val CALENDAR = "calendar-questions"
    const val WEEKLY = "weekly"
    const val MODEL = "model"
    const val FREE_TIME = "free-time"
    const val FREE_TIME_ENDING = "free-time-ending"
    /** A timer on a piece (D-112): its countdown, silent; and its "time's up", popping up to be seen on silent too. */
    const val TIMER = "timer"
    const val TIMER_OVER = "timer-up"
    /** Everything Felix says (AI-24, LR-03): his one notification, made from the chat. */
    const val FELIX = "felix"

    /** Your sources (D-106): a write to Gmail that couldn't be made; outside the day's ceiling. */
    const val SOURCES = "sources"
}
