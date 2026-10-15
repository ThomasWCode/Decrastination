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
    val MIN_SCHOOL_SLOT_MIN = 5..60
    val SLOT_BREAK_MIN = 0..60
    val SLOT_LONG_BREAK_AFTER_MIN = 30..240
    val OUTSIDE_BREAKS = 0..3
    val OUTSIDE_BREAK_MIN = 5..120
    const val MAX_CALENDARS_EXCLUDED = 100
    val PHOTO_FROM_MIN = 5..240
    val YEAR_GROUP_START = 2000..2100
    const val MAX_PROFILE_LINES = 20
    const val MAX_PROFILE_LINE = 200
    const val MAX_CLASSES = 40
    val FREE_ALLOWANCE_MIN = 0..240
    const val MAX_FELIX_LINE = 200
    val FELIX_GRACE_MIN = 0..60
    val FELIX_CHECK_MIN = 5..240
    const val MAX_NAME = 60
    const val MAX_LUNCH_WINDOWS = 4

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
            check("the timetable's name for a free period", studyPeriodName.isNotBlank() && studyPeriodName.length <= MAX_NAME)
            check("the morning break", window(breakWindow, overnight = false))
            check("lunch", lunchWindows.size <= MAX_LUNCH_WINDOWS && lunchWindows.all { window(it, overnight = false) })
            check("the shortest school time used", minSchoolSlotMin in MIN_SCHOOL_SLOT_MIN)
            check("the breaks between pieces", slotBreakMin in SLOT_BREAK_MIN && slotLongBreakMin in SLOT_BREAK_MIN && slotLongBreakAfterMin in SLOT_LONG_BREAK_AFTER_MIN)
            check("the breaks outside", outsideBreaks in OUTSIDE_BREAKS && outsideBreakMin in OUTSIDE_BREAK_MIN && outsideTimes.size == 3 && outsideTimes.all { it in MINUTE_OF_DAY })
            check("the length a piece earns with a photo from", photoFromMin in PHOTO_FROM_MIN)
            check("the year you went into Year 12", yearGroupStart in YEAR_GROUP_START)
            check("what you'd have Claude know", profileLines.size <= MAX_PROFILE_LINES && profileLines.all { it.length <= MAX_PROFILE_LINE })
            check("your classes", classMap.size <= MAX_CLASSES && classMap.all { (code, subject) -> code.length <= MAX_NAME && subject.length <= MAX_NAME })
            check("Felix's name and line", felixName.isNotBlank() && felixName.length <= MAX_NAME && felixLine.isNotBlank() && felixLine.length <= MAX_FELIX_LINE)
            check("when Felix asks and checks again", felixGraceMin in FELIX_GRACE_MIN && felixDefaultCheckMin in FELIX_CHECK_MIN)
            check("an empty day's free time", freeAllowanceMin in FREE_ALLOWANCE_MIN)
            check("the calendars", (calendarWriteId == null || calendarWriteId > 0) && calendarsExcluded.size <= MAX_CALENDARS_EXCLUDED && calendarsExcluded.all { it > 0 })
            check("the box length", boxMin in BOX_MIN)
            check("the safety margin", marginDays in MARGIN_DAYS)
            check("the days given to undated work", softDeadlineDays in SOFT_DEADLINE_DAYS)
            check("the undated work a day", softMinPerDay in SOFT_MIN_PER_DAY)
            check("the quiet hours", window(quietHours, overnight = true))
            check("when blocking starts on school days", weekdayBlockFromMin in MINUTE_OF_DAY)
            check("when blocking starts on other days", weekendBlockFromMin in MINUTE_OF_DAY)
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
