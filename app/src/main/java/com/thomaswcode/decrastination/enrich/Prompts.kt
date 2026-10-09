package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the model is asked (docs/data-sources.md §5), one job at a time: the instructions, the
 * item, and the JSON schema its answer must follow. Pure, so the wording and the schemas are
 * tested and can be tried out without the API.
 */
object Prompts {

    /**
     * Text past this is left out (and the model told). Room for a long email's own calendar of
     * deadlines (one seen ran to 8 500 characters); past it, a newsletter's tail, which doesn't
     * change its triage.
     */
    const val MAX_TEXT = 16_000

    /** Blocks an email or a planner item can come back in: one email can hold months of applications. */
    const val MAX_BLOCKS = 30

    private const val STUDENT = "a UK sixth-form student (Year 12)"

    fun system(job: Enrichments.Job): String = when (job) {
        Enrichments.Job.Email -> """
            You triage one email for a planner app used by $STUDENT. The planner turns each obligation into scheduled work and blocks distracting apps until it's done, so overstating the work costs the student free time and understating it lets work slip. An email the student sent themselves is a note of something they mean to do.

            Decide, from the email:
            - kind: "Admin" if it asks the student to do something (reply, sign, pay, fill in a form, bring or prepare something); "Event" if it's about something happening at a set time that needs nothing beyond turning up (a trip, a call, an open evening); "Info" if it only needs reading (newsletters, notifications, receipts, marketing, automatic messages, or a message forwarded or copied to the student that asks them nothing).
            - actionableFrom: the date (YYYY-MM-DD) before which nothing can be done, if the email says so (a form that opens next week); otherwise null.
            - deadline: when it must be done by, only if the email states or clearly implies one (a day it suggests for doing it counts); for an Event, when it starts; otherwise null. Never invent one. Write it as a local date and time (YYYY-MM-DDTHH:MM), or the date alone (YYYY-MM-DD) if it gives no time.
            - effortMin: the minutes of the student's own work it needs, reading included: about 2 for Info.
            - nextStep: the one concrete next action, as an instruction under 12 words ("Reply to Mr Hughes confirming the trip").
            - blocks: when the email holds more than one piece of work for the student, or dated items (a calendar of deadlines, an application that opens on a date), the pieces of work in order, each with its minutes (10 to 60 where the work allows: a long piece of work, like writing a proposal, is several blocks with the same dates; at most $MAX_BLOCKS blocks, longer ones if there's more work than that), from (the date before which it can't be done, or null) and due (when it must be done by, written as for deadline). A window the email gives only roughly ("applications open in December") runs from its first day to its last. A quick action with no date of its own that's best done soon (signing up, replying, asking someone) is due within the next two weeks. Leave due null only for a block that can wait for the email's own deadline. The planner schedules each block in its own window, so one email can become work spread over months, kept after the email is archived. An email with blocks is Admin, and its effortMin is their total. Otherwise an empty list.

            Work out relative dates ("next Friday") from when the email was received.
        """.trimIndent()
        Enrichments.Job.Assignment -> """
            You plan one homework assignment for $STUDENT, for a planner that schedules the work over the days before it's due and blocks distracting apps until it's done.

            Split the work into the ordered steps the student will actually do, each with a realistic duration in minutes for a typical Year 12 student (the planner adjusts them to this one). Keep steps between 10 and 60 minutes where the work allows, and a short task as one step. A step has dates of its own only where the instructions give them (a draft due before the final version): from, the date before which it can't be started, and due, when it must be done by (YYYY-MM-DDTHH:MM, or the date alone); otherwise both null. Base the steps on the instructions. Learning numbered vocabulary sections is a step of its own, with nothing else in it, and those sections in its ankiSections: the planner leaves it out where the student's Anki decks hold them. Every other step has an empty ankiSections. Add a step for handing work in only where the instructions ask for it, in its place in the order.

            Also give:
            - effortMin: the total minutes, the sum of the steps.
            - ankiSections: every vocabulary section the student is asked to learn, written like "1.2" (from "learn vocabulary 1.2" or "p46-47/ 2.2/2.3"); otherwise an empty list.
            - testDate: if the work prepares for a test or assessment on a stated date, that date (YYYY-MM-DD); otherwise null.
        """.trimIndent()
        Enrichments.Job.Effort -> """
            Estimate how many minutes of work $STUDENT needs to finish one item from their planner, from its title, class and details: a realistic total for a typical Year 12 student (the planner adjusts it to this one). A call, meeting or other appointment isn't work: give only the minutes needed to prepare for it, at least 1.

            If it's big enough to be worth doing in parts (revising for a test, a project), also split it into blocks: the ordered pieces of work, 10 to 60 minutes each (at most $MAX_BLOCKS blocks), adding up to effortMin, each with from (the date before which it can't be done, YYYY-MM-DD) and due (when it must be done by, YYYY-MM-DDTHH:MM or the date alone) where the details give them, otherwise null. Otherwise blocks is an empty list.
        """.trimIndent()
    }

