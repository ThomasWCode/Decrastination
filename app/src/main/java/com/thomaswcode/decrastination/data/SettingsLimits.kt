package com.thomaswcode.decrastination.data

/**
 * What each setting may be: the Settings screen takes nothing else, and a backup with anything
 * else isn't restored ([problems]), so a damaged or edited file can't put the planner somewhere the
 * screen never could.
 */
object SettingsLimits {
    val CAP_USD = 1..10_000
    val TEAMS_EVERY_MIN = 30..24 * 60
    val BOX_MIN = 10..240
    val MARGIN_DAYS = 0..7
    val SOFT_DEADLINE_DAYS = 1..60
    val SOFT_MIN_PER_DAY = 10..600
    val WORK_MIN_PER_FREE_MIN = 1..20
    val TEXTBOOK = 1..9

    /** How long a loosening change may wait: the screen doesn't set it, so it's held to what makes sense. */
    val LOOSEN_DELAY_HOURS = 1..24 * 14

    private val MINUTE_OF_DAY = 0 until 24 * 60

    /** Working hours end the day they start; quiet hours may cross midnight ([overnight]). */
    fun window(window: Window, overnight: Boolean): Boolean =
        window.startMin in MINUTE_OF_DAY && window.endMin in MINUTE_OF_DAY && window.startMin != window.endMin && (overnight || window.endMin > window.startMin)

    /** What's out of range in [settings], in words; empty if nothing is. */
    fun problems(settings: Settings): List<String> = buildList {
        fun check(name: String, ok: Boolean) {
            if (!ok) add(name)
        }
        with(settings) {
            check("the Anki textbook", ankiTextbook in TEXTBOOK)
            check("the Anki deadline", ankiDeadlineMin in MINUTE_OF_DAY)
            check("the school-day hours", window(weekdayHours, overnight = false))
            check("the weekend hours", window(weekendHours, overnight = false))
            check("the box length", boxMin in BOX_MIN)
            check("the safety margin", marginDays in MARGIN_DAYS)
            check("the days given to undated work", softDeadlineDays in SOFT_DEADLINE_DAYS)
            check("the undated work a day", softMinPerDay in SOFT_MIN_PER_DAY)
            check("the quiet hours", window(quietHours, overnight = true))
            check("when blocking starts on school days", weekdayBlockFromMin in MINUTE_OF_DAY)
            check("the minutes of work for one of free time", workMinPerFreeMin in WORK_MIN_PER_FREE_MIN)
            check("the first-unlock Teams sync time", teamsFirstUnlockMin in MINUTE_OF_DAY)
            check("how often Teams syncs", teamsSyncEveryMin in TEAMS_EVERY_MIN)
            check("the monthly cap", aiMonthlyCapUsd == null || aiMonthlyCapUsd in CAP_USD)
            check("the briefing's times", briefingWeekdayMin in MINUTE_OF_DAY && briefingWeekendMin in MINUTE_OF_DAY)
            check("the check-in's time", checkInMin in MINUTE_OF_DAY)
            check("the wait for loosening changes", loosenDelayHours in LOOSEN_DELAY_HOURS)
        }
    }
}
