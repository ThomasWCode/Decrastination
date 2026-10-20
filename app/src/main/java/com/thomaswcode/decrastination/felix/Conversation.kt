package com.thomaswcode.decrastination.felix

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.CheckInDraft
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.Found
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.enrich.WebResearcher
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Felix's conversation (docs/plan_v4, Stage 4), moved out of `AppGraph`: who the student is, as every
 * call sends it ([profile]); the chat, your messages and his answers, their cards and acts made at
 * once with their Undo ([ask], [answerChat], [applyProposal], [undoAct]); what he remembers
 * ([remember]); a reply to one of his messages, from the chat, his chips or his notification, routed
 * by what it answers ([ask], [Replies]); the week's
 * check-in as he asks it ([remindCheckIn]); and what the app says in his name, each through his
 * [outbox] (docs/plan_v4/05-the-chat.md): the morning's list, a calendar event's question, how work
 * went, the week's review and summary. Built like [com.thomaswcode.decrastination.block.Focus]: its
 * stores, the clock and small interfaces in its constructor, for what others own ([Owners]: your own
 * tasks, your instructions, the settings, the calendar's answers) and what Android does
 * ([FelixPorts]), so it's tested on temporary stores.
 */
class Conversation(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val learning: JsonStore<LearningState>,
    private val log: JsonStore<ActivityLog>,
    private val instructions: JsonStore<InstructionState>,
    private val chat: JsonStore<ChatState>,
    private val school: JsonStore<SchoolCalendar>,
    private val memory: JsonStore<MemoryState>,
    /** What he keeps at your asking (Stage 8a): notes on tasks, and reminders. */
    private val felix: JsonStore<FelixStore>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val notices: Notices,
    private val focus: Focus,
    private val planning: Planning,
    private val ports: FelixPorts,
    /** Where every message in his name is written, and his notification made from ([Outbox]). */
    val outbox: Outbox,
    private val owners: Owners,
    /** The calendar's events and busy time, as last read and judged. */
    private val calendarTime: () -> EventJudge.Time,
    /** Your reply to one of his messages that's an answer and not for the chat ([Looks.answered]). */
    private val answered: suspend (ChatMessage, String) -> Boolean,
    /** His words for the reminders due within his window, written now ([Looks.draftReminders]): after one's set, moved or brought back. */
    private val draftReminders: suspend (Long) -> Unit = {},
) {
    /**
     * What the chat changes through those who own it (`AppGraph`'s functions, till Stage 6 makes
     * services of them): your own tasks, your instructions, and the settings.
     */
    interface Owners {
        suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean
        suspend fun finishOwnTask(taskId: String)
        suspend fun reopenOwnTask(taskId: String)
        suspend fun deleteOwnTask(taskId: String)
        suspend fun applyInstruction(id: String)
        suspend fun deleteInstruction(id: String)
        suspend fun layTaskOverrides()
        suspend fun changeSettings(change: (Settings) -> Settings)

        /** [answer], one of a calendar question's answers as it's kept (`CalendarTime.ANSWERS`), for every event named [key] (Q7). */
        suspend fun answerEvent(key: String, answer: String)

        /** The week's [checkIn] on record, once a week ([com.thomaswcode.decrastination.learn.Learning.recordCheckIn]): whether it was kept. */
        suspend fun recordCheckIn(checkIn: CheckIn, now: Long): Boolean
    }

    /**
     * Who the student is, as every model call sends it (AI-05): from the settings, the tasks, the
     * timetable, the school calendar and what's [remembered].
     */
    fun profile(now: Long = clock.now(), remembered: MemoryState = memory.value): Profile.Block {
        val s = settings.value
        val state = tasks.value
        val zone = clock.zone()
        return Profile.Block(
            Profile.yearGroup(now, zone, s.yearGroupStart),
            Profile.block(s, state.tasks, state.timetable, school.value, remembered, now, zone),
        )
    }

    /** Said on a message whose answer you let go (AC-08). */
    private val CANCELLED = "Cancelled"

    /** What a message of yours left out of his answer says (more waited than go in one turn): it waits for Try again, so it's never asked unasked. */
    private val LEFT_OUT = "Left out of his answer, as more waited than go in one: Try again"

    /**
     * The week's check-in begun (LR-02): Felix asks its first question in the chat, the rest as each
     * is answered ([checkInReply]); the form, This week, takes them all at once.
     * The reminder to open the form, where his notifications can't be shown.
     */
    suspend fun remindCheckIn() {
        if (askCheckIn()) return
        ports.remindForm()
    }

    /**
     * The week's check-in asked by Felix (LR-02): its first question, unless the week has its check-in
     * already, or it's quiet hours (false then, and the form's reminder goes instead). With his
     * notifications off it's asked in the chat all the same (D-95), and false, so the form's reminder
     * says so. A week before's questions never finished are settled first: an answer to one isn't this week's.
     */
    suspend fun askCheckIn(): Boolean {
        val now = clock.now()
        val week = Daily.checkInWeek(now, clock.zone(), settings.value.checkInMin)
        if (log.value.checkIns.any { it.weekOf == week }) return true
        // In quiet hours the form's reminder goes instead, held as the routine ones are till the morning.
        if (BlockPolicy.isQuiet(now, clock.zone(), settings.value)) return false
        chat.update { s -> s.copy(messages = s.messages.map { if (it.from == Speaker.Felix && it.kind == MessageKind.CheckIn && it.answeredAt == null) it.copy(answeredAt = now) else it }) }
        askNext(CheckInDraft(week), now)
        return notices.shown(Channels.FELIX)
    }

    /** [draft]'s question as his message, in the app's words, with its replies, the draft kept with it: so its reply comes back here. */
    private suspend fun askNext(draft: CheckInDraft, now: Long, again: Boolean = false) {
        val text = (if (again) FelixWords.CHECK_IN_AGAIN else "") + CheckInFlow.question(draft)
        val question = ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, text, now, kind = MessageKind.CheckIn, byApp = true, suggestedReplies = CheckInFlow.replies(draft, log.value, clock.zone()))
        outbox.send(question) { it.copy(checkInDraft = draft) }
    }

    /**
     * [words], the answer to the check-in [question] he asked (LR-02): kept under it, and the next asked;
     * after the last, the week's check-in kept and its review begun. The form filled meanwhile, or the
     * week moved on, ends it.
     */
    private suspend fun checkInReply(question: ChatMessage, words: String): Boolean {
        val now = clock.now()
        val flow = CheckInFlow
        keepReply(question, words, now)
        val draft = chat.value.felix.checkInDraft
        val week = Daily.checkInWeek(now, clock.zone(), settings.value.checkInMin)
        if (draft == null || draft.week != week || log.value.checkIns.any { it.weekOf == draft.week }) {
            chat.update { it.copy(felix = it.felix.copy(checkInDraft = null)) }
            outbox.delivered()
            return true
        }
        val next = flow.answered(draft, words) ?: run {
            askNext(draft, now, again = true)
            return true
        }
        if (!flow.done(next)) {
            askNext(next, now)
            return true
        }
        val added = owners.recordCheckIn(flow.checkIn(next, now), now)
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, FelixWords.CHECK_IN_THANKS, now, kind = MessageKind.CheckIn, byApp = true)) { it.copy(checkInDraft = null) }
        // Its review, unless the week has had it (the evening's ran first).
        if (added) ports.review()
        return true
    }

    /**
     * Felix's check-in let go (LR-02), the form having taken the week's answers: its answers so far,
     * and his questions, answered there, out of his notification.
     */
    suspend fun endCheckInFlow() {
        if (chat.value.felix.checkInDraft == null) return
        val now = clock.now()
        chat.update { s ->
            s.copy(
                felix = s.felix.copy(checkInDraft = null),
                messages = s.messages.map { if (it.from == Speaker.Felix && it.kind == MessageKind.CheckIn && it.answeredAt == null) it.copy(answeredAt = now) else it },
            )
        }
        outbox.notify(now)
    }

    /**
     * [words], your reply to his [message] that's an answer with no call (a check-in's, a reading's
     * question's, how work went, a calendar event's), kept under it, answered as it came; and his
     * question, answered, out of his notification. Shown there by the outbox after.
     */
    private suspend fun keepReply(message: ChatMessage, words: String, now: Long) {
        val reply = ChatMessage(UUID.randomUUID().toString(), Speaker.You, words, now, message.about, kind = message.kind, replyTo = message.id, answeredAt = now)
        chat.update { s -> s.copy(messages = s.messages.map { if (it.id == message.id && it.answeredAt == null) it.copy(answeredAt = now) else it } + reply) }
        // One scrolled back to in the archive is answered there, so it takes no second answer.
        outbox.answeredInArchive(message.id, now)
    }

    /** Messages of his marked answered at [at] ([markAnswered]): what its Undo puts back. */
    data class Marked(val ids: Set<String>, val at: Long)

    /**
     * [ids], messages of his, marked answered by you (D-99): no longer waiting for your answer, nor on his
     * notification. A check-in's question while the check-in is under way isn't: the check-in waits on it.
     */
    suspend fun markAnswered(ids: Collection<String>): Marked = withContext(NonCancellable) {
        val now = clock.now()
        val wanted = ids.toSet()
        val marked = HashSet<String>()
        chat.update { s ->
            val underWay = s.felix.checkInDraft != null
            s.copy(
                messages = s.messages.map {
                    if (it.id in wanted && it.from == Speaker.Felix && it.answeredAt == null && !(underWay && it.kind == MessageKind.CheckIn)) it.copy(answeredAt = now).also { m -> marked += m.id } else it
                },
            )
        }
        outbox.delivered()
        Marked(marked, now)
    }

    /** [marked]'s Undo: those still answered as they were marked wait for your answer again. */
    suspend fun unmarkAnswered(marked: Marked): Unit = withContext(NonCancellable) {
        chat.update { s -> s.copy(messages = s.messages.map { if (it.id in marked.ids && it.answeredAt == marked.at) it.copy(answeredAt = null) else it }) }
        outbox.delivered()
    }

    /** The morning's list (LR-04) as his message, in the app's words, where he has none of his own ([Looks.morning]): the day's [title], then its [lines]. */
    suspend fun morningList(title: String, lines: List<String>) =
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, FelixWords.morningList(title, lines), clock.now(), kind = MessageKind.Morning, byApp = true))

    /**
     * An event the plan can't judge (Q7), [title] on [day], asked about as his message in the app's
     * words, its [answers] his chips: one of them, from here or his notification, is kept for every
     * event named [key] with no call ([ask]).
     */
    suspend fun askAboutEvent(key: String, title: String, day: String, start: Long, answers: List<String>) = outbox.send(
        ChatMessage(
            UUID.randomUUID().toString(), Speaker.Felix, FelixWords.eventQuestion(title, day), clock.now(),
            About(eventKey = key, eventTitle = title, eventStart = start), kind = MessageKind.EventQuestion, byApp = true, suggestedReplies = answers,
        ),
    )

    /** His questions about the events named [key] answered (here, in Settings, or by an instruction): out of his notification. */
    suspend fun settleEvent(key: String) {
        val now = clock.now()
        if (chat.value.messages.none { it.kind == MessageKind.EventQuestion && it.about.eventKey == key && it.answeredAt == null }) return
        chat.update { s -> s.copy(messages = s.messages.map { if (it.kind == MessageKind.EventQuestion && it.about.eventKey == key && it.answeredAt == null) it.copy(answeredAt = now) else it }) }
        outbox.notify(now)
    }

    /**
     * How the work of [asks] went (LR-05): kept for his evening question while Claude is on; with it
     * off, asked now, a message each in the app's words, the three answers its replies.
     */
    suspend fun askHowItWent(asks: List<AssessLater>) {
        if (asks.isEmpty()) return
        if (gateway.claudeKey() != null) {
            chat.update { s -> s.copy(felix = s.felix.copy(assessLater = (s.felix.assessLater + asks).distinctBy { a -> a.taskId to a.doneAt }.takeLast(MAX_ASKS))) }
            return
        }
        // Handed over again after a stop (`Focus.rewardCompletions`): one asked already isn't asked twice,
        // and one kept while Claude was on is let go of as it's asked.
        asks.filterNot(::asked).forEach { ask -> outbox.send(howItWent(ask)) { it.copy(assessLater = it.assessLater - ask) } }
    }

    /** Those kept for his evening question asked now, a message each, as Claude is off: kept while it was on. */
    suspend fun askKept() {
        if (gateway.claudeKey() != null) return
        val kept = chat.value.felix.assessLater
        if (kept.isEmpty()) return
        // Each let go of in the same write that asks it: a stop between leaves the rest kept, to be asked.
        // One asked already is only let go of.
        kept.forEach { ask ->
            if (asked(ask)) {
                chat.update { s -> s.copy(felix = s.felix.copy(assessLater = s.felix.assessLater - ask)) }
            } else {
                outbox.send(howItWent(ask)) { it.copy(assessLater = it.assessLater - ask) }
            }
        }
    }

    /** Whether how [ask]'s work went has been asked already, in a message of his in the chat. */
    private fun asked(ask: AssessLater): Boolean =
        chat.value.messages.any { m -> m.kind == MessageKind.Completions && m.asks.any { it.taskId == ask.taskId && it.doneAt == ask.doneAt } }

    /** How [ask]'s work went, as his message in the app's words: the three answers its replies, each kept on it ([Looks.answered]). */
    private fun howItWent(ask: AssessLater) = ChatMessage(
        UUID.randomUUID().toString(), Speaker.Felix, FelixWords.howItWent(ask.title), clock.now(), About(taskId = ask.taskId, taskTitle = ask.title),
        kind = MessageKind.Completions, byApp = true, asks = listOf(ask), suggestedReplies = Assessment.ANSWERS.map { it.first },
    )

    /** Every question about how [taskId]'s work went let go (a test's task forgotten, the debug `forget-task`): not kept, nor in his notification. */
    suspend fun forgetAsks(taskId: String) {
        val now = clock.now()
        chat.update { s ->
            s.copy(
                felix = s.felix.copy(assessLater = s.felix.assessLater.filterNot { it.taskId == taskId }),
                messages = s.messages.map { if (it.kind == MessageKind.Completions && it.answeredAt == null && it.asks.any { a -> a.taskId == taskId }) it.copy(answeredAt = now) else it },
            )
        }
        outbox.notify(now)
    }

    /** The week's review written (LR-01): its note's [firstLine] as his message in the app's words, with the button that opens the review. */
    suspend fun reviewed(firstLine: String) =
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, firstLine, clock.now(), kind = MessageKind.Review, byApp = true, opens = OPENS_REVIEW))

    /** The [week]'s summary written (NF-02): his offer to send it, with the button that opens it in an email. */
    suspend fun summaryReady(week: String) =
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, FelixWords.SUMMARY_READY, clock.now(), kind = MessageKind.Summary, byApp = true, opens = OPENS_SUMMARY + week))

    /** Held while the chat is answered, so each message is answered once. */
    private val answering = Mutex()

    private val sentAt = MutableStateFlow<Long?>(null)

    /** When the chat's call in flight was sent (AC-08), for its "thinking… 12 s"; null with none. */
    val answeringSince: StateFlow<Long?> get() = sentAt

    /** The message whose answer you let go while its call ran (AC-08): that answer, when it comes, goes unused. */
    @Volatile
    private var letGo: String? = null

    /**
     * What a message's answer has been given beyond its context, by the message's [id], kept till
     * he's answered with it: what a web search [found] (16 Oct), or why one made found nothing
     * ([notLooked]), and the tasks [read] in full (D-93). An answer with them that failed is asked
     * again with them, never searching or asking for them again, as each is paid for; a search held
     * back (Claude off or resting, the cap) isn't kept, so it can be made later.
     */
    private data class Gathered(val id: String, val found: Found?, val notLooked: String?, val read: List<String>)

    @Volatile
    private var gathered: Gathered? = null

    /**
     * The answer being waited for let go (AC-08): your message says so, with its Try again. A call once
     * sent is paid for and counted (AC-04), so it isn't closed: it comes back, its answer unused.
     */
    fun cancelAnswer() {
        val waiting = chat.value.unanswered ?: return
        if (sentAt.value == null) return
        letGo = waiting.id
        sentAt.value = null
        scope.launch { chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = CANCELLED, stuck = true) else it }) } }
    }

    /**
     * Your [text] to Felix about [about] (2.0.0, Q29), answering the message [replyTo] if it does: one
     * entrance for replies (D-98), from the chat, his chips or his notification. A reply to a question of
     * his that's an answer is kept under it with no call ([routed]); anything else is a turn of the
     * chat, kept as message [id] (about what it replies to, where it's about nothing else, unless not
     * [takeItsSubject]: the box's words to his nudge just above it, told him as your words alone, D-99)
     * and answered ([answerChat]). One sent while he answers another of yours lets that answer go, paid
     * for and unused, and he answers both together (D-98). False only with no words. Kept, it's shown in
     * his notification under what it answers.
     */
    suspend fun ask(text: String, about: About = About(), id: String = UUID.randomUUID().toString(), replyTo: String? = null, takeItsSubject: Boolean = true): Boolean =
        send(text, about, id, replyTo, takeItsSubject) != Asked.Nothing

    /** What became of words sent ([send]): nothing (there were none), an answer taken with no call, or a turn of the chat waiting for his answer. */
    enum class Asked { Nothing, Answer, Turn }

    /**
     * [ask], saying which way it went: a reply from his notification is answered as work of its own only
     * where it's a turn of the chat ([replyFromNotification]), and the `chat-ask` command waits for an
     * answer only then. The message replied to may be in the archive, scrolled back to.
     */
    suspend fun send(text: String, about: About = About(), id: String = UUID.randomUUID().toString(), replyTo: String? = null, takeItsSubject: Boolean = true): Asked {
        val words = text.trim().take(ChatPrompts.MAX_TEXT)
        if (words.isEmpty()) return Asked.Nothing
        val repliedTo = replyTo?.let { outbox.find(it) }
        if (repliedTo != null && routed(repliedTo, words)) return Asked.Answer
        // Yours still waiting go with it, answered together: only the newest says why it waits. Each keeps
        // whether it waits for Try again, for if the newest is taken back ([withdrawQuestion]).
        chat.update { state ->
            state.copy(
                messages = state.messages.map { if (it.from == Speaker.You && it.answeredAt == null) it.copy(note = null) else it } +
                    ChatMessage(id, Speaker.You, words, clock.now(), about.takeIf { it != About() } ?: repliedTo?.about?.takeIf { takeItsSubject } ?: About(), replyTo = replyTo),
            )
        }
        outbox.delivered()
        scope.launch { answerChat() }
        return Asked.Turn
    }

    /**
     * [words], your reply to [message], answered with no call where it's the answer to a question of his
     * ([Replies.route]): a check-in's question, its next asked ([checkInReply]); a reading's question, or
     * how work went with one of its three ([answered]); a calendar event's question, one of its three
     * kept ([Owners.answerEvent]); a reminder's Done or snooze ([replyToReminder]); each kept under it.
     * False for a turn of the chat.
     */
    private suspend fun routed(message: ChatMessage, words: String): Boolean {
        val now = clock.now()
        // A check-in's question takes its answer only while it's the one the check-in under way asked last:
        // one from a week long gone, scrolled back to in the archive, is a turn of the chat.
        val checkIn = chat.value.felix.checkInDraft != null && message.id == chat.value.messages.lastOrNull { it.from == Speaker.Felix && it.kind == MessageKind.CheckIn }?.id
        when (val route = Replies.route(message, words, checkInUnderWay = checkIn)) {
            // An answer to a check-in question (LR-02): the next asked, or the check-in kept.
            Replies.Route.CheckIn -> return checkInReply(message, words)
            // A reading's question's answer (AI-10), or one of the three to how work went (LR-05): on them, and that's all.
            Replies.Route.Question, is Replies.Route.Completions -> if (!answered(message, words)) return false
            // One of a calendar question's three answers (Q7), for every event of its name.
            is Replies.Route.Event -> owners.answerEvent(route.key, route.answer)
            // A reminder's Done or snooze (8a): on it; gone (pruned), a turn of the chat.
            is Replies.Route.Reminder -> if (!replyToReminder(route, now)) return false
            Replies.Route.Chat -> return false
        }
        keepReply(message, words, now)
        outbox.delivered()
        return true
    }

    /**
     * Your reply to one of your reminders (8a): *Done* marks it done; a snooze has it come again then,
     * its words for before let go, written again near the time (its alarm set again by the store's
     * change). One done or cancelled since stays so, as does one moved while its message went, which
     * never came. False where it's gone.
     */
    private suspend fun replyToReminder(route: Replies.Route.Reminder, now: Long): Boolean {
        var found = false
        felix.update { s ->
            s.copy(
                reminders = s.reminders.map { r ->
                    if (r.id != route.id) return@map r
                    found = true
                    when {
                        r.doneAt != null || r.cancelledAt != null -> r
                        // Moved while its message went, it never came: the message's chips aren't its.
                        r.sentAt == null -> r
                        route.snoozeMin == null -> r.copy(doneAt = now).undrafted()
                        else -> Reminders.snoozed(r, now, route.snoozeMin)
                    }
                },
            )
        }
        return found
    }

    /**
     * What the chat tells Claude the app knows at [now] ([ChatPrompts.context]): the open tasks, the
     * calendar and the changes in use; and beside them the plan as the Day and Work tabs show it then, the
     * slot under way included ([ChatPrompts.plan]). All of one [state], calendar [time],
     * [applied] instructions and [kept] notes and reminders, so the list and the plan agree, and with
     * the answer's checks where the caller passes it the same ones.
     */
    fun chatContext(
        now: Long = clock.now(),
        state: TaskState = tasks.value,
        time: EventJudge.Time = calendarTime(),
        applied: List<Instruction> = instructions.value.applied,
        kept: FelixStore = felix.value,
    ): ChatPrompts.Context {
        val plan = planning.plan(state, now = now, applied = applied, time = time)
        // His notes and reminders (8a), each reminder with when it comes by this plan.
        val byId = state.tasks.associateBy { it.id }
        val moments = kept.reminders.mapNotNull { r -> Reminders.moment(r, plan, byId, now)?.let { r.id to it } }.toMap()
        return ChatPrompts.Context(
            known = ChatPrompts.context(state.tasks, time.events, applied, now, clock.zone(), settings.value.classMap, runtime.value.notSame, settings.value, learning.value.calibration, kept.notes, kept.reminders, moments),
            plan = ChatPrompts.plan(plan, clock.zone()),
        )
    }

    /**
     * Claude's answer to your message waiting for one: a call made as the enrichment's are, under the
     * cap and resting after a failure. While Claude can't be asked, your message says why, and it's
     * answered once it can be (the chat opened again, *Try again*, or Claude back from its rest).
     */
    suspend fun answerChat(retry: Boolean = false): Unit = answering.withLock {
        val waiting = chat.value.unanswered ?: return@withLock
        // Answered with nothing usable, it waits for your Try again ([retry]): never asked by itself.
        if (waiting.stuck && !retry) return@withLock
        var stuck = false
        val note = run call@{
            // Taken back (or the chat cleared) while this waited its turn: nothing to ask.
            if (chat.value.unanswered?.id != waiting.id) return@withLock
            val chatClient = gateway.client(ModelEffort.Job.Chat) ?: return@call gateway.because(ModelHold.Off)
            val researchClient = gateway.client(ModelEffort.Job.Research) ?: return@call gateway.because(ModelHold.Off)
            // One snapshot of the tasks, the calendar and your changes, and his notes and reminders: what Claude's shown is what its answer's checked against.
            val state = tasks.value
            val time = calendarTime()
            val noted = felix.value
            // The conversation up to your message: what he wrote unasked after it isn't what it answers.
            fun upTo(messages: List<ChatMessage>) = messages.take(messages.indexOfFirst { it.id == waiting.id } + 1)
            // His answer, with what a web search he asked for found ([found], 16 Oct), and the tasks whose
            // whole text he asked to [read] (D-93).
            suspend fun ask(found: Found?, read: List<TaskItem> = emptyList()) = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.Chat, waiting.text) { r: ChatReader.Result -> r.usage to ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: ChatReader.Result -> r.costUsd to r.refused }) { at ->
                // Being asked now: whatever it said before (a failed call) is gone.
                chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(note = null, stuck = false) else it }) }
                sentAt.value = at
                try {
                    val sent = upTo(chat.value.messages)
                    // What a message of yours quotes from the archive, past the window: told to him all the same.
                    val quoted = sent.mapNotNull { m -> m.replyTo?.takeIf { r -> sent.none { it.id == r } }?.let { outbox.find(it) } }
                    ChatReader(chatClient, clock.zone(), profile = profile(at), settings = settings.value).answer(sent, state.tasks, time.events, chatContext(at, state, time, instructions.value.applied, noted), at, found, read, quoted, noted)
                } finally {
                    sentAt.value = null
                }
            }
            // The tasks of [ids], as they are in the snapshot his answers are checked against.
            fun tasksOf(ids: List<String>): List<TaskItem> = ids.mapNotNull { id -> state.tasks.firstOrNull { it.id == id } }
            // What it was given before, for an answer with it that failed ([Gathered]): asked again
            // with it, not looked up or asked for again.
            val before = gathered?.takeIf { it.id == waiting.id }
            var read = tasksOf(before?.read.orEmpty())
            // Let go while it ran (AC-08: the message says so already), or another of yours sent meanwhile, to be
            // answered with it (D-98): the answer goes unused, and nothing more is looked up or read for it.
            fun unwanted(): Boolean {
                val gone = letGo == waiting.id || chat.value.unanswered?.id != waiting.id
                if (letGo == waiting.id) letGo = null
                return gone
            }
            val called = ask(before?.found, read)
            if (unwanted()) return@withLock
            val result = when (called) {
                is Called.Held -> return@call gateway.because(called.hold)
                is Called.Failed -> {
                    Log.w(AppGraph.TAG, "Claude's answer in the chat failed", called.error)
                    return@call "Claude's call failed: ${gateway.retryWords()}, or now with Try again"
                }
                is Called.Answered -> called.value
            }
            var reading = result.reading ?: run {
                stuck = true
                return@call if (result.refused) "Claude declined to answer that: Try again, or put it another way" else "Claude's answer couldn't be read: Try again"
            }
            // What he asked to look up on the web (16 Oct), in a call of its own, and the tasks whose
            // whole text he asked to read (D-93): he's asked again with them, and that's his answer.
            // A search the texts showed a need for, or texts the search did, take one more round
            // ([ChatRounds]); never more, as each is paid for. Not found, his answer stands, saying
            // why; his answer with them failed, the message waits for its Try again with them, as a
            // failed call leaves it.
            var found = before?.found
            var notLooked = before?.notLooked
            // Why a search made found nothing, kept with the rest; one held back isn't, so it can be made later.
            var tried = before?.notLooked
            for (round in 1..ChatRounds.MAX_ROUNDS) {
                val next = ChatRounds.next(reading, searched = found != null || notLooked != null, searches = settings.value.felixSearches, read = read.map { it.id }) ?: break
                var looks = false
                next.search?.let { question ->
                    val search = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.Research, question) { r: WebResearcher.Result -> r.usage to ModelCalls.outcome(r.refused, r.found == null) }, cost = { r: WebResearcher.Result -> r.costUsd to r.refused }) { at ->
                        sentAt.value = at
                        try {
                            WebResearcher(researchClient, year = profile(at).year).look(question, waiting.text, at, clock.zone())
                        } finally {
                            sentAt.value = null
                        }
                    }
                    if (unwanted()) return@withLock
                    val what = (search as? Called.Answered)?.value?.found
                    if (what == null) {
                        if (search is Called.Failed) Log.w(AppGraph.TAG, "Felix's web search failed", search.error)
                        notLooked = when (search) {
                            is Called.Held -> gateway.because(search.hold)
                            is Called.Failed -> "the search failed"
                            is Called.Answered -> when {
                                search.value.refused -> "Claude declined to search for it"
                                search.value.paused -> "the search ran too long"
                                else -> "the search found nothing"
                            }
                        }
                        if (search !is Called.Held) tried = notLooked
                    } else {
                        found = what
                        looks = true
                    }
                }
                val more = tasksOf(next.read)
                val reads = more.size > read.size
                if (reads) read = more
                // Nothing new to answer with (a search that found nothing, no texts asked for): his answer stands.
                if (!looks && !reads) break
                gathered = Gathered(waiting.id, found, tried, read.map { it.id })
                val again = ask(found, read)
                if (unwanted()) return@withLock
                val second = when (again) {
                    is Called.Held -> return@call gateway.because(again.hold)
                    is Called.Failed -> {
                        Log.w(AppGraph.TAG, "Claude's answer with what it looked up or read failed", again.error)
                        return@call "Claude's call failed: ${gateway.retryWords()}, or now with Try again"
                    }
                    is Called.Answered -> again.value
                }
                reading = second.reading ?: run {
                    stuck = true
                    return@call if (second.refused) "Claude declined to answer that: Try again, or put it another way" else "Claude's answer couldn't be read: Try again"
                }
            }
            reading = reading.copy(research = null, details = emptyList())
            gathered = null
            val reply = ChatMessage(
                id = UUID.randomUUID().toString(),
                from = Speaker.Felix,
                text = reading.reply,
                at = clock.now(),
                // Everything on a card made as it comes (your call, 16 Oct), each with its Undo.
                proposals = ChatAnswers.cards(reading, tasks.value.tasks.associateBy { it.id }, clock.zone()),
                suggestedReplies = reading.suggestedReplies,
                searched = found?.let { it.searched.ifEmpty { listOf(it.question) } }.orEmpty(),
                sources = found?.sources.orEmpty(),
                note = reading.leftOut.takeIf { it.isNotEmpty() }?.let { "Left out, as the app can't use it: ${it.joinToString("; ")}" },
                // Asked to look something up, and it couldn't be: his first answer stands, saying why.
                // What he read in full to answer (D-93), said under it.
                acts = listOfNotNull(
                    notLooked?.let { Act(Act.NOT_LOOKED_UP + "$it.", problem = true) },
                    read.takeIf { it.isNotEmpty() }?.let { r -> Act("Read in full: " + r.joinToString("; ") { "“${it.title}”" } + ".") },
                ),
            )
            var kept = false
            // What he was sent of the chat ([ChatPrompts.history]): yours before it that waited with it and went
            // with it (one sent while he answered: D-98) are answered with it; one left out (more waited than go
            // in one) says so, and waits for its Try again.
            val sent = ChatPrompts.history(upTo(chat.value.messages)).mapTo(HashSet()) { it.id }
            chat.update { s ->
                // Only while it's still the one waiting: taken back meanwhile, the answer goes with it.
                if (s.unanswered?.id != waiting.id) {
                    s
                } else {
                    kept = true
                    s.copy(
                        messages = s.messages.map {
                            when {
                                it.id == waiting.id || (it.from == Speaker.You && it.answeredAt == null && it.at <= waiting.at && it.id in sent) -> it.copy(answeredAt = reply.at, note = null, stuck = false)
                                it.from == Speaker.You && it.answeredAt == null && it.at <= waiting.at -> it.copy(note = LEFT_OUT, stuck = true)
                                else -> it
                            }
                        } + reply,
                    )
                }
            }
            // What it remembered (AI-21), with an answer kept only, and said under it with its Undo.
            val edits = if (kept) remember(reading.memories) else emptyList()
            if (edits.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(memoryEdits = edits) else it }) }
            // Its actions (AI-14; 16 Oct, everything he does), said under it with their Undo: before its
            // changes, as its steps are the ones it was shown.
            val acts = if (kept) act(reading.actions) else emptyList()
            if (acts.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = acts + it.acts) else it }) }
            // Its cards, made now, as if Apply were tapped: Undo takes them back.
            if (kept) {
                val instant = reply.proposals.filter { it.instant }
                instant.forEach { applyProposal(reply.id, it.id) }
                // Its reminders and notes (8a), after its changes, so one before a deadline it sets has it.
                // By the deadlines of its cards that were made: one refused (a due date before a start of yours) sets none.
                val made = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty().filter { it.instant && it.state == ProposalState.Applied }
                val noted = keep(reading.reminders, reading.notes, ChatAnswers.deadlines(made.flatMap { it.changes }))
                if (noted.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + noted) else it }) }
                // A reminder set or moved into his window is written for at once, not at the next plan or look.
                if (noted.any { it.action?.type == ChatAction.Type.SetReminder || it.action?.type == ChatAction.Type.MoveReminder }) scope.launch { draftReminders(clock.now()) }
                // A time the plan couldn't keep (PL-04): where the piece went, said under the answer,
                // which names the time asked for.
                val moved = ChatAnswers.movedPins(instant.flatMap { it.changes }, planning.plan().buckets, clock.zone())
                // One that couldn't be made after all, said under the answer that says it was (16 Oct).
                val refused = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty()
                    .filter { p -> p.instant && p.state != ProposalState.Applied && p.note != null }.map { Act(Act.NOT_MADE + "${it.note!!.replaceFirstChar(Char::lowercase)}.", problem = true) }
                val lines = refused + moved.map { Act(it) }
                if (lines.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + lines) else it }) }
                // His answer, in the chat whole: the window moved, and it's his notification while the chat isn't on screen.
                outbox.delivered(alertFor = reply.id)
            }
            null
        }
        // Why it waits, said once: written only when it changes, as this runs after every sync; and under
        // your words in his notification, where they're shown.
        if (note != null && chat.value.unanswered?.let { it.id == waiting.id && (it.note != note || it.stuck != stuck) } == true) {
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = note, stuck = stuck) else it }) }
            outbox.notify(clock.now())
        }
    }

    /**
     * The chat's actions (AI-14; your call, 16 Oct: everything he does), made at once, as it alters
     * what exists (AI-24), each said under the answer with what its Undo needs ([undoAct]): a step
     * ticked (its piece kept where the plan has one, today or another day, earning as a tick does,
     * else ticked as on its sheet) or to do again; a task of yours done, open again or deleted; a
     * setting set. None for one that couldn't be.
     */
    private suspend fun act(actions: List<ChatAction>): List<Act> {
        fun said(line: String, a: ChatAction) = Act(line, a)
        return actions.mapNotNull { a ->
            val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
            when (a.type) {
                ChatAction.Type.Open -> null
                ChatAction.Type.TickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { !it.done } ?: return@mapNotNull null
                    said("Ticked: ${step.title}, of ${task.title}", a).takeIf { tick(task, index, step.title) }
                }
                ChatAction.Type.UntickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { it.done } ?: return@mapNotNull null
                    said("To do again: ${step.title}, of ${task.title}", a).takeIf { focus.untickBlock(task.id, index, step.title) }
                }
                ChatAction.Type.Done -> {
                    if (task?.isOpen != true) return@mapNotNull null
                    owners.finishOwnTask(task.id)
                    said("Done: ${task.title}", a).takeIf { tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == false }
                }
                ChatAction.Type.Reopen -> {
                    if (task == null || task.isOpen) return@mapNotNull null
                    owners.reopenOwnTask(task.id)
                    // As it was, finished, for its Undo: not finished anew, a second time on record.
                    Act("Open again: ${task.title}", a, was = task).takeIf { tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == true }
                }
                ChatAction.Type.Delete -> {
                    if (task?.source != Source.Own) return@mapNotNull null
                    // The cards that added it, which its going sets back: linked again by its Undo.
                    val cards = chat.value.messages.flatMap { m -> m.proposals.filter { it.made == task.id }.map { "${m.id}|${it.id}" } }
                    owners.deleteOwnTask(task.id)
                    Act("Deleted: ${task.title}", a, was = task, cards = cards).takeIf { tasks.value.tasks.none { it.id == task.id } }
                }
                ChatAction.Type.Setting -> {
                    val field = FelixSettings.field(a.setting.orEmpty()) ?: return@mapNotNull null
                    var made: Act? = null
                    owners.changeSettings { s ->
                        val (next, line) = FelixSettings.set(s, field.name, a.value.orEmpty()) ?: return@changeSettings s
                        made = Act(line, a, before = field.get(s), after = field.get(next))
                        next
                    }
                    // The reminders' alarms at their new times, as the Settings screen sets them.
                    if (made != null) ports.scheduleReminders()
                    made
                }
                // A reminder cancelled, a note deleted (8a): marked, kept whole for the Undo, never removed here.
                // One come, or gone, while he answered: not made, and said so under his reply.
                ChatAction.Type.CancelReminder -> {
                    var was: Reminder? = null
                    felix.update { st -> st.copy(reminders = st.reminders.map { r -> if (r.id == a.value && Reminders.pending(r)) r.copy(cancelledAt = clock.now()).undrafted().also { was = r } else r }) }
                    was?.let { Act("Cancelled: the reminder “${it.text}”", a) }
                        ?: Act(Act.NOT_MADE + "the reminder “${felix.value.reminders.firstOrNull { it.id == a.value }?.text ?: "it"}” isn't cancelled: it came, or went, while he answered.", problem = true)
                }
                ChatAction.Type.DeleteNote -> {
                    var was: Note? = null
                    felix.update { st -> st.copy(notes = st.notes.map { n -> if (n.id == a.value && n.deletedAt == null) n.copy(deletedAt = clock.now()).also { was = n } else n }) }
                    was?.let { Act("Note deleted: “${it.text}”", a) } ?: Act(Act.NOT_MADE + "the note wasn't deleted: it went while he answered.", problem = true)
                }
                // Made from the answer's reminders and notes ([keep]), not its actions.
                ChatAction.Type.SetReminder, ChatAction.Type.MoveReminder, ChatAction.Type.AddNote -> null
            }
        }
    }

    /**
     * [reminders] set, or moved and reworded in place where one has a listed reminder's id, and [notes] kept
     * (8a): made at once, each said under his answer with its Undo ([undoAct]), when it'll come said as the
     * plan has it now. A move of one sent since he was asked isn't made. One before a deadline the answer
     * sets goes by that one ([deadlines], by task id), its lay onto the task coming after.
     */
    private suspend fun keep(reminders: List<Reminder>, notes: List<Note>, deadlines: Map<String, Long> = emptyMap()): List<Act> {
        if (reminders.isEmpty() && notes.isEmpty()) return emptyList()
        val now = clock.now()
        val zone = clock.zone()
        val byId = tasks.value.tasks.associateBy { it.id }.mapValues { (id, t) -> deadlines[id]?.let { t.copy(dueAt = it) } ?: t }
        val plan = planning.plan(now = now)
        fun title(id: String?) = id?.let(byId::get)?.title
        val acts = mutableListOf<Act>()
        felix.update { st ->
            acts.clear()
            // Counted again as they're made: a note added on its sheet, or a reminder set, while he answered counts too.
            var kept = st.reminders
            var room = Reminders.MAX_PENDING - kept.count(Reminders::pending)
            for (r in reminders) {
                val was = kept.firstOrNull { it.id == r.id }
                val at = Reminders.whenLine(r, Reminders.moment(r, plan, byId, now), title(r.taskId), zone)
                when {
                    // Its slot's or its deadline's task finished, gone or dateless while he answered: it would never come.
                    r.due.kind != DueKind.At && Reminders.letGo(listOf(r), byId, now) { s -> tasks.value.status(s).lastSuccessAt }.isNotEmpty() ->
                        acts += Act(Act.NOT_MADE + "the reminder “${r.text}”: “${title(r.taskId) ?: "its task"}” is finished, gone or without a due date now.", problem = true)
                    was == null && room <= 0 -> acts += Act(Act.NOT_MADE + "the reminder “${r.text}”: ${Reminders.MAX_PENDING} are still to come.", problem = true)
                    was == null -> {
                        kept = kept + r
                        room--
                        acts += Act("Reminder set: $at, “${r.text}”", ChatAction(ChatAction.Type.SetReminder, r.taskId.orEmpty(), value = r.id))
                    }
                    Reminders.unsent(was) -> {
                        kept = kept.map { if (it.id == r.id) r else it }
                        acts += Act("Moved: “${r.text}” to $at", ChatAction(ChatAction.Type.MoveReminder, r.taskId.orEmpty(), value = r.id), reminder = was)
                    }
                    else -> acts += Act(Act.NOT_MADE + "the reminder “${was.text}” isn't moved: it came while he answered.", problem = true)
                }
            }
            val live = Notes.live(st.notes)
            val onTask = live.filter { it.taskId != null }.groupingBy { it.taskId!! }.eachCount().toMutableMap()
            var standing = live.count { it.scope != null }
            val added = notes.filter { n ->
                val on = n.scope?.let { "for ${Notes.scopeLine(it)}" } ?: "on “${title(n.taskId) ?: "it"}”"
                val task = n.taskId
                // Its task gone while he answered: not made, as it'd be kept on nothing.
                if (task != null && task !in byId) {
                    acts += Act(Act.NOT_MADE + "the note “${n.text.take(60)}”: its task went while he answered.", problem = true)
                    return@filter false
                }
                val fits = if (task != null) (onTask[task] ?: 0) < Notes.PER_TASK else standing < Notes.MAX_STANDING
                if (fits) {
                    if (task != null) onTask[task] = (onTask[task] ?: 0) + 1 else standing++
                    acts += Act("Noted $on: ${n.text}", ChatAction(ChatAction.Type.AddNote, n.taskId.orEmpty(), value = n.id))
                } else {
                    acts += Act(Act.NOT_MADE + "the note $on: it has as many as are kept already.", problem = true)
                }
                fits
            }
            st.copy(reminders = kept, notes = st.notes + added)
        }
        return acts
    }

    /**
     * The chat's tick of [task]'s step [index], still [title]: its piece kept where the plan has one,
     * today or another day (earning as a tick does; in sittings, its last, which ticks it), else
     * ticked as on its sheet, where that can be.
     */
    private suspend fun tick(task: TaskItem, index: Int, title: String): Boolean {
        val plan = planning.plan()
        val piece = (plan.todaySlots.mapNotNull { it.chunk } + plan.ordered).firstOrNull { it.taskId == task.id && it.ticks == index }
        return if (piece != null) focus.keepSlot(piece.key) else runCatching { focus.tickBlock(task.id, index, title) }.getOrDefault(false)
    }

    /**
     * The [index]th of message [messageId]'s actions taken back ([act], its Undo): a step ticked to
     * do again (one of yours it finished open again with it), or one unticked ticked again as the
     * chat ticks one; a task done open again, or one opened back as it was, finished; one deleted
     * back as it was, with the cards that added it; a setting back to its value before; a reminder or a
     * note set, moved, cancelled or deleted taken back (8a). Not once it's changed since (ticked, finished
     * or deleted another way, set again, a reminder come or snoozed): why, then.
     */
    suspend fun undoAct(messageId: String, index: Int): String? {
        val act = chat.value.messages.firstOrNull { it.id == messageId }?.acts?.getOrNull(index)?.takeIf { !it.undone } ?: return null
        val a = act.action ?: return null
        val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
        val step = a.stepIndex?.let { i -> task?.subSteps?.getOrNull(i) }
        fun open(id: String) = tasks.value.tasks.firstOrNull { it.id == id }?.isOpen
        val undone = when (a.type) {
            ChatAction.Type.Open -> false
            // The step that finished one of yours: open again with it to do.
            ChatAction.Type.TickStep -> step?.done == true && focus.untickBlock(task!!.id, a.stepIndex!!, step.title, reopen = true)
            ChatAction.Type.UntickStep -> step?.done == false && task!!.isOpen && tick(task, a.stepIndex!!, step.title)
            ChatAction.Type.Done -> task?.isOpen == false && (task.source == Source.Own || task.tickDone) && run { owners.reopenOwnTask(task.id); open(task.id) == true }
            ChatAction.Type.Reopen -> task?.isOpen == true && act.was.let { was -> was != null && refinish(was) }
            // Not once a card that had added it is made again: that's the task anew.
            ChatAction.Type.Delete -> act.was.let { was ->
                was != null && task == null && !ChatActs.redone(act.cards, chat.value.messages) && run {
                    restoreTask(was)
                    relink(act.cards, was.id)
                    open(was.id) != null
                }
            }
            ChatAction.Type.Setting -> {
                val field = FelixSettings.field(a.setting.orEmpty())
                var back = false
                val before = act.before
                if (field != null && before != null) {
                    owners.changeSettings { s ->
                        if (field.get(s) != act.after) return@changeSettings s
                        field.set(s, before)?.also { back = true } ?: s
                    }
                }
                if (back) ports.scheduleReminders()
                back
            }
            // A reminder or a note (8a), while it's as the act left it: a reminder set goes, one moved has its time and
            // words back, one cancelled comes again; a note added goes, one deleted is back.
            ChatAction.Type.SetReminder, ChatAction.Type.MoveReminder, ChatAction.Type.CancelReminder, ChatAction.Type.AddNote, ChatAction.Type.DeleteNote -> {
                var back = false
                felix.update { st ->
                    if (!ChatActs.canUndo(act, tasks.value.tasks, settings.value, chat.value.messages, st.notes, st.reminders, messageId = messageId, now = clock.now()) { s -> tasks.value.status(s).lastSuccessAt }) return@update st
                    back = true
                    when (a.type) {
                        ChatAction.Type.SetReminder -> st.copy(reminders = st.reminders.filterNot { it.id == a.value })
                        ChatAction.Type.MoveReminder -> act.reminder!!.let { was -> st.copy(reminders = st.reminders.map { r -> if (r.id == a.value) r.copy(text = was.text, due = was.due, taskId = was.taskId).undrafted() else r }) }
                        ChatAction.Type.CancelReminder -> st.copy(reminders = st.reminders.map { r -> if (r.id == a.value) r.copy(cancelledAt = null) else r })
                        ChatAction.Type.AddNote -> st.copy(notes = st.notes.filterNot { it.id == a.value })
                        else -> st.copy(notes = st.notes.map { n -> if (n.id == a.value) n.copy(deletedAt = null) else n })
                    }
                }
                // One back with a time to come is written for, if it's within his window.
                if (back && a.type in setOf(ChatAction.Type.MoveReminder, ChatAction.Type.CancelReminder)) scope.launch { draftReminders(clock.now()) }
                back
            }
        }
        if (!undone) return "It's changed since, so it can't be undone here"
        chat.update { s -> s.copy(messages = s.messages.map { m -> if (m.id != messageId) m else m.copy(acts = m.acts.mapIndexed { i, x -> if (i == index) x.copy(undone = true) else x }) }) }
        return null
    }

    /**
     * [was], a task the chat opened again, back as it was before, finished: its finish and its
     * steps' ticks as they were, not finished anew (a second completion on record). Only while it's as
     * opening it left it ([com.thomaswcode.decrastination.core.ChatActs.asReopened]): work on it
     * since, or a finish another way, stands, and false.
     */
    private suspend fun refinish(was: TaskItem): Boolean {
        var back = false
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != was.id || !ChatActs.asReopened(t, was)) return@map t
                    back = true
                    t.copy(status = was.status, doneAt = was.doneAt, subSteps = was.subSteps)
                },
            )
        }
        return back
    }

    /** The chat's [cards] ("message|card") that added task [id], made again by a deletion's Undo: added, as they were. */
    private suspend fun relink(cards: List<String>, id: String) {
        if (cards.isEmpty()) return
        chat.update { s ->
            s.copy(messages = s.messages.map { m -> m.copy(proposals = m.proposals.map { p -> if ("${m.id}|${p.id}" in cards && p.state == ProposalState.Proposed && p.made == null) p.copy(state = ProposalState.Applied, made = id) else p }) })
        }
    }

    /** [task], one of your own deleted, back as it was (a chat action's Undo), its instructions laid over it again. */
    private suspend fun restoreTask(task: TaskItem) {
        tasks.update { state -> if (state.tasks.any { it.id == task.id }) state else state.copy(tasks = Duplicates.linksPresent(state.tasks + task)) }
        owners.layTaskOverrides()
    }

    /**
     * Your note [text] on [taskId] from its sheet (8a, Q14: A), kept as yours; or why it can't be: no words,
     * a task not there, or [com.thomaswcode.decrastination.core.Notes.PER_TASK] on it already.
     */
    suspend fun addNote(taskId: String, text: String): String? {
        val words = text.trim().take(Notes.MAX_TEXT)
        if (words.isEmpty()) return "A note needs some words"
        if (tasks.value.tasks.none { it.id == taskId }) return "That task isn't there now"
        var full = false
        felix.update { s ->
            full = Notes.on(taskId, s.notes).size >= Notes.PER_TASK
            if (full) s else s.copy(notes = s.notes + Note(UUID.randomUUID().toString(), words, clock.now(), By.You, taskId = taskId))
        }
        return if (full) "It has ${Notes.PER_TASK} notes already: delete one first" else null
    }

    /**
     * His notes and reminders kept as long as they're wanted (8a, the morning's prune): a reminder sent or
     * done a month ago goes, a cancelled one and a deleted note once their act's Undo is gone with its
     * message from the chat's window, and a note on a task gone from the list once its delete can't be
     * undone ([Notes.prune], [Reminders.prune]).
     */
    suspend fun pruneFelix(now: Long = clock.now()) {
        val messages = chat.value.messages
        val ids = tasks.value.tasks.mapTo(HashSet()) { it.id }
        felix.update { s -> s.copy(notes = Notes.prune(s.notes, ids, messages) { source -> tasks.value.status(source).lastSuccessAt }, reminders = Reminders.prune(s.reminders, now, messages)) }
    }

    /** His notes and reminders as the debug page and the `felix` command show them (8a): a line each, a reminder's with when it comes now. */
    fun notesAndReminders(now: Long = clock.now()): List<String> {
        val kept = felix.value
        val plan = planning.plan(now = now)
        val byId = tasks.value.tasks.associateBy { it.id }
        val reminders = kept.reminders.map { r ->
            val state = when {
                r.cancelledAt != null -> "cancelled"
                r.letGoAt != null -> "let go"
                r.doneAt != null -> "done"
                !Reminders.pending(r) -> "sent"
                r.sentAt != null -> "snoozed"
                else -> "to come"
            }
            "reminder ${r.id} ($state, ${r.by}): ${Reminders.whenLine(r, Reminders.moment(r, plan, byId, now), r.taskId?.let(byId::get)?.title, clock.zone())}, “${r.text}”" +
                (r.draft?.let { " · written ahead: “${it.message}”" }.orEmpty())
        }
        val notes = kept.notes.map { n -> "note ${n.id} (${n.by}${if (n.deletedAt != null) ", deleted" else ""}) on ${n.taskId ?: n.scope?.let(Notes::scopeLine)}: ${n.text}" }
        return reminders + notes
    }

    /** Note [id] deleted from a sheet (8a): marked, shown nowhere, and let go at the next prune, as no act holds it. */
    suspend fun deleteNote(id: String) {
        felix.update { s -> s.copy(notes = s.notes.map { if (it.id == id && it.deletedAt == null) it.copy(deletedAt = clock.now()) else it }) }
    }

    /** [given] kept in what's remembered (AI-21), and what each thing did ([com.thomaswcode.decrastination.core.MemoryEdit.line] says it). */
    suspend fun remember(given: List<Given>): List<MemoryEdit> {
        if (given.isEmpty()) return emptyList()
        var edits = emptyList<MemoryEdit>()
        memory.update { state -> Memories.edit(state, given, clock.now()).also { edits = it.second }.first }
        return edits
    }

    /**
     * What the [index]th of message [messageId]'s remembering did, taken back (its Undo, your call,
     * 16 Oct): the fact gone and the one it replaced back, or what it forgot back. An answer from
     * before what each did was kept has its lines read against what's remembered now
     * ([com.thomaswcode.decrastination.core.Memories.editOf]). Null once it is; why not, if it can't be.
     */
    suspend fun undoMemory(messageId: String, index: Int): String? {
        val message = chat.value.messages.firstOrNull { it.id == messageId } ?: return null
        val edits = message.memoryEdits
        val edit = edits.getOrNull(index)?.takeIf { !it.undone } ?: return null
        var undone = false
        memory.update { state -> Memories.undo(state, edit)?.also { undone = true } ?: state }
        if (!undone) return "It's changed since: forget it in the list of what ${settings.value.felixName} remembers"
        chat.update { s -> s.copy(messages = s.messages.map { m -> if (m.id != messageId) m else m.copy(memoryEdits = edits.mapIndexed { i, e -> if (i == index) e.copy(undone = true) else e }) }) }
        return null
    }

    /** Whether the chat is on screen (AI-24): what arrives then is read there, not a notification. */
    @Volatile
    var chatInFront = false

    /**
     * What's in the chat read up to [now] (docs/plan_v4/05-the-chat.md §4): you've left the chat, or
     * opened it from his notification. His notification made again, with nothing older in it.
     */
    suspend fun read(now: Long = clock.now()) {
        chat.update { it.copy(readAt = maxOf(it.readAt, now)) }
        outbox.notify(now)
    }

    /**
     * How many of his messages wait unread (docs/plan_v4/05-the-chat.md §4), the badge on his button:
     * those he's written since you last left the chat, but a question you've answered from his notification.
     */
    val unread: Flow<Int> = chat.state.map { s -> s.messages.count { Outbox.unread(it, s.readAt) && it.answeredAt == null } }.distinctUntilChanged()

    /**
     * Your [text] in reply to his message [id] from his notification (AI-24): asked as any reply is
     * ([ask]). A turn of the chat is answered as work of its own ([FelixPorts.answerLater]), as the
     * receiver that took the reply is let go once this returns, and the app may go with it.
     */
    suspend fun replyFromNotification(text: String, id: String): Boolean = when (send(text, replyTo = id)) {
        Asked.Nothing -> false
        Asked.Answer -> true
        Asked.Turn -> true.also { ports.answerLater() }
    }

    /**
     * Your message still waiting for Claude taken back, unanswered. One of yours before it that waited with
     * it (only the newest says why: [send]) says why it waits now, as that one did, with its Try again; one
     * that waited for its Try again still does.
     */
    suspend fun withdrawQuestion() {
        chat.update { s ->
            s.unanswered?.let { w ->
                val rest = s.messages.filterNot { it.id == w.id }
                val before = rest.lastOrNull { it.from == Speaker.You && it.answeredAt == null }?.id
                s.copy(messages = rest.map { if (it.id == before) it.copy(note = w.note, stuck = w.stuck || it.stuck) else it })
            } ?: s
        }
    }

    /** The chat cleared, its earlier messages with it. What its cards made (your tasks, changes in use) stays, and so does his state. */
    suspend fun clearChat() = outbox.clear()

    /**
     * Card [proposalId] of Claude's message [messageId] made, at once, armed or not (2.0.0): a task
     * of your own added (as [edited] if you changed it first), or its changes applied as an
     * instruction in the words that asked for them, so they're listed with the rest and taken back
     * there too. Says what went wrong on the card, if anything did.
     */
    suspend fun applyProposal(messageId: String, proposalId: String, edited: OwnTask? = null): Unit = withContext(NonCancellable) { proposing.withLock { apply(messageId, proposalId, edited) } }

    /** Held while a card is made, undone or set aside, so a second tap finds it as the first left it. */
    private val proposing = Mutex()

    private suspend fun apply(messageId: String, proposalId: String, edited: OwnTask?) {
        val messages = chat.value.messages
        val message = messages.firstOrNull { it.id == messageId } ?: return
        val proposal = message.proposals.firstOrNull { it.id == proposalId && it.state != ProposalState.Applied } ?: return
        // The message of yours it answered: its words name the instruction.
        val asked = messages.take(messages.indexOf(message)).lastOrNull { it.from == Speaker.You }
        var made: String? = null
        var note: String? = null
        var before = emptyMap<String, OwnTask>()
        // The days off and half-days it puts in the school calendar (PL-16), for its Undo.
        var schoolBefore: SchoolCalendar? = null
        var schoolAfter: SchoolCalendar? = null
        val task = edited ?: proposal.task
        if (task != null) {
            val id = UUID.randomUUID().toString()
            if (owners.saveOwnTask(id, task)) made = TaskItem.id(Source.Own, id) else note = OwnTaskRules.problem(task) ?: "It couldn't be added"
        } else if (proposal.changes.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            // Your own tasks it changes, as they are: made on them, the changes are undone by putting them back.
            val own = tasks.value.tasks.filter { it.source == Source.Own }.associateBy { it.id }
            before = proposal.changes.filter { it.type in OwnTaskRules.MADE_ON_IT }.mapNotNull { it.taskId }.distinct()
                .mapNotNull { tid -> own[tid]?.let { tid to OwnTaskRules.draftOf(it) } }.toMap()
            val instruction = Instruction(id, asked?.text ?: message.text, asked?.about ?: About(), clock.now(), state = InstructionStatus.Understood, changes = proposal.changes, by = By.Felix)
            instructions.update { it.copy(instructions = it.instructions + instruction) }
            val calendar = school.value
            owners.applyInstruction(id)
            if (school.value != calendar) {
                schoolBefore = calendar
                schoolAfter = school.value
            }
            val after = instructions.value.instructions.firstOrNull { it.id == id }
            when {
                // All made on tasks of your own, so there's nothing left of it to keep.
                after == null -> made = null
                after.state == InstructionStatus.Applied -> made = id
                else -> {
                    note = after.note ?: "It couldn't be applied"
                    before = emptyMap()
                    owners.deleteInstruction(id)
                }
            }
        }
        chat.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id != messageId) m else m.copy(proposals = m.proposals.map { p ->
                        if (p.id != proposalId) {
                            p
                        } else if (note == null) {
                            // As added: changed first, it's yours that the card shows and the chat recalls.
                            p.copy(state = ProposalState.Applied, made = made, before = before, note = null, task = task ?: p.task, schoolBefore = schoolBefore, schoolAfter = schoolAfter)
                        } else {
                            p.copy(note = note)
                        }
                    })
                },
            )
        }
    }

    /**
     * Card [proposalId] undone: the task it added deleted, or its changes taken back, those made on
     * tasks of your own put back as they were, and its days off out of the school calendar.
     */
    suspend fun undoProposal(messageId: String, proposalId: String): Unit = proposing.withLock {
        val proposal = chat.value.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId && it.state == ProposalState.Applied } ?: return@withLock
        val made = proposal.made
        if (made == null && proposal.before.isEmpty() && proposal.schoolBefore == null) return@withLock
        val note = if (proposal.task != null) {
            made?.let { owners.deleteOwnTask(it) }
            null
        } else {
            // What it changed on your own tasks put back as it was (no instruction held those changes):
            // only the fields that still hold what it set, so a later change of yours stands. One that
            // can't be (the form wouldn't take it now) keeps the card applied, saying why.
            // Every one checked before any is saved: all go back, or none and the card says why.
            val backs = proposal.before.mapNotNull { (id, was) ->
                val t = tasks.value.tasks.firstOrNull { it.id == id } ?: return@mapNotNull null
                val back = OwnTaskRules.restored(OwnTaskRules.draftOf(t), was, proposal.changes.filter { it.taskId == id })
                (t to back).takeIf { back != OwnTaskRules.draftOf(t) }
            }
            // Its days off and half-days out of the school calendar, unless one has gone from it since.
            val calendar = proposal.schoolBefore?.let { was -> school.value.undone(was, proposal.schoolAfter ?: return@let null) }
            val stuck = backs.mapNotNull { (t, back) -> OwnTaskRules.problem(back)?.let { "“${t.title}” can't go back: ${it.replaceFirstChar(Char::lowercase)}" } } +
                // And its instruction, which taking back mustn't leave tasks waiting for each other in a circle.
                listOfNotNull(made?.takeIf { m -> InstructionRules.makesCircle(emptyList(), instructions.value.applied.filterNot { it.id == m }) }
                    ?.let { "taking its changes back would bring back an earlier instruction that has tasks waiting for each other in a circle" }) +
                listOfNotNull("its days off have changed since: they're deleted in Settings → School".takeIf { proposal.schoolBefore != null && calendar == null })
            if (stuck.isNotEmpty()) {
                stuck.joinToString("; ").replaceFirstChar(Char::uppercase)
            } else {
                backs.forEach { (t, back) -> owners.saveOwnTask(t.sourceId, back) }
                calendar?.let { c -> school.update { c } }
                made?.let { owners.deleteInstruction(it) }
                made?.let { m -> instructions.value.instructions.firstOrNull { it.id == m }?.note }
            }
        }
        chat.update { s ->
            s.copy(
                // Every message's cards: a later one may be in a later reply.
                messages = s.messages.map { m ->
                    m.copy(proposals = m.proposals.map { p ->
                        when {
                            m.id == messageId && p.id == proposalId -> if (note == null) p.copy(state = ProposalState.Proposed, made = null, before = emptyMap(), schoolBefore = null, schoolAfter = null) else p.copy(note = note)
                            // Undone, its values are gone from the tasks: a card applied after it, which
                            // replaced them, puts back what this one replaced instead, if it's undone too.
                            note == null && p.state == ProposalState.Applied && p.before.keys.any { it in proposal.before } ->
                                p.copy(before = p.before.mapValues { (id, was) -> proposal.before[id]?.let { OwnTaskRules.relayered(was, proposal.changes.filter { it.taskId == id }, it) } ?: was })
                            else -> p
                        }
                    })
                },
            )
        }
    }

    /** Card [proposalId] set aside (or, [discarded] false, brought back). */
    suspend fun discardProposal(messageId: String, proposalId: String, discarded: Boolean = true): Unit = proposing.withLock {
        chat.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id != messageId) m else m.copy(proposals = m.proposals.map { p ->
                        if (p.id != proposalId || p.state == ProposalState.Applied) p else p.copy(state = if (discarded) ProposalState.Discarded else ProposalState.Proposed)
                    })
                },
            )
        }
    }

    companion object {
        /** A message's button opening the week's review ([ChatMessage.opens]). */
        const val OPENS_REVIEW = "review"

        /** A message's button opening a week's summary to send, the week (its Monday) after it ([ChatMessage.opens]). */
        const val OPENS_SUMMARY = "summary:"

        /** Completions kept for his evening question, at most: the newest. */
        private const val MAX_ASKS = 20
    }
}
