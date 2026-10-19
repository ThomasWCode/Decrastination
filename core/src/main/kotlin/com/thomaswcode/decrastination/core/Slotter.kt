package com.thomaswcode.decrastination.core

import java.time.LocalDate
import java.time.ZoneId

/**
 * A day's pieces laid into its windows (PL-12), each at a time: breaks between them, outside breaks
 * on a weekend or a day off school, your pins and the times kept from the last plan, a deadline's
 * time on its own day, and work for home only in the evening. Pure.
 */
object Slotter {
    private const val MINUTE = 60_000L

    /** How long after its end a slot stays where it was, its piece still to tick, before it's missed and moves (PL-13). */
    const val GRACE_MS = 10 * MINUTE

    /** The outside breaks given up when the work needs their time, by [Settings.outsideTimes]' place: midday's first, then the afternoon's, then the morning's. */
    private val GIVEN_UP = listOf(1, 2, 0)

    /** The latest a break outside starts (D-41): 18:00, whatever its time; one with no room by then isn't laid. */
    const val OUTSIDE_LATEST_MIN = 18 * 60

    /** Free time in one window, epoch millis. */
    data class Interval(val start: Long, val end: Long, val window: DayWindow) {
        val minutes: Int get() = ((end - start) / MINUTE).toInt()
    }

    /**
     * [chunks] (in the day's order) laid into [windows] on [date], around [busy]. In turn: outside
     * breaks where [outside], as many as the work laid around them leaves room for; pinned pieces ([pins], by [Chunk.key]) at their time, or the next free
     * start; pieces at the time they had ([previous]) where it's still free, one under way or just
     * over ([GRACE_MS]) included;
     * the rest, in order, at the first free time each fits whole. A piece has a break of
     * `slotBreakMin` before it and after it in its window, or after it `slotLongBreakMin` once
     * `slotLongBreakAfterMin` of work has gone without a long one. What fits nowhere is unslotted, in order.
     */
    fun slot(
        date: LocalDate,
        chunks: List<Chunk>,
        windows: List<DayWindow>,
        busy: List<Busy>,
        pins: Map<String, Long>,
        previous: Map<String, Long>,
        now: Long,
        zone: ZoneId,
        settings: Settings,
        outside: Boolean,
        deadlineMinOf: (Chunk) -> Int?,
    ): Pair<List<Slot>, List<Chunk>> {
        fun around(breaks: List<Slot>) = lay(date, chunks, windows, busy, pins, previous, now, zone, settings, breaks, deadlineMinOf)
        if (!outside || settings.outsideBreaks <= 0) return around(emptyList())
        // Outside breaks, each at or after its time, over by the next one's and started by 18:00 (so on
        // a day under way one whose time has gone isn't laid after now, nor any in the evening): as many
        // of `outsideBreaks` as the work leaves room for, the midday one given up first, then the
        // afternoon's, then the morning's.
        val free = intervals(date, windows, busy, now, zone, settings.minSchoolSlotMin)
        val room = free.sumOf { it.minutes }
        val length = settings.outsideBreakMin * MINUTE
        val latest = at(date, OUTSIDE_LATEST_MIN, zone)
        val found = mutableListOf<Pair<Int, Slot>>()
        for ((i, minute) in settings.outsideTimes.withIndex().sortedBy { it.value }) {
            val from = at(date, minute, zone)
            val by = settings.outsideTimes.filter { it > minute }.minOrNull()?.let { at(date, it, zone) } ?: Long.MAX_VALUE
            val interval = free.firstOrNull { maxOf(it.start, from) <= latest && maxOf(it.start, from) + length <= minOf(it.end, by) } ?: continue
            val start = maxOf(interval.start, from)
            found += i to Slot(SlotKind.Outside, null, start, start + length, interval.window.kind, pinned = false)
            carve(free, start, start + length)
        }
        // The work's time with its rests as they'd be laid back to back: a longer one after each run
        // of `slotLongBreakAfterMin`, as [lay] gives them.
        var run = 0
        var need = 0
        for (chunk in chunks) {
            run += chunk.minutes
            val long = run >= settings.slotLongBreakAfterMin
            need += chunk.minutes + if (long) settings.slotLongBreakMin else settings.slotBreakMin
            if (long) run = 0
        }
        // From the most that leave the work that time, fewer, till laid around them it's all slotted,
        // or no less of it than with none (time cut up around them holds less than its sum).
        val choices = (GIVEN_UP.runningFold(found.toList()) { kept, drop -> kept.filterNot { it.first == drop } } + listOf(emptyList<Pair<Int, Slot>>()))
            .distinct().filter { it.size <= settings.outsideBreaks }
        val bare by lazy { around(emptyList()) }
        for (kept in choices.dropWhile { it.isNotEmpty() && room - it.size * settings.outsideBreakMin < need }) {
            if (kept.isEmpty()) return bare
            val laid = around(kept.map { it.second })
            if (laid.second.isEmpty() || laid.second.sumOf { it.minutes } <= bare.second.sumOf { it.minutes }) return laid
        }
        return bare
    }

