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
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.FelixSettings
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Lessons
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SlotRecord
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
import com.thomaswcode.decrastination.learn.Stats
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.sync.GmailFinishes
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
 * Felix's conversation: who the student is, as every
 * call sends it ([profile]); the chat, your messages and his answers, their cards and acts made at
 * once with their Undo ([ask], [answerChat], [applyProposal], [undoAct]); what he remembers
 * ([remember]); a reply to one of his messages, from the chat, his chips or his notification, routed
 * by what it answers ([ask], [Replies]); the week's
 * check-in as he asks it ([remindCheckIn]); and what the app says in his name, each through his
 * [outbox] (docs/spec/felix.md §His messages): the morning's list, a calendar event's question, how work
 * went, the week's review and summary. Built like [com.thomaswcode.decrastination.block.Focus]: its
 * stores, the clock and small interfaces in its constructor, for what others own ([Owners]: your own
 * tasks, your instructions, the settings, the calendar's answers, the week's check-in on record, and
 * Gmail's finishes) and what Android does
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
    /** What he keeps at your asking: notes on tasks, and reminders. */
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
    /**
     * [Source]s read now, as a job (G2, [com.thomaswcode.decrastination.sync.Sync.syncNow]): a callback, as the Sync service
     * calls this one (a calendar event's question), so this one mustn't call it back.
     */
    private val requestSync: (Set<Source>) -> Unit = {},
    /**
     * The Teams widget asked to sync Teams itself, as a tap on its refresh asks it (D-11,
     * [com.thomaswcode.decrastination.sync.Sync.requestTeamsSync]): why it didn't start, or null where it did.
     */
    private val requestTeamsSync: suspend () -> String? = { null },
) {
    /**
     * What the chat changes through those who own it: your own tasks,
     * your instructions, the settings, the calendar's answers, the week's check-in on record, and Gmail's finishes.
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

        /** A starred email of [taskId] finished by his `done`: unstarred and archived in Gmail, after the grace. */
        suspend fun finishGmail(taskId: String): GmailFinishes.Finish = GmailFinishes.Finish.Off

        /** [taskId]'s finish taken back, only [writeId] where it's given: Gmail left as it was, or put back. Whether there was one. */
        suspend fun unfinishGmail(taskId: String, writeId: String?): Boolean = false

        /** [taskId]'s finish that stands, by its id: the one his act made, for its Undo. */
        fun gmailWriteOf(taskId: String): String? = null
    }

    /**
     * Who the student is, as every model call sends it (AI-05): from the settings, the tasks, the
     * timetable, the school calendar and what's [remembered].
     */
    fun profile(now: Long = clock.now(), remembered: MemoryState = memory.value): Profile.Block {
        val s = settings.value
        val state = tasks.value
        val zone = clock.zone()
        // What's run out isn't told: the profile never carries a stale line, and what's kept lets it go too.
        val live = expired(remembered, now)
        if (live !== remembered && remembered === memory.value) scope.launch { forgetExpired(now) }
        return Profile.Block(
            Profile.yearGroup(now, zone, s.yearGroupStart),
            Profile.block(s, state.tasks, state.timetable, school.value, live, now, zone),
        )
    }

    /** [remembered] without what's run out at [now] ([Memories.expired]): by the tasks, what the chat's acts can still bring back, and the sources' reads. */
    private fun expired(remembered: MemoryState, now: Long): MemoryState {
        val state = tasks.value
        return Memories.expired(remembered, state.tasks.associateBy { it.id }, now, ChatActs.held(chat.value.messages)) { source -> state.status(source).lastSuccessAt }
    }

    /** What's run out of what's remembered let go of, silently, as a person forgets: as the profile's read, and each morning. Written only where something has. */
    suspend fun forgetExpired(now: Long = clock.now()) {
        memory.update { expired(it, now) }
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
     * [words], your reply to his [message] that's an answer, not a turn of the chat (a check-in's, a reading's
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
        val marked = mark(ids, now)
        outbox.delivered()
        Marked(marked, now)
    }

    /** His messages of [ids] still waiting marked answered at [now], but a check-in's question under way: those marked. */
    private suspend fun mark(ids: Collection<String>, now: Long): Set<String> {
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
        return marked
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
        kind = MessageKind.Completions, byApp = true, asks = listOf(ask), suggestedReplies = FelixWords.HOW_IT_WENT_REPLIES,
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
     * he's answered with it: what a web search [found], or why one made found nothing
     * ([notLooked]), and the tasks [read] in full (D-93). An answer with them that failed is asked
     * again with them, never searching or asking for them again, as each is paid for; a search held
     * back (Claude off or resting, the cap) isn't kept, so it can be made later.
     */
    private data class Gathered(val id: String, val found: Found?, val notLooked: String?, val read: List<String>, val stats: Boolean = false, val calendar: List<String> = emptyList())

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
     * Your [text] to Felix about [about] (Q29), answering the message [replyTo] if it does: one
     * entrance for replies (D-98), from the chat, his chips or his notification. A reply to a question of
     * his that's an answer is kept under it, not a turn of the chat ([routed]: with no call, but a reading's,
     * which Claude reads as an instruction to judge whether it answers it); anything else is a turn of the
     * chat, kept as message [id] (about what it replies to, where it's about nothing else, unless not
     * [takeItsSubject]: the box's words to his nudge just above it, told him as your words alone, D-99)
     * and answered ([answerChat]). One sent while he answers another of yours lets that answer go, paid
     * for and unused, and he answers both together (D-98). False only with no words. Kept, it's shown in
     * his notification under what it answers.
     */
    suspend fun ask(text: String, about: About = About(), id: String = UUID.randomUUID().toString(), replyTo: String? = null, takeItsSubject: Boolean = true): Boolean =
        send(text, about, id, replyTo, takeItsSubject) != Asked.Nothing

    /** What became of words sent ([send]): nothing (there were none), an answer taken as one (with no call, but a reading's question's, read by Claude), or a turn of the chat waiting for his answer. */
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
     * [words], your reply to [message], taken as its answer where it's the answer to a question of his
     * ([Replies.route]), with no call but the first: a reading's question, an instruction read by Claude, or
     * how work went with one of its three ([answered]); a check-in's question, its next asked ([checkInReply]); a calendar event's question, one of its three
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
            // A reminder's Done or snooze: on it; gone (pruned), a turn of the chat.
            is Replies.Route.Reminder -> if (!replyToReminder(route, now)) return false
            Replies.Route.Chat -> return false
        }
        keepReply(message, words, now)
        outbox.delivered()
        return true
    }

    /**
     * Your reply to one of your reminders: *Done* marks it done; a snooze has it come again then,
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
     * the answer's checks where the caller passes it the same ones; his messages still waiting from the chat's
     * [messages] as sent with it (up to the message answered), so one he wrote after it isn't taken as answered.
     */
    fun chatContext(
        now: Long = clock.now(),
        state: TaskState = tasks.value,
        time: EventJudge.Time = calendarTime(),
        applied: List<Instruction> = instructions.value.applied,
        kept: FelixStore = felix.value,
        messages: List<ChatMessage> = chat.value.messages,
    ): ChatPrompts.Context {
        val plan = planning.plan(state, now = now, applied = applied, time = time)
        // His notes and reminders, each reminder with when it comes by this plan.
        val byId = state.tasks.associateBy { it.id }
        val moments = kept.reminders.mapNotNull { r -> Reminders.moment(r, plan, byId, now)?.let { r.id to it } }.toMap()
        // The pieces he may keep (G1): today's slots', and each task with no steps' next; and those kept today, to take back.
        val keepable = (plan.todaySlots.mapNotNull { it.chunk } + plan.ordered.filter { it.stepIndex == null }.distinctBy { it.taskId }).associate { it.key to it.taskId }
        val keptToday = keptToday(now)
        val lessons = lessons(state, now)
        val waiting = waiting(now, messages)
        return ChatPrompts.Context(
            known = ChatPrompts.context(state.tasks, time.events, applied, now, clock.zone(), settings.value.classMap, runtime.value.notSame, settings.value, learning.value.calibration, kept.notes, kept.reminders, moments, lessons, waiting),
            plan = ChatPrompts.plan(plan, clock.zone(), keptToday.map { it.key to it.label }, focus.blockingLine(plan, now), focus.timer),
            keepable = keepable,
            kept = keptToday.associate { it.key to it.taskId },
            lessons = lessons,
            waiting = waiting.mapTo(HashSet()) { it.id },
        )
    }

    /**
     * His nudges of [messages] still waiting for your answer at [now] (D-99): the newest [ChatPrompts.MAX_WAITING], oldest
     * first, which the chat's answer may name as answered by your words. Not a question whose answer the app keeps
     * (a reading's, how work went, a calendar event's, the check-in's), nor a reminder (its *Done*): marked so, its answer
     * would be lost, so each waits for its reply as before ([ANSWERS_KEPT]).
     */
    private fun waiting(now: Long, messages: List<ChatMessage>): List<ChatMessage> {
        val ids = Replies.awaitingIds(messages, chat.value.felix.checkInDraft != null, now, clock.zone())
        return messages.filter { it.id in ids && it.kind !in ANSWERS_KEPT }.takeLast(ChatPrompts.MAX_WAITING)
    }

    /**
     * Each class's next lessons (G8), by subject, from Power Planner's timetable in [state] as of its last good
     * read (DX-09), said to be stale past a week; none with no timetable read.
     */
    fun lessons(state: TaskState = tasks.value, now: Long = clock.now()): Lessons.Timetabled? {
        val timetable = state.timetable ?: return null
        val s = settings.value
        val next = Lessons.next(timetable, now, clock.zone(), school.value, skip = setOf(s.studyPeriodName))
        val readAt = state.status(Source.PowerPlanner).lastSuccessAt
        return Lessons.Timetabled(Lessons.groups(next, s.classMap), readAt, Lessons.stale(readAt, now))
    }

    /** A task he asked a source to read about (G2), as it was then, watched till [until]. */
    private data class Watch(val source: Source, val was: TaskItem, val until: Long)

    /** The tasks he asked sources to read about (G2), by id: in memory, as a read comes within minutes or not at all. */
    private val watching = ConcurrentHashMap<String, Watch>()

    /**
     * After every sync (G2): each task he asked a source to read about that the read changed (finished,
     * open again, due another day, its words), said in a note of his in the app's words, then let go;
     * one unchanged waits for a later read till its watch is over, so a sync already under way when he
     * asked doesn't end it; one over, or its task gone, is let go first, whatever a later read finds.
     */
    suspend fun afterSync(now: Long = clock.now()) {
        if (watching.isEmpty()) return
        val byId = tasks.value.tasks.associateBy { it.id }
        for ((id, watch) in watching.entries.toList()) {
            val task = byId[id]
            if (now >= watch.until || task == null) {
                watching.remove(id, watch)
                continue
            }
            val what = changed(watch.was, task, watch.source) ?: continue
            watching.remove(id, watch)
            outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, FelixWords.read(watch.source.label, task.title, what), now, About(taskId = task.id, taskTitle = task.title), kind = MessageKind.Note, byApp = true))
        }
    }

    /**
     * How [now] differs from [was] as a read of [source] tells it, in words, by the source's own values: its finish
     * (not one done by its tick, which is yours), its deadline and its name as it gives them, not what your
     * instructions or a reading laid over them, so a change made here meanwhile isn't said as the source's. Null
     * where nothing he'd say changed.
     */
    private fun changed(was: TaskItem, now: TaskItem, source: Source): String? {
        fun due(t: TaskItem) = t.sourceValues.let { v -> if (v != null) v.dueAt else t.dueAt }
        fun name(t: TaskItem) = t.sourceValues?.title ?: t.title
        val theirs = !now.tickDone
        return when {
            // Finished as its source has it, done: a quota missed (taken over by homework) isn't studied.
            theirs && was.isOpen && now.status == Status.Done -> when (source) {
                Source.Teams -> "is handed in"
                Source.Gmail -> "is unstarred, so it's done"
                Source.Anki -> "is studied"
                else -> "is done"
            }
            theirs && !was.isOpen && now.isOpen -> "is open again"
            due(was) != due(now) -> due(now)?.let { "is due ${Instant.ofEpochMilli(it).atZone(clock.zone()).format(WHEN)} now" } ?: "has no due date now"
            name(was) != name(now) || was.detail != now.detail -> "has changed"
            else -> null
        }
    }

    /** Today's kept pieces by key, each with its newest record's time (G1): a keep's Undo takes back only the record it made, while it's that one. */
    fun keptAt(now: Long = clock.now()): Map<String, Long> {
        val today = focus.today(now)
        return log.value.slots.filter { it.outcome.kept && focus.today(it.at) == today }.groupBy { it.key }.mapValues { (_, records) -> records.maxOf { it.at } }
    }

    /**
     * The pieces kept today (G1), whatever day their slot was for (one done ahead of it too), each key's newest
     * record, newest last: what unkeepPiece takes back, so the line he's shown names that one.
     */
    fun keptToday(now: Long = clock.now()): List<SlotRecord> {
        val today = focus.today(now)
        return log.value.slots.filter { it.outcome.kept && focus.today(it.at) == today }.groupBy { it.key }.map { (_, records) -> records.maxBy { it.at } }.sortedBy { it.at }
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
            // When the plan he was last shown was made: a piece he keeps is kept as of it (G1).
            var shownAt = clock.now()
            // His answer, with what a web search he asked for found ([found]), the tasks whose whole
            // text he asked to [read] (D-93), and the week's numbers and the calendar further out where he asked
            // for them ([stats], [calendar]), as of the moment it's asked, why a search he asked for couldn't be made ([notLooked]),
            // so he doesn't ask for it again; at the [last] round a message has, told so.
            suspend fun ask(found: Found?, read: List<TaskItem> = emptyList(), stats: Boolean = false, calendar: List<String> = emptyList(), last: Boolean = false, notLooked: String? = null) = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.Chat, waiting.text) { r: ChatReader.Result -> r.usage to ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: ChatReader.Result -> r.costUsd to r.refused }) { at ->
                // Being asked now: whatever it said before (a failed call) is gone.
                chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(note = null, stuck = false) else it }) }
                sentAt.value = at
                shownAt = at
                try {
                    val sent = upTo(chat.value.messages)
                    // What a message of yours quotes from the archive, past the window: told to him all the same.
                    val quoted = sent.mapNotNull { m -> m.replyTo?.takeIf { r -> sent.none { it.id == r } }?.let { outbox.find(it) } }
                    val brought = listOfNotNull(ChatPrompts.statsBlock(Stats.weekLines(log.value, settings.value.classMap, at, clock.zone())).takeIf { stats }) +
                        calendar.map { ChatPrompts.calendarBlock(time.events, it, at, clock.zone(), time.read) }
                    ChatReader(chatClient, clock.zone(), profile = profile(at), settings = settings.value).answer(sent, state.tasks, time.events, chatContext(at, state, time, instructions.value.applied, noted, sent), at, found, read, quoted, noted, brought, last, notLooked)
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
            var stats = before?.stats == true
            var calendar = before?.calendar.orEmpty()
            // Let go while it ran (AC-08: the message says so already), or another of yours sent meanwhile, to be
            // answered with it (D-98): the answer goes unused, and nothing more is looked up or read for it.
            fun unwanted(): Boolean {
                val gone = letGo == waiting.id || chat.value.unanswered?.id != waiting.id
                if (letGo == waiting.id) letGo = null
                return gone
            }
            val called = ask(before?.found, read, stats, calendar, notLooked = before?.notLooked.takeIf { before?.found == null })
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
            // What he asked to look up on the web, in a call of its own, and the tasks whose
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
                val next = ChatRounds.next(reading, searched = found != null || notLooked != null, searches = settings.value.felixSearches, read = read.map { it.id }, stats = stats, calendar = calendar) ?: break
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
                // The week's numbers and the calendar further out, each span the first time he asks for it.
                val brings = next.stats != stats || next.calendar != calendar
                stats = next.stats
                calendar = next.calendar
                // Nothing new to answer with (a search that found nothing, no texts asked for): his answer stands.
                if (!looks && !reads && !brings) break
                gathered = Gathered(waiting.id, found, tried, read.map { it.id }, stats, calendar)
                val again = ask(found, read, stats, calendar, last = round == ChatRounds.MAX_ROUNDS, notLooked = notLooked.takeIf { found == null })
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
            // Still asking after the last round a message has: said under his answer, not his "looking" passed off as one.
            val unserved = ChatRounds.asking(reading, searched = found != null || notLooked != null, searches = settings.value.felixSearches, read = read.map { it.id }, stats = stats, calendar = calendar)
            reading = reading.copy(research = null, details = emptyList(), stats = false, calendar = null)
            gathered = null
            val reply = ChatMessage(
                id = UUID.randomUUID().toString(),
                from = Speaker.Felix,
                text = reading.reply,
                at = clock.now(),
                // Everything on a card made as it comes (D-60), each with its Undo.
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
                    // What else he read to answer, said under it as the texts are.
                    Act("Read: the week's numbers.").takeIf { stats },
                    *calendar.map { Act("Read: the calendar for " + ChatPrompts.calendarWhat(it, shownAt, clock.zone()) + ".") }.toTypedArray(),
                    Act(Act.NOT_BROUGHT + "he asked for more than one message brings (two rounds, three texts, two spans of the calendar): ask again to go on.", problem = true).takeIf { unserved },
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
            // Its actions (AI-14; D-60), said under it with their Undo: before its
            // changes, as its steps are the ones it was shown.
            val acts = if (kept) act(reading.actions, shownAt, reply.id) else emptyList()
            if (acts.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = acts + it.acts) else it }) }
            // Its cards, made now, as if Apply were tapped: Undo takes them back.
            if (kept) {
                val instant = reply.proposals.filter { it.instant }
                instant.forEach { applyProposal(reply.id, it.id) }
                // Its reminders and notes, after its changes, so one before a deadline it sets has it.
                // By the deadlines of its cards that were made: one refused (a due date before a start of yours) sets none.
                val made = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty().filter { it.instant && it.state == ProposalState.Applied }
                val noted = keep(reading.reminders, reading.notes, ChatAnswers.deadlines(made.flatMap { it.changes }))
                if (noted.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + noted) else it }) }
                // A reminder set or moved into his window is written for at once, not at the next plan or look.
                if (noted.any { it.action?.type == ChatAction.Type.SetReminder || it.action?.type == ChatAction.Type.MoveReminder }) scope.launch { draftReminders(clock.now()) }
                // A time the plan couldn't keep (PL-04): where the piece went, said under the answer,
                // which names the time asked for.
                val moved = ChatAnswers.movedPins(instant.flatMap { it.changes }, planning.plan().buckets, clock.zone())
                // One that couldn't be made after all, said under the answer that says it was.
                val refused = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty()
                    .filter { p -> p.instant && p.state != ProposalState.Applied && p.note != null }.map { Act(Act.NOT_MADE + "${it.note!!.replaceFirstChar(Char::lowercase)}.", problem = true) }
                val lines = refused + moved.map { Act(it) }
                if (lines.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + lines) else it }) }
                // His messages still waiting that your words answered, at his word (D-99): marked as *Mark answered* marks them,
                // and said under his answer, so one that stops waiting doesn't just vanish.
                if (reading.answered.isNotEmpty()) {
                    val marked = mark(reading.answered, reply.at)
                    if (marked.isNotEmpty()) chat.update { s ->
                        val taken = s.messages.filter { it.id in marked }.map { Act("Taken as answered: “${it.text.trim().take(TAKEN_TEXT)}”") }
                        s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + taken) else it })
                    }
                }
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
     * The chat's actions (AI-14; D-60), made at once, as it alters
     * what exists (AI-24), each said under the answer with what its Undo needs ([undoAct]): a step
     * ticked (its piece kept where the plan has one, today or another day, earning as a tick does,
     * else ticked as on its sheet) or to do again; a task of yours done, open again or deleted; a
     * setting set. None for one that couldn't be. A piece kept as of the plan he was shown, made at
     * [shownAt] (G1): one kept from Day meanwhile isn't kept twice. A task to open where it lives with
     * the chat not on screen comes on a card on his message ([replyId]) instead (G3).
     */
    private suspend fun act(actions: List<ChatAction>, shownAt: Long, replyId: String): List<Act> {
        fun said(line: String, a: ChatAction) = Act(line, a)
        // A task's card on his message (G3), where it isn't, or mayn't have been, opened where it lives.
        suspend fun card(t: TaskItem) = chat.update { s -> s.copy(messages = s.messages.map { m -> if (m.id == replyId) m.copy(proposals = m.proposals + Proposal(UUID.randomUUID().toString(), open = t.id)) else m }) }
        return actions.mapNotNull { a ->
            val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
            when (a.type) {
                // Shown on its card; or opened where it lives at once (G3), as its sheet's Open does, which notes it seen worked on.
                ChatAction.Type.Open -> task?.takeIf { a.value == ChatAnswers.OPEN_SOURCE }?.let { t ->
                    // Only while the chat's on screen, looked at as it opens: from his notification's reply, a retry in the
                    // background or with the chat left, Android may block the start, and one long after would surprise
                    // you; on a card then, its sheet's Open opening it there.
                    if (!chatInFront) {
                        card(t)
                        return@let Act("On its card, not opened in ${t.source.label}: the chat wasn't on screen.")
                    }
                    val (opened, say) = ports.openSource(t)
                    // Left while it opened (the opener waits on the source's app first), it mayn't have come up: its card's there too.
                    val left = opened && !chatInFront
                    if (left) card(t)
                    if (opened) Act("Opened in ${t.source.label}: ${t.title}" + say?.let { ". $it" }.orEmpty() + if (left) ". The chat was left as it opened: its card's here if it didn't come up" else "")
                    else Act("Couldn't open “${t.title}” in ${t.source.label}" + say?.let { ": $it" }.orEmpty(), problem = true)
                }
                ChatAction.Type.TickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { !it.done } ?: return@mapNotNull null
                    // What its Undo is tied to: the record its piece's keep made, or a tick by hand ([ChatActs.tickedBy]).
                    val ticked = tick(task, index, step.title) ?: return@mapNotNull null
                    Act("Ticked: ${step.title}, of ${task.title}", a, after = ticked)
                }
                ChatAction.Type.UntickStep -> {
                    val index = a.stepIndex ?: return@mapNotNull null
                    val step = task?.subSteps?.getOrNull(index)?.takeIf { it.done } ?: return@mapNotNull null
                    said("To do again: ${step.title}, of ${task.title}", a).takeIf { focus.untickBlock(task.id, index, step.title) }
                }
                ChatAction.Type.Done -> {
                    if (task?.isOpen != true) return@mapNotNull null
                    // A starred email: finished in Gmail, which closes it at the read after the write (D-10).
                    if (task.source == Source.Gmail) {
                        return@mapNotNull when (owners.finishGmail(task.id)) {
                            // The finish its own, by id, so its Undo takes back that one and not a newer.
                            GmailFinishes.Finish.Going -> Act("Finished: “${task.title}” (unstarring and archiving it in Gmail)", a, after = owners.gmailWriteOf(task.id))
                            // One standing already, made before this answer: said, with nothing of this one's to undo.
                            GmailFinishes.Finish.Pending -> Act("Being finished in Gmail already: “${task.title}”")
                            GmailFinishes.Finish.Awaiting -> Act("Waiting for your OK in the app to finish it in Gmail: “${task.title}”", a, after = owners.gmailWriteOf(task.id))
                            GmailFinishes.Finish.Off, GmailFinishes.Finish.No -> Act(Act.NOT_MADE + "“${task.title}” is done when Gmail says so.", problem = true)
                        }
                    }
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
                // A reminder cancelled, a note deleted: marked, kept whole for the Undo, never removed here.
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
                // A source read now (G2), said with no Undo; the task asked about watched, so its change is said when it's read.
                ChatAction.Type.Sync -> {
                    val source = Source.entries.firstOrNull { it.name == a.setting } ?: return@mapNotNull null
                    task?.let { watching[it.id] = Watch(source, it, clock.now() + WATCH_MS) }
                    requestSync(setOf(source))
                    // Teams: with the chat on screen, its widget's own sync too, as a tap on its refresh asks it, which
                    // has the screen for a minute (D-11); else what the widget has, and the line says why.
                    Act(
                        when {
                            source != Source.Teams -> FelixWords.reading(source.label)
                            !chatInFront -> FelixWords.readingWidget("the chat wasn't on screen to start its sync")
                            else -> requestTeamsSync()?.let { why -> FelixWords.readingWidget("its sync didn't start (${why.replaceFirstChar(Char::lowercase)})") } ?: FelixWords.TEAMS_SYNCING
                        },
                    )
                }
                // A piece done that has no step to tick (G1): kept as Day's Done keeps it, earning as that does; or taken back.
                ChatAction.Type.KeepPiece -> {
                    val label = planning.plan().let { p -> (p.todaySlots.mapNotNull { it.chunk } + p.ordered).firstOrNull { it.key == a.value } }?.label ?: task?.title
                    // As of the plan he was shown: kept from Day meanwhile, it isn't kept again (a box's next has its key).
                    if (!focus.keepSlot(a.value.orEmpty(), seenAt = shownAt)) return@mapNotNull null
                    // Its record's time with it, so its Undo takes back that record only, while it's the piece's newest.
                    Act("Kept: $label", a, after = keptAt()[a.value]?.toString())
                }
                ChatAction.Type.UnkeepPiece -> {
                    // The record he was shown, its key's newest then: kept again since (taken back and kept from Day while he
                    // answered), the newest is yours, not his to take back, and his answer says so.
                    val at = keptAt()[a.value] ?: return@mapNotNull null
                    val record = log.value.slots.lastOrNull { it.key == a.value && it.outcome.kept && it.at == at } ?: return@mapNotNull null
                    if (at > shownAt) return@mapNotNull Act(Act.NOT_MADE + "“${record.label}” isn't taken back: it was kept again while he answered.", problem = true)
                    // One of yours, or one done by its tick, that this record finished is open again with it, as a keep's Undo
                    // opens one; finished another way since, it stays so, nothing's taken back, and his answer says so.
                    val reopen = task != null && ChatActs.finishedBy(task, at)
                    if (task != null && !task.isOpen && !reopen) return@mapNotNull Act(Act.NOT_MADE + "“${record.label}” isn't taken back: “${task.title}” was finished since, another way.", problem = true)
                    if (!focus.unkeepSlot(record.key, at, reopen = reopen)) return@mapNotNull null
                    // What it was kept with, so its Undo keeps it so again: its extra minutes past its piece as the plan lays it
                    // again (its own and, a step's last sitting, its step's other sittings, to do again now, which a keep counts
                    // again), its photo, its source's word; and that piece's minutes, so one laid otherwise since isn't kept for it.
                    val (own, rest) = focus.pieceMinutes(record.key) ?: (record.plannedMin to 0)
                    val with = "${(record.workedMin - own - rest).coerceAtLeast(0)}|${record.photo}|${record.earned}|$own|$rest"
                    Act("Not kept after all: ${record.label}", a, before = with, after = record.at.toString())
                }
            }
        }
    }

    /**
     * [reminders] set, or moved and reworded in place where one has a listed reminder's id, and [notes] kept:
     * made at once, each said under his answer with its Undo ([undoAct]), when it'll come said as the
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
    private suspend fun tick(task: TaskItem, index: Int, title: String): String? {
        val plan = planning.plan()
        val piece = (plan.todaySlots.mapNotNull { it.chunk } + plan.ordered).firstOrNull { it.taskId == task.id && it.ticks == index }
        // Kept as its piece, the record that made; else by hand. Null, nothing ticked.
        if (piece != null) return if (focus.keepSlot(piece.key)) keptAt()[piece.key]?.toString() else null
        return ChatActs.TICKED_BY_HAND.takeIf { runCatching { focus.tickBlock(task.id, index, title) }.getOrDefault(false) }
    }

    /**
     * The [index]th of message [messageId]'s actions taken back ([act], its Undo): a step ticked to
     * do again (one of yours it finished open again with it), or one unticked ticked again as the
     * chat ticks one; a task done open again, or one opened back as it was, finished; one deleted
     * back as it was, with the cards that added it; a setting back to its value before; a reminder or a
     * note set, moved, cancelled or deleted taken back. Not once it's changed since (ticked, finished
     * or deleted another way, set again, a reminder come or snoozed): why, then.
     */
    suspend fun undoAct(messageId: String, index: Int): String? {
        val act = chat.value.messages.firstOrNull { it.id == messageId }?.acts?.getOrNull(index)?.takeIf { !it.undone } ?: return null
        val a = act.action ?: return null
        val task = tasks.value.tasks.firstOrNull { it.id == a.taskId }
        val step = a.stepIndex?.let { i -> task?.subSteps?.getOrNull(i) }
        fun open(id: String) = tasks.value.tasks.firstOrNull { it.id == id }?.isOpen
        val undone = when (a.type) {
            // A task shown, a source read (G2): nothing to take back.
            ChatAction.Type.Open, ChatAction.Type.Sync -> false
            // The step that finished one of yours: open again with it to do.
            ChatAction.Type.TickStep -> step?.done == true && ChatActs.tickedBy(act, task!!.id, a.stepIndex!!, step, keptAt()) && focus.untickBlock(task.id, a.stepIndex!!, step.title, reopen = true)
            ChatAction.Type.UntickStep -> step?.done == false && task!!.isOpen && tick(task, a.stepIndex!!, step.title) != null
            // A starred email's, its task here or not (a backup restored: no read brings back an email unstarred).
            ChatAction.Type.Done -> if (task?.source == Source.Gmail || task == null && act.after != null) owners.unfinishGmail(a.taskId, act.after) else task?.isOpen == false && (task.source == Source.Own || task.tickDone) && run { owners.reopenOwnTask(task.id); open(task.id) == true }
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
            // A piece kept (G1): the record it made taken back, while it's the piece's newest; one taken back, kept again as the chat keeps one.
            ChatAction.Type.KeepPiece -> act.after?.toLongOrNull()?.let { at ->
                // Open again only where this keep finished it: one finished another way since stays so.
                val reopen = task != null && ChatActs.finishedBy(task, at)
                keptAt()[a.value] == at && (task?.isOpen == true || reopen) && focus.unkeepSlot(a.value.orEmpty(), at, reopen = reopen)
            } == true
            // As it was kept: its extra minutes, its photo, its source's word ([Act.before]); the day of the record it took back
            // only, and while its piece is laid as it was then (its box's length, its task's minutes changed, it's another).
            ChatAction.Type.UnkeepPiece -> act.after?.toLongOrNull()?.let { removed -> focus.today(removed) == focus.today() && keptAt()[a.value]?.let { it < removed } ?: true } == true && task?.isOpen == true && act.before.orEmpty().split('|').let { w ->
                val photo = w.getOrNull(1) == "true"
                val laid = w.getOrNull(3)?.toIntOrNull()?.let { own -> own to (w.getOrNull(4)?.toIntOrNull() ?: 0) }
                laid != null && focus.pieceMinutes(a.value.orEmpty()) == laid && focus.keepSlot(a.value.orEmpty(), extraMin = w.getOrNull(0)?.toIntOrNull() ?: 0, photo = photo, verified = !photo && w.getOrNull(2) == "true")
            }
            // A reminder or a note, while it's as the act left it: a reminder set goes, one moved has its time and
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
     * Your note [text] on [taskId] from its sheet (D-100), kept as yours; or why it can't be: no words,
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
     * His notes and reminders kept as long as they're wanted (the morning's prune): a reminder sent or
     * done a month ago goes, a cancelled one and a deleted note once their act's Undo is gone with its
     * message from the chat's window, and a note on a task gone from the list once its delete can't be
     * undone ([Notes.prune], [Reminders.prune]).
     */
    suspend fun pruneFelix(now: Long = clock.now()) {
        val messages = chat.value.messages
        val ids = tasks.value.tasks.mapTo(HashSet()) { it.id }
        felix.update { s -> s.copy(notes = Notes.prune(s.notes, ids, messages) { source -> tasks.value.status(source).lastSuccessAt }, reminders = Reminders.prune(s.reminders, now, messages)) }
    }

    /** His notes and reminders as the debug page and the `felix` command show them: a line each, a reminder's with when it comes now. */
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

    /** Note [id] deleted from a sheet: marked, shown nowhere, and let go at the next prune, as no act holds it. */
    suspend fun deleteNote(id: String) {
        felix.update { s -> s.copy(notes = s.notes.map { if (it.id == id && it.deletedAt == null) it.copy(deletedAt = clock.now()) else it }) }
    }

    /**
     * [given] kept in what's remembered (AI-21), and what each thing did ([com.thomaswcode.decrastination.core.MemoryEdit.line]
     * says it): each until read where you are, and a fact's task one of the open tasks he's shown.
     */
    suspend fun remember(given: List<Given>): List<MemoryEdit> {
        if (given.isEmpty()) return emptyList()
        var edits = emptyList<MemoryEdit>()
        val listed = tasks.value.tasks.filter { it.isOpen && !it.hidden }.associate { it.id to it.title }
        // What's run out let go of first, so it can't take a rule's place or push out a fact that lasts.
        val now = clock.now()
        memory.update { state -> Memories.edit(expired(state, now), given, now, clock.zone(), listed).also { edits = it.second }.first }
        return edits
    }

    /**
     * What the [index]th of message [messageId]'s remembering did, taken back (its Undo, D-64):
     * the fact gone and the one it replaced back, or what it forgot back. An answer from
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

    /** [rule] let go of, as Settings → Felix's list deletes one (AI-21). */
    suspend fun deleteRule(rule: Rule) {
        memory.update { it.copy(rules = it.rules - rule) }
    }

    /** [fact] let go of, as Settings → Felix's list deletes one (AI-21). */
    suspend fun deleteFact(fact: Fact) {
        memory.update { it.copy(facts = it.facts - fact) }
    }

    /** Whether the chat is on screen (AI-24): what arrives then is read there, not a notification. */
    @Volatile
    var chatInFront = false

    /**
     * What's in the chat read up to [now] (docs/spec/felix.md §The chat): you've left the chat, or
     * opened it from his notification. His notification made again, with nothing older in it.
     */
    suspend fun read(now: Long = clock.now()) {
        chat.update { it.copy(readAt = maxOf(it.readAt, now)) }
        outbox.notify(now)
    }

    /**
     * How many of his messages wait unread (docs/spec/felix.md §Who he is), the badge on his button:
     * those he's written since you last left the chat, but a question you've answered, or a message you've replied to,
     * from his notification.
     */
    val unread: Flow<Int> = chat.state.map { s -> Outbox.repliedTo(s).let { replied -> s.messages.count { Outbox.unread(it, s.readAt) && it.answeredAt == null && it.id !in replied } } }.distinctUntilChanged()

    /**
     * Your [text] in reply to his message [id] from his notification (AI-24): asked as any reply is
     * ([ask]). A turn of the chat is answered as work of its own ([FelixPorts.answerLater]), and an answer
     * to his question about a task, an instruction for Claude to read ([Looks.answered]), read so
     * ([FelixPorts.readLater]), as the receiver that took the reply is let go once this returns, and the
     * app may go with it. Only an instruction this reply made: one left waiting before it (a call that failed) waits
     * for its own retry, so a reply that needs no call starts none.
     */
    suspend fun replyFromNotification(text: String, id: String): Boolean {
        val before = instructions.value.instructions.mapTo(HashSet()) { it.id }
        return when (send(text, replyTo = id)) {
            Asked.Nothing -> false
            Asked.Answer -> true.also { if (instructions.value.instructions.any { it.id !in before && it.state == InstructionStatus.Reading }) ports.readLater() }
            Asked.Turn -> true.also { ports.answerLater() }
        }
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
     * Card [proposalId] of Claude's message [messageId] made, at once, armed or not: a task
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
        // The message of yours it answered: its words name the instruction. One of his own, a morning's
        // rebalance (D-110), answered none: what it made names it.
        val asked = messages.take(messages.indexOf(message)).lastOrNull { it.from == Speaker.You }?.takeIf { message.kind == MessageKind.Chat }
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
            val instruction = Instruction(id, asked?.text ?: proposal.lines.firstOrNull() ?: message.text, asked?.about ?: About(), clock.now(), state = InstructionStatus.Understood, changes = proposal.changes, by = By.Felix)
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

        /** How long a task he asked a source to read about is watched (G2): a read is seconds, the Teams widget's own sync a minute or two. */
        private const val WATCH_MS = 15 * 60_000L

        /** A due date, as his note says it. */
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

        /** How much of a message of his marked answered by his word its line under his answer quotes. */
        private const val TAKEN_TEXT = 60

        /** His messages whose answer the app keeps, by a reply to them alone: never marked answered by his word. */
        private val ANSWERS_KEPT = setOf(MessageKind.Question, MessageKind.Completions, MessageKind.EventQuestion, MessageKind.CheckIn, MessageKind.Reminder)
    }
}
