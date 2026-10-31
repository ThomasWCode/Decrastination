package com.thomaswcode.decrastination.calendar

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.CalendarState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.MicroPlan
import com.thomaswcode.decrastination.ports.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The plan written to the calendar you picked (NF-07; docs/plan_v4/architecture.md §The rules):
 * each plan's next seven days, once plans have been quiet a while ([planned], [writeSlots]), a slot
 * you've moved there taken as your time; the app's events taken out of a calendar no longer written to
 * ([clearOldCalendars]); the calendar watched for changes ([watchCalendar], [calendarChanged]); and your
 * answers about its events ([answer]). Built like [com.thomaswcode.decrastination.block.Focus]: its
 * stores, the clock and small callbacks in its constructor, the calendar provider behind a port
 * ([CalendarProvider]: `SlotCalendar` on the phone).
 */
@OptIn(FlowPreview::class)
class CalendarWriter(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val model: JsonStore<ModelState>,
    /** The versions of its events the app has written to calendars, so a copy of one is known ([SlotCalendarDiff.isCopy]). */
    private val calendar: JsonStore<CalendarState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** The calendar's events, read and written. */
    private val provider: CalendarProvider,
    /** A move of yours written back, and why, said (`SlotCalendar.sayRefused`). */
    private val sayRefused: (move: SlotCalendarDiff.HandMove, zone: ZoneId, why: String) -> Unit,
    /** A move of yours taken as your time: a change of yours, kept and applied at once ([com.thomaswcode.decrastination.tasks.Instructions.addOwnChange]). */
    private val addOwnChange: suspend (id: String, text: String, about: About, change: Change) -> Unit,
    /** The plan made again ([com.thomaswcode.decrastination.plan.PlanWatcher.poke]). */
    private val replan: () -> Unit,
    /** His questions about the events named a key settled ([com.thomaswcode.decrastination.felix.Conversation.settleEvent]). */
    private val settleEvent: suspend (String) -> Unit,
    /** The calendar read again, and today's record made again with it ([com.thomaswcode.decrastination.sync.Sync.refreshCalendar], [com.thomaswcode.decrastination.learn.Briefing.replanToday]). */
    private val reread: suspend () -> Unit,
    /** Your busy times from one day to another, as the instructions in use give them ([InstructionRules.busyBlocks]): each written as its own event (D-117). */
    private val busyTimes: (from: LocalDate, to: LocalDate) -> List<BusyBlock>,
    /** A busy time's event you [deleted] or changed, put back, and why, said (`SlotCalendar.sayPutBack`). */
    private val sayPutBack: (want: SlotCalendarDiff.Desired, deleted: Boolean, why: String) -> Unit,
    /** How long plans are quiet before the calendar's written: [CALENDAR_WRITE_QUIET_MS], but in a test. */
    private val quietMs: Long = CALENDAR_WRITE_QUIET_MS,
) {
    /**
     * The plan to write to the calendar, once plans have been quiet [quietMs]: a burst of
     * changes (a chat answer's, one after another) is one write, not an event inserted and changed
     * again while the calendar's sync still has it in hand, which brought copies of it back.
     */
    private val toWrite = MutableSharedFlow<Plan>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** [made], a new plan, written once plans have been quiet a while ([toWrite]). */
    fun planned(made: Plan) {
        toWrite.tryEmit(made)
    }

    /** How many listen for [planned]'s plans: one once [start]'s write does (a plan before then is let go). Its tests wait for it. */
    internal val listening: StateFlow<Int> get() = toWrite.subscriptionCount

    /**
     * [made]'s next seven days written to the calendar you picked (NF-07). A slot you've moved there
     * is taken as your time for its task's next piece, where its deadline allows; else it's put back,
     * and you're told. Only [start]'s write, once plans are quiet, calls it (and its tests): two at
     * once could insert a slot twice. Under [writing], so a switch of calendar waits for it.
     */
    internal suspend fun writeSlots(made: Plan): Unit = writing.withLock {
        clearOldCalendars()
        val calendarId = settings.value.calendarWriteId ?: return
        if (!provider.allowed()) return
        val zone = clock.zone()
        // Your busy times of the same days, each an event of its own (D-117).
        val busy = busyTimes(made.today, made.today.plusDays(SlotCalendarDiff.DAYS - 1))
        // The same slots as last written, and nothing changed in the calendar since (AR-08): it isn't read again.
        val desired = SlotCalendarDiff.desired(made, zone, model.value.microPlans, busy)
        if (!calendarTouched && lastWritten == (calendarId to desired)) return
        calendarTouched = false
        val byId = tasks.value.tasks.associateBy { it.id }
        val before = calendar.value.calendarWritten
        // A copy is one of the versions earlier writes made ([SlotCalendarDiff.isCopy]), never one of
        // this write's: an event of yours under a slot's title at its times isn't the app's.
        val known = calendar.value.written.toSet()
        // The versions written moments ago: an event the sync brings back to one of them is the app's own (Q93).
        val now = clock.now()
        wroteAt.values.removeAll { now - it > SlotCalendarDiff.ECHO_MS }
        val echoes = wroteAt.keys.toSet()
        val written = try {
            withContext(Dispatchers.IO) {
                write(calendarId, made, zone, before, known, echoes, model.value.microPlans, busy) { move ->
                    val task = move.desired.taskId?.let(byId::get)
                    when {
                        // A busy time is changed by asking Felix, or in Your changes (D-117): put back, and said.
                        move.desired.busy -> BUSY_CHANGED
                        // A break outside is moved by Felix, in the chat (D-116): put back, and said.
                        move.desired.taskId == null -> "a break outside is moved by asking Felix"
                        task == null -> "its task is no longer there"
                        InstructionRules.latePin(change(move, zone), task, zone, clock.now()) -> "it's due before then"
                        else -> null
                    }
                }
            }
        } catch (e: Throwable) {
            // Not written: the calendar's read again at the next plan, the change seen before it with it.
            calendarTouched = true
            throw e
        }
        // Each event's version remembered once it's written (not a move of yours taken: its event is
        // at your time), so a copy the calendar's sync makes of it is known at the next write. Not
        // one an event of yours already had: remembered, the next write would
        // take yours for a copy.
        val moved = written.taken.map { it.desired.uri }.toSet()
        runCatching {
            val versions = desired.filter { d -> d.uri !in moved }.map { d -> SlotCalendarDiff.version(calendarId, d.title, d.start, d.end) }.filterNot { it in written.yours }
            calendar.update { it.copy(written = SlotCalendarDiff.remembered(it.written, versions)) }
        }.onFailure { Log.w(Log.TAG, "Remembering what was written to the calendar failed", it) }
        // And each event inserted or changed, by its URI and version, with when, so the sync bringing it back to that within
        // [SlotCalendarDiff.ECHO_MS] is known for the app's own; not one left as it was, nor another event's.
        written.wrote.forEach { d -> wroteAt[d.uri to SlotCalendarDiff.version(calendarId, d.title, d.start, d.end)] = now }
        // A move taken counts as written only once its instruction is in: stopped before, the next
        // write finds it a move again, rather than putting it back.
        val held = SlotCalendarDiff.pending(written.starts, before, written.taken)
        if (held != before) calendar.update { it.copy(calendarWritten = held) }
        written.refused.forEach { (move, why) -> sayRefused(move, zone, why) }
        written.restored.forEach { sayPutBack(it, true, BUSY_CHANGED) }
        written.reverted.forEach { sayPutBack(it, false, BUSY_CHANGED) }
        lastWritten = calendarId to desired
        for (move in written.taken) {
            val taskId = move.desired.taskId ?: continue
            addOwnChange(UUID.randomUUID().toString(), "Moved in the calendar", About(taskId = taskId, taskTitle = byId[taskId]?.title), change(move, zone))
            calendar.update { it.copy(calendarWritten = it.calendarWritten + (move.desired.uri to move.existing.start)) }
        }
    }

    /**
     * Held while the calendar's written ([writeSlots]) and while a calendar no longer written to is cleared ([start]): a
     * write under way for the calendar just switched from goes in before that's cleared, so none of its events is left
     * there with nothing on the list to clear it.
     */
    private val writing = Mutex()

    /**
     * The events this writer inserted or changed in the last [SlotCalendarDiff.ECHO_MS], each as its URI and version
     * ([SlotCalendarDiff.version]), with when; under [writing].
     */
    private val wroteAt = mutableMapOf<Pair<String, Long>, Long>()

    /** The app's events taken out of calendars no longer written to, each kept on the list till that's done; under [writing]. */
    private suspend fun clearOldCalendars() {
        val waiting = calendar.value.calendarsToClear
        if (waiting.isEmpty()) return
        val versions = calendar.value.written.toSet()
        val cleared = withContext(Dispatchers.IO) { waiting.filter { clear(it, versions) } }
        if (cleared.isNotEmpty()) calendar.update { it.copy(calendarsToClear = it.calendarsToClear - cleared.toSet()) }
    }

    /**
     * What a write left: each URI's start as it now stands, and the moves you made, taken or written
     * back (with why); and the versions it wrote that an event of yours already has, not to be
     * remembered ([SlotCalendarDiff.yours]); and the events it inserted or changed ([wrote]).
     */
    data class Written(val starts: Map<String, Long>, val taken: List<SlotCalendarDiff.HandMove>, val refused: List<Pair<SlotCalendarDiff.HandMove, String>>, val yours: Set<Long> = emptySet(), val restored: List<SlotCalendarDiff.Desired> = emptyList(), val reverted: List<SlotCalendarDiff.Desired> = emptyList(), val wrote: List<SlotCalendarDiff.Desired> = emptyList())

    /** [move] as your time for its task's next piece: an [ChangeType.AtTime] at its event's start. */
    private fun change(move: SlotCalendarDiff.HandMove, zone: ZoneId): Change {
        val at = Instant.ofEpochMilli(move.existing.start).atZone(zone)
        return Change(ChangeType.AtTime, taskId = move.desired.taskId, date = at.toLocalDate().toString(), startMin = at.hour * 60 + at.minute, time = move.existing.start)
    }

    /**
     * [plan]'s slots written to [calendarId] against what's there and [written] (each URI's start as
     * last written); a hand move taken as [SlotCalendarDiff.judge] judges it, else written back. A
     * copy of one of its events is known by [versions], those it has written ([SlotCalendarDiff.isCopy]);
     * an event of yours under a slot's title at its times, there before it, is said ([Written.yours]).
     * One at a version of [echoes] (its URI and version), written moments before, is the sync's echo of it, written back (Q93).
     */
    private fun write(calendarId: Long, plan: Plan, zone: ZoneId, written: Map<String, Long>, versions: Set<Long>, echoes: Set<Pair<String, Long>>, micro: Map<String, MicroPlan> = emptyMap(), busy: List<BusyBlock> = emptyList(), refuses: (SlotCalendarDiff.HandMove) -> String?): Written {
        val from = plan.today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = plan.today.plusDays(SlotCalendarDiff.DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val desired = SlotCalendarDiff.desired(plan, zone, micro, busy)
        // Those with none of its marks in its words: copies of its own, to delete, and events of yours.
        val unmarked = provider.unmarked(calendarId)
        val copies = unmarked.filter { SlotCalendarDiff.isCopy(calendarId, it.title, it.description, it.start, it.end, versions) }
        // An event in a version the app never wrote there was changed by hand.
        val diff = SlotCalendarDiff.diff(desired, provider.existing(calendarId, from, to, desired.map { it.uri }) + copies, written, echo = { e ->
            (e.uri to SlotCalendarDiff.version(calendarId, e.title, e.start, e.end)) in echoes
        }) { e ->
            SlotCalendarDiff.version(calendarId, e.title, e.start, e.end) !in versions
        }
        val (taken, refused) = SlotCalendarDiff.judge(diff.handMoves, refuses)
        provider.apply(calendarId, zone, diff.inserts, diff.updates.map { it.existing.id to it.desired } + refused.map { it.first.existing.id to it.first.desired }, diff.deletes.map { it.id })
        val wrote = diff.inserts + diff.updates.map { it.desired } + refused.map { it.first.desired }
        return Written(desired.associate { it.uri to it.start } + taken.associate { it.desired.uri to it.existing.start }, taken, refused, SlotCalendarDiff.yours(calendarId, desired, unmarked, versions), diff.restored, diff.reverted, wrote)
    }

    /**
     * The app's events in [calendarId], all of them: a calendar no longer written to is left as it
     * was before. False when they can't be (no permission, or the provider failed), to be tried again.
     */
    private fun clear(calendarId: Long, versions: Set<Long>): Boolean {
        if (!provider.allowed()) return false
        return runCatching {
            provider.deleteOwn(calendarId)
            // And copies of its events that lost the app's marks, by their words and [versions].
            val copies = provider.unmarked(calendarId).filter { SlotCalendarDiff.isCopy(calendarId, it.title, it.description, it.start, it.end, versions) }.map { it.id }
            if (copies.isNotEmpty()) provider.apply(calendarId, clock.zone(), emptyList(), emptyList(), copies)
        }.isSuccess
    }

    /** The calendar announces each change; it's read again once they stop. */
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Each change the calendar announces, once it's watched ([watchCalendar]). */
    val calendarChanged: SharedFlow<Unit> = changes

    private var calendarWatched = false

    /**
     * Whether the calendar has changed since the app last read the one it writes to (AR-08): till it
     * has, a plan that would write what was written last ([lastWritten]) doesn't read it again. A
     * change there (a slot moved or deleted by hand, or the app's own write) has it read again.
     */
    @Volatile
    private var calendarTouched = true

    /** The calendar last written to and the slots written there (AR-08). */
    @Volatile
    private var lastWritten: Pair<Long, List<SlotCalendarDiff.Desired>>? = null

    /**
     * Watches the calendar for changes, once it may be read: at start, and at each read of it
     * after (so allowing it later, in Setup or Android's settings, starts the watching too).
     */
    @Synchronized
    fun watchCalendar() {
        if (calendarWatched) return
        runCatching {
            provider.watch {
                calendarTouched = true
                changes.tryEmit(Unit)
            }
        }.onSuccess { calendarWatched = it }.onFailure { Log.w(Log.TAG, "Can't watch the calendar", it) }
    }

    /**
     * What the calendar's written from, watched from the app's start: the calendar picked, and each
     * plan once plans have been quiet a while.
     */
    fun start() {
        // The calendar written to changed, or none is now (NF-07): what was written to one no longer
        // is goes from it, and the plan's made again for the new one.
        scope.launch {
            settings.state.map { it.calendarWriteId }.distinctUntilChanged().collect { id ->
                // After a write under way, which may be for the one switched from.
                writing.withLock {
                    val was = calendar.value.calendarWrittenTo
                    if (was == id) return@collect
                    // The old one kept on the list to clear till it is: without the permission now, or
                    // should the provider fail, it's tried again at the next replan.
                    calendar.update { state ->
                        state.copy(calendarWrittenTo = id, calendarWritten = emptyMap(), calendarsToClear = (state.calendarsToClear + listOfNotNull(was)).distinct() - listOfNotNull(id).toSet())
                    }
                    clearOldCalendars()
                }
                replan()
            }
        }
        // The calendar written once plans have been quiet a while.
        scope.launch {
            toWrite.debounce(quietMs).collect { made -> runCatching { writeSlots(made) }.onFailure { Log.w(Log.TAG, "Writing the calendar failed", it) } }
        }
    }

    /**
     * Your [answer] about every event named [key] (null: forgotten, for the rules to judge again):
     * kept, his question answered, the plan redone, and today's as the briefing recorded it.
     */
    suspend fun answer(key: String, answer: String?) {
        calendar.update { it.copy(eventAnswers = if (answer == null) it.eventAnswers - key else it.eventAnswers + (key to answer)) }
        settleEvent(key)
        reread()
    }

    companion object {
        /** How long plans are quiet before the calendar's written ([toWrite]). */
        private const val CALENDAR_WRITE_QUIET_MS = 5_000L

        /** Why a busy time's event moved or deleted by hand is put back (D-117). */
        const val BUSY_CHANGED = "a busy time is changed by asking Felix, or in Your changes"
    }
}