    /** The schema for [job]'s answer, as the API takes it: every object closed, every field required, optional ones nullable. */
    fun schema(job: Enrichments.Job): Map<String, Any> = when (job) {
        Enrichments.Job.Email -> obj(
            "kind" to mapOf("type" to "string", "enum" to listOf("Admin", "Event", "Info")),
            "actionableFrom" to nullable(mapOf("type" to "string", "format" to "date")),
            "deadline" to nullable(LOCAL_TIME),
            "effortMin" to mapOf("type" to "integer"),
            "nextStep" to mapOf("type" to "string"),
            "blocks" to mapOf("type" to "array", "items" to block(vocabulary = false)),
        )
        Enrichments.Job.Assignment -> obj(
            "subSteps" to mapOf("type" to "array", "items" to block(vocabulary = true)),
            "effortMin" to mapOf("type" to "integer"),
            "ankiSections" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "testDate" to nullable(mapOf("type" to "string", "format" to "date")),
        )
        Enrichments.Job.Effort -> obj(
            "effortMin" to mapOf("type" to "integer"),
            "blocks" to mapOf("type" to "array", "items" to block(vocabulary = false)),
        )
    }

    private val LOCAL_TIME = mapOf("type" to "string", "description" to "Local date and time, YYYY-MM-DDTHH:MM, or the date alone, YYYY-MM-DD")

    /** One block (or step) of work: its own dates where it has them; an assignment's, its vocabulary sections too. */
    private fun block(vocabulary: Boolean): Map<String, Any> = obj(
        *listOfNotNull(
            "title" to mapOf("type" to "string"),
            "minutes" to mapOf("type" to "integer"),
            ("ankiSections" to mapOf("type" to "array", "items" to mapOf("type" to "string"))).takeIf { vocabulary },
            "from" to nullable(mapOf("type" to "string", "format" to "date")),
            "due" to nullable(LOCAL_TIME),
        ).toTypedArray(),
    )

