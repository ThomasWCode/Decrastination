package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.FelixState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.FelixPrompts.Reason

/**
 * When Felix writes (LR-03): only around today's work, never in quiet hours or a busy event, and
 * otherwise at the pace he sets himself (Q-31). Pure: the plan, his state and what's on record in;
 * the reason to write, if there's one, out.
 */
object FelixRules {
    /** What a message would be about: a slot under way, or one on record (missed, kept). */
    data class Trigger(val reason: Reason, val slot: Slot? = null, val record: SlotRecord? = null, val asks: List<com.thomaswcode.decrastination.data.AssessLater> = emptyList()) {
        val key: String? get() = slot?.chunk?.key ?: record?.key
        val taskId: String? get() = slot?.chunk?.taskId ?: record?.taskId
    }

    /** At most this many messages about one slot under way with nothing done: the first, then at the checks he sets. */
    const val MAX_PER_SLOT = 3

    /** A slot missed or kept, or the day done, is said only while it's fresh: not after a restart hours on. */
    const val FRESH_MS = 30 * 60_000L

    /** The evening's question about the day's completions comes from this minute of the day (LR-05). */
    const val COMPLETIONS_FROM_MIN = 19 * 60

    /** How long after a slot's end and grace its miss is surely on record. */
    private const val MISS_LAG_MS = 60_000L

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
     */
    fun due(
        plan: Plan,
        state: FelixState,
        today: List<SlotRecord>,
        sent: List<FelixRecord>,
        settings: Settings,
        now: Long,
        quiet: Boolean,
        busyNow: Boolean,
        needsPhoto: (SlotRecord) -> Boolean,
        toAsk: List<com.thomaswcode.decrastination.data.AssessLater> = emptyList(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Trigger? {
        if (quiet || busyNow) return null
        fun said(reason: Reason, key: String?, since: Long) = sent.filter { it.reason == reason.name && it.key == key && it.at >= since }
        fun fresh(record: SlotRecord) = now - record.at <= FRESH_MS
        // A slot gone by undone, or ticked without the photo it needs to earn once its time is over.
        today.firstOrNull { r ->
            fresh(r) && said(Reason.SlotEnd, r.key, r.start).isEmpty() &&
                (r.outcome == SlotOutcome.Missed || (r.outcome.kept && !r.earned && needsPhoto(r) && now >= r.end))
        }?.let { return Trigger(Reason.SlotEnd, record = it) }
        // Today's last slot kept.
        val kept = today.filter { it.outcome.kept }
        val last = kept.maxByOrNull { it.at }
        if (plan.todayBucket?.chunks.orEmpty().isEmpty() && last != null && fresh(last) && sent.none { it.reason == Reason.DayDone.name }) {
            return Trigger(Reason.DayDone, record = last)
        }
        val paced = state.nextCheckAt?.let { now < it } == true
        // Work its source says is done that the app saw none of: how it went, asked together in the evening.
        if (!paced && toAsk.isNotEmpty() && com.thomaswcode.decrastination.block.BlockPolicy.minuteOfDay(now, zone) >= COMPLETIONS_FROM_MIN && sent.none { it.reason == Reason.Completions.name }) {
            return Trigger(Reason.Completions, asks = toAsk)
        }
        // Quiet when nothing's scheduled (Q-31): no slot of today's under way or to come.
        val work = plan.todaySlots.filter { it.kind == SlotKind.Work && it.chunk != null }
        if (work.none { it.end > now }) return null
        if (paced) return null
        plan.slotAt(now)?.let { slot ->
            val chunk = slot.chunk!!
            val done = (state.activity[chunk.taskId]?.let { it >= slot.start } == true) || kept.any { it.taskId == chunk.taskId && it.at >= slot.start }
            if (!done && now >= slot.start + settings.felixGraceMin * 60_000L && said(Reason.SlotStart, chunk.key, slot.start).size < MAX_PER_SLOT) {
                return Trigger(Reason.SlotStart, slot = slot)
            }
        }
        kept.firstOrNull { fresh(it) && Math.floorMod(it.key.hashCode(), 3) == 0 && said(Reason.SlotKept, it.key, it.start).isEmpty() }?.let {
            return Trigger(Reason.SlotKept, record = it)
        }
        return null
    }

    /** [message]'s first sentence, and the rest if there's more (LR-04: the morning's title and text). */
    fun sentences(message: String): Pair<String, String?> {
        val parts = message.trim().split(Regex("(?<=[.!?])\\s+"), limit = 2)
        return parts[0] to parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * When to look again (LR-03, AC-10): the earliest of a slot's grace into it, a slot's end and
     * grace (its miss then on record), and the check he set while there's work to come. Null with
     * nothing ahead.
     */
    fun next(plan: Plan, state: FelixState, settings: Settings, now: Long): Long? {
        val work = plan.buckets.flatMap { it.slots }.filter { it.kind == SlotKind.Work }
        val starts = work.map { it.start + settings.felixGraceMin * 60_000L }
        val ends = work.map { it.end + Slotter.GRACE_MS + MISS_LAG_MS }
        val check = state.nextCheckAt?.takeIf { work.any { it.end + Slotter.GRACE_MS > now } }
        return (starts + ends + listOfNotNull(check)).filter { it > now }.minOrNull()
    }
}
