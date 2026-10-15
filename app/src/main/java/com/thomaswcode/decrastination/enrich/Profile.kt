package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerItems
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Who the student is (AI-05, AI-06), sent with every model call as a second system block after the
 * job's own: their year group and subjects, the school day, when they work, days off coming,
 * the textbook, what they've said about themselves, how they've asked Felix to behave, and what's
 * remembered. At most [MAX_CHARS], cut from the end, so what's remembered goes first. Pure.
 */
object Profile {
    const val MAX_CHARS = 2_000

    /** The most subjects, study periods and days off it names: a school week's worth. */
    private const val MAX_SUBJECTS = 12
    private const val MAX_STUDY = 5
    private const val MAX_OFF = 3

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    /** What a call sends: the year group its prompt names, and the block. */
    data class Block(val year: Int, val text: String) {
        companion object {
            val NONE = Block(12, "")
        }
    }

    /** Year 12 in the school year from [yearGroupStart]'s September, 13 the next, and so on. */
    fun yearGroup(now: Long, zone: ZoneId, yearGroupStart: Int): Int {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val schoolYear = if (date.monthValue >= 9) date.year else date.year - 1
        return 12 + (schoolYear - yearGroupStart)
    }

    fun block(settings: Settings, tasks: List<TaskItem>, timetable: Timetable?, school: SchoolCalendar, memory: MemoryState, now: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val lines = mutableListOf("About the student")
        // Subjects: the classes you've named, then any seen on open work that you haven't, by its code.
        val mapped = settings.classMap.entries.filter { it.value.isNotBlank() }.map { (code, subject) -> "${subject.trim()} (${code.trim()})" }
        val seen = tasks.asSequence().filter { it.isOpen }.mapNotNull { it.className?.trim()?.takeIf(String::isNotEmpty) }
            .filter { it !in settings.classMap.keys }.distinct().sorted().toList()
        val subjects = (mapped + seen).take(MAX_SUBJECTS)
        lines += "Year ${yearGroup(now, zone, settings.yearGroupStart)} at a UK sixth form." + if (subjects.isEmpty()) "" else " Subjects: ${subjects.joinToString(", ")}."
        schoolDay(settings, timetable, today)?.let { lines += it }
        val off = school.noSchool.filter { it.to >= today.toString() }.take(MAX_OFF).map { entry ->
            val (from, to) = LocalDate.parse(entry.from) to LocalDate.parse(entry.to)
            (if (from == to) from.format(DAY) else "${from.format(DAY)}–${to.format(DAY)}") + entry.label.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        }
        lines += "Works school days ${window(settings.weekdayHours)}, weekends and days off ${window(settings.weekendHours)}." + if (off.isEmpty()) "" else " No school: ${off.joinToString(", ")}."
        lines += "German textbook ${settings.ankiTextbook}."
        settings.profileLines.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { said ->
            lines += "From the student:"
            said.forEach { lines += "- $it" }
        }
        memory.rules.takeIf { it.isNotEmpty() }?.let { rules ->
            lines += "How Felix should behave:"
            rules.forEach { lines += "- ${it.text}" }
        }
        memory.facts.takeIf { it.isNotEmpty() }?.let { facts ->
            lines += "Remembered:"
            facts.sortedByDescending { it.at }.forEach { lines += "- ${it.topic}: ${it.text}" }
        }
        // Cut from the end: what's remembered first, the oldest of it last in, so first out.
        while (lines.size > 2 && lines.sumOf { it.length + 1 } > MAX_CHARS) lines.removeAt(lines.lastIndex)
        // A heading left with nothing under it goes too.
        if (lines.last().endsWith(":")) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    /** "School days: lessons 08:30–15:30; study periods Tue 11:15–12:15; break 10:45–11:15; lunch 13:15–14:35 (eating 13:40–13:55)." */
    private fun schoolDay(settings: Settings, timetable: Timetable?, today: LocalDate): String? {
        val monday = today.with(DayOfWeek.MONDAY)
        val week = (0L..4L).map { monday.plusDays(it) }
        val lessons = timetable?.let { t -> week.associateWith { PowerPlannerItems.lessonsOn(t, it) } }.orEmpty()
        val all = lessons.values.flatten()
        val parts = mutableListOf<String>()
        if (all.isNotEmpty()) parts += "lessons ${all.minOf { it.start }}–${all.maxOf { it.end }}"
        val name = settings.studyPeriodName.trim()
        val study = lessons.flatMap { (day, list) -> list.filter { it.className.trim().equals(name, ignoreCase = true) }.map { day to it } }.take(MAX_STUDY)
        if (study.isNotEmpty()) parts += "study periods " + study.joinToString(", ") { (day, lesson) -> "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)} ${lesson.start}–${lesson.end}" }
        parts += "break ${window(settings.breakWindow)}"
        val lunch = settings.lunchWindows.sortedBy { it.startMin }
        if (lunch.isNotEmpty()) {
            parts += if (lunch.size == 2 && lunch[0].endMin < lunch[1].startMin) "lunch ${clock(lunch[0].startMin)}–${clock(lunch[1].endMin)} (eating ${clock(lunch[0].endMin)}–${clock(lunch[1].startMin)})"
            else "lunch " + lunch.joinToString(", ") { window(it) }
        }
        return "School days: ${parts.joinToString("; ")}."
    }

    private fun window(w: Window) = "${clock(w.startMin)}–${clock(w.endMin)}"

    private fun clock(minute: Int) = "%02d:%02d".format(Locale.UK, minute / 60, minute % 60)
}
