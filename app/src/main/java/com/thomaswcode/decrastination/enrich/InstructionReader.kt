package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The string, if it's filled in: an unused field is empty, as the answers' schemas have no nullable fields. */
internal fun String?.filled(): String? = this?.takeIf { it.isNotBlank() }

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
        - Steps: replace a task's steps with these (any task's, from any source, but Anki's, whose steps are its cards), adding up to its minutes (for a new total, add a Minutes change with it too); dates within the task's. Needs taskId and steps (each with title, minutes, startDate and dueDate as YYYY-MM-DD or empty, and where: anywhere or home). One step is the whole task in one go.
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
                appendLine("  Due: " + due(task) + (if (task.userDueAt != null) " (set by an earlier instruction)" else ""))
                appendLine("  Takes: ${task.effortMin} min in all" + (if (task.userEffortMin != null) " (set by an earlier instruction)" else "") + "; ${task.workedMin} min worked on it so far")
                listOfNotNull(task.availableFrom, task.userFrom).maxOrNull()?.let { appendLine("  Can't start before: ${at(it)}") }
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

/** Claude's reading of an instruction, checked: changes the app can make, or why there are none. */
object InstructionAnswers {

    sealed interface Reading {
        data class Changes(val changes: List<Change>) : Reading

        /** Nothing to apply: what Claude found unclear, or why its answer couldn't be used. */
        data class Unclear(val why: String) : Reading
    }

    @Serializable
    private data class Answer(val understood: Boolean, val question: String? = null, val changes: List<Given> = emptyList())

    /** One change as Claude gives it, before it's checked ([change]). */
    @Serializable
    internal data class Given(
        val type: String,
        val taskId: String? = null,
        val afterTaskId: String? = null,
        val eventKey: String? = null,
        val eventTime: String? = null,
        val minutes: Int? = null,
        val date: String? = null,
        val endDate: String? = null,
        val weekday: String? = null,
        val time: String? = null,
        val endTime: String? = null,
        val title: String = "",
        val steps: List<GivenStep> = emptyList(),
        /** The chat's only ([ChatPrompts]): a Minutes total that's the app's estimate at the student's pace. */
        val paced: Boolean = false,
    ) {
        /** As given, but an empty string or a minutes of -1 is none: the schema has no nullable fields ([InstructionPrompts.CHANGE_FIELDS]). */
        fun normalised(): Given = copy(
            taskId = taskId.filled(), afterTaskId = afterTaskId.filled(), eventKey = eventKey.filled(), eventTime = eventTime.filled(),
            minutes = minutes?.takeIf { it >= 0 }, date = date.filled(), endDate = endDate.filled(), weekday = weekday.filled(),
            time = time.filled(), endTime = endTime.filled(),
        )
    }

    /** A step of a [ChangeType.Steps] change, its dates empty for none. */
    @Serializable
    internal data class GivenStep(val title: String = "", val minutes: Int = 0, val startDate: String = "", val dueDate: String = "", val where: String = "anywhere")

    private val json = Json { ignoreUnknownKeys = true }

    /** More changes than this is a misreading, not an instruction. */
    const val MAX_CHANGES = 40
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * [text] as Claude's reading, checked against what there is: the [tasks] it names must be
     * listed, the [events] too, and every date and time must read and be plausible. One change that
     * can't be made and none are: you're told why, and can write it again.
     */
    fun parse(text: String, tasks: List<TaskItem>, events: List<CalendarEvent>, about: com.thomaswcode.decrastination.core.About, now: Long, zone: ZoneId): Reading {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull()
            ?: return Reading.Unclear("Claude's answer couldn't be read")
        if (!answer.understood || answer.changes.isEmpty()) {
            return Reading.Unclear(answer.question?.trim()?.takeIf { it.isNotEmpty() }?.take(300) ?: "Claude couldn't turn it into a change the app can make")
        }
        if (answer.changes.size > MAX_CHANGES) return Reading.Unclear("Claude read it as ${answer.changes.size} changes, more than the $MAX_CHANGES the app takes")
        val byId = tasks.associateBy { it.id }
        // The event it's about counts even if it's past the fortnight read.
        val titles = events.associate { EventJudge.key(it) to it.title } + listOfNotNull(about.eventKey?.let { it to (about.eventTitle ?: it) })
        val changes = answer.changes.map(Given::normalised).map { g ->
            change(g, byId, titles, now, zone) ?: return Reading.Unclear("Claude's reading couldn't be used: ${problem(g, byId, titles)}")
        }
        stepsProblem(changes, byId)?.let { return Reading.Unclear("Claude's reading couldn't be used: $it") }
        // Tasks waiting on each other in a circle within this one reading: none would ever be planned.
        if (InstructionRules.makesCircle(changes, emptyList())) return Reading.Unclear("Claude's reading would have tasks wait for each other in a circle")
        return Reading.Changes(changes)
    }

