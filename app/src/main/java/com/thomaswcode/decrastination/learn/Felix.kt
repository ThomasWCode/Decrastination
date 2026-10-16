package com.thomaswcode.decrastination.learn

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.SlotAlarms
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.FelixAnswers
import com.thomaswcode.decrastination.enrich.FelixCaller
import com.thomaswcode.decrastination.enrich.FelixPrompts
import com.thomaswcode.decrastination.notify.FelixNotifications
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Felix (LR-03): looks for a reason to write ([FelixRules]) at each slot alarm, at the check he set
 * and on the focus service's half-minute tick; writes through the model (AI-22) when there is one,
 * posts it with its replies (AI-24) and keeps it on record. Cheap when there's none: the rules are
 * pure, and the model is asked only for a message. Silent while Claude is off.
 */
class Felix(private val graph: AppGraph) {
    /** One look at a time: a check while one writes is covered by it. */
    private val writing = Mutex()

    /** Something done on [taskIds] (LR-03): opened, or studied in AnkiDroid. A tick or a photo is on record already. */
    suspend fun noteActivity(taskIds: Collection<String>, now: Long = graph.clock.now()) {
        if (taskIds.isEmpty()) return
        graph.runtime.update { it.copy(felix = it.felix.withActivity(taskIds, now)) }
    }

    /**
     * Writes if there's a reason to now, or else his next message ahead if it's time to ([draft]);
     * then sets his next look. Writing already (a draft), he looks again in a minute.
     */
    suspend fun check(now: Long = graph.clock.now()) {
        if (!writing.tryLock()) {
            SlotAlarms.armFelix(graph.app, now + BUSY_RETRY_MS)
            return
        }
        try {
            val trigger = due(now)
            if (trigger != null) write(trigger, now) else draft(graph.focus.plan(now), now)
            arm(now)
        } finally {
            writing.unlock()
        }
    }

