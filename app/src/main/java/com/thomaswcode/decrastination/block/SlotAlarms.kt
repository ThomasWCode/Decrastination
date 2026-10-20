package com.thomaswcode.decrastination.block

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.felix.FelixWorker
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
    /** Felix's next look (LR-03). */
    const val ACTION_FELIX_CHECK = "com.thomaswcode.decrastination.FELIX_CHECK"
    /** Your reminders' next moment (Stage 8a). */
    const val ACTION_REMINDER = "com.thomaswcode.decrastination.REMINDER"

    /** How long after a failed sweep the reminders' alarm rings again: its moment's gone by, so it would ring at once, and a failure that lasts mustn't wake the phone over and over. */
    const val REMINDER_RETRY_MS = 5 * 60_000L
    const val EXTRA_KEY = "key"
    const val EXTRA_START = "start"
    const val EXTRA_END = "end"
    const val EXTRA_WINDOW = "window"

    /** How long after its slot's end a piece can still be ticked before the slot's missed. */
    const val GRACE_MS = com.thomaswcode.decrastination.core.Slotter.GRACE_MS

    /** Alarms at [plan]'s [next] start and end. */
    fun arm(context: Context, plan: Plan, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(intent(context, ACTION_SLOT_START, null))
        alarms.cancel(intent(context, ACTION_SLOT_END, null))
        val (start, end) = next(plan, now)
        start?.let { set(alarms, it.start, intent(context, ACTION_SLOT_START, it)) }
        end?.let { set(alarms, it.end + GRACE_MS, intent(context, ACTION_SLOT_END, it)) }
    }

    /** Felix's next look (LR-03) at [at], or none: set again with each look. */
    fun armFelix(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, SlotReceiver::class.java).setAction(ACTION_FELIX_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(intent)
        at?.let { set(alarms, it, intent) }
    }

    /**
     * Your reminders' next wake ([com.thomaswcode.decrastination.core.Reminders.nextWake], Stage 8a) at [at], or
     * none: one's moment, or the start of his window to write for one; set again with every plan, as a SlotStart's
     * or a BeforeDue's moment moves with it, with every change to them, and at each of its own.
     */
    fun armReminder(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            4,
            Intent(context, SlotReceiver::class.java).setAction(ACTION_REMINDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(intent)
        at?.let { set(alarms, it, intent) }
    }

    /**
     * [plan]'s next work slot to start after [now], and the next whose end and grace are still to
     * come, on whatever day they are: with nothing today or tomorrow, a later day's still rings.
     */
    fun next(plan: Plan, now: Long): Pair<Slot?, Slot?> {
        val work = plan.buckets.flatMap { it.slots }.filter { it.kind == SlotKind.Work }.sortedBy { it.start }
        return work.firstOrNull { it.start > now } to work.firstOrNull { it.end + GRACE_MS > now }
    }

    private fun set(alarms: AlarmManager, at: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, intent)
        }
    }

    /** One pending intent for each of the two, its slot in it: key, times and window (a replan sets each again). */
    private fun intent(context: Context, action: String, slot: Slot?): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_SLOT_START) 1 else 2,
        Intent(context, SlotReceiver::class.java).setAction(action).apply {
            slot?.let {
                putExtra(EXTRA_KEY, it.key)
                putExtra(EXTRA_START, it.start)
                putExtra(EXTRA_END, it.end)
                putExtra(EXTRA_WINDOW, it.window.name)
            }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Today's slots as they pass (PL-13): one begun makes the plan again; one over, its piece undone,
 * is missed: kept on record, and no longer held to its time, so the next plan moves it.
 */
class Slots(private val graph: AppGraph) {
    /** [key]'s slot has begun (phase 5 has more to do here). */
    fun onStart(key: String) {
        Log.i(AppGraph.TAG, "Slot begun: $key")
        graph.planning.watcher.poke()
    }

    /** [key]'s slot is over, and its grace: its piece still planned is missed, and moves; recorded as it was [laid] (LR-09). */
    suspend fun onEnd(key: String, laid: Focus.Laid? = null, now: Long = graph.clock.now()) {
        val plan = graph.planning.plan(now = now)
        // Done (ticked, or by its source): nothing missed.
        val chunk = plan.ordered.firstOrNull { it.key == key }
        if (chunk == null) {
            Log.i(AppGraph.TAG, "Slot over, its piece done: $key")
            return
        }
        Log.i(AppGraph.TAG, "Slot missed: $key")
        graph.focus.missSlot(key, laid ?: graph.stores.session.value.slots.on(plan.today).previous[key]?.let { Focus.Laid(it, it + chunk.minutes * 60_000L, WindowKind.Evening) }, now)
        graph.stores.session.update { it.copy(slots = it.slots.on(plan.today).missed(key, chunk.taskId, now)) }
        graph.planning.watcher.poke()
    }

    /** The kind of today's window [time] falls in, as the plan lays them (the evening's, where none does). */
    private fun windowAt(time: Long): WindowKind {
        val zone = graph.clock.zone()
        val at = Instant.ofEpochMilli(time).atZone(zone)
        val minute = at.hour * 60 + at.minute
        val windows = SchoolDays.windows(at.toLocalDate(), graph.settings.value, graph.tasks.value.timetable, graph.school.value)
        return windows.firstOrNull { minute >= it.startMin && minute < it.endMin }?.kind ?: WindowKind.Evening
    }

    /**
     * Slots of today's whose end and grace went by unmarked (the phone off at the alarm): as if it
     * rang. By the starts the last plan kept: a slot under way more than its grace is looked at.
     */
    suspend fun sweep(now: Long = graph.clock.now()) {
        val today = Instant.ofEpochMilli(now).atZone(graph.clock.zone()).toLocalDate()
        val state = graph.stores.session.value.slots.on(today)
        val missed = state.missed.map { it.key }.toSet()
        val started = state.previous.filter { (key, start) -> key !in missed && start + SlotAlarms.GRACE_MS < now }
        if (started.isEmpty()) return
        val plan = graph.planning.plan(now = now)
        for ((key, start) in started) {
            val chunk = plan.ordered.firstOrNull { it.key == key } ?: continue
            if (start + chunk.minutes * 60_000L + SlotAlarms.GRACE_MS < now) onEnd(key, Focus.Laid(start, start + chunk.minutes * 60_000L, windowAt(start)), now)
        }
    }
}

/** A slot's alarm: its start or its end; Felix's look; your reminder's moment ([SlotAlarms]). */
class SlotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(SlotAlarms.EXTRA_KEY)
        if (key == null && intent.action != SlotAlarms.ACTION_FELIX_CHECK && intent.action != SlotAlarms.ACTION_REMINDER) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val graph = AppGraph.get(context)
                when (intent.action) {
                    // A failure is logged, not a crash (it would take the focus service's connection with it), and his look's still queued.
                    SlotAlarms.ACTION_SLOT_START -> runCatching { graph.slots.onStart(key!!) }.onFailure { Log.w(AppGraph.TAG, "A slot's start failed", it) }
                    SlotAlarms.ACTION_SLOT_END -> {
                        // As the alarm had it laid, for its record.
                        val window = intent.getStringExtra(SlotAlarms.EXTRA_WINDOW)?.let { name -> WindowKind.entries.firstOrNull { it.name == name } }
                        val start = intent.getLongExtra(SlotAlarms.EXTRA_START, -1L)
                        val end = intent.getLongExtra(SlotAlarms.EXTRA_END, -1L)
                        runCatching { graph.slots.onEnd(key!!, if (window != null && start >= 0 && end >= start) Focus.Laid(start, end, window) else null) }
                            .onFailure { Log.w(AppGraph.TAG, "A slot's end failed", it) }
                    }
                    // Your reminder's moment (8a): sent now, with no call, so the receiver's time is enough; or the start of
                    // his window to write one, which the look after this writes. Either way the alarm's set for the next.
                    SlotAlarms.ACTION_REMINDER -> {
                        // A failure sending them is logged, so the alarm's still set again, a while on, and the look queued.
                        val sent = runCatching { graph.looks.sweepReminders() }.onFailure { Log.w(AppGraph.TAG, "Sending reminders failed", it) }.isSuccess
                        graph.armReminder(after = if (sent) null else graph.clock.now() + SlotAlarms.REMINDER_RETRY_MS)
                    }
                }
                // Each is a moment Felix may have something to say (LR-03): his look is work of its own,
                // as the model's answer may take longer than a receiver is given, and the app may go
                // once it's let go.
                FelixWorker.enqueue(context)
            } finally {
                pending.finish()
            }
        }
    }
}
