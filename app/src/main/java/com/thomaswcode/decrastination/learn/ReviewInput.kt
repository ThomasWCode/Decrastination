package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.core.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * The weekly review as the model sees it (docs/spec/learning.md, item 7): the week's log, your
 * answers, and the calibration in; a note and changes to a few bounded settings out. Pure.
 */
object ReviewInput {

    @Serializable
    data class Change(val setting: String, val value: Int, val why: String)

    @Serializable
    data class Answer(val note: List<String>, val changes: List<Change> = emptyList())

    /**
     * What the model may change, and between what. Nothing about blocking or protection; nor, since
     * 17 Oct, a piece's length, which is yours: work's cut only past it (an hour, your call).
     */
    val BOUNDS: Map<String, IntRange> = mapOf(
        "marginDays" to 0..3,
        "softMinPerDay" to 30..120,
        "workMinPerFreeMin" to 2..5,
    )

    /** A school day's hours (AI-18): the review may move each end, in minutes of the day, by up to [HOURS_REACH]. */
    val HOURS = listOf("weekdayHoursStart", "weekdayHoursEnd")
    const val HOURS_REACH = 60

    /** What the model may change with [settings] as they are: [BOUNDS], and a school day's hours within [HOURS_REACH] of theirs. */
    fun bounds(settings: Settings): Map<String, IntRange> {
        fun near(minute: Int) = maxOf(0, minute - HOURS_REACH)..minOf(24 * 60 - 1, minute + HOURS_REACH)
        return BOUNDS + mapOf(HOURS[0] to near(settings.weekdayHours.startMin), HOURS[1] to near(settings.weekdayHours.endMin))
    }

    /** The setting's field in [Settings] (the hours' both are `weekdayHours`). */
    fun fieldOf(setting: String): String = if (setting in HOURS) "weekdayHours" else setting

    const val MAX_NOTE = 5

    /** The finished work listed, the latest: more is a busy week, not more to learn from. */
    const val MAX_LISTED = 40
    private const val MAX_TITLE = 100

    /** A note on a finished task, and a check-in answer, as far as each is read. */
    private const val MAX_NOTE_CHARS = 300
    private const val MAX_ANSWER = 600

