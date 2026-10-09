package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.DayOfWeek
import java.time.Duration
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
    private const val MAX_DETAIL = 1_500

    // Before the schemas that use it: an object's properties are made in order.
    private val STRING = mapOf("type" to "string")

    val SYSTEM = """
        You turn one instruction from a UK sixth-form student (Year 12) into changes for their planner app. The app plans their homework and other tasks into the evenings before each is due, counts their calendar's events as time they can't work, and blocks distracting apps while work is due. The student writes the instruction about one task, one calendar event, one day, or nothing in particular, and checks your reading before it's applied.

        Give only the changes the instruction asks for. The types:
        - NotATask: the task isn't work to plan (an email that only needs reading, something already dealt with). Needs taskId.
        - StartFrom: the task can't be started before a date. Needs taskId, date, and time if it gives one (else null: the start of that day).
        - After: the task can't be started until another listed task is done. Needs taskId and afterTaskId.
        - DueBy: the task's deadline is a different date. Needs taskId, date, and time if it gives one (else null: the end of that day).
        - EventTime: how much of the student's time a calendar event takes, for every event of that name. Needs eventKey and eventTime: "free" (they can work through it), "busy" (it takes all its time; for an all-day event, the whole day), or "hours" with minutes (it takes that many minutes of its day, at no set time).
        - DayLimit: the most minutes of work the student can do on a day: minutes (0 for none at all), and date (one day) or weekday (every such day).
        - BusyTime: a stretch of time the student can't work: time (its start, HH:MM) and endTime (HH:MM), and date or weekday.

        Use the ids and event keys exactly as listed. Work out relative dates ("next Friday", "the weekend") from today; a weekday named alone ("on Saturday") means the next one, and only "every Saturday" or "on Saturdays" means each week. A stretch of days ("20 to 25 October", "this weekend") is one change per day. When the instruction is about the given task, event or day, the change is to it unless the instruction clearly names another. Leave every field a change type doesn't use null.

        If the instruction can't be turned into these changes, or it's unclear which task, event or date it means, set understood to false, give no changes, and in question say in one short sentence what's unclear or what the app can't do. Otherwise understood is true and question is null.
    """.trimIndent()

    private val CHANGE = obj(
        "type" to mapOf("type" to "string", "enum" to ChangeType.entries.map { it.name }),
        "taskId" to nullable(STRING),
        "afterTaskId" to nullable(STRING),
        "eventKey" to nullable(STRING),
        "eventTime" to nullable(mapOf("type" to "string", "enum" to listOf("free", "busy", "hours"))),
        "minutes" to nullable(mapOf("type" to "integer")),
        "date" to nullable(mapOf("type" to "string", "format" to "date")),
        "weekday" to nullable(mapOf("type" to "string", "enum" to DayOfWeek.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })),
        "time" to nullable(mapOf("type" to "string", "description" to "HH:MM, 24-hour")),
        "endTime" to nullable(mapOf("type" to "string", "description" to "HH:MM, 24-hour")),
    )

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
                appendLine("  Due: " + ((task.sourceValues?.dueAt ?: task.dueAt)?.let(::at) ?: "no date"))
                task.availableFrom?.let { appendLine("  Can't start before: ${at(it)}") }
                task.detail.trim().takeIf { it.isNotEmpty() }?.let { appendLine("  Details: " + it.take(MAX_DETAIL).replace("\n", "\n    ")) }
            }
            about.eventKey != null -> appendLine(
                "About the calendar event \"${about.eventTitle ?: about.eventKey}\" (eventKey: ${about.eventKey})" +
                    (about.eventStart?.let { ", on ${at(it)}" } ?: ""),
            )
            about.day != null -> appendLine("About the day: " + (runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day))
            else -> appendLine("About: nothing in particular")
        }
        appendLine()
        appendLine("Open tasks (id | title | from | due):")
        tasks.filter { it.isOpen }.sortedBy { it.dueAt ?: Long.MAX_VALUE }.take(MAX_TASKS).forEach { t ->
            appendLine("- ${t.id} | ${t.title.take(100)} | ${t.source.label} | ${t.dueAt?.let(::at) ?: "no date"}")
        }
        appendLine()
        appendLine("Calendar events in the next fortnight (eventKey | title | when):")
        if (events.isEmpty()) appendLine("(none)")
        events.sortedBy { it.start }.take(MAX_EVENTS).forEach { e ->
            val time = if (e.allDay) "all day " + Instant.ofEpochMilli(e.start).atZone(ZoneId.of("UTC")).toLocalDate().format(DAY) else "${at(e.start)} to ${at(e.end)}"
            appendLine("- ${EventJudge.key(e)} | ${e.title} | $time")
        }
    }.trimEnd()

    private fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    private fun nullable(schema: Map<String, Any>): Map<String, Any> = mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))
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

    @Serializable
    private data class Given(
        val type: String,
        val taskId: String? = null,
        val afterTaskId: String? = null,
        val eventKey: String? = null,
        val eventTime: String? = null,
        val minutes: Int? = null,
        val date: String? = null,
        val weekday: String? = null,
        val time: String? = null,
        val endTime: String? = null,
    )

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
        val changes = answer.changes.map { g ->
            change(g, byId, titles, now, zone) ?: return Reading.Unclear("Claude's reading couldn't be used: ${problem(g, byId, titles)}")
        }
        // Tasks waiting on each other in a circle within this one reading: none would ever be planned.
        if (Instructions.makesCircle(changes, emptyList())) return Reading.Unclear("Claude's reading would have tasks wait for each other in a circle")
        return Reading.Changes(changes)
    }

    private fun change(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>, now: Long, zone: ZoneId): Change? {
        val type = ChangeType.entries.firstOrNull { it.name == g.type } ?: return null
        val task = g.taskId?.takeIf { it in tasks }
        return when (type) {
            ChangeType.NotATask -> task?.let { Change(type, taskId = it) }
            ChangeType.StartFrom -> task?.let { id -> at(g.date, g.time, LocalTime.MIDNIGHT, zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
            ChangeType.DueBy -> task?.let { id -> at(g.date, g.time, LocalTime.of(23, 59), zone)?.takeIf { plausible(it, now) }?.let { Change(type, taskId = id, time = it) } }
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
        }
    }

    /** Why [g] couldn't be used, in words. */
    private fun problem(g: Given, tasks: Map<String, TaskItem>, events: Map<String, String>): String = when {
        ChangeType.entries.none { it.name == g.type } -> "a change of a kind the app doesn't make"
        g.type in setOf("NotATask", "StartFrom", "DueBy", "After") && g.taskId !in tasks -> "it names a task that isn't listed"
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
class InstructionReader(apiKey: String, private val zone: ZoneId, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    data class Result(val reading: InstructionAnswers.Reading?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    suspend fun read(instruction: Instruction, tasks: List<TaskItem>, events: List<CalendarEvent>, now: Long): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { InstructionPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(InstructionPrompts.SYSTEM)
            .addUserMessage(InstructionPrompts.describe(instruction, tasks, events, now, zone))
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true)
            StopReason.MAX_TOKENS -> return Result(null, cost)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return Result(InstructionAnswers.parse(text, tasks, events, instruction.about, now, zone), cost)
    }
}
