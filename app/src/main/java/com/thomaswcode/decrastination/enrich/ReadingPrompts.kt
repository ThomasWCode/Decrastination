package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the model is asked (docs/spec/readings.md), one job at a time: the instructions, the
 * item, and the JSON schema its answer must follow. Pure, so the wording and the schemas are
 * tested and can be tried out without the API.
 */
object ReadingPrompts {

    /**
     * The prompts' version (AC-06): raised with every change to a system prompt, a schema, an
     * example or the profile's shape, so a reading the model made by older ones is made again.
     * 1: everything before review v2's phase 8 (what the readings stored then carry); 2: phases 1
     * to 7's changes (PROGRESS.md listed them); 3: every starred email a task (3.0.1); 4: fewer,
     * longer steps (3.1.1, your call, 17 Oct: a task in parts only where it is, its few minutes'
     * admin in the step it belongs to); 5: a piece of work whole, never split by what's in it, the
     * app cutting one over an hour into sittings (3.1.2, the same day); 6: whole up to a task's most,
     * the schema saying so (3.1.2's review, before it was installed); 7: a long text's summary, for
     * Felix (3.2.0, D-93); 8: the profile saying how long a passing fact or rule holds (4.2.0, D-103).
     */
    const val VERSION = 8

    /**
     * Text past this is left out (and the model told). Room for a long email's own calendar of
     * deadlines (one seen ran to 8 500 characters); past it, a newsletter's tail, which doesn't
     * change its triage.
     */
    const val MAX_TEXT = 16_000

    /** Blocks an email or a planner item can come back in: one email can hold months of applications. */
    const val MAX_BLOCKS = 30

    /** Who the prompts are for, by year group (AI-06). */
    private fun student(year: Int) = "a UK sixth-form student (Year $year)"

    /** [job]'s system prompt: what it's to do, the limits its answer is checked against (AI-03), and worked examples (AI-07). */
    fun system(job: Enrichments.Job, year: Int = 12): String = brief(job, year) + "\n\n" + limits(job) + "\n\n" + Examples.text(job)

    /**
     * The limits the app checks [job]'s answer against (AI-03), said in its prompt from the checks'
     * own values, so the model keeps to them rather than finding them out.
     */
    fun limits(job: Enrichments.Job): String = when (job) {
        Enrichments.Job.Assignment ->
            "Limits the app enforces: at most ${ReadingAnswers.MAX_STEPS} steps; a total of at most ${ReadingAnswers.MAX_EFFORT / 60} hours; the steps must add up to effortMin exactly."
        else ->
            "Limits the app enforces: without blocks, effortMin at most ${ReadingAnswers.MAX_EFFORT / 60} hours; at most $MAX_BLOCKS blocks, none longer than ${ReadingAnswers.MAX_EFFORT} minutes, ${ReadingAnswers.MAX_BLOCKS_EFFORT / 60} hours in all, adding up to effortMin exactly."
    }

