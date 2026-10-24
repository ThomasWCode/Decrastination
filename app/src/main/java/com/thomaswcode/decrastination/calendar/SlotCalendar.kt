package com.thomaswcode.decrastination.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What [CalendarWriter] asks of the calendar provider (docs/plan_v4/architecture.md §Ports),
 * so its rules are tested without it: the app's events read, and those worded like them; a write's
 * inserts, updates and deletes made in one batch; the app's events in a calendar deleted; and the
 * calendar's changes watched. [SlotCalendar] on the phone, events in memory in a test.
 */
interface CalendarProvider {
    /** Whether events may be written. */
    fun allowed(): Boolean

    /**
     * The app's events in [calendarId], not deleted, starting between [from] and [to], or one of
     * [uris] wherever it now is (one you moved out of the week is found by what it is).
     */
    fun existing(calendarId: Long, from: Long, to: Long, uris: List<String>): List<SlotCalendarDiff.Existing>

    /**
     * The events in [calendarId] with no app's marks in its words ([SlotCalendarDiff.looksWritten]),
     * whenever they are: copies of its own the calendar's sync brought back without them
     * ([SlotCalendarDiff.isCopy]), or events of yours so worded ([SlotCalendarDiff.yours]).
     */
    fun unmarked(calendarId: Long): List<SlotCalendarDiff.Existing>

    /**
     * In [calendarId], [inserts] made, each event [updates] names (by its id) made what it says, and
     * [deletes] (ids) deleted, in one batch; nothing asked of the provider when there's nothing to do.
     */
    fun apply(calendarId: Long, zone: ZoneId, inserts: List<SlotCalendarDiff.Desired>, updates: List<Pair<Long, SlotCalendarDiff.Desired>>, deletes: List<Long>)

    /** The app's events in [calendarId], all of them, deleted by its package. */
    fun deleteOwn(calendarId: Long)

    /** [onChange] at each change the calendar announces, once its events may be read: false while they can't be. */
    fun watch(onChange: () -> Unit): Boolean
}

/**
 * The calendar provider on the phone, as [CalendarWriter] writes the plan's slots to the calendar you
 * picked (NF-07): the next seven days', as events the app knows by their URI ([SlotCalendarDiff]),
 * free (so a read never counts them busy, though the calendar's left out of reading anyway, DX-13),
 * with no reminders of their own. Two queries ([unmarked], [existing]) and at most one batch a write,
 * so a replan's write is cheap and an unchanged plan writes nothing.
 */
class SlotCalendar(private val context: Context) : CalendarProvider {
    /** A calendar on the phone, and whether events can be added to it. */
    data class DeviceCalendar(val id: Long, val name: String, val account: String, val writable: Boolean)

    override fun allowed(): Boolean = allowed(context)

    override fun apply(calendarId: Long, zone: ZoneId, inserts: List<SlotCalendarDiff.Desired>, updates: List<Pair<Long, SlotCalendarDiff.Desired>>, deletes: List<Long>) {
        val ops = ArrayList<ContentProviderOperation>()
        inserts.forEach { ops += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values(calendarId, it, zone)).build() }
        updates.forEach { (id, want) ->
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, id)).withValues(values(calendarId, want, zone)).build()
        }
        deletes.forEach { ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, it)).build() }
        if (ops.isNotEmpty()) context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
    }

    override fun deleteOwn(calendarId: Long) {
        context.contentResolver.delete(Events.CONTENT_URI, "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(calendarId.toString(), context.packageName))
    }

    override fun unmarked(calendarId: Long): List<SlotCalendarDiff.Existing> {
        val projection = arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION)
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} IS NULL AND ${Events.DELETED} = 0"
        val found = mutableListOf<SlotCalendarDiff.Existing>()
        context.contentResolver.query(Events.CONTENT_URI, projection, selection, arrayOf(calendarId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val description = c.getString(4).orEmpty()
                if (SlotCalendarDiff.looksWritten(description)) found += SlotCalendarDiff.Existing(c.getLong(0), "", c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), description, marked = false)
            }
        }
        return found
    }

    override fun existing(calendarId: Long, from: Long, to: Long, uris: List<String>): List<SlotCalendarDiff.Existing> {
        val projection = arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION, Events.CUSTOM_APP_URI)
        val byUri = if (uris.isEmpty()) "" else " OR ${Events.CUSTOM_APP_URI} IN (${uris.joinToString(",") { "?" }})"
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = 0 AND ((${Events.DTSTART} >= ? AND ${Events.DTSTART} < ?)$byUri)"
        val args = arrayOf(calendarId.toString(), context.packageName, from.toString(), to.toString()) + uris
        val found = mutableListOf<SlotCalendarDiff.Existing>()
        context.contentResolver.query(Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val uri = c.getString(5)?.takeIf { it.startsWith(SlotCalendarDiff.URI_PREFIX) } ?: continue
                found += SlotCalendarDiff.Existing(c.getLong(0), uri, c.getLong(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty())
            }
        }
        return found
    }

    override fun watch(onChange: () -> Unit): Boolean {
        if (!CalendarTime.allowed(context)) return false
        context.contentResolver.registerContentObserver(
            Events.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChange()
            },
        )
        return true
    }

    private fun values(calendarId: Long, want: SlotCalendarDiff.Desired, zone: ZoneId) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, want.title)
        put(Events.DESCRIPTION, want.description)
        put(Events.DTSTART, want.start)
        put(Events.DTEND, want.end)
        put(Events.EVENT_TIMEZONE, zone.id)
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        put(Events.HAS_ALARM, 0)
        put(Events.CUSTOM_APP_PACKAGE, context.packageName)
        put(Events.CUSTOM_APP_URI, want.uri)
    }

    companion object {
        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

        fun allowed(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

        /** The phone's calendars, by name, with whether each can be written to. */
        fun calendars(context: Context): List<DeviceCalendar> {
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
            val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_ACCESS_LEVEL)
            val found = mutableListOf<DeviceCalendar>()
            context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, "${Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) found += DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR)
            }
            return found
        }

        /** Says a move of yours was written back, and [why]. */
        fun sayRefused(context: Context, move: SlotCalendarDiff.HandMove, zone: ZoneId, why: String) {
            val notification = Notify.builder(context, Channels.CALENDAR)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("Can't move “${move.desired.title}”")
                .setContentText("Not to ${Instant.ofEpochMilli(move.existing.start).atZone(zone).format(WHEN)}: $why, so it's back where it was.")
                .setAutoCancel(true)
                .build()
            AppGraph.get(context).notifier.post(NoticeId.CalendarMove, notification, move.desired.uri)
        }
    }
}