    /** The weekly review's, for a student in Year [year] (AI-06). */
    fun system(year: Int = 12): String = """
        You review a UK sixth-form student's week (Year $year) for the planner app that schedules their homework and blocks distracting apps until it's done. You get the week's finished work (estimates against the minutes worked in its kept slots, and the student's own "harder/as expected/easier"), the slots kept and missed by hour and day, how often the blocker stopped them, their Sunday answers, and the current settings and calibration.

        You also get last week's review and what it changed, each day's plan and how much of it got done, the slots by window, what earned free time, Felix's messages and what followed them, and the week by subject.

        Write a note of at most five short lines for the student: open with one line on last week's change and how it went (or that there was none), then what the week shows, specific and kind, with numbers where they help ("German took 1.4× its estimates; its pieces now start earlier"). Then propose changes only to these settings, and only where the week gives a reason: marginDays (days before a deadline to finish, 0–3), softMinPerDay (minutes of undated work a day, 30–120), workMinPerFreeMin (minutes of work for one of free time, 2–5), weekdayHoursStart and weekdayHoursEnd (a school day's working hours, in minutes after midnight, each within $HOURS_REACH minutes of its current value). Give each change a short reason. No change is a fine answer.
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
                        "setting" to mapOf("type" to "string", "enum" to BOUNDS.keys.toList() + HOURS),
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
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /**
     * The reviewed [week] (its Monday), Monday to Sunday, as the model reads it, with its check-in
     * if it had one: a review run late, on the Monday after, doesn't take that Monday's work in, nor
     * leave the week's own Monday out.
     */
    fun describe(log: ActivityLog, calibration: Calibration, settings: Settings, now: Long, zone: ZoneId, week: String, messages: List<ChatMessage> = emptyList()): String = buildString {
        val monday = LocalDate.parse(week)
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), now + 1)
        fun inWeek(time: Long) = time in start until end
        fun at(time: Long) = Instant.ofEpochMilli(time).atZone(zone).format(WHEN)
        // Last week's review and what it changed (AI-18), so the note can say how that went.
        // By the week each reviewed (one answered late, on the Monday after, is still last week's); by
        // when it ran for one from before weeks were kept.
        log.reviews.filter { r -> r.week?.let { it < week } ?: (r.at < start) }.maxWithOrNull(compareBy({ it.week.orEmpty() }, { it.at }))?.let { last ->
            appendLine("Last week's review said: " + last.lines.take(MAX_NOTE).joinToString(" ") { it.take(MAX_NOTE_CHARS) })
            if (last.changes.isEmpty()) {
                appendLine("It changed nothing.")
            } else {
                appendLine("It changed: " + last.changes.joinToString("; ") { c -> "${c.setting} ${last.settingsBefore[c.setting] ?: "?"} to ${c.value} (${c.why.take(MAX_NOTE_CHARS)}), now ${valueOf(settings, c.setting) ?: "?"}" })
            }
            appendLine()
        }
        // Each piece of free text, and the list, kept to a length: a week's record stays a call's
        // size ([com.thomaswcode.decrastination.enrich.Pricing.MAX_INPUT_CHARS], BUG-P2-015).
        appendLine("Finished this week:")
        val finished = log.completions.filter { inWeek(it.doneAt) }
        finished.takeLast(MAX_LISTED).ifEmpty { null }?.forEach { c ->
            // A date the app or Claude set is a plan (PL-14): said, as being early or late for it says nothing.
            val due = c.dueAt?.let { " due ${at(it)}" + (if (c.hardDeadline) "" else " (a plan, not a deadline)") + "," }.orEmpty()
            val answer = c.assessment?.let { " felt $it" }.orEmpty() + c.note?.let { " (\"${it.take(MAX_NOTE_CHARS)}\")" }.orEmpty()
            appendLine("- ${c.title.take(MAX_TITLE)} [${c.kind.label}${c.className?.let { ", ${it.take(MAX_TITLE)}" }.orEmpty()}]:$due done ${at(c.doneAt)}, estimate ${c.estimateMin} min, timed ${c.workedMin} min${if (c.byHand) " (some ticked off by hand)" else ""}${if (c.photo) " (some found done by a photo check)" else ""}.$answer")
        } ?: appendLine("- nothing")
        if (finished.size > MAX_LISTED) appendLine("- and ${finished.size - MAX_LISTED} more before those")
        // The week's slots (LR-09): kept or missed, by the hour and the day they were laid in.
        val slots = log.slots.filter { inWeek(it.start) && it.outcome != SlotOutcome.Moved }
        appendLine()
        appendLine("Slots: ${slots.count { it.outcome.kept }} kept of ${slots.size}, ${slots.filter { it.outcome.kept }.sumOf { it.workedMin }} min worked; ${slots.count { it.outcome == SlotOutcome.KeptLate }} kept late, ${slots.count { it.outcome == SlotOutcome.Missed }} missed.")
        fun split(of: List<SlotRecord>) = "${of.count { it.outcome.kept }}/${of.size}"
        val byHour = slots.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).hour }.toSortedMap()
        if (byHour.isNotEmpty()) appendLine("Kept by hour: " + byHour.entries.joinToString { (h, s) -> "%02d:00 %s".format(Locale.UK, h, split(s)) })
        val byDay = slots.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }.toSortedMap()
        if (byDay.isNotEmpty()) appendLine("Kept by day: " + byDay.entries.joinToString { (d, s) -> "${d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK)} ${split(s)}" })
        val photos = slots.count { it.photo }
        if (photos > 0) appendLine("Pieces a photo check found done: $photos.")
        // By window (AI-18): where in the day the work went, moves included.
        val all = log.slots.filter { inWeek(it.start) }
        all.groupBy { it.window }.toSortedMap().takeIf { it.isNotEmpty() }?.let { windows ->
            appendLine("By window: " + windows.entries.joinToString("; ") { (w, s) -> "${w.name} ${s.count { it.outcome.kept }} kept, ${s.count { it.outcome == SlotOutcome.Missed }} missed, ${s.count { it.outcome == SlotOutcome.Moved }} moved" })
        }
        appendLine("Pieces that earned free time: ${slots.count { it.earned }}.")
        appendLine("Times the blocker stopped them: ${log.blocks.count { inWeek(it.at) }}.")
        // Each day's plan and what got done of it.
        val days = log.days.filter { d -> LocalDate.parse(d.date).let { !it.isBefore(monday) && it.isBefore(monday.plusDays(7)) } }.sortedBy { it.date }
        if (days.isNotEmpty()) {
            appendLine()
            appendLine("Days:")
            days.forEach { d ->
                appendLine("- ${LocalDate.parse(d.date).format(DAY)}${d.kind?.let { " (${it.label})" }.orEmpty()}: planned ${d.plannedMin} min, done ${d.doneMin?.let { "$it min" } ?: "not yet known"}" + when (d.full) {
                    true -> ", all of it"
                    false -> ", not all of it"
                    null -> ""
                })
            }
        }
        // Felix's own messages in the chat ([messages]), the replies to them, and the slots they were about then kept.
        val felix = ChatHistory.his(messages).filter { inWeek(it.at) && it.text.isNotBlank() }
        if (felix.isNotEmpty()) {
            val aboutSlots = felix.filter { it.pieceKey != null }
            val keptAfter = aboutSlots.count { f -> log.slots.any { s -> s.key == f.pieceKey && s.outcome.kept && s.at >= f.at && s.at < f.at + 12 * 3_600_000L } }
            appendLine()
            appendLine("Felix: ${felix.size} messages, ${felix.count { ChatHistory.replyOf(it, messages) != null }} replied to; of the ${aboutSlots.size} about a slot, ${keptAfter} saw it kept after.")
        }
        // The week by subject (PL-08).
        val subjects = Stats.bySubject(log, settings.classMap, minOf(end - 1, now), zone, days = 7)
        if (subjects.isNotEmpty()) {
            appendLine()
            appendLine("By subject:")
            subjects.forEach { s ->
                appendLine("- ${s.subject.take(MAX_TITLE)}: ${s.minutes} min in ${s.pieces} pieces" + (if (s.dated > 0) ", ${s.onTime} of ${s.dated} on time" else "") + (s.ratio?.let { ", %.2f× the estimates (from %d)".format(Locale.UK, it, s.timed) } ?: ""))
            }
        }
        // The reviewed week's answers only: last week's, answered late, aren't this week's.
        log.checkIns.filter { it.weekOf == week }.maxByOrNull { it.at }?.let { c ->
            appendLine()
            fun said(text: String) = text.ifBlank { "-" }.take(MAX_ANSWER)
            appendLine("Their Sunday answers: the week felt ${c.feel}/5. Avoided: ${said(c.avoided)}. In the way: ${said(c.inTheWay)}. Would change: ${said(c.change)}. Most energy: ${said(c.energy)}.")
        }
        appendLine()
        appendLine("Settings: boxMin ${settings.boxMin}, marginDays ${settings.marginDays}, softMinPerDay ${settings.softMinPerDay}, workMinPerFreeMin ${settings.workMinPerFreeMin}, weekdayHoursStart ${settings.weekdayHours.startMin}, weekdayHoursEnd ${settings.weekdayHours.endMin}.")
        if (calibration.multipliers.isNotEmpty()) appendLine("Learned time multipliers: " + calibration.multipliers.entries.joinToString { (k, v) -> "$k ×%.2f".format(Locale.UK, v) })
        // Per kind, these stand in for the settings above: the planner uses them where they're set.
        if (calibration.marginDays.isNotEmpty()) appendLine("Learned margins (days, used instead of marginDays): " + calibration.marginDays.entries.joinToString { (k, v) -> "${k.name} $v" })
    }.trimEnd()

    /** Whether [change] is to a setting the review may change, and within its bounds as [settings] stand. */
    fun allowed(change: Change, settings: Settings): Boolean = bounds(settings)[change.setting]?.let { change.value in it } == true

    /** [setting]'s value in [settings], for those the review may change. */
    fun valueOf(settings: Settings, setting: String): Int? = when (setting) {
        "boxMin" -> settings.boxMin
        "marginDays" -> settings.marginDays
        "softMinPerDay" -> settings.softMinPerDay
        "workMinPerFreeMin" -> settings.workMinPerFreeMin
        "weekdayHoursStart" -> settings.weekdayHours.startMin
        "weekdayHoursEnd" -> settings.weekdayHours.endMin
        else -> null
    }

    /**
     * [settings] with [changes] applied, where they're to a setting the review may change and within
     * its bounds as [settings] stood. A school day's two ends, each within its reach, can still meet
     * or cross: then neither moves.
     */
    fun apply(settings: Settings, changes: List<Change>): Settings {
        val bounds = bounds(settings)
        val applied = changes.fold(settings) { s, change ->
            val range = bounds[change.setting] ?: return@fold s
            if (change.value !in range) s else set(s, change.setting, change.value)
        }
        return if (com.thomaswcode.decrastination.core.SettingsLimits.window(applied.weekdayHours, overnight = false)) applied else applied.copy(weekdayHours = settings.weekdayHours)
    }

    /** [settings] with [setting], one the review may change, at [value], whatever its bounds: as it was before a review, put back (LR-01). */
    fun set(settings: Settings, setting: String, value: Int): Settings = when (setting) {
        "boxMin" -> settings.copy(boxMin = value)
        "marginDays" -> settings.copy(marginDays = value)
        "softMinPerDay" -> settings.copy(softMinPerDay = value)
        "workMinPerFreeMin" -> settings.copy(workMinPerFreeMin = value)
        "weekdayHoursStart" -> settings.copy(weekdayHours = settings.weekdayHours.copy(startMin = value))
        "weekdayHoursEnd" -> settings.copy(weekdayHours = settings.weekdayHours.copy(endMin = value))
        else -> settings
    }

    /** What [setting] is called on the Reviews page (LR-01). */
    fun label(setting: String): String = when (setting) {
        "boxMin" -> "A piece's length"
        "marginDays" -> "Days to spare before a deadline"
        "softMinPerDay" -> "Undated work a day"
        "workMinPerFreeMin" -> "Work for a minute of free time"
        "weekdayHoursStart" -> "A school day's start"
        "weekdayHoursEnd" -> "A school day's end"
        else -> setting
    }

    /** [setting]'s [value] as it reads: minutes, days, or a time of day. */
    fun valueText(setting: String, value: Int): String = when (setting) {
        "marginDays" -> "$value day${if (value == 1) "" else "s"}"
        "workMinPerFreeMin" -> "$value min"
        in HOURS -> "%02d:%02d".format(Locale.UK, value / 60, value % 60)
        else -> "$value min"
    }

    /** A review's [change] as the Reviews page says it: "A piece's length 45 min → 30 min (shorter pieces got finished)". */
    fun changeText(change: Change, before: Int?): String =
        label(change.setting) + " " + (before?.let { valueText(change.setting, it) + " → " } ?: "to ") + valueText(change.setting, change.value) + " (" + change.why.trim() + ")"
}
