package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Blocklist
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.learn.Stats
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The Stats page's words and rules (docs/spec/learning.md), the same on the phone and on the website. A screen draws them. */
object StatsPage {
    /** The days shown before the rest are folded. */
    const val WEEK = 7

    /** The periods but two weeks (PL-10): six weeks, and this term (its days counted as the calendar has them). */
    const val SIX_WEEKS = 42
    const val TERM = -1

    val PERIODS: List<Pair<Int, String>> = listOf(Stats.DAYS to "2 weeks", SIX_WEEKS to "6 weeks", TERM to "This term")

    const val NOTHING_LEARNED = "Nothing yet: it learns from finished work, at Sunday's review."

    val HEAT_HOURS: IntRange = 7..23

    val HEAT_NOTE: String = "7:00 to midnight, the last ${Stats.HEAT_WEEKS} weeks"

    /** One number to skim: what it is, the number, and a word on it, [warn] where it needs a look. */
    data class Tile(val label: String, val value: String, val note: String, val warn: Boolean = false)

    /** What's said beside the record: today's free time in minutes, and Claude's month. */
    data class Today(val earnedMin: Int, val spentMin: Int, val leftMin: Int, val spentUsd: Double, val calls: Int, val capUsd: Int?, val claudeOff: Boolean)

    /** How many days [period] is at [today]: a term's as the calendar has them. */
    fun days(period: Int, today: LocalDate, isSchoolDay: (LocalDate) -> Boolean): Int =
        if (period == TERM) (ChronoUnit.DAYS.between(Stats.termStart(today, isSchoolDay), today) + 1).toInt().coerceAtLeast(1) else period

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun name(target: String) = Blocklist.NAMES[target] ?: target

    fun tiles(stats: Stats.Summary, today: Today): List<Tile> = listOf(
        Tile("Finished", "${stats.completions}", if (stats.dated > 0) "${stats.onTime} of ${stats.dated} dated on time" else "none with a deadline"),
        Tile("Slots kept", "${stats.slotsKept} of ${stats.slotsPlanned}", "earned ${stats.slotsEarned}, ${Format.minutes(stats.slotMin)} worked"),
        Tile("Blocked", "${stats.blocks}", stats.topBlocked.firstOrNull()?.let { (target, _) -> "most: ${name(target)}" } ?: "nothing"),
        Tile(
            "Protection",
            if (stats.protectionProblems == 0) "Fine" else "${stats.protectionProblems}",
            if (stats.protectionProblems == 0) "no problems found" else "problems, ${stats.protectionRepaired} put right",
            warn = stats.protectionProblems > stats.protectionRepaired,
        ),
        Tile("Free time left today", Format.minutes(today.leftMin), "earned ${Format.minutes(today.earnedMin)}, spent ${Format.minutes(today.spentMin)}"),
        Tile(
            "Claude this month",
            if (today.claudeOff) "Off" else "$%.2f".format(Locale.UK, today.spentUsd),
            if (today.claudeOff) "nothing sent" else "${today.calls} call${plural(today.calls)}" + (today.capUsd?.let { ", cap $$it" } ?: ""),
        ),
    )

    /** A subject's line (PL-08): the minutes, the pieces, the deadlines met, and time against the estimates. */
    fun subject(row: Stats.SubjectRow): String =
        "${row.subject}: ${Format.minutes(row.minutes)} in ${row.pieces} piece${plural(row.pieces)}" +
            (if (row.dated > 0) ", ${row.onTime} of ${row.dated} on time" else "") +
            (row.ratio?.let { ", %.1f× the estimates (from %d)".format(Locale.UK, it, row.timed) } ?: "")

    /** A day's line under its bar. */
    fun day(day: Stats.Day): String = listOfNotNull(
        "${day.completions} done",
        Format.minutes(day.slotMin).takeIf { day.slotMin > 0 }?.let { "$it in slots" },
        "${day.blocks} block${plural(day.blocks)}".takeIf { day.blocks > 0 },
        when (day.planDone) {
            true -> "plan done"
            false -> "plan not done"
            null -> null
        },
    ).joinToString(" · ")

    /** Whether [day] had nothing in it: it fades back, so the days with something stand out. */
    fun quiet(day: Stats.Day): Boolean = day.completions == 0 && day.slotMin == 0 && day.blocks == 0

    /** The sentences behind the tiles. */
    fun inFull(stats: Stats.Summary, today: Today, days: Int, term: Boolean): List<String> {
        val top = stats.topBlocked.joinToString { (target, times) -> "${name(target)} $times" }
        return listOfNotNull(
            "${stats.completions} finished" + if (stats.dated > 0) "; ${stats.onTime} of the ${stats.dated} with a deadline by it." else ".",
            "${stats.slotsKept} slot${plural(stats.slotsKept)} kept of ${stats.slotsPlanned}, ${stats.slotsEarned} earning free time: ${Format.minutes(stats.slotMin)} worked.",
            "${stats.photoChecks} piece${plural(stats.photoChecks)} a photo check found done.".takeIf { stats.photoChecks > 0 },
            "Blocked ${stats.blocks} time${plural(stats.blocks)}" + if (top.isNotEmpty()) ": $top." else ".",
            when {
                stats.protectionProblems == 0 -> "Protection: no problems found."
                else -> "Protection: problems found ${stats.protectionProblems} time${plural(stats.protectionProblems)}, put right ${stats.protectionRepaired}."
            },
            "Free time today: earned ${Format.minutes(today.earnedMin)}, spent ${Format.minutes(today.spentMin)}: ${Format.minutes(today.leftMin)} left.",
            "Shown: the last $days days" + if (term) ", this term." else ".",
            "Claude this month: " + if (today.claudeOff) {
                "off: nothing sent."
            } else {
                "$%.2f".format(Locale.UK, today.spentUsd) + (today.capUsd?.let { " of $$it" } ?: "") + " in ${today.calls} call${plural(today.calls)}."
            },
        )
    }

    /** The offer of weekday hours from the [heat]'s kept slots. */
    fun hoursLabel(suggested: Window): String = "Set weekday hours from this (${Format.clock(suggested.startMin)}–${Format.clock(suggested.endMin)})"

    const val HOURS_TITLE = "Weekday hours from your slots?"

    fun hoursDetail(suggested: Window, now: Window): String =
        "From ${Format.clock(suggested.startMin)} to ${Format.clock(suggested.endMin)}, where most of your kept slots were on weekdays. Now ${Format.clock(now.startMin)} to ${Format.clock(now.endMin)}."

    const val FORGET_TITLE = "Forget this?"

    fun forgetDetail(row: Stats.CalibrationRow): String = "${row.line.substringBefore(':')}: what the app has learned goes, and its work so far won't teach it again. Its kind's figure stands for it meanwhile."
}
