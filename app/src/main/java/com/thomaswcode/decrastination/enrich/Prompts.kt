package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Where
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
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
     * The prompts' version (AC-06): raised with every change to a system prompt, a schema, an
     * example or the profile's shape, so a reading the model made by older ones is made again.
     * 1: everything before review v2's phase 8 (what the readings stored then carry); 2: phases 1
     * to 7's changes (PROGRESS.md listed them); 3: every starred email a task (3.0.1); 4: fewer,
     * longer steps (3.1.1, your call, 17 Oct: a task in parts only where it is, its few minutes'
     * admin in the step it belongs to); 5: a piece of work whole, never split by what's in it, the
     * app cutting one over an hour into sittings (3.1.2, the same day); 6: whole up to a task's most,
     * the schema saying so (3.1.2's review, before it was installed).
     */
    const val VERSION = 6

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
            "Limits the app enforces: at most ${Answers.MAX_STEPS} steps; a total of at most ${Answers.MAX_EFFORT / 60} hours; the steps must add up to effortMin exactly."
        else ->
            "Limits the app enforces: without blocks, effortMin at most ${Answers.MAX_EFFORT / 60} hours; at most $MAX_BLOCKS blocks, none longer than ${Answers.MAX_EFFORT} minutes, ${Answers.MAX_BLOCKS_EFFORT / 60} hours in all, adding up to effortMin exactly."
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
        """.trimIndent()
        Enrichments.Job.Effort -> """
            Estimate how many minutes of work ${student(year)} needs to finish one item from their planner, from its title, class and details: a realistic total for a typical Year 12 student (the planner adjusts it to this one). A call, meeting or other appointment isn't work: give only the minutes needed to prepare for it, at least 1.

            Give blocks only where its details list pieces of work with dates of their own: the ordered pieces, each with its minutes (at most $MAX_BLOCKS blocks), adding up to effortMin, each with from (the date before which it can't be done, YYYY-MM-DD) and due (when it must be done by, YYYY-MM-DDTHH:MM or the date alone) where the details give them, otherwise null. No block may start after the item's date. Otherwise blocks is an empty list, however long the work is (revising for a test, a project): the app cuts long work into sittings itself, as it can't know how long each part of it takes.
        """.trimIndent()
    }

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
        )
        Enrichments.Job.Assignment -> obj(
            "subSteps" to mapOf("type" to "array", "items" to block(vocabulary = true)),
            "effortMin" to described(mapOf("type" to "integer"), "The sum of the steps' minutes"),
            "ankiSections" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "testDate" to nullable(mapOf("type" to "string", "format" to "date")),
            "question" to nullable(mapOf("type" to "string")),
            "reason" to described(mapOf("type" to "string"), REASON),
        )
        Enrichments.Job.Effort -> obj(
            "effortMin" to described(mapOf("type" to "integer"), "The minutes in all; with blocks, their sum"),
            "blocks" to mapOf("type" to "array", "items" to block(vocabulary = false)),
        )
    }

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
            "minutes" to described(mapOf("type" to "integer"), "All its work, however long (the app cuts one over ${Answers.MAX_PART_MIN} minutes into sittings itself); never over ${Answers.MAX_EFFORT}"),
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
                val due = if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt
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

/** The model's answers, read and checked: anything out of range is dropped rather than trusted. */
object Answers {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * An answer read (AI-01): its enrichment (null if it isn't one), and the check it failed, said
     * so the model can put it right ([Prompts.repair]); null when it passed them all.
     */
    data class Parsed(val enrichment: Enrichment?, val complaint: String? = null)

    @Serializable
    private data class Triage(
        val kind: String,
        val actionableFrom: String? = null,
        val deadline: String? = null,
        /** Whether [deadline] is the email's own words (AI-12): missing, it isn't, so it's never taken as hard. */
        val deadlineQuoted: Boolean = false,
        val effortMin: Int,
        val nextStep: String,
        val question: String? = null,
        val reason: String = "",
        val blocks: List<Step> = emptyList(),
    )

    @Serializable
    private data class Step(val title: String, val minutes: Int, val ankiSections: List<String> = emptyList(), val from: String? = null, val due: String? = null, val where: String? = null)

    @Serializable
    private data class Split(val subSteps: List<Step>, val effortMin: Int, val ankiSections: List<String> = emptyList(), val testDate: String? = null, val question: String? = null, val reason: String = "")

    @Serializable
    private data class Estimate(val effortMin: Int, val blocks: List<Step> = emptyList())

    internal const val MAX_EFFORT = 600

    /** The longest question put to you. */
    private const val MAX_QUESTION = 200

    private fun question(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUESTION)

    /** The longest phrase a reading's reason quotes (AI-09). */
    private const val MAX_REASON = 160

    private fun reason(text: String): String? = text.trim().trim('"', '“', '”').trim().takeIf { it.isNotEmpty() }?.take(MAX_REASON)

    /**
     * The Email job's kinds (AI-02): Admin, as a starred email is always something to do (16 Oct);
     * an older answer's Event is read as one.
     */
    private val EMAIL_KINDS = mapOf("Admin" to Kind.Admin, "Event" to Kind.Admin)

    /**
     * The most an email or planner item in blocks is taken to be: twenty hours, as one email can
     * hold months of applications (a calendar of five months' came to 13). Past it, an estimate
     * gone wrong.
     */
    internal const val MAX_BLOCKS_EFFORT = 1_200

    /** The most steps an assignment's reading gives: past it, the rest are folded ([fold]). Its sittings may be more. */
    internal const val MAX_STEPS = 12
    private const val MAX_TITLE = 80
    private const val MAX_NEXT_STEP = 120
    private const val STEPS_SLACK_MIN = 10

    /** The most of a date that can't be read put back to the model. */
    private const val MAX_DATE_SAID = 30
    private val SECTION = Regex("""^[1-9]\.[1-9]$""")

    /**
     * The model's steps or blocks as checked: those the planner can take, or none, and then why not;
     * and the lines saying whose dates were left out to keep them ([adjusted], AI-01).
     */
    private class Checked(val steps: List<SubStep>?, val dropped: String? = null, val adjusted: List<String> = emptyList())

    /** [minutes] in hours, for a reason given: "21 hours", "20.8 hours". */
    private fun hours(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60} hours" else "%.1f hours".format(Locale.UK, minutes / 60.0)

    /**
     * The longest a step is planned in one sitting (your call, 17 Oct: an hour; three before): a
     * longer one is cut into even sittings, as the app can't know which part of the work takes how
     * long, so they're split by time, never by what's in them ([fitParts]).
     */
    const val MAX_PART_MIN = 60

    /**
     * [step] in even sittings no longer than [MAX_PART_MIN]: the first the step itself, then
     * "Continue: …", and the last "Continue and finish: …".
     */
    internal fun fitParts(step: SubStep): List<SubStep> {
        val parts = (step.minutes + MAX_PART_MIN - 1) / MAX_PART_MIN
        if (parts <= 1) return listOf(step)
        fun title(i: Int): String {
            val prefix = when (i) {
                0 -> return step.title
                parts - 1 -> "Continue and finish: "
                else -> "Continue: "
            }
            return prefix + step.title.take(MAX_TITLE - prefix.length)
        }
        return (0 until parts).map { i -> step.copy(title = title(i), minutes = step.minutes / parts + if (i < step.minutes % parts) 1 else 0) }
    }

    /**
     * [steps] in sittings ([fitParts]), each part keeping its step's dates, so ticking a piece never
     * ticks off more than it did; but not one that takes the mark of a step of [before] done
     * ([com.thomaswcode.decrastination.core.carryMarks]): done, it isn't planned, and cut, the parts
     * after its first would lose the mark (#48's review).
     */
    internal fun sittings(steps: List<SubStep>, before: List<SubStep>): List<SubStep> =
        com.thomaswcode.decrastination.core.carryMarks(steps, before).zip(steps).flatMap { (marked, step) -> if (marked.done) listOf(step) else fitParts(step) }

    /** A test's day: work must be done before school that morning. */
    private val SCHOOL_STARTS: LocalTime = LocalTime.of(8, 30)

    /** [text] as [job]'s answer, or null if it isn't one: a step's dates that can't be left out ([read] with salvage). */
    fun parse(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId): Enrichment? = read(job, text, task, by, now, zone, salvage = true).enrichment

    /**
     * [text] as [job]'s answer (AI-01). Not [salvage] (the first time of asking), any check failed
     * is its [Parsed.complaint], to put back to the model; with it (asked again, or not asked), a
     * step or block whose dates can't be (a start after it's due, a start or due after its task is
     * due) keeps its place and minutes with none, said in [Enrichment.adjusted], and the rest still
     * fail it.
     */
    fun read(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId, salvage: Boolean): Parsed {
        // What the checks couldn't take but needn't drop the answer for: a date that can't be read,
        // a kind that isn't one: said, so the answer's put back to be put right (AI-01).
        val problems = mutableListOf<String>()
        return runCatching {
            val made = reading(job, text, task, by, now, zone, salvage, problems)
            Parsed(made, (listOfNotNull(made?.dropped) + problems).firstOrNull()?.replaceFirstChar(Char::uppercase))
        }.getOrElse { Parsed(null, "The answer isn't JSON the schema takes (${it.message?.lineSequence()?.firstOrNull()?.take(120)})") }
    }

    private fun reading(job: Enrichments.Job, text: String, task: TaskItem, by: String, now: Long, zone: ZoneId, salvage: Boolean, problems: MutableList<String>): Enrichment? {
        val base = Enrichment(inputHash = Enrichments.inputHash(task), by = by, at = now, promptVersion = Prompts.VERSION)
        return when (job) {
            Enrichments.Job.Email -> json.decodeFromString(Triage.serializer(), text).let { t ->
                // Starred, an email is always something to do (AI-02, 16 Oct): Admin. Any other kind is
                // no answer at all, and the rules' reading stands.
                val kind = EMAIL_KINDS[t.kind] ?: return null.also { problems += "kind is \"${t.kind.take(20)}\": it must be Admin" }
                val total = effort(t.effortMin)
                if (total == null && t.blocks.isEmpty()) problems += "effortMin is ${t.effortMin}, not from 1 to $MAX_EFFORT minutes"
                val deadline = checkedTime("deadline", t.deadline, now, problems) { dateTime(it, zone) }
                val opens = checkedTime("actionableFrom", t.actionableFrom, now, problems) { date(it, zone, LocalTime.MIDNIGHT) }
                // Blocks of work, each perhaps with its own dates (Q12): not trusted, the triage stands
                // without them, and the task says why.
                val checked = trusted(steps(t.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = deadline ?: sourceDue(task), opens = opens, noun = "block", salvage = salvage), t.effortMin, t.blocks.size)
                val blocks = checked.steps?.let { sittings(it, task.subSteps) }
                base.copy(
                    // Blocks of work make it something to do, whatever else it says: the ones given,
                    // trusted or not. Dropped, its whole estimate is planned instead, and its warning
                    // shows; an email hidden as an event would hide both (BUG-P2-004).
                    kind = if (t.blocks.isNotEmpty()) Kind.Admin else kind,
                    actionableFrom = opens,
                    deadline = deadline,
                    // Quoted only of a deadline kept: one dropped as out of range quotes nothing.
                    deadlineQuoted = t.deadlineQuoted && deadline != null,
                    effortMin = blocks?.sumOf { it.minutes } ?: total,
                    nextStep = t.nextStep.trim().takeIf { it.isNotEmpty() }?.take(MAX_NEXT_STEP),
                    subSteps = blocks,
                    dropped = checked.dropped,
                    adjusted = checked.adjusted,
                    question = question(t.question),
                    reason = reason(t.reason),
                )
            }
            Enrichments.Job.Assignment -> json.decodeFromString(Split.serializer(), text).let { s ->
                // One step it can't take (no title, minutes or a date out of range) and the split isn't
                // trusted: the task's whole estimate is planned instead, none of it lost with that step.
                val testDate = checkedTime("testDate", s.testDate, now, problems) { date(it, zone, SCHOOL_STARTS) }
                // By the test, where there's one before the source's deadline: it's the task's then.
                val read = steps(s.subSteps, now, zone, vocabulary = true, maxMinutes = MAX_EFFORT, dueBy = listOfNotNull(sourceDue(task), testDate).minOrNull(), salvage = salvage)
                val usable = read.steps != null
                val valid = read.steps.orEmpty()
                // More steps than the model may give: the rest folded, so none of the work goes (see
                // [fold]); cut into sittings only after, a fold too ([sittings]).
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
                // Steps given but not planned, and why: said on the task, so it can be checked.
                val dropped = when {
                    !usable -> read.dropped
                    agree || planned.isEmpty() -> null
                    sum > MAX_EFFORT -> "the steps come to ${hours(sum)}, over the ${MAX_EFFORT / 60} hours one task can take"
                    !allVocabulary && s.effortMin > MAX_EFFORT -> "effortMin is ${hours(s.effortMin)}, over the ${MAX_EFFORT / 60} hours one task can take"
                    else -> "the steps add up to $sum minutes, not the ${s.effortMin} effortMin gives"
                }
                base.copy(
                    effortMin = total ?: sum.takeIf { agree && it > 0 },
                    subSteps = planned.takeIf { agree }?.let { sittings(it, task.subSteps) }.orEmpty().ifEmpty { null },
                    dropped = dropped,
                    adjusted = read.adjusted,
                    // A split not trusted hands no vocabulary to a deck either: its total, planned
                    // whole, already holds it.
                    ankiSections = if (usable && agree) sections else emptyList(),
                    testDate = testDate,
                    question = question(s.question),
                    reason = reason(s.reason),
                )
            }
            Enrichments.Job.Effort -> json.decodeFromString(Estimate.serializer(), text).let { e ->
                val total = effort(e.effortMin)
                // Big enough to do in parts: its blocks, each perhaps with its own dates (Q12).
                val checked = trusted(steps(e.blocks, now, zone, vocabulary = false, maxMinutes = MAX_EFFORT, dueBy = sourceDue(task), noun = "block", salvage = salvage), e.effortMin, e.blocks.size)
                val blocks = checked.steps?.let { sittings(it, task.subSteps) }
                base.copy(effortMin = blocks?.sumOf { it.minutes } ?: total, subSteps = blocks, dropped = checked.dropped, adjusted = checked.adjusted)
            }
        }
    }

    /**
     * [given] as steps the planner can take, or none if any can't be, and why, naming the step and
     * what's wrong with it ([noun] names them; AI-01): no title, minutes out of range (up to
     * [maxMinutes]), a date that can't be read or is out of range, a deadline before its task can
     * be started ([opens]), or an order its dates forbid (a step that can't start till after one
     * listed later is due). Dates that can't be (a start after its own deadline, a start or a
     * deadline after its task's, [dueBy]) fail it too, unless it's to [salvage]: then the step keeps
     * its place and minutes with no dates, planned by its task's, and a line in [Checked.adjusted]
     * says so. Each keeps its own dates; one over [MAX_PART_MIN] is cut into sittings after, once
     * folded or trusted ([sittings]). A step tagged as vocabulary
     * ([vocabulary] jobs only) that asks for anything else too stays planned whole.
     */
    private fun steps(given: List<Step>, now: Long, zone: ZoneId, vocabulary: Boolean, maxMinutes: Int, dueBy: Long?, opens: Long? = null, noun: String = "step", salvage: Boolean = true): Checked {
        fun no(why: String) = Checked(null, why)
        // A time as the model wrote it: a date at midnight alone, as "from" is.
        fun at(time: Long): String = Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime().let { if (it.toLocalTime() == LocalTime.MIDNIGHT) it.toLocalDate().toString() else it.toString().take(16) }
        fun which(i: Int, title: String) = "$noun ${i + 1}" + title.trim().takeIf { it.isNotEmpty() }?.let { " ('${it.take(MAX_TITLE)}')" }.orEmpty()
        val adjusted = mutableListOf<String>()
        val read = given.mapIndexed { i, step ->
            val name = which(i, step.title)
            if (step.title.isBlank()) return no("$name has no title")
            if (step.minutes < 1) return no("$name has no minutes")
            if (step.minutes > maxMinutes) return no("$name is ${step.minutes} minutes, over the $maxMinutes allowed")
            var from = step.from?.let { date(it, zone, LocalTime.MIDNIGHT) ?: return no("$name has a date that can't be read: '${it.take(MAX_DATE_SAID)}'") }
            var due = step.due?.let { dateTime(it, zone) ?: return no("$name has a date that can't be read: '${it.take(MAX_DATE_SAID)}'") }
            listOfNotNull(from, due).firstOrNull { !plausible(it, now) }?.let { return no("$name has a date out of range: ${at(it)}") }
            // Dates that can't be: put right by the model, or, asked again, left out (AI-01).
            val wrong = when {
                from != null && due != null && from > due -> "starts on ${at(from)}, after its own due time ${at(due)}" to "its start was after its own due time"
                from != null && dueBy != null && from > dueBy -> "starts on ${at(from)}, after the due time ${at(dueBy)}" to "its start was after the hand-in"
                due != null && dueBy != null && due > dueBy -> "is due ${at(due)}, after the due time ${at(dueBy)}" to "it was due after the hand-in"
                else -> null
            }
            if (wrong != null) {
                if (!salvage) return no("$name ${wrong.first}")
                adjusted += "'${step.title.trim().take(MAX_TITLE)}': ${wrong.second}, so it's planned by the task's date"
                from = null
                due = null
            }
            if (due != null && opens != null && due < opens) return no("$name is due ${at(due)}, before the email can be started on ${at(opens)}")
            val sections = if (vocabulary && AnkiRules.vocabularyOnly(step.title)) sections(step.ankiSections) else emptyList()
            SubStep(step.title.trim().take(MAX_TITLE), step.minutes, ankiSections = sections, from = from, dueAt = due, where = Where.of(step.where)?.name?.lowercase())
        }
        for (i in read.indices) {
            val from = read[i].from ?: continue
            val j = read.indices.drop(i + 1).firstOrNull { later -> read[later].dueAt?.let { it < from } == true } ?: continue
            return no("${which(i, read[i].title)} can't start till ${at(from)}, after ${which(j, read[j].title)} is due ${at(read[j].dueAt!!)}")
        }
        return Checked(read, adjusted = adjusted)
    }

    /**
     * An email's or planner item's blocks ([checked]), if they can be planned: some, no more than
     * [Prompts.MAX_BLOCKS] as [given] (before a long one was cut into parts that fit a session),
     * within the most a task in blocks is taken to be ([MAX_BLOCKS_EFFORT]), and adding up to its
     * [total] (within a tenth, or ten minutes), which has to be within it too. Otherwise none, and
     * why: its estimate is planned whole. None given, there's nothing to drop.
     */
    private fun trusted(checked: Checked, total: Int, given: Int): Checked {
        val blocks = checked.steps ?: return checked
        if (blocks.isEmpty()) return Checked(null)
        fun no(why: String) = Checked(null, why)
        if (given > Prompts.MAX_BLOCKS) return no("there are $given blocks, over the ${Prompts.MAX_BLOCKS} allowed")
        val sum = blocks.sumOf { it.minutes }
        val most = maxOf(sum, total)
        if (most > MAX_BLOCKS_EFFORT) return no("the blocks come to ${hours(most)}, over the ${MAX_BLOCKS_EFFORT / 60} hours one task can take")
        if (total < 1) return no("the blocks come with no effortMin")
        if (abs(sum - total) > maxOf(STEPS_SLACK_MIN, total / 10)) return no("the blocks add up to $sum minutes, not the $total effortMin gives")
        return checked
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
                // At home if any of it must be: the rest is done where its hardest part can be (PL-15).
                else -> out += SubStep(
                    ("The rest: " + run.joinToString("; ") { it.title }).take(MAX_TITLE),
                    run.sumOf { it.minutes },
                    where = if (run.any { Where.of(it.where) == Where.Home }) "home" else run.map { it.where }.distinct().singleOrNull(),
                )
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

    /**
     * [field]'s [given] text as a time by [read], if it's plausible ([plausible]): one that can't be
     * read, or is further ahead than the app plans, is said in [problems] to be put right. One long
     * gone (an old email's date) is no mistake: it's left out without a word.
     */
    private fun checkedTime(field: String, given: String?, now: Long, problems: MutableList<String>, read: (String) -> Long?): Long? {
        val text = given?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val time = read(text) ?: return null.also { problems += "$field \"${text.take(40)}\" isn't a date (YYYY-MM-DD) or a local date and time (YYYY-MM-DDTHH:MM)" }
        if (time > now + 400 * DAY_MS) return null.also { problems += "$field $text is more than 400 days ahead, further than the app plans: give it only if the text says so" }
        return time.takeIf { plausible(it, now) }
    }

    private const val DAY_MS = 24 * 3_600_000L
}
