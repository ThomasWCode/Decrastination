package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * What the app learns from how the work actually went (docs/spec/learning.md, items 1–3), worked out
 * afresh from the activity log each time, so there's no hidden state to drift: replaying the same
 * log gives the same calibration. Every value is bounded.
 */
object Calibrator {
    const val MIN_MULTIPLIER = 0.5
    const val MAX_MULTIPLIER = 3.0

    /** How much each new completion moves a multiplier. */
    const val WEIGHT = 0.3

    /**
     * Timed pieces a class needs before its own multiplier is used (IMP-P2-016, D-70): until
     * then its kind's, so one long evening can't set a new class's figure.
     */
    const val MIN_CLASS_TIMED = 3

    /** A self-assessment nudges the multiplier: more when there are no timed minutes to go on. */
    private const val NUDGE_WITH_TIME = 0.05
    private const val NUDGE_ALONE = 0.10

    const val MIN_MARGIN = 1
    const val MAX_MARGIN = 3
    private const val LATE_IN_A_ROW = 2
    private const val EARLY_IN_A_ROW = 5
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    /** A kind of day's yield (PL-06): from its last [YIELD_LOOK_BACK] finished days with work, once it has [YIELD_DAYS]. */
    const val YIELD_DAYS = 7
    const val YIELD_LOOK_BACK = 14
    const val MIN_YIELD = 0.5
    const val MAX_YIELD = 1.0

    const val HARDER = "harder"
    const val AS_EXPECTED = "as expected"
    const val EASIER = "easier"

    /** The calibration the log teaches, and what changed from [previous], in words. */
    data class Learned(val calibration: Calibration, val changes: List<String>)

    /** Of [completions], those [calibration] hasn't been told to forget (LR-06): a kind or class's from before it was. */
    fun remembered(completions: List<CompletionRecord>, calibration: Calibration): List<CompletionRecord> =
        completions.filterNot { r ->
            calibration.learnFrom?.let { r.doneAt < it } == true || calibration.forgotten[Calibration.key(r.kind, r.className)]?.let { r.doneAt < it } == true
        }

