package com.thomaswcode.decrastination.learn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.MainActivity

/**
 * The morning briefing (Q13): today's plan in a notification, at 07:00 on school days and 08:30 at
 * weekends; a Teams sync offered at the next unlock; and the day's plan recorded for the capacity
 * check, with yesterday's marked done or not.
 */
object Briefing {
    private const val ID = 4001

    /** How long after the briefing an unlock still brings the morning's Teams sync. */
    private const val MORNING_OFFER_MS = 90 * 60_000L

    /** The notification's title and lines for [plan]: pure, for the tests. */
    fun summary(plan: Plan): Pair<String, List<String>> {
        val today = plan.todayBucket?.chunks.orEmpty()
        if (today.isEmpty()) {
            val tomorrow = plan.tomorrowBucket?.chunks.orEmpty()
            return "Nothing planned today" to listOfNotNull(tomorrow.takeIf { it.isNotEmpty() }?.let { "Tomorrow: ${it.size} thing${if (it.size == 1) "" else "s"}, ${Format.minutes(it.sumOf { c -> c.minutes })}" })
        }
        val title = "Today: ${today.size} thing${if (today.size == 1) "" else "s"}, ${Format.minutes(today.sumOf { it.minutes })}"
        val lines = today.take(6).map { chunk ->
            val flag = when {
                chunk.overdue -> " (overdue)"
                chunk.dueToday -> " (due today)"
                chunk.behind -> " (behind)"
                else -> ""
            }
            "${chunk.label}$flag · ${Format.minutes(chunk.minutes)}"
        } + listOfNotNull((today.size - 6).takeIf { it > 0 }?.let { "and $it more" })
        return title to lines
    }

    suspend fun run(context: Context) {
        val graph = AppGraph.get(context)
        // The calendar read first: woken by its alarm, the app has had no time to read it yet.
        CalendarTime.refresh(context)
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val plan = graph.plan()
        val today = Days.record(plan, zone)
        graph.log.update { log ->
            val finished = log.days.map { day -> if (day.full == null && day.date < today.date) Days.finish(day, log, zone) else day }
            log.copy(days = finished.filterNot { it.date == today.date } + today).trimmed(now)
        }
        graph.runtime.update { it.copy(teamsAuto = it.teamsAuto.copy(morningUntil = now + MORNING_OFFER_MS)) }
        val (title, lines) = summary(plan)
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notify.post(
            context,
            ID,
            NotificationCompat.Builder(context, Channels.DAILY)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle(title)
                .setContentText(lines.firstOrNull().orEmpty())
                .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