    /** [g] as a change the app can make, checked against the [tasks] and [events] there are; null if it can't be. */
    internal fun change(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>, now: Long, zone: ZoneId): Change? {
        val type = ChangeType.entries.firstOrNull { it.name == g.type } ?: return null
        val task = g.taskId?.takeIf { it in tasks }
        return when (type) {
            ChangeType.NotATask -> task?.let { Change(type, taskId = it) }
            ChangeType.StartFrom -> task?.let { id -> at(g.date, g.time, LocalTime.MIDNIGHT, zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
            ChangeType.DueBy -> task?.let { id -> at(g.date, g.time, LocalTime.of(23, 59), zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
            // At least a minute for each of its steps, as yours are shared out over them.
            // A paced total keeps the app's estimate it came from, and the task's content, as they are now (#50's review).
            ChangeType.Minutes -> task?.let { id ->
                val t = tasks.getValue(id)
                g.minutes?.takeIf { it in OwnTaskRules.minutesFor(t) }?.let {
                    Change(type, taskId = id, minutes = it, pacedFrom = t.appEffortMin.takeIf { g.paced }, pacedHash = com.thomaswcode.decrastination.core.Enrichments.inputHash(t).takeIf { g.paced })
                }
            }
            ChangeType.After -> task?.let { id -> g.afterTaskId?.takeIf { it in tasks && it != id }?.let { Change(type, taskId = id, afterTaskId = it) } }
            ChangeType.EventTime -> g.eventKey?.takeIf { it in events }?.let { key ->
                val answer = when (g.eventTime) {
                    "free" -> "free"
                    "busy" -> "busy"
                    "hours" -> g.minutes?.takeIf { it in 1..24 * 60 }?.let { "load:$it" }
                    else -> null
                } ?: return null
                Change(type, eventKey = key, eventTitle = events[key], eventAnswer = answer)
            }
            ChangeType.DayLimit -> {
                val minutes = g.minutes?.takeIf { it in 0..24 * 60 } ?: return null
                day(g, now)?.let { (date, weekday) -> Change(type, date = date, weekday = weekday, freeMin = minutes) }
            }
            ChangeType.BusyTime -> {
                val start = minute(g.time) ?: return null
                val end = minute(g.endTime)?.takeIf { it > start } ?: return null
                day(g, now)?.let { (date, weekday) -> Change(type, date = date, weekday = weekday, startMin = start, endMin = end) }
            }
            // A stretch of dates, not a weekday: the school calendar holds dates (PL-16).
            ChangeType.NoSchool -> {
                val first = day(g.copy(weekday = null), now)?.first ?: return null
                val last = g.endDate?.let { day(g.copy(date = it, weekday = null), now)?.first ?: return null } ?: first
                if (last < first) return null
                Change(type, date = first, endDate = last)
            }
            ChangeType.HalfDay -> {
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val end = minute(g.endTime) ?: return null
                Change(type, date = date, endMin = end)
            }
            // A day or a time for a task (PL-04): a date, not a weekday.
            ChangeType.OnDay -> task?.let { id -> day(g.copy(weekday = null), now)?.first?.let { Change(type, taskId = id, date = it) } }
            ChangeType.AtTime -> task?.let { id ->
                val date = day(g.copy(weekday = null), now)?.first ?: return null
                val start = minute(g.time) ?: return null
                at(date, g.time, LocalTime.MIDNIGHT, zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, date = date, startMin = start, time = it) }
            }
            // The same work as another listed task (DX-15), which is kept.
            ChangeType.SameAs -> task?.let { id -> g.afterTaskId?.takeIf { it in tasks && it != id }?.let { Change(type, taskId = id, afterTaskId = it) } }
            // A task's name in the app (16 Oct): not one derived from Anki's counts, whose name is their count.
            ChangeType.Rename -> task?.takeIf { !tasks.getValue(it).derived }?.let { id -> g.title.trim().takeIf { it.isNotEmpty() }?.let { Change(type, taskId = id, title = it.take(OwnTaskRules.MAX_TITLE)) } }
            // Any task's steps (16 Oct; your own's only before) but Anki's, which are its cards, each with
            // its minutes and its dates readable (AI-19).
            ChangeType.Steps -> task?.takeIf { !tasks.getValue(it).derived && tasks.getValue(it).source != com.thomaswcode.decrastination.core.Source.Anki }?.let { id ->
                // A step's dates: readable, in range, its start not after its end, and neither after the task's day.
                val dueDay = tasks.getValue(id).dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                fun dayOf(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
                val steps = g.steps.takeIf { it.isNotEmpty() && it.size <= OwnTaskRules.MAX_STEPS }?.map { s ->
                    if (s.title.isBlank() || s.minutes < 1) return null
                    val from = s.startDate.takeIf { it.isNotBlank() }?.let { at(it, null, LocalTime.MIDNIGHT, zone)?.takeIf { t -> plausible(t, now) } ?: return null }
                    val due = s.dueDate.takeIf { it.isNotBlank() }?.let { at(it, null, LocalTime.of(23, 59), zone)?.takeIf { t -> plausible(t, now) } ?: return null }
                    if (from != null && due != null && from > due) return null
                    if (dueDay != null && listOfNotNull(from, due).any { dayOf(it) > dueDay }) return null
                    OwnStep(
                        title = s.title.trim().take(OwnTaskRules.MAX_TITLE),
                        minutes = s.minutes,
                        from = from,
                        dueAt = due,
                        where = s.where.takeIf { it == "home" || it == "anywhere" },
                    )
                } ?: return null
                Change(type, taskId = id, steps = steps)
            }
        }
    }

    /**
     * Why a split among [changes] can't be taken (AI-19): its steps don't add up to its task's
     * minutes, and no Minutes change among them gives that new total. Null when each can.
     */
    internal fun stepsProblem(changes: List<Change>, tasks: Map<String, TaskItem>): String? {
        for (c in changes.filter { it.type == ChangeType.Steps }) {
            val task = tasks[c.taskId] ?: continue
            val sum = c.steps.orEmpty().sumOf { it.minutes }
            // Your own task's minutes as the form has them; a source's task's, the estimate in force.
            val total = if (task.source == com.thomaswcode.decrastination.core.Source.Own) {
                OwnTaskRules.draftOf(task).let { draft -> if (draft.steps.isEmpty()) draft.minutes else draft.steps.sumOf { it.minutes } }
            } else {
                task.effortMin
            }
            if (sum == total || changes.any { it.type == ChangeType.Minutes && it.taskId == c.taskId && it.minutes == sum }) continue
            return "the steps for “${task.title.take(60)}” add up to $sum minutes, not its $total, and nothing says its total changes"
        }
        return null
    }

    /** Why [g] couldn't be used, in words. */
    internal fun problem(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>): String = when {
        ChangeType.entries.none { it.name == g.type } -> "a change of a kind the app doesn't make"
        g.type in setOf("NotATask", "StartFrom", "DueBy", "Minutes", "After", "OnDay", "AtTime", "Steps", "SameAs", "Rename") && g.taskId !in tasks -> "it names a task that isn't listed"
        g.type == "SameAs" -> "the task it's the same as isn't listed"
        g.type in setOf("Steps", "Rename") && tasks[g.taskId]?.let { it.derived || (g.type == "Steps" && it.source == com.thomaswcode.decrastination.core.Source.Anki) } == true -> "an Anki task's steps and name come from its cards"
        g.type == "Rename" -> "the new name is missing"
        g.type == "Steps" -> "a step has no name or minutes, or a date that doesn't read, is out of range or falls after the task's"
        g.type == "After" -> "it waits on a task that isn't listed"
        g.type == "EventTime" && g.eventKey !in events -> "it names an event that isn't in the calendar"
        else -> "a date, time or number in it is out of range"
    }

    /** A day's date (YYYY-MM-DD) or weekday (1–7), one of the two. */
    private fun day(g: Given, now: Long): Pair<String?, Int?>? {
        g.date?.let { text ->
            val date = runCatching { LocalDate.parse(text.trim().take(10)) }.getOrNull() ?: return null
            // A day long past, or more than a year off, is a misreading.
            val today = LocalDate.ofEpochDay(now / DAY_MS)
            if (date < today.minusDays(1) || date > today.plusDays(400)) return null
            return date.toString() to null
        }
        val weekday = DayOfWeek.entries.firstOrNull { it.name.equals(g.weekday?.trim(), ignoreCase = true) } ?: return null
        return null to weekday.value
    }

    private fun minute(text: String?): Int? = text?.trim()?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }?.let { it.hour * 60 + it.minute }

    private fun at(date: String?, time: String?, otherwise: LocalTime, zone: ZoneId): Long? {
        val day = date?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() } ?: return null
        val clock = time?.let { t -> runCatching { LocalTime.parse(t.trim().take(5)) }.getOrNull() ?: return null } ?: otherwise
        return day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
    }

    /** Within a month before now and a year and a bit after. */
    private fun plausible(time: Long, now: Long): Boolean = time in (now - 31 * DAY_MS)..(now + 400 * DAY_MS)
}

/** Claude reading one instruction ([InstructionPrompts]), as the enrichment's calls are made. */
class InstructionReader(private val client: AnthropicClient, private val zone: ZoneId, val model: String = ClaudeEnricher.MODEL, private val profile: Profile.Block = Profile.Block.NONE) {

    data class Result(val reading: InstructionAnswers.Reading?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    suspend fun read(instruction: Instruction, tasks: List<TaskItem>, events: List<CalendarEvent>, now: Long): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { InstructionPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(InstructionPrompts.system(profile.year), profile.text))
            .addUserMessage(InstructionPrompts.describe(instruction, tasks, events, now, zone).take(Pricing.MAX_INPUT_CHARS))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Instruction)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> return Result(null, cost, usage = usage)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return Result(InstructionAnswers.parse(text, tasks, events, instruction.about, now, zone), cost, usage = usage)
    }
}
