package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.data.ActivityLog
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** One piece of a day's plan, as it stood that morning. */
@Serializable
data class DayChunk(val taskId: String, val minutes: Int)

/**
 * A day's plan as it stood at the morning briefing, and, the next morning, whether it was all done
 * (docs/spec/learning.md, item 4).
 */
@Serializable
data class DayRecord(
    val date: String,
    val plannedMin: Int,
    val chunks: List<DayChunk>,
    /** Minutes of it done, once the day is over. */
    val doneMin: Int? = null,
    /** All of it done. */
    val full: Boolean? = null,
    /** The time zone it was planned in, so it's finished by that day's own midnight. */
    val zone: String? = null,
    /** Its kind, for its yield (PL-06); null in a record from before, whose date tells it. */
    val kind: DayKind? = null,
    /** Of [plannedMin], the minutes laid in school's windows (LR-09): what school's yield is learned against. */
    val schoolMin: Int = 0,
)

/** The Sunday check-in (docs/spec/learning.md, item 6): five questions, a scale or a line each. */
@Serializable
data class CheckIn(
    /** The Monday of the week it's about. */
    val weekOf: String,
    val at: Long,
    /** How the week felt, 1 (badly) to 5 (well). */
    val feel: Int,
    val avoided: String,
    val inTheWay: String,
    val change: String,
    val energy: String,
)

object Days {

    /**
     * Today's plan as it stands, for the record, as the [kind] of day it was planned as
     * (`SchoolDays.kindOf`): a weekday off school is a weekend's, and teaches the weekend's yield (PL-06).
     */
    fun record(plan: Plan, zone: ZoneId, kind: DayKind): DayRecord {
        val chunks = plan.todayBucket?.chunks.orEmpty().map { DayChunk(it.taskId, it.minutes) }
        val school = plan.todaySlots.filter { it.kind == SlotKind.Work && it.window.atSchool }.sumOf { it.chunk!!.minutes }
        return DayRecord(plan.today.toString(), chunks.sumOf { it.minutes }, chunks, zone = zone.id, kind = kind, schoolMin = school)
    }

    /**
     * [day], finished: each of its tasks counts as done in full if its source confirmed it done by
     * the day's end, or as far as the day's kept slots on it went (LR-09).
     */
    fun finish(day: DayRecord, log: ActivityLog, now: ZoneId): DayRecord {
        // By the day's own midnights, where it was planned: travel since doesn't move them.
        val zone = day.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: now
        val date = LocalDate.parse(day.date)
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        // Confirmed that day: an older completion of a task since reopened doesn't count.
        val done = log.completions.filter { it.doneAt in start until end }.map { it.taskId }.toSet()
        val worked = workedIn(log, start, end)
        var doneMin = 0
        for ((taskId, chunks) in day.chunks.groupBy { it.taskId }) {
            val planned = chunks.sumOf { it.minutes }
            doneMin += if (taskId in done) planned else minOf(planned, worked[taskId] ?: 0)
        }
        return day.copy(doneMin = doneMin, full = doneMin >= day.plannedMin)
    }

    /**
     * [old], today's record, made again once an answer about an event has changed what today holds:
     * what was done of it already counts as planned and done (a finished task's pieces in full,
     * another's kept slots so far), and the rest is what the plan has today now ([ahead]). So the day
     * is judged by what it could hold once the event was known.
     */
    fun replan(old: DayRecord, ahead: List<DayChunk>, log: ActivityLog, now: Long, zone: ZoneId, aheadSchoolMin: Int = 0): DayRecord {
        val dayZone = old.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        val start = LocalDate.parse(old.date).atStartOfDay(dayZone).toInstant().toEpochMilli()
        val done = log.completions.filter { it.doneAt in start..now }.map { it.taskId }.toSet()
        // Kept today, a slot laid later today and ticked early included.
        val worked = workedOn(log, old.date)
        val kept = old.chunks.groupBy { it.taskId }.mapNotNull { (id, chunks) ->
            val planned = chunks.sumOf { it.minutes }
            when {
                id in done -> DayChunk(id, planned)
                (worked[id] ?: 0) > 0 -> DayChunk(id, minOf(planned, worked.getValue(id)))
                else -> null
            }
        }
        val chunks = kept + ahead.filter { it.taskId !in done }
        // School's minutes as planned again: those kept there today, and what's laid there now.
        val school = log.slots.filter { it.date == old.date && it.outcome.kept && it.window.atSchool }.sumOf { it.plannedMin } + aheadSchoolMin
        return old.copy(plannedMin = chunks.sumOf { it.minutes }, chunks = chunks, schoolMin = school)
    }

    /** Minutes worked on each task in the slots laid on [date] and kept, whenever they were ticked (LR-09). */
    fun workedOn(log: ActivityLog, date: String): Map<String, Int> =
        log.slots.filter { it.outcome.kept && it.date == date }.groupBy { it.taskId }.mapValues { (_, kept) -> kept.sumOf { it.workedMin } }.filterValues { it > 0 }

    /** Minutes worked on each task from [start] until [end]: its kept slots' (LR-09), by when each was laid. */
    fun workedIn(log: ActivityLog, start: Long, end: Long): Map<String, Int> =
        log.slots.filter { it.outcome.kept && it.start >= start && it.start < end }.groupBy { it.taskId }.mapValues { (_, kept) -> kept.sumOf { it.workedMin } }.filterValues { it > 0 }

    /** At least this many finished days before the check says anything. */
    const val CHECK_DAYS = 10
    private const val LOOK_BACK = 14
    private const val FULL_SHARE = 0.4

    /**
     * Item 4: if fewer than 40 % of the last fortnight's plans (with work in them) were done in full,
     * the hours are more than the evenings hold. Advice in words, or null.
     */
    fun capacityAdvice(days: List<DayRecord>, today: LocalDate): String? {
        // The last fourteen dates, then those of them with work: older plans don't stand in for
        // days with none.
        val from = today.minusDays(LOOK_BACK - 1L).toString()
        val judged = days.filter { it.date >= from && it.date <= today.toString() && it.full != null && it.plannedMin > 0 }
        if (judged.size < CHECK_DAYS) return null
        val full = judged.count { it.full == true }
        if (full >= FULL_SHARE * judged.size) return null
        return "Only $full of the last ${judged.size} days' plans were done in full: the hours in Settings may be more than the evenings hold. " +
            "Planning with the hours you really have puts less on each day, so each day's plan can be done; what doesn't fit before its deadline then shows as behind, early enough to do something about it."
    }
}
