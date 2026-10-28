package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FelixDraft
import com.thomaswcode.decrastination.core.FelixQuestion
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.FelixPrompts.Reason
import java.time.Instant
import java.time.ZoneId

/**
 * When Felix writes (LR-03): only around today's work, never in quiet hours or a busy event, and
 * otherwise at the pace he sets himself (Q-31). Pure: the plan, his state and what's on record in;
 * the reason to write, if there's one, out.
 */
object FelixRules {
    /** What a message would be about: a slot under way, or one on record (missed, kept). */
    data class Trigger(
        val reason: Reason,
        val slot: Slot? = null,
        val record: SlotRecord? = null,
        val asks: List<AssessLater> = emptyList(),
        val question: FelixQuestion? = null,
    ) {
        val key: String? get() = slot?.chunk?.key ?: record?.key
        val taskId: String? get() = slot?.chunk?.taskId ?: record?.taskId ?: question?.taskId
    }

    /** One of his messages as his rules count it: why he wrote it, the piece it was about, and when. */
    data class Sent(val reason: String, val key: String?, val at: Long)

    /** What he's sent since [since], as his rules count it: his own messages in [messages], the silent ones (he chose not to write) too. */
    fun sent(messages: List<ChatMessage>, since: Long): List<Sent> =
        ChatHistory.his(messages).filter { it.at >= since }.map { Sent(it.kind.name, it.pieceKey, it.at) }

    /** At most this many messages about one slot under way with nothing done: the first, then at the checks he sets. */
    const val MAX_PER_SLOT = 3

    /** A slot missed or kept, or the day done, is said only while it's fresh: not after a restart hours on. */
    const val FRESH_MS = 30 * 60_000L

    /** The evening's question about the day's completions comes from this minute of the day (LR-05). */
    const val COMPLETIONS_FROM_MIN = 19 * 60

    /** How long after a slot's end and grace its miss is surely on record. */
    private const val MISS_LAG_MS = 60_000L

    /** How long after a timer ends, at its zero or by a Stop, his messages about its task wait (D-112). */
    const val TIMER_HOLD_MS = 30 * 60_000L

    /**
     * When the hold [timer] puts on his messages about its task lifts: [TIMER_HOLD_MS] after it ended, at its zero or
     * by a Stop by hand, whichever came first, so one forgotten and left running silences nothing past it.
     */
    fun liftAt(timer: PieceTimer): Long = minOf(timer.endsAt, timer.stoppedAt ?: Long.MAX_VALUE) + TIMER_HOLD_MS

    /** Whether [timer] holds his messages about [taskId] at [now]: it's on that task, and its hold hasn't lifted. */
    private fun holds(timer: PieceTimer?, taskId: String, now: Long): Boolean = timer != null && timer.taskId == taskId && now < liftAt(timer)

    /** Whether [timer] could have covered [record]: on its task, and the record still fresh when it began, or made after it. */
    private fun covers(timer: PieceTimer?, record: SlotRecord): Boolean = timer != null && record.taskId == timer.taskId && record.at + FRESH_MS > timer.startedAt

    /**
     * Whether [timer] holds [trigger]'s message at [now] (D-112): a SlotStart about its task while it runs, or a
     * SlotEnd on a record it could have covered, till its hold lifts. [due] keeps such a message back; this is asked again
     * once his words are back, as a Start can come while he's asked.
     */
    fun held(trigger: Trigger, timer: PieceTimer?, now: Long): Boolean = when (trigger.reason) {
        Reason.SlotStart -> timer?.running == true && trigger.taskId?.let { holds(timer, it, now) } == true
        Reason.SlotEnd -> trigger.record?.let { covers(timer, it) && holds(timer, it.taskId, now) } == true
        else -> false
    }

