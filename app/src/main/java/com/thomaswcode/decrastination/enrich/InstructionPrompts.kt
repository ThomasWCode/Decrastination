package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Claude is asked to read an instruction ([Instruction]) into, and how its answer is checked.
 * Pure, so the wording, the schema and the checks are tested without the API.
 */
object InstructionPrompts {

    /** An instruction longer than this is cut: it's a note, not an essay. */
    const val MAX_TEXT = 1_000

    /** The open tasks and events listed for it to choose from, at most. */
    const val MAX_TASKS = 80
    const val MAX_EVENTS = 60

    /** The events listed are the fortnight's: the calendar's read further, as far as the plan reaches (DX-14). */
    const val EVENTS_AHEAD_MS = 14 * 24 * 3_600_000L
    private const val MAX_DETAIL = 1_500

    /** A task's or an event's title, as far as it's read: a whole note can sit in an event's title. */
    private const val MAX_TITLE = 100

    // Before the schemas that use it: an object's properties are made in order.
    internal val STRING = mapOf("type" to "string")

    /** The changes Claude can make, and how it reads dates for them: an instruction's and the chat's. */
    internal val CHANGE_TYPES = """
        - NotATask: the task isn't work to plan (an email that only needs reading, something already dealt with). Needs taskId.
        - StartFrom: the task can't be started before a date. Needs taskId, date, and time if it gives one (else empty: the start of that day).
        - After: the task can't be started until another listed task is done. Needs taskId and afterTaskId.
        - DueBy: the task's deadline is a different date. Needs taskId, date, and time if it gives one (else empty: the end of that day).
        - Minutes: how long the whole task takes, as the student reckons it. Needs taskId and minutes (the whole task's: where they say how much more it needs, add the minutes already worked on it).
        - EventTime: how much of the student's time a calendar event takes, for every event of that name. Needs eventKey and eventTime: "free" (they can work through it), "busy" (it takes all its time; for an all-day event, the whole day), or "hours" with minutes (it takes that many minutes of its day, at no set time).
        - DayLimit: the most minutes of work the student can do on a day: minutes (0 for none at all), and date (one day) or weekday (every such day).
        - BusyTime: a stretch of time the student can't work: time (its start, HH:MM) and endTime (HH:MM), and date or weekday.
        - NoSchool: days with no school (a holiday, half term, an INSET day). Needs date and endDate (the same date for one day).
        - HalfDay: a school day that ends early. Needs date and endTime (HH:MM).
        - OnDay: all of the task's remaining work goes on one day. Needs taskId and date.
        - AtTime: the task's next piece starts at a set time. Needs taskId, date and time (HH:MM).
        - SameAs: the task is the same work as another listed task, from another source, which is the one kept (this one is hidden). Needs taskId and afterTaskId (the one kept).
        - Steps: replace a task's steps with these (any task's, from any source, but Anki's, whose steps are its cards), adding up to its minutes (for a new total, add a Minutes change with it too); dates within the task's. Needs taskId and steps (each with title, minutes, startDate and dueDate as YYYY-MM-DD or empty, where: anywhere or home, and anyOrder: true where it doesn't need the steps before it done first, as separate questions don't, false where it builds on them, as a draft after its plan does, and for the first; steps the student lists themselves stay in their order, false, unless they say the order doesn't matter). One step is the whole task in one go.
        - Rename: the task's name as the app shows it (its source keeps its own). Needs taskId and title (the new name).

        Use the ids and event keys exactly as listed. Work out relative dates ("next Friday", "the weekend") from today; a weekday named alone ("on Saturday") means the next one, and only "every Saturday" or "on Saturdays" means each week. A stretch of days ("20 to 25 October", "this weekend") is one change per day, but for NoSchool, which takes the stretch whole. Leave every field a change type doesn't use empty: an empty string, minutes -1 (0 is a real value: a DayLimit's no work at all), and steps an empty list.
    """.trimIndent()

    /** The instructions' reader's, for a student in Year [year] (AI-06). */
    fun system(year: Int = 12): String = (
        """
        You turn one instruction from a UK sixth-form student (Year $year) into changes for their planner app. The app plans their homework and other tasks into the evenings before each is due, counts their calendar's events as time they can't work, and blocks distracting apps while work is due. The student writes the instruction about one task, one calendar event, one day, or nothing in particular. Your changes are made as soon as you give them, and listed for the student to check and take back.

        Give only the changes the instruction asks for. The types:
""" + CHANGE_TYPES.prependIndent("        ") + """

        When the instruction is about the given task, event or day, the change is to it unless the instruction clearly names another.

        If the instruction can't be turned into these changes, or it's unclear which task, event or date it means, set understood to false, give no changes, and in question say in one short sentence what's unclear or what the app can't do. Otherwise understood is true and question is null.
    """
        ).trimIndent()

    /** A date's field, empty for none (no format: an empty one wouldn't be a date). */
    internal val DATE = mapOf("type" to "string", "description" to "YYYY-MM-DD, or empty")

    /** A time's field, empty for none. */
    internal val TIME = mapOf("type" to "string", "description" to "HH:MM, 24-hour, or empty")

