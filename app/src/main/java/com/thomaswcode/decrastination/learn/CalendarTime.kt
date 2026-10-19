package com.thomaswcode.decrastination.learn

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.thomaswcode.decrastination.core.Planner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The phone's calendar as busy time (Q7, docs/spec/slots-and-calendar.md): the next fortnight's events, judged one
 * by one ([EventJudge]), given to the planner; an all-day event the rules can't judge is asked
 * about once, as Felix's message in the chat, within the week before it, and takes all its time till answered.
 */
object CalendarTime {
    const val PERMISSION = Manifest.permission.READ_CALENDAR
    const val DAY_MS = 24 * 3_600_000L
    /**
     * The end of the plan's last day (DX-14): its days run to today + [Planner.MAX_HORIZON_DAYS], each
     * whole, so the calendar is read to that day's midnight, where you are; a trip in the plan's third
     * week takes its day's hours out of the spread.
     */
    fun readUntil(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(Planner.MAX_HORIZON_DAYS + 1).atStartOfDay(zone).toInstant().toEpochMilli()
    const val ASK_AHEAD_MS = 7 * DAY_MS

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The answers a question offers, in words and as they're kept: his chips on it, and on the Calendar page. */
    val ANSWERS: List<Pair<String, String>> = listOf(
        "All of it" to EventJudge.Judgement.Busy,
        "A few hours" to EventJudge.Judgement.Load(EventJudge.FEW_HOURS_MIN),
        "None" to EventJudge.Judgement.Free,
    ).map { (label, judgement) -> label to EventJudge.store(judgement)!! }

    /** How much of an event's description is kept (DX-12). */
    private const val DESCRIPTION_CHARS = 500

    /** The events between [from] and [to], the ones you've declined and those of [excluded] calendars (DX-13) left out. */
    fun read(context: Context, from: Long, to: Long, excluded: Set<Long> = emptySet()): List<CalendarEvent> {
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
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        // Ids are numbers: put in the query as they are.
        val selection = excluded.takeIf { it.isNotEmpty() }?.let { "${CalendarContract.Instances.CALENDAR_ID} NOT IN (${it.joinToString(",")})" }
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
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
                    location = c.getString(8).orEmpty(),
                    description = c.getString(9).orEmpty().take(DESCRIPTION_CHARS),
                )
            }
        }
        return events
    }

    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    /** Of the events the rules can't judge, those to ask about now: not over, within the week, not asked before. */
    fun questions(candidates: List<CalendarEvent>, asked: Set<String>, now: Long): List<CalendarEvent> =
        candidates.filter { it.end > now && it.start < now + ASK_AHEAD_MS && EventJudge.key(it) !in asked }.distinctBy(EventJudge::key)

    /** The day a question names: an all-day event's date as stored (UTC), a timed one's where you are. */
    fun questionDay(event: CalendarEvent, zone: ZoneId): String =
        Instant.ofEpochMilli(event.start).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate().format(DAY)

}
