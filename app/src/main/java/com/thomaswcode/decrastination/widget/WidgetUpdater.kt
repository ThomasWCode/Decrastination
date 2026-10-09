package com.thomaswcode.decrastination.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.learn.Daily
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Redraws every placed widget, and schedules the next redraw for when the plan turns over by itself. */
object WidgetUpdater {
    const val ACTION_REDRAW = "com.thomaswcode.decrastination.action.REDRAW"

    /** How late a redraw may come: Android 12+ widens shorter windows to 10 minutes anyway. */
    private const val REDRAW_WINDOW_MS = 10 * 60_000L

    suspend fun update(context: Context) {
        NextWidget().updateAll(context)
        scheduleRedraw(context, AppGraph.get(context))
    }

    /**
     * A non-waking alarm for the next moment the plan changes with no new data: midnight, or the
     * next deadline (a chunk turns overdue). Delivered within [REDRAW_WINDOW_MS] while the phone is
     * in use, and at its next wake-up otherwise. With no widget placed it's cancelled instead.
     */
    fun scheduleRedraw(context: Context, graph: AppGraph) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val placed = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextWidgetReceiver::class.java))
        if (placed.isEmpty()) {
            alarms.cancel(redrawIntent(context))
            return
        }
        val teamsAsOf = graph.tasks.value.status(Source.Teams).dataAsOf
        alarms.setWindow(AlarmManager.RTC, nextRedrawAt(graph.plan(), graph.clock.zone(), graph.settings.value, teamsAsOf), REDRAW_WINDOW_MS, redrawIntent(context))
    }

    /** While the evening's hours run out, the plan shifts as they do: a chunk stops fitting today. */
    private const val WORKING_REDRAW_MS = 15 * 60_000L

    /**
     * The next moment the plan can change with no new data: midnight, the next deadline (a chunk
     * turns overdue), the moment a chunk can be started (an Anki deck's cards at 04:00), the start
     * or end of today's working hours, and, while they last, every 15 minutes, since the time left
     * today shrinks with every minute; and the moment Teams' data, as of [teamsAsOf], turns stale
     * and earns its warning. A minute away at the soonest.
     */
    fun nextRedrawAt(plan: Plan, zone: ZoneId, settings: Settings, teamsAsOf: Long? = null): Long {
        val now = plan.now
        val midnight = plan.today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val deadline = plan.ordered.map { it.deadline }.filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        val startable = plan.ordered.mapNotNull { it.availableAt }.filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        val stale = teamsAsOf?.plus(WidgetModel.TEAMS_STALE_MS)?.takeIf { it > now } ?: Long.MAX_VALUE
        val weekend = plan.today.dayOfWeek == DayOfWeek.SATURDAY || plan.today.dayOfWeek == DayOfWeek.SUNDAY
        val hours = if (weekend) settings.weekendHours else settings.weekdayHours
        val start = plan.today.atStartOfDay().plusMinutes(hours.startMin.toLong()).atZone(zone).toInstant().toEpochMilli()
        val end = plan.today.atStartOfDay().plusMinutes(hours.endMin.toLong()).atZone(zone).toInstant().toEpochMilli()
        val working = if (now in start until end) now + WORKING_REDRAW_MS else Long.MAX_VALUE
        val boundary = listOf(start, end).filter { it > now }.minOrNull() ?: Long.MAX_VALUE
        return maxOf(minOf(midnight, deadline, startable, stale, working, boundary), now + 60_000L)
    }

    fun cancelRedraw(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(redrawIntent(context))
    }

    private fun redrawIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, NextWidgetReceiver::class.java).setAction(ACTION_REDRAW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class NextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextWidget()

    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelRedraw(context)
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REDRAW_ACTIONS) {
            super.onReceive(context, intent)
            return
        }
        // No sync, just draw the plan again: at midnight or a deadline, or because the time zone
        // or clock changed, which moves both the day boundaries and the next alarm.
        // The zone or the clock moved: the daily alarms are wall-clock times, so they're set again.
        if (intent.action != WidgetUpdater.ACTION_REDRAW) Daily.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.update(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val REDRAW_ACTIONS = setOf(WidgetUpdater.ACTION_REDRAW, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)
    }
}
