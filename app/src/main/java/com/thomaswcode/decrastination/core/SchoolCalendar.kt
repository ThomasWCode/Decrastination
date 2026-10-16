package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Days with no school, and school days that end early (PL-16): told to the chat ("half term is 26
 * to 30 October") or added in Settings. A day off is planned as a weekend is, and a half-day's
 * school windows end when it does (PL-15). Kept in `school.json`, each list in date order.
 */
@Serializable
data class SchoolCalendar(val noSchool: List<NoSchool> = emptyList(), val halfDays: List<HalfDay> = emptyList()) {

    /** Monday to Friday, and not inside a stretch with no school. */
    fun isSchoolDay(date: LocalDate): Boolean = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && noSchoolOn(date) == null

    /** [date]'s early end, where it's a school day that has one. */
    fun halfDay(date: LocalDate): HalfDay? = halfDays.firstOrNull { it.date == date.toString() }?.takeIf { isSchoolDay(date) }

    /** The stretch with no school [date] falls in, if any. */
    fun noSchoolOn(date: LocalDate): NoSchool? = noSchool.firstOrNull { it.covers(date) }

    /**
     * With [entry] added: merged with every stretch it overlaps or runs on from, so no day is in two;
     * the label is the first given.
     */
    fun with(entry: NoSchool): SchoolCalendar {
        var merged = entry
        val rest = mutableListOf<NoSchool>()
        for (other in noSchool) {
            if (other.touches(merged)) {
                merged = NoSchool(minOf(other.from, merged.from), maxOf(other.to, merged.to), other.label.ifBlank { merged.label })
            } else {
                rest += other
            }
        }
        // A merge can reach a stretch passed over before it: merged again until nothing touches.
        val again = rest.firstOrNull { it.touches(merged) }
        if (again != null) return copy(noSchool = rest).with(merged)
        return copy(noSchool = (rest + merged).sortedBy { it.from })
    }

    /** With [entry] added, in place of one on its date. */
    fun with(entry: HalfDay): SchoolCalendar = copy(halfDays = (halfDays.filterNot { it.date == entry.date } + entry).sortedBy { it.date })

    fun without(entry: NoSchool): SchoolCalendar = copy(noSchool = noSchool - entry)

    fun without(entry: HalfDay): SchoolCalendar = copy(halfDays = halfDays - entry)

    /**
     * This calendar with what a change took [before] to [after] taken back (a chat card's Undo, 16
     * Oct): the stretches and half-days it added gone, and those it merged into them back. Null if
     * one it added has gone since (deleted in Settings → School): taking it back would then bring
     * back what you took out.
     */
    fun undone(before: SchoolCalendar, after: SchoolCalendar): SchoolCalendar? {
        val addedOff = after.noSchool - before.noSchool.toSet()
        val addedHalf = after.halfDays - before.halfDays.toSet()
        if (!noSchool.containsAll(addedOff) || !halfDays.containsAll(addedHalf)) return null
        return copy(
            noSchool = (noSchool - addedOff.toSet() + (before.noSchool - after.noSchool.toSet())).distinct().sortedBy { it.from },
            halfDays = (halfDays - addedHalf.toSet() + (before.halfDays - after.halfDays.toSet())).distinct().sortedBy { it.date },
        )
    }

    /** Every entry of [other] added to this one. */
    fun withAll(other: SchoolCalendar): SchoolCalendar =
        other.halfDays.fold(other.noSchool.fold(this) { calendar, entry -> calendar.with(entry) }) { calendar, entry -> calendar.with(entry) }
}

/** Days with no school, [from] to [to] inclusive, as ISO dates (which compare as the dates do). */
@Serializable
data class NoSchool(val from: String, val to: String, val label: String = "") {
    fun covers(date: LocalDate): Boolean = date.toString() in from..to

    /** Overlapping [other], or a day on from it either way. */
    fun touches(other: NoSchool): Boolean {
        val start = LocalDate.parse(from)
        val end = LocalDate.parse(to)
        return LocalDate.parse(other.from) <= end.plusDays(1) && LocalDate.parse(other.to) >= start.minusDays(1)
    }
}

/** A school day that ends early: at [endsAtMin], minutes after midnight. */
@Serializable
data class HalfDay(val date: String, val endsAtMin: Int)
