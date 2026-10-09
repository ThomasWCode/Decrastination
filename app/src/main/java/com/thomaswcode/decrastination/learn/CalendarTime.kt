package com.thomaswcode.decrastination.learn

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import com.thomaswcode.decrastination.widget.WidgetUpdater
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's calendar as busy time (Q7, PLAN.md Phase 5): the next fortnight's events, judged one
 * by one ([EventJudge]), given to the planner; an all-day event the rules can't judge is asked
 * about once, in a notification, within the week before it.
 */
object CalendarTime {
    const val PERMISSION = Manifest.permission.READ_CALENDAR
    const val ACTION_ANSWER = "com.thomaswcode.decrastination.action.EVENT_ANSWER"
    const val EXTRA_KEY = "key"
    const val EXTRA_ANSWER = "answer"
    private const val BASE_ID = 4200
    private const val DAY_MS = 24 * 3_600_000L
    private const val LOOK_AHEAD_MS = 14 * DAY_MS
    private const val ASK_AHEAD_MS = 7 * DAY_MS

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The events between [from] and [to], the ones you've declined left out. */
    fun read(context: Context, from: Long, to: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                events += CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    start = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    busy = c.getInt(5) != CalendarContract.Events.AVAILABILITY_FREE,
                    calendar = c.getString(6).orEmpty(),
                )
            }
        }
        return events
    }

    /** Reads the calendar, judges it for the planner, and asks about what it can't judge. */
    suspend fun refresh(context: Context) {
        val graph = AppGraph.get(context)
        if (!allowed(context)) {
            graph.calendarTime = EventJudge.Time(emptyList(), emptyMap(), emptyList())
            return
        }
        graph.watchCalendar()
        val now = graph.clock.now()
        val events = runCatching { withContext(Dispatchers.IO) { read(context, now - DAY_MS, now + LOOK_AHEAD_MS) } }
            .onFailure { Log.w(AppGraph.TAG, "Can't read the calendar", it) }
            .getOrNull() ?: return
        val time = EventJudge.time(events, graph.runtime.value.eventAnswers, graph.clock.zone())
        graph.calendarTime = time
        // Not at night: they're asked at the first look after quiet hours. Nor while the question
        // couldn't be seen (notifications off): it's asked once it can be, not marked asked unseen.
        val quiet = BlockPolicy.isQuiet(now, graph.clock.zone(), graph.settings.value)
        val toAsk = if (quiet || !Notify.shown(context, Channels.DAILY)) emptyList() else questions(time.toAsk, graph.runtime.value.eventsAsked, now)
        if (toAsk.isNotEmpty()) {
            toAsk.forEach { ask(context, it, graph.clock.zone()) }
            graph.runtime.update { it.copy(eventsAsked = it.eventsAsked + toAsk.map(EventJudge::key)) }
        }
        runCatching { WidgetUpdater.update(context) }
    }

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    /** Of the events the rules can't judge, those to ask about now: not over, within the week, not asked before. */
    fun questions(candidates: List<CalendarEvent>, asked: Set<String>, now: Long): List<CalendarEvent> =
        candidates.filter { it.end > now && it.start < now + ASK_AHEAD_MS && EventJudge.key(it) !in asked }.distinctBy(EventJudge::key)

    /** The day a question names: an all-day event's date as stored (UTC), a timed one's where you are. */
    fun questionDay(event: CalendarEvent, zone: ZoneId): String =
        Instant.ofEpochMilli(event.start).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate().format(DAY)

    private fun ask(context: Context, event: CalendarEvent, zone: ZoneId) {
        val key = EventJudge.key(event)
        // One notification per event name, told apart by its tag, and actions told apart by their
        // data: no two questions can share either.
        val tag = TAG_PREFIX + key
        val day = questionDay(event, zone)
        val builder = NotificationCompat.Builder(context, Channels.DAILY)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle("${event.title}, $day")
            .setContentText("How much of the day does it take? The plan leaves you that much less time.")
            .setAutoCancel(true)
        val answers = listOf(
            "All of it" to EventJudge.store(EventJudge.Judgement.Busy),
            "A few hours" to EventJudge.store(EventJudge.Judgement.Load(EventJudge.FEW_HOURS_MIN)),
            "None" to EventJudge.store(EventJudge.Judgement.Free),
        )
        answers.forEachIndexed { i, (label, answer) ->
            val tap = PendingIntent.getBroadcast(
                context,
                i,
                Intent(context, EventAnswerReceiver::class.java)
                    .setAction(ACTION_ANSWER)
                    .setData(Uri.fromParts("event", "$key#$i", null))
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_ANSWER, answer),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, label, tap)
        }
        Notify.post(context, tag, BASE_ID, builder.build())
    }

    fun cancelQuestion(context: Context, key: String) = Notify.cancel(context, TAG_PREFIX + key, BASE_ID)

    private const val TAG_PREFIX = "event:"
}

/** Your answer about an event: kept for every event of that name, and the plan redone. */
class EventAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(CalendarTime.EXTRA_KEY) ?: return
        val answer = intent.getStringExtra(CalendarTime.EXTRA_ANSWER) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AppGraph.get(context).runtime.update { it.copy(eventAnswers = it.eventAnswers + (key to answer)) }
                CalendarTime.cancelQuestion(context, key)
                CalendarTime.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
