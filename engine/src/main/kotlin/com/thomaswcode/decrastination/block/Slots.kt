package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SessionState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.ports.Log
import java.time.Instant

/**
 * Today's slots as they pass (PL-13): one begun makes the plan again; one over, its piece undone,
 * is missed: kept on record, and no longer held to its time, so the next plan moves it.
 */
class Slots(
    private val session: JsonStore<SessionState>,
    private val settings: JsonStore<Settings>,
    private val tasks: JsonStore<TaskState>,
    private val school: JsonStore<SchoolCalendar>,
    private val clock: WallClock,
    private val planning: Planning,
    private val focus: Focus,
) {
    /** [key]'s slot has begun, or a break outside's has ended (D-113): the plan made again. */
    fun onStart(key: String) {
        Log.i(Log.TAG, "Slot begun or a break over: $key")
        planning.watcher.poke()
    }

    /** [key]'s slot is over, and its grace: its piece still planned is missed, and moves; recorded as it was [laid] (LR-09). */
    suspend fun onEnd(key: String, laid: Focus.Laid? = null, now: Long = clock.now()) {
        val plan = planning.plan(now = now)
        // Done (ticked, or by its source): nothing missed.
        val chunk = plan.ordered.firstOrNull { it.key == key }
        if (chunk == null) {
            Log.i(Log.TAG, "Slot over, its piece done: $key")
            return
        }
        Log.i(Log.TAG, "Slot missed: $key")
        focus.missSlot(key, laid ?: session.value.slots.on(plan.today).previous[key]?.let { Focus.Laid(it, it + chunk.minutes * 60_000L, WindowKind.Evening) }, now)
        session.update { it.copy(slots = it.slots.on(plan.today).missed(key, chunk.taskId, now)) }
        planning.watcher.poke()
    }

    /** The kind of today's window [time] falls in, as the plan lays them (the evening's, where none does). */
    private fun windowAt(time: Long): WindowKind {
        val zone = clock.zone()
        val at = Instant.ofEpochMilli(time).atZone(zone)
        val minute = at.hour * 60 + at.minute
        val windows = SchoolDays.windows(at.toLocalDate(), settings.value, tasks.value.timetable, school.value)
        return windows.firstOrNull { minute >= it.startMin && minute < it.endMin }?.kind ?: WindowKind.Evening
    }

    /**
     * Slots of today's whose end and grace went by unmarked (the phone off at the alarm): as if it
     * rang. By the starts the last plan kept: a slot under way more than its grace is looked at.
     */
    suspend fun sweep(now: Long = clock.now()) {
        val today = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate()
        val state = session.value.slots.on(today)
        val started = state.started(now, Slotter.GRACE_MS)
        if (started.isEmpty()) return
        val plan = planning.plan(now = now)
        for ((key, start) in started) {
            val chunk = plan.ordered.firstOrNull { it.key == key } ?: continue
            if (start + chunk.minutes * 60_000L + Slotter.GRACE_MS < now) onEnd(key, Focus.Laid(start, start + chunk.minutes * 60_000L, windowAt(start)), now)
        }
    }
}
