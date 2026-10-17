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
    /** The September you went into Year 12 (AI-06): the year group the model's told goes by it. */
    val yearGroupStart: Int = 2026,
    /** What you'd have the model know about you (AI-05), a line each. */
    val profileLines: List<String> = emptyList(),
    /** Where the weekly summary goes (NF-02), set in Settings → Notifications: none, and the email opens to choose. */
    val summaryEmail: String = "",
    /** Your classes' codes, as the sources give them, to the subject each is (AI-05). */
    val classMap: Map<String, String> = emptyMap(),
    /** The voice of the app's messages (AI-22): his name, and the line that says who he is. */
    val felixName: String = "Felix",
    val felixLine: String = "a strict friend who knows your week and wants the work done",
    /** How far into a slot with nothing done he asks (LR-03), in minutes. */
    val felixGraceMin: Int = 10,
    /** When he checks again if he doesn't say (Q-31), in minutes. */
    val felixDefaultCheckMin: Int = 20,
    /** Whether he may search the web from the chat when a message needs it (your call, 16 Oct): a search costs about 5¢. */
    val felixSearches: Boolean = true,
    /** The screens' first-run hints you've put away (UI-17), by name. */
    val hintsSeen: List<String> = emptyList(),
    /** When the daily Anki quota is due (21:30, decided 8 Oct). */
    val ankiDeadlineMin: Int = 21 * 60 + 30,

    /** When you can work on a school day (Q6): 16:45 to 22:00, the commute already inside. */
    val weekdayHours: Window = Window(16 * 60 + 45, 22 * 60),
    /** And at the weekend: 08:30 to 22:30. */
    val weekendHours: Window = Window(8 * 60 + 30, 22 * 60 + 30),
    /** The timetable's name for a free period (PL-15), compared trimmed, whatever its case. */
    val studyPeriodName: String = "Study Period",
    /** School's morning break: work can go in it (PL-15). */
    val breakWindow: Window = Window(10 * 60 + 45, 11 * 60 + 15),
    /** Lunch, either side of eating (13:40 to 13:55): work can go in both parts. */
    val lunchWindows: List<Window> = listOf(Window(13 * 60 + 15, 13 * 60 + 40), Window(13 * 60 + 55, 14 * 60 + 35)),
    /** A school window with less free than this left isn't used. */
    val minSchoolSlotMin: Int = 15,
    /** How long a piece of work is cut into, when it has no steps of its own (docs/spec/planner.md): 30 since PL-12. */
    val boxMin: Int = 30,
    /** The break after each piece's slot (PL-12), and the longer one once this much work has gone without one. */
    val slotBreakMin: Int = 5,
    val slotLongBreakMin: Int = 15,
    val slotLongBreakAfterMin: Int = 90,
    /** Breaks outside on a weekend or a day off (PL-12): how many, how long, and the times each is laid at or after. */
    val outsideBreaks: Int = 3,
    val outsideBreakMin: Int = 20,
    val outsideTimes: List<Int> = listOf(9 * 60 + 30, 13 * 60, 16 * 60),
    /**
     * A piece this long or longer earns its free time when a photo shows it done, not on its tick
     * (BL-08): a short one's tick is taken at its word.
     */
    val photoFromMin: Int = 20,
    /** The free time a day with nothing to do starts with (BL-07). */
    val freeAllowanceMin: Int = 60,
    /** The calendar the plan's slots are written to (NF-07), by its id on the phone: none till you pick one. */
    val calendarWriteId: Long? = null,
    /** Calendars not read for busy time (DX-13), by id: the one written to never is. */
    val calendarsExcluded: List<Long> = emptyList(),
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
    /** When blocking starts on a weekend or a day off school (BL-02). */
    val weekendBlockFromMin: Int = 10 * 60,
    /** Minutes of work that earn one minute of free time (Q22: about 1 for 3). */
    val workMinPerFreeMin: Int = 3,

    /** Automatic Teams syncs (Q21): on the first unlock after [teamsFirstUnlockMin], and every so often. */
    val teamsAutoSync: Boolean = true,
    val teamsFirstUnlockMin: Int = 16 * 60 + 45,
    val teamsSyncEveryMin: Int = 180,

    /**
     * Claude for email triage, assignment steps and estimates (docs/spec/readings.md): built, but
     * off until you switch it on and give it an API key. Without it, the rules do what they can.
     */
    val aiEnabled: Boolean = false,
    /**
     * Whether the saved API key may be used: set when a key is saved, and, once armed, after the
     * same wait as switching Claude on, so a key can't switch on triage that's already on.
     */
    val aiKeyActive: Boolean = false,
    /**
     * The most the model may cost in a calendar month, in US dollars as the API bills; none by
     * default, as the account is prepaid with no card: its calls stop when its credit runs out, and
     * that's alerted ([com.thomaswcode.decrastination.enrich.KeyProblem.NoCredit]). An install from
     * before 1.2.0 had a cap of £200 (`aiMonthlyCapGbp`, no longer read); dropping it with the
     * upgrade was the owner's decision (9 Oct), made by installing it, so it isn't a change through
     * Settings that waits.
     */
    val aiMonthlyCapUsd: Int? = null,

    /** The morning briefing (Q13): 07:00 on school days, 08:30 at weekends. */
    val briefingWeekdayMin: Int = 7 * 60,
    val briefingWeekendMin: Int = 8 * 60 + 30,
    /** The Sunday check-in's reminder; the week's review follows it. */
    val checkInMin: Int = 19 * 60 + 30,

    /** Anti-tamper (docs/spec/blocking-and-protection.md) is built but off until you arm it (Q20). */
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
