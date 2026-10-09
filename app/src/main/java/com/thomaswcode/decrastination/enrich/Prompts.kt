package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is asked (docs/data-sources.md §5), one job at a time: the instructions, the
 * item, and the JSON schema its answer must follow. Pure, so the wording and the schemas are
 * tested and can be tried out without the API.
 */
object Prompts {

    /** An email body past this is left out (and the model told): a newsletter's tail doesn't change its triage. */
    const val MAX_TEXT = 8_000

    private const val STUDENT = "a UK sixth-form student (Year 12)"

    fun system(job: Enrichments.Job): String = when (job) {
        Enrichments.Job.Email -> """
            You triage one email for a planner app used by $STUDENT. The planner turns each obligation into scheduled work and blocks distracting apps until it's done, so overstating the work costs the student free time and understating it lets work slip.

            Decide, from the email:
            - kind: "Admin" if it asks the student to do something (reply, sign, pay, fill in a form, bring or prepare something); "Event" if it's about something happening at a set time that needs nothing beyond turning up (a trip, a call, an open evening); "Info" if it only needs reading (newsletters, notifications, receipts, marketing, automatic messages).
            - actionableFrom: the date (YYYY-MM-DD) before which nothing can be done, if the email says so (a form that opens next week); otherwise null.
            - deadline: when it must be done by, as a local date and time (YYYY-MM-DDTHH:MM), only if the email states or clearly implies one; for an Event, when it starts; otherwise null. Never invent one.
            - effortMin: the minutes of the student's own work it needs, reading included: about 2 for Info.
            - nextStep: the one concrete next action, as an instruction under 12 words ("Reply to Mr Hughes confirming the trip").

            Work out relative dates ("next Friday") from when the email was received.
        """.trimIndent()
        Enrichments.Job.Assignment -> """
            You plan one homework assignment for $STUDENT, for a planner that schedules the work over the days before it's due and blocks distracting apps until it's done.

            Split the work into the ordered steps the student will actually do, each with a realistic duration in minutes. Keep steps between 10 and 60 minutes where the work allows, and a short task as one step. Base the steps on the instructions; add a final "Check and hand in" step only when there's something to hand in. The class's effort multiplier says how this student's real times compare with estimates (above 1 means they take longer): apply it.

            Also give:
            - effortMin: the total minutes, the sum of the steps.
            - ankiSections: the vocabulary sections the student is asked to learn, written like "1.2" (from "learn vocabulary 1.2" or "p46-47/ 2.2/2.3"); otherwise an empty list.
            - testDate: if the work prepares for a test or assessment on a stated date, that date (YYYY-MM-DD); otherwise null.
        """.trimIndent()
        Enrichments.Job.Effort -> """
            Estimate how many minutes of work $STUDENT needs to finish one item from their planner, from its title, class and details. Give a realistic total for this student; the class's effort multiplier says how their real times compare with estimates (above 1 means they take longer): apply it.
        """.trimIndent()
    }

    /** The schema for [job]'s answer, as the API takes it: every object closed, every field required, optional ones nullable. */
    fun schema(job: Enrichments.Job): Map<String, Any> = when (job) {
        Enrichments.Job.Email -> obj(
            "kind" to mapOf("type" to "string", "enum" to listOf("Admin", "Event", "Info")),
            "actionableFrom" to nullable(mapOf("type" to "string", "format" to "date")),
            "deadline" to nullable(mapOf("type" to "string", "description" to "Local date and time, YYYY-MM-DDTHH:MM")),
            "effortMin" to mapOf("type" to "integer"),
            "nextStep" to mapOf("type" to "string"),
        )
        Enrichments.Job.Assignment -> obj(
            "subSteps" to mapOf(
                "type" to "array",
                "items" to obj("title" to mapOf("type" to "string"), "minutes" to mapOf("type" to "integer")),
            ),
            "effortMin" to mapOf("type" to "integer"),
            "ankiSections" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "testDate" to nullable(mapOf("type" to "string", "format" to "date")),
        )
        Enrichments.Job.Effort -> obj("effortMin" to mapOf("type" to "integer"))
    }

    private fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    private fun nullable(schema: Map<String, Any>): Map<String, Any> = mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", Locale.UK)

