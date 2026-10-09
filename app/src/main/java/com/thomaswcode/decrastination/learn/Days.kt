package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.data.ActivityLog
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/** One piece of a day's plan, as it stood that morning. */
@Serializable
data class DayChunk(val taskId: String, val minutes: Int)

/**
 * A day's plan as it stood at the morning briefing, and, the next morning, whether it was all done
 * (docs/scheduler.md §5, item 4).
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
)

/** The Sunday check-in (docs/scheduler.md §5, item 6): five questions, a scale or a line each. */
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

    /** Today's plan as it stands, for the record. */
    fun record(plan: Plan): DayRecord {
        val chunks = plan.todayBucket?.chunks.orEmpty().map { DayChunk(it.taskId, it.minutes) }
        return DayRecord(plan.today.toString(), chunks.sumOf { it.minutes }, chunks)
    }

    /**
     * [day], finished: each of its tasks counts as done in full if its source confirmed it done by
     * the day's end, or as far as the day's focus sessions on it went.
     */
    fun finish(day: DayRecord, log: ActivityLog, zone: ZoneId): DayRecord {
        val date = LocalDate.parse(day.date)
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        // Confirmed that day: an older completion of a task since reopened doesn't count.
        val done = log.completions.filter { it.doneAt in start until end }.map { it.taskId }.toSet()
        val worked = log.sessions.filter { it.startedAt in start until end }.groupBy { it.taskId }.mapValues { (_, s) -> s.sumOf { it.workedMin } }
        var doneMin = 0
        for ((taskId, chunks) in day.chunks.groupBy { it.taskId }) {
            val planned = chunks.sumOf { it.minutes }
            doneMin += if (taskId in done) planned else minOf(planned, worked[taskId] ?: 0)
        }
        return day.copy(doneMin = doneMin, full = doneMin >= day.plannedMin)
    }

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
            "Planning with fewer would put less on each day, and less would show as behind."
    }
}