    private fun brief(job: Enrichments.Job, year: Int): String = when (job) {
        Enrichments.Job.Email -> """
            You triage one email for a planner app used by ${student(year)}. The planner turns each obligation into scheduled work and blocks distracting apps until it's done, so overstating the work costs the student free time and understating it lets work slip. Every email you see was starred by the student: it is something to do. An email the student sent themselves is a note of something they mean to do.

            Decide, from the email:
            - kind: always "Admin": the task is what the email asks of the student (reply, sign, pay, fill in a form, bring or prepare something); for information they starred to come back to, to read it and decide (nextStep says what to decide); for something happening at a set time (a trip, a call, an open evening), what it needs before then (put it in the calendar, reply, get something ready).
            - actionableFrom: the date (YYYY-MM-DD) before which nothing can be done, if the email says so (a form that opens next week); otherwise null.
            - deadline: when it must be done by, only if the email states or clearly implies one (a day it suggests for doing it counts); for something happening at a set time, when it starts, unless the email asks for something sooner; otherwise null. Never invent one: a quick action with no date of its own (signing up, replying, asking someone) has deadline null, and the app plans it within the week. Write it as a local date and time (YYYY-MM-DDTHH:MM), or the date alone (YYYY-MM-DD) if it gives no time.
            - deadlineQuoted: true when deadline is a date the email itself gives or clearly implies as when it must be done by, or the start of what it's about; false when it's only a day the email suggests for doing it, and when deadline is null. Only a quoted deadline is shown as missed once it passes; any other date is a plan, moved when it passes.
            - effortMin: the minutes of the student's own work it needs, reading included: at least 5 for something only to read and decide.
            - nextStep: the one concrete next action, as an instruction under 12 words ("Reply to Mr Hughes confirming the trip").
            - question: the one question that would settle a reading the text can't (which Friday; whether the test is the 12th or the 19th), or what the student means to do about it when the email doesn't make that clear; otherwise null.
            - reason: the phrase in the email that gave the deadline or the task, quoted (under 160 characters); empty if nothing did.
            - summary: $EMAIL_SUMMARY
            - blocks: only when the email holds separate pieces of work with dates of their own (a calendar of deadlines, an application that opens on a date): the pieces of work in order, each with its minutes (at most $MAX_BLOCKS blocks, longer ones if there's more work than that), from (the date before which it can't be done, or null) and due (when it must be done by, written as for deadline, only where the email gives one; otherwise null, and the block waits for the email's own deadline, or with none is planned within the week). A window the email gives only roughly ("applications open in December") runs from its first day to its last. The planner schedules each block in its own window, so one email can become work spread over months, kept after the email is archived. An email with blocks is Admin, and its effortMin is their total. One job (filling in a form, writing a proposal, putting a video on a website), however long or however many small actions it takes, has no blocks: the app cuts long work into sittings itself, as it can't know how long each part of it takes. A few minutes' admin around the work (printing, uploading, replying to confirm) is never a block of its own: say it in nextStep. Otherwise an empty list.

            You see the conversation the starred message is in, oldest message first (where a long one's earlier messages had to be cut short or left out, it says so); when there's more than one, each is headed by who sent it and when ("From you" for the student's own). The first message shown keeps what it quotes from earlier (cut short too, where it had to be); in the ones after it, quoted text and signatures are left out and marked in square brackets, as what they quote is shown above them. You don't see attachments, only their names (an attached email goes by its subject). Where what the email asks turns on an attachment or an earlier message you can't see, and its name doesn't tell you, don't guess what it asked: make nextStep say to read it first, naming it ("Read the attached consent form and decide"), and effortMin 10.

            Work out relative dates ("next Friday") from when the message saying them was sent.
        """.trimIndent()
        Enrichments.Job.Assignment -> """
            You plan one homework assignment for ${student(year)}, for a planner that schedules the work over the days before it's due and blocks distracting apps until it's done.

            Give the work as the ordered steps the student will actually do, as few as it really has, each with a realistic duration in minutes for a typical Year 12 student (the planner adjusts them to this one). An assignment is one step, the whole of it, unless its instructions list separate pieces of work (questions and an essay; vocabulary to learn and a reading task). Never split one piece of work by what's in it (pages 35-39, then 40-43; the first third of the questions; research, then write): give it whole, however long, as the app can't know how long each part takes, and cuts a step over 60 minutes into sittings itself (the first the work, the last "Continue and finish"). A run of pages, questions or exercises is one step ("Questions on pages 28-30"), never a step each. Never make a step of a few minutes around the work (printing or downloading it, reading the task, marking it against given answers, photographing or uploading it, handing it in): say it in the title of the step it belongs to ("Past paper 1 (print it first), then mark it"). A step has dates of its own only where the instructions give them (a draft due before the final version): from, the date before which it can't be started, and due, when it must be done by (YYYY-MM-DDTHH:MM, or the date alone); otherwise both null. Base the steps on the instructions. Learning numbered vocabulary sections is a step of its own, with nothing else in it, and those sections in its ankiSections: the planner leaves it out where the student's Anki decks hold them. Every other step has an empty ankiSections. Handing the work in is part of its last step, never a step of its own. No step may start after the assignment's due time. Work that comes after the hand-in (revising for a test the work prepares for) gets no dates of its own; give the test's day in testDate instead. Each step's where: "home" for a step that needs the laptop, books or quiet (an essay, an upload, a long problem set); "anywhere" for one that can be done at school in a free period with what's in the bag (a booklet, reading, vocabulary).

            Also give:
            - effortMin: the total minutes, the sum of the steps.
            - ankiSections: every vocabulary section the student is asked to learn, written like "1.2" (from "learn vocabulary 1.2" or "p46-47/ 2.2/2.3"); otherwise an empty list.
            - testDate: if the work prepares for a test or assessment on a stated date, that date (YYYY-MM-DD); otherwise null.
            - question: rarely: the one question that would settle a reading the text can't (which Friday; whether the test is the 12th or the 19th); otherwise null.
            - reason: the phrase in the instructions that gave the test date or the split, quoted (under 160 characters); empty if nothing did.
            - summary: $TEXT_SUMMARY
        """.trimIndent()
        Enrichments.Job.Effort -> """
            Estimate how many minutes of work ${student(year)} needs to finish one item from their planner, from its title, class and details: a realistic total for a typical Year 12 student (the planner adjusts it to this one). A call, meeting or other appointment isn't work: give only the minutes needed to prepare for it, at least 1.

            Give blocks only where its details list pieces of work with dates of their own: the ordered pieces, each with its minutes (at most $MAX_BLOCKS blocks), adding up to effortMin, each with from (the date before which it can't be done, YYYY-MM-DD) and due (when it must be done by, YYYY-MM-DDTHH:MM or the date alone) where the details give them, otherwise null. No block may start after the item's date. Otherwise blocks is an empty list, however long the work is (revising for a test, a project): the app cuts long work into sittings itself, as it can't know how long each part of it takes.

            Also give summary: $TEXT_SUMMARY
        """.trimIndent()
    }

