package com.thomaswcode.decrastination.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PpClass(
    @SerialName("Identifier") val identifier: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Schedules") val schedules: List<PpSchedule>? = null,
)

/** A timetable slot. `DayOfWeek` is .NET's (0 is Sunday); `ScheduleWeek` 1, 2, or 3 for both. */
@Serializable
data class PpSchedule(
    @SerialName("StartTime") val startTime: String,
    @SerialName("EndTime") val endTime: String,
    @SerialName("DayOfWeek") val dayOfWeek: Int,
    @SerialName("ScheduleWeek") val scheduleWeek: Int = 3,
)

/** The semester's classes and their two-week timetable; kept with the tasks between reads (DX-09). */
@Serializable
data class Timetable(val weekOneStartsOn: String?, val classes: List<PpClass>)
