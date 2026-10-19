package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Timetable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a setting does, in one sentence worked out from it (UI-13), shown under its field: what the
 * number means for the plan. Pure.
 */
object SettingsEffects {
    private val COUNT = listOf("No", "One", "Two", "Three")

    private fun day(day: DayOfWeek) = day.getDisplayName(TextStyle.FULL, Locale.UK)

    /** [field]'s sentence as [settings] have it, the week from [today] for the school days'; null for a field with none. */
    fun line(field: String, settings: Settings, timetable: Timetable?, school: SchoolCalendar, today: LocalDate): String? = when (field) {
        "weekdayHours" -> {
            val evening = settings.weekdayHours.minutes
            // The school day of the week ahead with most time in all: break, lunch and study periods besides.
            val most = (0L until 7L).map { today.plusDays(it) }.filter { SchoolDays.kindOf(it, school) == DayKind.Weekday }
                .map { it to SchoolDays.windows(it, settings, timetable, school).sumOf { w -> w.minutes } }
                .maxByOrNull { it.second }
            "${Format.minutes(evening)} of evening on a school day" +
                (most?.takeIf { it.second > evening }?.let { (date, minutes) -> "; with school's free times, about ${Format.minutes(minutes)} on a ${day(date.dayOfWeek)}" } ?: "")
        }
        "weekendHours" -> "${Format.minutes(settings.weekendHours.minutes)} on a weekend day or a day off"
        "marginDays" -> {
            // A deadline before the day's work starts leaves its day out.
            val usable = if (8 * 60 + 30 <= settings.weekdayHours.startMin) 1 else 0
            val by = DayOfWeek.THURSDAY.minus((usable + settings.marginDays).toLong())
            "Work due Thursday 08:30 is planned by ${day(by)}" + if (usable + settings.marginDays >= 7) " a week before" else ""
        }
        "boxMin" -> "Work with no steps of its own is cut into ${settings.boxMin}-minute pieces"
        "workMinPerFreeMin" -> {
            val earned = (settings.boxMin.toDouble() / settings.workMinPerFreeMin.coerceAtLeast(1)).roundToInt()
            "A ${settings.boxMin}-minute piece earns ${Format.minutes(earned)} of free time"
        }
        "photoFromMin" -> "Written work of ${settings.photoFromMin} minutes or more earns its free time with a photo; other work, once its source says it's done"
        "softMinPerDay" -> "Work with no date takes at most ${Format.minutes(settings.softMinPerDay)} of a day"
        "freeAllowanceMin" -> if (settings.freeAllowanceMin == 0) "A day with nothing to do starts with no free time" else "A day with nothing to do starts with ${Format.minutes(settings.freeAllowanceMin)} free"
        "outsideBreaks", "outsideBreakMin" -> if (settings.outsideBreaks == 0) "No outside breaks on a free day" else {
            "${COUNT[settings.outsideBreaks.coerceIn(0, 3)]} ${settings.outsideBreakMin}-minute outside break${if (settings.outsideBreaks == 1) "" else "s"} on a free Saturday"
        }
        else -> null
    }
}
