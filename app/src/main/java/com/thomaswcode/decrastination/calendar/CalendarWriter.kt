package com.thomaswcode.decrastination.calendar

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.CalendarVersions
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.MicroPlan
import java.time.Instant
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
import kotlinx.coroutines.withContext

/**
 * The plan written to the calendar you picked (NF-07; docs/plan_v4, Stage 6a), moved out of `AppGraph`:
 * each plan's next seven days, once plans have been quiet a while ([planned], [writeSlots]), a slot
 * you've moved there taken as your time; the app's events taken out of a calendar no longer written to
 * ([clearOldCalendars]); the calendar watched for changes ([watchCalendar], [calendarChanged]); and your
 * answers about its events ([answer]). Built like [com.thomaswcode.decrastination.block.Focus]: its
 * stores, the clock and small callbacks in its constructor, the calendar provider behind a port
 * ([CalendarProvider]: [SlotCalendar] on the phone).
 */
@OptIn(FlowPreview::class)
class CalendarWriter(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    /** The versions of its events the app has written to calendars, so a copy of one is known ([SlotCalendarDiff.isCopy]). */
    private val calendarVersions: JsonStore<CalendarVersions>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** The calendar's events, read and written. */
    private val provider: CalendarProvider,
    /** A move of yours written back, and why, said ([SlotCalendar.sayRefused]). */
    private val sayRefused: (move: SlotCalendarDiff.HandMove, zone: ZoneId, why: String) -> Unit,
    /** A move of yours taken as your time: a change of yours, kept and applied at once ([com.thomaswcode.decrastination.tasks.Instructions.addOwnChange]). */
    private val addOwnChange: suspend (id: String, text: String, about: About, change: Change) -> Unit,
    /** The plan made again ([com.thomaswcode.decrastination.plan.PlanWatcher.poke]). */
    private val replan: () -> Unit,
    /** His questions about the events named a key settled ([com.thomaswcode.decrastination.felix.Conversation.settleEvent]). */
    private val settleEvent: suspend (String) -> Unit,
    /** The calendar read again, and today's record made again with it ([com.thomaswcode.decrastination.sync.Sync.refreshCalendar], [com.thomaswcode.decrastination.learn.Briefing.replanToday]). */
    private val reread: suspend () -> Unit,
    /** How long plans are quiet before the calendar's written: [CALENDAR_WRITE_QUIET_MS], but in a test. */
    private val quietMs: Long = CALENDAR_WRITE_QUIET_MS,
) {
    /**
     * The plan to write to the calendar, once plans have been quiet [quietMs] (17 Oct): a burst of
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
     * once could insert a slot twice.
     */
    internal suspend fun writeSlots(made: Plan) {
        clearOldCalendars()
        val calendarId = settings.value.calendarWriteId ?: return
        if (!provider.allowed()) return
        val zone = clock.zone()
        // The same slots as last written, and nothing changed in the calendar since (AR-08): it isn't read again.
        val desired = SlotCalendarDiff.desired(made, zone, runtime.value.microPlans)
        if (!calendarTouched && lastWritten == (calendarId to desired)) return
        calendarTouched = false
        val byId = tasks.value.tasks.associateBy { it.id }
        val before = runtime.value.calendarWritten
        // A copy is one of the versions earlier writes made ([SlotCalendarDiff.isCopy]), never one of
        // this write's: an event of yours under a slot's title at its times isn't the app's.
        val known = calendarVersions.value.written.toSet()
        val written = try {
            withContext(Dispatchers.IO) {
                write(calendarId, made, zone, before, known, runtime.value.microPlans) { move ->
                    val task = move.desired.taskId?.let(byId::get)
                    when {
                        task == null -> "its task is no longer there"
                        Instructions.latePin(change(move, zone), task, zone, clock.now()) -> "it's due before then"
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
        // one an event of yours already had (#48's third review): remembered, the next write would
        // take yours for a copy.
        val moved = written.taken.map { it.desired.uri }.toSet()
        runCatching {
            val versions = desired.filter { d -> d.uri !in moved }.map { d -> SlotCalendarDiff.version(calendarId, d.title, d.start, d.end) }.filterNot { it in written.yours }
            calendarVersions.update { it.copy(written = SlotCalendarDiff.remembered(it.written, versions)) }
        }.onFailure { Log.w(AppGraph.TAG, "Remembering what was written to the calendar failed", it) }
        // A move taken counts as written only once its instruction is in: stopped before, the next
        // write finds it a move again, rather than putting it back.
        val held = SlotCalendarDiff.pending(written.starts, before, written.taken)
        if (held != before) runtime.update { it.copy(calendarWritten = held) }
        written.refused.forEach { (move, why) -> sayRefused(move, zone, why) }
        lastWritten = calendarId to desired
        for (move in written.taken) {
            val taskId = move.desired.taskId ?: continue
            addOwnChange(UUID.randomUUID().toString(), "Moved in the calendar", About(taskId = taskId, taskTitle = byId[taskId]?.title), change(move, zone))
            runtime.update { it.copy(calendarWritten = it.calendarWritten + (move.desired.uri to move.existing.start)) }
        }
    }

    /** The app's events taken out of calendars no longer written to, each kept on the list till that's done. */
    private suspend fun clearOldCalendars() {
        val waiting = runtime.value.calendarsToClear
        if (waiting.isEmpty()) return
        val versions = calendarVersions.value.written.toSet()
        val cleared = withContext(Dispatchers.IO) { waiting.filter { clear(it, versions) } }
        if (cleared.isNotEmpty()) runtime.update { it.copy(calendarsToClear = it.calendarsToClear - cleared.toSet()) }
    }

    /**
     * What a write left: each URI's start as it now stands, and the moves you made, taken or written
     * back (with why); and the versions it wrote that an event of yours already has, not to be
     * remembered ([SlotCalendarDiff.yours]).
     */
    data class Written(val starts: Map<String, Long>, val taken: List<SlotCalendarDiff.HandMove>, val refused: List<Pair<SlotCalendarDiff.HandMove, String>>, val yours: Set<Long> = emptySet())

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
     */
    private fun write(calendarId: Long, plan: Plan, zone: ZoneId, written: Map<String, Long>, versions: Set<Long>, micro: Map<String, MicroPlan> = emptyMap(), refuses: (SlotCalendarDiff.HandMove) -> String?): Written {
        val from = plan.today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = plan.today.plusDays(SlotCalendarDiff.DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val desired = SlotCalendarDiff.desired(plan, zone, micro)
        // Those with none of its marks in its words: copies of its own, to delete, and events of yours.
        val unmarked = provider.unmarked(calendarId)
        val copies = unmarked.filter { SlotCalendarDiff.isCopy(calendarId, it.title, it.description, it.start, it.end, versions) }
        val diff = SlotCalendarDiff.diff(desired, provider.existing(calendarId, from, to, desired.map { it.uri }) + copies, written)
        val (taken, refused) = SlotCalendarDiff.judge(diff.handMoves, refuses)
        provider.apply(calendarId, zone, diff.inserts, diff.updates.map { it.existing.id to it.desired } + refused.map { it.first.existing.id to it.first.desired }, diff.deletes.map { it.id })
        return Written(desired.associate { it.uri to it.start } + taken.associate { it.desired.uri to it.existing.start }, taken, refused, SlotCalendarDiff.yours(calendarId, desired, unmarked, versions))
    }

    /**
     * The app's events in [calendarId], all of them: a calendar no longer written to is left as it
     * was before. False when they can't be (no permission, or the provider failed), to be tried again.
     */
    private fun clear(calendarId: Long, versions: Set<Long>): Boolean {
        if (!provider.allowed()) return false
        return runCatching {
            provider.deleteOwn(calendarId)
            // And copies of its events that lost the app's marks (17 Oct), by their words and [versions].
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
        }.onSuccess { calendarWatched = it }.onFailure { Log.w(AppGraph.TAG, "Can't watch the calendar", it) }
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
                val was = runtime.value.calendarWrittenTo
                if (was == id) return@collect
                // The old one kept on the list to clear till it is: without the permission now, or
                // should the provider fail, it's tried again at the next replan.
                runtime.update { state ->
                    state.copy(calendarWrittenTo = id, calendarWritten = emptyMap(), calendarsToClear = (state.calendarsToClear + listOfNotNull(was)).distinct() - listOfNotNull(id).toSet())
                }
                clearOldCalendars()
                replan()
            }
        }
        // The calendar written once plans have been quiet a while.
        scope.launch {
            toWrite.debounce(quietMs).collect { made -> runCatching { writeSlots(made) }.onFailure { Log.w(AppGraph.TAG, "Writing the calendar failed", it) } }
        }
    }

    /**
     * Your [answer] about every event named [key] (null: forgotten, for the rules to judge again):
     * kept, his question answered, the plan redone, and today's as the briefing recorded it.
     */
    suspend fun answer(key: String, answer: String?) {
        runtime.update { it.copy(eventAnswers = if (answer == null) it.eventAnswers - key else it.eventAnswers + (key to answer)) }
        settleEvent(key)
        reread()
    }

    companion object {
        /** How long plans are quiet before the calendar's written ([toWrite]). */
        private const val CALENDAR_WRITE_QUIET_MS = 5_000L
    }
}
