package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.data.ActivityLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the Stats tab shows (PLAN.md Phase 6), from the activity log: each recent day's finished
 * work, focus minutes and blocks, the period's totals, and the calibration in words. Pure, for
 * the tests.
 */
object Stats {
    /** How far back the tab looks. */
    const val DAYS = 14

    data class Day(
        val date: LocalDate,
        val completions: Int,
        val focusMin: Int,
        val blocks: Int,
        /** The morning's plan all done (true), not all (false), or not recorded or not over yet (null). */
        val planDone: Boolean?,
    )

    data class Summary(
        /** Newest first, today included. */
        val days: List<Day>,
        val completions: Int,
        /** Completions that had a deadline, and of them, those confirmed done by it. */
        val dated: Int,
        val onTime: Int,
        val sessions: Int,
        /** Sessions run to their end. */
        val sessionsFinished: Int,
        /** Pieces a photo check found done. */
        val photoChecks: Int = 0,
        val focusMin: Int,
        val blocks: Int,
        /** What was covered most, with how often: a package or a site. */
        val topBlocked: List<Pair<String, Int>>,
        /** The watchdog's findings: times it found protection wanting, and times it put it right. */
        val protectionProblems: Int,
        val protectionRepaired: Int,
    )

    fun summary(log: ActivityLog, now: Long, zone: ZoneId, days: Int = DAYS): Summary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(days - 1L)
        fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        fun inRange(at: Long): Boolean = dateOf(at).let { !it.isBefore(first) && !it.isAfter(today) }
        val completions = log.completions.filter { inRange(it.doneAt) }
        // Timed focus sessions; work a photo check found done is counted as such, apart.
        val sessions = log.sessions.filter { inRange(it.startedAt) && !it.photo }
        val photos = log.sessions.filter { inRange(it.startedAt) && it.photo }
        val blocks = log.blocks.filter { inRange(it.at) }
        val protection = log.protection.filter { inRange(it.at) }
        val plans = log.days.associateBy { it.date }
        val perDay = (0 until days).map { back ->
            val date = today.minusDays(back.toLong())
            Day(
                date = date,
                completions = completions.count { dateOf(it.doneAt) == date },
                focusMin = sessions.filter { dateOf(it.startedAt) == date }.sumOf { it.workedMin },
                blocks = blocks.count { dateOf(it.at) == date },
                planDone = plans[date.toString()]?.full,
            )
        }
        val dated = completions.filter { it.dueAt != null }
        return Summary(
            days = perDay,
            completions = completions.size,
            dated = dated.size,
            onTime = dated.count { it.doneAt <= it.dueAt!! },
            sessions = sessions.size,
            sessionsFinished = sessions.count { it.completed },
            photoChecks = photos.size,
            focusMin = sessions.sumOf { it.workedMin },
            blocks = blocks.size,
            topBlocked = blocks.groupingBy { it.target }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(TOP)
                .map { it.key to it.value },
            protectionProblems = protection.count { it.problems.isNotEmpty() },
            protectionRepaired = protection.count { it.repaired },
        )
    }

    /** The calibration in words, a line a value: what the planner now assumes about your work. */
    fun calibrationLines(calibration: Calibration): List<String> {
        val multipliers = calibration.multipliers.entries.sortedBy { it.key }.map { (key, value) ->
            val kind = key.substringBefore('|')
            val className = key.substringAfter('|', "")
            val label = Kind.entries.firstOrNull { it.name == kind }?.label ?: kind
            val what = if (className.isEmpty()) label else "$label, $className"
            "$what: takes %.1f× the estimate".format(Locale.UK, value)
        }
        val margins = calibration.marginDays.entries.sortedBy { it.key.ordinal }.map { (kind, days) ->
            "${kind.label}: finished $days day${if (days == 1) "" else "s"} before the deadline"
        }
        val boxes = calibration.boxMin.entries.sortedBy { it.key.ordinal }.map { (kind, minutes) -> "${kind.label}: cut into $minutes-minute pieces" }
        return multipliers + margins + boxes
    }

    private const val TOP = 5
}
