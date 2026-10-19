package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Blocklist
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BlockRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Keeping
import com.thomaswcode.decrastination.data.PhotoDone
import com.thomaswcode.decrastination.data.Rewarded
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.core.AnkiRules
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.thomaswcode.decrastination.ui.Format
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The blocker's state and decisions, apart from Android: what a blocked app or site meets now
 * (the plan, the policy, earned time), and the bookkeeping that follows from work being done:
 * slots kept, missed and moved (LR-09), photo checks, completions. The focus service asks it on
 * every app change; the Plan and the block screen keep slots through it.
 */
class Focus(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val log: JsonStore<ActivityLog>,
    private val clock: WallClock,
    /** One of your own whose work a kept slot finished (LR-09): finished, as Done does. */
    private val onOwnTaskDone: suspend (String) -> Unit = {},
    /** Whether [LocalDate] is a school day (BL-02): by the school calendar; without one, Monday to Friday. */
    private val schoolDay: (LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 },
    private val planOf: (TaskState, Settings, Long) -> Plan,
) {
    /** What the blocker covers. */
    sealed interface Target {
        val name: String

        data class App(override val name: String) : Target
        data class Browser(override val name: String) : Target
        data class Site(override val name: String, val browser: String) : Target
    }

    private var cachedFor: Pair<TaskState, Settings>? = null
    private var cachedAt = 0L
    private var cachedPlan: Plan? = null

    /** The plan, worked out again only when the tasks or settings change, or a minute has passed. */
    fun plan(now: Long = clock.now()): Plan {
        val key = tasks.value to settings.value
        val cached = cachedPlan
        if (cached != null && cachedFor?.first === key.first && cachedFor?.second === key.second && now - cachedAt in 0 until PLAN_TTL_MS) return cached
        return planOf(key.first, key.second, now).also {
            cachedPlan = it
            cachedFor = key
            cachedAt = now
        }
    }

    /** Drops the plan kept for the minute: what it was made from beside the tasks and settings (the calendar) has changed. */
    fun forgetPlan() {
        cachedPlan = null
    }

    fun today(now: Long = clock.now()): LocalDate = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate()

    /** Whether a blocked app may be in front now. */
    fun verdict(now: Long = clock.now()): BlockPolicy.Verdict {
        val state = runtime.value
        return BlockPolicy.decide(
            BlockPolicy.Input(
                now = now,
                zone = clock.zone(),
                settings = settings.value,
                pressure = plan(now).pressure || missedKeepsPressure(now),
                creditLeftMs = creditLeftMs(now),
                overrideUntil = state.overrideUntil,
                forceActiveUntil = state.forceActiveUntil,
                schoolDay = schoolDay(today(now)),
                gapUntil = gapUntil(now),
            ),
        )
    }

    /**
     * The end of the gap after a slot kept today that [now] is in (BL-10), if it's in one. None once
     * a slot's been missed since: the gap ended when that one began, and its new time opens no other.
     */
    fun gapUntil(now: Long = clock.now()): Long? {
        val today = today(now).toString()
        val records = log.value.slots.filter { it.date == today }
        val keptAt = records.filter { it.outcome.kept }.maxOfOrNull { it.at } ?: return null
        if (records.any { it.outcome == SlotOutcome.Missed && it.at > keptAt }) return null
        return plan(now).gapUntil(now, keptAt)
    }

    /**
     * How long free time can run from [now]: what's left of it, and in a gap between slots (BL-10)
     * no longer than till the next slot starts.
     */
    fun spendableMs(now: Long = clock.now(), spentMs: Long = 0): Long {
        val credit = creditLeftMs(now) - spentMs
        val gap = gapUntil(now)?.takeIf { plan(now).pressure || missedKeepsPressure(now) }?.let { it - now } ?: Long.MAX_VALUE
        return minOf(credit, gap)
    }

    /**
     * A day with nothing to do starts with free time (BL-07): `freeAllowanceMin`, once, when nothing
     * is planned today or overdue, no slot of today's was kept, missed or moved, and the morning's
     * record of the day, if made yet, planned nothing. A day emptied by finishing its work, or by
     * moving it away, earned or chose what it has. Only once each source has been read today: the
     * list from before (the app just started, a sync not run yet) may not hold today's work. One
     * never read (not set up) holds none. And not before what a stop left part-way is finished.
     */
    suspend fun grantAllowanceIfDue(now: Long = clock.now()) {
        val minutes = settings.value.freeAllowanceMin
        if (minutes <= 0) return
        val today = today(now)
        val day = today.toString()
        if (runtime.value.credit.on(today).allowanceOn == day) return
        // Not while a stop's left work part-way recorded (a slot kept, a photo, a completion): it's finished first.
        if (runtime.value.keeping.isNotEmpty() || runtime.value.photosDone.isNotEmpty() || tasks.value.unrewarded.isNotEmpty()) return
        val dayStart = today.atStartOfDay(clock.zone()).toInstant().toEpochMilli()
        if (Source.SYNCED.any { source -> tasks.value.status(source).lastSuccessAt?.let { it < dayStart } == true }) return
        val plan = plan(now)
        if (plan.todayBucket?.chunks.orEmpty().isNotEmpty() || plan.ordered.any { it.overdue }) return
        if (log.value.slots.any { it.date == day } || log.value.days.any { it.date == day && it.plannedMin > 0 }) return
        runtime.update { state ->
            val credit = state.credit.on(today)
            if (credit.allowanceOn == day) state else state.copy(credit = credit.copy(earnedMs = credit.earnedMs + minutes * 60_000L, allowanceOn = day))
        }
    }

    /**
     * What finishing today earns, where the block meets you (BL-04): today's pieces still to do,
     * their minutes, and the free time they'd earn: the short ones' on their tick, the longer ones'
     * with a photo. Once today's done, the free time there is; on a day with nothing to do, its
     * allowance (BL-07). Null with nothing planned and none to spend.
     */
    fun rewardLine(plan: Plan, now: Long = clock.now()): String? {
        val pieces = plan.todayBucket?.chunks.orEmpty()
        val s = settings.value
        if (pieces.isEmpty()) {
            val free = (creditLeftMs(now) / 60_000L).toInt()
            if (free <= 0) return null
            val empty = runtime.value.credit.on(today(now)).allowanceOn == today(now).toString()
            return if (empty) "Nothing planned today: ${Format.minutes(free)} free." else "Today's plan is done. ${Format.minutes(free)} free."
        }
        fun earned(of: List<Chunk>) = Credit.forSlot(of.sumOf { it.minutes }, s.workMinPerFreeMin).roundToInt()
        val by = pieces.groupBy(::earnsBy)
        val head = if (pieces.size == 1) "Finish today's slot" else "Finish today's ${pieces.size} slots"
        val onTick = by[EarnsBy.Tick]?.let(::earned)?.takeIf { it > 0 }?.let { "${Format.minutes(it)} free" }
        val photo = by[EarnsBy.Photo]?.let { long ->
            val what = if (long.size == 1) "with a photo of ${long.single().label.take(MAX_LABEL)}" else "with photos of ${long.size} of them"
            "${Format.minutes(earned(long))} ${if (onTick != null) "more" else "free"} $what"
        }
        val confirmed = by[EarnsBy.Source]?.let { long ->
            val what = if (long.size == 1) "once ${long.single().source.label} says ${long.single().label.take(MAX_LABEL)} is done" else "once their sources say ${long.size} of them are done"
            "${Format.minutes(earned(long))} ${if (onTick != null || photo != null) "more" else "free"} $what"
        }
        val parts = listOfNotNull(onTick, photo, confirmed)
        val line = "$head (${Format.minutes(pieces.sumOf { it.minutes })})"
        return if (parts.isEmpty()) line else "$line → ${parts.joinToString(", ")}"
    }

    /** What lets a kept slot of [piece] earn (BL-08), by the settings. */
    fun earnsBy(piece: Chunk): EarnsBy = piece.earnsBy(settings.value.photoFromMin)

    /**
     * A slot missed today that went to tomorrow keeps today's work undone (BL-11), till the day's
     * hours end: letting a slot lapse doesn't buy the evening. A move of yours forgets it (PL-13).
     */
    fun missedKeepsPressure(now: Long = clock.now()): Boolean {
        val today = today(now)
        if (runtime.value.slots.on(today).missed.none { it.spilled }) return false
        val hours = if (schoolDay(today)) settings.value.weekdayHours else settings.value.weekendHours
        return BlockPolicy.minuteOfDay(now, clock.zone()) < hours.endMin
    }

    /** [pkg] as something the blocker covers outright (an app, or a browser whose sites can't be read). */
    fun target(pkg: String): Target? {
        val s = settings.value
        return when (pkg) {
            in s.blockedApps -> Target.App(pkg)
            in s.blockedBrowsers -> Target.Browser(pkg)
            else -> null
        }
    }

    fun isCheckedBrowser(pkg: String): Boolean = pkg in settings.value.checkedBrowsers

    /**
     * Of [browsers] whose address bar can't be read, those that leaves uncovered: read for sites, and
     * not covered outright. One blocked outright (Settings' fix) is covered at once, though its taking
     * off the checked list waits while armed, as a loosening does.
     */
    fun uncovered(browsers: Set<String>): List<String> = browsers.filter { isCheckedBrowser(it) && target(it) == null }

    /** The blocked site a checked browser's address bar shows, if any. */
    fun siteTarget(browser: String, addressBar: String?): Target.Site? {
        val address = addressBar?.let(Blocklist::address) ?: return null
        return Blocklist.blockedSite(address, settings.value.blockedSites)?.let { Target.Site(it, browser) }
    }

    /** Spent but not yet saved ([spendSoon]). */
    private val unsavedMs = AtomicLong()

    /** Today's free time left, less what was spent a moment ago and is still being saved. */
    fun creditLeftMs(now: Long = clock.now()): Long = (runtime.value.credit.on(today(now)).leftMs - unsavedMs.get()).coerceAtLeast(0)

    suspend fun spend(ms: Long, day: LocalDate = today()) {
        if (ms <= 0) return
        runtime.update { it.copy(credit = it.credit.spend(day, ms)) }
    }

    /**
     * Takes [ms] off today's free time, saved on [scope] (the app's, which outlives the save).
     * What's left says so at once: a check made before the save lands (moving straight from one
     * blocked app to another) mustn't be given the time again.
     */
    fun spendSoon(ms: Long, scope: CoroutineScope): Job? {
        if (ms <= 0) return null
        val day = today()
        unsavedMs.addAndGet(ms)
        return scope.launch {
            try {
                spend(ms, day)
            } finally {
                unsavedMs.addAndGet(-ms)
            }
        }
    }

    suspend fun recordBlock(target: Target, reason: BlockPolicy.Reason) {
        val now = clock.now()
        log.update { it.copy(blocks = it.blocks + BlockRecord(now, target.name, reason.name)).trimmed(now) }
    }

    /**
     * Where in [task]'s list the step a kept slot or photo check was on is, to tick: the one at [index]
     * if it's still that step and still to do; none if the list has changed under it, rather than
     * another of the same [title]. With no index (one saved before they were kept), the first still
     * to do of that title. -1 for none.
     */
    private fun stepAt(task: TaskItem, index: Int?, title: String): Int {
        if (index == null) return task.subSteps.indexOfFirst { !it.done && it.title == title }
        val step = task.subSteps.getOrNull(index) ?: return -1
        return if (!step.done && step.title == title) index else -1
    }

    /**
     * [key]'s piece done (LR-09): its step ticked and its minutes worked (a task in boxes, its minutes
     * worked), with [extraMin] more you say it took; the slot recorded kept, or kept late once its
     * grace is over; its free time earned where it's a short piece, or [photo] shows it done, or
     * [verified] its source (AnkiDroid's cards counted) (BL-08), never past today's plan; and its
     * start no longer kept for the next plan. A piece ticked ahead of its slot, or with none, is kept
     * all the same. [seenAt]: when the plan it was ticked on was made, so a second tap on it (a task
     * in boxes has its next box under the same key) keeps nothing more. False when there's no such
     * piece to keep.
     */
    suspend fun keepSlot(key: String, extraMin: Int = 0, photo: Boolean = false, verified: Boolean = false, seenAt: Long? = null, now: Long = clock.now()): Boolean {
        val kept = keeping.withLock { keep(key, extraMin, photo, verified, seenAt, now) } ?: return false
        // One of your own it finished: finished as Done does, once its record is in.
        kept.ownDone?.let { onOwnTaskDone(it) }
        return true
    }

    /** Held while a slot's outcome is recorded: two ticks at once keep a slot once, and a tick and its slot's end don't cross. */
    private val keeping = Mutex()

    /** What keeping a slot leaves to do: one of your own it finished, or one done by its tick (NF-06). */
    private class Kept(val ownDone: String?)

    private suspend fun keep(key: String, extraMin: Int, photo: Boolean, verified: Boolean, seenAt: Long?, now: Long, photoDone: PhotoDone? = null): Kept? {
        val plan = plan(now)
        val slot = (plan.todaySlots + plan.buckets.flatMap { it.slots }).firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == key }
        val chunk = slot?.chunk ?: plan.ordered.firstOrNull { it.key == key } ?: return null
        // Kept since you saw it (a second tap, a sheet left open), or moments ago (taps in a row, the
        // screen drawn again between them): a box's next piece has its key now.
        if (seenAt != null && log.value.slots.any { it.key == key && it.outcome.kept && it.at >= minOf(seenAt, now - AGAIN_MS) }) return null
        val task = tasks.value.tasks.firstOrNull { it.id == chunk.taskId && it.isOpen } ?: return null
        // Its step, where it's still that one and still to do (none for a box of time, or the whole task).
        val index = chunk.stepIndex?.let { i -> stepAt(task, i, chunk.step.orEmpty()).takeIf { it >= 0 } ?: return null }
        // A sitting before its step's last ticks nothing: its minutes are worked on the step, as a
        // session stopped early's are. The last ticks it, with the step's sittings not kept before it
        // (ticked ahead, or by the chat), as one piece of it would have been (3.1.2's review).
        val ticks = index?.takeIf { chunk.ticks != null }
        val rest = if (ticks == null) 0 else plan.chunksOf(chunk.taskId).filter { it.stepIndex == chunk.stepIndex && it.key != key }.sumOf { it.minutes }
        val worked = chunk.minutes + rest + extraMin.coerceAtLeast(0)
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        // A slot of it missed today (it was moved on): this is that slot, kept late, where it was laid, and today's.
        val missed = log.value.slots.lastOrNull { it.key == key && it.date == today.toString() && it.outcome == SlotOutcome.Missed }
        // A piece of a later day's, ticked ahead, earns nothing today (BL-08: today's plan, not more).
        val laidToday = missed != null || today(slot?.start ?: now) == today
        val earns = (photo || verified || earnsBy(chunk) == EarnsBy.Tick) && laidToday
        val record = SlotRecord(
            date = missed?.date ?: today(slot?.start ?: now).toString(),
            key = key,
            taskId = chunk.taskId,
            kind = chunk.kind,
            className = task?.className,
            label = chunk.label,
            plannedMin = chunk.minutes,
            start = missed?.start ?: slot?.start ?: now,
            end = missed?.end ?: slot?.end ?: now,
            window = missed?.window ?: slot?.window ?: WindowKind.Evening,
            // Late: missed first, or past its slot's grace.
            outcome = if (missed != null || runtime.value.slots.on(today).missed.any { it.key == key } || (slot != null && now > slot.end + Slotter.GRACE_MS)) SlotOutcome.KeptLate else SlotOutcome.Kept,
            workedMin = worked,
            photo = photo,
            earned = earns,
            at = now,
        )
        val earning = if (earns) minOf(Credit.forSlot(chunk.minutes, ratio), slotRoomMin(now, ratio, keeping = record)) else 0.0
        // On record before anything changes: a stop part-way is carried through at start-up.
        val journal = Keeping(record, ticks, chunk.step, task.workedMin, earning, replaces = missed, photo = photoDone)
        runtime.update { it.copy(keeping = it.keeping + journal) }
        return finish(journal, now, recovering = false)
    }

    /**
     * [k] carried through (LR-09), each step once however often it's run: the task's step ticked and
     * its minutes worked, unless that's been done (its minutes moved on by just these); the slot's
     * record, a miss of it today giving way, unless ([recovering] after a stop) it's in; then, in one
     * write with the journal's end, its free time (on its own day only) and its start let go. Null,
     * and the journal let go, when the task isn't as it was: changed since, or done.
     */
    private suspend fun finish(k: Keeping, now: Long, recovering: Boolean): Kept? {
        val record = k.record
        // A slot kept already, earning now: nothing on the task.
        var carried = k.earnOnly
        if (!k.earnOnly) tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != record.taskId) return@map t
                    val step = k.stepIndex?.let { t.subSteps.getOrNull(it) }
                    when {
                        // Done before a stop: only the rest is left.
                        t.workedMin == k.workedBefore + record.workedMin && (k.stepIndex == null || step?.done == true) -> t.also { carried = true }
                        !t.isOpen || t.workedMin != k.workedBefore -> t
                        // A box of time (or the whole task): its minutes worked.
                        k.stepIndex == null -> t.copy(workedMin = t.workedMin + record.workedMin).also { carried = true }
                        // Its step, done, and its minutes worked: so more than the step's own come off the next steps.
                        step == null || step.done || step.title != k.step -> t
                        else -> t.copy(
                            subSteps = t.subSteps.mapIndexed { j, s -> if (j == k.stepIndex) s.copy(done = true, timedMin = record.workedMin) else s },
                            workedMin = t.workedMin + record.workedMin,
                        ).also { carried = true }
                    }
                },
            )
        }
        if (!carried) {
            runtime.update { it.copy(keeping = it.keeping - k) }
            return null
        }
        // A slot of it missed today and kept after all is one slot, kept late: that miss gives way.
        // Earning only, the record it was is marked; gone since (marked by another photo, trimmed), nothing's given.
        var recorded = true
        log.update { state ->
            when {
                recovering && state.slots.any { it.key == record.key && it.at == record.at && it.outcome.kept && (!k.earnOnly || it.earned) } -> state
                k.earnOnly -> if (k.replaces != null && k.replaces in state.slots) state.copy(slots = state.slots.map { if (it == k.replaces) record else it }) else state.also { recorded = false }
                else -> state.copy(slots = state.slots.filterNot { it == k.replaces } + record).trimmed(now)
            }
        }
        if (!recorded) {
            runtime.update { it.copy(keeping = it.keeping - k) }
            return null
        }
        val today = today(now)
        val day = today(record.at)
        runtime.update { state ->
            if (k !in state.keeping) {
                state
            } else {
                state.copy(
                    credit = if (k.earningMin > 0 && day == today) state.credit.earn(today, k.earningMin) else state.credit.on(today),
                    slots = if (day == today && !k.earnOnly) state.slots.on(today).kept(record.key) else state.slots,
                    keeping = state.keeping - k,
                    photosDone = k.photo?.let { state.photosDone - it } ?: state.photosDone,
                )
            }
        }
        // One of your own finished by it: its last step (seen to as its steps are), or its minutes all
        // worked; so too one done by its tick (NF-06).
        val task = tasks.value.tasks.firstOrNull { it.id == record.taskId }.takeUnless { k.earnOnly }
        return Kept(task?.takeIf { (it.source == Source.Own || it.tickDone) && it.isOpen && it.subSteps.isEmpty() && Planner.remaining(it, runtime.value.calibration.multiplierFor(it)) <= 0 }?.id)
    }

    /** Carries through the slots a stop left part-way kept ([RuntimeState.keeping]): at start-up, before the photo checks. */
    suspend fun finishKeeps() {
        val now = clock.now()
        for (k in runtime.value.keeping) keeping.withLock { finish(k, now, recovering = true) }?.ownDone?.let { onOwnTaskDone(it) }
    }

    /**
     * The last slot of [key]'s kept since [since] taken back (UI-03's Undo): its record gone (one kept
     * late is the miss it was again, on record and in today's misses), its step to do again (the key
     * says which) or a box's minutes off its task, and the free time it earned given back, as far as
     * it's left. Not once its task has finished by it (one of your own, its last piece): false then,
     * and with none to take back.
     */
    suspend fun unkeepSlot(key: String, since: Long, now: Long = clock.now()): Boolean = keeping.withLock {
        val record = log.value.slots.lastOrNull { it.key == key && it.outcome.kept && it.at >= since } ?: return@withLock false
        if (tasks.value.tasks.firstOrNull { it.id == record.taskId }?.isOpen != true) return@withLock false
        // A step's key is its task and its place ("id#2"); a sitting before its step's last has how
        // many come after it too ("id#2#1"), and a box its part after a -1 ("id#-1#1"): only a step's
        // tick is taken back, the others' minutes alone.
        val stepIndex = key.removePrefix(record.taskId + "#").toIntOrNull()?.takeIf { it >= 0 }
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != record.taskId) return@map t
                    val steps = if (stepIndex == null) t.subSteps else t.subSteps.mapIndexed { i, s -> if (i == stepIndex) s.copy(done = false, timedMin = 0) else s }
                    t.copy(subSteps = steps, workedMin = (t.workedMin - record.workedMin).coerceAtLeast(0))
                },
            )
        }
        // Kept late, its slot had gone by undone: missed again, as when it went by.
        val missed = record.takeIf { it.outcome == SlotOutcome.KeptLate }?.copy(outcome = SlotOutcome.Missed, workedMin = 0, photo = false, earned = false, at = minOf(record.end + Slotter.GRACE_MS, now))
        log.update { state -> state.copy(slots = state.slots - record + listOfNotNull(missed)) }
        val today = today(now)
        runtime.update { state ->
            state.copy(
                credit = if (record.earned) state.credit.takeBack(today, Credit.forSlot(record.plannedMin, settings.value.workMinPerFreeMin)) else state.credit,
                slots = when {
                    missed != null && missed.date == today.toString() -> state.slots.on(today).missed(record.key, record.taskId, missed.at)
                    // Kept in its slot: its start back, so the next plan keeps it where it was while that's
                    // free (PL-13) rather than laying it anew, where today may have no room left for it.
                    missed == null && record.date == today.toString() -> state.slots.on(today).let { it.copy(previous = it.previous + (record.key to record.start)) }
                    else -> state.slots
                },
            )
        }
        true
    }

    /**
     * The free minutes today's slots can still earn (BL-08): today's plan (the pieces still in it and
     * those kept today, with the one [keeping], a slot missed today and moved on among them) at the
     * ratio, less what kept slots have earned. Work beyond the day's plan earns nothing more today;
     * free time from elsewhere (a completion) stands beside it, not in it.
     */
    private fun slotRoomMin(now: Long, ratio: Int, keeping: SlotRecord? = null): Double {
        val today = today(now).toString()
        val kept = log.value.slots.filter { it.outcome.kept && it.date == today }
        val planned = plan(now).todayBucket
        val keepingMin = keeping?.takeIf { k -> k.date == today && planned?.chunks.orEmpty().none { it.key == k.key } }?.plannedMin ?: 0
        val share = Credit.forSlot((planned?.plannedMin ?: 0) + kept.sumOf { it.plannedMin } + keepingMin, ratio)
        val earned = kept.filter { it.earned }.sumOf { Credit.forSlot(it.plannedMin, ratio) }
        return (share - earned).coerceAtLeast(0.0)
    }

    /**
     * [key]'s slot went by undone (PL-13): recorded missed, once a slot, as it was laid ([laid]: its
     * start, end and window, as its alarm had them; else as the plan now has the piece).
     */
    suspend fun missSlot(key: String, laid: Laid? = null, now: Long = clock.now()) = recordOutcome(key, SlotOutcome.Missed, laid, now)

    /** [key]'s slot moved on purpose (a time, a day or a start you gave its task): recorded moved, once a slot. */
    suspend fun moveSlot(key: String, laid: Laid? = null, now: Long = clock.now()) = recordOutcome(key, SlotOutcome.Moved, laid, now)

    /** Where a slot was: its start, end and window. */
    data class Laid(val start: Long, val end: Long, val window: WindowKind)

    private suspend fun recordOutcome(key: String, outcome: SlotOutcome, laid: Laid?, now: Long): Unit = keeping.withLock {
        val plan = plan(now)
        val slot = plan.todaySlots.firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == key }
        val chunk = slot?.chunk ?: plan.ordered.firstOrNull { it.key == key } ?: return
        val known = laid ?: slot?.let { Laid(it.start, it.end, it.window) }
        val at = known ?: Laid(now, now, WindowKind.Evening)
        val date = today(at.start).toString()
        val record = SlotRecord(
            date = date, key = key, taskId = chunk.taskId, kind = chunk.kind, className = tasks.value.tasks.firstOrNull { it.id == chunk.taskId }?.className,
            label = chunk.label, plannedMin = chunk.minutes, start = at.start, end = at.end, window = at.window, outcome = outcome, workedMin = 0, at = now,
        )
        // Once a slot (a second alarm, a sweep after it): a piece moved and laid again later has
        // another, with its own outcome. Where it was isn't known, once a day.
        log.update { state -> if (state.slots.any { it.key == key && it.date == date && (known == null || it.start == at.start) }) state else state.copy(slots = state.slots + record).trimmed(now) }
    }

    /**
     * The piece [key] of [taskId] the photo check found done (Phase 5, BL-08): its slot kept, or a
     * kept one's free time earned. Saved first ([RuntimeState.photosDone]), so a stop part-way is
     * finished at start-up.
     */
    suspend fun photoChecked(taskId: String, step: String?, minutes: Int, stepIndex: Int? = null, key: String, takenAt: Long? = null): Boolean {
        val done = PhotoDone(UUID.randomUUID().toString(), taskId, step, minutes, clock.now(), stepIndex, key, takenAt)
        runtime.update { it.copy(photosDone = it.photosDone + done) }
        return finishPhoto(done)
    }

    /**
     * The block of an email or a task of your own at [index] you've done, ticked off in Tasks (still
     * [title], so a list changed meanwhile ticks nothing): either is yours to say, as archiving an
     * email is. Marked as ticked by hand, so it earns no free time (that's for kept slots). Says
     * whether it was ticked.
     */
    suspend fun tickBlock(taskId: String, index: Int, title: String): Boolean {
        var ticked = false
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != taskId || !Focus.tickable(t) || !t.isOpen) return@map t
                    val step = t.subSteps.getOrNull(index)
                    if (step == null || step.done || step.title != title) return@map t
                    ticked = true
                    // The timed minutes not yet taken up by its other steps were worked on this one
                    // (stopped early, then ticked): it keeps them, up to its length, so they aren't
                    // taken off the next step as well.
                    val multiplier = runtime.value.calibration.stepMultiplierFor(t)
                    val taken = t.subSteps.filter { it.done }.sumOf { if (it.byHand) it.timedMin.toDouble() else it.minutes * multiplier }
                    val spare = (t.workedMin + t.photoMin - t.stepsWorkedMin - taken).roundToInt().coerceAtLeast(0)
                    val kept = minOf(spare, (step.minutes * multiplier).roundToInt())
                    t.copy(subSteps = t.subSteps.mapIndexed { j, s -> if (j == index) s.copy(done = true, byHand = true, timedMin = kept) else s })
                },
            )
        }
        return ticked
    }

    /**
     * The step of [taskId] at [index], still [title], to do again (16 Oct: Felix unticks it, or undoes
     * his tick): one ticked by keeping its slot has that slot taken back ([unkeepSlot]: its record
     * gone, its minutes off the task, the free time it earned given back as far as it's left); one
     * ticked by hand is to do again, with the minutes it kept back on the steps after it. With
     * [reopen], one of your own, or one done by its tick (NF-06), that its tick finished is open
     * again with it to do, in the one write, so it isn't finished again at once. Says whether it was.
     */
    suspend fun untickBlock(taskId: String, index: Int, title: String, reopen: Boolean = false): Boolean {
        val task = tasks.value.tasks.firstOrNull { it.id == taskId && (it.isOpen || (reopen && (it.source == Source.Own || it.tickDone) && it.status == Status.Done)) } ?: return false
        val step = task.subSteps.getOrNull(index)?.takeIf { it.done && it.title == title } ?: return false
        val key = Chunk.keyOf(taskId, index, 0)
        val kept = log.value.slots.lastOrNull { it.key == key && it.outcome.kept }
        if (!task.isOpen) {
            var opened = false
            tasks.update { state ->
                state.copy(
                    tasks = state.tasks.map { t ->
                        if (t.id != taskId || t.isOpen || t.subSteps.getOrNull(index)?.let { it.done && it.title == title } != true) return@map t
                        opened = true
                        t.copy(status = Status.Open, doneAt = null, subSteps = t.subSteps.mapIndexed { j, s -> if (j == index) s.copy(done = false, byHand = false, timedMin = 0) else s })
                    },
                )
            }
            // Open now: its kept slot's record and free time taken back too.
            if (opened && !step.byHand && kept != null) unkeepSlot(key, kept.at)
            return opened
        }
        if (!step.byHand && kept != null && unkeepSlot(key, kept.at)) return true
        var unticked = false
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != taskId || t.subSteps.getOrNull(index)?.let { it.done && it.title == title } != true) return@map t
                    unticked = true
                    t.copy(subSteps = t.subSteps.mapIndexed { j, s -> if (j == index) s.copy(done = false, byHand = false, timedMin = 0) else s })
                },
            )
        }
        return unticked
    }

    /** Gives the photo checks a stop left part-way ([RuntimeState.photosDone]) what they're owed: at start-up. */
    suspend fun finishPhotos() {
        runtime.value.photosDone.forEach { finishPhoto(it) }
    }

    /**
     * Gives [done] its due, once however often this runs (BL-08): its slot kept, earning, if it isn't
     * yet; if it was kept without earning (a longer piece's tick), it earns now, once. Kept since the
     * photo was taken (a stop after, finished again at start-up): nothing more. One saved before a
     * photo named its piece (none were waiting when sessions went) is let go.
     */
    private suspend fun finishPhoto(done: PhotoDone): Boolean {
        val earned = done.key?.let { photoEarns(done, it) } ?: false
        runtime.update { it.copy(photosDone = it.photosDone - done) }
        return earned
    }

    /**
     * [done]'s photo found [key] done (BL-08): its slot kept and earning, or a kept slot's free time
     * earned now, given in the write that lets the photo go (so a stop after can't give it twice, or
     * keep the next box of the same key with it).
     */
    private suspend fun photoEarns(done: PhotoDone, key: String): Boolean {
        val at = done.at
        val today = today(at).toString()
        // Taken of a piece still to do: a slot of it kept since (ticked while the check ran), or moments
        // before (Done, then the photo at once: [AGAIN_MS]), is the one it shows; one kept before that is
        // another (an earlier box under the same key, a round before the task reopened), and the piece in
        // the plan now is the one.
        val taken = done.takenAt ?: at
        val unearned = log.value.slots.lastOrNull { it.key == key && it.outcome.kept && it.date == today && !it.earned && it.at >= taken - AGAIN_MS }
        if (unearned == null) {
            val kept = keeping.withLock { keep(key, 0, photo = true, verified = false, seenAt = taken, now = maxOf(at, clock.now()), photoDone = done) } ?: return false
            kept.ownDone?.let { onOwnTaskDone(it) }
            return true
        }
        return keeping.withLock { earnKept(unearned, photo = true, letGo = done) }
    }

    /**
     * [unearned], a slot kept today without earning, earns now (BL-08: its photo passed), within
     * today's plan: journalled, its record marked, then given with [letGo]'s letting go. False when
     * its day is over, it's earned since (another photo), or [letGo] was let go already. Held under
     * [keeping], as a keep is.
     */
    private suspend fun earnKept(unearned: SlotRecord, photo: Boolean, letGo: PhotoDone? = null): Boolean {
        val now = clock.now()
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        if (unearned.date != today.toString() || unearned !in log.value.slots) return false
        val earning = minOf(Credit.forSlot(unearned.plannedMin, ratio), slotRoomMin(now, ratio))
        // On record before anything changes, as a keep is: a stop part-way is carried through at start-up, once.
        val journal = Keeping(unearned.copy(earned = true, photo = unearned.photo || photo), null, null, 0, earning, replaces = unearned, photo = letGo, earnOnly = true)
        var saved = false
        runtime.update { state -> if (letGo != null && letGo !in state.photosDone) state else state.copy(keeping = state.keeping + journal).also { saved = true } }
        return saved && finish(journal, now, recovering = false) != null
    }

    /**
     * Gives what's owed for the completions syncs confirmed ([TaskState.unrewarded]), hands them to
     * [then] (which asks how each went), and only then clears them. Stopped before that, it's all
     * done again next time (after the next sync, or when the app starts): [onCompleted] gives each
     * only once, and [then] must be safe to repeat.
     */
    suspend fun rewardCompletions(then: suspend (List<TaskItem>) -> Unit = {}) {
        val waiting = tasks.value.unrewarded
        if (waiting.isEmpty()) return
        onCompleted(waiting)
        then(waiting)
        tasks.update { state -> state.copy(unrewarded = state.unrewarded.filterNot { t -> waiting.any { it.id == t.id && it.doneAt == t.doneAt } }) }
    }

    /**
     * Work a source confirmed done: free time for what was left of it, and a record in the log.
     * Each is given once per completion however often it's offered: the free time with a ledger of
     * what's had it ([RuntimeState.rewarded]), written in the same step; the record only if the log
     * hasn't got it.
     */
    suspend fun onCompleted(completed: List<TaskItem>) {
        if (completed.isEmpty()) return
        val now = clock.now()
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        // What was worked on it (its kept slots): as stored, where it's still this completion; once it
        // has reopened (its minutes started again), as the completion had it.
        fun worked(task: TaskItem): Int = tasks.value.tasks.firstOrNull { it.id == task.id && it.doneAt == task.doneAt }?.workedMin ?: task.workedMin
        fun key(task: TaskItem) = Rewarded(task.id, task.doneAt ?: task.lastSeenAt)
        // Its deck tasks, missed ones too: the read that closes an assignment drops its unfinished
        // deck in the same sync, before this, and that deck's slots earned their time already.
        val decks = AnkiRules.heldDecks(tasks.value.tasks, settings.value.ankiTextbook, missed = true)
        // Blocks you ticked off by hand: done, but not timed, so the completion's no measure of time.
        fun handMinutes(task: TaskItem): Int = tasks.value.tasks.firstOrNull { it.id == task.id && it.doneAt == task.doneAt }?.handMin ?: task.handMin
        // Nor is work a photo check found done: its minutes weren't timed.
        fun photoMinutes(task: TaskItem): Int = tasks.value.tasks.firstOrNull { it.id == task.id && it.doneAt == task.doneAt }?.photoMin ?: task.photoMin
        // Its vocabulary steps its decks hold: their work is the decks', and learned from theirs.
        fun deckMinutes(task: TaskItem): Int = task.subSteps
            .filter { step -> step.ankiSections.isNotEmpty() && step.ankiSections.all { decks[task.id]?.containsKey(it) == true } }
            .sumOf { it.minutes }
        // When this time round began for [task]: just after its completion before this one, else when it was first seen.
        fun since(task: TaskItem): Long =
            log.value.completions.filter { it.taskId == task.id && it.doneAt < (task.doneAt ?: now) }.maxOfOrNull { it.doneAt + 1 } ?: task.firstSeenAt
        // Its slots kept today without earning, this time round, that no photo could check (longer
        // pieces of work that isn't written, ticked: BL-08): its source saying it's done is their
        // evidence, so they earn by it. Written work's is its photo.
        val confirming = completed.filter { it.kind != Kind.Info && it.kind != Kind.Event && it.source != Source.Own && !it.tickDone && today(it.doneAt ?: now) == today }
            .flatMap { task ->
                log.value.slots.filter {
                    it.taskId == task.id && it.outcome.kept && !it.earned && it.date == today.toString() && it.at >= since(task) &&
                        !(it.kind == Kind.Homework && task.source != Source.Anki)
                }
            }
        val room = slotRoomMin(now, ratio)
        runtime.update { state ->
            val fresh = completed.filter { key(it) !in state.rewarded }
            // Reading or archiving an email, or an event passing, isn't work that earns time. Free
            // time is for the day the work was confirmed: given late (the app stopped first), after
            // that day is over, it has gone as the rest of that day's has.
            // Nor is a task of your own you said is done: no source confirms it (your call, 12 Oct); its
            // slots earned their time. Nor one done by its tick (NF-06), for the same reason.
            val earned = fresh.filter { it.kind != Kind.Info && it.kind != Kind.Event && it.source != Source.Own && !it.tickDone && today(it.doneAt ?: now) == today }.sumOf { task ->
                // Less what its kept slots and photo checks already earned time for: as stored while it's still
                // this completion, as the completion had it once the task has reopened.
                val stored = tasks.value.tasks.firstOrNull { it.id == task.id && it.doneAt == task.doneAt }
                val photos = stored?.photoMin ?: task.photoMin
                val counted = task.copy(workedMin = worked(task), photoMin = photos)

                /**
                 * What's owed for [t]: what was left of it, less what its decks and your ticks account
                 * for, all in the plan's units (the estimate as calibrated, its steps as scaled), as its
                 * slots earned theirs.
                 */
                fun owed(t: TaskItem): Double {
                    val multiplier = state.calibration.stepMultiplierFor(t)
                    // Vocabulary its deck tasks hold earns its time through them, not again here: all
                    // of it once they're done, else as far as their kept slots and photo checks went. No
                    // more, as a deck left unfinished when the work closes is dropped, and mustn't take
                    // that time with it.
                    val delegated = t.subSteps.filter { it.ankiSections.isNotEmpty() }.sumOf { step ->
                        val holding = step.ankiSections.mapNotNull { decks[t.id]?.get(it) }
                        when {
                            holding.size < step.ankiSections.size -> 0.0
                            holding.all { it.status == Status.Done } -> step.minutes * multiplier
                            else -> minOf(step.minutes * multiplier, holding.sumOf { it.workedMin + it.photoMin }.toDouble())
                        }
                    }
                    // Nor for blocks you ticked off by hand: done, but not timed or checked (bar what
                    // was timed on them first, which counts as worked).
                    val hand = (stored?.subSteps ?: t.subSteps).filter { it.done && it.byHand }.sumOf { (it.minutes * multiplier - it.timedMin).coerceAtLeast(0.0) }
                    return Planner.remaining(t, state.calibration.multiplierFor(t)) - delegated - hand
                }
                // By your minutes or by the app's estimate, each in its own scale, the less: raising a
                // task's minutes plans more time for it, but can't make finishing it earn more. The app's
                // estimate leaves out your steps as well as your minutes (#49's review): yours are planned
                // as given, its by the pace learned.
                val remaining = minOf(owed(counted), owed(counted.copy(mine = counted.mine.copy(minutes = null, steps = null))))
                Credit.forCompletion(remaining.roundToInt().coerceAtLeast(0), ratio)
            }
            // Its slots kept without earning: given with it, once, within today's plan.
            val slotEarning = minOf(confirming.filter { slot -> fresh.any { it.id == slot.taskId } }.sumOf { Credit.forSlot(it.plannedMin, ratio) }, room)
            state.copy(
                credit = state.credit.earn(today, earned + slotEarning),
                rewarded = (state.rewarded + fresh.map(::key)).distinct().filter { it.doneAt > now - REWARDED_KEPT_MS },
            )
        }
        // Marked so on their records: those of one given before a stop again, no more.
        if (confirming.isNotEmpty()) log.update { state -> state.copy(slots = state.slots.map { if (it in confirming) it.copy(earned = true) else it }) }
        log.update { state ->
            val unrecorded = completed.filter { it.status == Status.Done }
                .filter { task -> state.completions.none { it.taskId == task.id && it.doneAt == (task.doneAt ?: now) } }
            state.copy(
                completions = state.completions + unrecorded.map { task ->
                    CompletionRecord(
                        taskId = task.id,
                        title = task.title,
                        source = task.source,
                        kind = task.kind,
                        className = task.className,
                        // What was left of it when first seen: progress it already had isn't work done here,
                        // nor is vocabulary its decks hold (their own records learn from it). A total Felix
                        // worked out at your pace, against the app's estimate it came from (17 Oct; #50's review).
                        estimateMin = ((if (task.pacedTeaches) task.mine.minutes?.paced?.from ?: task.appEffortMin else task.effortMin) * (1 - task.sourceProgress.coerceIn(0.0, 1.0)) - deckMinutes(task)).roundToInt().coerceAtLeast(1),
                        workedMin = worked(task),
                        // The deadline in force when it was done (a plan's gone by gives way to the real one
                        // behind it); one split into dated blocks with none of its own, its last block's.
                        dueAt = task.deadlineAt(task.doneAt ?: now) ?: task.subSteps.mapNotNull { it.dueAt }.maxOrNull(),
                        // A step's date, or any the app or Claude set, is a plan's, never a deadline to meet.
                        hardDeadline = task.hardAt(task.doneAt ?: now),
                        firstSeenAt = task.firstSeenAt,
                        doneAt = task.doneAt ?: now,
                        byHand = handMinutes(task) > 0,
                        // Work a photo check found done: as minutes counted before (BL-08's), or a slot it kept.
                        photo = photoMinutes(task) > 0 || state.slots.any { it.taskId == task.id && it.photo && it.at >= since(task) },
                        // Your own reckoning teaches the app's estimates nothing; a total at your pace, of the
                        // task as it was when paced, still does.
                        yourEstimate = task.yourMinutes && !task.pacedTeaches,
                    )
                },
            ).trimmed(now)
        }
    }

    companion object {
        /** How soon after a piece's Done a tick on it is that Done's tap again, whenever its screen saw it: no piece is worked in less. */
        private const val AGAIN_MS = 30_000L
        private const val PLAN_TTL_MS = 60_000L

        /** Whether [task]'s blocks can be ticked off by hand: an email's, or one of your own's, which are yours to say. */
        fun tickable(task: TaskItem): Boolean = task.source == Source.Gmail || task.source == Source.Own

        /** How long a completion stays in the ledger of those rewarded: far longer than any retry. */
        private const val REWARDED_KEPT_MS = 14 * 24 * 3_600_000L

        /** The most of a piece's name a reward line quotes. */
        private const val MAX_LABEL = 40
    }
}
