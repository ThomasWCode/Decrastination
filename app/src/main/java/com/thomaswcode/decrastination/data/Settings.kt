package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/**
 * Everything you can change about how the app plans and blocks. Kept in `settings.json`. Times of
 * day are minutes after midnight, local time.
 */
@Serializable
data class Settings(
    /** Which textbook's decks a German section number like "1.2" means (Q18): `Textbook 1::1.2`. */
    val ankiTextbook: Int = 1,
    /** When the daily Anki quota is due (21:30, decided 8 Oct). */
    val ankiDeadlineMin: Int = 21 * 60 + 30,

    /** When you can work on a school day (Q6): 16:45 to 22:00, the commute already inside. */
    val weekdayHours: Window = Window(16 * 60 + 45, 22 * 60),
    /** And at the weekend: 08:30 to 22:30. */
    val weekendHours: Window = Window(8 * 60 + 30, 22 * 60 + 30),
    /** How long a piece of work is cut into, when it has no steps of its own (docs/scheduler.md §3). */
    val boxMin: Int = 45,
    /** Days before a deadline its work should be finished by, until calibration learns better. */
    val marginDays: Int = 1,
    /** Work with no deadline (an email, an undated task) is due this many days after it's first seen. */
    val softDeadlineDays: Int = 7,
    /**
     * At most this much undated work is planned on one day, so a batch first seen together (the
     * whole inbox, the day the app arrived) spreads over the week instead of filling one evening.
     */
    val softMinPerDay: Int = 60,
)

/** A stretch of a day, in minutes after midnight. */
@Serializable
data class Window(val startMin: Int, val endMin: Int) {
    val minutes: Int get() = (endMin - startMin).coerceAtLeast(0)
}
