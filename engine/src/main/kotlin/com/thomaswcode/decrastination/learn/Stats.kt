package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.WeekRollup
import com.thomaswcode.decrastination.ui.Format
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Stats tab shows (docs/spec/learning.md), from the activity log: each recent day's finished
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

    /**
     * The [days] to [now] in figures: each day's, and the period's. Weeks older than the log keeps
     * ([ActivityLog.KEEP_DAYS]) come from their rollups (PL-10), in the totals only.
     */
    fun summary(log: ActivityLog, now: Long, zone: ZoneId, days: Int = DAYS): Summary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(days - 1L)
        fun dateOf(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        fun inRange(at: Long): Boolean = dateOf(at).let { !it.isBefore(first) && !it.isAfter(today) }
        // Weeks the log no longer keeps whole, in the period (PL-10): their rollups in the totals, in
        // place of what's left of their records, the week the log's end falls in among them.
        val kept0 = Instant.ofEpochMilli(now - ActivityLog.KEEP_DAYS * DAY_MS).atZone(zone).toLocalDate()
        val older = log.weeks.filter { w -> LocalDate.parse(w.week).let { !it.isBefore(first) && it.isBefore(kept0) } }
        val rolled = older.map { LocalDate.parse(it.week) }
        fun rolledUp(date: LocalDate) = rolled.any { monday -> !date.isBefore(monday) && date.isBefore(monday.plusDays(7)) }
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
        // In the totals, a rolled-up week's records are its rollup's. Only real deadlines are met or
        // missed (PL-14): a date the app or Claude set was a plan.
        val done = completions.filterNot { rolledUp(dateOf(it.doneAt)) }
        val dated = done.filter { it.dueAt != null && it.hardDeadline }
        val laid = slots.filterNot { rolledUp(LocalDate.parse(it.date)) }
        val held = laid.filter { it.outcome.kept }
        return Summary(
            days = perDay,
            completions = done.size + older.sumOf { it.finished },
            dated = dated.size + older.sumOf { it.dated },
            onTime = dated.count { it.doneAt <= it.dueAt!! } + older.sumOf { it.onTime },
            slotsPlanned = laid.size + older.sumOf { it.slotsKept + it.slotsMissed },
            slotsKept = held.size + older.sumOf { it.slotsKept },
            slotsEarned = kept.count { it.earned },
            photoChecks = kept.count { it.photo },
            slotMin = held.sumOf { it.workedMin } + older.sumOf { it.minutes },
            blocks = blocks.count { !rolledUp(dateOf(it.at)) } + older.sumOf { it.blocks },
            topBlocked = blocks.groupingBy { it.target }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(TOP)
                .map { it.key to it.value },
            protectionProblems = protection.count { it.problems.isNotEmpty() },
            protectionRepaired = protection.count { it.repaired },
        )
    }

    /**
     * This week so far and last week in figures, as Felix is told them when he asks (G6): each week's time
     * worked in kept slots, its slots kept and missed, its work finished and the real deadlines among it met
     * (PL-14), then its subjects, busiest first, as [bySubject] names them; [MAX_WEEK_LINES] lines at most,
     * each week an even share of them: past its share, its last line says how many more subjects there are.
     */
    fun weekLines(log: ActivityLog, classMap: Map<String, String>, now: Long, zone: ZoneId): List<String> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weeks = listOf(Triple("This week so far", monday, today), Triple("Last week", monday.minusDays(7), monday.minusDays(1)))
        // A week's own line, then its subjects in what's left of its share.
        val each = MAX_WEEK_LINES / weeks.size - 1
        return weeks.flatMap { (label, first, last) ->
            fun inWeek(date: LocalDate) = !date.isBefore(first) && !date.isAfter(last)
            val slots = log.slots.filter { inWeek(LocalDate.parse(it.date)) && it.outcome != SlotOutcome.Moved }
            val kept = slots.filter { it.outcome.kept }
            val done = log.completions.filter { inWeek(Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate()) }
            val dated = done.filter { it.dueAt != null && it.hardDeadline }
            val head = "$label (${first.format(WEEK_DAY)} to ${last.format(WEEK_DAY)}): ${Format.minutes(kept.sumOf { it.workedMin })} worked in ${kept.size} slots kept, " +
                "${slots.count { it.outcome == SlotOutcome.Missed }} missed; ${done.size} finished, ${dated.count { it.doneAt <= it.dueAt!! }} of ${dated.size} deadlines met"
            fun subject(className: String?) = className?.trim()?.takeIf { it.isNotEmpty() }?.let { classMap[it]?.trim()?.takeIf { s -> s.isNotEmpty() } ?: it } ?: OTHER
            val subjects = (kept.map { subject(it.className) } + done.map { subject(it.className) }).distinct().map { s ->
                val theirs = kept.filter { subject(it.className) == s }
                Triple(s, theirs.sumOf { it.workedMin }, theirs.size) to done.count { subject(it.className) == s }
            }.sortedWith(compareByDescending<Pair<Triple<String, Int, Int>, Int>> { it.first.second }.thenBy { it.first.first })
            // Past its share, one line fewer of them, so the count of the rest fits too.
            val shown = if (subjects.size <= each) subjects else subjects.take(each - 1)
            listOf(head) + shown.map { (s, finished) -> "  - ${s.first}: ${Format.minutes(s.second)} in ${s.third} slots; $finished finished" } +
                listOfNotNull((subjects.size - shown.size).takeIf { it > 0 }?.let { "  - and $it more subjects" })
        }
    }

    /** The most lines [weekLines] gives. */
    const val MAX_WEEK_LINES = 20

    private val WEEK_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** A subject's period (PL-08): its kept slots' minutes and pieces, its deadlines met of those it had, and its timed work's time over its estimates, from [timed] pieces. */
    data class SubjectRow(val subject: String, val minutes: Int, val pieces: Int, val dated: Int, val onTime: Int, val timed: Int, val ratio: Double?)

    /**
     * Each subject's numbers over the [days] to [now] (PL-08), busiest first. A subject is the class
     * map's name for a class, or the class as it is; work with neither is [OTHER]. Timed work is
     * finished work with minutes and an estimate of the app's, none of it ticked by hand or by photo.
     */
    fun bySubject(log: ActivityLog, classMap: Map<String, String>, now: Long, zone: ZoneId, days: Int = DAYS): List<SubjectRow> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays(days - 1L)
        fun inRange(date: LocalDate) = !date.isBefore(first) && !date.isAfter(today)
        fun subject(className: String?) = className?.trim()?.takeIf { it.isNotEmpty() }?.let { classMap[it]?.trim()?.takeIf { s -> s.isNotEmpty() } ?: it } ?: OTHER
        val kept = log.slots.filter { it.outcome.kept && inRange(LocalDate.parse(it.date)) }
        val done = log.completions.filter { inRange(Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate()) }
        return (kept.map { subject(it.className) } + done.map { subject(it.className) }).distinct().map { s ->
            val slots = kept.filter { subject(it.className) == s }
            val finished = done.filter { subject(it.className) == s }
            val dated = finished.filter { it.dueAt != null && it.hardDeadline }
            val timed = finished.filter { it.estimateMin > 0 && it.workedMin > 0 && !it.byHand && !it.photo && !it.yourEstimate }
            SubjectRow(
                subject = s,
                minutes = slots.sumOf { it.workedMin },
                pieces = slots.size,
                dated = dated.size,
                onTime = dated.count { it.doneAt <= it.dueAt!! },
                timed = timed.size,
                ratio = timed.takeIf { it.isNotEmpty() }?.let { t -> t.sumOf { it.workedMin }.toDouble() / t.sumOf { it.estimateMin } },
            )
        }.sortedWith(compareByDescending<SubjectRow> { it.minutes }.thenBy { it.subject })
    }

    /** Slots kept and missed by their start's weekday and hour (PL-09), over the last [weeks] weeks: (kept, missed). */
    fun heat(log: ActivityLog, now: Long, zone: ZoneId, weeks: Int = HEAT_WEEKS): Map<Pair<DayOfWeek, Int>, Pair<Int, Int>> =
        log.slots.filter { it.start in (now - weeks * 7 * DAY_MS)..now && it.outcome != SlotOutcome.Moved }
            .groupBy { Instant.ofEpochMilli(it.start).atZone(zone).let { t -> t.dayOfWeek to t.hour } }
            .mapValues { (_, slots) -> slots.count { it.outcome.kept } to slots.count { it.outcome == SlotOutcome.Missed } }

    /**
     * The weekday hours [heat]'s kept slots suggest (PL-09): from the start of the first hour to the
     * end of the last that hold them, the outlying tenth at each end left out; null with fewer than
     * [MIN_HEAT] kept on weekdays to go by.
     */
    fun suggestedWeekdayHours(heat: Map<Pair<DayOfWeek, Int>, Pair<Int, Int>>): Window? {
        val byHour = IntArray(24)
        heat.forEach { (at, counts) -> if (at.first.value <= DayOfWeek.FRIDAY.value) byHour[at.second] += counts.first }
        val total = byHour.sum()
        if (total < MIN_HEAT) return null
        var low = 0
        var cut = 0
        while (low < 23 && cut + byHour[low] <= total / 10) cut += byHour[low++]
        var high = 23
        cut = 0
        while (high > low && cut + byHour[high] <= total / 10) cut += byHour[high--]
        return Window(low * 60, minOf(24 * 60 - 1, (high + 1) * 60))
    }

    /** The week from [monday] in totals (PL-10), as its review writes them down. */
    fun rollup(log: ActivityLog, monday: LocalDate, zone: ZoneId): WeekRollup {
        val dates = (0L..6L).map { monday.plusDays(it).toString() }.toSet()
        fun inWeek(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString() in dates
        val slots = log.slots.filter { it.date in dates && it.outcome != SlotOutcome.Moved }
        val done = log.completions.filter { inWeek(it.doneAt) }
        val dated = done.filter { it.dueAt != null && it.hardDeadline }
        return WeekRollup(
            week = monday.toString(),
            minutes = slots.filter { it.outcome.kept }.sumOf { it.workedMin },
            finished = done.size,
            onTime = dated.count { it.doneAt <= it.dueAt!! },
            dated = dated.size,
            blocks = log.blocks.count { inWeek(it.at) },
            fullDays = log.days.count { it.date in dates && it.full == true },
            slotsKept = slots.count { it.outcome.kept },
            slotsMissed = slots.count { it.outcome == SlotOutcome.Missed },
        )
    }

    /**
     * When this term began (PL-10's period), as the school calendar has it: the day after the last
     * run of [HOLIDAY_DAYS] or more days without school before [today], a weekend included; with
     * none in [TERM_LOOK_BACK] days, that far back. The days off today is in are this term's break,
     * not the one before it: they're passed first.
     */
    fun termStart(today: LocalDate, isSchoolDay: (LocalDate) -> Boolean): LocalDate {
        var first = 1
        if (!isSchoolDay(today)) {
            while (first < TERM_LOOK_BACK && !isSchoolDay(today.minusDays(first.toLong()))) first++
        }
        var run = 0
        for (back in first..TERM_LOOK_BACK) {
            val date = today.minusDays(back.toLong())
            if (isSchoolDay(date)) {
                if (run >= HOLIDAY_DAYS) return date.plusDays(run + 1L)
                run = 0
            } else {
                run++
            }
        }
        return if (run >= HOLIDAY_DAYS) today.minusDays(TERM_LOOK_BACK - run + 1L) else today.minusDays(TERM_LOOK_BACK.toLong())
    }

    /**
     * [log]'s week rollups, those of weeks the log still holds whole made again from its records
     * (PL-10): a week's is written at its review, which can come before the week's end (Sunday
     * evening's), so what came in after it is taken in while the records are there.
     */
    fun refreshedWeeks(log: ActivityLog, now: Long, zone: ZoneId): List<WeekRollup> {
        val cut = Instant.ofEpochMilli(now - ActivityLog.KEEP_DAYS * DAY_MS).atZone(zone).toLocalDate()
        return log.weeks.map { w -> LocalDate.parse(w.week).let { monday -> if (monday.isAfter(cut)) rollup(log, monday, zone) else w } }
    }

    /** Work with no class, by subject. */
    const val OTHER = "Other"

    /** How far back the hours' heat looks. */
    const val HEAT_WEEKS = 8

    private const val MIN_HEAT = 10
    private const val HOLIDAY_DAYS = 5
    private const val TERM_LOOK_BACK = 120
    private const val DAY_MS = 24 * 3_600_000L

    /** A line of what's been learned (LR-06), and the kind or class it's of where it can be forgotten. */
    data class CalibrationRow(val line: String, val forget: String? = null)

    /** The calibration in words, a line a value: what the planner now assumes about your work. */
    fun calibrationLines(calibration: Calibration): List<String> = calibrationRows(calibration).map { it.line }

    /**
     * [calibrationLines] as rows (LR-06), each class's figure (and one still coming) with its key, so
     * it can be forgotten ([Calibration.forgotten]), starting afresh from its kind's.
     */
    fun calibrationRows(calibration: Calibration): List<CalibrationRow> {
        fun what(key: String): String {
            val kind = key.substringBefore('|')
            val className = key.substringAfter('|', "")
            val label = Kind.entries.firstOrNull { it.name == kind }?.label ?: kind
            return if (className.isEmpty()) label else "$label, $className"
        }
        // What each figure rests on (IMP-P2-016): its timed pieces, or only your answers.
        fun pieces(n: Int) = "$n timed piece${if (n == 1) "" else "s"}"
        fun forgettable(key: String) = key.takeIf { it.substringAfter('|').isNotEmpty() }
        val multipliers = calibration.multipliers.entries.sortedBy { it.key }.map { (key, value) ->
            val n = calibration.timed[key] ?: 0
            CalibrationRow("${what(key)}: takes %.1f× the estimate".format(Locale.UK, value) + if (n > 0) " (from ${pieces(n)})" else " (from your answers)", forgettable(key))
        }
        // A class with some timed pieces, not yet enough for its own figure.
        val coming = calibration.timed.entries.filter { (key, n) -> key.substringAfter('|').isNotEmpty() && key !in calibration.multipliers && n > 0 }
            .sortedBy { it.key }
            .map { (key, n) -> CalibrationRow("${what(key)}: ${pieces(n)} so far; its own figure counts from ${Calibrator.MIN_CLASS_TIMED}", key) }
        val margins = calibration.marginDays.entries.sortedBy { it.key.ordinal }.map { (kind, days) ->
            CalibrationRow("${kind.label}: finished $days day${if (days == 1) "" else "s"} before the deadline")
        }
        // How much of each kind of day's free time is used (PL-06), and the days it rests on.
        val yields = calibration.yields.entries.sortedBy { it.key.ordinal }.map { (kind, share) ->
            val n = calibration.yieldDays[kind] ?: 0
            CalibrationRow("${kind.label}: about ${kotlin.math.round(share * 100).toInt()} % of the free time used" + if (n > 0) " (from $n days)" else "")
        }
        return multipliers + coming + margins + yields
    }

    private const val TOP = 5
}
