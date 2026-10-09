package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.Settings
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /** The last seven days, as the model reads them. */
    fun describe(log: ActivityLog, calibration: Calibration, settings: Settings, now: Long, zone: ZoneId): String = buildString {
        val since = now - 7 * 24 * 3_600_000L
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        appendLine("Finished this week:")
        log.completions.filter { it.doneAt >= since }.ifEmpty { null }?.forEach { c ->
            val due = c.dueAt?.let { " due ${at(it)}," }.orEmpty()
            val answer = c.assessment?.let { " felt $it" }.orEmpty() + c.note?.let { " (\"$it\")" }.orEmpty()
            appendLine("- ${c.title} [${c.kind.label}${c.className?.let { ", $it" }.orEmpty()}]:$due done ${at(c.doneAt)}, estimate ${c.estimateMin} min, timed ${c.workedMin} min.$answer")
        } ?: appendLine("- nothing")
        val sessions = log.sessions.filter { it.startedAt >= since }
        appendLine()
        appendLine("Focus sessions: ${sessions.size}, ${sessions.count { it.completed }} run to the end, ${sessions.sumOf { it.workedMin }} min in all.")
        val byHour = sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }.toSortedMap()
        if (byHour.isNotEmpty()) appendLine("Started by hour: " + byHour.entries.joinToString { (h, s) -> "%02d:00 ×%d".format(Locale.UK, h, s.size) })
        appendLine("Times the blocker stopped them: ${log.blocks.count { it.at >= since }}.")
        log.checkIns.maxByOrNull { it.at }?.takeIf { it.at >= since }?.let { c ->
            appendLine()
            appendLine("Their Sunday answers: the week felt ${c.feel}/5. Avoided: ${c.avoided.ifBlank { "-" }}. In the way: ${c.inTheWay.ifBlank { "-" }}. Would change: ${c.change.ifBlank { "-" }}. Most energy: ${c.energy.ifBlank { "-" }}.")
        }
        appendLine()
        appendLine("Settings: boxMin ${settings.boxMin}, marginDays ${settings.marginDays}, softMinPerDay ${settings.softMinPerDay}, workMinPerFreeMin ${settings.workMinPerFreeMin}.")
        if (calibration.multipliers.isNotEmpty()) appendLine("Learned time multipliers: " + calibration.multipliers.entries.joinToString { (k, v) -> "$k ×%.2f".format(Locale.UK, v) })
    }.trimEnd()

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
