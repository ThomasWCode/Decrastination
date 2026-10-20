package com.thomaswcode.decrastination.felix

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FelixDraft
import com.thomaswcode.decrastination.core.FelixRetry
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.Plan
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
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Felix (LR-03): looks for a reason to write ([FelixRules]) at each slot alarm, at the check he set
 * and on the focus service's half-minute tick; writes through the model (AI-22) when there is one,
 * and sends it with its replies (AI-24) through his [outbox], so it's in the chat first and his
 * notification after. Cheap when there's none: the rules are pure, and the model is asked only for a
 * message. Silent while Claude is off. Built like [Focus]: its stores, the clock and small callbacks
 * in its constructor, so it's tested on temporary stores.
 */
class Looks(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val model: JsonStore<ModelState>,
    private val log: JsonStore<ActivityLog>,
    /** The chat: his messages, and his state between looks ([ChatState.felix]). */
    private val chat: JsonStore<ChatState>,
    /** His notes and reminders (Stage 8a): the reminders delivered as they come, and written for just before. */
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
    /** Your answer to his question about a task, as an instruction on it, read by Claude ([com.thomaswcode.decrastination.tasks.Instructions.addInstruction]). */
    private val addInstruction: suspend (text: String, about: About, answer: Boolean) -> String,
    /** How a completion went, on record ([com.thomaswcode.decrastination.learn.Learning.recordAssessment]). */
    private val assess: suspend (taskId: String, doneAt: Long, answer: String) -> Unit,
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
            // Reminders gone by first (8a), Claude on or off: they're yours, and never nothing.
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
     * Your reminders as they come (Stage 8a, docs/plan_v4/07-notes-and-reminders.md §Delivery): each one
     * whose moment has come by [now] delivered as his message, kind Reminder, about its task, with its
     * replies (*Done* and two snoozes), through his outbox, so it's in the chat and on his notification,
     * whatever the hour (Q10: A); one gone by more than [Reminders.LATE_MS] (the phone off) marked late.
     * His words written for it ahead where they're there and it's on time; else yours as you set them,
     * the app's ([FelixWords.reminder]). One whose task closed before it came is let go first, said in
     * a note of his in the app's words. At its alarm, at each of his looks, and at a start.
     */
    suspend fun sweepReminders(now: Long = clock.now()): Unit = sweeping.withLock {
        if (felix.value.reminders.none(Reminders::pending)) return@withLock
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        for (r in Reminders.letGo(felix.value.reminders, tasks, now)) {
            var let = false
            felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && Reminders.unsent(it)) it.copy(cancelledAt = now).undrafted().also { let = true } else it }) }
            if (!let) continue
            val task = r.taskId?.let(tasks::get)
            val why = when {
                task == null -> "is no longer listed"
                !task.isOpen -> "is done"
                else -> "has no due date now"
            }
            outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, FelixWords.letGo(r.text, task?.title ?: "its task", why), now, About(taskId = r.taskId, taskTitle = task?.title), kind = MessageKind.Note, byApp = true))
        }
        for ((r, moment) in Reminders.due(felix.value.reminders, focus.plan(now), tasks, now)) deliver(r, moment, now, tasks)
    }

    /**
     * [r], due at [moment], sent as his message at [now]: in the chat first, under an id of its moment's,
     * so one sent again after a stop between the chat's write and its own replaces itself; then marked sent.
     */
    private suspend fun deliver(r: Reminder, moment: Long, now: Long, tasks: Map<String, TaskItem>) {
        val late = now - moment > Reminders.LATE_MS
        // Written for this moment, it's sent on time; late, the moment it was written for has gone.
        val draft = r.draft?.takeIf { !late && it.message.isNotBlank() && Reminders.drafted(r, moment) }
        val words = draft?.message ?: FelixWords.reminder(r.text)
        val zone = clock.zone()
        val at = Instant.ofEpochMilli(moment).atZone(zone)
        val time = at.format(if (at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) CLOCK else WHEN)
        val task = r.taskId?.let(tasks::get)
        val message = ChatMessage(
            "reminder-${r.id}-$moment", Speaker.Felix, if (late) "$words ${FelixWords.late(time)}" else words, now, About(taskId = r.taskId, taskTitle = task?.title),
            kind = MessageKind.Reminder, byApp = draft == null, reminderId = r.id, suggestedReplies = FelixWords.REMINDER_REPLIES,
        )
        outbox.send(message)
        felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id) it.copy(sentAt = now).undrafted() else it }) }
        draft?.memories?.takeIf { it.isNotEmpty() }?.let { remember(it) }
        Log.i(AppGraph.TAG, "A reminder came${if (late) ", late" else ""}, in ${if (draft != null) "his words" else "yours"}")
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
     * with no list of the day beside it (your call, 16 Oct), so it has [FelixAnswers.MAX_MORNING]
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
        val user = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan, null, null, today(now), chat.value.messages, null, settings, now, clock.zone(), reminders = remindersToday(plan, now))
        // Asked in the app's scope, not the briefing's: past [MORNING_MS] the briefing goes without
        // it, and the call, paid for, comes back and is counted all the same.
        val call = scope.async {
            gateway.call(interactive = false, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, "Morning") { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user, maxMessage = FelixAnswers.MAX_MORNING) }
        }
        val called = withTimeoutOrNull(MORNING_MS) { call.await() }
        val answer = (called as? Called.Answered)?.value?.answer?.takeIf { it.message.isNotBlank() } ?: return@withLock false
        // His message whole, with no list of the day beside it (your call, 16 Oct): the room is his.
        outbox.send(ChatMessage(UUID.randomUUID().toString(), Speaker.Felix, answer.message, now, kind = MessageKind.Morning, suggestedReplies = answer.replies))
        answer.memories.takeIf { it.isNotEmpty() }?.let { remember(it) }
        true
    }

    private fun today(now: Long): List<SlotRecord> {
        val date = focus.today(now).toString()
        return log.value.slots.filter { it.date == date }
    }

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
        val drafted = state.draft?.takeIf { FelixRules.fits(it, trigger, now, lastSent()) }
        if (state.draft != null) chat.update { it.copy(felix = it.felix.copy(draft = null)) }
        val called = if (drafted != null) null else ask(caller, trigger, now, interactive = true)
        val answer = drafted?.answer ?: (called as? Called.Answered)?.value?.answer
        val text = answer?.message.orEmpty()
        val next = now + (answer?.nextCheckMin ?: settings.felixDefaultCheckMin).coerceAtLeast(FelixAnswers.MIN_CHECK_MIN) * 60_000L
        // Asking how the completions went, the replies are the three answers (LR-05).
        val replies = when (trigger.reason) {
            FelixPrompts.Reason.Completions -> Assessment.ANSWERS.map { it.first }
            // A question's answer is in your words (AI-10).
            FelixPrompts.Reason.Question -> emptyList()
            else -> answer?.replies.orEmpty()
        }
        val task = trigger.taskId?.let { id -> tasks.value.tasks.firstOrNull { it.id == id } }
        val message = ChatMessage(
            UUID.randomUUID().toString(), Speaker.Felix, text, now, About(taskId = trigger.taskId, taskTitle = task?.title),
            // Why he wrote it, its kind (his answer, the chat's).
            kind = MessageKind.entries.firstOrNull { it.name == trigger.reason.name } ?: MessageKind.Chat, pieceKey = trigger.key, nextCheckAt = next, asks = trigger.asks, suggestedReplies = replies,
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
        Log.i(AppGraph.TAG, "Felix, ${trigger.reason} about ${trigger.key}: ${if (text.isBlank()) "nothing" else "a message"}, next look in ${(next - now) / 60_000L} min")
        return message
    }

    /** When he last wrote something with words: a message written ahead since is out of date. */
    private fun lastSent(): Long? = chat.value.messages.filter { it.from == Speaker.Felix && it.text.isNotBlank() }.maxOfOrNull { it.at }

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
     * answers (LR-05): put on each completion it asked about, as the old notification's buttons
     * did; or, a Question's (AI-10), an instruction on its task and kept with it, so the model reads
     * it again with your answer. False for anything else, which goes on in the chat.
     */
    suspend fun answered(message: ChatMessage, text: String): Boolean {
        val about = message.about.taskId
        if (message.kind == MessageKind.Question && about != null) {
            val task = this.tasks.value.tasks.firstOrNull { it.id == about } ?: return false
            addInstruction(text, About(taskId = task.id, taskTitle = task.title), true)
            tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == task.id) it.copy(answers = (it.answers + text.trim()).takeLast(MAX_ANSWERS)) else it }) }
            // Read again with it now, not at the next sync: it's what settles the reading.
            ports.readAgain()
            return true
        }
        if (message.kind != MessageKind.Completions || message.asks.isEmpty()) return false
        val answer = Assessment.ANSWERS.firstOrNull { it.first.equals(text.trim(), ignoreCase = true) }?.second ?: return false
        message.asks.forEach { assess(it.taskId, it.doneAt ?: 0L, answer) }
        return true
    }

    /**
     * His answer for [trigger] as it stands at [at] (now, or when a draft's for), as the call went;
     * [interactive], due now, it goes through a rest (AC-04: as the chat does, but for a key that
     * failed or the cap). About [task] (its trigger's, or a reminder's), with your [reminder]'s words
     * and the task's [notes] where there are any (8a).
     */
    private suspend fun ask(
        caller: FelixCaller,
        trigger: FelixRules.Trigger,
        at: Long,
        interactive: Boolean,
        task: TaskItem? = trigger.taskId?.let { id -> this.tasks.value.tasks.firstOrNull { it.id == id } },
        reminder: String? = slotReminder(trigger),
        notes: List<String> = task?.let(::notesOf).orEmpty(),
    ): Called<FelixCaller.Result> {
        val settings = this.settings.value
        val profile = this.profile(at)
        val free = (focus.creditLeftMs(at) / 60_000L).toInt().takeIf { trigger.reason == FelixPrompts.Reason.DayDone || trigger.reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(trigger.reason, planning.plan(), trigger.slot, task, today(at), chat.value.messages, trigger.question?.question, settings, at, clock.zone(), free, trigger.record, trigger.asks.map { it.title }, firstAction = trigger.slot?.chunk?.key?.let { model.value.microPlans[it] }?.first?.takeIf { it.isNotEmpty() }, reminder = reminder, notes = notes)
        val called = gateway.call(interactive = interactive, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, trigger.reason.name) { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user) }
        if (called is Called.Failed) Log.w(AppGraph.TAG, "Felix's message failed", called.error)
        return called
    }

    /** [task]'s notes, its own and the standing ones it matches (8a), as he's told them. */
    private fun notesOf(task: TaskItem): List<String> = Notes.forTask(task, felix.value.notes, settings.value.classMap).map { it.text }

    /**
     * Your reminder at [trigger]'s slot's start (8a), for his SlotStart: sent at it, or still to come with it
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

    /** Your reminders that come today (8a), as his morning's told them: "16:00 dentist". */
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
     * His words for the reminders due within [FelixRules.AHEAD_MS] (Stage 8a): at once after one's set,
     * moved or brought back, so one due inside the window has them without waiting for a plan or a look.
     */
    suspend fun draftReminders(now: Long = clock.now()) = writeReminders(planning.plan(now = now), now)

    /** Held while his words for reminders are written, so each is asked for once. */
    private val drafting = Mutex()

    /**
     * His words for each reminder of yours due within [FelixRules.AHEAD_MS] of [now] by [plan] and not
     * asked for its moment yet ([Reminders.asked]): one call each (Q9: B; Q24), written as of its
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
            val task = r.taskId?.let(tasks::get)
            val notes = task?.let { Notes.forTask(it, felix.value.notes, settings.value.classMap).map { n -> n.text } }.orEmpty()
            val trigger = FelixRules.Trigger(FelixPrompts.Reason.Reminder, slot = plan.slotAt(moment) ?: plan.nextSlot(moment))
            val called = ask(caller, trigger, moment, interactive = false, task = task, reminder = r.text, notes = notes) as? Called.Answered ?: continue
            // Refused, or none that could be read, it's paid for all the same: its moment's kept as asked for, so the next look doesn't ask again.
            val answer = called.value.answer
            // Kept only on the reminder as it was asked for: moved, snoozed, sent or cancelled meanwhile, it's another moment's.
            felix.update { s -> s.copy(reminders = s.reminders.map { if (it.id == r.id && it.undrafted() == r.undrafted()) it.copy(draft = answer, draftedAt = now, draftFor = moment) else it }) }
            if (answer == null) Log.w(AppGraph.TAG, "Felix's words for a reminder came back with none: yours go")
            else Log.i(AppGraph.TAG, "Felix wrote ahead for a reminder, due at ${Instant.ofEpochMilli(moment).atZone(clock.zone()).toLocalTime()}")
        }
    }

    /** [draftAhead]'s writing, under [writing]: the cheap checks first, as his looks come every half minute. */
    private suspend fun draft(plan: Plan, now: Long) {
        val (trigger, at) = FelixRules.upcoming(plan, this.settings.value, now) ?: return
        if (at - now > FelixRules.AHEAD_MS) return
        if (FelixRules.fits(chat.value.felix.draft, trigger, now, lastSent())) return
        if (!gateway.available()) return
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return
        val answer = (ask(caller, trigger, at, interactive = false) as? Called.Answered)?.value?.answer ?: return
        chat.update { it.copy(felix = it.felix.copy(draft = FelixDraft(trigger.reason.name, trigger.key, answer, now))) }
        Log.i(AppGraph.TAG, "Felix wrote ahead for ${trigger.key}, due at ${Instant.ofEpochMilli(at).atZone(clock.zone()).toLocalTime()}")
    }

    /** His next look, by alarm: the phone may be asleep through the service's ticks. */
    private fun arm(now: Long) = ports.armLook(FelixRules.next(focus.plan(now), chat.value.felix, this.settings.value, now))
}
