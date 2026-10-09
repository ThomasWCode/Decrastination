package com.thomaswcode.decrastination.learn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The app's own times of day (Phase 5): the morning briefing (Q13: 07:00 on school days, 08:30 at
 * weekends), the Sunday check-in's reminder, and the weekly review after it.
 */
object Daily {
    const val ACTION_BRIEFING = "com.thomaswcode.decrastination.action.BRIEFING"
    const val ACTION_CHECK_IN = "com.thomaswcode.decrastination.action.CHECK_IN"
    const val ACTION_REVIEW = "com.thomaswcode.decrastination.action.REVIEW"

    /** The next morning briefing after [now]. */
    fun nextBriefing(now: Long, zone: ZoneId, settings: Settings): Long {
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        while (true) {
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
            val minute = if (weekend) settings.briefingWeekendMin else settings.briefingWeekdayMin
            val at = day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
            if (at > now) return at
            day = day.plusDays(1)
        }
    }

    /** The next Sunday at [minuteOfDay] after [now]. */
    fun nextSunday(now: Long, zone: ZoneId, minuteOfDay: Int): Long {
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        while (true) {
            if (day.dayOfWeek == DayOfWeek.SUNDAY) {
                val at = day.atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            day = day.plusDays(1)
        }
    }

    /** The check-in's time on [now]'s day. */
    fun checkInOn(now: Long, zone: ZoneId, minuteOfDay: Int): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()

    /** The review runs this long after the check-in's reminder, with or without the answers. */
    const val REVIEW_AFTER_MIN = 90

    /** Sets the three alarms for their next times; each replaces the one before. */
    fun schedule(context: Context) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val settings = graph.settings.value
        set(context, ACTION_BRIEFING, nextBriefing(now, zone, settings))
        set(context, ACTION_CHECK_IN, nextSunday(now, zone, settings.checkInMin))
        set(context, ACTION_REVIEW, nextSunday(now, zone, settings.checkInMin + REVIEW_AFTER_MIN))
    }

    private fun set(context: Context, action: String, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, DailyReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
        }
    }

    /** "Monday 5 October", the week a check-in is about. */
    fun weekOf(now: Long, zone: ZoneId): String {
        val date: ZonedDateTime = Instant.ofEpochMilli(now).atZone(zone)
        return date.toLocalDate().with(DayOfWeek.MONDAY).toString()
    }
}

/** The briefing, the check-in's reminder and the review, each when its alarm goes off; then the next. */
class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    Daily.ACTION_BRIEFING -> Briefing.run(context)
                    Daily.ACTION_CHECK_IN -> CheckIns.remind(context)
                    Daily.ACTION_REVIEW -> Review.run(context, ifDue = true)
                }
            } catch (error: Exception) {
                Log.w(AppGraph.TAG, "Daily ${intent.action} failed", error)
            } finally {
                Daily.schedule(context)
                pending.finish()
            }
        }
    }
}
