package com.thomaswcode.decrastination.block

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Alarms at the next slot's start and at the next slot's end, its grace after it (PL-13): a start
 * makes the plan again; an end with its piece undone is a missed slot ([Slots.onEnd]), which moves.
 * Set again with every plan ([arm]), so they follow the slots wherever a replan puts them.
 */
object SlotAlarms {
    const val ACTION_SLOT_START = "com.thomaswcode.decrastination.SLOT_START"
    const val ACTION_SLOT_END = "com.thomaswcode.decrastination.SLOT_END"
    const val EXTRA_KEY = "key"

    /** How long after its slot's end a piece can still be ticked before the slot's missed. */
    const val GRACE_MS = com.thomaswcode.decrastination.core.Slotter.GRACE_MS

    /** [plan]'s next work slot to start after [now], and the next whose end and grace are still to come, today's or tomorrow's. */
    fun arm(context: Context, plan: Plan, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(intent(context, ACTION_SLOT_START, null))
        alarms.cancel(intent(context, ACTION_SLOT_END, null))
        val work = (plan.todaySlots + plan.tomorrowBucket?.slots.orEmpty()).filter { it.kind == SlotKind.Work }
        work.firstOrNull { it.start > now }?.let { set(alarms, it.start, intent(context, ACTION_SLOT_START, it.key)) }
        work.firstOrNull { it.end + GRACE_MS > now }?.let { set(alarms, it.end + GRACE_MS, intent(context, ACTION_SLOT_END, it.key)) }
    }

    private fun set(alarms: AlarmManager, at: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, intent)
        }
    }

    /** One pending intent for each of the two, its slot's key in it (a replan sets each again). */
    private fun intent(context: Context, action: String, key: String?): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_SLOT_START) 1 else 2,
        Intent(context, SlotReceiver::class.java).setAction(action).apply { key?.let { putExtra(EXTRA_KEY, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Today's slots as they pass (PL-13): one begun makes the plan again; one over, its piece undone,
 * is missed: kept on record, and no longer held to its time, so the next plan moves it.
 */
class Slots(private val graph: AppGraph) {
    /** [key]'s slot has begun (phase 5 has more to do here). */
    fun onStart(@Suppress("UNUSED_PARAMETER") key: String) = graph.watcher.poke()

    /** [key]'s slot is over, and its grace: its piece still planned is missed, and moves. */
    suspend fun onEnd(key: String, now: Long = graph.clock.now()) {
        val plan = graph.plan(now = now)
        // Done (ticked, or finished in a session): nothing missed.
        val chunk = plan.ordered.firstOrNull { it.key == key } ?: return
        graph.runtime.update { it.copy(slots = it.slots.on(plan.today).missed(key, chunk.taskId, now)) }
        graph.watcher.poke()
    }

    /**
     * Slots of today's whose end and grace went by unmarked (the phone off at the alarm): as if it
     * rang. By the starts the last plan kept: a slot under way more than its grace is looked at.
     */
    suspend fun sweep(now: Long = graph.clock.now()) {
        val today = Instant.ofEpochMilli(now).atZone(graph.clock.zone()).toLocalDate()
        val state = graph.runtime.value.slots.on(today)
        val missed = state.missed.map { it.key }.toSet()
        val started = state.previous.filter { (key, start) -> key !in missed && start + SlotAlarms.GRACE_MS < now }
        if (started.isEmpty()) return
        val plan = graph.plan(now = now)
        for ((key, start) in started) {
            val chunk = plan.ordered.firstOrNull { it.key == key } ?: continue
            if (start + chunk.minutes * 60_000L + SlotAlarms.GRACE_MS < now) onEnd(key, now)
        }
    }
}

/** A slot's alarm: its start or its end ([SlotAlarms]). */
class SlotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(SlotAlarms.EXTRA_KEY) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val graph = AppGraph.get(context)
                when (intent.action) {
                    SlotAlarms.ACTION_SLOT_START -> graph.slots.onStart(key)
                    SlotAlarms.ACTION_SLOT_END -> graph.slots.onEnd(key)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
