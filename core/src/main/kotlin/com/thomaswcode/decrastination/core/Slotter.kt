package com.thomaswcode.decrastination.core

import java.time.Instant
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
     * breaks where [outside], as many as the work laid around them leaves room for, one begun kept as
     * [previous] has it (D-113), one you or Felix moved or added at its time ([given], D-116), those still
     * to come around the pins; pinned pieces ([pins], by [Chunk.key],
     * the latest given first) at their time to the minute wherever it is in the day, or, under one given
     * later, before their task opens or not over by their deadline, the next free start (D-114); pieces at
     * the time they had ([previous]) where it's still free, one under way or just over ([GRACE_MS]) included;
     * the rest, in order, at the first free time each fits whole. A task's steps keep their order
     * (D-108): one not yet begun goes no earlier than the end of the steps before it, and waits
     * unslotted while one of them has no time; one pinned or under way stays where it is, and one in one
     * piece that doesn't need the steps before it (D-109) follows none of them. A piece has a break of
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
        /** The day's breaks outside you or Felix moved, took off or added (D-116). */
        given: OutsideDay = OutsideDay.NONE,
        /**
         * The pins laid at their time, and the breaks you or Felix placed (by `<date>/<key>`), as [Planner] worked them out for
         * every day at once (across midnight too); else found here, those breaks all kept.
         */
        exact: Set<String>? = null,
        deadlineMinOf: (Chunk) -> Int?,
    ): Pair<List<Slot>, List<Chunk>> {
        val length = settings.outsideBreakMin * MINUTE
        val day = at(date, 0, zone) until at(date.plusDays(1), 0, zone)
        fun windowAt(start: Long) = windows.firstOrNull { at(date, it.startMin, zone) <= start && start < at(date, it.endMin, zone) }?.kind ?: WindowKind.Evening
        fun breakOf(start: Long, end: Long, minute: Int, added: Boolean) = Slot(SlotKind.Outside, null, start, end, windowAt(start), pinned = false, outsideAt = minute, outsideAdded = added)
        // The breaks outside a plan laid today that have begun (D-113), a settings break's by its own time and one added by
        // its minute (D-116), each to its end as it was laid ([Slot.endKey]), or by its length now where no plan laid it under
        // way: one under way laid at its start till then, whatever the free time and the settings now say, never given up
        // for work; one over, completed, never laid again that day. A settings one counts towards `outsideBreaks`.
        fun begunOf(at: (String) -> Int?) = previous.mapNotNull { (key, start) ->
            at(key)?.takeIf { start in day && start <= now }?.let { it to (start to (previous[Slot.endKey(key)] ?: (start + length))) }
        }.toMap()
        val begun = begunOf(Slot::outsideAt)
        val begunAdded = begunOf(Slot::addedAt)
        val underWay = begun.filterValues { (_, end) -> end > now }.map { (minute, span) -> breakOf(span.first, span.second, minute, added = false) } +
            begunAdded.filterValues { (_, end) -> end > now }.map { (minute, span) -> breakOf(span.first, span.second, minute, added = true) }
        // Placed by you or Felix (D-116), not yet begun: a settings break moved, laid at its time to the minute, on a day
        // that has them; one added, at its own or where it was moved, any day, but not at a settings break's time on a day
        // with them. Over busy
        // time or outside the windows kept, never given up for work, nor held to 18:00; not once its time is over.
        val times = settings.outsideTimes.distinct()
        // Each over by the day's end, a break staying on its day.
        val midnight = at(date.plusDays(1), 0, zone)
        val moved = if (!outside) emptyList() else given.moved.filterKeys { it in times && it !in begun }.map { (own, minute) ->
            at(date, minute, zone).let { breakOf(it, minOf(it + length, midnight), own, added = false) }
        }
        val added = given.added.distinct().filter { it !in begunAdded && !(outside && it in times) }.map { minute ->
            at(date, given.moved[minute] ?: minute, zone).let { breakOf(it, minOf(it + length, midnight), minute, added = true) }
        }
        // Not once its time is over, but where a pin given later put it after that and it's still to come (its kept start).
        val placed = (moved + added).filter { it.end > now || (previous[it.key] ?: 0L) > now }
        // Those a pin given later covers (the planner's word), and those it put after their time: at the first free start at or
        // after their time, laid below.
        val (keptPlaced, shifted) = placed.partition { (exact == null || "$date/${it.key}" in exact) && it.end > now }
        val free = intervals(date, windows, busy, now, zone, settings.minSchoolSlotMin)
        for (slot in underWay + keptPlaced) carve(free, slot.start, slot.end)
        // Your pins laid at their time (D-114): the breaks still to come are laid around them.
        val pinned = exactPins(date, chunks, pins, now, zone, deadlineMinOf, underWay + keptPlaced, exact).first
        for ((chunk, pin) in pinned) carve(free, pin, pin + chunk.minutes * MINUTE)
        // Where the windows have no time free after it, straight after what put it off its time, a placed break being kept
        // outside them too; by the day's end, or not laid.
        val taken = ((underWay + keptPlaced).map { it.start to it.end } + pinned.map { (chunk, pin) -> pin to pin + chunk.minutes * MINUTE }).toMutableList()
        val laidAfter = shifted.mapNotNull { b ->
            val interval = free.firstOrNull { maxOf(it.start, b.start) + length <= it.end }
            if (interval != null) {
                val start = maxOf(interval.start, b.start)
                carve(free, start, start + length)
                taken += start to start + length
                return@mapNotNull b.copy(start = start, end = start + length, window = interval.window.kind)
            }
            var start = maxOf(b.start, now)
            while (true) start = taken.filter { (from, to) -> from < start + length && start < to }.maxOfOrNull { it.second } ?: break
            if (start + length > midnight) return@mapNotNull null
            taken += start to start + length
            b.copy(start = start, end = start + length, window = windowAt(start))
        }
        val fixed = underWay + keptPlaced + laidAfter
        fun around(breaks: List<Slot>) = lay(date, chunks, windows, busy, pins, previous, now, zone, settings, fixed + breaks, exact, deadlineMinOf)
        // Those still to come: as many as `outsideBreaks` leaves, those begun and moved counted, the added ones over and above.
        val left = settings.outsideBreaks - begun.size - moved.size
        if (!outside || left <= 0 || given.noneAtAll) return around(emptyList())
        // Outside breaks still to come, each at or after its time, over by the next one's and started by 18:00 (so
        // on a day under way one whose time has gone, none laid, is laid from now, and none in the evening): as many
        // of them as the work leaves room for, the midday one given up first, then the afternoon's, then the morning's.
        // Not one moved (laid above) nor one taken off (D-116).
        val room = free.sumOf { it.minutes }
        val latest = at(date, OUTSIDE_LATEST_MIN, zone)
        val found = mutableListOf<Pair<Int, Slot>>()
        // A time given twice is one break: its key is its time.
        for ((i, minute) in settings.outsideTimes.withIndex().distinctBy { it.value }.sortedBy { it.value }) {
            if (minute in begun || minute in given.moved || minute in given.off) continue
            val from = at(date, minute, zone)
            val by = settings.outsideTimes.filter { it > minute }.minOrNull()?.let { at(date, it, zone) } ?: Long.MAX_VALUE
            val interval = free.firstOrNull { maxOf(it.start, from) <= latest && maxOf(it.start, from) + length <= minOf(it.end, by) } ?: continue
            val start = maxOf(interval.start, from)
            found += i to Slot(SlotKind.Outside, null, start, start + length, interval.window.kind, pinned = false, outsideAt = minute)
            carve(free, start, start + length)
        }
        // The work's time with its rests as they'd be laid back to back: a longer one after each run
        // of `slotLongBreakAfterMin`, as [lay] gives them, each task's steps in their order; the pins aside, their time
        // already out of the room.
        var run = 0
        var need = 0
        for (chunk in stepsInOrder(chunks).filter { c -> pinned.none { it.first == c } }) {
            run += chunk.minutes
            val long = run >= settings.slotLongBreakAfterMin
            need += chunk.minutes + if (long) settings.slotLongBreakMin else settings.slotBreakMin
            if (long) run = 0
        }
        // From the most that leave the work that time, fewer, till laid around them it's all slotted,
        // or no less of it than with none (time cut up around them holds less than its sum).
        val choices = (GIVEN_UP.runningFold(found.toList()) { kept, drop -> kept.filterNot { it.first == drop } } + listOf(emptyList<Pair<Int, Slot>>()))
            .distinct().filter { it.size <= left }
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
        exact: Set<String>?,
        deadlineMinOf: (Chunk) -> Int?,
    ): Pair<List<Slot>, List<Chunk>> {
        val free = intervals(date, windows, busy, now, zone, settings.minSchoolSlotMin)
        val day = at(date, 0, zone) until at(date.plusDays(1), 0, zone)
        val ordered = stepsInOrder(chunks)
        // Each work slot with its window, for its breaks.
        val work = mutableListOf<Pair<Slot, DayWindow>>()
        val left = ordered.toMutableList()
        // Tasks whose steps' times from the last plan aren't in the steps' order (a plan before D-108): laid afresh.
        // Steps pinned today or begun are left out: they stay where they are, ahead of the steps before them or not;
        // so are those in one piece that don't need the steps before them (D-109).
        val shuffled = ordered.filter { it.stepIndex != null }.groupBy { it.taskId }.filterValues { steps ->
            val times = steps.filter { step -> !step.free && pins[step.key]?.takeIf { it in day } == null }.mapNotNull { step -> previous[step.key]?.takeIf { it in day && it > now } }
            times != times.sorted()
        }.keys

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
        // The run [slot] is in among the work laid in [window]: it, and the work either side of it there with no long
        // break between, by their start.
        fun runOf(slot: Slot, window: DayWindow): List<Slot> {
            val slots = (work.filter { it.second == window }.map { it.first } + slot).distinct().sortedBy { it.start }
            val i = slots.indexOf(slot)
            var from = i
            while (from > 0 && slots[from].start - slots[from - 1].end < settings.slotLongBreakMin * MINUTE) from--
            var to = i
            while (to < slots.lastIndex && slots[to + 1].start - slots[to].end < settings.slotLongBreakMin * MINUTE) to++
            return slots.subList(from, to + 1)
        }
        // Whether laid at [start] in [interval]'s window, [chunk] would leave work going on past `slotLongBreakAfterMin`
        // with no long break (D-40): a run's long break is laid after its last piece, so laid before work there already
        // (a kept start, a pin), no piece of its run but the last may reach it.
        fun overruns(chunk: Chunk, start: Long, interval: Interval): Boolean {
            var minutes = 0
            for (laid in runOf(Slot(SlotKind.Work, chunk, start, start + chunk.minutes * MINUTE, interval.window.kind, pinned = false), interval.window).dropLast(1)) {
                minutes += ((laid.end - laid.start) / MINUTE).toInt()
                if (minutes >= settings.slotLongBreakAfterMin) return true
            }
            return false
        }
        // Where in [interval] [chunk] can start from [start] on with the long break kept (D-40): [start], or, where that
        // joins it to the run before and the run's work goes past the rule, the first start a long break after that run,
        // where it still fits. No other later start helps: each brings it nearer the work after.
        fun withBreaks(chunk: Chunk, start: Long, interval: Interval): Long? {
            if (!overruns(chunk, start, interval)) return start
            val before = work.filter { it.second == interval.window && it.first.end <= start }.maxOfOrNull { it.first.end } ?: return null
            val later = startIn(chunk, interval, after = before + settings.slotLongBreakMin * MINUTE) ?: return null
            return later.takeIf { it > start && !overruns(chunk, it, interval) }
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
            val laid = Slot(SlotKind.Work, chunk, start, end, interval.window.kind, pinned)
            work += laid to interval.window
            // Its breaks, within its window (over a busy time too short to be one): past the window's
            // edges there's nothing to keep apart.
            carve(free, maxOf(start - settings.slotBreakMin * MINUTE, at(date, interval.window.startMin, zone)), minOf(end + rest * MINUTE, at(date, interval.window.endMin, zone)))
            // Laid before work there already (a kept start, a pin): the run that work ends may now need its long break
            // after its last piece, carved there too.
            val around = runOf(laid, interval.window)
            val last = around.last()
            if (last != laid && around.sumOf { ((it.end - it.start) / MINUTE).toInt() } >= settings.slotLongBreakAfterMin) {
                carve(free, last.end, minOf(last.end + settings.slotLongBreakMin * MINUTE, at(date, interval.window.endMin, zone)))
            }
            left.remove(chunk)
        }
        // The earliest a step can start (D-108): the end of the last of its task's steps before it today,
        // once each of them is laid; null while one isn't. A piece with no step of its own, any time, and a
        // step in one piece that doesn't need the steps before it (D-109).
        fun follows(chunk: Chunk): Long? {
            if (chunk.stepIndex == null || chunk.free) return Long.MIN_VALUE
            var from = Long.MIN_VALUE
            for (before in ordered) {
                if (before.taskId != chunk.taskId || before.stepIndex == null || before.part >= chunk.part) continue
                from = maxOf(from, work.firstOrNull { it.first.chunk == before }?.first?.end ?: return null)
            }
            return from
        }

        // Where a pin is laid at its time ([place]'s interval): in the free interval holding it, so its breaks are kept
        // within its window; else over its own span in the window it overlaps most; or, outside every window, in a window
        // of its own span, of the kind of the window nearest it in time, so its record counts as that kind's (D-114) and
        // no break is carved outside the windows.
        fun pinnedIn(chunk: Chunk, pin: Long): Interval {
            val end = pin + chunk.minutes * MINUTE
            val from = maxOf(pin, onFive(now))
            free.firstOrNull { it.start <= from && end <= it.end }?.let { return it }
            fun overlap(w: DayWindow) = minOf(at(date, w.endMin, zone), end) - maxOf(at(date, w.startMin, zone), pin)
            windows.filter { overlap(it) > 0 }.maxByOrNull(::overlap)?.let { return Interval(pin, end, it) }
            fun distance(w: DayWindow) = maxOf(at(date, w.startMin, zone) - end, pin - at(date, w.endMin, zone))
            val kind = windows.minByOrNull(::distance)?.kind ?: WindowKind.Evening
            val wall = Instant.ofEpochMilli(pin).atZone(zone).toLocalTime()
            val minute = wall.hour * 60 + wall.minute
            return Interval(pin, end, DayWindow(minute, minute + chunk.minutes, kind))
        }

        // The outside breaks [slot] chose, laid first.
        for (slot in breaks) carve(free, slot.start, slot.end)

        // Pinned ([exactPins]): each at its time, to the minute, wherever it is in the day (D-114); then those a pin given
        // later covers, or that may not be done then, at the next free start that holds them, so a pin moved never takes
        // another's time.
        val (kept, moved) = exactPins(date, chunks, pins, now, zone, deadlineMinOf, breaks, exact)
        for ((chunk, pin) in kept) {
            place(chunk, pin, pinnedIn(chunk, pin), pinned = true)
            // All of its time, whichever window it's counted in: one across two windows has its breaks kept in one.
            carve(free, pin, pin + chunk.minutes * MINUTE)
        }
        for ((chunk, pin) in moved) {
            // Free time running past the pin, from before it too, at a start no earlier than it.
            val later = free.filter { it.end > pin }.firstNotNullOfOrNull { iv -> startIn(chunk, iv, after = pin)?.let { iv to it } } ?: continue
            place(chunk, later.second, later.first, pinned = true)
        }
        // Kept where they were: a start from the last plan whose time is still free from now on,
        // one under way included (else [Plan.slotAt] would never find one). A step not yet begun only
        // after the steps before it, and not where its task's steps were laid out of their order.
        for (chunk in left.toList()) {
            val start = previous[chunk.key]?.takeIf { it in day } ?: continue
            val interval = holding(chunk, start) ?: continue
            if (start > now && (chunk.taskId in shuffled || start < (follows(chunk) ?: continue))) continue
            if (allowed(chunk, interval) && beforeDeadline(chunk, start) && start >= opens(chunk) && (start <= now || !overruns(chunk, start, interval))) place(chunk, start, interval, pinned = false)
        }
        // The rest, in order, at the first free time each fits whole: a step after the steps before it, at
        // its start from the last plan where that's still free and follows them (one before it moved, say).
        for (chunk in left.toList()) {
            val after = follows(chunk) ?: continue
            val kept = previous[chunk.key]?.takeIf { chunk.stepIndex != null && chunk.taskId !in shuffled && it in day && it >= after }
                ?.let { start -> holding(chunk, start)?.takeIf { allowed(chunk, it) && beforeDeadline(chunk, start) && start >= opens(chunk) && (start <= now || !overruns(chunk, start, it)) }?.let { it to start } }
            val (interval, start) = kept ?: free.firstNotNullOfOrNull { iv -> startIn(chunk, iv, after)?.let { withBreaks(chunk, it, iv) }?.let { iv to it } } ?: continue
            place(chunk, start, interval, pinned = false)
        }
        return (work.map { it.first } + breaks).sortedBy { it.start } to ordered.filter { it in left }
    }

    /**
     * [pins] on [date] laid at their time, and those that go to the next free start after it (D-114), in [pins]' order, the
     * latest given first: each at its time, to the minute, wherever it is in the day (over busy time, outside the windows,
     * in a window not for its kind of work), where its time overlaps no pin laid before it and none of [breaks] (a break
     * outside under way is never given up for work, D-113), its piece is over by its deadline and its task is open by then
     * (to the minute: the time you gave, not the next five); one under way stays. Where [exact] is given (the planner's, worked out
     * for every day at once, across midnight too), those it names.
     * One whose time and grace have gone by (its slot missed, PL-13) is in neither: laid as any piece, where the last plan
     * put it, so it isn't laid again at the next five minutes at every plan and never begins.
     */
    private fun exactPins(
        date: LocalDate,
        chunks: List<Chunk>,
        pins: Map<String, Long>,
        now: Long,
        zone: ZoneId,
        deadlineMinOf: (Chunk) -> Int?,
        breaks: List<Slot>,
        given: Set<String>?,
    ): Pair<List<Pair<Chunk, Long>>, List<Pair<Chunk, Long>>> {
        val day = at(date, 0, zone) until at(date.plusDays(1), 0, zone)
        val exact = mutableListOf<Pair<Chunk, Long>>()
        val moved = mutableListOf<Pair<Chunk, Long>>()
        for ((key, pin) in pins) {
            if (pin !in day) continue
            val chunk = chunks.firstOrNull { it.key == key } ?: continue
            val end = pin + chunk.minutes * MINUTE
            if (end + GRACE_MS <= now) continue
            if (given != null) {
                if (key in given) exact += chunk to pin else moved += chunk to pin
                continue
            }
            val clear = exact.none { (other, at) -> at < end && pin < at + other.minutes * MINUTE } && breaks.none { it.start < end && pin < it.end }
            val due = deadlineMinOf(chunk)?.let { end <= at(date, it, zone) } ?: true
            if (clear && due && pin >= (chunk.availableAt ?: Long.MIN_VALUE)) exact += chunk to pin else moved += chunk to pin
        }
        return exact to moved
    }

    /**
     * A step in one piece that doesn't need the steps before it (D-109): free of their order. One in sittings
     * isn't: a sitting kept is counted on the task's steps in their order, as minutes worked ([Planner]), so
     * done ahead of them it would be counted on the wrong one; kept whole, a step is ticked itself.
     */
    private val Chunk.free: Boolean get() = anyOrder && sitting == null

    /** [chunks] with each task's steps in their order ([Chunk.part]), in the places its steps have among them: the day's order between tasks kept. */
    private fun stepsInOrder(chunks: List<Chunk>): List<Chunk> {
        val steps = chunks.filter { it.stepIndex != null }.groupBy { it.taskId }.mapValues { (_, own) -> own.sortedBy { it.part }.iterator() }
        return chunks.map { if (it.stepIndex == null) it else steps.getValue(it.taskId).next() }
    }

    /**
     * The first start at or after [from] on [date] with [minutes] free for work done [where], around
     * [slots] already laid (a work slot with [breakMin] either side) and [busy]: for "later today"
     * (UI-03) and a missed slot (PL-13).
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
     * The free times at school on [date] that [slots] leave, from [now] on (D-110): its school windows less
     * [busy] and each work slot with its breaks as [lay] keeps them (`slotBreakMin` before it, and after it
     * that or `slotLongBreakMin` once its run reaches `slotLongBreakAfterMin`), those of at least `minSchoolSlotMin`.
     */
    fun schoolGaps(date: LocalDate, windows: List<DayWindow>, busy: List<Busy>, slots: List<Slot>, now: Long, zone: ZoneId, settings: Settings): List<Gap> {
        val free = intervals(date, windows.filter { it.kind.atSchool }, busy, now, zone, settings.minSchoolSlotMin)
        val work = slots.filter { it.kind == SlotKind.Work }.sortedBy { it.start }
        // Each slot's window by its times, as a run goes on only within one ([lay]'s, not its kind's).
        fun windowOf(slot: Slot): DayWindow? = windows.firstOrNull { at(date, it.startMin, zone) <= slot.start && slot.end <= at(date, it.endMin, zone) }
        for (slot in slots) {
            if (slot.kind != SlotKind.Work) {
                carve(free, slot.start, slot.end)
                continue
            }
            // Its run: it, and the work before it in its window with no long break between, as [lay] counts it.
            var run = ((slot.end - slot.start) / MINUTE).toInt()
            var from = slot.start
            val window = windowOf(slot)
            for (before in work.filter { it.end <= slot.start && windowOf(it) == window }.asReversed()) {
                if (from - before.end >= settings.slotLongBreakMin * MINUTE) break
                run += ((before.end - before.start) / MINUTE).toInt()
                from = before.start
            }
            val rest = if (run >= settings.slotLongBreakAfterMin) settings.slotLongBreakMin else settings.slotBreakMin
            // Within its window: past its edges there's nothing to keep apart, as [lay] has it.
            val opens = window?.let { at(date, it.startMin, zone) } ?: Long.MIN_VALUE
            val closes = window?.let { at(date, it.endMin, zone) } ?: Long.MAX_VALUE
            carve(free, maxOf(slot.start - settings.slotBreakMin * MINUTE, opens), minOf(slot.end + rest * MINUTE, closes))
        }
        return free.filter { it.minutes >= settings.minSchoolSlotMin }.map { Gap(it.start, it.minutes) }
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
