package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Kind
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
import com.thomaswcode.decrastination.data.PhotoDone
import com.thomaswcode.decrastination.data.Rewarded
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
            ),
        )
    }

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
     * grace is over; its free time earned where it's a short piece, or [photo] shows it done (BL-08),
     * never past today's plan; and its start no longer kept for the next plan. A piece ticked ahead
     * of its slot, or with none, is kept all the same. [seenAt]: when the plan it was ticked on was
     * made, so a second tap on it (a task in boxes has its next box under the same key) keeps
     * nothing more. False when there's no such piece to keep.
     */
    suspend fun keepSlot(key: String, extraMin: Int = 0, photo: Boolean = false, seenAt: Long? = null, now: Long = clock.now()): Boolean {
        val kept = keeping.withLock { keep(key, extraMin, photo, seenAt, now) } ?: return false
        // One of your own it finished: finished as Done does, once its record is in.
        kept.ownDone?.let { onOwnTaskDone(it) }
        return true
    }

    /** Held while a slot's outcome is recorded: two ticks at once keep a slot once, and a tick and its slot's end don't cross. */
    private val keeping = Mutex()

    /** What keeping a slot leaves to do: one of your own it finished. */
    private class Kept(val ownDone: String?)

    private suspend fun keep(key: String, extraMin: Int, photo: Boolean, seenAt: Long?, now: Long): Kept? {
        val plan = plan(now)
        val slot = (plan.todaySlots + plan.buckets.flatMap { it.slots }).firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == key }
        val chunk = slot?.chunk ?: plan.ordered.firstOrNull { it.key == key } ?: return null
        // Kept since you saw it (a second tap, a sheet left open): a box's next piece has its key now.
        if (seenAt != null && log.value.slots.any { it.key == key && it.outcome.kept && it.at >= seenAt }) return null
        val worked = chunk.minutes + extraMin.coerceAtLeast(0)
        var kept = false
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != chunk.taskId || !t.isOpen) return@map t
                    val index = chunk.stepIndex
                    if (index == null) {
                        // A box of time (or the whole task): its minutes worked.
                        kept = true
                        return@map t.copy(workedMin = t.workedMin + worked)
                    }
                    // Its step, done, and its minutes worked: so more than
                    // the step's own come off the next steps.
                    val i = stepAt(t, index, chunk.step.orEmpty())
                    if (i < 0) return@map t
                    kept = true
                    t.copy(subSteps = t.subSteps.mapIndexed { j, s -> if (j == i) s.copy(done = true, timedMin = worked) else s }, workedMin = t.workedMin + worked)
                },
            )
        }
        if (!kept) return null
        val task = tasks.value.tasks.firstOrNull { it.id == chunk.taskId }
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        val earns = photo || chunk.minutes < settings.value.photoFromMin
        // A slot of it missed today (it was moved on): this is that slot, kept late, where it was laid.
        val missed = log.value.slots.lastOrNull { it.key == key && it.date == today.toString() && it.outcome == SlotOutcome.Missed }
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
        // A slot of it missed today and kept after all is one slot, kept late: the miss gives way.
        log.update { state -> state.copy(slots = state.slots.filterNot { it.key == key && it.date == record.date && it.outcome == SlotOutcome.Missed } + record).trimmed(now) }
        val earning = if (earns) minOf(Credit.forSlot(chunk.minutes, ratio), slotRoomMin(now, ratio, without = record)) else 0.0
        runtime.update { state ->
            state.copy(
                credit = if (earning > 0) state.credit.earn(today, earning) else state.credit.on(today),
                slots = state.slots.on(today).kept(key),
            )
        }
        // One of your own finished by it: its last step (seen to as its steps are), or its minutes all worked.
        return Kept(task?.takeIf { it.source == Source.Own && it.isOpen && it.subSteps.isEmpty() && Planner.remaining(it, runtime.value.calibration.multiplierFor(it)) <= 0 }?.id)
    }

    /**
     * The free minutes today's slots can still earn (BL-08): today's plan (the pieces still in it and
     * those kept today) at the ratio, less what kept slots have earned, [without] the one being kept.
     * Work beyond the day's plan earns nothing more today; free time from elsewhere (a completion)
     * stands beside it, not in it.
     */
    private fun slotRoomMin(now: Long, ratio: Int, without: SlotRecord? = null): Double {
        val today = today(now).toString()
        val kept = log.value.slots.filter { it.outcome.kept && it.date == today }
        val share = Credit.forSlot((plan(now).todayBucket?.plannedMin ?: 0) + kept.sumOf { it.plannedMin }, ratio)
        val earned = kept.filter { it.earned && it != without }.sumOf { Credit.forSlot(it.plannedMin, ratio) }
        return (share - earned).coerceAtLeast(0.0)
    }

    /**
     * [key]'s slot went by undone (PL-13): recorded missed, once a day, as it was laid ([laid]: its
     * start, end and window, as its alarm had them; else as the plan now has the piece).
     */
    suspend fun missSlot(key: String, laid: Laid? = null, now: Long = clock.now()) = recordOutcome(key, SlotOutcome.Missed, laid, now)

    /** [key]'s slot moved on purpose (a time, a day or a start you gave its task): recorded moved, once a day. */
    suspend fun moveSlot(key: String, laid: Laid? = null, now: Long = clock.now()) = recordOutcome(key, SlotOutcome.Moved, laid, now)

    /** Where a slot was: its start, end and window. */
    data class Laid(val start: Long, val end: Long, val window: WindowKind)

    private suspend fun recordOutcome(key: String, outcome: SlotOutcome, laid: Laid?, now: Long): Unit = keeping.withLock {
        val plan = plan(now)
        val slot = plan.todaySlots.firstOrNull { it.kind == SlotKind.Work && it.chunk?.key == key }
        val chunk = slot?.chunk ?: plan.ordered.firstOrNull { it.key == key } ?: return
        val at = laid ?: slot?.let { Laid(it.start, it.end, it.window) } ?: Laid(now, now, WindowKind.Evening)
        val date = today(at.start).toString()
        val record = SlotRecord(
            date = date, key = key, taskId = chunk.taskId, kind = chunk.kind, className = tasks.value.tasks.firstOrNull { it.id == chunk.taskId }?.className,
            label = chunk.label, plannedMin = chunk.minutes, start = at.start, end = at.end, window = at.window, outcome = outcome, workedMin = 0, at = now,
        )
        log.update { state -> if (state.slots.any { it.key == key && it.date == date }) state else state.copy(slots = state.slots + record).trimmed(now) }
    }

    /**
     * The piece [key] of [taskId] the photo check found done (Phase 5, BL-08): its slot kept, or a
     * kept one's free time earned. Saved first ([RuntimeState.photosDone]), so a stop part-way is
     * finished at start-up.
     */
    suspend fun photoChecked(taskId: String, step: String?, minutes: Int, stepIndex: Int? = null, key: String): Boolean {
        val done = PhotoDone(UUID.randomUUID().toString(), taskId, step, minutes, clock.now(), stepIndex, key)
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
        val earned = done.key?.let { photoEarns(it, done.at) } ?: false
        runtime.update { it.copy(photosDone = it.photosDone - done) }
        return earned
    }

    /** [key]'s photo found it done at [at] (BL-08): its slot kept and earning, or a kept slot's free time earned now. */
    private suspend fun photoEarns(key: String, at: Long): Boolean {
        val today = today(at).toString()
        val unearned = log.value.slots.lastOrNull { it.key == key && it.outcome.kept && it.date == today && !it.earned }
        if (unearned == null) return keepSlot(key, photo = true, seenAt = at, now = maxOf(at, clock.now()))
        val ratio = settings.value.workMinPerFreeMin
        val room = slotRoomMin(clock.now(), ratio)
        var marked = false
        log.update { state ->
            state.copy(slots = state.slots.map { if (it == unearned && !it.earned) it.copy(earned = true, photo = true).also { marked = true } else it })
        }
        if (marked) runtime.update { it.copy(credit = it.credit.earn(today(at), minOf(Credit.forSlot(unearned.plannedMin, ratio), room))) }
        return marked
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
        runtime.update { state ->
            val fresh = completed.filter { key(it) !in state.rewarded }
            // Reading or archiving an email, or an event passing, isn't work that earns time. Free
            // time is for the day the work was confirmed: given late (the app stopped first), after
            // that day is over, it has gone as the rest of that day's has.
            // Nor is a task of your own you said is done: no source confirms it (your call, 12 Oct); its
            // slots earned their time.
            val earned = fresh.filter { it.kind != Kind.Info && it.kind != Kind.Event && it.source != Source.Own && today(it.doneAt ?: now) == today }.sumOf { task ->
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
                // task's minutes plans more time for it, but can't make finishing it earn more.
                val remaining = minOf(owed(counted), owed(counted.copy(userEffortMin = null)))
                Credit.forCompletion(remaining.roundToInt().coerceAtLeast(0), ratio)
            }
            state.copy(
                credit = state.credit.earn(today, earned),
                rewarded = (state.rewarded + fresh.map(::key)).distinct().filter { it.doneAt > now - REWARDED_KEPT_MS },
            )
        }
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
                        // nor is vocabulary its decks hold (their own records learn from it).
                        estimateMin = (task.effortMin * (1 - task.sourceProgress.coerceIn(0.0, 1.0)) - deckMinutes(task)).roundToInt().coerceAtLeast(1),
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
                        photo = photoMinutes(task) > 0 || state.slots.any { it.taskId == task.id && it.photo && it.at >= task.firstSeenAt },
                        yourEstimate = task.yourMinutes,
                    )
                },
            ).trimmed(now)
        }
    }

    companion object {
        private const val PLAN_TTL_MS = 60_000L

        /** Whether [task]'s blocks can be ticked off by hand: an email's, or one of your own's, which are yours to say. */
        fun tickable(task: TaskItem): Boolean = task.source == Source.Gmail || task.source == Source.Own

        /** How long a completion stays in the ledger of those rewarded: far longer than any retry. */
        private const val REWARDED_KEPT_MS = 14 * 24 * 3_600_000L
    }
}