    /** The item, as the model reads it. */
    fun describe(task: TaskItem, job: Enrichments.Job, now: Long, zone: ZoneId, multiplier: Double = 1.0): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone)
        appendLine("Today: ${at(now).format(DAY)}")
        when (job) {
            Enrichments.Job.Email -> {
                task.extra["received"]?.toLongOrNull()?.let { appendLine("Received: ${at(it).format(DAY_TIME)}") }
                task.extra["from"]?.let { appendLine("From: $it") }
                appendLine("Subject: ${task.title}")
            }
            else -> {
                appendLine("Title: ${task.title}")
                task.className?.let { appendLine("Class: $it") }
                appendLine("Due: " + (task.dueAt?.let { at(it).format(DAY_TIME) } ?: "no date"))
                appendLine("Class effort multiplier: %.2f".format(Locale.UK, multiplier))
            }
        }
        val text = task.detail.trim()
        if (text.isNotEmpty()) {
            appendLine()
            appendLine(if (job == Enrichments.Job.Email) "Email:" else "Details:")
            if (text.length <= MAX_TEXT) append(text) else append(text.take(MAX_TEXT)).append("\n[The rest of this long text is left out.]")
        }
    }.trimEnd()
}

/** The model's answers, read and checked: anything out of range is dropped rather than trusted. */
object Answers {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Triage(val kind: String, val actionableFrom: String? = null, val deadline: String? = null, val effortMin: Int, val nextStep: String)

    @Serializable
    private data class Step(val title: String, val minutes: Int)

    @Serializable
    private data class Split(val subSteps: List<Step>, val effortMin: Int, val ankiSections: List<String> = emptyList(), val testDate: String? = null)

    @Serializable
    private data class Estimate(val effortMin: Int)

    private const val MAX_EFFORT = 600
    private const val MAX_STEP = 240
    private const val MAX_STEPS = 12
    private const val MAX_TITLE = 80
    private const val MAX_NEXT_STEP = 120
    private val SECTION = Regex("""^[1-9]\.[1-9]$""")

    /** A test's day: work must be done before school that morning. */
    private val SCHOOL_STARTS: LocalTime = LocalTime.of(8, 30)

    /** [text] as [job]'s answer, or null if it isn't one. */
    fun parse(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId): Enrichment? = runCatching {
        val base = Enrichment(inputHash = Enrichments.inputHash(task), by = by, at = now)
        when (job) {
            Enrichments.Job.Email -> json.decodeFromString(Triage.serializer(), text).let { t ->
                base.copy(
                    kind = Kind.entries.firstOrNull { it.name == t.kind && it in setOf(Kind.Admin, Kind.Event, Kind.Info) },
                    actionableFrom = t.actionableFrom?.let { date(it, zone, LocalTime.MIDNIGHT) }?.takeIf { plausible(it, now) },
                    deadline = t.deadline?.let { dateTime(it, zone) }?.takeIf { plausible(it, now) },
                    effortMin = effort(t.effortMin),
                    nextStep = t.nextStep.trim().takeIf { it.isNotEmpty() }?.take(MAX_NEXT_STEP),
                )
            }
            Enrichments.Job.Assignment -> json.decodeFromString(Split.serializer(), text).let { s ->
                val steps = s.subSteps.filter { it.title.isNotBlank() && it.minutes in 1..MAX_STEP }.take(MAX_STEPS)
                    .map { SubStep(it.title.trim().take(MAX_TITLE), it.minutes) }
                base.copy(
                    effortMin = effort(s.effortMin) ?: steps.sumOf { it.minutes }.takeIf { it > 0 },
                    subSteps = steps.ifEmpty { null },
                    ankiSections = s.ankiSections.map { it.trim() }.filter { SECTION.matches(it) }.distinct(),
                    testDate = s.testDate?.let { date(it, zone, SCHOOL_STARTS) }?.takeIf { plausible(it, now) },
                )
            }
            Enrichments.Job.Effort -> base.copy(effortMin = effort(json.decodeFromString(Estimate.serializer(), text).effortMin))
        }
    }.getOrNull()

    private fun effort(minutes: Int): Int? = minutes.takeIf { it in 1..MAX_EFFORT }

    private fun date(text: String, zone: ZoneId, time: LocalTime): Long? = runCatching {
        LocalDate.parse(text.trim().take(10)).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** "YYYY-MM-DDTHH:MM", or a date alone, meaning the end of that day. */
    private fun dateTime(text: String, zone: ZoneId): Long? = runCatching {
        val trimmed = text.trim()
        if (trimmed.length <= 10) date(trimmed, zone, LocalTime.of(23, 59))
        else LocalDateTime.parse(trimmed.take(16)).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** Within a month before now and a year and a bit after: anything else is a misreading. */
    private fun plausible(time: Long, now: Long): Boolean = time in (now - 31 * DAY_MS)..(now + 400 * DAY_MS)

    private const val DAY_MS = 24 * 3_600_000L
}
