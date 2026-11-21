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
data class Timetable(val weekOneStartsOn: String?, val classes: List<PpClass>) {
    /** Whether it has a lesson at all: one with none may be a read gone wrong ([Timetables]). */
    val hasLessons: Boolean get() = classes.any { !it.schedules.isNullOrEmpty() }
}

/**
 * A timetable with no lesson in it, as last [read], held back since the read at [since] while the one before, which
 * had lessons, is kept (D-165): asked about till you say, as it may be a read gone wrong; [kept] once you've said to
 * keep the one before, when it's no longer asked.
 */
@Serializable
data class EmptyTimetable(val read: Timetable, val since: Long, val kept: Boolean = false)

/** Which timetable is kept as reads come and you answer (D-165), pure. */
object Timetables {
    /** The timetable kept, and the empty one held back beside it, if one is. */
    data class Kept(val timetable: Timetable?, val empty: EmptyTimetable?)

    /**
     * After [read], a good read's, at [now]: one with a lesson is the timetable, and nothing's held back. One with none
     * isn't taken while the [stored] one has lessons: that's kept, and the empty one [held] back, as from the first
     * read that brought one, your answer to it standing. With no lessons stored either, there's nothing to keep.
     */
    fun after(stored: Timetable?, held: EmptyTimetable?, read: Timetable, now: Long): Kept = when {
        read.hasLessons || stored?.hasLessons != true -> Kept(read, null)
        else -> Kept(stored, held?.copy(read = read) ?: EmptyTimetable(read, now))
    }

    /** Your answer to the empty one [held] back: the [stored] one kept, and asked no more; or the empty one taken. Nothing with none held. */
    fun answered(stored: Timetable?, held: EmptyTimetable?, keep: Boolean): Kept = when {
        held == null -> Kept(stored, null)
        keep -> Kept(stored, held.copy(kept = true))
        else -> Kept(held.read, null)
    }
}
