package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
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
 * What Claude is told in the chat (2.0.0, Q29), and the shape of its answer: a reply, tasks of your
 * own it would add, and changes it would make. Pure, so the wording, the schema and the checks are
 * tested without the API.
 */
object ChatPrompts {

    /** A message longer than this is cut. */
    const val MAX_TEXT = 2_000

    /** The earlier messages sent with a new one: the last few exchanges. */
    const val HISTORY = 12

    private const val MAX_TITLE = 100

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.UK)
    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    val SYSTEM = (
        """
        You're the assistant in a UK sixth-form student's (Year 12) planner app, in a chat. The app gathers their homework (Teams), their own planner (Power Planner), emails that ask them for something (Gmail), German vocabulary (Anki) and tasks they add themselves; plans the work into the evenings before each is due, around their calendar; and blocks distracting apps while work is due.

        The student tells you about their work. You can:
        1. Add tasks of their own (newTasks): something to do that isn't listed ("write my UCAS statement by 1 December", "buy mum a present before Sunday"). Give a short title in their words; kind: Homework, Revision, Admin, or Event for something that happens at a time (an appointment, a match); subject if it's for one; dueDate and dueTime if they gave them (an event's start; no time means the end of that day); minutes for the whole thing (your best estimate if they didn't say, and say so in your reply); startDate if it can't be started before a day; notes only for something worth keeping; and steps only for a big task worth splitting, each with minutes (adding up to the task's) and its own dates where it has them.
        2. Change their tasks and days (changes), with these:
""" + InstructionPrompts.CHANGE_TYPES.prependIndent("        ") + """
        3. Answer questions about their work from the lists you're given, briefly.

        Reply in one to three short sentences of plain UK English. Nothing you suggest happens until the student taps Add or Apply on it, so never say you've done it: say what you'd do ("I'd add…", "I'd move…"). If what you need is missing or unclear (which task they mean, or when), ask one short question and suggest nothing. Don't add a task that's already listed: change it instead. When their message is about a given task, event or day, it's about that unless they clearly name another. Suggest only what they asked for, and leave every field you don't use null.
    """
        ).trimIndent()

    private val STRING = InstructionPrompts.STRING
    private val DATE = mapOf("type" to "string", "format" to "date")
    private val TIME = mapOf("type" to "string", "description" to "HH:MM, 24-hour")
    private val INTEGER = mapOf("type" to "integer")

    private val STEP = InstructionPrompts.obj(
        "title" to STRING,
        "minutes" to INTEGER,
        "startDate" to InstructionPrompts.nullable(DATE),
        "dueDate" to InstructionPrompts.nullable(DATE),
    )

    private val TASK = InstructionPrompts.obj(
        "title" to STRING,
        "kind" to mapOf("type" to "string", "enum" to OwnTasks.KINDS.map { it.name }),
        "subject" to InstructionPrompts.nullable(STRING),
        "dueDate" to InstructionPrompts.nullable(DATE),
        "dueTime" to InstructionPrompts.nullable(TIME),
        "startDate" to InstructionPrompts.nullable(DATE),
        "minutes" to INTEGER,
        "notes" to InstructionPrompts.nullable(STRING),
        "steps" to mapOf("type" to "array", "items" to STEP),
    )

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "reply" to STRING,
        "newTasks" to mapOf("type" to "array", "items" to TASK),
        "changes" to mapOf("type" to "array", "items" to InstructionPrompts.CHANGE),
    )

    /**
     * The conversation as it's sent: the last [HISTORY] messages, starting with one of yours, each
     * of Claude's with what it suggested and what became of it; your newest carries what the app
     * knows now ([context]).
     */
    fun turns(messages: List<ChatMessage>, context: String, tasks: Map<String, TaskItem>, zone: ZoneId): List<Pair<Speaker, String>> {
        val recent = messages.takeLast(HISTORY).dropWhile { it.from != Speaker.You }
        return recent.mapIndexed { i, m ->
            val text = when (m.from) {
                Speaker.You -> (if (i == recent.lastIndex) "$context\n\n" else "") + aboutLine(m.about) + m.text.trim().take(MAX_TEXT)
                Speaker.Claude -> m.text + m.proposals.joinToString("") { p ->
                    val what = p.task?.let { "add the task “${it.title}”" } ?: p.changes.joinToString("; ") { Instructions.describe(it, tasks, zone) }
                    val became = when (p.state) {
                        ProposalState.Proposed -> "not acted on yet"
                        ProposalState.Applied -> "the student applied it"
                        ProposalState.Discarded -> "the student discarded it"
                    }
                    "\n[Suggested: $what — $became]"
                }
            }
            m.from to text
        }
    }

    private fun aboutLine(about: About): String = when {
        about.taskId != null -> "(About the task ${about.taskId}, “${about.taskTitle.orEmpty().take(MAX_TITLE)}”) "
        about.eventKey != null -> "(About the calendar event “${(about.eventTitle ?: about.eventKey).take(MAX_TITLE)}”, eventKey ${about.eventKey}) "
        about.day != null -> "(About ${runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day}) "
        else -> ""
    }

    /** What the app knows now: today, the open tasks, the fortnight's events, and the changes in use. */
    fun context(tasks: List<TaskItem>, events: List<CalendarEvent>, applied: List<Instruction>, now: Long, zone: ZoneId): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        val byId = tasks.associateBy { it.id }
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)} (${zone.id})")
        appendLine()
        appendLine("Open tasks (id | title | from | kind | due | minutes in all | can't start before):")
        val open = tasks.filter { it.isOpen && !it.hidden }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        if (open.isEmpty()) appendLine("(none)")
        open.take(InstructionPrompts.MAX_TASKS).forEach { t ->
            val start = listOfNotNull(t.availableFrom, t.userFrom, t.notBefore).maxOrNull()?.takeIf { it > now }
            appendLine("- ${t.id} | ${t.title.take(MAX_TITLE)} | ${t.source.label} | ${t.kind.label} | ${t.dueAt?.let(::at) ?: "no date"} | ${t.effortMin} | ${start?.let(::at) ?: "-"}")
        }
        appendLine()
        appendLine("Calendar events in the next fortnight (eventKey | title | when):")
        if (events.isEmpty()) appendLine("(none)")
        events.sortedBy { it.start }.take(InstructionPrompts.MAX_EVENTS).forEach { e ->
            val time = if (e.allDay) "all day " + Instant.ofEpochMilli(e.start).atZone(ZoneId.of("UTC")).toLocalDate().format(DAY) else "${at(e.start)} to ${at(e.end)}"
            appendLine("- ${EventJudge.key(e)} | ${e.title.take(MAX_TITLE)} | $time")
        }
        val inUse = applied.flatMap { it.changes }
        if (inUse.isNotEmpty()) {
            appendLine()
            appendLine("Changes already in use:")
            inUse.take(InstructionPrompts.MAX_TASKS).forEach { appendLine("- " + Instructions.describe(it, byId, zone)) }
        }
    }.trimEnd()
}