    /** [slot]'s pieces laid around [breaks], the outside breaks it chose. */
    private fun lay(
        date: LocalDate,
        chunks: List<Chunk>,
        windows: List<DayWindow>,
        busy: List<Busy>,
        pins: Map<String, Long>,
        previous: Map<String, Long>,
        now: Long,
        zone: ZoneId,
        settings: Settings,
        breaks: List<Slot>,
        deadlineMinOf: (Chunk) -> Int?,
    ): Pair<List<Slot>, List<Chunk>> {
        val free = intervals(date, windows, busy, now, zone, settings.minSchoolSlotMin)
        val day = at(date, 0, zone) until at(date.plusDays(1), 0, zone)
        // Each work slot with its window, for its breaks.
        val work = mutableListOf<Pair<Slot, DayWindow>>()
        val left = chunks.toMutableList()

        fun allowed(chunk: Chunk, interval: Interval) = chunk.where != Where.Home || !interval.window.kind.atSchool
        fun beforeDeadline(chunk: Chunk, start: Long): Boolean {
            val minute = deadlineMinOf(chunk) ?: return true
            return start + chunk.minutes * MINUTE <= at(date, minute, zone)
        }
        // The earliest it can start: when its task opens (a deck's cards at 04:00), on five minutes.
        fun opens(chunk: Chunk): Long = chunk.availableAt?.let(::onFive) ?: Long.MIN_VALUE
        // Where in [interval] it can start, no earlier than [after]: whole, in a window for its kind of work, before its deadline.
        fun startIn(chunk: Chunk, interval: Interval, after: Long = Long.MIN_VALUE): Long? {
            val start = maxOf(interval.start, opens(chunk), after)
            return start.takeIf { it + chunk.minutes * MINUTE <= interval.end && allowed(chunk, interval) && beforeDeadline(chunk, it) }
        }
        // The free interval holding [start] on for it, from now on (one under way, the rest of it), till
        // its grace after its end is over: a slot just over stays where it was, its piece still to tick.
        fun holding(chunk: Chunk, start: Long): Interval? {
            val end = start + chunk.minutes * MINUTE
            if (end + GRACE_MS <= now) return null
            val from = maxOf(start, onFive(now))
            // What of it is before now: clear of the slots laid and their breaks.
            val gap = settings.slotBreakMin * MINUTE
            if (start < from && work.any { (laid, _) -> laid.start < minOf(end, from) + gap && start - gap < laid.end }) return null
            if (end > from) return free.firstOrNull { it.start <= from && end <= it.end }
            // Over: the window it was in, if it's one of today's still.
            val window = windows.firstOrNull { at(date, it.startMin, zone) <= start && end <= at(date, it.endMin, zone) } ?: return null
            return Interval(start, end, window)
        }
        fun place(chunk: Chunk, start: Long, interval: Interval, pinned: Boolean) {
            val end = start + chunk.minutes * MINUTE
            // Its run: it, and the work before it in its window with no long break between.
            var run = chunk.minutes
            var from = start
            for ((before, window) in work.sortedByDescending { it.first.start }) {
                if (window != interval.window || before.end > start) continue
                if (from - before.end >= settings.slotLongBreakMin * MINUTE) break
                run += ((before.end - before.start) / MINUTE).toInt()
                from = before.start
            }
            val rest = if (run >= settings.slotLongBreakAfterMin) settings.slotLongBreakMin else settings.slotBreakMin
            work += Slot(SlotKind.Work, chunk, start, end, interval.window.kind, pinned) to interval.window
            // Its breaks, within its window (over a busy time too short to be one): past the window's
            // edges there's nothing to keep apart.
            carve(free, maxOf(start - settings.slotBreakMin * MINUTE, at(date, interval.window.startMin, zone)), minOf(end + rest * MINUTE, at(date, interval.window.endMin, zone)))
            left.remove(chunk)
        }

        // The outside breaks [slot] chose, laid first.
        for (slot in breaks) carve(free, slot.start, slot.end)

        // Pinned: at their time where it's free (one under way kept) and the piece may be done then, as
        // any piece: in a window for its kind of work, over by its deadline, its task open. Then those
        // whose time wasn't, at the next free start that holds them, so a pin moved never takes
        // another's time.
        val moved = mutableListOf<Pair<Chunk, Long>>()
        for (chunk in chunks) {
            val pin = pins[chunk.key]?.takeIf { it in day } ?: continue
            // Its time and grace gone by (its slot missed, PL-13): laid as any piece, where the last plan
            // put it, so it isn't laid again at the next five minutes at every plan and never begins.
            if (pin + chunk.minutes * MINUTE + GRACE_MS <= now) continue
            // Its piece opens at the pin itself (the time you gave), to the minute: not at the next five.
            val exact = holding(chunk, pin)?.takeIf { allowed(chunk, it) && beforeDeadline(chunk, pin) && pin >= (chunk.availableAt ?: Long.MIN_VALUE) }
            if (exact != null) place(chunk, pin, exact, pinned = true) else moved += chunk to pin
        }
        for ((chunk, pin) in moved) {
            // Free time running past the pin, from before it too, at a start no earlier than it.
            val later = free.filter { it.end > pin }.firstNotNullOfOrNull { iv -> startIn(chunk, iv, after = pin)?.let { iv to it } } ?: continue
            place(chunk, later.second, later.first, pinned = true)
        }
        // Kept where they were: a start from the last plan whose time is still free from now on,
        // one under way included (else [Plan.slotAt] would never find one).
        for (chunk in left.toList()) {
            val start = previous[chunk.key]?.takeIf { it in day } ?: continue
            val interval = holding(chunk, start) ?: continue
            if (allowed(chunk, interval) && beforeDeadline(chunk, start) && start >= opens(chunk)) place(chunk, start, interval, pinned = false)
        }
        // The rest, in order, at the first free time each fits whole.
        for (chunk in left.toList()) {
            val (interval, start) = free.firstNotNullOfOrNull { iv -> startIn(chunk, iv)?.let { iv to it } } ?: continue
            place(chunk, start, interval, pinned = false)
        }
        return (work.map { it.first } + breaks).sortedBy { it.start } to chunks.filter { it in left }
    }

