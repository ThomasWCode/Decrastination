package com.thomaswcode.decrastination.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plan's slots written to the calendar you picked (NF-07): the next seven days', as events the
 * app knows by their URI ([SlotCalendarDiff]), free (so a read never counts them busy, though the
 * calendar's left out of reading anyway, DX-13), with no reminders of their own. One query and one
 * batch a write, so a replan's write is cheap and an unchanged plan writes nothing.
 */
object SlotCalendar {
    private const val MOVE_ID = 4300
    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /** A calendar on the phone, and whether events can be added to it. */
    data class DeviceCalendar(val id: Long, val name: String, val account: String, val writable: Boolean)

    /** What a write left: each URI's start as it now stands, and the moves you made, taken or written back (with why). */
    data class Written(val starts: Map<String, Long>, val taken: List<SlotCalendarDiff.HandMove>, val refused: List<Pair<SlotCalendarDiff.HandMove, String>>)

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

    /** [move] as your time for its task's next piece: an [ChangeType.AtTime] at its event's start. */
    fun change(move: SlotCalendarDiff.HandMove, zone: ZoneId): Change {
        val at = Instant.ofEpochMilli(move.existing.start).atZone(zone)
        return Change(ChangeType.AtTime, taskId = move.desired.taskId, date = at.toLocalDate().toString(), startMin = at.hour * 60 + at.minute, time = move.existing.start)
    }

    /**
     * [plan]'s slots written to [calendarId] against what's there and [written] (each URI's start as
     * last written); a hand move taken as [SlotCalendarDiff.judge] judges it, else written back.
     */
    fun write(context: Context, calendarId: Long, plan: Plan, zone: ZoneId, written: Map<String, Long>, refuses: (SlotCalendarDiff.HandMove) -> String?): Written {
        val from = plan.today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = plan.today.plusDays(SlotCalendarDiff.DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val desired = SlotCalendarDiff.desired(plan, zone)
        val diff = SlotCalendarDiff.diff(desired, existing(context, calendarId, from, to, desired.map { it.uri }), written)
        val (taken, refused) = SlotCalendarDiff.judge(diff.handMoves, refuses)
        val ops = ArrayList<ContentProviderOperation>()
        diff.inserts.forEach { ops += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values(context, calendarId, it, zone)).build() }
        (diff.updates.map { it.existing.id to it.desired } + refused.map { it.first.existing.id to it.first.desired }).forEach { (id, want) ->
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, id)).withValues(values(context, calendarId, want, zone)).build()
        }
        diff.deletes.forEach { ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, it.id)).build() }
        if (ops.isNotEmpty()) context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        return Written(desired.associate { it.uri to it.start } + taken.associate { it.desired.uri to it.existing.start }, taken, refused)
    }

    /**
     * The app's events in [calendarId], all of them: a calendar no longer written to is left as it
     * was before. False when they can't be (no permission, or the provider failed), to be tried again.
     */
    fun clear(context: Context, calendarId: Long): Boolean {
        if (!allowed(context)) return false
        return runCatching {
            context.contentResolver.delete(Events.CONTENT_URI, "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ?", arrayOf(calendarId.toString(), context.packageName))
        }.isSuccess
    }

    /** Says a move of yours was written back, and [why]. */
    fun sayRefused(context: Context, move: SlotCalendarDiff.HandMove, zone: ZoneId, why: String) {
        val notification = NotificationCompat.Builder(context, Channels.CALENDAR)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle("Can't move “${move.desired.title}”")
            .setContentText("Not to ${Instant.ofEpochMilli(move.existing.start).atZone(zone).format(WHEN)}: $why, so it's back where it was.")
            .setAutoCancel(true)
            .build()
        Notify.post(context, move.desired.uri, MOVE_ID, notification)
    }

    /**
     * The app's events in [calendarId], not deleted, starting between [from] and [to], or one of
     * [uris] wherever it now is (one you moved out of the week is found by what it is).
     */
    private fun existing(context: Context, calendarId: Long, from: Long, to: Long, uris: List<String>): List<SlotCalendarDiff.Existing> {
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

    private fun values(context: Context, calendarId: Long, want: SlotCalendarDiff.Desired, zone: ZoneId) = ContentValues().apply {
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
}
