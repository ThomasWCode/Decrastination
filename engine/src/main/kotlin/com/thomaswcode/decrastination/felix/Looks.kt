package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.FelixDraft
import com.thomaswcode.decrastination.core.FelixRetry
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.ports.Log
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Felix (LR-03): looks for a reason to write ([FelixRules]) at each slot alarm, at the check he set
 * and on the focus service's half-minute tick; writes through the model (AI-22) when there is one,
 * and sends it with its replies (AI-24) through his [outbox], so it's in the chat first and his
 * notification after. Cheap when there's none: the rules are pure, and the model is asked only for a
 * message. Its looks write nothing while Claude is off; your reminders still go, in your words
 * ([sweepReminders]). Built like [Focus]: its stores, the clock and small callbacks
 * in its constructor, so it's tested on temporary stores.
 */
class Looks(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val model: JsonStore<ModelState>,
    private val log: JsonStore<ActivityLog>,
    /** The chat: his messages, and his state between looks ([ChatState.felix]). */
    private val chat: JsonStore<ChatState>,
    /** His notes and reminders: the reminders delivered as they come, and written for just before. */
    private val felix: JsonStore<FelixStore>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val focus: Focus,
    private val planning: Planning,
    private val ports: FelixPorts,
    private val outbox: Outbox,
    /** The calendar's busy time, as last read and judged: he doesn't write while you're in something on it. */
    private val calendarTime: () -> EventJudge.Time,
    /** Who the student is, as every call sends it ([Conversation.profile]). */
    private val profile: (Long) -> Profile.Block,
    /** What he remembered, kept ([Conversation.remember]). */
    private val remember: suspend (List<Given>) -> List<MemoryEdit>,
    /** Your answer to his question about a task, as an instruction on it, read by Claude shown the question ([com.thomaswcode.decrastination.tasks.Instructions.addInstruction]). */
    private val addInstruction: suspend (text: String, about: About, answer: Boolean, question: String?) -> String,
    /** How a completion went, on record ([com.thomaswcode.decrastination.learn.Learning.recordAssessment]). */
    private val assess: suspend (taskId: String, doneAt: Long, answer: String) -> Unit,
    /** A change of his under a message of his made at once, as the chat's are ([Conversation.applyProposal]). */
    private val applyProposal: suspend (messageId: String, proposalId: String) -> Unit,
) {
    /** One look at a time: a check while one writes is covered by it. */
    private val writing = Mutex()

    /** Something done on [taskIds] (LR-03): opened, or studied in AnkiDroid. A tick or a photo is on record already. */
    suspend fun noteActivity(taskIds: Collection<String>, now: Long = clock.now()) {
        if (taskIds.isEmpty()) return
        chat.update { it.copy(felix = it.felix.withActivity(taskIds, now)) }
    }

    /**
     * Writes if there's a reason to now, or else his next message ahead if it's time to ([draft]);
     * then sets his next look. Writing already (a draft), he looks again in a minute.
     */
    suspend fun check(now: Long = clock.now()) {
        if (!writing.tryLock()) {
            ports.armLook(now + BUSY_RETRY_MS)
            return
        }
        try {
            // Reminders gone by first, Claude on or off: they're yours, and never nothing.
            sweepReminders(now)
            val trigger = due(now)
            if (trigger != null) write(trigger, now) else draft(focus.plan(now), now)
            writeReminders(focus.plan(now), now)
            arm(now)
        } finally {
            writing.unlock()
        }
    }

    /** Held while reminders are delivered, so each is sent once: its alarm, his looks and a start can come together. */
    private val sweeping = Mutex()

    /**
     * Your reminders as they come (docs/spec/felix.md §His messages): each one
     * whose moment has come by [now] delivered as his message, kind Reminder, about its task, with its
     * replies (*Done* and two snoozes), through his outbox, so it's in the chat and on his notification,
     * whatever the hour (D-100); one gone by more than [Reminders.LATE_MS] (the phone off) marked late.
     * His words asked for its moment, with nothing said since, where it's on time; else, where he can be
     * asked, his words asked for afresh as work of its own ([remindNow], [FelixPorts.remindLater]), and it
     * goes once they're kept: a call can take longer than an alarm's receiver or a plan's hooks are given,
     * so this makes none. Else yours as you set them, the app's ([FelixWords.reminder]). One whose task
     * closed before it came is let go first, said in a note of his in the app's words. At its alarm, at each
     * of his looks, and at a start; and with each plan, [plan] the one just made, so a slot's reminder goes
     * by where it laid the slot. Whether one waits for his words so; [askLater] false where the caller asks for them
     * itself ([remindNow]).
     */
    suspend fun sweepReminders(now: Long = clock.now(), plan: Plan? = null, askLater: Boolean = true): Boolean = sweeping.withLock {
        if (felix.value.reminders.none { Reminders.pending(it) || it.letGoAt != null }) return@withLock false
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        // What was said before this sweep: what it says itself (a let-go, another reminder) doesn't make a reminder's words out of date.
        val said = lastSaid()
        for (r in Reminders.letGo(felix.value.reminders, tasks, now) { s -> this.tasks.value.status(s).lastSuccessAt }) {
            val task = r.taskId?.let(tasks::get)
            val why = when {
                task == null -> "is no longer listed"
                !task.isOpen -> "is done"
                r.due.kind == DueKind.SlotStart -> if (task.kind == Kind.Event) "is an event, with no slots of work" else "is off the plan"
                else -> "has no due date now"
            }
            // Said first, under an id of its own, then marked: a failure between leaves it to be let go again, its
            // message replacing itself, rather than gone with nothing said.
            outbox.send(ChatMessage("letgo-${r.id}", Speaker.Felix, FelixWords.letGo(r.text, task?.title ?: "its task", why), now, About(taskId = r.taskId, taskTitle = task?.title), kind = MessageKind.Note, byApp = true))
            // Only as it was let go: one moved meanwhile is another moment's, to come then.
            felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && it.undrafted() == r.undrafted() && Reminders.unsent(it)) it.copy(letGoAt = now).undrafted() else it }) }
        }
        // One let go whose task is to do again (a tick undone, a source's read that took it away a while) is back, said
        // under its let-go's id, so the chat holds one note for it as it now is; said first, then marked, as a let-go is.
        for (r in Reminders.back(felix.value.reminders, tasks, now) { s -> this.tasks.value.status(s).lastSuccessAt }) {
            val task = r.taskId?.let(tasks::get)
            val why = if (r.due.kind == DueKind.BeforeDue) "is due again" else "is on the plan again"
            outbox.send(ChatMessage("letgo-${r.id}", Speaker.Felix, FelixWords.back(r.text, task?.title ?: "its task", why), now, About(taskId = r.taskId, taskTitle = task?.title), kind = MessageKind.Note, byApp = true))
            felix.update { s -> s.copy(reminders = s.reminders.map { if (it == r) it.copy(letGoAt = null) else it }) }
        }
        val laid = plan ?: focus.plan(now)
        var asking = false
        for ((r, moment) in Reminders.due(felix.value.reminders, laid, tasks, now)) if (!deliver(r, moment, now, tasks, said)) asking = true
        if (asking && askLater && remindQueued.compareAndSet(false, true)) ports.remindLater()
        asking
    }

    /**
     * [r], due at [moment], sent as his message at [now]: in the chat first, under an id of its moment's,
     * so one sent again after a stop between the chat's write and its own replaces itself; then marked sent.
     * [said]: when something was last said in the chat before this sweep. False, not sent, where his words
     * are to be asked for afresh first ([remindNow]).
     */
    private suspend fun deliver(r: Reminder, moment: Long, now: Long, tasks: Map<String, TaskItem>, said: Long?): Boolean {
        val late = now - moment > Reminders.LATE_MS
        // His words written for this moment, with nothing said in the chat since, go as written. Otherwise (none written,
        // none that came back ahead, written for another moment, or something said since, which they'd talk past: their
        // greeting, D-57) he's asked again, as of this moment, as work of its own, and it goes then ([toAsk]). Late, the
        // moment's gone: your words go, as they do when he can't be asked, or gives none at its moment.
        if (!late && toAsk(r, moment, said) && gateway.available()) return false
        val draft = if (late) null else written(r, moment, said)
        val words = draft?.message ?: FelixWords.reminder(r.text)
        val zone = clock.zone()
        val at = Instant.ofEpochMilli(moment).atZone(zone)
        val time = at.format(if (at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) CLOCK else WHEN)
        val task = r.taskId?.let(tasks::get)
        val message = ChatMessage(
            "reminder-${r.id}-$moment", Speaker.Felix, if (late) "$words ${FelixWords.late(time)}" else words, now, About(taskId = r.taskId, taskTitle = task?.title),
            kind = MessageKind.Reminder, byApp = draft == null, reminderId = r.id, suggestedReplies = FelixWords.REMINDER_REPLIES,
            // His note on how he felt, where his words go (D-119), and the texts he wrote beside them (D-120): read beside your rating of it.
            feeling = draft?.feeling.orEmpty(),
            unsent = draft?.unsent.orEmpty(),
        )
        outbox.send(message)
        // Marked sent only as it was sent: moved, snoozed or cancelled while it went, it's another moment's, to come then.
        felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && it.undrafted() == r.undrafted()) it.copy(sentAt = now).undrafted() else it }) }
        draft?.memories?.takeIf { it.isNotEmpty() }?.let { remember(it) }
        Log.i(Log.TAG, "A reminder came${if (late) ", late" else ""}, in ${if (draft != null) "his words" else "yours"}")
        return true
    }

    /** His words written for [r]'s [moment], with nothing said in the chat since ([said]): to go as written. */
    private fun written(r: Reminder, moment: Long, said: Long?): FelixAnswer? {
        val fresh = (said ?: Long.MIN_VALUE) <= (r.draftedAt ?: Long.MIN_VALUE)
        return r.draft?.let(FelixAnswers::bounded)?.takeIf { fresh && it.message.isNotBlank() && Reminders.drafted(r, moment) }
    }

    /**
     * Whether his words for [r]'s [moment] are to be asked for afresh, as of it: none [written], and none asked for at
     * it already, with nothing said since, that came back with none (refused, unreadable, or he couldn't be reached),
     * when yours go. Asked ahead and none came back, they're asked once more at its moment.
     */
    private fun toAsk(r: Reminder, moment: Long, said: Long?): Boolean {
        val askedAt = r.draftedAt ?: Long.MIN_VALUE
        val noneAtIt = (said ?: Long.MIN_VALUE) <= askedAt && Reminders.asked(r, moment) && askedAt >= moment
        return written(r, moment, said) == null && !noneAtIt
    }

    /**
     * Held while reminders due are written for at their moment ([remindNow]), so each is asked for once: apart from
     * [drafting], which words written ahead hold across their batch, so one due doesn't wait on calls for others.
     */
    private val reminding = Mutex()

    /**
     * The reminders whose words are being written ahead now ([writeReminders]), each with its call's end: not asked for
     * again at their moment meanwhile, but waited for ([remindNow]).
     */
    private val askingAhead = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Whether a run of [remindNow] is queued and not yet begun ([FelixPorts.remindLater]): a sweep doesn't queue another. */
    private val remindQueued = AtomicBoolean(false)

    /**
     * Your reminders whose moment has come and whose words are to be asked for afresh ([sweepReminders] leaves them
     * to this, as work of its own: [ReminderWorker]): his asked for each as of its moment, outside the sweep's lock,
     * so a sweep from an alarm or a plan never waits on a call; kept on the reminder as asked for that moment, words
     * or none, unless it changed meanwhile (a cancel, a move, a snooze); then the sweep, which sends them as they now
     * are. One whose call is held or fails, or comes back with none, goes in your words.
     */
    suspend fun remindNow(now: Long = clock.now()) {
        // Begun: a sweep from now on that finds one still to ask for queues the next run.
        remindQueued.set(false)
        reminding.withLock {
            val tasks = this.tasks.value.tasks.associateBy { it.id }
            val plan = focus.plan(now)
            val said = lastSaid()
            val due = Reminders.due(felix.value.reminders, plan, tasks, now).filter { (r, moment) ->
                now - moment <= Reminders.LATE_MS && toAsk(r, moment, said)
            }
            if (due.isEmpty()) return@withLock
            val caller = gateway.client(ModelEffort.Job.Felix)?.takeIf { gateway.available() }?.let { FelixCaller(it) }
            for ((r, moment) in due) {
                // Its words being written ahead still, its moment come during that call: that call's waited for, not made
                // again, so it isn't paid for twice; what it kept is looked at as it then is.
                askingAhead[r.id]?.await()
                // As it now is: one sent, let go or late since (by the sweep after an earlier one's words), or cancelled,
                // moved or snoozed meanwhile, isn't paid for; nor one whose words its call ahead kept, which go with the sweep.
                val current = felix.value.reminders.firstOrNull { it.id == r.id }
                if (current == null || !Reminders.pending(current) || current.undrafted() != r.undrafted() || clock.now() - moment > Reminders.LATE_MS) continue
                if (!toAsk(current, moment, said)) {
                    sweepReminders(now = clock.now(), askLater = false)
                    continue
                }
                val askedAt = clock.now()
                val answer = caller?.let { (askReminder(it, r, moment, plan, tasks) as? Called.Answered)?.value?.answer }
                felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && it.undrafted() == r.undrafted()) it.copy(draft = answer, draftedAt = askedAt, draftFor = moment) else it }) }
                if (answer == null) Log.w(Log.TAG, "Felix's words for a reminder at its moment came back with none: yours go")
                // Sent as soon as its words are kept, as it now is, not after the others' calls, which could make it late;
                // those still to ask for are this run's.
                sweepReminders(now = clock.now(), askLater = false)
            }
        }
        // Any come due meanwhile, asked for in a run of their own.
        sweepReminders(now = clock.now())
    }

    /**
     * A message for [reason] now, whatever the rules say (a PC's `felix-say`, to try his prompt):
     * about the slot under way or next, or the last on record. Null while Claude is off.
     */
    suspend fun say(reason: FelixPrompts.Reason, now: Long = clock.now()): ChatMessage? = writing.withLock {
        val plan = planning.plan(now = now)
        val record = today(now).maxByOrNull { it.at }
        val slot = (plan.slotAt(now) ?: plan.nextSlot(now)).takeIf { reason == FelixPrompts.Reason.SlotStart || record == null }
        write(FelixRules.Trigger(reason, slot = slot, record = record.takeIf { slot == null }), now)
    }

    /**
     * The morning's message (LR-04): his sentence or two on the day's shape, whole, as the briefing,
     * with no list of the day beside it (D-65), so it has [FelixAnswers.MAX_MORNING]
     * characters. False, the day's list going instead in the app's words, with no slot today, Claude
     * off or resting, no answer within [MORNING_MS], or an empty one. It sets no check: the day's slots
     * do that.
     */
    suspend fun morning(now: Long = clock.now()): Boolean = writing.withLock {
        val plan = planning.plan(now = now)
        if (plan.todaySlots.none { it.kind == SlotKind.Work }) return@withLock false
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return@withLock false
        val settings = this.settings.value
        val profile = this.profile(now)
        // Two steps, his or the reading's, he may rebalance to fit a free time at school today (D-110), at their minutes as
        // planned, none of a task he's had one taken back on.
        val rebalance = Rebalance.candidates(plan, tasks.value.tasks, Rebalance.declined(chat.value.messages), now, planning.calibration())
        val user = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan, null, null, today(now), chat.value.messages, null, settings, now, clock.zone(), reminders = remindersToday(plan, now), rebalance = rebalance, tasks = planning.asPlanned(), timer = focus.timer, story = story(now), diary = diary(now), since = dayStart(now))
        // Asked in the app's scope, not the briefing's: past [MORNING_MS] the briefing goes without
        // it, and the call, paid for, comes back and is counted all the same.
        val call = scope.async {
            gateway.call(interactive = false, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, "Morning") { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings), profile.felix, user, maxMessage = FelixAnswers.MAX_MORNING) }
        }
        val called = withTimeoutOrNull(MORNING_MS) { call.await() }
        val result = (called as? Called.Answered)?.value
        val answer = result?.answer?.takeIf { it.message.isNotBlank() } ?: return@withLock false
        // Each rebalance he gave for steps he was asked about, as his Steps change on its task, under his message (D-110);
        // only where they're one still, the call back: in it a task can be finished or begun, or its free time gone.
        val back = clock.now()
        // The settings as they are now, for the plans tried and the rests checked alike: they may have changed in the call.
        val settingsBack = this.settings.value
        val current = Rebalance.candidates(planning.plan(now = back, settings = settingsBack), tasks.value.tasks, Rebalance.declined(chat.value.messages), back, planning.calibration())
        val known = tasks.value.tasks.associateBy { it.id }
        // Each tried on the plan with those made before it, made only where its first step is laid in the free
        // time he was shown, its rests kept: one he leaves out can leave an earlier one free for another's.
        val made = mutableListOf<Instruction>()
        val proposals = result.rebalances.sortedBy { given -> rebalance.firstOrNull { it.taskId == given.taskId && it.index == given.stepIndex }?.gap?.start ?: Long.MAX_VALUE }.mapNotNull { given ->
            val asked = rebalance.firstOrNull { it.taskId == given.taskId && it.index == given.stepIndex } ?: return@mapNotNull null
            // The same free time too, by its end (one under way starts later as the clock moves on): his
            // message names the one he was shown, and his minutes are checked against what's left of it.
            val candidate = current.firstOrNull { it.taskId == asked.taskId && it.index == asked.index && it.first == asked.first && it.second == asked.second && it.gap.end == asked.gap.end } ?: return@mapNotNull null
            if (made.any { it.changes.any { c -> c.taskId == candidate.taskId } }) return@mapNotNull null
            val task = known[candidate.taskId] ?: return@mapNotNull null
            val steps = Rebalance.steps(task, candidate, given) ?: return@mapNotNull null
            val tried = Rebalance.instruction(task, candidate, steps, back)
            if (!Rebalance.laidAsOffered(planning.plan(now = back, trying = made + tried, settings = settingsBack), candidate, settingsBack)) return@mapNotNull null
            made += tried
            Proposal(UUID.randomUUID().toString(), changes = tried.changes, lines = listOf(Rebalance.line(candidate, given)), instant = true)
        }
        // One he gave that isn't made, his message would still say it was: the day's list in the app's words
        // instead ([Briefing]), what he remembered kept all the same.
        if (proposals.size < result.rebalances.size) {
            answer.memories.takeIf { it.isNotEmpty() }?.let { remember(it) }
            return@withLock false
        }
        // His message whole, with no list of the day beside it (D-65): the room is his.
        val message = ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, answer.message, now, kind = MessageKind.Morning, suggestedReplies = answer.replies, proposals = proposals, feeling = answer.feeling, unsent = answer.unsent)
        outbox.send(message)
        // Made at once, each with its Undo, as his changes in the chat are.
        proposals.forEach { applyProposal(message.id, it.id) }
        answer.memories.takeIf { it.isNotEmpty() }?.let { remember(it) }
        true
    }

    private fun today(now: Long): List<SlotRecord> {
        val date = focus.today(now).toString()
        return log.value.slots.filter { it.date == date }
    }

    /** When the day of [now] began, as the slots on record have it: today's story is from then (D-120). */
    private fun dayStart(now: Long): Long = focus.today(now).atStartOfDay(clock.zone()).toInstant().toEpochMilli()

    /** Today's story as of [now] (D-120): today's slots on record and what's been said, his notes with his. */
    private fun story(now: Long): List<String> = Story.today(today(now), chat.value.messages, dayStart(now), clock.zone())

    /** His diary as of [now] (D-120): the week's days, the week before, and the week's check-in. */
    private fun diary(now: Long): List<String> = log.value.let { Story.diary(it.slots, it.checkIns, chat.value.messages, focus.today(now), clock.zone()) }

    private fun due(now: Long): FelixRules.Trigger? {
        // Claude off: he has no voice, nothing's asked of the model, and nothing's put in the chat, so
        // it's all there for later. His notifications off silence only them: the chat is his voice.
        if (gateway.claudeKey() == null) return null
        val settings = this.settings.value
        val zone = clock.zone()
        val dayStart = focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        // The completions waiting to be asked about whose work no slot kept that day saw (LR-05):
        // today's, and any kept from a day he couldn't ask (Claude off).
        val conversation = chat.value
        val toAsk = FelixRules.toAsk(conversation.felix.assessLater, log.value.slots, now, zone)
        return FelixRules.due(
            // The blocker's, kept for the minute: this runs every half minute.
            plan = focus.plan(now),
            state = conversation.felix,
            today = today(now),
            sent = FelixRules.sent(conversation.messages, dayStart),
            settings = settings,
            now = now,
            quiet = BlockPolicy.isQuiet(now, zone, settings),
            busyNow = calendarTime().busy.any { now >= it.start && now < it.end },
            // Written work that needs its photo to earn (BL-08): what he asks for at its slot's end.
            needsPhoto = { r -> r.kind == Kind.Homework && tasks[r.taskId]?.source != Source.Anki && r.plannedMin >= settings.photoFromMin },
            toAsk = toAsk,
            zone = zone,
            // A timer on a piece holds his slot-start and slot-over messages about its task (D-112).
            timer = focus.timer,
            // A slot kept of a task he was let down about today is always answered (Q102).
            letDown = { taskId, keptAt -> Mood.letDown(taskId, conversation.messages, now, zone, keptAt) },
        )
    }

    /**
     * [trigger]'s message: asked of the model with what it's about, through a rest as it's due now,
     * and sent through his outbox (an empty one, his choice not to write, as is an answer refused or
     * unread, paid for, is kept in the chat shown nowhere, so it isn't asked for again); his next check
     * set by what he said (20 minutes if he didn't), and what he remembered kept. A call held or failed
     * writes nothing: it's tried again past [FelixRules.RETRY_MS] while it's still to say.
     */
    private suspend fun write(trigger: FelixRules.Trigger, now: Long): ChatMessage? {
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return null
        val settings = this.settings.value
        val zone = clock.zone()
        // Written ahead and still fitting (AC-10): sent as it is, at once.
        val state = chat.value.felix
        // Written from the plan as it is now, its slot where it laid it: one moved since says what isn't so.
        val drafted = state.draft?.takeIf { FelixRules.fits(it, trigger, now, lastSaid(), laidOf(it), FelixRules.laid(focus.plan(now), trigger, today(now))) }
        if (state.draft != null) chat.update { it.copy(felix = it.felix.copy(draft = null)) }
        val task = trigger.taskId?.let { id -> tasks.value.tasks.firstOrNull { it.id == id } }
        // How he feels writing it (D-119), as he's told it: kept on the message as told (Q103), not worked out again once
        // he's answered, as a piece kept during the call changes the records it rests on.
        val mood = feeling(trigger, now, task)
        val called = if (drafted != null) null else ask(caller, trigger, now, interactive = true, mood = mood)
        // A timer started on its task while he was asked (D-112): held now, so not sent; a slot's miss is said once
        // the hold lifts, asked afresh then.
        if (called != null && FelixRules.held(trigger, focus.timer, clock.now())) {
            Log.i(Log.TAG, "Felix, ${trigger.reason} about ${trigger.key}: held by a timer started while he was asked")
            return null
        }
        val answer = drafted?.answer ?: (called as? Called.Answered)?.value?.answer
        val text = answer?.message.orEmpty()
        val next = now + (answer?.nextCheckMin ?: settings.felixDefaultCheckMin).coerceAtLeast(FelixAnswers.MIN_CHECK_MIN) * 60_000L
        // Its work begun while he was asked (a piece kept from Day or the widget, a step ticked, the task opened): a nudge
        // to start it isn't sent after (D-119), its look settled as one sent would be, so it isn't asked again.
        if (called != null && !FelixRules.stillDue(trigger, chat.value.felix, today(clock.now()))) {
            Log.i(Log.TAG, "Felix, ${trigger.reason} about ${trigger.key}: its work began while he was asked, not sent")
            chat.update { s -> s.copy(felix = s.felix.copy(nextCheckAt = next, retry = s.felix.retry?.takeUnless { it.reason == trigger.reason.name && it.key == trigger.key })) }
            return null
        }
        // Asking how the completions went, the replies are the three answers (LR-05).
        val replies = when (trigger.reason) {
            FelixPrompts.Reason.Completions -> FelixWords.HOW_IT_WENT_REPLIES
            // A question's answer is in your words (AI-10).
            FelixPrompts.Reason.Question -> emptyList()
            else -> answer?.replies.orEmpty()
        }
        val message = ChatMessage(
            UUID.randomUUID().toString(), Speaker.Felix, text, now, About(taskId = trigger.taskId, taskTitle = task?.title),
            // Why he wrote it, its kind (his answer, the chat's).
            kind = MessageKind.entries.firstOrNull { it.name == trigger.reason.name } ?: MessageKind.Chat, pieceKey = trigger.key, nextCheckAt = next, asks = trigger.asks, suggestedReplies = replies,
            // How he felt as he wrote it (D-119), kept (Q103): what a kept slot after a let-down is known by. Written ahead,
            // the level he was told then; a draft from before that was kept, as it is now.
            mood = drafted?.mood ?: Mood.kept(mood, text),
            // His note on how he felt, kept with it (D-119), and the texts he wrote beside it (D-120): read beside your rating of it.
            feeling = answer?.feeling.orEmpty(),
            unsent = answer?.unsent.orEmpty(),
        )
        // Held or failed, or a question or completions with no answer it could read: nothing written,
        // tried again from his next look past the wait.
        if ((drafted == null && called !is Called.Answered) || (answer == null && (trigger.question != null || trigger.asks.isNotEmpty()))) {
            val retry = FelixRetry(trigger.reason.name, trigger.key, now + FelixRules.RETRY_MS)
            chat.update { it.copy(felix = it.felix.copy(nextCheckAt = next, retry = retry)) }
            return null
        }
        val dayStart = focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
        // In the chat with what it settles of his state: the question put, the completions asked (with
        // any older than today's), off their lists; his next check; the retry it was, done.
        outbox.send(message) { s ->
            s.copy(
                questions = trigger.question?.let { q -> s.questions - q } ?: s.questions,
                assessLater = if (trigger.asks.isEmpty()) s.assessLater else s.assessLater.filter { a -> a !in trigger.asks && (a.doneAt ?: 0L) >= dayStart },
                nextCheckAt = next,
                lastSentAt = if (text.isNotBlank()) now else s.lastSentAt,
                retry = s.retry?.takeUnless { it.reason == trigger.reason.name && it.key == trigger.key },
            )
        }
        answer?.memories?.takeIf { it.isNotEmpty() }?.let { remember(it) }
        Log.i(Log.TAG, "Felix, ${trigger.reason} about ${trigger.key}: ${if (text.isBlank()) "nothing" else "a message"}, next look in ${(next - now) / 60_000L} min")
        return message
    }

    /** When something was last said in the chat, by him or you: a message written ahead before it would talk past it (D-57). */
    private fun lastSaid(): Long? = chat.value.messages.filter { it.text.isNotBlank() }.maxOfOrNull { it.at }

    private companion object {
        /** A late reminder's time, as it says it: the day's, or another day's. */
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

        /** How long the morning's briefing waits for his sentence. */
        const val MORNING_MS = 20_000L

        /** How soon he looks again when a look finds him writing. */
        const val BUSY_RETRY_MS = 60_000L

        /** A task's answers kept: the latest. */
        const val MAX_ANSWERS = 5
    }

    /**
     * The model's [question] about [taskId]'s newest reading (AI-10), to be put when he can, in place
     * of one waiting from an older reading; none (null) takes that away, the reading having settled
     * it. Not again within a day of the last he put about it.
     */
    suspend fun queueQuestion(taskId: String, question: String?, now: Long = clock.now()) {
        val lately = chat.value.messages.any { it.kind == MessageKind.Question && it.about.taskId == taskId && it.at > now - 24 * 3_600_000L }
        chat.update { s -> s.copy(felix = s.felix.withQuestion(taskId, question.takeUnless { lately }, now)) }
    }

    /**
     * [text] in reply to his [message], if it's a Completions one and [text] one of its three
     * answers (LR-05): put on each completion it asked about;
     * or, a Question's (AI-10), an instruction on its task, read shown his question, and kept
     * with it once it's read as its answer ([answerRead]). False for anything else, which goes on in the chat.
     */
    suspend fun answered(message: ChatMessage, text: String): Boolean {
        val about = message.about.taskId
        if (message.kind == MessageKind.Question && about != null) {
            val task = this.tasks.value.tasks.firstOrNull { it.id == about } ?: return false
            // Its read shown the question you replied to, not one found by its task: your reply isn't kept yet as it begins.
            addInstruction(text, About(taskId = task.id, taskTitle = task.title), true, message.text.takeIf { it.isNotBlank() })
            return true
        }
        if (message.kind != MessageKind.Completions || message.asks.isEmpty()) return false
        val answer = Assessment.ANSWERS.firstOrNull { it.first.equals(FelixWords.chip(text), ignoreCase = true) }?.second ?: return false
        message.asks.forEach { assess(it.taskId, it.doneAt ?: 0L, answer) }
        return true
    }

    /**
     * Your reply to his question about [instruction]'s task, read (AI-10): one that [answers] it is
     * kept with the task, which is read again with it now, as it's what settles the reading. One that
     * doesn't (a question of yours, or about something else) isn't kept, and his question waits for its
     * answer again (D-19); where it [changed] nothing, it's a turn of the chat, answered there.
     */
    suspend fun answerRead(instruction: Instruction, answers: Boolean, changed: Boolean) {
        val taskId = instruction.about.taskId ?: return
        val reply = replyOf(instruction, chat.value.messages)
        if (answers) {
            // Your words as the chat kept them, whole (the instruction's are cut shorter), and kept once: the same
            // words again are the newest, not a second answer.
            val text = (reply?.text ?: instruction.text).trim()
            tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(answers = (it.answers.filterNot { a -> a == text } + text).takeLast(MAX_ANSWERS)) else it }) }
            // Read again with it now, not at the next sync: it's what settles the reading.
            ports.readAgain()
            return
        }
        // Your reply gone from the live chat (read a fortnight on, Claude held that long): too late for the chat to
        // take on, it's let go, neither kept nor answered.
        if (reply == null) return
        // No longer his question's reply, so his question waits for its answer again (D-19, D-99), in the archive too
        // where it was answered from there. A change of it made is what it was for, shown in Your changes: it stays as
        // said and done, its question's kind kept, so it's no turn of the chat, nor sent to him as one
        // ([com.thomaswcode.decrastination.core.ChatHistory.doneWith]); one that made none is a turn of the chat now, answered there.
        chat.update { s ->
            s.copy(
                messages = s.messages.map {
                    when (it.id) {
                        reply.id -> if (changed) it.copy(replyTo = null) else it.copy(kind = MessageKind.Chat, answeredAt = null, replyTo = null)
                        reply.replyTo -> it.copy(answeredAt = null)
                        else -> it
                    }
                },
            )
        }
        reply.replyTo?.let { outbox.waitingInArchive(it) }
        // A change made, nothing's to answer: his notification made again, his question back in it where it's unread.
        if (changed) outbox.notify(clock.now()) else ports.answerLater()
    }

    /**
     * His question that [instruction], your reply to it, answers, in his words as he put it, from the chat or the
     * archive: for its reading (AI-10). Your reply not kept yet (its reading begun as it's kept), his newest
     * question about its task by then, the one it most often answers.
     */
    fun questionFor(instruction: Instruction): String? {
        val messages = chat.value.messages
        val asked = replyOf(instruction, messages)?.replyTo?.let(outbox::find)
            ?: messages.lastOrNull { it.from == Speaker.Felix && it.kind == MessageKind.Question && it.about.taskId == instruction.about.taskId && it.at <= instruction.at }
        return asked?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Your reply that [instruction] reads, as it was kept under his question as its answer: the last of yours about
     * its task by then in its words (the instruction's are them, cut as it cuts them), so an older reply isn't taken for it.
     */
    private fun replyOf(instruction: Instruction, messages: List<ChatMessage>): ChatMessage? {
        val taskId = instruction.about.taskId ?: return null
        return messages.lastOrNull {
            it.from == Speaker.You && it.kind == MessageKind.Question && it.about.taskId == taskId && it.at <= instruction.at &&
                it.text.trim().take(InstructionPrompts.MAX_TEXT) == instruction.text
        }
    }

    /**
     * His answer for [trigger] as it stands at [at] (now, or when a draft's for), as the call went;
     * [interactive], due now, it goes through a rest (AC-04: as the chat does, but for a key that
     * failed or the cap). About [task] (its trigger's, or a reminder's), with your [reminder]'s words
     * and the task's [notes] where there are any.
     */
    private suspend fun ask(
        caller: FelixCaller,
        trigger: FelixRules.Trigger,
        at: Long,
        interactive: Boolean,
        task: TaskItem? = trigger.taskId?.let { id -> this.tasks.value.tasks.firstOrNull { it.id == id } },
        reminder: String? = slotReminder(trigger),
        notes: List<String> = task?.let(::notesOf).orEmpty(),
        mood: Mood.Feeling = feeling(trigger, at, task),
    ): Called<FelixCaller.Result> {
        val settings = this.settings.value
        val profile = this.profile(at)
        val free = (focus.creditLeftMs(at) / 60_000L).toInt().takeIf { trigger.reason == FelixPrompts.Reason.DayDone || trigger.reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(trigger.reason, planning.plan(), trigger.slot, task, today(at), chat.value.messages, trigger.question?.question, settings, at, clock.zone(), free, trigger.record, trigger.asks.map { it.title }, firstAction = trigger.slot?.chunk?.key?.let { model.value.microPlans[it] }?.first?.takeIf { it.isNotEmpty() }, reminder = reminder, notes = notes, tasks = planning.asPlanned(), timer = focus.timer, feeling = mood, story = story(at), diary = diary(at), since = dayStart(at))
        val called = gateway.call(interactive = interactive, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, trigger.reason.name) { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings), profile.felix, user) }
        if (called is Called.Failed) Log.w(Log.TAG, "Felix's message failed", called.error)
        return called
    }

    /**
     * How he feels writing [trigger]'s message at [at] (D-119), about [task]: by today's slots on record, the one it's about,
     * and his nudges already sent on its piece, from the chat as it is.
     */
    private fun feeling(trigger: FelixRules.Trigger, at: Long, task: TaskItem?): Mood.Feeling {
        val messages = chat.value.messages
        return Mood.of(trigger.reason, today(at), trigger.record, Mood.nudges(messages, trigger.key, at, clock.zone()), task, messages, at, clock.zone())
    }

    /** [task]'s notes, its own and the standing ones it matches, as he's told them. */
    private fun notesOf(task: TaskItem): List<String> = Notes.forTask(task, felix.value.notes, settings.value.classMap).map { it.text }

    /**
     * Your reminder at [trigger]'s slot's start, for his SlotStart: sent at it, or still to come with it
     * its next, so he doesn't say it twice.
     */
    private fun slotReminder(trigger: FelixRules.Trigger): String? {
        val slot = trigger.slot?.takeIf { trigger.reason == FelixPrompts.Reason.SlotStart } ?: return null
        val taskId = slot.chunk?.taskId ?: return null
        return felix.value.reminders.firstOrNull { r ->
            r.taskId == taskId && r.due.kind == DueKind.SlotStart && r.cancelledAt == null &&
                (r.sentAt?.let { it >= slot.start && it < slot.end } == true || (Reminders.unsent(r) && (r.due.at ?: r.at) <= slot.start))
        }?.text
    }

    /** Your reminders that come today, as his morning's told them: "16:00 dentist". */
    private fun remindersToday(plan: Plan, now: Long): List<String> {
        val zone = clock.zone()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        return felix.value.reminders.filter(Reminders::pending).mapNotNull { r -> Reminders.moment(r, plan, tasks, now)?.let { r to it } }
            .filter { (_, at) -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == today }.sortedBy { it.second }
            .map { (r, at) -> "${Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)} ${r.text}" }
    }

    /**
     * His next message written ahead (AC-10), after a plan: a slot's "starting?" due within
     * [FelixRules.AHEAD_MS], written as of that moment, so it's sent the moment it's due. Not again
     * while one fits; not with Claude off; not while he's writing. His looks do it too ([check]), one
     * of them when it's time to with a grace longer than that ([FelixRules.next]).
     */
    suspend fun draftAhead(plan: Plan, now: Long = clock.now()) {
        if (!writing.tryLock()) return
        try {
            draft(plan, now)
            writeReminders(plan, now)
        } finally {
            writing.unlock()
        }
    }

    /**
     * His words for the reminders due within [FelixRules.AHEAD_MS]: at once after one's set,
     * moved or brought back, so one due inside the window has them without waiting for a plan or a look.
     */
    suspend fun draftReminders(now: Long = clock.now()) = writeReminders(planning.plan(now = now), now)

    /** Held while his words for reminders are written, so each is asked for once. */
    private val drafting = Mutex()

    /**
     * His words for each reminder of yours due within [FelixRules.AHEAD_MS] of [now] by [plan] and not
     * asked for its moment yet ([Reminders.asked]): one call each (D-100), written as of its
     * moment, kept on it unless it changed meanwhile; one that came back with none isn't asked again for
     * that moment. None with Claude off, resting or over the cap: the sweep sends your words then.
     */
    private suspend fun writeReminders(plan: Plan, now: Long): Unit = drafting.withLock {
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        val due = felix.value.reminders.filter(Reminders::pending).mapNotNull { r ->
            Reminders.moment(r, plan, tasks, now)?.takeIf { it > now && it - now <= FelixRules.AHEAD_MS && !Reminders.asked(r, it) }?.let { r to it }
        }
        if (due.isEmpty() || !gateway.available()) return@withLock
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return@withLock
        for ((r, moment) in due) {
            // While its call's out, it's this one's ([askingAhead]): its moment coming meanwhile, it isn't asked for twice.
            // Marked before the clock's looked at, so the work at its moment either waits for this call or is asked for alone.
            val call = CompletableDeferred<Unit>()
            askingAhead[r.id] = call
            try {
                // Its moment come by now (a batch's later one): it's asked for at its moment ([remindNow]), not ahead.
                if (clock.now() >= moment) continue
                val called = askReminder(caller, r, moment, plan, tasks) as? Called.Answered ?: continue
                // Refused, or none that could be read, it's paid for all the same: its moment's kept as asked for, so the next look doesn't ask again.
                val answer = called.value.answer
                // Kept only on the reminder as it was asked for: moved, snoozed, sent or cancelled meanwhile, it's another moment's.
                felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && it.undrafted() == r.undrafted()) it.copy(draft = answer, draftedAt = now, draftFor = moment) else it }) }
                if (answer == null) Log.w(Log.TAG, "Felix's words for a reminder came back with none: yours go")
                else Log.i(Log.TAG, "Felix wrote ahead for a reminder, due at ${Instant.ofEpochMilli(moment).atZone(clock.zone()).toLocalTime()}")
            } finally {
                askingAhead.remove(r.id)
                call.complete(Unit)
            }
        }
    }

    /** His call for [r] as of its [moment] (D-100): your words, the slot then under way or next by [plan], and its task's text and notes. */
    private suspend fun askReminder(caller: FelixCaller, r: Reminder, moment: Long, plan: Plan, tasks: Map<String, TaskItem>): Called<FelixCaller.Result> {
        val task = r.taskId?.let(tasks::get)
        val notes = task?.let { Notes.forTask(it, felix.value.notes, settings.value.classMap).map { n -> n.text } }.orEmpty()
        val trigger = FelixRules.Trigger(FelixPrompts.Reason.Reminder, slot = plan.slotAt(moment) ?: plan.nextSlot(moment))
        return ask(caller, trigger, moment, interactive = false, task = task, reminder = r.text, notes = notes)
    }

    /** The trigger and moment his words were last asked for ahead ([draft]): not asked again ahead, whatever came back. */
    private var draftAsked: Pair<String?, Long>? = null

    /**
     * What the draft written when (its [FelixDraft.at]) was written from of the plan ([FelixRules.laid]): one from before a
     * start isn't known, so it's written again (ahead where there's still time, else at its moment) rather than sent as it is.
     */
    private var draftLaid: Pair<Long, String>? = null

    /** What [draft] was written from, where it's known ([draftLaid]). */
    private fun laidOf(draft: FelixDraft?): String? = draftLaid?.takeIf { draft != null && it.first == draft.at }?.second

    /**
     * [draftAhead]'s writing, under [writing]: the cheap checks first, as his looks come every half minute. Asked
     * once a trigger's moment, whatever came back: one with none (refused, unreadable) was paid for, and one made
     * stale since by something said is written again at its time, not at each look, so a chat before it costs no
     * call an exchange.
     */
    private suspend fun draft(plan: Plan, now: Long) {
        // Not one the timer's hold would keep unsent: no call's paid for it.
        val (trigger, at) = FelixRules.upcoming(plan, this.settings.value, now, focus.timer) ?: return
        if (at - now > FelixRules.AHEAD_MS) return
        val draft = chat.value.felix.draft
        if (FelixRules.fits(draft, trigger, now, lastSaid(), laidOf(draft), FelixRules.laid(plan, trigger, today(at)))) return
        if (draftAsked == trigger.key to at) return
        if (!gateway.available()) return
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return
        val mood = feeling(trigger, at, trigger.taskId?.let { id -> tasks.value.tasks.firstOrNull { it.id == id } })
        val called = ask(caller, trigger, at, interactive = false, mood = mood) as? Called.Answered ?: return
        // Once a moment: made stale since, it's written again at its moment ([write]), not again ahead.
        draftAsked = trigger.key to at
        val answer = called.value.answer ?: return
        chat.update { it.copy(felix = it.felix.copy(draft = FelixDraft(trigger.reason.name, trigger.key, answer, now, Mood.kept(mood, answer.message)))) }
        draftLaid = now to FelixRules.laid(plan, trigger, today(at))
        Log.i(Log.TAG, "Felix wrote ahead for ${trigger.key}, due at ${Instant.ofEpochMilli(at).atZone(clock.zone()).toLocalTime()}")
    }

    /** His next look, by alarm: the phone may be asleep through the service's ticks. */
    private fun arm(now: Long) = ports.armLook(FelixRules.next(focus.plan(now), chat.value.felix, this.settings.value, now, focus.timer))
}
