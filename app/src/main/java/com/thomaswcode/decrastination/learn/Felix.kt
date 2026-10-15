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

    /** Writes if there's a reason to now; then sets his next look. */
    suspend fun check(now: Long = graph.clock.now()) {
        if (!writing.tryLock()) return
        try {
            due(now)?.let { write(it, now) }
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
     * The morning's message (LR-04): his sentence or two on the day's shape, the first the title and
     * the second the text over the briefing's [lines] (its facts, should the sentence be off). False,
     * the list going alone as before, with no slot today, Claude off or resting, no answer within
     * [MORNING_MS], or an empty one. It sets no check: the day's slots do that.
     */
    suspend fun morning(title: String, lines: List<String>, now: Long = graph.clock.now()): Boolean = writing.withLock {
        val plan = graph.plan(now = now)
        if (plan.todaySlots.none { it.kind == com.thomaswcode.decrastination.core.SlotKind.Work }) return@withLock false
        val caller = graph.felixCaller() ?: return@withLock false
        val settings = graph.settings.value
        val profile = graph.profile(now)
        val user = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan, null, null, today(now), graph.log.value.felix, null, settings, now, graph.clock.zone())
        val called = kotlinx.coroutines.withTimeoutOrNull(MORNING_MS) {
            graph.callModel(interactive = false, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user) }
        }
        val answer = (called as? AppGraph.Called.Answered)?.value?.answer?.takeIf { it.message.isNotBlank() } ?: return@withLock false
        val record = FelixRecord(UUID.randomUUID().toString(), now, FelixPrompts.Reason.Morning.name, answer.message, answer.replies)
        graph.log.update { it.copy(felix = it.felix + record).trimmed(now) }
        answer.memories.takeIf { it.isNotEmpty() }?.let { graph.remember(it) }
        val (first, second) = FelixRules.sentences(answer.message)
        FelixNotifications.post(graph.app, record.id, settings.felixName, second ?: title, answer.replies, title = first, lines = lines)
        true
    }

    private fun today(now: Long): List<SlotRecord> {
        val date = graph.focus.today(now).toString()
        return graph.log.value.slots.filter { it.date == date }
    }

    private fun due(now: Long): FelixRules.Trigger? {
        // Claude off: he has no voice, and nothing's put on record to say so.
        if (graph.claudeKey() == null) return null
        val settings = graph.settings.value
        val zone = graph.clock.zone()
        val dayStart = graph.focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = graph.tasks.value.tasks.associateBy { it.id }
        // Today's completions waiting to be asked about whose work no kept slot saw (LR-05).
        val keptOn = today(now).filter { it.outcome.kept }.map { it.taskId }.toSet()
        val toAsk = graph.runtime.value.assessLater.filter { a -> a.taskId !in keptOn && (a.doneAt ?: 0L) >= dayStart }
        return FelixRules.due(
            plan = graph.plan(now = now),
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
     * [trigger]'s message: asked of the model with what it's about, posted if he wrote one (an empty
     * one is his choice not to), and on record either way, so it isn't asked for again; his next
     * check set by what he said (20 minutes if he didn't), and what he remembered kept.
     */
    private suspend fun write(trigger: FelixRules.Trigger, now: Long): FelixRecord? {
        val caller = graph.felixCaller() ?: return null
        val settings = graph.settings.value
        val zone = graph.clock.zone()
        // Written ahead and still fitting (AC-10): sent as it is, at once.
        val state = graph.runtime.value.felix
        val drafted = state.draft?.takeIf { FelixRules.fits(it, trigger, now, graph.log.value.felix.filter { r -> r.text.isNotBlank() }.maxOfOrNull { r -> r.at }) }
        if (state.draft != null) graph.runtime.update { it.copy(felix = it.felix.copy(draft = null)) }
        val answer = drafted?.answer ?: ask(caller, trigger, now)
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
        graph.log.update { it.copy(felix = it.felix + record).trimmed(now) }
        // Put (or let go, silent): off the list.
        trigger.question?.let { q -> graph.runtime.update { it.copy(felix = it.felix.copy(questions = it.felix.questions - q)) } }
        // Asked (or let go, silent): off the list, with any older than today's.
        if (trigger.asks.isNotEmpty()) {
            val dayStart = graph.focus.today(now).atStartOfDay(zone).toInstant().toEpochMilli()
            graph.runtime.update { s -> s.copy(assessLater = s.assessLater.filter { a -> a !in trigger.asks && (a.doneAt ?: 0L) >= dayStart }) }
        }
        graph.runtime.update { it.copy(felix = it.felix.copy(nextCheckAt = next, lastSentAt = if (text.isNotBlank()) now else it.felix.lastSentAt)) }
        answer?.memories?.takeIf { it.isNotEmpty() }?.let { graph.remember(it) }
        if (text.isNotBlank()) FelixNotifications.post(graph.app, record.id, settings.felixName, text, record.replies)
        Log.i(AppGraph.TAG, "Felix, ${trigger.reason} about ${trigger.key}: ${if (text.isBlank()) "nothing" else "a message"}, next look in ${(next - now) / 60_000L} min")
        return record
    }

    private companion object {
        /** How long the morning's briefing waits for his sentence. */
        const val MORNING_MS = 20_000L

        /** A task's answers kept: the latest. */
        const val MAX_ANSWERS = 5
    }

    /**
     * The model's [question] about [taskId]'s reading (AI-10), to be put when he can: not while one's
     * waiting for it, nor again within a day of the last he put about it.
     */
    suspend fun queueQuestion(taskId: String, question: String, now: Long = graph.clock.now()) {
        if (graph.log.value.felix.any { it.reason == FelixPrompts.Reason.Question.name && it.taskId == taskId && it.at > now - 24 * 3_600_000L }) return
        graph.runtime.update { s ->
            if (s.felix.questions.any { it.taskId == taskId }) s
            else s.copy(felix = s.felix.copy(questions = s.felix.questions + com.thomaswcode.decrastination.data.FelixQuestion(taskId, question, now)))
        }
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
            graph.addInstruction(text, com.thomaswcode.decrastination.core.About(taskId = task.id, taskTitle = task.title))
            graph.tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == task.id) it.copy(answers = (it.answers + text.trim()).takeLast(MAX_ANSWERS)) else it }) }
            return true
        }
        if (record.reason != FelixPrompts.Reason.Completions.name || record.asks.isEmpty()) return false
        val answer = Assessment.ANSWERS.firstOrNull { it.first.equals(text.trim(), ignoreCase = true) }?.second ?: return false
        record.asks.forEach { Assessment.record(graph.app, it.taskId, it.doneAt ?: 0L, answer) }
        return true
    }

    /** His answer for [trigger] as it stands at [at] (now, or when a draft's for), or null: held, failed, or none. */
    private suspend fun ask(caller: FelixCaller, trigger: FelixRules.Trigger, at: Long): com.thomaswcode.decrastination.enrich.FelixAnswer? {
        val settings = graph.settings.value
        val profile = graph.profile(at)
        val task = trigger.taskId?.let { id -> graph.tasks.value.tasks.firstOrNull { it.id == id } }
        val recent = graph.log.value.felix.filter { it.text.isNotBlank() }
        val free = (graph.focus.creditLeftMs(at) / 60_000L).toInt().takeIf { trigger.reason == FelixPrompts.Reason.DayDone || trigger.reason == FelixPrompts.Reason.SlotKept }
        val user = FelixPrompts.describe(trigger.reason, graph.plan(), trigger.slot, task, today(at), recent, trigger.question?.question, settings, at, graph.clock.zone(), free, trigger.record, trigger.asks.map { it.title })
        val called = graph.callModel(interactive = false, cost = { r: FelixCaller.Result -> r.costUsd to r.refused }) { caller.say(FelixPrompts.system(settings, profile.year), profile.text, user) }
        if (called is AppGraph.Called.Failed) Log.w(AppGraph.TAG, "Felix's message failed", called.error)
        return (called as? AppGraph.Called.Answered)?.value?.answer
    }

    /**
     * His next message written ahead (AC-10), after a plan: a slot's "starting?" due within
     * [FelixRules.AHEAD_MS], written as of that moment, so it's sent the moment it's due. Not again
     * while one fits; not with Claude off; not while he's writing.
     */
    suspend fun draftAhead(plan: com.thomaswcode.decrastination.core.Plan, now: Long = graph.clock.now()) {
        val caller = graph.felixCaller() ?: return
        val (trigger, at) = FelixRules.upcoming(plan, graph.settings.value, now) ?: return
        if (at - now > FelixRules.AHEAD_MS) return
        val lastSent = graph.log.value.felix.filter { it.text.isNotBlank() }.maxOfOrNull { it.at }
        if (FelixRules.fits(graph.runtime.value.felix.draft, trigger, now, lastSent)) return
        if (!writing.tryLock()) return
        try {
            val answer = ask(caller, trigger, at) ?: return
            graph.runtime.update { it.copy(felix = it.felix.copy(draft = com.thomaswcode.decrastination.data.FelixDraft(trigger.reason.name, trigger.key, answer, now))) }
            Log.i(AppGraph.TAG, "Felix wrote ahead for ${trigger.key}, due at ${java.time.Instant.ofEpochMilli(at).atZone(graph.clock.zone()).toLocalTime()}")
        } finally {
            writing.unlock()
        }
    }

    /** His next look, by alarm: the phone may be asleep through the service's ticks. */
    private fun arm(now: Long) = SlotAlarms.armFelix(graph.app, FelixRules.next(graph.plan(now = now), graph.runtime.value.felix, graph.settings.value, now))
}
