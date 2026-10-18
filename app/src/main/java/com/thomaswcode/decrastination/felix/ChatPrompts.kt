package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.data.FelixSettings
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.enrich.Found
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.Pricing
import com.thomaswcode.decrastination.enrich.ResearchPrompts
import com.thomaswcode.decrastination.enrich.TaskText
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What Claude is told in the chat (2.0.0, Q29), and the shape of its answer: a reply, tasks of your
 * own it would add, and changes it would make. Pure, so the wording, the schema and the checks are
 * tested without the API.
 */
object ChatPrompts {

    /** A message longer than this is cut. */
    const val MAX_TEXT = 2_000

    /** The conversation's earlier messages sent with a new one (yours, and his answers to you): the last few exchanges. */
    const val HISTORY = 12

    /** His nudges (what he writes unasked) sent with a new one, each with your reply: the last few, so they can't push the conversation out (D-97). */
    const val NUDGES = 2

    /**
     * What of [messages] the conversation sent with a new one is made from (D-97): its last [HISTORY]
     * messages of the conversation (yours, and his answers to you), and his last [NUDGES] nudges (a
     * message he wrote unasked), each with your reply to it, in their order; nothing with no words (his
     * silences). A reply of yours to a nudge that went to no call (a check-in's answer, how work went)
     * goes only with its nudge; one he answered is the conversation's, as is the newest, yours, which
     * is what's being answered.
     */
    fun history(messages: List<ChatMessage>): List<ChatMessage> {
        val said = messages.filter { it.from == Speaker.You || it.text.isNotBlank() }
        val nudges = said.filter { it.from == Speaker.Felix && it.kind != MessageKind.Chat }
        val nudged = nudges.mapTo(HashSet()) { it.id }
        // Yours to a nudge, answered by him: his answer comes before another message of yours.
        fun answered(i: Int): Boolean = said.drop(i + 1).firstOrNull { it.from == Speaker.You || it.kind == MessageKind.Chat }?.from == Speaker.Felix
        val conversation = said.filterIndexed { i, m ->
            when {
                m.from == Speaker.Felix -> m.kind == MessageKind.Chat
                m.replyTo == null || m.replyTo !in nudged -> true
                else -> i == said.lastIndex || answered(i)
            }
        }
        val last = nudges.takeLast(NUDGES)
        val kept = (conversation.takeLast(HISTORY) + last + last.mapNotNull { ChatHistory.replyOf(it, said) }).mapTo(HashSet()) { it.id }
        return said.filter { it.id in kept }
    }

    private const val MAX_TITLE = 100

