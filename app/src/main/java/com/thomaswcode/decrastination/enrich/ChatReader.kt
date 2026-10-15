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
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.InstantChanges
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.core.shareOut
import com.thomaswcode.decrastination.data.Settings
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
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /** The chat's instructions, as Felix (AI-24; Q-44: the chat is him too), for a student in Year [year] (AI-06). */
    fun system(settings: Settings = Settings(), year: Int = 12): String = (
        """
        You are ${settings.felixName}: ${settings.felixLine}, and this is the chat in a UK sixth-form student's (Year $year) planner app. The app gathers their homework (Teams), their own planner (Power Planner), emails that ask them for something (Gmail), German vocabulary (Anki) and tasks they add themselves; plans the work into the evenings before each is due, around their calendar; and blocks distracting apps while work is due.

        The student tells you about their work. You can:
        1. Add tasks of their own (newTasks): something to do that isn't listed ("write my UCAS statement by 1 December", "buy mum a present before Sunday"). Give a short title in their words; kind: Homework, Revision, Admin, or Event for something that happens at a time (an appointment, a match); subject if it's for one (else an empty string); dueDate and dueTime if they gave them (an event's start; no time means the end of that day); minutes for the whole thing (your best estimate if they didn't say, and say so in your reply); startDate if it can't be started before a day; notes only for something worth keeping (else an empty string); and steps only for a big task worth splitting, each with minutes (adding up to the task's) and its own dates where it has them.
        2. Change their tasks and days (changes), with these:
""" + InstructionPrompts.CHANGE_TYPES.prependIndent("        ") + """
        3. Answer questions about their work from what you're given, briefly: what's due when from the task list; what's on a day, how full it is, what to do next and why something isn't planned from the plan. The plan is what the app has them do and shows them, so say what it says rather than planning the work again yourself. The context gives the time now: a deadline later today is still ahead ("due at 16:00 today"), not past, and a slot marked past has gone by; say "overdue" only where the plan marks it so.
        4. Remember (memories): a fact about the student worth keeping for later (a preference, a pattern, something about their week: "football is on Tuesdays"), with a short topic, so a newer fact on the same topic replaces it; or, kind "rule", a standing instruction about how you should behave ("don't message before 8", "be blunter"). To forget one, give its topic (a rule's own words) and an empty text. Busy times, limits and days off are changes, not memories; most messages need none.

        Reply in one to three short sentences of plain UK English (asked what's on, a short list instead, a line a task). A change to a task that's already listed, a busy time or a limit today, or how an event counts is made as soon as you give it, so say what you've done ("Moved it to 19:30"); a new task or event, a busy time or a limit on another day, days off or a half-day are shown to the student to confirm, so say what you'd add. To undo a change, give the change that puts it back. When a reply from them is likely, offer up to three short ones in their words (suggestedReplies). If what you need is missing or unclear (which task they mean, or when), ask one short question and change nothing. Don't add a task that's already listed: change it instead. When their message is about a given task, event or day, it's about that unless they clearly name another. Do only what they asked for, and leave every field you don't use null.
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
        // Plain strings, empty for none: a schema holds at most 16 nullable fields (the API's limit,
        // SchemaLimitsTest), and the changes' take most of them.
        "subject" to STRING,
        "dueDate" to InstructionPrompts.nullable(DATE),
        "dueTime" to InstructionPrompts.nullable(TIME),
        "startDate" to InstructionPrompts.nullable(DATE),
        "minutes" to INTEGER,
        "notes" to STRING,
        "steps" to mapOf("type" to "array", "items" to STEP),
    )

    /** A memory to keep or forget (AI-21): a string each, so no union is added (the API takes 16). */
    val MEMORY: Map<String, Any> = InstructionPrompts.obj(
        "topic" to STRING,
        "text" to STRING,
        "kind" to mapOf("type" to "string", "enum" to listOf(com.thomaswcode.decrastination.core.Given.FACT, com.thomaswcode.decrastination.core.Given.RULE)),
    )

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "reply" to STRING,
        "newTasks" to mapOf("type" to "array", "items" to TASK),
        "changes" to mapOf("type" to "array", "items" to InstructionPrompts.CHANGE),
        "memories" to mapOf("type" to "array", "items" to MEMORY),
        "suggestedReplies" to mapOf("type" to "array", "items" to STRING),
    )

    /**
     * The conversation as it's sent: the last [HISTORY] messages, starting with one of yours, each
     * of Claude's with what it suggested and what became of it; your newest carries what the app
     * knows now ([context]). All of it within [budget] characters, as every call's input is held
     * to ([Pricing.MAX_INPUT_CHARS], which the cap's worst case assumes): your newest words whole;
     * the turns before them, newest first, in up to a quarter of what's left (a follow-up needs its
     * question); then the context, its end cut if it must be, and after it the [plan] ([ChatPrompts.plan]).
     * Where both can't be whole, the plan keeps at least a third of their room (its near days first),
     * so a long list of tasks can't push it out: the list loses its end, and the plan its far days.
     */
    fun turns(messages: List<ChatMessage>, context: String, tasks: Map<String, TaskItem>, zone: ZoneId, budget: Int = Pricing.MAX_INPUT_CHARS, plan: String = ""): List<Pair<Speaker, String>> {
        val last = messages.takeLast(HISTORY)
        // Before any of yours: an answer cut off from its question by the window goes; a message of
        // Felix's own (a notification you answered, [ChatMessage.reason]) is said in your first turn,
        // as the conversation has to start with one of yours.
        val lead = last.takeWhile { it.from != Speaker.You }.filter { it.reason != null }
        val recent = last.dropWhile { it.from != Speaker.You }
        if (recent.isEmpty()) return emptyList()
        // A turn each way at a time: a message of Felix's from a notification can follow his last answer.
        val folded = recent.map { m -> m.from to text(m, tasks, zone) }.fold(emptyList<Pair<Speaker, String>>()) { turns, (from, text) ->
            if (turns.lastOrNull()?.first == from) turns.dropLast(1) + (from to turns.last().second + "\n\n" + text) else turns + (from to text)
        }
        val texts = if (lead.isEmpty()) folded else {
            val before = lead.joinToString("\n") { "(You'd written to them: “${it.text.trim().take(MAX_TEXT)}”)" }
            listOf(folded.first().first to before + "\n\n" + folded.first().second) + folded.drop(1)
        }
        val newest = texts.last().second.take(budget)
        // The turns before it, newest first, while they fit their share, starting again with one of yours.
        var left = (budget - newest.length) / 4
        val earlier = texts.dropLast(1).reversed().takeWhile { (_, t) -> (t.length <= left).also { if (it) left -= t.length } }.reversed()
            .dropWhile { it.first != Speaker.You }
        // The context and the plan in the rest, beside your newest words.
        val room = (budget - newest.length - 2 - earlier.sumOf { it.second.length }).coerceAtLeast(0)
        val known = when {
            plan.isEmpty() || room <= PLAN_GAP.length -> context.take(room)
            context.length + PLAN_GAP.length + plan.length <= room -> context + PLAN_GAP + plan
            else -> {
                val planRoom = maxOf(room - PLAN_GAP.length - context.length, minOf(plan.length, room / 3))
                context.take(room - PLAN_GAP.length - planRoom) + PLAN_GAP + plan.take(planRoom)
            }
        }
        return earlier + (Speaker.You to known + "\n\n" + newest)
    }

    private const val PLAN_GAP = "\n\n"

    /** What the app knows, as the chat sends it: [known] ([context]), then the [plan] ([plan]), each with its share of the room ([turns]). */
    data class Context(val known: String, val plan: String) {
        /** All of it, as it's sent when there's room. */
        val whole: String get() = if (plan.isEmpty()) known else known + PLAN_GAP + plan
    }

    /** One message as it's sent: yours with what it was about; Claude's with its cards and what became of them. */
    private fun text(m: ChatMessage, tasks: Map<String, TaskItem>, zone: ZoneId): String = when (m.from) {
        Speaker.You -> aboutLine(m.about) + m.text.trim().take(MAX_TEXT)
        Speaker.Claude -> m.text + m.proposals.joinToString("") { p ->
            // In the words it was proposed in, where they're kept: made, a task's estimate is the new one.
            val what = p.task?.let { "add the task “${it.title}”" }
                ?: p.lines.takeIf { it.size == p.changes.size }?.joinToString("; ")
                ?: p.changes.joinToString("; ") { Instructions.describe(it, tasks, zone) }
            val became = when (p.state) {
                ProposalState.Proposed -> "not acted on yet"
                ProposalState.Applied -> if (p.instant) "made at once" else "the student applied it"
                ProposalState.Discarded -> "the student discarded it"
            }
            "\n[Suggested: $what — $became]"
        }
    }

    private fun aboutLine(about: About): String = when {
        about.taskId != null -> "(About the task ${about.taskId}, “${about.taskTitle.orEmpty().take(MAX_TITLE)}”) "
        about.eventKey != null -> "(About the calendar event “${(about.eventTitle ?: about.eventKey).take(MAX_TITLE)}”, eventKey ${about.eventKey}) "
        about.day != null -> "(About ${runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day}) "
        else -> ""
    }

    /** What the app knows now: today, the open tasks, the fortnight's events, and the changes in use. The plan's sent beside it ([plan], [turns]). */
    fun context(tasks: List<TaskItem>, events: List<CalendarEvent>, applied: List<Instruction>, now: Long, zone: ZoneId): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        val byId = tasks.associateBy { it.id }
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)} (${zone.id})")
        appendLine()
        appendLine("Open tasks (id | title | from | kind | due, a real deadline unless marked a plan, which is never overdue and moves on once it passes | minutes in all | minutes worked so far | can't start before):")
        // By the date shown (the deadline in force, PL-14), so a plan's date gone by doesn't crowd nearer ones out of the list.
        val open = tasks.filter { it.isOpen && !it.hidden }.sortedBy { it.deadlineAt(now) ?: it.dueAt ?: Long.MAX_VALUE }
        if (open.isEmpty()) appendLine("(none)")
        open.take(InstructionPrompts.MAX_TASKS).forEach { t ->
            val start = listOfNotNull(t.availableFrom, t.userFrom, t.notBefore).maxOrNull()?.takeIf { it > now }
            // A date the app or Claude set is a plan, not a deadline (PL-14): said, so it's not called overdue.
            val due = (t.deadlineAt(now) ?: t.dueAt)?.let { at(it) + if (t.hardAt(now)) "" else " (a plan)" } ?: "no date"
            appendLine("- ${t.id} | ${t.title.take(MAX_TITLE)} | ${t.source.label} | ${t.kind.label} | $due | ${t.effortMin} | ${t.workedMin + t.photoMin} | ${start?.let(::at) ?: "-"}")
        }
        appendLine()
        appendLine("Calendar events in the next fortnight (eventKey | title | when):")
        // The calendar's read as far as the plan reaches (DX-14); the chat is told of the fortnight's.
        val fortnight = events.filter { it.start < now + InstructionPrompts.EVENTS_AHEAD_MS }
        if (fortnight.isEmpty()) appendLine("(none)")
        fortnight.sortedBy { it.start }.take(InstructionPrompts.MAX_EVENTS).forEach { e ->
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

    /** The plan's days sent in full: a fortnight, as the calendar's events are. */
    const val PLAN_DAYS = 14

    /**
     * [plan] as the Plan tab shows it, for questions about a day ("what's on tomorrow?"): the next
     * thing to do, as its *Do now* card has it, with the slot under way ("Now: 17:00–17:30 …");
     * the open work it leaves out, with why; the other events coming up; what's planned after
     * the fortnight, in a line naming its tasks; then each of the next [PLAN_DAYS] days, its minutes
     * against its working time, its work a task a line with the pieces that day, and its events from
     * *Coming up*. The days go last, the nearest first, so a plan cut to fit ([turns]) loses only its
     * far days; each summary is at most [PLAN_LIST] lines.
     */
    fun plan(plan: Plan, zone: ZoneId): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        fun piece(chunk: Chunk) = "${chunk.label.take(MAX_TITLE)} (${chunk.minutes} min)"
        fun event(task: TaskItem) = "${task.id} | ${task.title.take(MAX_TITLE)} | ${task.source.label}"
        appendLine(
            "The plan: the work the app has them do each day so every deadline is met, as its Plan tab shows it. Its minutes are the app's, " +
                "from what it's learned of how long things take them, so they can differ from the task list's. While anything's planned for " +
                "today, blocked apps stay blocked in blocking hours; otherwise they cost free time earned by working. Each piece's [why: …] " +
                "says why it's on its day: duesoon (due today or tomorrow), overdue, spread (its share of the free time to its deadline), " +
                "minimumtouch (kept within a week of its piece before), revisiongap (a day after its revision piece before), mostroom (a " +
                "task in one piece, on the roomiest day before its deadline), soft (no due date: spare time this week), packed (its days " +
                "are too tight to spread it, so as late as they allow), noroom (no room before its deadline).",
        )
        // As the Do now card: the slot under way, with its time, else the next thing; then the one after.
        val then = plan.then?.let { "; then ${piece(it)}" }.orEmpty()
        val doing = plan.slotAt(plan.now)
        fun clock(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(CLOCK)
        when {
            doing != null -> appendLine("Now: ${clock(doing.start)}–${clock(doing.end)} ${piece(doing.chunk!!)}$then")
            else -> plan.next?.let { appendLine("Next to do: ${piece(it)}$then") }
        }
        // Today's slots at their times, each gone by, under way or to come (AI-13), so a time today isn't taken for past.
        val slots = plan.todaySlots.filter { it.kind == com.thomaswcode.decrastination.core.SlotKind.Work && it.chunk != null }
        if (slots.isNotEmpty()) {
            appendLine("Today's slots (the times its pieces are laid at):")
            slots.forEach { s ->
                val state = when {
                    s.end <= plan.now -> "past"
                    s.start <= plan.now -> "now"
                    else -> "ahead"
                }
                appendLine("- ${clock(s.start)}–${clock(s.end)} ${piece(s.chunk!!)} [$state]")
            }
        }
        val last = plan.today.plusDays(PLAN_DAYS - 1L)
        fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        /** At most [PLAN_LIST] of [lines], and how many more. */
        fun list(lines: List<String>) {
            lines.take(PLAN_LIST).forEach { appendLine("- $it") }
            if (lines.size > PLAN_LIST) appendLine("- …and ${lines.size - PLAN_LIST} more")
        }
        // The summaries before the days: a plan cut to fit loses its far days, never why something isn't planned.
        if (plan.unplanned.isNotEmpty()) {
            val end = (plan.buckets.lastOrNull()?.date ?: plan.today).format(DAY)
            appendLine("Not planned (open work the plan leaves out, and why):")
            list(
                plan.unplanned.map { gap ->
                    val why = when (gap.reason) {
                        Unplanned.Reason.NotOpen -> "can't be started before ${gap.at?.let(::at) ?: "later"}, its source says"
                        Unplanned.Reason.Waits -> "waits until “${gap.after?.take(MAX_TITLE) ?: "another task"}” is done, as they said"
                        Unplanned.Reason.OpensLater -> "opens ${gap.at?.let(::at) ?: "later"}, after the plan's last day ($end): planned once it's within reach"
                        Unplanned.Reason.NoRoom -> "no room for it by $end, the plan's last day, within each day's limits"
                    }
                    val pieces = if (gap.pieces == 1) "1 piece" else "${gap.pieces} pieces"
                    "${gap.taskId} | ${gap.title.take(MAX_TITLE)} | $pieces, ${gap.minutes} min | $why"
                },
            )
        }
        // The rest of *Coming up*: past, after the fortnight, or with no date.
        val others = plan.events.filter { e -> e.dueAt?.let { day(it) !in plan.today..last } ?: true }
        if (others.isNotEmpty()) {
            appendLine("Events on the Plan other than those under their days below (past, after ${last.format(DAY)}, or with no date):")
            list(others.map { "${event(it)} | ${it.dueAt?.let(::at) ?: "no date"}" })
        }
        val after = plan.buckets.filter { it.date > last && it.chunks.isNotEmpty() }
        if (after.isNotEmpty()) {
            val days = if (after.size == 1) "1 more day" else "${after.size} more days"
            // Which tasks, so that's said rather than guessed.
            val ids = after.flatMap { it.chunks }.distinctBy { it.taskId }
            val tasks = ids.take(PLAN_LIST).joinToString("; ") { "${it.taskId} | ${it.title.take(MAX_TITLE)}" } +
                if (ids.size > PLAN_LIST) "; …and ${ids.size - PLAN_LIST} more" else ""
            appendLine("Planned after ${last.format(DAY)}: ${after.sumOf { it.plannedMin }} min on $days, the last ${after.last().date.format(DAY)}, of: $tasks")
        }
        // Then the days, the nearest first. Events (an appointment in Power Planner, one of yours) under their day, as *Coming up* lists them.
        appendLine("Day by day:")
        val eventsOn = plan.events.filter { it.dueAt != null }.groupBy { day(it.dueAt!!) }.filterKeys { it in plan.today..last }
        plan.buckets.filter { it.date <= last }.forEach { bucket ->
            val name = when (bucket.date) {
                plan.today -> "Today, "
                plan.today.plusDays(1) -> "Tomorrow, "
                else -> ""
            } + bucket.date.format(DAY)
            val time = "${bucket.capacityMin} min of working time" + if (bucket.date == plan.today) " left" else ""
            val over = (bucket.plannedMin - bucket.capacityMin).takeIf { it > 0 }?.let { ", over by $it min" }.orEmpty()
            appendLine(if (bucket.chunks.isEmpty()) "- $name: nothing planned ($time)" else "- $name: ${bucket.plannedMin} min planned of $time$over")
            eventsOn[bucket.date].orEmpty().forEach { appendLine("  - event at ${Instant.ofEpochMilli(it.dueAt!!).atZone(zone).format(CLOCK)}: ${event(it)}") }
            // A task a line, as the Plan lists them (Q31), its pieces that day in their order.
            bucket.chunks.groupBy { it.taskId }.forEach { (id, pieces) ->
                val first = pieces.first()
                // Undated work's deadline is the app's own (a week after it was first seen): none to be late for.
                val dated = first.dueAt != null
                val flags = listOfNotNull(
                    first.dueAt?.let { "due ${at(it)}" } ?: "no due date",
                    "overdue".takeIf { dated && pieces.any { it.overdue } },
                    // Behind its deadline itself (PL-02), not only the safety margin, which a piece can still meet.
                    "won't fit before its deadline".takeIf { dated && pieces.any { it.why == Why.NoRoom } },
                    pieces.mapNotNull { it.availableAt }.minOrNull()?.let { "can't start before ${at(it)}" },
                )
                // Why each piece is on its day, as a short code (PL-07): the sheet's sentence would take the plan's room.
                val parts = pieces.joinToString { "${it.step?.take(MAX_TITLE) ?: "all of it"} (${it.minutes} min) [why: ${it.why.name.lowercase()}]" }
                appendLine("  - $id | ${first.title.take(MAX_TITLE)} | ${pieces.sumOf { it.minutes }} min: $parts | ${flags.joinToString(" | ")}")
            }
        }
    }.trimEnd()

    /** The most lines in each of the plan's summaries (what isn't planned, other events, later tasks), so they leave the days room. */
    const val PLAN_LIST = 20
}

/** Claude's answer in the chat, checked: its reply, the tasks and changes the app can make, and what it left out. */
object ChatAnswers {

    data class Reading(
        val reply: String,
        val tasks: List<OwnTask>,
        val changes: List<Change>,
        val leftOut: List<String>,
        val memories: List<com.thomaswcode.decrastination.core.Given> = emptyList(),
        val suggestedReplies: List<String> = emptyList(),
    )

    /**
     * [reading]'s cards (AI-24): its changes to what exists ([InstantChanges]) on one card, applied as
     * it's made, first; its new tasks, a card each; the rest of its changes on one card to confirm.
     */
    fun cards(reading: Reading, tasks: Map<String, TaskItem>, today: LocalDate, zone: ZoneId, id: () -> String = { java.util.UUID.randomUUID().toString() }): List<com.thomaswcode.decrastination.core.Proposal> {
        val (now, later) = reading.changes.partition { InstantChanges.instant(it, tasks, today) }
        fun card(changes: List<Change>, instant: Boolean) = changes.takeIf { it.isNotEmpty() }?.let {
            com.thomaswcode.decrastination.core.Proposal(id(), changes = it, lines = it.map { c -> Instructions.describe(c, tasks, zone) }, instant = instant)
        }
        return listOfNotNull(card(now, instant = true)) + reading.tasks.map { com.thomaswcode.decrastination.core.Proposal(id(), task = it) } + listOfNotNull(card(later, instant = false))
    }

    @Serializable
    private data class Answer(
        val reply: String = "",
        val newTasks: List<NewTask> = emptyList(),
        val changes: List<InstructionAnswers.Given> = emptyList(),
        val memories: List<com.thomaswcode.decrastination.core.Given> = emptyList(),
        val suggestedReplies: List<String> = emptyList(),
    )

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
    /** The most memories one answer gives: a few at most is what's meant. */
    private const val MAX_MEMORIES = 5

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
        if (answer.changes.size > MAX_CHANGES) leftOut += "${answer.changes.size - MAX_CHANGES} more changes than the app takes at once"
        if (Instructions.makesCircle(changes, emptyList())) {
            leftOut += "changes that would have tasks wait for each other in a circle"
            changes = emptyList()
        }
        val replies = answer.suggestedReplies.map { it.trim().take(FelixAnswers.MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(FelixAnswers.MAX_REPLIES)
        return Reading(reply.take(ChatPrompts.MAX_TEXT), made, changes, leftOut, answer.memories.take(MAX_MEMORIES), replies)
    }

    private fun task(n: NewTask, now: Long, zone: ZoneId): OwnTask? {
        val kind = OwnTasks.KINDS.firstOrNull { it.name == n.kind } ?: Kind.Homework
        val due = n.dueDate?.let { at(it, n.dueTime, if (kind == Kind.Event) LocalTime.of(9, 0) else LocalTime.of(23, 59), zone) ?: return null }
        val start = n.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null }
        val steps = n.steps.map { s ->
            OwnStep(
                title = s.title.trim(),
                minutes = s.minutes,
                from = s.startDate?.let { at(it, null, LocalTime.MIDNIGHT, zone) ?: return null },
                dueAt = s.dueDate?.let { at(it, null, LocalTime.of(23, 59), zone) ?: return null },
            )
        }
            .let { given ->
                // The task's minutes as Claude gave them, shared over its steps where theirs don't come to
                // the same: its total is what it said (and what the reply says), its steps the shape.
                val total = n.minutes.takeIf { it >= maxOf(1, given.size) && it in OwnTasks.MINUTES }
                if (given.isEmpty() || total == null || given.sumOf { it.minutes } == total || given.any { it.minutes <= 0 }) given
                else shareOut(total, given.map { it.minutes }).let { shares -> given.mapIndexed { i, s -> s.copy(minutes = shares[i]) } }
            }
        // Every date in it, its steps' too, within a month back and a year and a bit ahead.
        val dates = listOfNotNull(due, start) + steps.flatMap { listOfNotNull(it.from, it.dueAt) }
        if (dates.any { it !in (now - 31 * DAY_MS)..(now + 400 * DAY_MS) }) return null
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
class ChatReader(
    apiKey: String,
    private val zone: ZoneId,
    endpoint: String? = null,
    val model: String = ClaudeEnricher.MODEL,
    private val profile: Profile.Block = Profile.Block.NONE,
    /** Felix's name and line, as the chat's him (AI-24). */
    private val settings: Settings = Settings(),
) {

    data class Result(val reading: ChatAnswers.Reading?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    /** Claude's answer to the last of [messages] (yours), with what the app knows now ([context]: [ChatPrompts.context] and [ChatPrompts.plan]). */
    suspend fun answer(messages: List<ChatMessage>, tasks: List<TaskItem>, events: List<CalendarEvent>, context: ChatPrompts.Context, now: Long): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { ChatPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(ChatPrompts.system(settings, profile.year), profile.text))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Chat)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        ChatPrompts.turns(messages, context.known, tasks.associateBy { it.id }, zone, plan = context.plan).forEach { (from, text) ->
            if (from == Speaker.You) builder.addUserMessage(text) else builder.addAssistantMessage(text)
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
