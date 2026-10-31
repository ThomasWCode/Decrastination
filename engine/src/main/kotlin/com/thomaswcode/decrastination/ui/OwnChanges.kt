package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A change you can make to a task yourself, with no Claude (IMP-P2-001), from
 * its sheet ([TaskSheet]). Kept as a quick change (an instruction already read) and applied at once,
 * armed or not: the work is yours to change (D-46).
 */
enum class OwnChange {
    NotATask,
    StartFrom,
    DueOn,
    Minutes,
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

/** The time of day [task]'s due date is picked at to begin with: the time it's due now (a lesson's 08:30), or 23:59 for one with none. */
fun dueTimeOf(task: TaskItem, zone: ZoneId): LocalTime =
    task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(23, 59)

/**
 * [what] on [day], as the change it makes and its words: a start at the day's beginning; a due
 * date at the time of day it's due now ([dueTimeOf]).
 */
fun ownChange(task: TaskItem, what: OwnChange, day: LocalDate, zone: ZoneId): Pair<Change, String> = when (what) {
    OwnChange.NotATask -> Change(ChangeType.NotATask, taskId = task.id) to "Not a task (set in Tasks)"
    OwnChange.StartFrom -> Change(ChangeType.StartFrom, taskId = task.id, time = day.atStartOfDay(zone).toInstant().toEpochMilli()) to
        "Can't be started before ${day.format(DAY)} (set in Tasks)"
    OwnChange.DueOn -> dueChange(task, day.atTime(dueTimeOf(task, zone)), zone)
    OwnChange.Minutes -> minutesChange(task, task.effortMin)
}

/** Due at [at], where you are, as the change it makes and its words. */
fun dueChange(task: TaskItem, at: LocalDateTime, zone: ZoneId): Pair<Change, String> {
    val due = at.atZone(zone)
    return Change(ChangeType.DueBy, taskId = task.id, time = due.toInstant().toEpochMilli()) to "Due ${due.format(DAY_TIME)} (set in Tasks)"
}

/** The whole of [task] taking [minutes], as the change it makes and its words. */
fun minutesChange(task: TaskItem, minutes: Int): Pair<Change, String> =
    Change(ChangeType.Minutes, taskId = task.id, minutes = minutes) to "Takes ${Format.minutes(minutes)} in all (set in Tasks)"