    /** A message a reply quotes, cut to this ([turns]): its point, not all of it. */
    private const val MAX_QUOTE = 300

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.UK)
    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /**
     * What looking things up is for (16 Oct), when he may ([Settings.felixSearches]): asked for in
     * the answer, made in a call of its own ([WebResearcher]), and answered again with what it found.
     */
    private val RESEARCH = """
        6. Look things up (research): when their message needs facts that neither they nor the app have given you (a competition's rules and dates, an exam board's specification, what an event involves), give what to search the web for in a few words, reply only that you're looking it up, and change nothing: you'll be asked again with what's found, to answer and act on (its steps, dates and minutes). Never for what the lists here tell you, nor for anything about the student themselves; most messages need none, and research is then an empty string.
    """.trimIndent()

    /** With looking things up off: the field it has is always empty. */
    private const val NO_RESEARCH = "6. research is always an empty string: you can't look things up."

    /**
     * Reading a task's whole text (D-93), its text in the context being cut short ([BRIEF_TEXT]):
     * asked for in the answer, and answered again with it ([turns]).
     */
    private const val READ = "7. Read a task in full (details): where you need more of a task's own text than the list shows (it says how much was left out), give its id, up to three, reply only that you're reading it, and change nothing: you'll be asked again with the whole text, to answer and act on. Not for the task a message is about, whose text comes whole; most messages need none, and details is then an empty list."

    /** The chat's instructions, as Felix (AI-24; Q-44: the chat is him too), for a student in Year [year] (AI-06). */
    fun system(settings: Settings = Settings(), year: Int = 12): String = (
        """
        You are ${settings.felixName}: ${settings.felixLine}, and this is the chat in a UK sixth-form student's (Year $year) planner app. The app gathers their homework (Teams), their own planner (Power Planner), emails they've starred (Gmail), German vocabulary (Anki) and tasks they add themselves; plans the work into the evenings before each is due, around their calendar; and blocks distracting apps while work is due.

        The student tells you about their work. You can:
        1. Add tasks of their own (newTasks): something to do that isn't listed ("write my UCAS statement by 1 December", "buy mum a present before Sunday"). Give a short title in their words; kind: Homework, Revision, Admin, or Event for something that happens at a time (an appointment, a match); subject if it's for one (else an empty string); dueDate and dueTime if they gave them (an event's start; with no time, an event starts at 09:00 and anything else is due by the end of that day); minutes for the whole thing (your best estimate if they didn't say, and say so in your reply); startDate if it can't be started before a day; notes only for something worth keeping (else an empty string); and steps only for a big task worth splitting, each with minutes (adding up to the task's) and its own dates where it has them.
        2. Change their tasks and days (changes), with these:
""" + InstructionPrompts.CHANGE_TYPES.prependIndent("        ") + """
        Minutes you set (a new task's, a Minutes change, each step's) are planned exactly as you set them, never stretched by the student's pace: that's only for the app's own estimates (the context gives their pace, and what each task's minutes come to in the plan by it). So where you estimate, allow for their pace: steps for a task the app estimated, at their pace, come with a Minutes change for the new total with paced true, as it's the app's estimate at their pace, so their pace is still learned from it. Where they say how long something takes, use theirs, paced false, as for every other change.
        3. Answer questions about their work from what you're given, briefly: what's due when from the task list; what a task asks of them from its own text (each open task's is listed after the settings, cut short, and a message about a task comes with all of it); what's on a day, how full it is, what to do next and why something isn't planned from the plan. The plan is what the app has them do and shows them, so say what it says rather than planning the work again yourself. The context gives the time now: a deadline later today is still ahead ("due at 16:00 today"), not past, and a slot marked past has gone by; say "overdue" only where the plan marks it so.
        4. Remember (memories): a fact about the student worth keeping for later (a preference, a pattern, something about their week: "football is on Tuesdays"), with a short topic, so a newer fact on the same topic replaces it; or, kind "rule", a standing instruction about how you should behave ("don't message before 8", "be blunter"). To forget one, give its topic (a rule's own words) and an empty text. Busy times, limits and days off are changes, not memories; most messages need none.
        5. Act (actions): tickStep when the student says a step is done, untickStep when one ticked isn't (taskId, and stepIndex from the list's steps); done when they've finished a task of their own (from Yours), or one whose tick finishes it; reopen for one of theirs finished by mistake (from the finished list); delete for a task of their own they no longer want (another source's they can only say isn't a task: NotATask); open to show them a task; setting to change one of the settings listed with their values (taskId empty, setting its name, value as the list writes it: a number, a time as HH:MM, hours as HH:MM-HH:MM). stepIndex is -1 but for steps, setting and value empty but for settings. A task from Teams, Power Planner, Gmail or Anki is done when its source says so (handed in, completed in Power Planner, unstarred, studied), so it can't be marked done. Most messages need none.
""" + (if (settings.felixSearches) RESEARCH else NO_RESEARCH).prependIndent("        ") + "\n" + READ.prependIndent("        ") + "\n" + """
        Reply in one to three short sentences of plain UK English (asked what's on, a short list instead, a line a task). Everything you give is made as soon as you give it (a new task or event, a change, days off, a step ticked, a setting), so say what you've done ("Moved it to 19:30", "Added it, due Tuesday"), never what you'd do: the student has an Undo for each. To undo something yourself, give what puts it back. When a reply from them is likely, offer up to three short ones in their words (suggestedReplies). If what you need is missing or unclear (which task they mean, or when), ask one short question and change nothing. Don't add a task that's already listed: change it instead. When their message is about a given task, event or day, it's about that unless they clearly name another. When it starts with a message it replies to, quoted, it's answering that one. Do only what they asked for, and leave every field you don't use empty: an empty string, -1 for a number.
    """
        ).trimIndent()

    private val STRING = InstructionPrompts.STRING
    private val DATE = InstructionPrompts.DATE
    private val TIME = InstructionPrompts.TIME
    private val INTEGER = mapOf("type" to "integer")

    // Plain fields, empty for none, as the changes' are: a nullable field is a union, and unions in
    // a list make the grammar the API compiles from the schema too large (SchemaLimitsTest).
    private val STEP = InstructionPrompts.obj(
        "title" to STRING,
        "minutes" to INTEGER,
        "startDate" to DATE,
        "dueDate" to DATE,
    )

    private val TASK = InstructionPrompts.obj(
        "title" to STRING,
        "kind" to mapOf("type" to "string", "enum" to OwnTasks.KINDS.map { it.name }),
        "subject" to STRING,
        "dueDate" to DATE,
        "dueTime" to TIME,
        "startDate" to DATE,
        "minutes" to INTEGER,
        "notes" to STRING,
        "steps" to mapOf("type" to "array", "items" to STEP),
    )

    /** A memory to keep or forget (AI-21): a string each, so no union is added (the API takes 16). */
    val MEMORY: Map<String, Any> = InstructionPrompts.obj(
        "topic" to STRING,
        "text" to STRING,
        "kind" to mapOf("type" to "string", "enum" to listOf(Given.FACT, Given.RULE)),
    )

    /** Something to do for the student (AI-14; 16 Oct, more of it), plain fields so no union is added: stepIndex -1, setting and value empty for none. */
    private val ACTION = InstructionPrompts.obj(
        "type" to mapOf("type" to "string", "enum" to listOf("tickStep", "untickStep", "done", "reopen", "delete", "open", "setting")),
        "taskId" to STRING,
        "stepIndex" to INTEGER,
        "setting" to STRING,
        "value" to STRING,
    )

    /**
     * A change as the chat gives it: the instructions' reader's, and whether a Minutes total is the
     * app's estimate at the student's pace (17 Oct), so the pace still learns from the task. A plain
     * boolean, so no union is added.
     */
    private val CHANGE = InstructionPrompts.obj(*(InstructionPrompts.CHANGE_FIELDS + ("paced" to mapOf("type" to "boolean"))).toTypedArray())

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "reply" to STRING,
        "newTasks" to mapOf("type" to "array", "items" to TASK),
        "changes" to mapOf("type" to "array", "items" to CHANGE),
        "memories" to mapOf("type" to "array", "items" to MEMORY),
        "suggestedReplies" to mapOf("type" to "array", "items" to STRING),
        "actions" to mapOf("type" to "array", "items" to ACTION),
        // What to look up on the web (16 Oct), empty for nothing: a plain string, so no union is added.
        "research" to STRING,
        // The tasks whose whole text to read (D-93), none for nothing.
        "details" to mapOf("type" to "array", "items" to STRING),
    )

    /**
     * The conversation as it's sent: what [history] takes of it, starting with one of yours, each
     * of Claude's with what it suggested and what became of it; your newest carries what the app
     * knows now ([context]). All of it within [budget] characters, as every call's input is held
     * to ([Pricing.MAX_INPUT_CHARS], which the cap's worst case assumes): your newest words whole;
     * the turns before them, newest first, in up to a quarter of what's left (a follow-up needs its
     * question); then the context, its end cut if it must be, and after it the [plan] ([ChatPrompts.plan]).
     * Where both can't be whole, the plan keeps at least a third of their room (its near days first),
     * so a long list of tasks can't push it out: the list loses its end, and the plan its far days.
     * Your newest words come after what they're about, in full ([aboutText], D-93), and before what
     * a web search [found] and the tasks he asked to [read] whole ([readText]). A message of yours that
     * replies to one that isn't just before it starts with that one, quoted (5b), as the window may not
     * hold it.
     */
    fun turns(
        messages: List<ChatMessage>,
        context: String,
        tasks: Map<String, TaskItem>,
        zone: ZoneId,
        budget: Int = Pricing.MAX_INPUT_CHARS,
        plan: String = "",
        found: Found? = null,
        read: List<TaskItem> = emptyList(),
        events: List<CalendarEvent> = emptyList(),
    ): List<Pair<Speaker, String>> {
        val last = history(messages)
        // Before any of yours: an answer cut off from its question by the window goes; a message of
        // Felix's own (one he wrote unasked, [ChatMessage.kind]) is said in your first turn, as the
        // conversation has to start with one of yours.
        val lead = last.takeWhile { it.from != Speaker.You }.filter { it.kind != MessageKind.Chat }
        val recent = last.dropWhile { it.from != Speaker.You }
        if (recent.isEmpty()) return emptyList()
        // What a message of yours replies to, where it isn't the message before it: quoted, from the
        // whole chat, as the window may not hold it.
        val byId = messages.associateBy { it.id }
        fun quote(m: ChatMessage): String {
            val before = last.getOrNull(last.indexOfFirst { it.id == m.id } - 1)
            val to = m.replyTo?.takeIf { m.from == Speaker.You && it != before?.id }?.let(byId::get)?.takeIf { it.text.isNotBlank() } ?: return ""
            val said = to.text.trim().take(MAX_QUOTE)
            return if (to.from == Speaker.Felix) "(Replying to your message: “$said”)\n" else "(Replying to their own earlier message: “$said”)\n"
        }
        // A turn each way at a time: a message of Felix's from a notification can follow his last answer.
        val folded = recent.map { m -> m.from to quote(m) + text(m, tasks, zone) }.fold(emptyList<Pair<Speaker, String>>()) { turns, (from, text) ->
            if (turns.lastOrNull()?.first == from) turns.dropLast(1) + (from to turns.last().second + "\n\n" + text) else turns + (from to text)
        }
        val texts = if (lead.isEmpty()) folded else {
            val before = lead.joinToString("\n") { (if (it.byApp) "(The app wrote to them for you: “" else "(You'd written to them: “") + it.text.trim().take(MAX_TEXT) + "”)" }
            listOf(folded.first().first to before + "\n\n" + folded.first().second) + folded.drop(1)
        }
        // After what it's about, in full; with what a web search found for it (16 Oct) and the tasks
        // he asked to read, to answer and act on. What it's about never at your words' cost: cut
        // to the room they leave, however much it says.
        val words = texts.last().second
        val about = aboutText(recent.last { it.from == Speaker.You }.about, tasks, events)?.take((budget - words.length - 2).coerceAtLeast(0))?.takeIf { it.isNotEmpty() }
        val newest = listOfNotNull(about, words, found?.let(ResearchPrompts::told), read.takeIf { it.isNotEmpty() }?.let(::readText)).joinToString("\n\n").take(budget)
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

    /**
     * One message as it's sent: yours with what it was about; Felix's with its cards and what became of
     * them, and one the app wrote in his name (a check-in's question, the morning's list) said as such.
     */
    private fun text(m: ChatMessage, tasks: Map<String, TaskItem>, zone: ZoneId): String = when (m.from) {
        Speaker.You -> aboutLine(m.about) + m.text.trim().take(MAX_TEXT)
        Speaker.Felix -> (if (m.byApp) "(the app wrote this for you) " else "") + m.text + m.proposals.joinToString("") { p ->
            // In the words it was proposed in, where they're kept: made, a task's estimate is the new one.
            val what = p.task?.let { "add the task “${it.title}”" }
                ?: p.lines.takeIf { it.size == p.changes.size }?.joinToString("; ")
                ?: p.changes.joinToString("; ") { Instructions.describe(it, tasks, zone) }
            val became = when (p.state) {
                // Made at once since 16 Oct: one not made says why; one the student took back, so.
                ProposalState.Proposed -> when {
                    p.note != null -> "not made: ${p.note}"
                    p.instant -> "undone by the student"
                    else -> "not acted on yet"
                }
                ProposalState.Applied -> if (p.instant) "made at once" else "the student applied it"
                ProposalState.Discarded -> "the student discarded it"
            }
            "\n[Suggested: $what — $became]"
        }
    }

    /**
     * What [about] names, in full (D-93): a task's whole text, at most [WHOLE_TEXT] characters of it,
     * with your answers to its questions; an event's place and description. Null where there's none.
     */
    private fun aboutText(about: About, tasks: Map<String, TaskItem>, events: List<CalendarEvent>): String? {
        about.taskId?.let(tasks::get)?.let { task ->
            if (!TaskText.has(task) && task.answers.isEmpty()) return null
            return buildString {
                append("The task this message is about, ${task.id} “${task.title.take(MAX_TITLE)}” (${TaskText.about(task)}), in full:\n")
                append(TaskText.whole(task, WHOLE_TEXT) ?: "  (no text of its own)")
                if (task.answers.isNotEmpty()) append("\nTheir answers to its questions: ").append(task.answers.joinToString("; ").take(MAX_TEXT))
            }
        }
        val key = about.eventKey ?: return null
        // A repeating event's occurrences share their key: the one it was asked about, by its start.
        val event = events.filter { EventJudge.key(it) == key }.let { same -> same.firstOrNull { it.start == about.eventStart } ?: same.firstOrNull() } ?: return null
        val parts = listOfNotNull(
            event.location.trim().takeIf { it.isNotEmpty() }?.let { "where: " + it.take(EVENT_PLACE) },
            event.description.trim().takeIf { it.isNotEmpty() }?.let { "its description: " + it.take(EVENT_TEXT) },
        )
        if (parts.isEmpty()) return null
        return "The event this message is about, “${event.title.take(MAX_TITLE)}” (eventKey $key): " + parts.joinToString("; ")
    }

    /** The tasks he asked to read in full (D-93), as your newest turn carries them: [WHOLE_TEXT] each at most, about [READ_TEXT] in all. */
    private fun readText(read: List<TaskItem>): String = buildString {
        append("The whole text of the tasks you asked to read (answer and act on it, and leave details empty):")
        val tasks = read.take(MAX_READ)
        // Each an even share of the room the shorter ones leave, so none is left too little to be worth reading.
        var room = READ_TEXT
        val shares = tasks.sortedBy { it.detail.trim().length }.mapIndexed { i, t ->
            val share = minOf(WHOLE_TEXT, room / (tasks.size - i))
            room -= minOf(share, t.detail.trim().length)
            t.id to share
        }.toMap()
        for (task in tasks) {
            val body = TaskText.whole(task, shares.getValue(task.id)) ?: "  (no text of its own)"
            append("\n- ${task.id} “${task.title.take(MAX_TITLE)}” (${TaskText.about(task)}):\n").append(body)
        }
    }

    private fun aboutLine(about: About): String = when {
        about.taskId != null -> "(About the task ${about.taskId}, “${about.taskTitle.orEmpty().take(MAX_TITLE)}”) "
        about.eventKey != null -> "(About the calendar event “${(about.eventTitle ?: about.eventKey).take(MAX_TITLE)}”, eventKey ${about.eventKey}) "
        about.day != null -> "(About ${runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day}) "
        else -> ""
    }

    /**
     * What the app knows now: today, the open tasks, the fortnight's events, and the changes in use;
     * since 16 Oct the student's own finished lately (to open again) and the [settings] he may change,
     * with their values; since 17 Oct their pace as [calibration] has learned it, and what each task's
     * minutes come to in the plan by it, so he allows for it in the minutes he sets (planned as he
     * sets them). The plan's sent beside it ([plan], [turns]).
     */
    fun context(tasks: List<TaskItem>, events: List<CalendarEvent>, applied: List<Instruction>, now: Long, zone: ZoneId, classMap: Map<String, String> = emptyMap(), different: List<String> = emptyList(), settings: Settings? = null, calibration: Calibration? = null): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        // A task's minutes, and where a pace is learned for its kind and class, what the plan makes of them:
        // the app's estimate stretched by it, or minutes given (the student's, Felix's), as they are.
        fun minutes(t: TaskItem): String {
            val pace = calibration?.multiplier(t.kind, t.className) ?: 1.0
            return when {
                abs(pace - 1.0) < 0.005 -> "${t.effortMin}"
                // Felix's paced total (#50's review): with the app's estimate now, where it's another, and
                // whether the task has changed since, so he can set it again.
                t.pacedMinutes -> "${t.effortMin}, as given: the app's ${t.userEffortPacedFrom} at their pace" +
                    (if (t.appEffortMin != t.userEffortPacedFrom) ", which it now puts at ${t.appEffortMin}" else "") +
                    (if (t.pacedTeaches) "" else ", the task changed since")
                t.yourMinutes || t.yourSteps -> "${t.effortMin}, as given"
                else -> "${t.effortMin}, planned as ${(t.effortMin * pace).roundToInt()} at their pace"
            }
        }
        val byId = tasks.associateBy { it.id }
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)} (${zone.id})")
        appendLine()
        appendLine("Open tasks (id | title | from | kind | due, a real deadline unless marked a plan, which is never overdue and moves on once it passes | minutes in all, with what the plan makes of them at their pace where it's learned | minutes worked so far | can't start before):")
        // By the date shown (the deadline in force, PL-14), so a plan's date gone by doesn't crowd nearer ones out of the list.
        val open = tasks.filter { it.isOpen && !it.hidden }.sortedBy { it.deadlineAt(now) ?: it.dueAt ?: Long.MAX_VALUE }
        if (open.isEmpty()) appendLine("(none)")
        open.take(InstructionPrompts.MAX_TASKS).forEach { t ->
            val start = listOfNotNull(t.availableFrom, t.userFrom, t.notBefore).maxOrNull()?.takeIf { it > now }
            // A date the app or Claude set is a plan, not a deadline (PL-14): said, so it's not called overdue.
            val due = (t.deadlineAt(now) ?: t.dueAt)?.let { at(it) + if (t.hardAt(now)) "" else " (a plan)" } ?: "no date"
            appendLine("- ${t.id} | ${t.title.take(MAX_TITLE)} | ${t.source.label} | ${t.kind.label} | $due | ${minutes(t)} | ${t.workedMin + t.photoMin} | ${start?.let(::at) ?: "-"}")
            // Its steps by their place, for a tick (AI-14).
            t.subSteps.take(OwnTasks.MAX_STEPS).forEachIndexed { i, step -> appendLine("    step $i: ${step.title.take(MAX_TITLE)} (${step.minutes} min)${if (step.done) ", done" else ""}") }
        }
        // Their pace (17 Oct): what the app's own estimates are planned by, never minutes given.
        if (calibration != null) {
            appendLine()
            appendLine("Their pace, learned from work they've finished (kind, and class where it's its own | how long their work takes against the app's own estimates): the plan stretches only the app's estimates by it.")
            val learned = calibration.multipliers.entries.sortedBy { it.key }
            if (learned.isEmpty()) appendLine("(nothing learned yet: the app's estimates are planned as they are)")
            learned.take(InstructionPrompts.MAX_TASKS).forEach { (key, pace) ->
                val kind = Kind.entries.firstOrNull { it.name == key.substringBefore('|') }?.label ?: key.substringBefore('|')
                val of = key.substringAfter('|').takeIf { it.isNotEmpty() }?.let { "$kind, ${it.take(MAX_TITLE)}" } ?: kind
                val from = calibration.timed[key]?.let { " (from $it timed)" }.orEmpty()
                appendLine("- $of | ×" + "%.2f".format(Locale.UK, pace).trimEnd('0').trimEnd('.') + from)
            }
        }
        // Work that looks the same from two sources (DX-15): a SameAs change keeps one.
        val alike = Duplicates.candidates(tasks, classMap, different)
        if (alike.isNotEmpty()) {
            appendLine()
            appendLine("Tasks that look like the same work (a SameAs change keeps one, if the student says so):")
            alike.take(InstructionPrompts.MAX_TASKS).forEach { (a, b) -> appendLine("- ${a.id} | ${b.id}") }
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
        // Their own finished lately (16 Oct), to open again if one wasn't.
        val finished = tasks.filter { (it.source == Source.Own || it.tickDone) && it.status == Status.Done && (it.doneAt ?: 0L) > now - FINISHED_MS }
            .sortedByDescending { it.doneAt }
        if (finished.isNotEmpty()) {
            appendLine()
            appendLine("Their own tasks finished in the last week (id | title | finished):")
            finished.take(MAX_FINISHED).forEach { appendLine("- ${it.id} | ${it.title.take(MAX_TITLE)} | ${at(it.doneAt!!)}") }
        }
        if (settings != null) {
            appendLine()
            appendLine("Settings you can change (setting | what it is | its value now):")
            FelixSettings.describe(settings).forEach { appendLine("- $it") }
        }
        // Each open task's own text, cut short (D-93): what it asks of them, and what comes with it.
        // Last, so it's what a context too long for its room loses first ([turns]).
        val withText = open.take(InstructionPrompts.MAX_TASKS).filter { !it.derived && TaskText.has(it) }
        if (withText.isNotEmpty()) {
            appendLine()
            appendLine("The tasks' own text (id | from, and what comes with it; then its text: an assignment's instructions, an email's conversation, a planner item's details, their notes), each at most $BRIEF_TEXT characters: a longer one as its reading's summary (each says of how much), or else its start and end (an email's newest replies are at its end):")
            var room = MAX_TEXTS
            var shown = 0
            for (t in withText) {
                val entry = "- ${t.id} | ${TaskText.about(t)}" + (TaskText.brief(t, BRIEF_TEXT, "    ")?.let { "\n$it" } ?: "")
                if (entry.length > room) break
                appendLine(entry)
                room -= entry.length
                shown++
            }
            if (shown < withText.size) appendLine("- …and ${withText.size - shown} more, left out for room")
        }
    }.trimEnd()

    /** Each open task's own text as the context gives it, at most (D-93). */
    const val BRIEF_TEXT = 600

    /** All of their texts in the context, at most. */
    private const val MAX_TEXTS = 12_000

    /** A task's whole text, as a message about it, or his asking to read it, carries it (D-93). */
    const val WHOLE_TEXT = 8_000

    /** The tasks one answer can ask to read in full, and all of their texts together, at most. */
    const val MAX_READ = 3
    private const val READ_TEXT = 16_000

    /** An event's place and its description, as a message about it carries them. */
    private const val EVENT_PLACE = 200
    private const val EVENT_TEXT = 500

    /** How far back, and how many, of the student's own finished tasks the chat's told of (16 Oct). */
    private const val FINISHED_MS = 7 * 24 * 3_600_000L
    private const val MAX_FINISHED = 15

    /** The plan's days sent in full: a fortnight, as the calendar's events are. */
    const val PLAN_DAYS = 14

    /**
     * [plan] as the Day and Work tabs show it, for questions about a day ("what's on tomorrow?"): the next
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
            "The plan: the work the app has them do each day so every deadline is met, as its Day and Work tabs show it. Its minutes are the app's, " +
                "from what it's learned of how long things take them, so they can differ from the task list's. While anything's planned for " +
                "today, blocked apps stay blocked in blocking hours; otherwise they cost free time earned by working. Each piece's [why: …] " +
                "says why it's on its day: duesoon (due today or tomorrow), overdue, spread (its share of the free time to its deadline), " +
                "minimumtouch (kept within a week of its piece before), revisiongap (a day after its revision piece before), mostroom (a " +
                "task in one piece, on the roomiest day before its deadline), soft (no due date: spare time this week), packed (its days " +
                "are too tight to spread it, so as late as they allow), kept (where its slot today already was, under way or still to come), " +
                "noroom (no room before its deadline).",
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
        val slots = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null }
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
                val parts = pieces.joinToString { "${it.shown?.take(MAX_TITLE) ?: "all of it"} (${it.minutes} min) [why: ${it.why.name.lowercase()}]" }
                appendLine("  - $id | ${first.title.take(MAX_TITLE)} | ${pieces.sumOf { it.minutes }} min: $parts | ${flags.joinToString(" | ")}")
            }
        }
    }.trimEnd()

    /** The most lines in each of the plan's summaries (what isn't planned, other events, later tasks), so they leave the days room. */
    const val PLAN_LIST = 20
}
