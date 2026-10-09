package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.SessionRecord
import kotlin.random.Random

/**
 * What the app learns from how the work actually went (docs/scheduler.md §5, items 1–3), worked out
 * afresh from the activity log each time, so there's no hidden state to drift: replaying the same
 * log gives the same calibration. Every value is bounded.
 */
object Calibrator {
    const val MIN_MULTIPLIER = 0.5
    const val MAX_MULTIPLIER = 3.0

    /** How much each new completion moves a multiplier. */
    const val WEIGHT = 0.3

    /** A self-assessment nudges the multiplier: more when there are no timed minutes to go on. */
    private const val NUDGE_WITH_TIME = 0.05
    private const val NUDGE_ALONE = 0.10

    const val MIN_MARGIN = 1
    const val MAX_MARGIN = 3
    private const val LATE_IN_A_ROW = 2
    private const val EARLY_IN_A_ROW = 5
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    val BOXES = listOf(25, 45, 60)

    /** Samples after which a kind's box length is settled. */
    const val BOX_SETTLED = 20
    private const val EXPLORE = 0.2

    const val HARDER = "harder"
    const val AS_EXPECTED = "as expected"
    const val EASIER = "easier"

    /** The calibration the log teaches, and what changed from [previous], in words. */
    data class Learned(val calibration: Calibration, val changes: List<String>)

    fun learn(log: ActivityLog, previous: Calibration, defaultBox: Int, week: Long): Learned {
        val multipliers = multipliers(log.completions)
        val margins = margins(log.completions)
        val boxes = boxes(log, defaultBox, week)
        val next = Calibration(multipliers = multipliers, marginDays = margins, boxMin = boxes)
        return Learned(next, describe(previous, next))
    }

    /**
     * Effort multipliers per kind and class (and per kind, for classes not yet seen): a moving
     * average of actual over estimated minutes, from completions with timed minutes, nudged by
     * "harder" and "easier".
     */
    fun multipliers(completions: List<CompletionRecord>): Map<String, Double> {
        val result = HashMap<String, Double>()
        for (record in completions.sortedBy { it.doneAt }) {
            if (record.estimateMin <= 0) continue
            for (key in listOf(Calibration.key(record.kind, record.className), Calibration.key(record.kind, null)).distinct()) {
                var m = result[key] ?: 1.0
                val timed = record.workedMin > 0
                if (timed) m = (1 - WEIGHT) * m + WEIGHT * (record.workedMin.toDouble() / record.estimateMin)
                val nudge = if (timed) NUDGE_WITH_TIME else NUDGE_ALONE
                when (record.assessment) {
                    HARDER -> m *= 1 + nudge
                    EASIER -> m *= 1 - nudge
                }
                if (timed || record.assessment == HARDER || record.assessment == EASIER) result[key] = m.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
            }
        }
        return result
    }

    /**
     * Safety margins per kind: from 1 day, one more (up to 3) after two finishes in a row within an
     * hour of the deadline or past it, and one less (down to 1) after five in a row a day early or more.
     */
    fun margins(completions: List<CompletionRecord>): Map<Kind, Int> {
        val result = HashMap<Kind, Int>()
        for ((kind, records) in completions.filter { it.dueAt != null }.groupBy { it.kind }) {
            var margin = MIN_MARGIN
            var late = 0
            var early = 0
            for (record in records.sortedBy { it.doneAt }) {
                val due = record.dueAt ?: continue
                when {
                    record.doneAt > due - HOUR_MS -> {
                        late++
                        early = 0
                    }
                    record.doneAt <= due - DAY_MS -> {
                        early++
                        late = 0
                    }
                    else -> {
                        late = 0
                        early = 0
                    }
                }
                if (late >= LATE_IN_A_ROW) {
                    margin = (margin + 1).coerceAtMost(MAX_MARGIN)
                    late = 0
                }
                if (early >= EARLY_IN_A_ROW) {
                    margin = (margin - 1).coerceAtLeast(MIN_MARGIN)
                    early = 0
                }
            }
            if (margin != MIN_MARGIN) result[kind] = margin
        }
        return result
    }

