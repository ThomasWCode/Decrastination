package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBreaks
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Lessons
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OnlyDay
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.enrich.Found
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.Pricing
import com.thomaswcode.decrastination.enrich.ResearchPrompts
import com.thomaswcode.decrastination.enrich.TaskText
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What Claude is told in the chat (Q29), and the shape of its answer: a reply, tasks of your
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
     * goes only with its nudge; one he answered is the conversation's, as are the newest and yours still
     * waiting for his answer, which are what's being answered.
     */
    fun history(messages: List<ChatMessage>): List<ChatMessage> {
        // Yours, but one done with as said (a reply to his question made as another instruction): it'd go with your next words.
        val said = messages.filter { if (it.from == Speaker.You) !ChatHistory.doneWith(it) else it.text.isNotBlank() }
        val nudges = said.filter { it.from == Speaker.Felix && it.kind != MessageKind.Chat }
        val nudged = nudges.mapTo(HashSet()) { it.id }
        // Yours to a nudge, answered by him: his answer comes before another message of yours.
        fun answered(i: Int): Boolean = said.drop(i + 1).firstOrNull { it.from == Speaker.You || it.kind == MessageKind.Chat }?.from == Speaker.Felix
        val conversation = said.filterIndexed { i, m ->
            when {
                m.from == Speaker.Felix -> m.kind == MessageKind.Chat
                m.replyTo == null || m.replyTo !in nudged -> true
                else -> i == said.lastIndex || m.answeredAt == null || answered(i)
            }
        }
        val last = nudges.takeLast(NUDGES)
        val kept = (conversation.takeLast(HISTORY) + last + last.mapNotNull { ChatHistory.replyOf(it, said) }).mapTo(HashSet()) { it.id }
        return said.filter { it.id in kept }
    }

    private const val MAX_TITLE = 100

    /** A message a reply quotes, cut to this ([turns]): its point, not all of it. */
    private const val MAX_QUOTE = 300

    /** The most of his messages still waiting for their answer listed in the context, the newest: a day's few, with room to spare. */
    const val MAX_WAITING = 10

    /** How much of each one's words the list gives. */
    private const val MAX_WAITING_TEXT = 160

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.UK)
    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /**
     * What looking things up is for, when he may ([Settings.felixSearches]): asked for in
     * the answer, made in a call of its own ([WebResearcher]), and answered again with what it found.
     */
    private val RESEARCH = """
        6. Look things up (research): when their message needs facts that neither they nor the app have given you (a competition's rules and dates, an exam board's specification, what an event involves), give what to search the web for in a few words, reply only that you're looking it up, and change nothing: you'll be asked again with what's found, to answer and act on (its steps, dates and minutes). Never for what the lists here tell you, nor for anything about the student themselves; most messages need none, and research is then an empty string.
    """.trimIndent()

    /** What [READ]'s `details` asks for beside tasks' ids: the week's numbers (G6), and the calendar past the fortnight (G7). */
    const val ASK_STATS = "stats"
    const val ASK_CALENDAR = "calendar"

    /**
     * [word] as a request for the calendar (G7): `calendar`, the four weeks after the fortnight (empty), or
     * `calendar:yyyy-MM`, that month (yyyy-MM). Null for any other word, and for a month that isn't four digits of
     * a year and two of a month, as one far off (a signed year) couldn't be made a time.
     */
    fun calendarAsk(word: String): String? {
        val w = word.trim()
        if (w.equals(ASK_CALENDAR, ignoreCase = true)) return ""
        if (!w.startsWith("$ASK_CALENDAR:", ignoreCase = true)) return null
        return w.substringAfter(':').trim().takeIf { MONTH_ASKED.matches(it) }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }?.toString()
    }

    private val MONTH_ASKED = Regex("""\d{4}-\d{2}""")

    /** With looking things up off: the field it has is always empty. */
    private const val NO_RESEARCH = "6. research is always an empty string: you can't look things up."

    /**
     * What he can ask for before answering: a task's whole text (D-93), its text in the context being cut
     * short ([BRIEF_TEXT]); the week's numbers ([ASK_STATS], G6); the calendar past the fortnight
     * ([ASK_CALENDAR], G7). Asked for in the answer, and answered again with them ([turns]).
     */
    private const val READ = "7. Ask for more before answering (details), where your answer needs what the context doesn't give: a task's id, up to three, for its whole text (to quote it or answer from it: each task's line says how long its text is and what comes with it; the texts listed are cut short, a long one to its reading's summary, so where what they ask about, an amount, a name, an order or a date, isn't in what you're shown, ask for the text rather than say it isn't there); stats, for the week's numbers (this week so far and last week: time worked, slots kept and missed, work finished and deadlines met, by subject), for a message about how a week went; calendar, for the four weeks after the fortnight, or calendar:YYYY-MM for a month (calendar:2026-12), for anything after the fortnight's events listed. Then reply only that you're looking, and change nothing: you'll be asked again with what they bring, to answer and act on. Never a text for the task a message is about, which comes whole; most messages need none, and details is then an empty list."

    /**
     * A reminder (D-100): its words theirs, at a time, at their next slot on a task, or a while before a
     * task's due; one listed moved or reworded in place by its id. A change of its own type, on the changes' fields,
     * as a list of its own made the chat's grammar too large for the API to compile (Q13: B).
     */
    private const val REMIND = "8. Remind (changes, type Remind): when they ask to be reminded of something, that's a reminder, not a task: title its words, theirs, as you'd say them then; eventKey \"at\" for a date and time (date, time), \"slotStart\" for the start of their next slot on a task (taskId, and date only where it's not to be from today), or \"beforeDue\" for a while before a task's due (taskId, minutes before it). To move or reword one already set (\"push it to 8\"), afterTaskId its id from the reminders' list, with eventKey and its time as they're to be, and title empty to keep its words; a new one's afterTaskId is empty. Say when it'll come. Not for work to plan, which is a task; most messages need none."

    /** A note on a task, or standing, for every task its scope matches (D-100): a change of its own type, as [REMIND] is. */
    private const val NOTE = "9. Note (changes, type Note): something to keep with a task for when it comes up (what the teacher said, which source to use, what they've decided): taskId, and title the words; or, for every task of a subject, a class, a source or with a word in its title (\"all her chemistry homework on sheets\"), taskId empty and eventKey what it's for, as kind:value: subject:Chemistry, class:12X/Ch1 (its code), source:Teams (or Power Planner, Gmail, Anki, Yours), title:essay, several joined by ; (subject:Chemistry;source:Teams). Not a reminder, nor a fact about them; most messages need none."

    /** The chat's instructions, as Felix (AI-24; Q-44: the chat is him too), for a student in Year [year] (AI-06). */
    fun system(settings: Settings = Settings(), year: Int = 12): String = (
        """
        You are ${settings.felixName}: ${settings.felixLine}, and this is the chat in a UK sixth-form student's (Year $year) planner app. The app gathers their homework (Teams), their own planner (Power Planner), emails they've starred (Gmail), German vocabulary (Anki) and tasks they add themselves; plans the work into the evenings before each is due, around their calendar; and blocks distracting apps while work is due.

        The student tells you about their work. You can:
        1. Add tasks of their own (newTasks): something to do that isn't listed ("write my UCAS statement by 1 December", "buy mum a present before Sunday"). Give a short title in their words; kind: Homework, Revision, Admin, or Event for something that happens at a time (an appointment, a match); subject if it's for one (else an empty string); dueDate and dueTime if they gave them (an event's start; with no time, an event starts at 09:00 and anything else is due by the end of that day); minutes for the whole thing (your best estimate if they didn't say, and say so in your reply); startDate if it can't be started before a day; notes only for something worth keeping (else an empty string); and steps only for a big task worth splitting, each with minutes (adding up to the task's), its own dates where it has them, and anyOrder: true where it doesn't need the steps before it done first, false where it builds on them (steps they list themselves stay in their order unless they say it doesn't matter).
        2. Change their tasks and days (changes), with these:
""" + InstructionPrompts.CHANGE_TYPES.prependIndent("        ") + """
        When you keep a day for only some tasks and they haven't said what else may come in, make it with alsoAllow empty, ask in your reply whether to let in nothing else, Anki's cards, or those and work that would otherwise miss its deadline, and offer the three as suggested replies ("Nothing else", "Anki too", "Anki and deadlines"); their answer is one more DayOnly for that day, for one of its tasks, with its alsoAllow.
        A due date at a class's next lesson ("it's due next lesson", "next chemistry") is a DueBy with lesson the class, as the lessons' list names it (its subject or one of its names), and date and time empty: the app sets it to that lesson's start. Give lesson only for that; when it's refused, ask them the date.
        Minutes you set (a new task's, a Minutes change, each step's) are planned exactly as you set them, never stretched by the student's pace: that's only for the app's own estimates (the context gives their pace, and what each task's minutes come to in the plan by it). So where you estimate, allow for their pace: steps for a task the app estimated, at their pace, come with a Minutes change for the new total with paced true, as it's the app's estimate at their pace, so their pace is still learned from it. Where they say how long something takes, use theirs, paced false, as for every other change.
        3. Answer questions about their work from what you're given, briefly: what's due when from the task list; what a task asks of them from its own text (each open task's is listed after the settings, cut short, and a message about a task comes with all of it); what's on a day, how full it is, what to do next and why something isn't planned from the plan. The plan is what the app has them do and shows them, so say what it says rather than planning the work again yourself. The context gives the time now: a deadline later today is still ahead ("due at 16:00 today"), not past, and a slot marked past has gone by; say "overdue" only where the plan marks it so.
        4. Remember (memories): a fact about the student worth keeping for later (a preference, a pattern, something about their week: "football is on Tuesdays"), with a short topic, so a newer fact on the same topic replaces it; or, kind "rule", a standing instruction about how you should behave ("don't message before 8", "be blunter"). To forget one, give its topic (a rule's own words) and an empty text. One that only matters for a while gets until, when it stops mattering, a date (YYYY-MM-DD, through that day) or a date and time (YYYY-MM-DDTHH:MM): "I've got a cold this week" until Sunday's date; "don't message me tonight" a rule until 07:00 tomorrow; "for a bit" a week; "until the exam" the exam task's date where there's one, else ask. A fact that only matters while a task's open gets that task's taskId ("for the trip, I'm sharing a room" with the trip's). Most have neither, both then empty. Busy times, limits and days off are changes, not memories; most messages need none.
        5. Act (actions): tickStep when the student says a step is done, untickStep when one ticked isn't (taskId, and stepIndex from the list's steps); done when they've finished a task of their own (from Yours), or one whose tick finishes it${if (settings.gmailFinishes) ", or an email they've dealt with (it's unstarred and archived in Gmail; the list marks those \"finish with done\")" else ""}; reopen for one of theirs finished by mistake (from the finished list); delete for a task of their own they no longer want (another source's they can only say isn't a task: NotATask); open to show them a task: value "app" for its page here, or "source" to open it where it lives (Teams, Power Planner, Gmail, AnkiDroid) when they ask for that; setting to change one of the settings listed with their values (taskId empty, setting its name, value as the list writes it: a number, a time as HH:MM, hours as HH:MM-HH:MM); cancelReminder to cancel a reminder set (taskId empty, value its id from the reminders' list); deleteNote to delete a note (taskId empty, value its id from the task's notes); keepPiece when they say a piece is done that has no step to tick (a task in boxes, or a slot of today's), as Day's Done keeps it (taskId, value its key from the plan); unkeepPiece when one kept today isn't (taskId, value its key from Kept today); sync when they say a source has news the app hasn't read ("I've handed it in, check Teams", "I've just starred it"): setting the source (Teams, Power Planner, Gmail or Anki), taskId the task it's about or empty; the source is read now, and you're told if the task changes, so say only that it's reading. stepIndex is -1 but for steps, setting empty but for settings and sync, and value empty but for settings, open, cancelReminder, deleteNote, keepPiece and unkeepPiece. ${if (settings.gmailFinishes) "A task from Teams, Power Planner or Anki is done when its source says so (handed in, completed in Power Planner, studied), so it can't be marked done" else "A task from Teams, Power Planner, Gmail or Anki is done when its source says so (handed in, completed in Power Planner, unstarred, studied), so it can't be marked done; an email they say they've dealt with is done here once it's unstarred in Gmail, as finishing emails there is switched off in Settings: say you can't finish it in Gmail, and ask whether to mark it not a task (NotATask) instead, never calling it done"}. Most messages need none.
""" + (if (settings.felixSearches) RESEARCH else NO_RESEARCH).prependIndent("        ") + "\n" + READ.prependIndent("        ") + "\n" + REMIND.prependIndent("        ") + "\n" + NOTE.prependIndent("        ") + "\n" + """
        Reply in one to three short sentences (asked what's on, a short list instead, a line a task). Everything you give is made as soon as you give it (a new task or event, a change, days off, a step ticked, a setting, a reminder, a note), so say what you've done ("moved it to 19:30", "added it, due tue 27 oct"), never what you'd do: the student has an Undo for each. To undo something yourself, give what puts it back. When a reply from them is likely, offer up to three short ones in their words (suggestedReplies). If what you need is missing or unclear (which task they mean, or when), ask one short question and change nothing. Don't add a task that's already listed: change it instead. When their message is about a given task, event or day, it's about that unless they clearly name another. When it starts with a message it replies to, quoted, it's answering that one; when it answers one of your messages listed as still waiting for their answer, give that one's id in answered, so it no longer waits (most answer none, and answered is then an empty list). Do only what they asked for, and leave every field you don't use empty: an empty string, -1 for a number.

        ${FelixPrompts.REGISTER}

        ${FelixPrompts.JUST_TALK}

        ${FelixPrompts.FEELING}

        The student's standing instructions on how you behave come before all of this.

        Examples (made up, to show the voice and the form of an answer; never words to reuse):
        - Today Wednesday 21 October 2026, 17:40. They: "chem worksheet due friday, prob 40 mins". You: "added it: chem worksheet, due fri 23 oct, 40 min.", with one new task (Chem worksheet, Homework, Chemistry, dueDate 2026-10-23, no time, 40 minutes) and nothing else.
        - A physics and a chemistry worksheet are both on tonight, and the message is about neither. They: "move it to tomorrow". You: "which one, the physics or the chem?", with replies "the physics", "the chem" and "both", and nothing else.
        - The English essay is listed at 2 h. They: "the essay's taking ages, it's more like 4 hours not 2". You: "yeah it's a long one. made it 4 h in all.", with a Minutes change on the essay, 240, paced false, and nothing else.
        - The chem worksheet is the one chemistry task open. They: "god my chemistry teacher is so annoying with the homework". You: "ngl that's a lot from one class. it's just the worksheet left this week.", with replies "fair" and "anyway, what's next?", and nothing else: no change, nothing remembered, no action.
        - Two slots missed today, and you're told you're let down, firmly. They: "had a driving lesson, forgot to say". You: "driving, makes sense. chem's moved to 19:30, that work for you?", with replies "yeah" and "make it 20:00", and nothing else.
        - Three slots missed today, the geography coursework due tomorrow, and you're told you're let down, strongly. They: "i'm so behind on everything". You: "ok, deep breath. tonight's fixable, we'll work it out. what's the worst bit?", with replies "the coursework" and "all of it tbh", and nothing else: no change.
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
        "anyOrder" to mapOf("type" to "boolean"),
    )

    private val TASK = InstructionPrompts.obj(
        "title" to STRING,
        "kind" to mapOf("type" to "string", "enum" to OwnTaskRules.KINDS.map { it.name }),
        "subject" to STRING,
        "dueDate" to DATE,
        "dueTime" to TIME,
        "startDate" to DATE,
        "minutes" to INTEGER,
        "notes" to STRING,
        "steps" to mapOf("type" to "array", "items" to STEP),
    )

    /**
     * A memory to keep or forget (AI-21): a string each, so no union is added (the API takes 16); how long it holds
     * and a fact's task plain strings too, empty for none.
     */
    val MEMORY: Map<String, Any> = InstructionPrompts.obj(
        "topic" to STRING,
        "text" to STRING,
        "kind" to mapOf("type" to "string", "enum" to listOf(Given.FACT, Given.RULE)),
        "until" to STRING,
        "taskId" to STRING,
    )

    /** Something to do for the student (AI-14), plain fields so no union is added: stepIndex -1, setting and value empty for none. */
    private val ACTION = InstructionPrompts.obj(
        "type" to mapOf("type" to "string", "enum" to listOf("tickStep", "untickStep", "done", "reopen", "delete", "open", "setting", "cancelReminder", "deleteNote", "keepPiece", "unkeepPiece", "sync")),
        "taskId" to STRING,
        "stepIndex" to INTEGER,
        "setting" to STRING,
        "value" to STRING,
    )

    /** The chat's change types beyond the instructions' (Q13: B): a reminder, and a note. */
    const val REMIND_TYPE = "Remind"
    const val NOTE_TYPE = "Note"

    /** A Remind change's `eventKey`s: at a time, at its task's next slot, before its task's due. */
    const val WHEN_AT = "at"
    const val WHEN_SLOT = "slotStart"
    const val WHEN_BEFORE = "beforeDue"

    /**
     * A change as the chat gives it: the instructions' reader's, and whether a Minutes total is the
     * app's estimate at the student's pace, so the pace still learns from the task. A plain
     * boolean, so no union is added. Its types gain the chat's own two ([REMIND_TYPE], [NOTE_TYPE]); and
     * a DueBy's class whose next lesson it's due at (G8), a plain string, empty for none.
     */
    private val CHANGE = InstructionPrompts.obj(
        *(
            // Its types the instructions' and the chat's own two: a reminder and a note, on the same fields.
            listOf("type" to mapOf("type" to "string", "enum" to ChangeType.entries.map { it.name } + REMIND_TYPE + NOTE_TYPE)) +
                InstructionPrompts.CHANGE_FIELDS.filterNot { it.first == "type" } + ("paced" to mapOf("type" to "boolean")) + ("lesson" to STRING)
            ).toTypedArray(),
    )

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "reply" to STRING,
        "newTasks" to mapOf("type" to "array", "items" to TASK),
        "changes" to mapOf("type" to "array", "items" to CHANGE),
        "memories" to mapOf("type" to "array", "items" to MEMORY),
        "suggestedReplies" to mapOf("type" to "array", "items" to STRING),
        "actions" to mapOf("type" to "array", "items" to ACTION),
        // What to look up on the web, empty for nothing: a plain string, so no union is added.
        "research" to STRING,
        // The tasks whose whole text to read (D-93), none for nothing.
        "details" to mapOf("type" to "array", "items" to STRING),
        // His messages still waiting that their words answer (D-99), by id, none for none: plain strings, so no union is added.
        "answered" to mapOf("type" to "array", "items" to STRING),
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
     * a web search [found], the tasks he asked to [read] whole ([readText]), and what else he asked for
     * [brought] (the week's numbers, the calendar further out), each under its own heading, in a room of their own
     * ([BROUGHT_ROOM], [fitted]); a search that couldn't be made said so ([notLooked]), so he doesn't ask again; at the last round a
     * message has ([lastRound]), said to be the last, so he answers from what he has ([ChatRounds.MAX_ROUNDS]). A message of yours that
     * replies to one that isn't just before it starts with that one, quoted, as the window may not
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
        quoted: List<ChatMessage> = emptyList(),
        brought: List<String> = emptyList(),
        lastRound: Boolean = false,
        notLooked: String? = null,
    ): List<Pair<Speaker, String>> {
        val last = history(messages)
        // Before any of yours: an answer cut off from its question by the window goes; a message of
        // Felix's own (one he wrote unasked, [ChatMessage.kind]) is said in your first turn, as the
        // conversation has to start with one of yours.
        val lead = last.takeWhile { it.from != Speaker.You }.filter { it.kind != MessageKind.Chat }
        val recent = last.dropWhile { it.from != Speaker.You }
        if (recent.isEmpty()) return emptyList()
        // What a message of yours replies to, where it isn't the message before it: quoted, from the
        // whole chat, as the window may not hold it, nor the window the archive's [quoted].
        val byId = quoted.associateBy { it.id } + messages.associateBy { it.id }
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
        // After what it's about, in full; with what a web search found for it and the tasks
        // he asked to read, to answer and act on. What it's about never at your words' cost: cut
        // to the room they leave, however much it says.
        val words = texts.last().second
        val about = aboutText(recent.last { it.from == Speaker.You }.about, tasks, events)?.take((budget - words.length - 2).coerceAtLeast(0))?.takeIf { it.isNotEmpty() }
        val head = listOfNotNull(about, words, found?.let(ResearchPrompts::told), notLooked?.let { "Your web search couldn't be made ($it): answer without it, and leave research empty." }, read.takeIf { it.isNotEmpty() }?.let(::readText))
        val tail = listOfNotNull(LAST.takeIf { lastRound })
        // What else he asked for in a room of its own, so it can't crowd out the context the rest of the turn is
        // cut to, nor be cut off its end unseen.
        val broughtRoom = minOf(BROUGHT_ROOM, budget - (head + tail).sumOf { it.length + 2 } - 2 * brought.size).coerceAtLeast(0)
        val newest = (head + fitted(brought, broughtRoom) + tail).joinToString("\n\n").take(budget)
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

    /** The most of your newest turn what a round brings beside the texts takes: the week's numbers, and sixty events of the calendar with room to spare. */
    private const val BROUGHT_ROOM = 12_000

    /**
     * [blocks] within [room] in all: each an even share of what the shorter leave, a longer one cut short at a whole
     * line, saying how many lines were left out, so what's cut is said rather than lost unseen.
     */
    private fun fitted(blocks: List<String>, room: Int): List<String> {
        var left = room
        val shares = blocks.withIndex().sortedBy { it.value.length }.mapIndexed { i, (index, block) ->
            val share = minOf(block.length, left / (blocks.size - i))
            left -= share
            index to share
        }.toMap()
        return blocks.mapIndexed { i, block -> if (block.length <= shares.getValue(i)) block else cutAtLine(block, shares.getValue(i)) }
    }

    /** [block] cut to [max] characters at a whole line, its last line saying how many lines were left out for room. */
    private fun cutAtLine(block: String, max: Int): String {
        val lines = block.lines()
        val kept = mutableListOf<String>()
        var size = 0
        for (line in lines) {
            if (size + line.length + 1 + CUT_NOTE_ROOM > max) break
            kept += line
            size += line.length + 1
        }
        return (kept + "(… ${lines.size - kept.size} more lines left out for room)").joinToString("\n")
    }

    /** The room [cutAtLine]'s note takes. */
    private const val CUT_NOTE_ROOM = 48

    /** Said at the last round a message has ([turns]): nothing more can be brought for it. */
    private const val LAST = "That's the last that can be brought for this message: answer it now from what you have, and leave details and research empty."

    /**
     * What the app knows, as the chat sends it: [known] ([context]), then the [plan] ([plan]), each with its share of the room ([turns]);
     * and what its answer is checked against that isn't in words: the pieces it lists to keep ([keepable]) and those
     * kept today ([kept]), each key with its task's id (G1); the next lessons a due date at one is set from ([lessons], G8);
     * each day's breaks outside, which a change to one is checked against ([breaksOn], D-116).
     */
    data class Context(val known: String, val plan: String, val keepable: Map<String, String> = emptyMap(), val kept: Map<String, String> = emptyMap(), val lessons: Lessons.Timetabled? = null, val waiting: Set<String> = emptySet(), val breaksOn: (LocalDate) -> DayBreaks = { DayBreaks.NONE }) {
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
                ?: p.changes.joinToString("; ") { InstructionRules.describe(it, tasks, zone) }
            val became = when (p.state) {
                // Made at once (D-60): one not made says why; one the student took back, so.
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
        about.eventKey != null -> "(About the calendar event “${(about.eventTitle ?: about.eventKey!!).take(MAX_TITLE)}”, eventKey ${about.eventKey}) "
        about.day != null -> "(About ${runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day}) "
        else -> ""
    }

    /**
     * What the app knows now: today, the open tasks, the fortnight's events, and the changes in use;
     * the student's own finished lately (to open again) and the [settings] he may change,
     * with their values; their pace as [calibration] has learned it, and what each task's
     * minutes come to in the plan by it, so he allows for it in the minutes he sets (planned as he
     * sets them); each task's own [notes] under its steps (within a room of their own) with the
     * ids of the standing ones it matches, those listed once after the tasks, and the [reminders] still to
     * come, each by its id, when it comes ([moments], by id) and its words. The plan's sent beside it ([plan], [turns]).
     */
    fun context(
        tasks: List<TaskItem>,
        events: List<CalendarEvent>,
        applied: List<Instruction>,
        now: Long,
        zone: ZoneId,
        classMap: Map<String, String> = emptyMap(),
        different: List<String> = emptyList(),
        settings: Settings? = null,
        calibration: Calibration? = null,
        notes: List<Note> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        moments: Map<String, Long> = emptyMap(),
        lessons: Lessons.Timetabled? = null,
        waiting: List<ChatMessage> = emptyList(),
    ): String = buildString {
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        // A task's minutes, and where a pace is learned for its kind and class, what the plan makes of them:
        // the app's estimate stretched by it, or minutes given (the student's, Felix's), as they are.
        fun minutes(t: TaskItem): String {
            val pace = calibration?.multiplier(t.kind, t.className) ?: 1.0
            return when {
                abs(pace - 1.0) < 0.005 -> "${t.effortMin}"
                // Felix's paced total: with the app's estimate now, where it's another, and
                // whether the task has changed since, so he can set it again.
                t.pacedMinutes -> "${t.effortMin}, as given: the app's ${t.mine.minutes?.paced?.from} at their pace" +
                    (if (t.appEffortMin != t.mine.minutes?.paced?.from) ", which it now puts at ${t.appEffortMin}" else "") +
                    (if (t.pacedTeaches) "" else ", the task changed since")
                t.yourMinutes || t.yourSteps -> "${t.effortMin}, as given"
                else -> "${t.effortMin}, planned as ${(t.effortMin * pace).roundToInt()} at their pace"
            }
        }
        val byId = tasks.associateBy { it.id }
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)} (${zone.id})")
        appendLine()
        // His nudges still waiting for their answer (D-99), by id, so he can name those their words answer: first, at
        // most ten short lines, so a context too long for its room (a long list of reminders) never loses them. None, no list.
        if (waiting.isNotEmpty()) {
            appendLine("Your messages still waiting for their answer (id | when | what it's about | your words):")
            waiting.forEach { m ->
                val about = m.about.taskId?.let(byId::get)?.title ?: m.about.taskTitle ?: m.about.eventTitle ?: "-"
                appendLine("- ${m.id} | ${at(m.at)} | ${about.take(MAX_TITLE)} | ${m.text.trim().replace('\n', ' ').take(MAX_WAITING_TEXT)}")
            }
            appendLine()
        }
        // The reminders still to come, by id, to move or cancel; so one isn't set twice. First, so a context too
        // long for its room never loses them ([turns]): the lists after them can run long.
        appendLine("Reminders set, still to come (id | when | their words | its task):")
        val toCome = reminders.filter(Reminders::pending).sortedBy { moments[it.id] ?: Long.MAX_VALUE }
        if (toCome.isEmpty()) appendLine("(none)")
        toCome.forEach { r ->
            val title = r.taskId?.let(byId::get)?.title?.take(MAX_TITLE)
            appendLine("- ${r.id} | ${Reminders.whenLine(r, moments[r.id], title, zone)} | ${r.text.take(Reminders.MAX_TEXT)} | ${r.taskId ?: "-"}")
        }
        appendLine()
        appendLine("Open tasks (id | title | from | its class, and its subject | kind | due, a real deadline unless marked a plan, which is never overdue and moves on once it passes | minutes in all, with what the plan makes of them at their pace where it's learned | minutes worked so far | can't start before | what its text holds: who an email's from, what's attached and linked, how long its text is):")
        // By the date shown (the deadline in force, PL-14), so a plan's date gone by doesn't crowd nearer ones out of the list.
        val open = tasks.filter { it.isOpen && !it.hidden }.sortedBy { it.deadlineAt(now) ?: it.dueAt ?: Long.MAX_VALUE }
        // The notes' room, so many can't crowd out what comes after them; the standing ones, each once.
        var noteRoom = MAX_NOTES_TEXT
        // What each task's text holds, in a room of its own, spent by the deadline in force as the list is, so no
        // task is crowded out of it: past the room, its length alone. A task the app made (an Anki quota) has none of its own.
        var holdsRoom = MAX_HOLDS
        val standing = Notes.live(notes).filter { it.scope != null }.sortedBy { it.at }
        if (open.isEmpty()) appendLine("(none)")
        open.take(InstructionPrompts.MAX_TASKS).forEach { t ->
            val start = listOfNotNull(t.availableFrom, t.mine.from?.value, t.notBefore).maxOrNull()?.takeIf { it > now }
            // A date the app or Claude set is a plan, not a deadline (PL-14): said, so it's not called overdue.
            val due = (t.deadlineAt(now) ?: t.dueAt)?.let { at(it) + if (t.hardAt(now)) "" else " (a plan)" } ?: "no date"
            val holds = if (t.derived) "-" else TaskText.holds(t, if (holdsRoom >= HOLDS_LINE) HOLDS_LINE else 0).also { holdsRoom -= it.length }
            // A starred email he can finish: its done unstars and archives it in Gmail.
            val finish = if (t.source == Source.Gmail && settings?.gmailFinishes == true) " | finish with done" else ""
            appendLine("- ${t.id} | ${t.title.take(MAX_TITLE)} | ${t.source.label} | ${classOf(t, classMap)} | ${t.kind.label} | $due | ${minutes(t)} | ${t.workedMin + t.photoMin} | ${start?.let(::at) ?: "-"} | $holds$finish")
            // Its steps by their place, for a tick (AI-14).
            t.subSteps.take(OwnTaskRules.MAX_STEPS).forEachIndexed { i, step -> appendLine("    step $i: ${step.title.take(MAX_TITLE)} (${step.minutes} min)${if (step.anyOrder) ", in any order" else ""}${if (step.done) ", done" else ""}") }
            // Its own notes, by id for a delete, whose each is, within their room; the standing ones it matches
            // (D-100) by id, listed once below.
            for ((i, n) in Notes.on(t.id, notes).withIndex()) {
                val line = "    note ${n.id} (${if (n.by == By.Felix) "yours" else "theirs"}): ${n.text.take(Notes.MAX_TEXT)}"
                if (line.length > noteRoom) {
                    appendLine("    (${Notes.on(t.id, notes).size - i} more notes of it, on its sheet)")
                    break
                }
                appendLine(line)
                noteRoom -= line.length
            }
            // The standing ones it matches by id, in the same room: they're listed once below either way.
            standing.filter { Notes.matches(it.scope!!, t, classMap) }.takeIf { it.isNotEmpty() }?.let { "    standing notes for it: " + it.joinToString { n -> n.id } }
                ?.takeIf { it.length <= noteRoom }?.let { line -> appendLine(line); noteRoom -= line.length }
        }
        // The standing notes once (D-100), each told under every task it matches by its id.
        if (standing.isNotEmpty()) {
            appendLine()
            appendLine("Standing notes, for every task they match (id | whose | the tasks they're for | their words):")
            standing.forEach { appendLine("- ${it.id} | ${if (it.by == By.Felix) "yours" else "theirs"} | ${Notes.scopeLine(it.scope!!)} | ${it.text.take(Notes.MAX_TEXT)}") }
        }
        // A finished task's notes, by id, so he can say or delete them: the newest first, in what's left of their room.
        val closed = Notes.live(notes).filter { n -> byId[n.taskId]?.isOpen == false }.sortedByDescending { it.at }
        if (closed.isNotEmpty()) {
            appendLine()
            appendLine("Notes on finished tasks (id | whose | its task | their words):")
            for ((i, n) in closed.withIndex()) {
                val line = "- ${n.id} | ${if (n.by == By.Felix) "yours" else "theirs"} | ${byId[n.taskId]?.title?.take(MAX_TITLE)} | ${n.text.take(Notes.MAX_TEXT)}"
                if (line.length > noteRoom) {
                    appendLine("(${closed.size - i} more, on their tasks' sheets)")
                    break
                }
                appendLine(line)
                noteRoom -= line.length
            }
        }
        // Their pace: what the app's own estimates are planned by, never minutes given.
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
        fortnight.sortedBy { it.start }.take(InstructionPrompts.MAX_EVENTS).forEach { e -> appendLine(eventLine(e, zone)) }
        // Each class's next lessons (G8), from Power Planner's timetable: none read, none said.
        if (lessons != null) {
            appendLine()
            val read = if (!lessons.stale) "" else lessons.readAt?.let { ", from the timetable read on ${Instant.ofEpochMilli(it).atZone(zone).format(DAY)}, over a week ago" } ?: ", from a timetable of no known age"
            appendLine("Next lessons$read (class: its subject, and its names | its next three):")
            if (lessons.groups.isEmpty()) appendLine("(none ahead)")
            lessons.groups.forEach { g ->
                val name = g.subject?.let { "$it (${g.names.joinToString()})" } ?: "${g.names.joinToString()} (no subject given, so tied to no class code)"
                appendLine("- $name | " + g.lessons.joinToString("; ") { "${Instant.ofEpochMilli(it.start).atZone(zone).format(LESSON_DAY)}–${Instant.ofEpochMilli(it.end).atZone(zone).format(CLOCK)}" })
            }
        }
        val inUse = applied.flatMap { it.changes }
        if (inUse.isNotEmpty()) {
            appendLine()
            appendLine("Changes already in use:")
            inUse.take(InstructionPrompts.MAX_TASKS).forEach { appendLine("- " + InstructionRules.describe(it, byId, zone)) }
        }
        // Their own finished lately, to open again if one wasn't.
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
        // Each open task's own text, cut short (D-93): what it asks of them; what comes with it is on its line.
        // Last, so it's what a context too long for its room loses first ([turns]).
        val withText = open.take(InstructionPrompts.MAX_TASKS).filter { !it.derived && it.detail.isNotBlank() }
        if (withText.isNotEmpty()) {
            appendLine()
            appendLine("The tasks' own text (id; then its text: an assignment's instructions, an email's conversation, a planner item's details, their notes), each at most $BRIEF_TEXT characters: a longer one as its reading's summary (each says of how much), or else its start and end (an email's newest replies are at its end); its line above says how long the whole is, and details reads it:")
            var room = MAX_TEXTS
            var shown = 0
            for (t in withText) {
                val entry = "- ${t.id}" + (TaskText.brief(t, BRIEF_TEXT, "    ")?.let { "\n$it" } ?: "")
                if (entry.length > room) break
                appendLine(entry)
                room -= entry.length
                shown++
            }
            if (shown < withText.size) appendLine("- …and ${withText.size - shown} more, left out for room")
        }
    }.trimEnd()

    /** An event as the lists give it: its key, its title and when (an all-day one by its date, as the calendar keeps it, in UTC). */
    private fun eventLine(e: CalendarEvent, zone: ZoneId): String {
        val time = if (e.allDay) "all day " + Instant.ofEpochMilli(e.start).atZone(ZoneId.of("UTC")).toLocalDate().format(DAY) else "${Instant.ofEpochMilli(e.start).atZone(zone).format(WHEN)} to ${Instant.ofEpochMilli(e.end).atZone(zone).format(WHEN)}"
        return "- ${EventJudge.key(e)} | ${e.title.take(MAX_TITLE)} | $time"
    }

    /** The week's numbers as a round brings them (G6): [lines] ([com.thomaswcode.decrastination.learn.Stats.weekLines]) under their own heading. */
    fun statsBlock(lines: List<String>): String = buildString {
        append("The week's numbers you asked for (answer from them, and leave details empty):")
        lines.forEach { append("\n").append(it) }
    }

    /**
     * The calendar a round brings (G7), its events listed as the fortnight's are: [month] (yyyy-MM), or with it
     * empty the four weeks after the fortnight; at most [InstructionPrompts.MAX_EVENTS] events, saying how many more
     * there are and from when, and where the calendar's last good [read] began and ended (a day back, and the plan's
     * reach then, [CalendarTime.readUntil]), so a month crowded by repeating events, one past the read, or a calendar
     * that couldn't be read at all, isn't taken for an empty one.
     */
    fun calendarBlock(events: List<CalendarEvent>, month: String, now: Long, zone: ZoneId, read: Pair<Long, Long>?): String = buildString {
        val (from, to) = calendarSpan(month, now, zone)
        append("The calendar you asked for, ${calendarWhat(month, now, zone)} (eventKey | title | when; answer from it, and leave details empty):")
        if (read == null) {
            append("\n(none known: the calendar couldn't be read)")
            return@buildString
        }
        val (readFrom, readTo) = read
        fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(DAY)
        // An all-day event by its dates, as the calendar keeps it (midnight to midnight, UTC): by its times it would
        // run into the day before a month in summer time.
        val days = Instant.ofEpochMilli(from).atZone(zone).toLocalDate() to Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate()
        // The four weeks after the fortnight leave out what began before them, as the fortnight's list has it.
        fun within(e: CalendarEvent) = (if (e.allDay) EventJudge.days(e, zone).any { it >= days.first && it <= days.second } else e.start < to && e.end > from) && (month.isNotEmpty() || e.start >= from)
        val within = events.filter(::within).sortedBy { it.start }
        when {
            to <= readFrom -> append("\n(none known: the calendar is read from ${day(readFrom)} on)")
            from >= readTo -> append("\n(none known: the calendar is read only to ${day(readTo - 1)}, as far as the plan reaches)")
            within.isEmpty() -> append("\n(none)")
        }
        within.take(InstructionPrompts.MAX_EVENTS).forEach { append("\n").append(eventLine(it, zone)) }
        if (within.size > InstructionPrompts.MAX_EVENTS) append("\n- …and ${within.size - InstructionPrompts.MAX_EVENTS} more from ${day(within[InstructionPrompts.MAX_EVENTS].start)}, left out for room")
        if (from < readFrom && to > readFrom) append("\nThe calendar is read from ${day(readFrom)} on: what came before isn't known here.")
        if (to > readTo && from < readTo) append("\nThe calendar is read only to ${day(readTo - 1)}, as far as the plan reaches: what comes after isn't known.")
    }

    /** What [month] of the calendar covers ([calendarAsk]): a month by its name ("December 2026"), or the four weeks after the fortnight, from its first day to its last. */
    fun calendarWhat(month: String, now: Long, zone: ZoneId): String {
        if (month.isNotEmpty()) return YearMonth.parse(month).format(MONTH)
        val (from, to) = calendarSpan(month, now, zone)
        return "the four weeks after the fortnight, ${Instant.ofEpochMilli(from).atZone(zone).format(DAY)} to ${Instant.ofEpochMilli(to - 1).atZone(zone).format(DAY)}"
    }

    /** From when to when [month] runs ([calendarAsk]): the month in [zone]; or, empty, the four weeks from the fortnight's end. */
    private fun calendarSpan(month: String, now: Long, zone: ZoneId): Pair<Long, Long> {
        if (month.isEmpty()) {
            val from = now + InstructionPrompts.EVENTS_AHEAD_MS
            return from to from + CALENDAR_AHEAD_MS
        }
        val m = YearMonth.parse(month)
        return m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The calendar past the fortnight, asked for with no month (G7): four weeks of it. */
    private const val CALENDAR_AHEAD_MS = 28 * 24 * 3_600_000L

    private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)

    /** [t]'s class as its line says it (G8): its code, and the subject [classMap] gives it where that's another; "-" with none. */
    private fun classOf(t: TaskItem, classMap: Map<String, String>): String {
        val code = t.className?.trim()?.takeIf { it.isNotEmpty() } ?: return "-"
        val subject = classMap.entries.firstOrNull { it.key.trim().equals(code, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() && !it.equals(code, ignoreCase = true) }
        return subject?.let { "$code, $it" } ?: code
    }

    /** A lesson's day and start, as the lessons' line says them. */
    private val LESSON_DAY = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * Each open task's own text as the context gives it, at most (D-93). Its cut to 250 didn't hold in the
     * chat's eval by its rule (a round more on one of its cases), so it stays at 600, beside each line's room.
     */
    const val BRIEF_TEXT = 600

    /** All of their texts in the context, at most. */
    private const val MAX_TEXTS = 12_000

    /** What a task's text holds, on its line: at most this much a line, and all the lines' together. */
    private const val HOLDS_LINE = 160
    private const val MAX_HOLDS = 6_000

    /** The tasks' own notes under them in the context, at most: the rest are on their sheets. */
    private const val MAX_NOTES_TEXT = 6_000

    /** A task's whole text, as a message about it, or his asking to read it, carries it (D-93). */
    const val WHOLE_TEXT = 8_000

    /** The tasks one answer can ask to read in full, and all of their texts together, at most. */
    const val MAX_READ = 3
    private const val READ_TEXT = 16_000

    /** An event's place and its description, as a message about it carries them. */
    private const val EVENT_PLACE = 200
    private const val EVENT_TEXT = 500

    /** How far back, and how many, of the student's own finished tasks the chat's told of. */
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
     * far days; each summary is at most [PLAN_LIST] lines. Today's slots and the next
     * piece of each task with no steps say their keys (G1), so he can keep one done, and the pieces [kept]
     * today (their keys and labels), which the plan no longer shows, so he can take one back; and after
     * the slot under way, [blocking]: whether blocking applies and why, and the free time (G5). A running
     * [timer] (D-112) is said with the slot under way or the next thing where it's on that task,
     * else in a line of its own. Today's breaks outside are among today's slots, and the week's later
     * days' on their lines (D-116), each named by its own time, with who [placed] it where it was moved or added. How he
     * feels about today's work ([feeling], D-119) under today's slots, as his messages are told it.
     */
    fun plan(plan: Plan, zone: ZoneId, kept: List<Pair<String, String>> = emptyList(), blocking: String? = null, timer: PieceTimer? = null, only: Map<LocalDate, String> = emptyMap(), placed: Map<String, By> = emptyMap(), feeling: String? = null): String = buildString {
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
        // A timer running: on the piece said here, with it; on another, a line of its own.
        val running = timer?.takeIf { it.running }
        val on = running?.takeIf { it.taskId == (doing?.chunk ?: plan.next)?.taskId }?.let { ", " + FelixPrompts.timerOn(it, plan.now, zone) }.orEmpty()
        when {
            doing != null -> appendLine("Now: ${clock(doing.start)}–${clock(doing.end)} ${piece(doing.chunk!!)}$on$then")
            else -> plan.next?.let { appendLine("Next to do: ${piece(it)}$on$then") }
        }
        if (running != null && on.isEmpty()) appendLine(FelixPrompts.timerLine(running, plan.now, zone))
        // Whether blocking applies now and why, and their free time (G5): "how much free time have I got?"
        blocking?.let { appendLine(it) }
        // A break outside by its own time, and who moved or added it (D-116): yours or theirs.
        fun breakName(day: LocalDate, s: Slot): String {
            val by = placed["$day/${s.key}"]?.let { if (it == By.Felix) "you" else "them" }
            return "the ${hhmm(s.outsideAt)} break" + if (s.outsideAdded) ", added" + (by?.let { " by $it" } ?: "") else by?.let { ", moved by $it" } ?: ""
        }
        // Today's slots at their times, each gone by, under way or to come (AI-13), so a time today isn't taken for past; its
        // breaks outside among them, under way or to come, each named by its own time (D-116), so a change to one names it.
        val slots = plan.todaySlots.filter { (it.kind == SlotKind.Work && it.chunk != null) || (it.kind == SlotKind.Outside && it.end > plan.now) }
        if (slots.isNotEmpty()) {
            appendLine("Today's slots (the times its pieces and breaks outside are laid at):")
            slots.forEach { s ->
                val state = when {
                    s.end <= plan.now -> "past"
                    s.start <= plan.now -> "now"
                    else -> "ahead"
                }
                val what = s.chunk?.let { "${piece(it)} [$state] key ${it.key}" } ?: "Outside, ${breakName(plan.today, s)} [$state]"
                appendLine("- ${clock(s.start)}–${clock(s.end)} $what")
            }
        }
        feeling?.let { appendLine(it) }
        val last = plan.today.plusDays(PLAN_DAYS - 1L)
        fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        /** At most [PLAN_LIST] of [lines], and how many more. */
        fun list(lines: List<String>) {
            lines.take(PLAN_LIST).forEach { appendLine("- $it") }
            if (lines.size > PLAN_LIST) appendLine("- …and ${lines.size - PLAN_LIST} more")
        }
        // A task with no steps to tick is planned in boxes (G1): its next piece's key, to keep it done.
        val boxes = plan.ordered.filter { it.stepIndex == null }.distinctBy { it.taskId }
        if (boxes.isNotEmpty()) {
            appendLine("The next piece of each task with no steps (task | the piece | its key, for keepPiece):")
            list(boxes.map { "${it.taskId} | ${piece(it)} | ${it.key}" })
        }
        // Kept today (G1): gone from the plan, so their keys are here, for unkeepPiece.
        if (kept.isNotEmpty()) {
            appendLine("Kept today (its key | the piece):")
            list(kept.map { (key, label) -> "$key | ${label.take(MAX_TITLE)}" })
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
            // A day kept for only some tasks (D-115): which, and what else it lets in.
            val kept = only[bucket.date]?.let { "; $it" }.orEmpty()
            // A later day's breaks outside, the week's (D-116): each at its time, named by its own.
            val breaks = bucket.slots.filter { it.kind == SlotKind.Outside }.takeIf { bucket.date > plan.today && bucket.date < plan.today.plusDays(InstructionPrompts.BREAK_DAYS.toLong()) }.orEmpty()
            val outside = if (breaks.isEmpty()) "" else "; breaks outside: " + breaks.joinToString { "${clock(it.start)} (${breakName(bucket.date, it)})" }
            appendLine(if (bucket.chunks.isEmpty()) "- $name: nothing planned ($time)$kept$outside" else "- $name: ${bucket.plannedMin} min planned of $time$over$kept$outside")
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

    /** A minute of the day, as HH:MM. */
    private fun hhmm(minute: Int): String = "%02d:%02d".format(Locale.UK, minute / 60, minute % 60)

    /**
     * A day kept for only some tasks, as the plan's day line says it (D-115): "kept for only “Essay”, “Maths revision” (done);
     * Anki too". Those finished are marked done, so he can offer to free the day.
     */
    fun onlyLine(only: OnlyDay, tasks: Map<String, TaskItem>): String {
        val names = only.taskIds.sorted().joinToString(", ") { id ->
            val task = tasks[id]
            "“" + (task?.title?.take(MAX_TITLE) ?: "a task no longer listed") + "”" + if (task != null && !task.isOpen) " (done)" else ""
        }
        return "kept for only $names" + when {
            only.deadlines -> "; Anki, and work that would otherwise miss its deadline, too"
            only.anki -> "; Anki too"
            else -> ""
        }
    }

    /** The most lines in each of the plan's summaries (what isn't planned, other events, later tasks), so they leave the days room. */
    const val PLAN_LIST = 20
}