    /**
     * The first start at or after [from] on [date] with [minutes] free for work done [where], around
     * [slots] already laid (a work slot with [breakMin] either side) and [busy]: for "later today"
     * (phase 6) and a missed slot (PL-13).
     */
    fun firstFreeAfter(
        slots: List<Slot>,
        windows: List<DayWindow>,
        busy: List<Busy>,
        from: Long,
        minutes: Int,
        where: Where,
        date: LocalDate,
        zone: ZoneId,
        breakMin: Int = 0,
    ): Long? {
        val free = intervals(date, windows, busy, from, zone, minSchoolMin = 0)
        for (slot in slots) {
            val gap = if (slot.kind == SlotKind.Work) breakMin * MINUTE else 0
            carve(free, slot.start - gap, slot.end + gap)
        }
        return free.firstOrNull { it.minutes >= minutes && (where != Where.Home || !it.window.kind.atSchool) }?.start
    }

    /**
     * [windows] on [date] as free intervals: [busy] taken out, none before [now] (rounded up to the
     * next five minutes), a school one with under [minSchoolMin] left and an evening's under five dropped.
     */
    private fun intervals(date: LocalDate, windows: List<DayWindow>, busy: List<Busy>, now: Long, zone: ZoneId, minSchoolMin: Int): MutableList<Interval> {
        val earliest = onFive(now)
        val merged = mutableListOf<Pair<Long, Long>>()
        for (b in busy.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && b.start <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, b.end) else merged += b.start to b.end
        }
        val result = mutableListOf<Interval>()
        for (w in windows) {
            var start = maxOf(at(date, w.startMin, zone), earliest)
            val end = at(date, w.endMin, zone)
            val pieces = mutableListOf<Interval>()
            for ((bs, be) in merged) {
                if (be <= start || bs >= end) continue
                if (bs > start) pieces += Interval(start, bs, w)
                start = maxOf(start, be)
            }
            if (start < end) pieces += Interval(start, end, w)
            result += pieces.filter { it.minutes >= if (w.kind.atSchool) minSchoolMin else 5 }
        }
        return result.sortedBy { it.start }.toMutableList()
    }

    /** [free] with [start] to [end] taken out of it. */
    private fun carve(free: MutableList<Interval>, start: Long, end: Long) {
        if (end <= start) return
        val next = mutableListOf<Interval>()
        for (iv in free) {
            if (end <= iv.start || start >= iv.end) {
                next += iv
                continue
            }
            if (iv.start < start) next += iv.copy(end = start)
            if (end < iv.end) next += iv.copy(start = end)
        }
        free.clear()
        free += next.filter { it.end > it.start }.sortedBy { it.start }
    }

    /** [time] rounded up to the next five minutes. */
    private fun onFive(time: Long): Long = Math.floorDiv(time + 5 * MINUTE - 1, 5 * MINUTE) * (5 * MINUTE)

    /** [minute] of [date] where you are, by the wall clock (a minute past midnight on the days the clocks change, as on any). */
    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long = date.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
}
