package com.thomaswcode.decrastination.calendar

import java.time.ZoneId

/**
 * What [CalendarWriter] asks of the calendar provider (docs/plan_v4/architecture.md §Ports),
 * so its rules are tested without it: the app's events read, and those worded like them; a write's
 * inserts, updates and deletes made in one batch; the app's events in a calendar deleted; and the
 * calendar's changes watched. `SlotCalendar` on the phone, events in memory in a test.
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
