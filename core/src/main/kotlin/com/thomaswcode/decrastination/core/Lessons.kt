package com.thomaswcode.decrastination.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One lesson of a class at its time, epoch millis: [className] as Power Planner's timetable names it. */
data class LessonAt(val className: String, val start: Long, val end: Long)

/**
 * The next lessons by class (G8, docs/plan_v4/15-sent-or-asked-for.md §G8): what "next lesson" and "when's
 * my next chemistry?" are answered from, a Power Planner class tied to a Teams code by the subject the
 * class map gives both. Pure.
 */
object Lessons {
    /** The lessons a class lists, at most. */
    const val PER_CLASS = 3

    /** How far ahead they're looked for: the plan's reach, so a holiday doesn't hide them. */
    const val REACH_DAYS = 90L

    /** A timetable read longer ago than this is said to be, and a lesson's date isn't set from it. */
    const val STALE_MS = 7 * 24 * 3_600_000L

    /** A subject's classes: the [subject] the class map gives them (none: one class, tied to no code), every name it has, and its next [lessons]. */
    data class Group(val subject: String?, val names: List<String>, val lessons: List<LessonAt>)

    /** The next lessons as the chat's told them: by subject ([groups]), from the timetable read at [readAt], [stale] past [STALE_MS] or read at no known time. */
    data class Timetabled(val groups: List<Group>, val readAt: Long?, val stale: Boolean)

    /**
     * Whether a timetable read at [readAt] is too old to date a lesson by at [now]: past [STALE_MS], or read at no
     * known time (its source's last read lost, the rest of the tasks' file recovered), as its age can't be told.
     */
    fun stale(readAt: Long?, now: Long): Boolean = readAt == null || now - readAt > STALE_MS

    /**
     * Each class's next [PER_CLASS] lessons after [now], one already begun passed over, looked for day by day
     * as far as [REACH_DAYS] ahead, by [PowerPlannerItems.lessonsOn]'s rule for the week (with week one unknown,
     * only the lessons of both weeks); none on a day of no school, nor from a half day's end on. The classes
     * named in [skip] (the study period's) aren't lessons of a subject.
     */
    fun next(timetable: Timetable, now: Long, zone: ZoneId, school: SchoolCalendar, skip: Set<String> = emptySet()): Map<String, List<LessonAt>> {
        val skipped = skip.map { it.trim().lowercase() }.toSet()
        val wanted = timetable.classes.map { it.name }.filter { it.isNotBlank() && it.trim().lowercase() !in skipped }.toSet()
        val found = wanted.associateWith { mutableListOf<LessonAt>() }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var day: LocalDate = today
        while (day <= today.plusDays(REACH_DAYS) && found.values.any { it.size < PER_CLASS }) {
            if (school.isSchoolDay(day)) {
                val half = school.halfDay(day)?.endsAtMin
                for (lesson in PowerPlannerItems.lessonsOn(timetable, day)) {
                    val list = found[lesson.className] ?: continue
                    if (list.size >= PER_CLASS) continue
                    if (half != null && lesson.start.hour * 60 + lesson.start.minute >= half) continue
                    val start = day.atTime(lesson.start).atZone(zone).toInstant().toEpochMilli()
                    if (start <= now) continue
                    list += LessonAt(lesson.className, start, day.atTime(lesson.end).atZone(zone).toInstant().toEpochMilli())
                }
            }
            day = day.plusDays(1)
        }
        return found.filterValues { it.isNotEmpty() }
    }

    /**
     * [next]'s classes by subject: a Power Planner class and a Teams code to which [classMap] gives the same subject
     * are one, its names all of theirs and its lessons the soonest [PER_CLASS] of its classes'; so is a Teams code
     * mapped to a Power Planner class's own name, that class given nothing (you said which it is); a class with no
     * subject is its own, tied to no code. By their first lesson.
     */
    fun groups(next: Map<String, List<LessonAt>>, classMap: Map<String, String>): List<Group> {
        // A class the map gives nothing, but a code is mapped to by its name, has its name as its subject: the code's.
        fun subjectOf(name: String) = classMap.entries.firstOrNull { it.key.trim().equals(name.trim(), ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: name.trim().takeIf { n -> n.isNotEmpty() && classMap.values.any { it.trim().equals(n, ignoreCase = true) } }
        val (mapped, unmapped) = next.keys.partition { subjectOf(it) != null }
        val bySubject = mapped.groupBy { subjectOf(it)!!.lowercase() }.map { (_, names) ->
            val subject = subjectOf(names.first())!!
            val codes = classMap.filter { (code, s) -> s.trim().equals(subject, ignoreCase = true) && names.none { it.equals(code, ignoreCase = true) } }.keys.sorted()
            Group(subject, names.sorted() + codes, names.flatMap { next.getValue(it) }.sortedBy { it.start }.take(PER_CLASS))
        }
        return (bySubject + unmapped.map { Group(null, listOf(it), next.getValue(it)) }).sortedBy { it.lessons.first().start }
    }

    /**
     * The next lesson of the class [named] in [groups]: by its subject, or one of its names (a Power Planner
     * class's, a Teams code), case ignored; or of the class a Teams code's subject ties it to through [classMap].
     * Null where none is found, or it has no lesson ahead.
     */
    fun nextOf(named: String, groups: List<Group>, classMap: Map<String, String>): LessonAt? {
        val name = named.trim()
        if (name.isEmpty()) return null
        val subject = classMap.entries.firstOrNull { it.key.trim().equals(name, ignoreCase = true) }?.value?.trim()
        return groups.firstOrNull { g ->
            g.subject?.equals(name, ignoreCase = true) == true || g.names.any { it.trim().equals(name, ignoreCase = true) } || (subject != null && g.subject?.equals(subject, ignoreCase = true) == true)
        }?.lessons?.firstOrNull()
    }
}
