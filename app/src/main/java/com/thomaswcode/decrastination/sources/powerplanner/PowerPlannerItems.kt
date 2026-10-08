package com.thomaswcode.decrastination.sources.powerplanner

import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Kind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Power Planner's agenda items as tasks, pure (docs/data-sources.md §2, docs/phase0-findings.md §2).
 *
 * An item's `Date` is local time, and its seconds carry the time option (the web app's
 * `viewItems.ts`, `timeOption`). A task with a class: `:00` start of class, `:01` before class,
 * `:02` during class, `:03` end of class, `:04` a set time, anything else all day. A task with no
 * class, whose `ClassIdentifier` is the semester's id: `:04` a set time, else all day. Class times
 * come from the class's slot that day in the two-week timetable, counted from `WeekOneStartsOn`.
 * An event (an exam, or "Call with Jags") is all day when its `EndTime` ends in `:59`.
 */
object PowerPlannerItems {
    const val TASK = 5
    const val EVENT = 6

    /** Power Planner's "no due date" (`DateValues.NO_DUE_DATE`). */
    private val NO_DUE_DATE = LocalDate.of(1999, 12, 31)

    const val EXTRA_CLASS_ID = "classId"
    const val EXTRA_ALL_DAY = "allDay"
    const val EXTRA_END_AT = "endAt"

    fun fetched(item: PpItem, semesterId: String, timetable: Timetable, zone: ZoneId): Fetched? {
        if (item.itemType != TASK && item.itemType != EVENT) return null
        val hasClass = item.classIdentifier != null && item.classIdentifier != semesterId
        val klass = timetable.classes.firstOrNull { it.identifier == item.classIdentifier }?.takeIf { hasClass }
        val date = item.date?.let(::parse)
        val due = date?.takeUnless { it.toLocalDate() <= NO_DUE_DATE }?.let { due(item, it, klass, timetable) }
        val isEvent = item.itemType == EVENT
        return Fetched(
            sourceId = item.identifier,
            title = item.name.ifBlank { "Untitled" },
            kind = when {
                isEvent -> Kind.Event
                hasClass -> Kind.Homework
                else -> Kind.Admin
            },
            detail = item.details.orEmpty(),
            className = klass?.name,
            dueAt = due?.time?.atZone(zone)?.toInstant()?.toEpochMilli(),
            sourceProgress = item.percentComplete.coerceIn(0.0, 1.0),
            done = item.percentComplete >= 1.0,
            extra = buildMap {
                if (hasClass) item.classIdentifier?.let { put(EXTRA_CLASS_ID, it) }
                if (due?.allDay == true) put(EXTRA_ALL_DAY, "true")
                due?.endsAt?.let { put(EXTRA_END_AT, it.atZone(zone).toInstant().toEpochMilli().toString()) }
            },
        )
    }

    data class Due(val time: LocalDateTime, val allDay: Boolean = false, val endsAt: LocalDateTime? = null)

    private fun due(item: PpItem, date: LocalDateTime, klass: PpClass?, timetable: Timetable): Due {
        val day = date.toLocalDate()
        val atSetTime = date.withSecond(0).withNano(0)
        // All-day tasks are due by the end of their day; an all-day event fills it.
        val allDayTask = Due(day.atTime(LocalTime.of(23, 59)), allDay = true)
        if (item.itemType == EVENT) {
            val end = item.endTime?.let(::parse)
            return when {
                end == null || end.year <= 1970 -> slot(klass, day, timetable)?.let { Due(day.atTime(it.first), endsAt = day.atTime(it.second)) }
                    ?: Due(day.atStartOfDay(), allDay = true)
                end.second == 59 -> Due(day.atStartOfDay(), allDay = true)
                else -> Due(atSetTime, endsAt = end.withSecond(0).withNano(0))
            }
        }
        val option = date.second
        if (klass == null) return if (option == 4) Due(atSetTime) else allDayTask
        if (option == 4) return Due(atSetTime)
        val slot = slot(klass, day, timetable) ?: return allDayTask
        return when (option) {
            0, 1, 2 -> Due(day.atTime(slot.first))
            3 -> Due(day.atTime(slot.second))
            else -> allDayTask
        }
    }

    /** The class's first slot on [day] in the two-week timetable, as (start, end), or null if it has none. */
    fun slot(klass: PpClass?, day: LocalDate, timetable: Timetable): Pair<LocalTime, LocalTime>? {
        val schedules = klass?.schedules ?: return null
        val week = scheduleWeek(day, timetable.weekOneStartsOn?.let(::parse)?.toLocalDate())
        return schedules
            .filter { dotNetDay(it.dayOfWeek) == day.dayOfWeek && (it.scheduleWeek == BOTH_WEEKS || week == null || it.scheduleWeek == week) }
            .map { parse(it.startTime).toLocalTime() to parse(it.endTime).toLocalTime() }
            .minByOrNull { it.first }
    }

    /** 1 or 2: weeks alternate from the week holding [weekOne]. Null when there's no rotation. */
    fun scheduleWeek(day: LocalDate, weekOne: LocalDate?): Int? {
        weekOne ?: return null
        val weeks = Math.floorDiv(ChronoUnit.DAYS.between(weekOne, day), 7L)
        return if (Math.floorMod(weeks, 2L) == 0L) 1 else 2
    }

    private const val BOTH_WEEKS = 3

    /** .NET counts Sunday as 0. */
    private fun dotNetDay(value: Int): DayOfWeek = if (value == 0) DayOfWeek.SUNDAY else DayOfWeek.of(value.coerceIn(1, 6))

    /** Power Planner's local times, with or without fractions of a second: "2026-09-23T09:46:46.98". */
    fun parse(text: String): LocalDateTime = LocalDateTime.parse(text.removeSuffix("Z"))
}