    /**
     * The reason to write now, if any, in this order: nothing in quiet hours or a busy event; a slot
     * gone by undone (SlotEnd), or kept without the photo it [needsPhoto] to earn, asked about once;
     * today's last slot kept (DayDone), once; from [COMPLETIONS_FROM_MIN], not before the check he set,
     * the day's completions with no kept slot ([toAsk], LR-05), once an evening; then, with nothing
     * under way or to come today, nothing. Otherwise, not before the check he set: a slot under way
     * its grace (`felixGraceMin`) and more
     * with nothing done on its task since it began (SlotStart), at most [MAX_PER_SLOT] times; and a
     * slot just kept, every third one (SlotKept). [today]: today's slots on record; [sent]: his
     * messages today, the silent ones (he chose not to write, or the model couldn't be asked) too.
     * [timer]: the timer on a piece (D-112), whose task's SlotStart and SlotEnd wait while
     * it [holds]. A slot kept of a task he was [letDown] about today (D-119, Q102) is answered after
     * DayDone, before the check he set and whatever's left today, so the let-down is never his last word.
     */
    fun due(
        plan: Plan,
        state: FelixState,
        today: List<SlotRecord>,
        sent: List<Sent>,
        settings: Settings,
        now: Long,
        quiet: Boolean,
        busyNow: Boolean,
        needsPhoto: (SlotRecord) -> Boolean,
        toAsk: List<AssessLater> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        timer: PieceTimer? = null,
        letDown: (String) -> Boolean = { false },
    ): Trigger? {
        if (quiet || busyNow) return null
        fun said(reason: Reason, key: String?, since: Long) = sent.filter { it.reason == reason.name && it.key == key && it.at >= since }
        fun fresh(record: SlotRecord) = now - record.at <= FRESH_MS
        // A record the timer could have covered (one still fresh when it began, or made after it): its SlotEnd waits
        // while the timer holds, and is fresh from the hold's lift, or its own time where that's later. Any other is
        // fresh from its own time, so a miss stale before the timer began isn't brought back by the lift.
        fun covered(record: SlotRecord) = covers(timer, record)
        fun freshEnd(record: SlotRecord) = if (covered(record)) !holds(timer, record.taskId, now) && now - maxOf(record.at, liftAt(timer!!)) <= FRESH_MS else fresh(record)
        // One whose call didn't come back waits to be tried again; the rest go on meanwhile.
        fun waiting(reason: Reason, key: String?) = state.retry?.let { it.reason == reason.name && it.key == key && now < it.notBefore } == true
        // A slot gone by undone, or ticked without the photo it needs to earn once its time is over.
        today.firstOrNull { r ->
            freshEnd(r) && said(Reason.SlotEnd, r.key, r.start).isEmpty() && !waiting(Reason.SlotEnd, r.key) &&
                (r.outcome == SlotOutcome.Missed || (r.outcome.kept && !r.earned && needsPhoto(r) && now >= r.end))
        }?.let { return Trigger(Reason.SlotEnd, record = it) }
        // Today's last slot kept.
        val kept = today.filter { it.outcome.kept }
        val last = kept.maxByOrNull { it.at }
        if (plan.todayBucket?.chunks.orEmpty().isEmpty() && last != null && fresh(last) && sent.none { it.reason == Reason.DayDone.name } && !waiting(Reason.DayDone, last.key)) {
            return Trigger(Reason.DayDone, record = last)
        }
        // A slot kept of a task he was let down about today (Q102): always answered, not one in three, nor held by his
        // check or the evening's work being over, so it's said that it's over.
        kept.firstOrNull { fresh(it) && letDown(it.taskId) && said(Reason.SlotKept, it.key, it.start).isEmpty() && !waiting(Reason.SlotKept, it.key) }?.let {
            return Trigger(Reason.SlotKept, record = it)
        }
        val paced = state.nextCheckAt?.let { now < it } == true
        // A question of the model's about a reading (AI-10): put when he's free to, the oldest first.
        if (!paced) state.questions.firstOrNull()?.let { return Trigger(Reason.Question, question = it) }
        // Work its source says is done that the app saw none of: how it went, asked together in the evening.
        if (!paced && toAsk.isNotEmpty() && BlockPolicy.minuteOfDay(now, zone) >= COMPLETIONS_FROM_MIN && sent.none { it.reason == Reason.Completions.name }) {
            return Trigger(Reason.Completions, asks = toAsk)
        }
        // Quiet when nothing's scheduled (Q-31): no slot of today's under way or to come.
        val work = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null }
        if (work.none { it.end > now }) return null
        if (paced) return null
        plan.slotAt(now)?.let { slot ->
            val chunk = slot.chunk!!
            val done = (state.activity[chunk.taskId]?.let { it >= slot.start } == true) || kept.any { it.taskId == chunk.taskId && it.at >= slot.start }
            // A timer running on its task is your word you're on it, up to its hold's lift.
            val timed = timer?.running == true && holds(timer, chunk.taskId, now)
            if (!done && !timed && now >= slot.start + settings.felixGraceMin * 60_000L && said(Reason.SlotStart, chunk.key, slot.start).size < MAX_PER_SLOT && !waiting(Reason.SlotStart, chunk.key)) {
                return Trigger(Reason.SlotStart, slot = slot)
            }
        }
        kept.firstOrNull { fresh(it) && Math.floorMod(it.key.hashCode(), 3) == 0 && said(Reason.SlotKept, it.key, it.start).isEmpty() && !waiting(Reason.SlotKept, it.key) }?.let {
            return Trigger(Reason.SlotKept, record = it)
        }
        return null
    }

    /**
     * How soon a message whose call didn't come back (held, failed) is tried again: while it's still
     * to say, no oftener, as one due goes through the model's rests.
     */
    const val RETRY_MS = 5 * 60_000L

    /** How far ahead his next message is written (AC-10). */
    const val AHEAD_MS = 10 * 60_000L

    /** How old a draft can be when it's sent: written ahead, but not before the last plan. */
    const val DRAFT_FRESH_MS = 20 * 60_000L

    /**
     * The message he's likeliest to write next, and when (AC-10): the next slot of today's whose
     * grace into it is still to come, asked about at that moment as if nothing's done on it by then.
     * The rest (a miss, a kept slot) depend on what happens, so aren't written ahead. A slot of a running
     * [timer]'s task whose moment comes before its hold lifts is passed over for the next: its message
     * would be held, so isn't paid for.
     */
    fun upcoming(plan: Plan, settings: Settings, now: Long, timer: PieceTimer? = null): Pair<Trigger, Long>? {
        val grace = settings.felixGraceMin * 60_000L
        val slot = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null }
            .firstOrNull { it.start + grace > now && !(timer?.running == true && holds(timer, it.chunk!!.taskId, it.start + grace)) } ?: return null
        return Trigger(Reason.SlotStart, slot = slot) to slot.start + grace
    }

    /**
     * Whether [draft] is the message for [trigger] now (AC-10): written for its reason and piece, from the plan as it
     * now is ([laid], what it was written from, null where that isn't known, against [laidNow]: a slot moved, or a
     * piece kept, missed or gone since, and it would say what isn't so), not longer ago than [DRAFT_FRESH_MS], and
     * with nothing said in the chat since ([lastSaidAt]): a message of his it would repeat or contradict, or one of
     * yours it would talk past (its greeting, D-57).
     */
    fun fits(draft: FelixDraft?, trigger: Trigger, now: Long, lastSaidAt: Long?, laid: String? = null, laidNow: String? = laid): Boolean =
        draft != null && draft.reason == trigger.reason.name && draft.key == trigger.key && now - draft.at <= DRAFT_FRESH_MS && (lastSaidAt == null || lastSaidAt < draft.at) &&
            laid == laidNow

    /**
     * What a message for [trigger] is written from of [plan] and [today]'s slots on record (AC-10): its slot and today's
     * work from its start on, each piece's key and times, and what's been kept or missed today ([fits]).
     */
    fun laid(plan: Plan, trigger: Trigger, today: List<SlotRecord>): String {
        val slot = trigger.slot ?: return ""
        val work = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null && it.end > slot.start }.joinToString(";") { "${it.chunk!!.key}@${it.start}-${it.end}" }
        return "${slot.chunk?.key}@${slot.start}-${slot.end}|$work|" + today.joinToString(";") { "${it.key}:${it.outcome}" }
    }

    /**
     * When to look again (LR-03, AC-10): the earliest of a slot's grace into it, a slot's end and
     * grace (its miss then on record), and the check he set while there's work to come; and, with a
     * grace longer than [AHEAD_MS], when the next slot's message is to be written ahead (with one
     * shorter, its slot's start does that, with its plan); and when a message whose call didn't come
     * back is tried again. Null with nothing ahead. A [timer]'s hold lifting has an alarm of its own.
     */
    fun next(plan: Plan, state: FelixState, settings: Settings, now: Long, timer: PieceTimer? = null): Long? {
        val work = plan.buckets.flatMap { it.slots }.filter { it.kind == SlotKind.Work }
        val starts = work.map { it.start + settings.felixGraceMin * 60_000L }
        val ends = work.map { it.end + Slotter.GRACE_MS + MISS_LAG_MS }
        val check = state.nextCheckAt?.takeIf { work.any { it.end + Slotter.GRACE_MS > now } }
        val ahead = upcoming(plan, settings, now, timer)?.second?.minus(AHEAD_MS)?.takeIf { settings.felixGraceMin * 60_000L > AHEAD_MS }
        return (starts + ends + listOfNotNull(check, ahead, state.retry?.notBefore)).filter { it > now }.minOrNull()
    }

    /**
     * Of the completions waiting to be asked about ([waiting], LR-05), those whose work no slot kept
     * on their day saw: today's, and any from a day his messages couldn't be seen, asked once they
     * can be. One with no time is today's.
     */
    fun toAsk(
        waiting: List<AssessLater>,
        slots: List<SlotRecord>,
        now: Long,
        zone: ZoneId,
    ): List<AssessLater> {
        val keptOn = slots.filter { it.outcome.kept }.mapTo(HashSet()) { it.taskId to it.date }
        return waiting.filter { a -> (a.taskId to Instant.ofEpochMilli(a.doneAt ?: now).atZone(zone).toLocalDate().toString()) !in keptOn }
    }
}
