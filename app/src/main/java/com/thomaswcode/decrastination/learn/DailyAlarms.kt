package com.thomaswcode.decrastination.learn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The alarms for [Daily]'s times: the morning briefing, the Sunday check-in's reminder, and the weekly review after it. */
object DailyAlarms {
    private const val RETRY_CODE = 1

    /** Sets the three alarms for their next times; each replaces the one before. */
    fun schedule(context: Context) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val settings = graph.settings.value
        val ran = graph.runtime.value.dailyRanAt
        fun from(action: String) = Daily.from(ran[action], now, zone)
        set(context, Daily.ACTION_BRIEFING, Daily.nextBriefing(from(Daily.ACTION_BRIEFING), zone, settings, graph.school.value))
        set(context, Daily.ACTION_CHECK_IN, Daily.nextSunday(from(Daily.ACTION_CHECK_IN), zone, settings.checkInMin))
        set(context, Daily.ACTION_REVIEW, Daily.nextSunday(from(Daily.ACTION_REVIEW), zone, settings.checkInMin + Daily.REVIEW_AFTER_MIN))
        // One whose run failed: tried again at its time too, on an alarm of its own, so its next
        // occurrence stands; none waiting, any such alarm is cancelled.
        val retries = graph.runtime.value.dailyRetries
        for (action in listOf(Daily.ACTION_BRIEFING, Daily.ACTION_CHECK_IN, Daily.ACTION_REVIEW)) {
            val retry = retries[action]
            if (retry != null) set(context, action, retry.at, RETRY_CODE) else cancel(context, action, RETRY_CODE)
        }
    }

    private fun pending(context: Context, action: String, code: Int, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        action.hashCode() + code,
        Intent(context, DailyReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    private fun cancel(context: Context, action: String, code: Int) {
        pending(context, action, code, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java)?.cancel(it) }
    }

    private fun set(context: Context, action: String, at: Long, code: Int = 0) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, action, code, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
        }
    }
}

/** The briefing, the check-in's reminder and the review, each when its alarm goes off; then the next. */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val graph = AppGraph.get(context)
            try {
                // Noted first, so setting the alarms again doesn't bring this one back.
                graph.runtime.update { it.copy(dailyRanAt = it.dailyRanAt + (action to graph.clock.now())) }
                when (action) {
                    Daily.ACTION_BRIEFING -> {
                        // What's remembered that's run out let go of first, so what Settings lists is what he's told.
                        runCatching { graph.conversation.forgetExpired() }.onFailure { Log.w(AppGraph.TAG, "Forgetting what's run out failed", it) }
                        // A new week's work in place before the day's plan is said (D-124).
                        runCatching { graph.ownTasks.rollWeeks() }.onFailure { Log.w(AppGraph.TAG, "Rolling weekly work failed", it) }
                        Briefing.run(context)
                        // Felix's notes and reminders kept as long as they're wanted, each morning: not the briefing's
                        // failure, so not one that runs it again.
                        runCatching { graph.conversation.pruneFelix() }.onFailure { Log.w(AppGraph.TAG, "Pruning Felix's notes and reminders failed", it) }
                    }
                    Daily.ACTION_CHECK_IN -> AppGraph.get(context).conversation.remindCheckIn()
                    // As a job: a model call can outlast what a receiver is let run.
                    Daily.ACTION_REVIEW -> ReviewWorker.enqueue(context, ifDue = true)
                }
                // Done: no retry of it left waiting.
                graph.runtime.update { it.copy(dailyRetries = it.dailyRetries - action) }
            } catch (error: Exception) {
                Log.w(AppGraph.TAG, "Daily $action failed", error)
                // Not done: tried again shortly, a few times, rather than lost till its next day or week.
                runCatching { graph.runtime.update { it.copy(dailyRetries = Daily.failed(it.dailyRetries, action, graph.clock.now())) } }
            } finally {
                DailyAlarms.schedule(context)
                pending.finish()
            }
        }
    }
}
