package com.thomaswcode.decrastination.felix

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.AssessLater
import com.thomaswcode.decrastination.data.FelixSettings
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
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
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Felix's conversation (docs/plan_v4, Stage 4), moved out of `AppGraph`: who the student is, as every
 * call sends it ([profile]); the chat, your messages and his answers, their cards and acts made at
 * once with their Undo ([ask], [answerChat], [applyProposal], [undoAct]); what he remembers
 * ([remember]); a reply from his notification or one of his chips ([replyToFelix]); the week's
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
    private val log: JsonStore<ActivityLog>,
    private val instructions: JsonStore<InstructionState>,
    private val chat: JsonStore<ChatState>,
    private val school: JsonStore<SchoolCalendar>,
    private val memory: JsonStore<MemoryState>,
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

        /** [words], one of a calendar question's answers, kept for every event named [key] (Q7): false where they're none of them. */
        suspend fun answerEvent(key: String, words: String): Boolean
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
     * already, or his notifications can't be shown or it's quiet hours (false then, and the form's
     * reminder goes instead).
     */
    suspend fun askCheckIn(): Boolean {
        val now = clock.now()
        val week = Daily.checkInWeek(now, clock.zone(), settings.value.checkInMin)
        if (log.value.checkIns.any { it.weekOf == week }) return true
        if (!notices.shown(Channels.FELIX)) return false
        // In quiet hours the form's reminder goes instead, held as the routine ones are till the morning.
        if (BlockPolicy.isQuiet(now, clock.zone(), settings.value)) return false
        askNext(CheckInFlow.Draft(week), now)
        return true
    }

    /** [draft]'s question as his message, in the app's words, with its replies, the draft kept with it: so its reply comes back here. */
    private suspend fun askNext(draft: CheckInFlow.Draft, now: Long, again: Boolean = false) {
        val text = (if (again) FelixWords.CHECK_IN_AGAIN else "") + CheckInFlow.question(draft)
        val question = ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, text, now, kind = MessageKind.CheckIn, byApp = true, suggestedReplies = CheckInFlow.replies(draft, log.value, clock.zone()))
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
        var added = false
        log.update { l -> if (l.checkIns.any { it.weekOf == next.week }) l else l.copy(checkIns = l.checkIns + flow.checkIn(next, now)).trimmed(now).also { added = true } }
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, FelixWords.CHECK_IN_THANKS, now, kind = MessageKind.CheckIn, byApp = true)) { it.copy(checkInDraft = null) }
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
                messages = s.messages.map { if (it.from == Speaker.Claude && it.kind == MessageKind.CheckIn && it.answeredAt == null) it.copy(answeredAt = now) else it },
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
        val reply = ChatMessage(UUID.randomUUID().toString(), Speaker.You, words, now, message.about, replyTo = message.id, answeredAt = now)
        chat.update { s -> s.copy(messages = s.messages.map { if (it.id == message.id && it.answeredAt == null) it.copy(answeredAt = now) else it } + reply) }
    }

    /** The morning's list (LR-04) as his message, in the app's words, where he has none of his own ([Looks.morning]): the day's [title], then its [lines]. */
    suspend fun morningList(title: String, lines: List<String>) =
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, FelixWords.morningList(title, lines), clock.now(), kind = MessageKind.Morning, byApp = true))

    /**
     * An event the plan can't judge (Q7), [title] on [day], asked about as his message in the app's
     * words, its [answers] his chips: one of them, from here or his notification, is kept for every
     * event named [key] with no call ([replyToFelix]).
     */
    suspend fun askAboutEvent(key: String, title: String, day: String, start: Long, answers: List<String>) = outbox.send(
        ChatMessage(
            UUID.randomUUID().toString(), Speaker.Claude, FelixWords.eventQuestion(title, day), clock.now(),
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
        asks.forEach { outbox.send(howItWent(it)) }
    }

    /** Those kept for his evening question asked now, a message each, as Claude is off: kept while it was on. */
    suspend fun askKept() {
        if (gateway.claudeKey() != null) return
        val kept = chat.value.felix.assessLater
        if (kept.isEmpty()) return
        chat.update { s -> s.copy(felix = s.felix.copy(assessLater = s.felix.assessLater - kept.toSet())) }
        kept.forEach { outbox.send(howItWent(it)) }
    }

    /** How [ask]'s work went, as his message in the app's words: the three answers its replies, each kept on it ([Looks.answered]). */
    private fun howItWent(ask: AssessLater) = ChatMessage(
        UUID.randomUUID().toString(), Speaker.Claude, FelixWords.howItWent(ask.title), clock.now(), About(taskId = ask.taskId, taskTitle = ask.title),
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
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, firstLine, clock.now(), kind = MessageKind.Review, byApp = true, opens = OPENS_REVIEW))

    /** The [week]'s summary written (NF-02): his offer to send it, with the button that opens it in an email. */
    suspend fun summaryReady(week: String) =
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, FelixWords.SUMMARY_READY, clock.now(), kind = MessageKind.Summary, byApp = true, opens = OPENS_SUMMARY + week))

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
     * Your [text] to Claude about [about] (2.0.0, Q29), answering his message [replyTo] if it does: kept
     * as message [id], then answered ([answerChat]). Not while one of yours still waits for its answer:
     * false then, and nothing's kept. Kept, it's shown in his notification under what it answers.
     */
    suspend fun ask(text: String, about: About = About(), id: String = UUID.randomUUID().toString(), replyTo: String? = null): Boolean {
        val words = text.trim().take(ChatPrompts.MAX_TEXT)
        if (words.isEmpty()) return false
        var added = false
        chat.update { state ->
            if (state.unanswered != null) {
                state
            } else {
                added = true
                state.copy(messages = state.messages + ChatMessage(id, Speaker.You, words, clock.now(), about, replyTo = replyTo))
            }
        }
        if (added) {
            outbox.delivered()
            scope.launch { answerChat() }
        }
        return added
    }

    /**
     * What the chat tells Claude the app knows at [now] ([ChatPrompts.context]): the open tasks, the
     * calendar and the changes in use; and beside them the plan as the Day and Work tabs show it then, the
     * slot under way included ([ChatPrompts.plan]). All of one [state], calendar [time] and
     * [applied] instructions, so the list and the plan agree, and with the answer's checks where the
     * caller passes it the same ones.
     */
    fun chatContext(
        now: Long = clock.now(),
        state: TaskState = tasks.value,
        time: EventJudge.Time = calendarTime(),
        applied: List<Instruction> = instructions.value.applied,
    ): ChatPrompts.Context {
        return ChatPrompts.Context(
            known = ChatPrompts.context(state.tasks, time.events, applied, now, clock.zone(), settings.value.classMap, runtime.value.notSame, settings.value, runtime.value.calibration),
            plan = ChatPrompts.plan(planning.plan(state, now = now, applied = applied, time = time), clock.zone()),
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
            // One snapshot of the tasks, the calendar and your changes: what Claude's shown is what its answer's checked against.
            val state = tasks.value
            val time = calendarTime()
            // The conversation up to your message: what he wrote unasked after it isn't what it answers.
            fun upTo(messages: List<ChatMessage>) = messages.take(messages.indexOfFirst { it.id == waiting.id } + 1)
            // His answer, with what a web search he asked for found ([found], 16 Oct), and the tasks whose
            // whole text he asked to [read] (D-93).
            suspend fun ask(found: Found?, read: List<TaskItem> = emptyList()) = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.Chat, waiting.text) { r: ChatReader.Result -> r.usage to ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: ChatReader.Result -> r.costUsd to r.refused }) { at ->
                // Being asked now: whatever it said before (a failed call) is gone.
                chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(note = null, stuck = false) else it }) }
                sentAt.value = at
                try {
                    ChatReader(chatClient, clock.zone(), profile = profile(at), settings = settings.value).answer(upTo(chat.value.messages), state.tasks, time.events, chatContext(at, state, time, instructions.value.applied), at, found, read)
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
            val called = ask(before?.found, read)
            // Let go while it ran (AC-08): the message says so already, and the answer goes unused.
            if (letGo == waiting.id) {
                letGo = null
                return@withLock
            }
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
                    if (letGo == waiting.id) {
                        letGo = null
                        return@withLock
                    }
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
                if (letGo == waiting.id) {
                    letGo = null
                    return@withLock
                }
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
                from = Speaker.Claude,
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
                    notLooked?.let { Act("Not looked up: $it.", problem = true) },
                    read.takeIf { it.isNotEmpty() }?.let { r -> Act("Read in full: " + r.joinToString("; ") { "“${it.title}”" } + ".") },
                ),
            )
            var kept = false
            chat.update { s ->
                // Only while it's still the one waiting: taken back meanwhile, the answer goes with it.
                if (s.unanswered?.id != waiting.id) {
                    s
                } else {
                    kept = true
                    s.copy(messages = s.messages.map { if (it.id == waiting.id) it.copy(answeredAt = reply.at, note = null) else it } + reply)
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
                // A time the plan couldn't keep (PL-04): where the piece went, said under the answer,
                // which names the time asked for.
                val moved = ChatAnswers.movedPins(instant.flatMap { it.changes }, planning.plan().buckets, clock.zone())
                // One that couldn't be made after all, said under the answer that says it was (16 Oct).
                val refused = chat.value.messages.firstOrNull { it.id == reply.id }?.proposals.orEmpty()
                    .filter { p -> p.instant && p.state != ProposalState.Applied && p.note != null }.map { Act("Not made: ${it.note!!.replaceFirstChar(Char::lowercase)}.", problem = true) }
                val lines = refused + moved.map { Act(it) }
                if (lines.isNotEmpty()) chat.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id) it.copy(acts = it.acts + lines) else it }) }
                // His answer, in the chat whole: the window moved, and it's his notification while the chat isn't on screen.
                outbox.delivered(alertFor = reply.id)
            }
            null
        }
        // Why it waits, said once: written only when it changes, as this runs after every sync.
        if (note != null && chat.value.unanswered?.let { it.id == waiting.id && (it.note != note || it.stuck != stuck) } == true) {
            chat.update { s -> s.copy(messages = s.messages.map { if (it.id == waiting.id && it.answeredAt == null) it.copy(note = note, stuck = stuck) else it }) }
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
            }
        }
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
     * back as it was, with the cards that added it; a setting back to its value before. Not once
     * it's changed since (ticked, finished or deleted another way, set again): why, then.
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
            ChatAction.Type.Reopen -> task?.isOpen == true && act.was != null && refinish(act.was)
            // Not once a card that had added it is made again: that's the task anew.
            ChatAction.Type.Delete -> act.was != null && task == null && !ChatActs.redone(act.cards, chat.value.messages) && run {
                restoreTask(act.was)
                relink(act.cards, act.was.id)
                open(act.was.id) != null
            }
            ChatAction.Type.Setting -> {
                val field = FelixSettings.field(a.setting.orEmpty())
                var back = false
                if (field != null && act.before != null) {
                    owners.changeSettings { s ->
                        if (field.get(s) != act.after) return@changeSettings s
                        field.set(s, act.before)?.also { back = true } ?: s
                    }
                }
                if (back) ports.scheduleReminders()
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
     * Your [text] in reply to Felix's message [id], from his notification or one of his chips (AI-24),
     * routed by what it answers: a check-in's question, its next asked ([checkInReply]); a reading's
     * question, or how work went, answered with one of its three ([answered]); a calendar event's
     * question, one of its three answers kept ([Owners.answerEvent]): each kept under it with no call.
     * Anything else is a chat turn about what his message was, your words under his while he answers,
     * the answer his next message. Returns once it's asked, not answered. False, and a line of his says
     * why, when a message of yours is still waiting.
     */
    suspend fun replyToFelix(text: String, id: String): Boolean {
        val words = text.trim()
        if (words.isEmpty()) return false
        val now = clock.now()
        val his = chat.value.messages.firstOrNull { it.id == id && it.from == Speaker.Claude }
        if (his != null && his.answeredAt == null) {
            when (his.kind) {
                // An answer to a check-in question (LR-02): the next asked, or the check-in kept.
                MessageKind.CheckIn -> return checkInReply(his, words)
                // A reading's question's answer (AI-10), or one of the three to how work went (LR-05): on them, and that's all.
                MessageKind.Question, MessageKind.Completions -> if (answered(his, words)) {
                    keepReply(his, words, now)
                    outbox.delivered()
                    return true
                }
                // One of a calendar question's three answers (Q7), for every event of its name.
                MessageKind.EventQuestion -> his.about.eventKey?.let { key ->
                    if (owners.answerEvent(key, words)) {
                        keepReply(his, words, now)
                        outbox.delivered()
                        return true
                    }
                }
                else -> Unit
            }
        }
        // The reply's own message in the chat, answering his: its answer is his next message.
        if (!ask(words, his?.about ?: About(), replyTo = id)) {
            outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Claude, FelixWords.STILL_WAITING, now, kind = MessageKind.Note, byApp = true), alert = false)
            return false
        }
        // Answered as work of its own, as the receiver that took the reply is let go once this
        // returns, and the app may go with it.
        ports.answerLater()
        return true
    }

    /**
     * The chat's answer to a reply from Felix's notification ([replyToFelix]), as [FelixReplyWorker]
     * runs it: his next message, notified as it comes; or, not answered (Claude off, resting, a failed
     * call), why, shown under your words in his notification.
     */
    suspend fun answerReply() {
        answerChat()
        outbox.notify(clock.now())
    }

    /** Your message still waiting for Claude taken back, unanswered. */
    suspend fun withdrawQuestion() {
        chat.update { s -> s.unanswered?.let { w -> s.copy(messages = s.messages.filterNot { it.id == w.id }) } ?: s }
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
            if (owners.saveOwnTask(id, task)) made = TaskItem.id(Source.Own, id) else note = OwnTasks.problem(task) ?: "It couldn't be added"
        } else if (proposal.changes.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            // Your own tasks it changes, as they are: made on them, the changes are undone by putting them back.
            val own = tasks.value.tasks.filter { it.source == Source.Own }.associateBy { it.id }
            before = proposal.changes.filter { it.type in OwnTasks.MADE_ON_IT }.mapNotNull { it.taskId }.distinct()
                .mapNotNull { tid -> own[tid]?.let { tid to OwnTasks.draftOf(it) } }.toMap()
            val instruction = Instruction(id, asked?.text ?: message.text, asked?.about ?: About(), clock.now(), state = InstructionStatus.Understood, changes = proposal.changes)
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
                val back = OwnTasks.restored(OwnTasks.draftOf(t), was, proposal.changes.filter { it.taskId == id })
                (t to back).takeIf { back != OwnTasks.draftOf(t) }
            }
            // Its days off and half-days out of the school calendar, unless one has gone from it since.
            val calendar = proposal.schoolBefore?.let { was -> school.value.undone(was, proposal.schoolAfter ?: return@let null) }
            val stuck = backs.mapNotNull { (t, back) -> OwnTasks.problem(back)?.let { "“${t.title}” can't go back: ${it.replaceFirstChar(Char::lowercase)}" } } +
                // And its instruction, which taking back mustn't leave tasks waiting for each other in a circle.
                listOfNotNull(made?.takeIf { m -> Instructions.makesCircle(emptyList(), instructions.value.applied.filterNot { it.id == m }) }
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
                                p.copy(before = p.before.mapValues { (id, was) -> proposal.before[id]?.let { OwnTasks.relayered(was, proposal.changes.filter { it.taskId == id }, it) } ?: was })
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