    /**
     * What a reading's [Enrichment.summary] is (D-93): from all of a long text, for Felix, who's told
     * it in place of the text where it's too long for his room; empty for a short one.
     */
    private const val EMAIL_SUMMARY = "only where the conversation runs past a short paragraph (about 600 characters), what it asks of the student, from all of it, in at most 400 characters: what to do and by when, what to bring, send or pay, and anything a later message changed (the newest message wins). The student's assistant in the app is told it in place of the conversation. Otherwise empty."
    private const val TEXT_SUMMARY = "only where the text runs past a short paragraph (about 600 characters), what it asks of the student, from all of it, in at most 400 characters: the work and its parts, what's needed, any dates, how it's handed in. The student's assistant in the app is told it in place of the text. Otherwise empty."

    /** The schema for [job]'s answer, as the API takes it: every object closed, every field required, optional ones nullable. */
    fun schema(job: Enrichments.Job): Map<String, Any> = when (job) {
        Enrichments.Job.Email -> obj(
            "kind" to mapOf("type" to "string", "enum" to listOf("Admin")),
            "actionableFrom" to nullable(mapOf("type" to "string", "format" to "date")),
            "deadline" to nullable(LOCAL_TIME),
            "deadlineQuoted" to mapOf("type" to "boolean"),
            "effortMin" to described(mapOf("type" to "integer"), "The minutes in all; with blocks, their sum"),
            "nextStep" to mapOf("type" to "string"),
            "question" to nullable(mapOf("type" to "string")),
            "reason" to described(mapOf("type" to "string"), REASON),
            "blocks" to mapOf("type" to "array", "items" to block(vocabulary = false)),
            "summary" to described(mapOf("type" to "string"), SUMMARY),
        )
        Enrichments.Job.Assignment -> obj(
            "subSteps" to mapOf("type" to "array", "items" to block(vocabulary = true)),
            "effortMin" to described(mapOf("type" to "integer"), "The sum of the steps' minutes"),
            "ankiSections" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "testDate" to nullable(mapOf("type" to "string", "format" to "date")),
            "question" to nullable(mapOf("type" to "string")),
            "reason" to described(mapOf("type" to "string"), REASON),
            "summary" to described(mapOf("type" to "string"), SUMMARY),
        )
        Enrichments.Job.Effort -> obj(
            "effortMin" to described(mapOf("type" to "integer"), "The minutes in all; with blocks, their sum"),
            "blocks" to mapOf("type" to "array", "items" to block(vocabulary = false)),
            "summary" to described(mapOf("type" to "string"), SUMMARY),
        )
    }

    /** A reading's [Enrichment.summary], as its schema says it. */
    private const val SUMMARY = "What a text longer than about 600 characters asks of the student, from all of it, in at most 400 characters; empty for a shorter one"

    private val LOCAL_TIME = mapOf("type" to "string", "description" to "Local date and time, YYYY-MM-DDTHH:MM, or the date alone, YYYY-MM-DD")

    /** What a reading's [Enrichment.reason] is (AI-09). */
    private const val REASON = "The phrase in the text that gave the deadline, kind or test date, quoted, under 160 characters; empty if none did"

    /**
     * One block (or step) of work: its own dates where it has them; an assignment's, its vocabulary
     * sections too. Each field says what it's for and its limit (AI-20), as the checks have it.
     */
    private fun block(vocabulary: Boolean): Map<String, Any> = obj(
        *listOfNotNull(
            "title" to mapOf("type" to "string"),
            "minutes" to described(mapOf("type" to "integer"), "All its work, however long (the app cuts one over ${ReadingAnswers.MAX_PART_MIN} minutes into sittings itself); never over ${ReadingAnswers.MAX_EFFORT}"),
            ("ankiSections" to described(mapOf("type" to "array", "items" to mapOf("type" to "string")), "Only for a step that is nothing but learning numbered vocabulary sections")).takeIf { vocabulary },
            // An assignment's steps say where they can be done (PL-15).
            ("where" to described(mapOf("type" to "string", "enum" to listOf("anywhere", "home")), "home for a step that needs the laptop, books or quiet; anywhere for one that can be done at school in a free period")).takeIf { vocabulary },
            "from" to described(nullable(mapOf("type" to "string", "format" to "date")), "The date before which this ${if (vocabulary) "step" else "block"} can't be done, only if the text gives one; never after the task's due date"),
            "due" to nullable(LOCAL_TIME),
        ).toTypedArray(),
    )

