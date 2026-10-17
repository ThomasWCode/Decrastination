package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/**
 * The versions of its events the app has written to calendars (#47's review), as
 * [com.thomaswcode.decrastination.calendar.SlotCalendarDiff.version]'s fingerprints, oldest first:
 * how a copy of one that's lost the app's marks is told from an event of yours worded like one,
 * which is never deleted. A file of its own, as it changes at each write and the runtime's is big.
 */
@Serializable
data class CalendarVersions(val written: List<Long> = emptyList())
