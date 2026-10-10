package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SessionRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

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
    /** The time zone it was planned in, so it's finished by that day's own midnight. */
    val zone: String? = null,
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
    fun record(plan: Plan, zone: ZoneId): DayRecord {
        val chunks = plan.todayBucket?.chunks.orEmpty().map { DayChunk(it.taskId, it.minutes) }
        return DayRecord(plan.today.toString(), chunks.sumOf { it.minutes }, chunks, zone = zone.id)
    }

    /**
     * [day], finished: each of its tasks counts as done in full if its source confirmed it done by
     * the day's end, or as far as the day's focus sessions on it went.
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
     * another's sessions so far), and the rest is what the plan has today now ([ahead]). So the day
     * is judged by what it could hold once the event was known.
     */
    fun replan(old: DayRecord, ahead: List<DayChunk>, log: ActivityLog, now: Long, zone: ZoneId): DayRecord {
        val dayZone = old.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
        val start = LocalDate.parse(old.date).atStartOfDay(dayZone).toInstant().toEpochMilli()
        val done = log.completions.filter { it.doneAt in start..now }.map { it.taskId }.toSet()
        val worked = workedIn(log, start, now + 1)
        val kept = old.chunks.groupBy { it.taskId }.mapNotNull { (id, chunks) ->
            val planned = chunks.sumOf { it.minutes }
            when {
                id in done -> DayChunk(id, planned)
                (worked[id] ?: 0) > 0 -> DayChunk(id, minOf(planned, worked.getValue(id)))
                else -> null
            }
        }
        val chunks = kept + ahead.filter { it.taskId !in done }
        return old.copy(plannedMin = chunks.sumOf { it.minutes }, chunks = chunks)
    }

    /** Minutes worked on each task from [start] until [end]: a session across either counts only its part within. */
    private fun workedIn(log: ActivityLog, start: Long, end: Long): Map<String, Int> =
        log.sessions.groupBy { it.taskId }.mapValues { (_, sessions) -> sessions.sumOf { minutesIn(it, start, end) } }.filterValues { it > 0 }

    /**
     * Minutes of [session] from [start] until [end]: a timed one's as far as its time fell in them
     * (from its start, for the minutes it ran), a photo check's on the day it was taken.
     */
    fun minutesIn(session: SessionRecord, start: Long, end: Long): Int {
        if (session.photo) return if (session.startedAt in start until end) session.workedMin else 0
        val from = maxOf(session.startedAt, start)
        val to = minOf(session.startedAt + session.workedMin * 60_000L, end)
        return ((to - from) / 60_000L).toInt().coerceIn(0, session.workedMin.coerceAtLeast(0))
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
            "Planning with the hours you really have puts less on each day, so each day's plan can be done; what doesn't fit before its deadline then shows as behind, early enough to do something about it."
    }
}
