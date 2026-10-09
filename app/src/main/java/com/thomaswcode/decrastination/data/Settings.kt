package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.Blocklist
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

    /** Apps covered by the block screen while blocking applies (Q8, decided 8 Oct). */
    val blockedApps: List<String> = Blocklist.APPS,
    /** Sites covered in the browsers whose address bar is read: a host, or a host and path. */
    val blockedSites: List<String> = Blocklist.SITES,
    /** Browsers whose address bar is read, so only the blocked sites are covered (Chrome, Brave). */
    val checkedBrowsers: List<String> = Blocklist.CHECKED_BROWSERS,
    /** Browsers covered outright while blocking applies (Firefox, Tor). */
    val blockedBrowsers: List<String> = Blocklist.BLOCKED_BROWSERS,
    /** Sleep: nothing is blocked (Q6). Crosses midnight. */
    val quietHours: Window = Window(22 * 60 + 30, 7 * 60),
    /** On school days blocking starts here (Q6b): school hours are left alone. */
    val weekdayBlockFromMin: Int = 16 * 60 + 45,
    /** Minutes of work that earn one minute of free time (Q22: about 1 for 3). */
    val workMinPerFreeMin: Int = 3,

    /** Automatic Teams syncs (Q21): on the first unlock after [teamsFirstUnlockMin], and every so often. */
    val teamsAutoSync: Boolean = true,
    val teamsFirstUnlockMin: Int = 16 * 60 + 45,
    val teamsSyncEveryMin: Int = 180,

    /**
     * Claude for email triage, assignment steps and estimates (docs/data-sources.md §5): built, but
     * off until you switch it on and give it an API key. Without it, the rules do what they can.
     */
    val aiEnabled: Boolean = false,
    /** The most the model may cost in a calendar month, in pounds. */
    val aiMonthlyCapGbp: Int = 200,
    /** Pounds to the US dollar, to turn the API's dollar prices into pounds against the cap. */
    val usdToGbp: Double = 0.79,

    /** Anti-tamper (docs/scheduler.md §6) is built but off until you arm it (Q20). */
    val armed: Boolean = false,
    /** How long a change that loosens blocking waits, once armed. */
    val loosenDelayHours: Int = 24,
)

/** A stretch of a day, in minutes after midnight; one that ends before it starts crosses midnight. */
@Serializable
data class Window(val startMin: Int, val endMin: Int) {
    val minutes: Int get() = if (endMin >= startMin) endMin - startMin else 24 * 60 - startMin + endMin

    operator fun contains(minuteOfDay: Int): Boolean =
        if (endMin >= startMin) minuteOfDay in startMin until endMin else minuteOfDay >= startMin || minuteOfDay < endMin
}
