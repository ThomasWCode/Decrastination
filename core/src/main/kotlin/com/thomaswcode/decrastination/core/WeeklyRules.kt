package com.thomaswcode.decrastination.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Work that comes round every week (D-124): "3 hours of guitar a week". A change of yours or Felix's holds it
 * ([ChangeType.Weekly]), so *Your changes* lists it and taking that back ends it; each week's work is an ordinary task of
 * your own, made from it ([WeeklyRules.roll]). [id] is the rule's, kept through a change to it; [minutes] the week's
 * whole; [days] the days named, 1 (Monday) to 7, a session on each, empty for the planner's choice; [sessionMin] how long
 * each session is, 0 for the app's boxes, unused with [days]; [where] as [OwnTask.where]; [setAt] when it was last set.
 */
@Serializable
data class WeeklyRule(
    val id: String,
    val title: String,
    val minutes: Int,
    val days: List<Int> = emptyList(),
    val sessionMin: Int = 0,
    val where: String = "anywhere",
    val subject: String? = null,
    val setAt: Long = 0,
)

/** What marks a task as a week of [rule]: [week] is its Monday, and [setAt] the rule's when the task was made from it. */
@Serializable
data class WeeklyMark(val rule: String, val week: String, val setAt: Long = 0) {
    /** The week's Sunday: no piece of it goes past that. */
    val lastDay: LocalDate get() = LocalDate.parse(week).plusDays(6)
}

/** The tasks and the weeks made, as [WeeklyRules.roll] leaves them. */
data class Rolled(val tasks: List<TaskItem>, val weeksMade: Map<String, String>)

/** Weekly work's rules (D-124), pure: what's in force, and each week's task from it. */
object WeeklyRules {
    /** A rule of at most 60 hours a week, so each week's task is one the form would take. */
    val MINUTES = 5..OwnTaskRules.MINUTES.last

    /** A session's length, as a box's ([Settings.boxMin]) can be. */
    val SESSION = 10..240

    /** More than this is a misreading, not a week. */
    const val MAX_RULES = 20

    /** A rule's name, as far as it's kept. */
    const val MAX_TITLE = 100

    /** The weeks ahead a rule's task is made for: this one and the next, as the calendar is written a week ahead (D-39). */
    private const val WEEKS = 2L

    /** The rules [applied] holds, in the order they were first set: a later change to one replaces it, and a [ChangeType.WeeklyEnd] ends it. */
    fun inForce(applied: List<Instruction>): List<WeeklyRule> {
        val rules = LinkedHashMap<String, WeeklyRule>()
        for (change in applied.flatMap { it.changes }) {
            val rule = change.weekly ?: continue
            if (change.type == ChangeType.Weekly) rules[rule.id] = rule
            if (change.type == ChangeType.WeeklyEnd) rules -= rule.id
        }
        return rules.values.toList()
    }

