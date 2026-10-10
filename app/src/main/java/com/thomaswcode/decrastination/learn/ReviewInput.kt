package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SessionRecord
import com.thomaswcode.decrastination.data.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * The weekly review as the model sees it (docs/scheduler.md §5, item 7): the week's log, your
 * answers, and the calibration in; a note and changes to a few bounded settings out. Pure.
 */
object ReviewInput {

    @Serializable
    data class Change(val setting: String, val value: Int, val why: String)

    @Serializable
    data class Answer(val note: List<String>, val changes: List<Change> = emptyList())

    /** What the model may change, and between what. Nothing about blocking, protection or hours. */
    val BOUNDS: Map<String, IntRange> = mapOf(
        "boxMin" to 25..60,
        "marginDays" to 0..3,
        "softMinPerDay" to 30..120,
        "workMinPerFreeMin" to 2..5,
    )

    const val MAX_NOTE = 5

    val SYSTEM = """
        You review a UK sixth-form student's week (Year 12) for the planner app that schedules their homework and blocks distracting apps until it's done. You get the week's finished work (estimates against the minutes timed in focus sessions, and the student's own "harder/as expected/easier"), the sessions by hour, how often the blocker stopped them, their Sunday answers, and the current settings and calibration.

        Write a note of at most five short lines for the student: what the week shows, specific and kind, with numbers where they help ("German took 1.4× its estimates; its pieces now start earlier"). Then propose changes only to these settings, and only where the week gives a reason: boxMin (minutes a piece of work is cut into, 25–60), marginDays (days before a deadline to finish, 0–3), softMinPerDay (minutes of undated work a day, 30–120), workMinPerFreeMin (minutes of work for one of free time, 2–5). Give each change a short reason. No change is a fine answer.
    """.trimIndent()

    fun schema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "note" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "changes" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "setting" to mapOf("type" to "string", "enum" to BOUNDS.keys.toList()),
                        "value" to mapOf("type" to "integer"),
                        "why" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("setting", "value", "why"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to listOf("note", "changes"),
        "additionalProperties" to false,
    )

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    /**
     * The reviewed [week] (its Monday), Monday to Sunday, as the model reads it, with its check-in
     * if it had one: a review run late, on the Monday after, doesn't take that Monday's work in, nor
     * leave the week's own Monday out.
     */
    fun describe(log: ActivityLog, calibration: Calibration, settings: Settings, now: Long, zone: ZoneId, week: String): String = buildString {
        val monday = LocalDate.parse(week)
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), now + 1)
        fun inWeek(time: Long) = time in start until end
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        appendLine("Finished this week:")
        log.completions.filter { inWeek(it.doneAt) }.ifEmpty { null }?.forEach { c ->
            val due = c.dueAt?.let { " due ${at(it)}," }.orEmpty()
            val answer = c.assessment?.let { " felt $it" }.orEmpty() + c.note?.let { " (\"$it\")" }.orEmpty()
            appendLine("- ${c.title} [${c.kind.label}${c.className?.let { ", $it" }.orEmpty()}]:$due done ${at(c.doneAt)}, estimate ${c.estimateMin} min, timed ${c.workedMin} min${if (c.byHand) " (some ticked off by hand)" else ""}${if (c.photo) " (some found done by a photo check)" else ""}.$answer")
        } ?: appendLine("- nothing")
        // A session across the week's start or end: only its minutes within the week.
        fun minutes(session: SessionRecord) = Days.minutesIn(session, start, end)
        val sessions = log.sessions.filter { !it.photo && (inWeek(it.startedAt) || minutes(it) > 0) }
        val photos = log.sessions.count { inWeek(it.startedAt) && it.photo }
        appendLine()
        appendLine("Focus sessions: ${sessions.size}, ${sessions.count { it.completed }} run to the end, ${sessions.sumOf { minutes(it) }} min in all.")
        val byHour = sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }.toSortedMap()
        if (byHour.isNotEmpty()) appendLine("Started by hour: " + byHour.entries.joinToString { (h, s) -> "%02d:00 ×%d".format(Locale.UK, h, s.size) })
        if (photos > 0) appendLine("Pieces a photo check found done: $photos.")
        appendLine("Times the blocker stopped them: ${log.blocks.count { inWeek(it.at) }}.")
        // The reviewed week's answers only: last week's, answered late, aren't this week's.
        log.checkIns.filter { it.weekOf == week }.maxByOrNull { it.at }?.let { c ->
            appendLine()
            appendLine("Their Sunday answers: the week felt ${c.feel}/5. Avoided: ${c.avoided.ifBlank { "-" }}. In the way: ${c.inTheWay.ifBlank { "-" }}. Would change: ${c.change.ifBlank { "-" }}. Most energy: ${c.energy.ifBlank { "-" }}.")
        }
        appendLine()
        appendLine("Settings: boxMin ${settings.boxMin}, marginDays ${settings.marginDays}, softMinPerDay ${settings.softMinPerDay}, workMinPerFreeMin ${settings.workMinPerFreeMin}.")
        if (calibration.multipliers.isNotEmpty()) appendLine("Learned time multipliers: " + calibration.multipliers.entries.joinToString { (k, v) -> "$k ×%.2f".format(Locale.UK, v) })
        // Per kind, these stand in for the settings above: the planner uses them where they're set.
        if (calibration.marginDays.isNotEmpty()) appendLine("Learned margins (days, used instead of marginDays): " + calibration.marginDays.entries.joinToString { (k, v) -> "${k.name} $v" })
        if (calibration.boxMin.isNotEmpty()) appendLine("Learned box lengths (minutes, used instead of boxMin): " + calibration.boxMin.entries.joinToString { (k, v) -> "${k.name} $v" })
    }.trimEnd()

    /** Whether [change] is to a setting the review may change, and within its bounds. */
    fun allowed(change: Change): Boolean = BOUNDS[change.setting]?.let { change.value in it } == true

    /** [setting]'s value in [settings], for those the review may change. */
    fun valueOf(settings: Settings, setting: String): Int? = when (setting) {
        "boxMin" -> settings.boxMin
        "marginDays" -> settings.marginDays
        "softMinPerDay" -> settings.softMinPerDay
        "workMinPerFreeMin" -> settings.workMinPerFreeMin
        else -> null
    }

    /** [settings] with [changes] applied, where they're to a setting the review may change and within its bounds. */
    fun apply(settings: Settings, changes: List<Change>): Settings = changes.fold(settings) { s, change ->
        val range = BOUNDS[change.setting] ?: return@fold s
        if (change.value !in range) return@fold s
        when (change.setting) {
            "boxMin" -> s.copy(boxMin = change.value)
            "marginDays" -> s.copy(marginDays = change.value)
            "softMinPerDay" -> s.copy(softMinPerDay = change.value)
            "workMinPerFreeMin" -> s.copy(workMinPerFreeMin = change.value)
            else -> s
        }
    }
}
