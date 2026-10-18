package com.thomaswcode.decrastination.felix

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.FelixDraft
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.FelixRetry
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.plan.Planning
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Felix (LR-03): looks for a reason to write ([FelixRules]) at each slot alarm, at the check he set
 * and on the focus service's half-minute tick; writes through the model (AI-22) when there is one,
 * posts it with its replies (AI-24) and keeps it on record. Cheap when there's none: the rules are
 * pure, and the model is asked only for a message. Silent while Claude is off. Built like
 * [Focus]: its stores, the clock and small callbacks in its constructor, so it's tested on temporary
 * stores.
 */
class Looks(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val log: JsonStore<ActivityLog>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val notices: Notices,
    private val focus: Focus,
    private val planning: Planning,
    private val ports: FelixPorts,
    /** The calendar's busy time, as last read and judged: he doesn't write while you're in something on it. */
    private val calendarTime: () -> EventJudge.Time,
    /** Who the student is, as every call sends it ([Conversation.profile]). */
    private val profile: (Long) -> Profile.Block,
    /** What he remembered, kept ([Conversation.remember]). */
    private val remember: suspend (List<Given>) -> List<MemoryEdit>,
    /** Your answer to his question about a task, as an instruction on it, read by Claude (`AppGraph.addInstruction`). */
    private val addInstruction: suspend (text: String, about: About, answer: Boolean) -> String,
    /** How a completion went, on record ([com.thomaswcode.decrastination.learn.Assessment.record]). */
    private val assess: suspend (taskId: String, doneAt: Long, answer: String) -> Unit,
) {
    /** One look at a time: a check while one writes is covered by it. */
    private val writing = Mutex()

    /** Something done on [taskIds] (LR-03): opened, or studied in AnkiDroid. A tick or a photo is on record already. */
    suspend fun noteActivity(taskIds: Collection<String>, now: Long = clock.now()) {
        if (taskIds.isEmpty()) return
        runtime.update { it.copy(felix = it.felix.withActivity(taskIds, now)) }
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
            val trigger = due(now)
            if (trigger != null) write(trigger, now) else draft(focus.plan(now), now)
            arm(now)
        } finally {
            writing.unlock()
        }
    }

    /**
     * A message for [reason] now, whatever the rules say (a PC's `felix-say`, to try his prompt):
     * about the slot under way or next, or the last on record. Null while Claude is off.
     */
    suspend fun say(reason: FelixPrompts.Reason, now: Long = clock.now()): FelixRecord? = writing.withLock {
        val plan = planning.plan(now = now)
        val record = today(now).maxByOrNull { it.at }
        val slot = (plan.slotAt(now) ?: plan.nextSlot(now)).takeIf { reason == FelixPrompts.Reason.SlotStart || record == null }
        write(FelixRules.Trigger(reason, slot = slot, record = record.takeIf { slot == null }), now)
    }

    /**
     * The morning's message (LR-04): his sentence or two on the day's shape, whole, under the
     * briefing's [title], as the briefing: on its channel, under its ceiling (LR-07), with no list of
     * the day beside it (your call, 16 Oct), so it has [FelixAnswers.MAX_MORNING] characters. False,
     * the list going alone as before, with no slot today, the briefing not to be seen, Claude off or
     * resting, no answer within [MORNING_MS], or an empty one. It sets no check: the day's slots do that.
     */
    suspend fun morning(title: String, now: Long = clock.now()): Boolean = writing.withLock {
        val plan = planning.plan(now = now)
        if (plan.todaySlots.none { it.kind == SlotKind.Work }) return@withLock false
        if (!notices.shown(Channels.BRIEFING)) return@withLock false
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return@withLock false
        val settings = this.settings.value
        val profile = this.profile(now)
        val user = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan, null, null, today(now), log.value.felix, null, settings, now, clock.zone())
        // Asked in the app's scope, not the briefing's: past [MORNING_MS] the briefing goes without
        // it, and the call, paid for, comes back and is counted all the same.
        val call = scope.async {
            gateway.call(interactive = false, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, "Morning") { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user, maxMessage = FelixAnswers.MAX_MORNING) }
        }
        val called = withTimeoutOrNull(MORNING_MS) { call.await() }
        val answer = (called as? Called.Answered)?.value?.answer?.takeIf { it.message.isNotBlank() } ?: return@withLock false
        val record = FelixRecord(UUID.randomUUID().toString(), now, FelixPrompts.Reason.Morning.name, answer.message, answer.replies)
        log.update { it.copy(felix = it.felix + record).trimmed(now) }
        answer.memories.takeIf { it.isNotEmpty() }?.let { remember(it) }
        // His message whole, under the day's title, with no list of the day beside it (your call,
        // 16 Oct): the room is his.
        ports.post(record.id, settings.felixName, answer.message, answer.replies, title = title, channel = Channels.BRIEFING)
        true
    }

    private fun today(now: Long): List<SlotRecord> {
        val date = focus.today(now).toString()
        return log.value.slots.filter { it.date == date }
    }

    private fun due(now: Long): FelixRules.Trigger? {
        // Claude off, or his messages not to be seen (notifications or his channel off): he has no
        // voice, nothing's asked of the model, and nothing's put on record, so it's all there for later.
        if (gateway.claudeKey() == null || !notices.shown(Channels.FELIX)) return null
        val settings = this.settings.value
        val zone = clock.zone()
        val dayStart = focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = this.tasks.value.tasks.associateBy { it.id }
        // The completions waiting to be asked about whose work no slot kept that day saw (LR-05):
        // today's, and any kept from a day his messages couldn't be seen.
        val toAsk = FelixRules.toAsk(runtime.value.assessLater, log.value.slots, now, zone)
        return FelixRules.due(
            // The blocker's, kept for the minute: this runs every half minute.
            plan = focus.plan(now),
            state = runtime.value.felix,
            today = today(now),
            sent = log.value.felix.filter { it.at >= dayStart },
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
     * posted if he wrote one (an empty one is his choice not to, as is an answer refused or unread,
     * paid for), and on record either way, so it isn't asked for again; his next check set by what
     * he said (20 minutes if he didn't), and what he remembered kept. A call held or failed puts
     * nothing on record: it's tried again past [FelixRules.RETRY_MS] while it's still to say.
     */
    private suspend fun write(trigger: FelixRules.Trigger, now: Long): FelixRecord? {
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return null
        val settings = this.settings.value
        val zone = clock.zone()
        // Written ahead and still fitting (AC-10): sent as it is, at once.
        val state = runtime.value.felix
        val drafted = state.draft?.takeIf { FelixRules.fits(it, trigger, now, log.value.felix.filter { r -> r.text.isNotBlank() }.maxOfOrNull { r -> r.at }) }
        if (state.draft != null) runtime.update { it.copy(felix = it.felix.copy(draft = null)) }
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
        val record = FelixRecord(UUID.randomUUID().toString(), now, trigger.reason.name, text, replies, trigger.key, trigger.taskId, next, asks = trigger.asks)
        // Held or failed, or a question or completions with no answer it could read: nothing on
        // record, tried again from his next look past the wait.
        if ((drafted == null && called !is Called.Answered) || (answer == null && (trigger.question != null || trigger.asks.isNotEmpty()))) {
            val retry = FelixRetry(trigger.reason.name, trigger.key, now + FelixRules.RETRY_MS)
            runtime.update { it.copy(felix = it.felix.copy(nextCheckAt = next, retry = retry)) }
            return null
        }
        log.update { it.copy(felix = it.felix + record).trimmed(now) }
        // Put (or let go, silent): off the list.
        trigger.question?.let { q -> runtime.update { it.copy(felix = it.felix.copy(questions = it.felix.questions - q)) } }
        // Asked (or let go, silent): off the list, with any older than today's.
        if (trigger.asks.isNotEmpty()) {
            val dayStart = focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
            runtime.update { s -> s.copy(assessLater = s.assessLater.filter { a -> a !in trigger.asks && (a.doneAt ?: 0L) >= dayStart }) }
        }
        runtime.update { s ->
            s.copy(
                felix = s.felix.copy(
                    nextCheckAt = next,
                    lastSentAt = if (text.isNotBlank()) now else s.felix.lastSentAt,
                    retry = s.felix.retry?.takeUnless { it.reason == trigger.reason.name && it.key == trigger.key },
                ),
            )
        }
        answer?.memories?.takeIf { it.isNotEmpty() }?.let { remember(it) }
        if (text.isNotBlank()) ports.post(record.id, settings.felixName, text, record.replies)
        Log.i(AppGraph.TAG, "Felix, ${trigger.reason} about ${trigger.key}: ${if (text.isBlank()) "nothing" else "a message"}, next look in ${(next - now) / 60_000L} min")
        return record
    }

    private companion object {
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
        val lately = log.value.felix.any { it.reason == FelixPrompts.Reason.Question.name && it.taskId == taskId && it.at > now - 24 * 3_600_000L }
        runtime.update { s -> s.copy(felix = s.felix.withQuestion(taskId, question.takeUnless { lately }, now)) }
    }

    /**
     * [text] in reply to his message [record], if it's a Completions one and [text] one of its three
     * answers (LR-05): put on each completion it asked about, as the old notification's buttons
     * did; or, a Question's (AI-10), an instruction on its task and kept with it, so the model reads
     * it again with your answer. False for anything else, which goes on in the chat.
     */
    suspend fun answered(record: FelixRecord, text: String): Boolean {
        if (record.reason == FelixPrompts.Reason.Question.name && record.taskId != null) {
            val task = this.tasks.value.tasks.firstOrNull { it.id == record.taskId } ?: return false
            addInstruction(text, About(taskId = task.id, taskTitle = task.title), true)
            tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == task.id) it.copy(answers = (it.answers + text.trim()).takeLast(MAX_ANSWERS)) else it }) }
            // Read again with it now, not at the next sync: it's what settles the reading.
            ports.readAgain()
            return true
        }
        if (record.reason != FelixPrompts.Reason.Completions.name || record.asks.isEmpty()) return false
        val answer = Assessment.ANSWERS.firstOrNull { it.first.equals(text.trim(), ignoreCase = true) }?.second ?: return false
        record.asks.forEach { assess(it.taskId, it.doneAt ?: 0L, answer) }
        return true
    }

    /**
     * His answer for [trigger] as it stands at [at] (now, or when a draft's for), as the call went;
     * [interactive], due now, it goes through a rest (AC-04: as the chat does, but for a key that
     * failed or the cap).
     */
    private suspend fun ask(caller: FelixCaller, trigger: FelixRules.Trigger, at: Long, interactive: Boolean): Called<FelixCaller.Result> {
        val settings = this.settings.value
        val profile = this.profile(at)
        val task = trigger.taskId?.let { id -> this.tasks.value.tasks.firstOrNull { it.id == id } }
        val recent = log.value.felix.filter { it.text.isNotBlank() }
        val free = (focus.creditLeftMs(at) / 60_000L).toInt().takeIf { trigger.reason == FelixPrompts.Reason.DayDone || trigger.reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(trigger.reason, planning.plan(), trigger.slot, task, today(at), recent, trigger.question?.question, settings, at, clock.zone(), free, trigger.record, trigger.asks.map { it.title }, firstAction = trigger.slot?.chunk?.key?.let { runtime.value.microPlans[it] }?.first?.takeIf { it.isNotEmpty() })
        val called = gateway.call(interactive = interactive, record = ModelCalls.OnRecord(ModelEffort.Job.Felix, trigger.reason.name) { r: FelixCaller.Result -> r.usage to ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user) }
        if (called is Called.Failed) Log.w(AppGraph.TAG, "Felix's message failed", called.error)
        return called
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
        } finally {
            writing.unlock()
        }
    }

    /** [draftAhead]'s writing, under [writing]: the cheap checks first, as his looks come every half minute. */
    private suspend fun draft(plan: Plan, now: Long) {
        val (trigger, at) = FelixRules.upcoming(plan, this.settings.value, now) ?: return
        if (at - now > FelixRules.AHEAD_MS) return
        val lastSent = log.value.felix.filter { it.text.isNotBlank() }.maxOfOrNull { it.at }
        if (FelixRules.fits(runtime.value.felix.draft, trigger, now, lastSent)) return
        if (!notices.shown(Channels.FELIX) || !gateway.available()) return
        val caller = gateway.client(ModelEffort.Job.Felix)?.let { FelixCaller(it) } ?: return
        val answer = (ask(caller, trigger, at, interactive = false) as? Called.Answered)?.value?.answer ?: return
        runtime.update { it.copy(felix = it.felix.copy(draft = FelixDraft(trigger.reason.name, trigger.key, answer, now))) }
        Log.i(AppGraph.TAG, "Felix wrote ahead for ${trigger.key}, due at ${Instant.ofEpochMilli(at).atZone(clock.zone()).toLocalTime()}")
    }

    /** His next look, by alarm: the phone may be asleep through the service's ticks. */
    private fun arm(now: Long) = ports.armLook(FelixRules.next(focus.plan(now), runtime.value.felix, this.settings.value, now))
}