    /** [job]'s schema as the JSON it's sent as. */
    fun schemaJson(job: Enrichments.Job): JsonElement = toJson(schema(job))

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJson(item) })
        is List<*> -> JsonArray(value.map(::toJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun obj(vararg properties: Pair<String, Any>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    private fun nullable(schema: Map<String, Any>): Map<String, Any> = mapOf("anyOf" to listOf(schema, mapOf("type" to "null")))

    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", Locale.UK)

    /** The item, as the model reads it. */
    fun describe(task: TaskItem, job: Enrichments.Job, now: Long, zone: ZoneId): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone)
        appendLine("Today: ${at(now).format(TODAY)}")
        when (job) {
            Enrichments.Job.Email -> {
                task.extra["received"]?.toLongOrNull()?.let { appendLine("Received: ${at(it).format(DAY_TIME)}") }
                if (task.extra["sent"] == "true") appendLine("From: the student (a note to self)") else task.extra["from"]?.let { appendLine("From: $it") }
                appendLine("Subject: ${task.title}")
            }
            else -> {
                appendLine("Title: ${task.title}")
                task.className?.let { appendLine("Class: $it") }
                // What the source says, not what an older enrichment laid over it.
                val due = if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt
                appendLine("Due: " + (due?.let { at(it).format(DAY_TIME) } ?: "no date"))
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
    private data class Triage(
        val kind: String,
        val actionableFrom: String? = null,
        val deadline: String? = null,
        val effortMin: Int,
        val nextStep: String,
        val blocks: List<Step> = emptyList(),
    )

    @Serializable
    private data class Step(val title: String, val minutes: Int, val ankiSections: List<String> = emptyList(), val from: String? = null, val due: String? = null)

    @Serializable
    private data class Split(val subSteps: List<Step>, val effortMin: Int, val ankiSections: List<String> = emptyList(), val testDate: String? = null)

    @Serializable
    private data class Estimate(val effortMin: Int, val blocks: List<Step> = emptyList())

    private const val MAX_EFFORT = 600

    /**
     * The most an email or planner item in blocks is taken to be: twenty hours, as one email can
     * hold months of applications (a calendar of five months' came to 13). Past it, an estimate
     * gone wrong.
     */
    private const val MAX_BLOCKS_EFFORT = 1_200
    private const val MAX_STEP = 240
    private const val MAX_STEPS = 12
    private const val MAX_TITLE = 80
    private const val MAX_NEXT_STEP = 120
    private const val STEPS_SLACK_MIN = 10
    private val SECTION = Regex("""^[1-9]\.[1-9]$""")

    /** [step] in even parts no longer than a focus session ([Focus.MAX_SESSION_MIN]), numbered if there's more than one. */
    private fun fitSessions(step: SubStep): List<SubStep> {
        val parts = (step.minutes + Focus.MAX_SESSION_MIN - 1) / Focus.MAX_SESSION_MIN
        if (parts <= 1) return listOf(step)
        val suffix = { i: Int -> " (${i + 1} of $parts)" }
        return (0 until parts).map { i ->
            step.copy(title = step.title.take(MAX_TITLE - suffix(i).length) + suffix(i), minutes = step.minutes / parts + if (i < step.minutes % parts) 1 else 0)
        }
    }

    /** A test's day: work must be done before school that morning. */
    private val SCHOOL_STARTS: LocalTime = LocalTime.of(8, 30)

    /** [text] as [job]'s answer, or null if it isn't one. */
    fun parse(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId): Enrichment? = runCatching {
        val base = Enrichment(inputHash = Enrichments.inputHash(task), by = by, at = now)
        when (job) {
            Enrichments.Job.Email -> json.decodeFromString(Triage.serializer(), text).let { t ->
                val total = effort(t.effortMin)
                val deadline = t.deadline?.let { dateTime(it, zone) }?.takeIf { plausible(it, now) }
                val opens = t.actionableFrom?.let { date(it, zone, LocalTime.MIDNIGHT) }?.takeIf { plausible(it, now) }
                // Blocks of work, each perhaps with its own dates (Q12): not trusted, the triage stands
                // without them.
                val blocks = trusted(steps(t.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = deadline ?: sourceDue(task), opens = opens), t.effortMin, t.blocks.size)
                base.copy(
                    // Blocks of work make it something to do, whatever else it says.
                    kind = if (blocks != null) Kind.Admin else Kind.entries.firstOrNull { it.name == t.kind && it in setOf(Kind.Admin, Kind.Event, Kind.Info) },
                    actionableFrom = opens,
                    deadline = deadline,
                    effortMin = blocks?.sumOf { it.minutes } ?: total,
                    nextStep = t.nextStep.trim().takeIf { it.isNotEmpty() }?.take(MAX_NEXT_STEP),
                    subSteps = blocks,
                )
            }
            Enrichments.Job.Assignment -> json.decodeFromString(Split.serializer(), text).let { s ->
                // One step it can't take (no title, minutes or a date out of range) and the split isn't
                // trusted: the task's whole estimate is planned instead, none of it lost with that step.
                val testDate = s.testDate?.let { date(it, zone, SCHOOL_STARTS) }?.takeIf { plausible(it, now) }
                // By the test, where there's one before the source's deadline: it's the task's then.
                val read = steps(s.subSteps, now, zone, vocabulary = true, maxMinutes = MAX_STEP, dueBy = listOfNotNull(sourceDue(task), testDate).minOrNull())
                val usable = read != null
                val valid = read.orEmpty()
                // More steps than the planner takes: the rest folded, so none of the work goes (see [fold]).
                val steps = if (valid.size <= MAX_STEPS) valid else valid.take(MAX_STEPS - 1) + fold(valid.drop(MAX_STEPS - 1))
                val sections = sections(s.ankiSections + steps.flatMap { it.ankiSections })
                // All vocabulary (no steps, its sections named): a step for it all the same, at the
                // task's own estimate. Where decks hold those sections the planner leaves it out, and
                // handing in is what's left; where none does (no such deck yet), it's planned.
                val allVocabulary = usable && steps.isEmpty() && sections.isNotEmpty()
                val planned = if (allVocabulary) {
                    listOf(SubStep(("Learn vocabulary " + sections.joinToString("/")).take(MAX_TITLE), task.copy(aiEffortMin = null).effortMin, ankiSections = sections))
                } else {
                    steps
                }
                // Steps that don't add up to the total (within a tenth, or ten minutes): one of the two
                // is wrong, and planning the steps could lose work, so the total is planned instead.
                // All vocabulary, the model's total is the hand-in alone: the step's estimate stands.
                val total = effort(s.effortMin).takeUnless { allVocabulary }
                val sum = planned.sumOf { it.minutes }
                // A total past the most one task is taken to be isn't one to plan by, nor are steps
                // adding up past it: the task's own estimate is planned instead.
                val inRange = sum <= MAX_EFFORT && (allVocabulary || s.effortMin <= MAX_EFFORT)
                val agree = inRange && (total == null || abs(sum - total) <= maxOf(STEPS_SLACK_MIN, total / 10))
                base.copy(
                    effortMin = total ?: sum.takeIf { agree && it > 0 },
                    subSteps = planned.takeIf { agree }.orEmpty().ifEmpty { null },
                    // A split not trusted hands no vocabulary to a deck either: its total, planned
                    // whole, already holds it.
                    ankiSections = if (usable && agree) sections else emptyList(),
                    testDate = testDate,
                )
            }
            Enrichments.Job.Effort -> json.decodeFromString(Estimate.serializer(), text).let { e ->
                val total = effort(e.effortMin)
                // Big enough to do in parts: its blocks, each perhaps with its own dates (Q12).
                val blocks = trusted(steps(e.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = sourceDue(task)), e.effortMin, e.blocks.size)
                base.copy(effortMin = blocks?.sumOf { it.minutes } ?: total, subSteps = blocks)
            }
        }
    }.getOrNull()

    /**
     * [given] as steps the planner can take, or null if any can't be: no title, minutes out of
     * range (up to [maxMinutes]), a date out of range, a start after its own deadline or after its
     * task's ([dueBy]), a deadline before its task can be started ([opens]), or an order its dates
     * forbid (a step that can't start till after one listed later is due). Each keeps its own dates; one
     * longer than a focus session is cut into parts that keep them, so finishing a session never
     * ticks off more than it did. A step tagged as vocabulary ([vocabulary] jobs only) that asks for
     * anything else too stays planned whole.
     */
    private fun steps(given: List<Step>, now: Long, zone: ZoneId, vocabulary: Boolean, maxMinutes: Int, dueBy: Long?, opens: Long? = null): List<SubStep>? {
        val read = given.map { step ->
            if (step.title.isBlank() || step.minutes !in 1..maxMinutes) return null
            val from = step.from?.let { date(it, zone, LocalTime.MIDNIGHT)?.takeIf { at -> plausible(at, now) } ?: return null }
            val due = step.due?.let { dateTime(it, zone)?.takeIf { at -> plausible(at, now) } ?: return null }
            if (from != null && ((due != null && from > due) || (dueBy != null && from > dueBy))) return null
            if (due != null && opens != null && due < opens) return null
            val sections = if (vocabulary && AnkiRules.vocabularyOnly(step.title)) sections(step.ankiSections) else emptyList()
            SubStep(step.title.trim().take(MAX_TITLE), step.minutes, ankiSections = sections, from = from, dueAt = due)
        }
        for (i in read.indices) {
            val from = read[i].from ?: continue
            if (read.drop(i + 1).any { later -> later.dueAt != null && later.dueAt < from }) return null
        }
        return read.flatMap(::fitSessions)
    }

    /**
     * An email's or planner item's [blocks], if they can be planned: some, no more than
     * [Prompts.MAX_BLOCKS] as [given] (before a long one was cut into parts that fit a session),
     * within the most a task in blocks is taken to be ([MAX_BLOCKS_EFFORT]), and adding up to its
     * [total] (within a tenth, or ten minutes), which has to be within it too. Otherwise null, and
     * its estimate is planned whole.
     */
    private fun trusted(blocks: List<SubStep>?, total: Int, given: Int): List<SubStep>? {
        if (blocks.isNullOrEmpty() || given > Prompts.MAX_BLOCKS || total !in 1..MAX_BLOCKS_EFFORT) return null
        val sum = blocks.sumOf { it.minutes }
        if (sum > MAX_BLOCKS_EFFORT || abs(sum - total) > maxOf(STEPS_SLACK_MIN, total / 10)) return null
        return blocks
    }

    /**
     * [tail], the steps past what the planner takes, with each run of plain steps made one ("The
     * rest: …") in its place. Vocabulary (its decks may hold it) and a step with dates of its own
     * keep a step of their own, between the runs, so no step moves ahead of one before it.
     */
    private fun fold(tail: List<SubStep>): List<SubStep> {
        val out = mutableListOf<SubStep>()
        val run = mutableListOf<SubStep>()
        fun flush() {
            when (run.size) {
                0 -> Unit
                1 -> out += run.single()
                else -> out += SubStep(("The rest: " + run.joinToString("; ") { it.title }).take(MAX_TITLE), run.sumOf { it.minutes })
            }
            run.clear()
        }
        for (step in tail) {
            if (step.ankiSections.isNotEmpty() || step.from != null || step.dueAt != null) {
                flush()
                out += step
            } else {
                run += step
            }
        }
        flush()
        return out
    }

    /** What [task]'s source says it's due by, not what an older enrichment laid over it. */
    private fun sourceDue(task: TaskItem): Long? = if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt

    private fun effort(minutes: Int): Int? = minutes.takeIf { it in 1..MAX_EFFORT }

    private fun sections(given: List<String>): List<String> = given.map { it.trim() }.filter { SECTION.matches(it) }.distinct()

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