    /**
     * A change's fields, as [CHANGE] has them; the chat's has one more ([ChatPrompts]). None is
     * nullable: the API compiles a schema into a grammar, a nullable field is a union in it, and
     * unions in a list of changes make the chat's grammar too large to compile. An unused string
     * is empty and a number -1, read as none ([InstructionAnswers.Given.normalised]).
     */
    internal val CHANGE_FIELDS: List<Pair<String, Any>> = listOf(
        "type" to mapOf("type" to "string", "enum" to ChangeType.entries.map { it.name }),
        "taskId" to STRING,
        "afterTaskId" to STRING,
        "eventKey" to STRING,
        "eventTime" to mapOf("type" to "string", "enum" to listOf("free", "busy", "hours", "")),
        "minutes" to mapOf("type" to "integer", "description" to "-1 for none"),
        "date" to DATE,
        "endDate" to DATE,
        "weekday" to mapOf("type" to "string", "enum" to DayOfWeek.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } + ""),
        "time" to TIME,
        "endTime" to TIME,
        "title" to STRING,
        "steps" to mapOf(
            "type" to "array",
            "items" to obj(
                "title" to STRING,
                "minutes" to mapOf("type" to "integer"),
                "startDate" to STRING,
                "dueDate" to STRING,
                "where" to mapOf("type" to "string", "enum" to listOf("anywhere", "home")),
                "anyOrder" to mapOf("type" to "boolean"),
            ),
        ),
    )

    internal val CHANGE = obj(*CHANGE_FIELDS.toTypedArray())

    val SCHEMA: Map<String, Any> = obj(
        "understood" to mapOf("type" to "boolean"),
        "question" to nullable(STRING),
        "changes" to mapOf("type" to "array", "items" to CHANGE),
    )

    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /**
     * The instruction as Claude reads it: today, what it's about, and the open tasks and the
     * fortnight's events to choose from.
     */
    fun describe(instruction: Instruction, tasks: List<TaskItem>, events: List<CalendarEvent>, now: Long, zone: ZoneId): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        // The deadline in force (PL-14), as the chat and the Tasks list have it: a plan's gone by, the real one behind it.
        fun due(t: TaskItem) = (t.deadlineAt(now) ?: t.dueAt)?.let { at(it) + if (t.hardAt(now)) "" else " (a plan)" } ?: "no date"
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)} (${zone.id})")
        appendLine("Instruction: \"${instruction.text.trim().take(MAX_TEXT)}\"")
        val about = instruction.about
        val task = about.taskId?.let { id -> tasks.firstOrNull { it.id == id } }
        when {
            task != null -> {
                appendLine("About the task ${task.id}:")
                appendLine("  Title: ${task.title}")
                appendLine("  From: ${task.source.label}" + (task.extra["from"]?.let { " ($it)" } ?: ""))
                task.className?.let { appendLine("  Class: $it") }
                // As it is now, an earlier instruction's date included, so "two days later" counts from it.
                appendLine("  Due: " + due(task) + (if (task.mine.dueAt?.value != null) " (set by an earlier instruction)" else ""))
                appendLine("  Takes: ${task.effortMin} min in all" + (if (task.mine.minutes?.minutes != null) " (set by an earlier instruction)" else "") + "; ${task.workedMin} min worked on it so far")
                listOfNotNull(task.availableFrom, task.mine.from?.value).maxOrNull()?.let { appendLine("  Can't start before: ${at(it)}") }
                task.detail.trim().takeIf { it.isNotEmpty() }?.let { appendLine("  Details: " + it.take(MAX_DETAIL).replace("\n", "\n    ")) }
            }
            about.eventKey != null -> appendLine(
                "About the calendar event \"${(about.eventTitle ?: about.eventKey!!).take(MAX_TITLE)}\" (eventKey: ${about.eventKey})" +
                    (about.eventStart?.let { ", on ${at(it)}" } ?: ""),
            )
            about.day != null -> appendLine("About the day: " + (runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day))
            else -> appendLine("About: nothing in particular")
        }
        appendLine()
        appendLine("Open tasks (id | title | from | due):")
        tasks.filter { it.isOpen }.sortedBy { it.deadlineAt(now) ?: it.dueAt ?: Long.MAX_VALUE }.take(MAX_TASKS).forEach { t ->
            appendLine("- ${t.id} | ${t.title.take(MAX_TITLE)} | ${t.source.label} | ${due(t)}")
        }
        appendLine()
        appendLine("Calendar events in the next fortnight (eventKey | title | when):")
        val fortnight = events.filter { it.start < now + EVENTS_AHEAD_MS }
        if (fortnight.isEmpty()) appendLine("(none)")
        fortnight.sortedBy { it.start }.take(MAX_EVENTS).forEach { e ->
            val time = if (e.allDay) "all day " + Instant.ofEpochMilli(e.start).atZone(ZoneId.of("UTC")).toLocalDate().format(DAY) else "${at(e.start)} to ${at(e.end)}"
            appendLine("- ${EventJudge.key(e)} | ${e.title.take(MAX_TITLE)} | $time")
        }
    }.trimEnd()

    internal fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    internal fun nullable(schema: Map<String, Any>): Map<String, Any> = mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))
}
