package com.thomaswcode.decrastination.learn

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/** The phone's calendar read (Q7, docs/spec/slots-and-calendar.md), for [CalendarTime] to judge and the plan to use. */
object CalendarReader {
    const val PERMISSION = Manifest.permission.READ_CALENDAR

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

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
            CalendarContract.Instances.DISPLAY_COLOR,
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
                    colour = if (c.isNull(10)) null else c.getInt(10),
                )
            }
        }
        return events
    }
}