    fun learn(whole: ActivityLog, previous: Calibration, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), defaultMargin: Int = MIN_MARGIN): Learned {
        // What came before a reset of the calibration (LR-08) teaches nothing: its days and slots as well.
        val from = previous.learnFrom
        val log = if (from == null) {
            whole
        } else {
            // Its days from the one after it: the reset's own is part before, part after.
            val after = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toString()
            whole.copy(slots = whole.slots.filter { it.at >= from }, days = whole.days.filter { it.date > after })
        }
        val completions = remembered(log.completions, previous)
        val multipliers = multipliers(completions)
        val margins = margins(completions, defaultMargin)
        val next = Calibration(
            yields = yields(log.days, log.slots, today),
            yieldDays = yieldDays(log.days, log.slots, today),
            multipliers = multipliers,
            marginDays = margins,
            timed = timed(completions),
            counted = true,
            // Learned as AnkiDroid is used (DX-11), not from the log: kept as it stands.
            ankiSecondsPerCard = previous.ankiSecondsPerCard,
            ankiStretches = previous.ankiStretches,
            forgotten = previous.forgotten,
            learnFrom = previous.learnFrom,
        )
        return Learned(next, describe(previous, next, defaultMargin))
    }

    /**
     * How much of each kind of day's free time is used (PL-06): the median of done over planned on
     * its last [YIELD_LOOK_BACK] finished days with work planned, between [MIN_YIELD] and
     * [MAX_YIELD]; none for a kind with fewer than [YIELD_DAYS] such days. Each kind on its own
     * days, so a run of bad Saturdays doesn't move the weekdays.
     */
    fun yields(days: List<DayRecord>, slots: List<SlotRecord>, today: LocalDate): Map<DayKind, Double> = ratios(days, slots, today).mapValues { (_, ratios) ->
        val sorted = ratios.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        median.coerceIn(MIN_YIELD, MAX_YIELD)
    }

    /** How many days each of [yields] rests on. */
    fun yieldDays(days: List<DayRecord>, slots: List<SlotRecord>, today: LocalDate): Map<DayKind, Int> = ratios(days, slots, today).mapValues { it.value.size }

    /**
     * Each kind of day's done over planned, a day each; and school's (LR-09): of a finished day's
     * minutes laid in school's windows that morning, the share its kept slots there held, on its last
     * [YIELD_LOOK_BACK] days with any, once it has [YIELD_DAYS].
     */
    private fun ratios(days: List<DayRecord>, slots: List<SlotRecord>, today: LocalDate): Map<DayKind, List<Double>> {
        val byKind = judgedDays(days, today).mapValues { (_, judged) -> judged.map { it.doneMin!!.toDouble() / it.plannedMin } }
        // Kept in their time: one missed there and done that evening wasn't school's time used, nor
        // one ticked before it began (at home, ahead of the day) or after it ended (in its grace).
        val kept = slots.filter { it.outcome == SlotOutcome.Kept && it.window.atSchool && it.at in it.start..it.end }.groupBy { it.date }.mapValues { (_, k) -> k.sumOf { it.plannedMin } }
        val school = days.filter { it.schoolMin > 0 && it.doneMin != null && LocalDate.parse(it.date) <= today }
            .sortedBy { it.date }.takeLast(YIELD_LOOK_BACK)
            .map { minOf(1.0, (kept[it.date] ?: 0).toDouble() / it.schoolMin) }
        return if (school.size >= YIELD_DAYS) byKind + (DayKind.School to school) else byKind
    }

    /** Each kind's last finished days with work planned, where it has enough; an older record's kind by its date. */
    private fun judgedDays(days: List<DayRecord>, today: LocalDate): Map<DayKind, List<DayRecord>> =
        days.filter { it.plannedMin > 0 && it.doneMin != null && LocalDate.parse(it.date) <= today }
            .groupBy { it.kind ?: DayKind.of(LocalDate.parse(it.date)) }
            .mapValues { (_, records) -> records.sortedBy { it.date }.takeLast(YIELD_LOOK_BACK) }
            .filterValues { it.size >= YIELD_DAYS }

    /**
     * Effort multipliers per kind and class (and per kind, for classes not yet seen): a moving
     * average of actual over estimated minutes, from completions with timed minutes, nudged by
     * "harder" and "easier". A class's own is kept only once it has [MIN_CLASS_TIMED] timed pieces;
     * its kind's, which learns from every piece, stands for it till then.
     */
    fun multipliers(completions: List<CompletionRecord>): Map<String, Double> {
        val counts = timed(completions)
        return learnedMultipliers(completions).filterKeys { key -> key.substringAfter('|').isEmpty() || (counts[key] ?: 0) >= MIN_CLASS_TIMED }
    }

    /**
     * A calibration from before 1.8.0, which counted no timed pieces: its multipliers made again from
     * [completions] as the next review would make them, so a class's own needs its [MIN_CLASS_TIMED]
     * timed pieces from now, and Stats can say what each rests on. Its margins and boxes stand. Null
     * for one that isn't ([Calibration.counted]), or has no multipliers to make again.
     */
    fun upgraded(calibration: Calibration, completions: List<CompletionRecord>): Calibration? {
        if (calibration.counted || calibration.multipliers.isEmpty()) return null
        val kept = remembered(completions, calibration)
        return calibration.copy(multipliers = multipliers(kept), timed = timed(kept), counted = true)
    }

    /** Whether [record]'s minutes are how long it took: timed, and all of it (no blocks ticked by hand, no photo check). */
    private fun isTimed(record: CompletionRecord) = record.estimateMin > 0 && record.workedMin > 0 && !record.byHand && !record.photo && !record.yourEstimate

    /** Timed pieces per multiplier key: each class's, and each kind's. */
    fun timed(completions: List<CompletionRecord>): Map<String, Int> =
        completions.filter(::isTimed)
            .flatMap { listOf(Calibration.key(it.kind, it.className), Calibration.key(it.kind, null)).distinct() }
            .groupingBy { it }.eachCount()

    private fun learnedMultipliers(completions: List<CompletionRecord>): Map<String, Double> {
        val result = HashMap<String, Double>()
        for (record in completions.sortedBy { it.doneAt }) {
            // Planned by your own minutes: a multiplier corrects the app's estimates, not yours, which it isn't laid over.
            if (record.estimateMin <= 0 || record.yourEstimate) continue
            for (key in listOf(Calibration.key(record.kind, record.className), Calibration.key(record.kind, null)).distinct()) {
                var m = result[key] ?: 1.0
                // Timed, and all of it: with blocks ticked by hand, or work a photo check found done,
                // its minutes aren't how long it took.
                val timed = isTimed(record)
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
    fun margins(completions: List<CompletionRecord>, start: Int = MIN_MARGIN): Map<Kind, Int> {
        // From your own setting, so a kind brought back to it falls back to it, not past it; within
        // the usual bounds, widened to take in a setting outside them.
        val floor = minOf(MIN_MARGIN, start)
        val ceiling = maxOf(MAX_MARGIN, start)
        val result = HashMap<Kind, Int>()
        // Only real deadlines say whether you finish in time: a date the app or Claude set was a plan (PL-14).
        for ((kind, records) in completions.filter { it.dueAt != null && it.hardDeadline }.groupBy { it.kind }) {
            var margin = start
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
                    margin = (margin + 1).coerceAtMost(ceiling)
                    late = 0
                }
                if (early >= EARLY_IN_A_ROW) {
                    margin = (margin - 1).coerceAtLeast(floor)
                    early = 0
                }
            }
            if (margin != start) result[kind] = margin
        }
        return result
    }

    private fun describe(previous: Calibration, next: Calibration, defaultMargin: Int): List<String> = buildList {
        for (kind in DayKind.entries) {
            val before = previous.yieldFor(kind)
            val after = next.yieldFor(kind)
            if (abs(after - before) >= 0.05) add("${kind.label} hold about ${percent(after)} % of their free time now (was ${percent(before)} %)")
        }
        // Every key either has, so one whose last completion aged out of the log is said to be back
        // to what it falls back on (the kind's, or the estimate itself).
        for (key in (previous.multipliers.keys + next.multipliers.keys).toSortedSet()) {
            val before = previous.multipliers[key] ?: fallback(previous, key)
            val after = next.multipliers[key] ?: fallback(next, key)
            if (abs(after - before) >= 0.05) add("${label(key)} takes ×%.2f its estimate (was ×%.2f)".format(Locale.UK, after, before))
        }
        for (kind in Kind.entries) {
            val before = previous.marginDays[kind] ?: defaultMargin
            val after = next.marginDays[kind] ?: defaultMargin
            if (before != after) add("${kind.label}: finished ${days(after)} early (was ${days(before)})")
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

    private fun percent(share: Double) = round(share * 100).toInt()
}