    /** The Monday of the week [at] is in. */
    fun mondayOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)

    /** [task]'s week is over by [today]: it's planned no more, and closed as it is ([roll]). */
    fun over(task: TaskItem, today: LocalDate): Boolean = task.weekly?.let { it.lastDay < today } == true

    /** A week's task's own id, the same each time, so a rule's week is only ever one task. */
    fun sourceId(rule: String, week: LocalDate): String = "weekly-$rule-$week"

    /** What's been done of [task], in minutes: its timed minutes, and each step ticked with none of its own; all of it, once it's done. */
    fun doneMin(task: TaskItem): Int {
        val ticked = task.subSteps.filter { it.done }.sumOf { (it.minutes - it.timedMin).coerceAtLeast(0) }
        val worked = task.workedMin + task.photoMin + ticked
        return if (task.status == Status.Done) maxOf(worked, task.effortMin) else worked
    }

    /** The sessions of [sessionMin] minutes [minutes] come to, at least one. A rule of more than a task's steps can hold isn't kept. */
    fun sessions(minutes: Int, sessionMin: Int): Int = ((minutes + sessionMin / 2) / sessionMin).coerceAtLeast(1)

    /** [week]'s work (its Monday) as it stands, a line each: "Guitar practice: 60 of 180 min". */
    fun weekLines(tasks: List<TaskItem>, week: LocalDate): List<String> =
        tasks.filter { it.weekly?.week == week.toString() }.map { "${it.title}: ${doneMin(it)} of ${it.effortMin} min" }

    /** What's been done of [rule]'s work in [week], as [weekLines] counts it; null with no task for that week. */
    fun doneLine(rule: WeeklyRule, tasks: List<TaskItem>, week: LocalDate): String? =
        tasks.firstOrNull { it.weekly?.rule == rule.id && it.weekly.week == week.toString() }?.let { "${doneMin(it)} of ${it.effortMin} min" }

    /**
     * [minutes] for the [daysLeft] days left of a week, to the nearest five minutes, today counted: 3 hours on a Friday
     * is an hour and a quarter.
     */
    fun share(minutes: Int, daysLeft: Int): Int =
        if (daysLeft >= 7) minutes else ((minutes * daysLeft / 7.0 / 5).roundToInt() * 5).coerceAtLeast(minOf(5, minutes))

    /**
     * [rule]'s task for [week] as of [now]; null with nothing left of that week. The week it was set in takes a share of
     * the days left, or the days named that are still to come, today's included; every later one is whole.
     */
    fun draft(rule: WeeklyRule, week: LocalDate, now: Long, zone: ZoneId): OwnTask? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun dayStart(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        fun dayEnd(day: LocalDate) = day.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli()
        val steps = if (rule.days.isNotEmpty()) {
            val shares = shareOut(rule.minutes, rule.days.map { 1 })
            rule.days.indices.map { it to week.plusDays(rule.days[it] - 1L) }.filter { (_, day) -> day >= today }.map { (i, day) ->
                OwnStep(day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK), shares[i], from = dayStart(day), dueAt = dayEnd(day), anyOrder = true)
            }.ifEmpty { return null }
        } else {
            emptyList()
        }
        val firstWeek = mondayOf(rule.setAt, zone) == week && week <= today
        val whole = if (firstWeek && rule.days.isEmpty()) share(rule.minutes, DayOfWeek.SUNDAY.value + 1 - today.dayOfWeek.value) else rule.minutes
        val sessions = if (rule.days.isEmpty() && rule.sessionMin > 0) {
            shareOut(whole, List(sessions(whole, rule.sessionMin).coerceAtMost(OwnTaskRules.MAX_STEPS)) { 1 }).mapIndexed { i, minutes -> OwnStep("Session ${i + 1}", minutes, anyOrder = true) }
        } else {
            steps
        }
        return OwnTask(
            title = rule.title,
            kind = Kind.Revision,
            className = rule.subject,
            dueAt = dayEnd(week.plusDays(6)),
            startFrom = dayStart(week).takeIf { week > today },
            minutes = if (sessions.isEmpty()) whole else sessions.sumOf { it.minutes },
            steps = sessions,
            where = rule.where,
        )
    }

    /**
     * [rule]'s task for [week], or null with nothing left of that week. Its mark makes its Sunday a plan, not a deadline
     * ([TaskItem.realDeadline]): it's never overdue, and stays out of the record of deadlines met and the margins learned.
     */
    fun make(rule: WeeklyRule, week: LocalDate, now: Long, zone: ZoneId): TaskItem? = draft(rule, week, now, zone)?.let {
        OwnTaskRules.create(it, sourceId(rule.id, week), now).copy(weekly = WeeklyMark(rule.id, week.toString(), rule.setAt))
    }

    /**
     * [tasks] as [rules] leave them at [now], [made] each rule's last week made, so a week you've deleted stays gone. A week
     * that's over closes as it stands, its minutes kept; a rule ended takes its weeks with it, those with work done closed
     * and the rest gone. A week not begun follows a rule changed since, unless it's been worked on; the week under way
     * stays as it is, as what's set on it is kept (D-4). Each rule then has this week's task and next week's.
     */
    fun roll(tasks: List<TaskItem>, made: Map<String, String>, rules: List<WeeklyRule>, now: Long, zone: ZoneId): Rolled {
        val thisWeek = mondayOf(now, zone)
        val byRule = rules.associateBy { it.id }
        val kept = tasks.mapNotNull { task ->
            val mark = task.weekly?.takeIf { task.isOpen } ?: return@mapNotNull task
            val week = LocalDate.parse(mark.week)
            when {
                week < thisWeek -> closed(task, week.plusDays(6).atTime(LocalTime.of(23, 59)).atZone(zone).toInstant().toEpochMilli())
                mark.rule in byRule -> task
                untouched(task) -> null
                else -> closed(task, now)
            }
        }.toMutableList()
        val weeks = made.filterKeys { it in byRule }.toMutableMap()
        for (rule in rules) {
            for (week in (0 until WEEKS).map { thisWeek.plusWeeks(it) }) {
                val id = TaskItem.id(Source.Own, sourceId(rule.id, week))
                val at = kept.indexOfFirst { it.id == id }
                if (at >= 0) {
                    val old = kept[at]
                    when {
                        // Ended and brought back within its week: open again as it was.
                        week == thisWeek && old.status == Status.Missed -> kept[at] = old.copy(status = Status.Open, doneAt = null)
                        week > thisWeek && old.isOpen && old.weekly?.setAt != rule.setAt && untouched(old) -> draft(rule, week, now, zone)?.let { draft ->
                            kept[at] = OwnTaskRules.edited(old, draft).copy(weekly = old.weekly?.copy(setAt = rule.setAt))
                        }
                    }
                } else if (weeks[rule.id]?.let { it >= week.toString() } != true) {
                    make(rule, week, now, zone)?.let { kept += it }
                }
                weeks[rule.id] = maxOf(weeks[rule.id] ?: "", week.toString())
            }
        }
        return Rolled(kept, weeks)
    }

    private fun closed(task: TaskItem, at: Long): TaskItem = task.copy(status = Status.Missed, doneAt = at)

    /** Nothing's been done of it. */
    private fun untouched(task: TaskItem): Boolean = doneMin(task) == 0
}