/** Claude's answer in the chat, checked: its reply, the tasks and changes the app can make, and what it left out. */
object ChatAnswers {

    data class Reading(val reply: String, val tasks: List<OwnTask>, val changes: List<Change>, val leftOut: List<String>)

    @Serializable
    private data class Answer(val reply: String = "", val newTasks: List<NewTask> = emptyList(), val changes: List<InstructionAnswers.Given> = emptyList())

    @Serializable
    private data class NewTask(
        val title: String = "",
        val kind: String = "Homework",
        val subject: String? = null,
        val dueDate: String? = null,
        val dueTime: String? = null,
        val startDate: String? = null,
        val minutes: Int = 0,
        val notes: String? = null,
        val steps: List<NewStep> = emptyList(),
    )

    @Serializable
    private data class NewStep(val title: String = "", val minutes: Int = 0, val startDate: String? = null, val dueDate: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** More than this is a misreading, not a message. */
    const val MAX_TASKS = 10
    const val MAX_CHANGES = 40
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * [text] as Claude's answer, checked against what there is: each task as the form would take it
     * ([OwnTasks.problem]) with plausible dates, each change as an instruction's is; what can't be
     * used is left out and said ([Reading.leftOut]), the rest kept. Null if it can't be read at all.
     */
    fun parse(text: String, tasks: List<TaskItem>, events: List<CalendarEvent>, about: About, now: Long, zone: ZoneId): Reading? {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull() ?: return null
        val reply = answer.reply.trim().takeIf { it.isNotEmpty() } ?: return null
        val leftOut = mutableListOf<String>()
        val made = answer.newTasks.take(MAX_TASKS).mapNotNull { n ->
            val task = task(n, now, zone)
            val problem = if (task == null) "a date in it doesn't read, or is out of range" else OwnTasks.problem(task)
            if (problem != null) leftOut += "the task “${n.title.trim().take(60)}” (${problem.replaceFirstChar(Char::lowercase)})"
            task.takeIf { problem == null }
        }
        if (answer.newTasks.size > MAX_TASKS) leftOut += "${answer.newTasks.size - MAX_TASKS} more tasks than the app takes at once"
        val byId = tasks.associateBy { it.id }
        val titles = events.associate { EventJudge.key(it) to it.title } + listOfNotNull(about.eventKey?.let { it to (about.eventTitle ?: it) })
        var changes = answer.changes.take(MAX_CHANGES).mapNotNull { g ->
            InstructionAnswers.change(g, byId, titles, now, zone).also { if (it == null) leftOut += "a change (${InstructionAnswers.problem(g, byId, titles)})" }
        }
        if (Instructions.makesCircle(changes, emptyList())) {
            leftOut += "changes that would have tasks wait for each other in a circle"
            changes = emptyList()
        }
        return Reading(reply.take(ChatPrompts.MAX_TEXT), made, changes, leftOut)
    }

    private fun task(n: NewTask, now: Long, zone: ZoneId): OwnTask? {
        val kind = OwnTasks.KINDS.firstOrNull { it.name == n.kind } ?: Kind.Homework
        val due = n.dueDate?.let { at(it, n.dueTime, if (kind == Kind.Event) LocalTime.of(9, 0) else LocalTime.of(23, 59), zone) ?: return null }
        val start = n.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null }
        if (listOfNotNull(due, start).any { it !in (now - 31 * DAY_MS)..(now + 400 * DAY_MS) }) return null
        val steps = n.steps.map { s ->
            OwnStep(
                title = s.title.trim(),
                minutes = s.minutes,
                from = s.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null },
                dueAt = s.dueDate?.let { at(it, null, LocalTime.of(23, 59), zone) ?: return null },
            )
        }
        return OwnTask(
            title = n.title.trim(),
            notes = n.notes?.trim().orEmpty(),
            kind = kind,
            className = n.subject?.trim()?.takeIf { it.isNotEmpty() },
            dueAt = due,
            startFrom = start,
            minutes = n.minutes,
            steps = steps,
        )
    }

    private fun at(date: String, time: String?, otherwise: LocalTime, zone: ZoneId): Long? {
        val day = runCatching { LocalDate.parse(date.trim().take(10)) }.getOrNull() ?: return null
        val clock = time?.let { t -> runCatching { LocalTime.parse(t.trim().take(5)) }.getOrNull() ?: return null } ?: otherwise
        return day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
    }
}

/** Claude in the chat ([ChatPrompts]), as the enrichment's calls are made. */
class ChatReader(apiKey: String, private val zone: ZoneId, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    data class Result(val reading: ChatAnswers.Reading?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    /** Claude's answer to the last of [messages] (yours), with what the app knows now. */
    suspend fun answer(messages: List<ChatMessage>, tasks: List<TaskItem>, events: List<CalendarEvent>, applied: List<Instruction>, now: Long): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { ChatPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val context = ChatPrompts.context(tasks, events, applied, now, zone)
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(ChatPrompts.SYSTEM)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        ChatPrompts.turns(messages, context, tasks.associateBy { it.id }, zone).forEach { (from, text) ->
            if (from == Speaker.You) builder.addUserMessage(text.take(Pricing.MAX_INPUT_CHARS)) else builder.addAssistantMessage(text)
        }
        val message = withContext(Dispatchers.IO) { client.messages().create(builder.build()) }
        val cost = Pricing.costUsd(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true)
            StopReason.MAX_TOKENS -> return Result(null, cost)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val about = messages.lastOrNull { it.from == Speaker.You }?.about ?: About()
        return Result(ChatAnswers.parse(text, tasks, events, about, now, zone), cost)
    }
}