    /** [schema] with what it's for said to the model. */
    private fun described(schema: Map<String, Any>, text: String): Map<String, Any> = schema + ("description" to text)

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

    /** Said before the item when the student photographed the work (AI-23). */
    fun photo(job: Enrichments.Job): String = "The student photographed the work itself; estimate and split from what the photo shows" +
        if (job == Enrichments.Job.Effort) "." else ", and say in reason what you saw."

    /** What's said to the model of its answer's [complaint], asked once more (AI-01). */
    fun repair(complaint: String): String = "Your answer had a problem: $complaint. Return the whole answer again with only that put right."

    /**
     * The item, as the model reads it; with [previous], its steps as planned before (AI-08), so a
     * step whose work hasn't changed keeps its title, and with it its tick; one done said to be, to
     * be kept as it is (#47's review), as fewer, longer steps would otherwise take its work in.
     */
    fun describe(task: TaskItem, job: Enrichments.Job, now: Long, zone: ZoneId, previous: List<SubStep> = emptyList(), decks: Map<String, String> = emptyMap()): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone)
        appendLine("Today: ${at(now).format(TODAY)}")
        when (job) {
            Enrichments.Job.Email -> {
                task.extra["received"]?.toLongOrNull()?.let { appendLine("Received: ${at(it).format(DAY_TIME)}") }
                if (task.extra["sent"] == "true") appendLine("From: the student (a note to self)") else task.extra["from"]?.let { appendLine("From: $it") }
                appendLine("Subject: ${task.title}")
                // A conversation is read whole (DX-03), and the model told so; attachments it can't
                // see are named (DX-01, AI-04).
                task.extra[GmailThreads.EXTRA_MESSAGES]?.toIntOrNull()?.takeIf { it > 1 }?.let { appendLine("Messages in this conversation: $it, oldest first") }
                GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> appendLine("Attachments: " + files.joinToString(", ") { it.label }) }
            }
            else -> {
                appendLine("Title: ${task.title}")
                task.className?.let { appendLine("Class: $it") }
                // What the source says, not what an older enrichment laid over it.
                val due = task.sourceValues.let { if (it != null) it.dueAt else task.dueAt }
                appendLine("Due: " + (due?.let { at(it).format(DAY_TIME) } ?: "no date"))
                // The vocabulary decks by topic (DX-10), so a section can be named by what it's about.
                if (job == Enrichments.Job.Assignment && decks.isNotEmpty()) appendLine("Decks: " + decks.entries.sortedBy { it.key }.joinToString("; ") { "${it.key} ${it.value}" })
                // The files the teacher attached, by name (DX-08): what "the booklet" is.
                if (job == Enrichments.Job.Assignment) GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> appendLine("Attachments: " + files.joinToString(", ") { it.label }) }
                if (job == Enrichments.Job.Assignment) com.thomaswcode.decrastination.sources.teams.TeamsRows.linksOf(task).takeIf { it.isNotEmpty() }?.let { appendLine("Links: " + it.joinToString(", ")) }
            }
        }
        // What the student said to its questions (AI-10).
        task.answers.forEach { appendLine("The student says: $it") }
        if (previous.isNotEmpty()) {
            val noun = if (job == Enrichments.Job.Assignment) "step" else "block"
            val done = if (previous.any { it.done }) "; a $noun marked done is done: keep it as it is, its exact title and minutes (still counted in effortMin), and give only the work left in the others" else "; mark none as done"
            appendLine("${noun.replaceFirstChar(Char::uppercase)}s as planned before (keep the exact title of any $noun whose work is unchanged$done):")
            previous.forEachIndexed { i, step -> appendLine("${i + 1}. ${step.title} (${step.minutes} min${if (step.done) ", done" else ""})") }
        }
        val text = task.detail.trim()
        if (text.isNotEmpty()) {
            appendLine()
            appendLine(if (job == Enrichments.Job.Email) "Email:" else "Details:")
            if (text.length <= MAX_TEXT) append(text) else append(text.take(MAX_TEXT)).append("\n[The rest of this long text is left out.]")
        }
    }.trimEnd()
}