    /**
     * Box lengths per kind: an experiment over [BOXES]. A session's reward is having run its full
     * length on work its source then confirmed done by the deadline. Until [BOX_SETTLED] sessions
     * with a box, each week mostly takes the best so far and sometimes tries another (seeded by
     * [week], so a replay agrees); after, the best.
     */
    fun boxes(log: ActivityLog, defaultBox: Int, week: Long): Map<Kind, Int> {
        // Each session is judged by its own task's next completion after it, so a later round of
        // a task done again isn't credited with an earlier round's finish.
        val completions = log.completions.groupBy { it.taskId }.mapValues { (_, list) -> list.sortedBy { it.doneAt } }
        fun rewarded(session: SessionRecord): Boolean {
            if (!session.completed) return false
            val next = completions[session.taskId]?.firstOrNull { it.doneAt >= session.startedAt } ?: return false
            return next.dueAt != null && next.doneAt <= next.dueAt
        }
        // Your own box is a candidate too: with nothing to go on, it's kept.
        val candidates = (BOXES + defaultBox).distinct()
        val result = HashMap<Kind, Int>()
        for ((kind, sessions) in log.sessions.filter { it.box != null }.groupBy { it.kind }) {
            val byBox = sessions.groupBy { it.box!! }
            fun mean(box: Int): Double = byBox[box]?.let { list -> list.count(::rewarded).toDouble() / list.size } ?: 0.5
            val best = candidates.maxWith(compareBy<Int>({ mean(it) }, { if (it == defaultBox) 1 else 0 }))
            // The seed scrambled first: small seeds a week apart would draw alike.
            val random = Random(java.util.Random(week * 31 + kind.ordinal).nextLong())
            // Settled on sessions at the boxes still tried: an old box of yours doesn't settle it.
            val counted = sessions.count { it.box in candidates }
            val choice = if (counted < BOX_SETTLED && random.nextDouble() < EXPLORE) candidates.filter { it != best }.random(random) else best
            if (choice != defaultBox) result[kind] = choice
        }
        return result
    }

    private fun describe(previous: Calibration, next: Calibration): List<String> = buildList {
        // Every key either has, so one whose last completion aged out of the log is said to be back
        // to what it falls back on (the kind's, or the estimate itself).
        for (key in (previous.multipliers.keys + next.multipliers.keys).toSortedSet()) {
            val before = previous.multipliers[key] ?: fallback(previous, key)
            val after = next.multipliers[key] ?: fallback(next, key)
            if (kotlin.math.abs(after - before) >= 0.05) add("${label(key)} takes ×%.2f its estimate (was ×%.2f)".format(java.util.Locale.UK, after, before))
        }
        for (kind in Kind.entries) {
            val before = previous.marginDays[kind] ?: MIN_MARGIN
            val after = next.marginDays[kind] ?: MIN_MARGIN
            if (before != after) add("${kind.label}: finished ${days(after)} early (was ${days(before)})")
            val boxBefore = previous.boxMin[kind]
            val boxAfter = next.boxMin[kind]
            if (boxBefore != boxAfter && boxAfter != null) add("${kind.label}: pieces of $boxAfter minutes")
        }
    }

    /** What [key] uses with no value of its own: a class its kind's, a kind the estimate itself. */
    private fun fallback(calibration: Calibration, key: String): Double {
        val kind = key.substringBefore('|')
        return if (key.substringAfter('|').isEmpty()) 1.0 else calibration.multipliers["$kind|"] ?: 1.0
    }

    private fun label(key: String): String {
        val (kind, className) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return if (className.isEmpty()) "$kind, any class" else "$kind in $className"
    }

    private fun days(n: Int) = if (n == 1) "a day" else "$n days"
}
