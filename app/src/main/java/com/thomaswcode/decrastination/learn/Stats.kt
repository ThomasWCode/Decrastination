package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What the Stats tab shows (PLAN.md Phase 6), from the activity log: each recent day's finished
 * work, minutes of kept slots (LR-09) and blocks, the period's totals, and the calibration in
 * words. Pure, for the tests.
 */
object Stats {
    /** How far back the tab looks. */
    const val DAYS = 14

    data class Day(
        val date: LocalDate,
        val completions: Int,
        /** Minutes of work in the day's kept slots. */
        val slotMin: Int,
        val blocks: Int,
        /** The morning's plan all done (true), not all (false), or not recorded or not over yet (null). */
        val planDone: Boolean?,
    )

    data class Summary(
        /** Newest first, today included. */
        val days: List<Day>,
        val completions: Int,
        /** Completions that had a real deadline (PL-14: not a date the app or Claude set), and of them, those confirmed done by it. */
        val dated: Int,
        val onTime: Int,
        /** Slots with an outcome (kept, kept late or missed; not moved), and of them those kept, and those that earned free time. */
        val slotsPlanned: Int,
        val slotsKept: Int,
        val slotsEarned: Int = 0,
        /** Pieces a photo check found done. */
        val photoChecks: Int = 0,
        val slotMin: Int,
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
        // The slots with an outcome, by the day each was laid in as recorded (LR-09), whatever the
        // zone now: a deliberate move is neither.
        val slots = log.slots.filter { LocalDate.parse(it.date).let { d -> !d.isBefore(first) && !d.isAfter(today) } && it.outcome != SlotOutcome.Moved }
        val kept = slots.filter { it.outcome.kept }
        fun minutesOn(date: LocalDate): Int = kept.filter { it.date == date.toString() }.sumOf { it.workedMin }
        val blocks = log.blocks.filter { inRange(it.at) }
        val protection = log.protection.filter { inRange(it.at) }
        val plans = log.days.associateBy { it.date }
        val perDay = (0 until days).map { back ->
            val date = today.minusDays(back.toLong())
            Day(
                date = date,
                completions = completions.count { dateOf(it.doneAt) == date },
                slotMin = minutesOn(date),
                blocks = blocks.count { dateOf(it.at) == date },
                planDone = plans[date.toString()]?.full,
            )
        }
        // Only real deadlines are met or missed (PL-14): a date the app or Claude set was a plan.
        val dated = completions.filter { it.dueAt != null && it.hardDeadline }
        return Summary(
            days = perDay,
            completions = completions.size,
            dated = dated.size,
            onTime = dated.count { it.doneAt <= it.dueAt!! },
            slotsPlanned = slots.size,
            slotsKept = kept.size,
            slotsEarned = kept.count { it.earned },
            photoChecks = kept.count { it.photo },
            slotMin = kept.sumOf { it.workedMin },
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
        fun what(key: String): String {
            val kind = key.substringBefore('|')
            val className = key.substringAfter('|', "")
            val label = Kind.entries.firstOrNull { it.name == kind }?.label ?: kind
            return if (className.isEmpty()) label else "$label, $className"
        }
        // What each figure rests on (IMP-P2-016): its timed pieces, or only your answers.
        fun pieces(n: Int) = "$n timed piece${if (n == 1) "" else "s"}"
        val multipliers = calibration.multipliers.entries.sortedBy { it.key }.map { (key, value) ->
            val n = calibration.timed[key] ?: 0
            "${what(key)}: takes %.1f× the estimate".format(Locale.UK, value) + if (n > 0) " (from ${pieces(n)})" else " (from your answers)"
        }
        // A class with some timed pieces, not yet enough for its own figure.
        val coming = calibration.timed.entries.filter { (key, n) -> key.substringAfter('|').isNotEmpty() && key !in calibration.multipliers && n > 0 }
            .sortedBy { it.key }
            .map { (key, n) -> "${what(key)}: ${pieces(n)} so far; its own figure counts from ${Calibrator.MIN_CLASS_TIMED}" }
        val margins = calibration.marginDays.entries.sortedBy { it.key.ordinal }.map { (kind, days) ->
            "${kind.label}: finished $days day${if (days == 1) "" else "s"} before the deadline"
        }
        // How much of each kind of day's free time is used (PL-06), and the days it rests on.
        val yields = calibration.yields.entries.sortedBy { it.key.ordinal }.map { (kind, share) ->
            val n = calibration.yieldDays[kind] ?: 0
            "${kind.label}: about ${kotlin.math.round(share * 100).toInt()} % of the free time used" + if (n > 0) " (from $n days)" else ""
        }
        return multipliers + coming + margins + yields
    }

    private const val TOP = 5
}
