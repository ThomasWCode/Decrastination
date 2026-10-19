package com.thomaswcode.decrastination.core

import java.time.LocalDate

/** What a stretch of a day's planning time is (PL-15): at home in the evening, or at school. */
enum class WindowKind {
    Evening,
    Study,
    Break,
    Lunch,
    ;

    /** At school: only work that can be done there goes in it ([Where.Anywhere]). */
    val atSchool: Boolean get() = this != Evening
}

/** A stretch of a day that work can be planned in, in minutes after midnight. */
data class DayWindow(val startMin: Int, val endMin: Int, val kind: WindowKind) {
    val minutes: Int get() = endMin - startMin
}

/** Where a piece of work can be done (PL-15): anywhere, a free period at school included, or only at home. */
enum class Where {
    Anywhere,
    Home,
    ;

    companion object {
        /** As a step or a task keeps it ("home", "anywhere"), or null when it doesn't say. */
        fun of(text: String?): Where? = when (text?.trim()?.lowercase()) {
            "home" -> Home
            "anywhere" -> Anywhere
            else -> null
        }
    }
}

/**
 * A day's planning time as windows (PL-15): on a school day its study periods (the timetable's
 * lessons named [Settings.studyPeriodName]), break, the two parts of lunch, and the evening; on a
 * weekend or a day with no school ([SchoolCalendar]), the weekend's hours. Pure.
 */
object SchoolDays {

    /** A weekend for Saturdays, Sundays and days with no school; else a weekday. */
    fun kindOf(date: LocalDate, school: SchoolCalendar): DayKind = if (school.isSchoolDay(date)) DayKind.Weekday else DayKind.Weekend

    fun windows(date: LocalDate, settings: Settings, timetable: Timetable?, school: SchoolCalendar): List<DayWindow> {
        if (kindOf(date, school) == DayKind.Weekend) return listOfNotNull(window(settings.weekendHours, WindowKind.Evening))
        val name = settings.studyPeriodName.trim()
        val study = timetable?.let { PowerPlannerItems.lessonsOn(it, date) }.orEmpty()
            .filter { it.className.trim().equals(name, ignoreCase = true) }
            .map { DayWindow(it.start.hour * 60 + it.start.minute, it.end.hour * 60 + it.end.minute, WindowKind.Study) }
        val atSchool = study + listOfNotNull(window(settings.breakWindow, WindowKind.Break)) + settings.lunchWindows.mapNotNull { window(it, WindowKind.Lunch) }
        // A half-day: school's windows end when it does, and the evening starts then.
        val half = school.halfDay(date)
        val schoolWindows = if (half == null) atSchool else atSchool.mapNotNull { w -> w.takeIf { it.startMin < half.endsAtMin }?.copy(endMin = minOf(w.endMin, half.endsAtMin)) }
        val evening = if (half == null) window(settings.weekdayHours, WindowKind.Evening) else window(Window(half.endsAtMin, settings.weekdayHours.endMin), WindowKind.Evening)
        return merged(schoolWindows + listOfNotNull(evening))
    }

    /** [w] as a window of [kind], or none where it ends before it starts. */
    private fun window(w: Window, kind: WindowKind): DayWindow? = DayWindow(w.startMin, w.endMin, kind).takeIf { it.endMin > it.startMin }

    /** By start, overlaps merged: where two overlap, the earlier's kind holds the time they share. */
    private fun merged(windows: List<DayWindow>): List<DayWindow> {
        val result = mutableListOf<DayWindow>()
        for (w in windows.sortedWith(compareBy({ it.startMin }, { it.endMin }))) {
            val last = result.lastOrNull()
            when {
                last == null || w.startMin >= last.endMin -> result += w
                w.endMin <= last.endMin -> Unit
                // The later one's own time after the earlier ends.
                else -> result += w.copy(startMin = last.endMin)
            }
        }
        return result.filter { it.endMin > it.startMin }
    }
}