    /**
     * A message for [reason] now, whatever the rules say (a PC's `felix-say`, to try his prompt):
     * about the slot under way or next, or the last on record. Null while Claude is off.
     */
    suspend fun say(reason: FelixPrompts.Reason, now: Long = graph.clock.now()): FelixRecord? = writing.withLock {
        val plan = graph.plan(now = now)
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
    suspend fun morning(title: String, now: Long = graph.clock.now()): Boolean = writing.withLock {
        val plan = graph.plan(now = now)
        if (plan.todaySlots.none { it.kind == com.thomaswcode.decrastination.core.SlotKind.Work }) return@withLock false
        if (!com.thomaswcode.decrastination.notify.Notify.shown(graph.app, com.thomaswcode.decrastination.notify.Channels.BRIEFING)) return@withLock false
        val caller = graph.felixCaller() ?: return@withLock false
        val settings = graph.settings.value
        val profile = graph.profile(now)
        val user = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan, null, null, today(now), graph.log.value.felix, null, settings, now, graph.clock.zone())
        // Asked in the app's scope, not the briefing's: past [MORNING_MS] the briefing goes without
        // it, and the call, paid for, comes back and is counted all the same.
        val call = graph.scope.async {
            graph.callModel(interactive = false, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Felix, "Morning") { r: FelixCaller.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user, maxMessage = FelixAnswers.MAX_MORNING) }
        }
        val called = kotlinx.coroutines.withTimeoutOrNull(MORNING_MS) { call.await() }
        val answer = (called as? AppGraph.Called.Answered)?.value?.answer?.takeIf { it.message.isNotBlank() } ?: return@withLock false
        val record = FelixRecord(UUID.randomUUID().toString(), now, FelixPrompts.Reason.Morning.name, answer.message, answer.replies)
        graph.log.update { it.copy(felix = it.felix + record).trimmed(now) }
        answer.memories.takeIf { it.isNotEmpty() }?.let { graph.remember(it) }
        // His message whole, under the day's title, with no list of the day beside it (your call,
        // 16 Oct): the room is his.
        FelixNotifications.post(graph.app, record.id, settings.felixName, answer.message, answer.replies, title = title, channel = com.thomaswcode.decrastination.notify.Channels.BRIEFING)
        true
    }

    private fun today(now: Long): List<SlotRecord> {
        val date = graph.focus.today(now).toString()
        return graph.log.value.slots.filter { it.date == date }
    }

    private fun due(now: Long): FelixRules.Trigger? {
        // Claude off, or his messages not to be seen (notifications or his channel off): he has no
        // voice, nothing's asked of the model, and nothing's put on record, so it's all there for later.
        if (graph.claudeKey() == null || !com.thomaswcode.decrastination.notify.Notify.shown(graph.app, com.thomaswcode.decrastination.notify.Channels.FELIX)) return null
        val settings = graph.settings.value
        val zone = graph.clock.zone()
        val dayStart = graph.focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = graph.tasks.value.tasks.associateBy { it.id }
        // The completions waiting to be asked about whose work no slot kept that day saw (LR-05):
        // today's, and any kept from a day his messages couldn't be seen.
        val toAsk = FelixRules.toAsk(graph.runtime.value.assessLater, graph.log.value.slots, now, zone)
        return FelixRules.due(
            // The blocker's, kept for the minute: this runs every half minute.
            plan = graph.focus.plan(now),
            state = graph.runtime.value.felix,
            today = today(now),
            sent = graph.log.value.felix.filter { it.at >= dayStart },
            settings = settings,
            now = now,
            quiet = BlockPolicy.isQuiet(now, zone, settings),
            busyNow = graph.calendarTime.busy.any { now >= it.start && now < it.end },
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
        val caller = graph.felixCaller() ?: return null
        val settings = graph.settings.value
        val zone = graph.clock.zone()
        // Written ahead and still fitting (AC-10): sent as it is, at once.
        val state = graph.runtime.value.felix
        val drafted = state.draft?.takeIf { FelixRules.fits(it, trigger, now, graph.log.value.felix.filter { r -> r.text.isNotBlank() }.maxOfOrNull { r -> r.at }) }
        if (state.draft != null) graph.runtime.update { it.copy(felix = it.felix.copy(draft = null)) }
        val called = if (drafted != null) null else ask(caller, trigger, now, interactive = true)
        val answer = drafted?.answer ?: (called as? AppGraph.Called.Answered)?.value?.answer
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
        if ((drafted == null && called !is AppGraph.Called.Answered) || (answer == null && (trigger.question != null || trigger.asks.isNotEmpty()))) {
            val retry = com.thomaswcode.decrastination.data.FelixRetry(trigger.reason.name, trigger.key, now + FelixRules.RETRY_MS)
            graph.runtime.update { it.copy(felix = it.felix.copy(nextCheckAt = next, retry = retry)) }
            return null
        }
        graph.log.update { it.copy(felix = it.felix + record).trimmed(now) }
        // Put (or let go, silent): off the list.
        trigger.question?.let { q -> graph.runtime.update { it.copy(felix = it.felix.copy(questions = it.felix.questions - q)) } }
        // Asked (or let go, silent): off the list, with any older than today's.
        if (trigger.asks.isNotEmpty()) {
            val dayStart = graph.focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
            graph.runtime.update { s -> s.copy(assessLater = s.assessLater.filter { a -> a !in trigger.asks && (a.doneAt ?: 0L) >= dayStart }) }
        }
        graph.runtime.update { s ->
            s.copy(
                felix = s.felix.copy(
                    nextCheckAt = next,
                    lastSentAt = if (text.isNotBlank()) now else s.felix.lastSentAt,
                    retry = s.felix.retry?.takeUnless { it.reason == trigger.reason.name && it.key == trigger.key },
                ),
            )
        }
        answer?.memories?.takeIf { it.isNotEmpty() }?.let { graph.remember(it) }
        if (text.isNotBlank()) FelixNotifications.post(graph.app, record.id, settings.felixName, text, record.replies)
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
    suspend fun queueQuestion(taskId: String, question: String?, now: Long = graph.clock.now()) {
        val lately = graph.log.value.felix.any { it.reason == FelixPrompts.Reason.Question.name && it.taskId == taskId && it.at > now - 24 * 3_600_000L }
        graph.runtime.update { s -> s.copy(felix = s.felix.withQuestion(taskId, question.takeUnless { lately }, now)) }
    }

    /**
     * [text] in reply to his message [record], if it's a Completions one and [text] one of its three
     * answers (LR-05): put on each completion it asked about, as the old notification's buttons
     * did; or, a Question's (AI-10), an instruction on its task and kept with it, so the model reads
     * it again with your answer. False for anything else, which goes on in the chat.
     */
    suspend fun answered(record: FelixRecord, text: String): Boolean {
        if (record.reason == FelixPrompts.Reason.Question.name && record.taskId != null) {
            val task = graph.tasks.value.tasks.firstOrNull { it.id == record.taskId } ?: return false
            graph.addInstruction(text, com.thomaswcode.decrastination.core.About(taskId = task.id, taskTitle = task.title), answer = true)
            graph.tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == task.id) it.copy(answers = (it.answers + text.trim()).takeLast(MAX_ANSWERS)) else it }) }
            // Read again with it now, not at the next sync: it's what settles the reading.
            com.thomaswcode.decrastination.enrich.EnrichWorker.enqueue(graph.app)
            return true
        }
        if (record.reason != FelixPrompts.Reason.Completions.name || record.asks.isEmpty()) return false
        val answer = Assessment.ANSWERS.firstOrNull { it.first.equals(text.trim(), ignoreCase = true) }?.second ?: return false
        record.asks.forEach { Assessment.record(graph.app, it.taskId, it.doneAt ?: 0L, answer) }
        return true
    }

    /**
     * His answer for [trigger] as it stands at [at] (now, or when a draft's for), as the call went;
     * [interactive], due now, it goes through a rest (AC-04: as the chat does, but for a key that
     * failed or the cap).
     */
    private suspend fun ask(caller: FelixCaller, trigger: FelixRules.Trigger, at: Long, interactive: Boolean): AppGraph.Called<FelixCaller.Result> {
        val settings = graph.settings.value
        val profile = graph.profile(at)
        val task = trigger.taskId?.let { id -> graph.tasks.value.tasks.firstOrNull { it.id == id } }
        val recent = graph.log.value.felix.filter { it.text.isNotBlank() }
        val free = (graph.focus.creditLeftMs(at) / 60_000L).toInt().takeIf { trigger.reason == FelixPrompts.Reason.DayDone || trigger.reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(trigger.reason, graph.plan(), trigger.slot, task, today(at), recent, trigger.question?.question, settings, at, graph.clock.zone(), free, trigger.record, trigger.asks.map { it.title }, firstAction = trigger.slot?.chunk?.key?.let { graph.runtime.value.microPlans[it] }?.first?.takeIf { it.isNotEmpty() })
        val called = graph.callModel(interactive = interactive, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Felix, trigger.reason.name) { r: FelixCaller.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user) }
        if (called is AppGraph.Called.Failed) Log.w(AppGraph.TAG, "Felix's message failed", called.error)
        return called
    }

    /**
     * His next message written ahead (AC-10), after a plan: a slot's "starting?" due within
     * [FelixRules.AHEAD_MS], written as of that moment, so it's sent the moment it's due. Not again
     * while one fits; not with Claude off; not while he's writing. His looks do it too ([check]), one
     * of them when it's time to with a grace longer than that ([FelixRules.next]).
     */
    suspend fun draftAhead(plan: com.thomaswcode.decrastination.core.Plan, now: Long = graph.clock.now()) {
        if (!writing.tryLock()) return
        try {
            draft(plan, now)
        } finally {
            writing.unlock()
        }
    }

    /** [draftAhead]'s writing, under [writing]: the cheap checks first, as his looks come every half minute. */
    private suspend fun draft(plan: com.thomaswcode.decrastination.core.Plan, now: Long) {
        val (trigger, at) = FelixRules.upcoming(plan, graph.settings.value, now) ?: return
        if (at - now > FelixRules.AHEAD_MS) return
        val lastSent = graph.log.value.felix.filter { it.text.isNotBlank() }.maxOfOrNull { it.at }
        if (FelixRules.fits(graph.runtime.value.felix.draft, trigger, now, lastSent)) return
        if (!com.thomaswcode.decrastination.notify.Notify.shown(graph.app, com.thomaswcode.decrastination.notify.Channels.FELIX) || !graph.modelAvailable()) return
        val caller = graph.felixCaller() ?: return
        val answer = (ask(caller, trigger, at, interactive = false) as? AppGraph.Called.Answered)?.value?.answer ?: return
        graph.runtime.update { it.copy(felix = it.felix.copy(draft = com.thomaswcode.decrastination.data.FelixDraft(trigger.reason.name, trigger.key, answer, now))) }
        Log.i(AppGraph.TAG, "Felix wrote ahead for ${trigger.key}, due at ${java.time.Instant.ofEpochMilli(at).atZone(graph.clock.zone()).toLocalTime()}")
    }

    /** His next look, by alarm: the phone may be asleep through the service's ticks. */
    private fun arm(now: Long) = SlotAlarms.armFelix(graph.app, FelixRules.next(graph.focus.plan(now), graph.runtime.value.felix, graph.settings.value, now))
}
