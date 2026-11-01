package com.thomaswcode.decrastination.block

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.felix.FelixRules
import com.thomaswcode.decrastination.felix.FelixWorker
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.sync.protocol.NoteActivity
import com.thomaswcode.decrastination.sync.protocol.StartTimer
import com.thomaswcode.decrastination.sync.protocol.StopTimer
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.MainActivity
import com.thomaswcode.decrastination.ui.timerOverText
import com.thomaswcode.decrastination.ui.timerOverTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A timer on a piece (D-112) on the phone: Start and Stop as the card, the sheet, the widget and its
 * notification ask, and its notifications and alarms made to match what Focus holds ([sync]). The one place that
 * touches them; the timer itself is Focus's. It blocks nothing, earns nothing and teaches nothing: it's a countdown,
 * and your word you're on the piece, which holds Felix's messages about its task ([FelixRules.liftAt]).
 */
object TimerAlarms {
    /** Its zero. */
    const val ACTION_END = "com.thomaswcode.decrastination.TIMER_END"
    /** *Stop* on its notification. */
    const val ACTION_STOP = "com.thomaswcode.decrastination.TIMER_STOP"
    /** The hold on Felix's messages about its task lifted: his look, to say a slot missed meanwhile. */
    const val ACTION_HOLD_END = "com.thomaswcode.decrastination.TIMER_HOLD_END"

    /**
     * A timer started on [taskId]'s piece ([key], else its step [stepIndex]'s next): something done on its task, so
     * Felix's nudge for its slot stays off after a Stop too; its notification and alarm set. What to tell you.
     */
    suspend fun start(context: Context, taskId: String, stepIndex: Int?, key: String? = null): String {
        val graph = AppGraph.get(context)
        val before = graph.focus.timer?.takeIf { it.running }
        val timer = graph.act(StartTimer(taskId, stepIndex, key)) ?: return "That piece has changed since (done, or planned again)."
        graph.act(NoteActivity(listOf(taskId)))
        sync(context)
        return when {
            before == timer -> "The timer's already on it."
            before != null -> "Timer moved from ${before.label}: ${Format.minutes(timer.minutes)}."
            else -> "Timer started: ${Format.minutes(timer.minutes)}."
        }
    }

    /** The running timer stopped by hand: its notifications gone, and Felix's hold lifted 30 minutes on ([sync]). What to tell you. */
    suspend fun stop(context: Context): String {
        val stopped = AppGraph.get(context).act(StopTimer)
        sync(context)
        return if (stopped) "Timer stopped." else "No timer was running."
    }

    /**
     * The notifications and alarms made to match the timer as Focus holds it, so this can run any number of times: at
     * each change of it, at a start (a restart or a boot), at its alarms. Running, its countdown, and an alarm at its zero;
     * its zero gone by (here, or while the app was down), "time's up" said once while it's [timely], the countdown
     * retitled to run on past it, and an alarm when Felix's hold lifts; stopped by hand, both gone, and that alarm where
     * it's still ahead; none, everything gone. "Time's up" stays only beside the timer it's about ([keepsTimeUp]).
     */
    suspend fun sync(context: Context) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        // Its zero gone by and not yet told: told now, once; said only while it's timely, not hours on (a boot).
        graph.focus.timer?.let { t -> if (t.running && !t.told && t.endsAt <= now && graph.focus.timerOver(now) && timely(t, now)) over(context, t) }
        val timer = graph.focus.timer
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms?.cancel(alarm(context, ACTION_END))
        alarms?.cancel(alarm(context, ACTION_HOLD_END))
        if (!keepsTimeUp(timer)) graph.notifier.cancel(NoticeId.TimerOver)
        if (timer?.running == true) {
            graph.notifier.post(NoticeId.Timer, running(context, timer))
            if (alarms != null && !timer.told) SlotAlarms.set(alarms, timer.endsAt, alarm(context, ACTION_END))
        } else {
            graph.notifier.cancel(NoticeId.Timer)
        }
        // Past its zero or stopped: his look when the hold lifts, so a slot missed meanwhile is said then, not at his next.
        if (alarms != null && timer != null && (timer.told || !timer.running)) FelixRules.liftAt(timer).takeIf { it > now }?.let { SlotAlarms.set(alarms, it, alarm(context, ACTION_HOLD_END)) }
    }

    /**
     * Whether "Time's up" for [timer]'s zero, gone by unseen (the phone off, or its clock moved, at its alarm), is still
     * worth saying at [now]: within Felix's hold after it, not hours on, when it's marked told without a word.
     */
    fun timely(timer: PieceTimer, now: Long): Boolean = now < FelixRules.liftAt(timer)

    /** Whether "Time's up" may stay showing beside [timer]: only while it runs past its zero; a fresh one replacing it, a stop or its piece done takes it away. */
    fun keepsTimeUp(timer: PieceTimer?): Boolean = timer != null && timer.running && timer.told

    /**
     * The running timer's notification: its piece, its minutes and *Stop*, counting down to its zero by Android's own
     * chronometer, so nothing wakes the phone to keep it current; past its zero, "Over on", the same chronometer running
     * on as −mm:ss (the owner's choice: Q68). Its tap opens the task's sheet.
     */
    private fun running(context: Context, timer: PieceTimer) = Notify.builder(context, Channels.TIMER)
        .setContentTitle(if (timer.told) "Over on: ${timer.label}" else timer.label)
        .setContentText(if (timer.told) "Stop when you're done." else "${Format.minutes(timer.minutes)}. Stop when you're done.")
        .setWhen(timer.endsAt)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .let { Notify.opening(it, context, NoticeId.Timer.code(0), sheet(context, timer)) }
        .let { Notify.button(it, context, "Stop", NoticeId.Timer.code(1), Intent(context, TimerReceiver::class.java).setAction(ACTION_STOP)) }
        .build()

    /** "Time's up", once at its zero, popping up whatever the hour: you started it, and it isn't Felix's. */
    private fun over(context: Context, timer: PieceTimer) {
        AppGraph.get(context).notifier.post(
            NoticeId.TimerOver,
            Notify.builder(context, Channels.TIMER_OVER)
                .setContentTitle(timerOverTitle(timer))
                .setContentText(timerOverText(timer))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .let { Notify.opening(it, context, NoticeId.TimerOver.code(), sheet(context, timer)) }
                .build(),
        )
    }

    /** The timer's task opened on its sheet in Tasks, as one of your own opens. */
    private fun sheet(context: Context, timer: PieceTimer): Intent = Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
        .putExtra(MainActivity.EXTRA_TASK, timer.taskId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** One pending intent for each of the two alarms (each set again by [sync]). */
    private fun alarm(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_END) 1 else 2,
        Intent(context, TimerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The timer's alarms and its notification's *Stop* ([TimerAlarms]). */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    // Its zero: "time's up" said, the countdown retitled.
                    TimerAlarms.ACTION_END -> TimerAlarms.sync(context)
                    TimerAlarms.ACTION_STOP -> TimerAlarms.stop(context)
                    // The hold lifted: his look queued as a slot's alarm queues it, as it may call the model, which can take
                    // longer than a receiver is given.
                    TimerAlarms.ACTION_HOLD_END -> FelixWorker.enqueue(context)
                }
            } catch (e: Exception) {
                // Logged, not a crash: it would take the focus service's connection with it.
                Log.w(AppGraph.TAG, "The timer's alarm failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
