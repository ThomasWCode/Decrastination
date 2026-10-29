package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A photo that becomes tasks (NF-01): homework on a worksheet, a page or a board, read into tasks
 * of your own to check and save, each as the form would take it. Pure: the prompt, the schema and
 * the answer read.
 */
object PhotoTasks {
    val SYSTEM = """
        Read the homework in this photo (a worksheet, a page, a board) into tasks for the student's planner. For each: title in the student's words, subject if it says, due date and time if it says, the instructions as written, minutes for a typical Year 12 student, steps if it's worth splitting (each with minutes and where it can be done), where for the task (anywhere or home). If you can't read a date, leave it empty and ask in question.
    """.trimIndent()

    private val STRING = InstructionPrompts.STRING
    private val WHERE = mapOf("type" to "string", "enum" to listOf("anywhere", "home"))

    /** Plain strings, empty for none, as the chat's are. */
    private val STEP = InstructionPrompts.obj("title" to STRING, "minutes" to mapOf("type" to "integer"), "where" to WHERE)

    private val TASK = InstructionPrompts.obj(
        "title" to STRING,
        "subject" to STRING,
        "dueDate" to STRING,
        "dueTime" to STRING,
        "instructions" to STRING,
        "minutes" to mapOf("type" to "integer"),
        "where" to WHERE,
        "steps" to mapOf("type" to "array", "items" to STEP),
    )

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "tasks" to mapOf("type" to "array", "items" to TASK),
        "question" to InstructionPrompts.nullable(STRING),
    )

    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** What's said beside the photo: today, so its dates are read from it. */
    fun describe(now: Long, zone: ZoneId): String = "Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)}. Read the homework in this photo."

    /** The tasks read, as the form would take them, what couldn't be, and the model's question. */
    data class Reading(val tasks: List<OwnTask>, val leftOut: List<String> = emptyList(), val question: String? = null)

    @Serializable
    private data class Answer(val tasks: List<Given> = emptyList(), val question: String? = null)

    @Serializable
    private data class Given(
        val title: String = "",
        val subject: String = "",
        val dueDate: String = "",
        val dueTime: String = "",
        val instructions: String = "",
        val minutes: Int = 0,
        val where: String = "anywhere",
        val steps: List<GivenStep> = emptyList(),
    )

    @Serializable
    private data class GivenStep(val title: String = "", val minutes: Int = 0, val where: String = "anywhere")

    private val json = Json { ignoreUnknownKeys = true }

    /** The most tasks one photo makes. */
    const val MAX_TASKS = 6

    /** [text] read at [now]: each task as the form would take it ([OwnTaskRules.problem]), the rest said. Null if it can't be read. */
    fun parse(text: String, now: Long, zone: ZoneId): Reading? {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull() ?: return null
        val left = mutableListOf<String>()
        val tasks = answer.tasks.take(MAX_TASKS).mapNotNull { g ->
            val due = g.dueDate.trim().takeIf { it.isNotEmpty() }?.let { d ->
                val day = runCatching { LocalDate.parse(d.take(10)) }.getOrNull()
                val time = g.dueTime.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() } ?: LocalTime.of(23, 59)
                day?.atTime(time)?.atZone(zone)?.toInstant()?.toEpochMilli()
            }
            val steps = g.steps.filter { it.title.isNotBlank() && it.minutes > 0 }.map { OwnStep(it.title.trim(), it.minutes, where = it.where.takeIf { w -> w == "home" || w == "anywhere" }) }
            val task = OwnTask(
                title = g.title.trim().take(OwnTaskRules.MAX_TITLE),
                notes = g.instructions.trim(),
                kind = Kind.Homework,
                className = g.subject.trim().takeIf { it.isNotEmpty() },
                dueAt = due?.takeIf { it > now - DAY_MS },
                minutes = if (steps.isEmpty()) g.minutes else steps.sumOf { it.minutes },
                steps = steps,
                where = if (g.where == "home") "home" else "anywhere",
            )
            val problem = OwnTaskRules.problem(task)
            if (problem != null) left += "“${g.title.trim().take(60)}” (${problem.replaceFirstChar(Char::lowercase)})"
            task.takeIf { problem == null }
        }
        // Past the most one photo makes: said, as the chat says its own, so none goes unseen.
        if (answer.tasks.size > MAX_TASKS) left += "${answer.tasks.size - MAX_TASKS} more tasks than the app takes from one photo"
        return Reading(tasks, left, answer.question?.trim()?.takeIf { it.isNotEmpty() })
    }

    private const val DAY_MS = 24 * 3_600_000L
}
