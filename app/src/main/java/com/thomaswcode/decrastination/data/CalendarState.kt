package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/**
 * The versions of its events the app has written to calendars, as
 * [com.thomaswcode.decrastination.calendar.SlotCalendarDiff.version]'s fingerprints, oldest first:
 * how a copy of one that's lost the app's marks is told from an event of yours worded like one,
 * which is never deleted; what it wrote where; your answers about events. The calendar writer's (its
 * events' questions are the sync's). Kept in `calendar.json`.
 */
@Serializable
data class CalendarState(
    val written: List<Long> = emptyList(),
    /** Each slot event's start as the app last wrote it to the calendar, by its URI (NF-07): one since moved was moved by you. */
    val calendarWritten: Map<String, Long> = emptyMap(),
    /** The calendar those were written to, so a change of calendar takes them out of the old one. */
    val calendarWrittenTo: Long? = null,
    /** Calendars no longer written to whose events of the app's are still to be taken out (no permission, or a failure, the last time). */
    val calendarsToClear: List<Long> = emptyList(),
    /** Your answers about calendar events, by name: "free", "busy" or "load:<minutes>" (Q7). */
    val eventAnswers: Map<String, String> = emptyMap(),
    /** Events you've been asked about, by name, so each is asked once. */
    val eventsAsked: Set<String> = emptySet(),
    /** The shape this is in ([Migration]): raised with each change of it. */
    val version: Int = 2,
)
