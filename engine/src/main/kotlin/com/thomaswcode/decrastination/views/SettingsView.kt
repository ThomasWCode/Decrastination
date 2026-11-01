package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.protect.PendingChange
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.CancelChange
import com.thomaswcode.decrastination.sync.protocol.DeleteFact
import com.thomaswcode.decrastination.sync.protocol.DeleteHalfDay
import com.thomaswcode.decrastination.sync.protocol.DeleteNoSchool
import com.thomaswcode.decrastination.sync.protocol.DeleteRule
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.SettingsEffects
import com.thomaswcode.decrastination.ui.SetupPage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Settings as the website draws them (W-14), the pages that are shared state: each field with what it takes, so the page draws
 * inputs and sends what changed, and no limit is written twice. What's asked for is shown, a change still waiting among it.
 */
@Serializable
data class SettingsView(
    val pages: List<SettingsPage>,
    /** What's waiting its hours, as protection's armed. */
    val pending: List<PendingLine>,
    /** Whether a change that loosens blocking waits, and for how long. */
    val armed: Boolean,
    val delayHours: Int,
    /** Whether a parent's code can be taken here: the server holds the secret. */
    val parentCode: Boolean,
    val school: List<ListLine>,
    val rules: List<ListLine>,
    val facts: List<ListLine>,
    val factsRoom: String? = null,
    val credentials: List<CredentialGroup>,
    /** Whether anything can be changed here: the server applies commands. */
    val canChange: Boolean,
    /** How hard Claude thinks, by job, and its calls, newest first. */
    val efforts: List<String> = emptyList(),
    val effortNote: String = SetupPage.EFFORT_NOTE,
    val calls: List<CallLine> = emptyList(),
    /** What's in the phone's Settings and not here. */
    val phoneOnly: List<String> = PHONE_ONLY,
)

/** A call to Claude, a line of it, and all of it to open. */
@Serializable
data class CallLine(val at: Long, val line: String, val details: String)

/** The phone's Settings that are its own: they're about the phone, so they stay there. */
private val PHONE_ONLY = listOf("Blocking and protection", "Calendar", "This phone's pairing", "The last crash")

@Serializable
data class SettingsPage(val key: String, val title: String, val says: String, val fields: List<SettingField>, val note: String? = null)

/** A setting: which, its words, what kind of input it takes, what it holds, its range, and whether a loosening of it waits. */
@Serializable
data class SettingField(
    val name: String,
    val label: String,
    val kind: String,
    val value: JsonElement,
    val min: Int? = null,
    val max: Int? = null,
    /** A window that can cross midnight. */
    val overnight: Boolean = false,
    /** A change to it that loosens blocking waits, once armed. */
    val waits: Boolean = false,
    /** What it does, in a sentence, as it stands. */
    val note: String? = null,
    /** The class codes a [CLASSES] field asks about. */
    val codes: List<String> = emptyList(),
) {
    companion object {
        const val NUMBER = "number"
        const val OPTIONAL_NUMBER = "optionalNumber"
        const val TIME = "time"
        const val WINDOW = "window"
        const val SWITCH = "switch"
        const val TEXT = "text"
        const val OPTIONAL_TEXT = "optionalText"
        const val LINES = "lines"
        const val CLASSES = "classes"
    }
}

@Serializable
data class PendingLine(val id: String, val line: String, val cancel: Action? = null)

/** A row of what's saved at once, and what deletes it. */
@Serializable
data class ListLine(val line: String, val delete: Action? = null)

/** Sign-ins for one source. */
@Serializable
data class CredentialGroup(val title: String, val fields: List<CredentialField>)

/** One credential: its name to the server, its words, and whether the server holds one. Never what it is. */
@Serializable
data class CredentialField(val name: String, val label: String, val secret: Boolean, val held: Boolean)

object SettingsViews {
    private val SCHOOL_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** The pages at [now], as [asked] says, [pending] waiting. [held] names the credentials the server has. */
    fun of(
        asked: Settings,
        pending: List<PendingChange>,
        tasks: TaskState,
        school: SchoolCalendar,
        memory: MemoryState,
        usage: AiUsage,
        held: Set<String>,
        parentCode: Boolean,
        now: Long,
        zone: ZoneId,
        writable: Boolean,
        calls: List<CallRecord> = emptyList(),
    ): SettingsView {
        val values = SettingsChanges.values(asked)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun field(name: String, label: String, kind: String, min: Int? = null, max: Int? = null, overnight: Boolean = false, codes: List<String> = emptyList()) = SettingField(
            name, label, kind, values.getValue(name), min, max, overnight,
            waits = name in WAITS,
            note = SettingsEffects.line(name, asked, tasks.timetable, school, today),
            codes = codes,
        )
        fun number(name: String, label: String, range: IntRange) = field(name, label, SettingField.NUMBER, range.first, range.last)
        val pages = listOf(
            SettingsPage(
                "about", "About you", "Your year, your subjects, and what Claude should know",
                listOf(
                    number("yearGroupStart", "The September you went into Year 12", SettingsLimits.YEAR_GROUP_START),
                    number("ankiTextbook", "Current German textbook", SettingsLimits.TEXTBOOK),
                    field("profileLines", "What you'd have Claude know, a line each", SettingField.LINES, max = SettingsLimits.MAX_PROFILE_LINE),
                    field("classMap", "Each class: its subject, or the Power Planner class it is, so its lessons go with it", SettingField.CLASSES, max = SettingsLimits.MAX_NAME, codes = classCodes(asked, tasks)),
                ),
                "Sent to Claude with every call, with your school days and what's remembered.",
            ),
            SettingsPage(
                "hours", "Hours and windows", "When you work, quiet hours, and when blocking starts",
                listOf(
                    field("weekdayHours", "School days: work", SettingField.WINDOW),
                    field("weekendHours", "Weekends: work", SettingField.WINDOW),
                    field("quietHours", "Quiet hours (nothing blocked)", SettingField.WINDOW, overnight = true),
                    field("weekdayBlockFromMin", "School days: blocking from", SettingField.TIME),
                    field("weekendBlockFromMin", "Weekends and days off: blocking from", SettingField.TIME),
                ),
            ),
            SettingsPage("school", "School", "Days with no school, and half-days", emptyList()),
            SettingsPage(
                "planning", "Planning", "Pieces, margins, undated work and free time",
                listOf(
                    number("boxMin", "Work cut into pieces of (minutes)", SettingsLimits.BOX_MIN),
                    number("marginDays", "Finish this many days before a deadline", SettingsLimits.MARGIN_DAYS),
                    number("softDeadlineDays", "Days given to undated work", SettingsLimits.SOFT_DEADLINE_DAYS),
                    number("softMinPerDay", "Undated work a day, at most (minutes)", SettingsLimits.SOFT_MIN_PER_DAY),
                    number("workMinPerFreeMin", "Minutes of work for one of free time", SettingsLimits.WORK_MIN_PER_FREE_MIN),
                    number("photoFromMin", "Pieces this long earn with a photo (minutes)", SettingsLimits.PHOTO_FROM_MIN),
                    number("freeAllowanceMin", "A day with nothing to do: free minutes", SettingsLimits.FREE_ALLOWANCE_MIN),
                    number("outsideBreaks", "Outside breaks on a free day", SettingsLimits.OUTSIDE_BREAKS),
                    number("outsideBreakMin", "Each outside break (minutes)", SettingsLimits.OUTSIDE_BREAK_MIN),
                ),
            ),
            SettingsPage(
                "felix", "Felix", "His name, when he asks, and what he remembers",
                listOf(
                    field("felixName", "His name", SettingField.TEXT, max = SettingsLimits.MAX_NAME),
                    field("felixLine", "Who he is", SettingField.TEXT, max = SettingsLimits.MAX_FELIX_LINE),
                    number("felixGraceMin", "He asks this far into a slot with nothing done (minutes)", SettingsLimits.FELIX_GRACE_MIN),
                    number("felixDefaultCheckMin", "He checks again after, if he doesn't say (minutes)", SettingsLimits.FELIX_CHECK_MIN),
                    field("felixSearches", "He searches the web when a message needs it (about 5¢ a search)", SettingField.SWITCH),
                ),
            ),
            SettingsPage(
                "claude", "Claude", "Its cap, and this month's cost",
                listOf(
                    field("aiEnabled", "Use Claude for steps, estimates and email triage", SettingField.SWITCH),
                    field("aiMonthlyCapUsd", "Monthly cap ($, blank for none)", SettingField.OPTIONAL_NUMBER, SettingsLimits.CAP_USD.first, SettingsLimits.CAP_USD.last),
                ),
                "This month: $%.2f in %d calls.".format(Locale.UK, usage.spentUsd, usage.calls),
            ),
            SettingsPage(
                "sources", "Sources", "Teams, Power Planner, AnkiDroid and Gmail",
                listOf(
                    field("gmailFinishes", "When you finish a starred email's work here, unstar and archive it in Gmail", SettingField.SWITCH),
                    field("teamsAutoSync", "Sync Teams automatically", SettingField.SWITCH),
                    field("teamsFirstUnlockMin", "First unlock after", SettingField.TIME),
                    number("teamsSyncEveryMin", "Then every (minutes)", SettingsLimits.TEAMS_EVERY_MIN),
                    field("ankiDeadlineMin", "Daily cards due by", SettingField.TIME),
                ),
            ),
            SettingsPage(
                "notifications", "Notifications", "The morning briefing and Sunday's check-in",
                listOf(
                    field("briefingWeekdayMin", "Morning briefing, school days", SettingField.TIME),
                    field("briefingWeekendMin", "Morning briefing, weekends and days off", SettingField.TIME),
                    field("checkInMin", "Sunday check-in", SettingField.TIME),
                    field("summaryEmail", "Weekly summary goes to (an email address, or none)", SettingField.OPTIONAL_TEXT, max = SUMMARY_EMAIL_MAX),
                ),
            ),
        )
        return SettingsView(
            pages = pages,
            pending = pending.map { PendingLine(it.id, "${it.description}: applies about ${Format.at(it.applyAt, now, zone)}", CancelChange(it.id).takeIf { writable }) },
            armed = asked.armed,
            delayHours = asked.loosenDelayHours,
            parentCode = parentCode && writable,
            school = school.noSchool.map { entry -> ListLine(schoolLine(entry), DeleteNoSchool(entry).takeIf { writable }) } +
                school.halfDays.map { entry -> ListLine(schoolLine(entry), DeleteHalfDay(entry).takeIf { writable }) },
            rules = memory.rules.map { rule -> ListLine(rule.text + (Memories.held(rule.until, null, { null }, zone)?.let { " ($it)" } ?: ""), DeleteRule(rule).takeIf { writable }) },
            facts = memory.facts.asReversed().map { fact ->
                val titles = tasks.tasks.associate { it.id to it.title }
                ListLine("${fact.topic}: ${fact.text}" + (Memories.held(fact.until, fact.taskId, titles::get, zone)?.let { " ($it)" } ?: ""), DeleteFact(fact).takeIf { writable })
            },
            factsRoom = memory.facts.takeIf { it.isNotEmpty() }?.let { "${Memories.chars(it) * 100 / Memories.FACT_BUDGET_CHARS}% of the room for facts used." },
            efforts = ModelEffort.table().map { (effort, jobs) -> "$effort: $jobs" },
            calls = calls.asReversed().map { CallLine(it.at, SetupPage.callLine(it, zone), it.details(zone)) },
            credentials = CREDENTIALS.map { (title, fields) -> CredentialGroup(title, fields.map { (name, label, secret) -> CredentialField(name, label, secret, name in held) }) },
            canChange = writable,
        )
    }

    private fun day(text: String) = runCatching { LocalDate.parse(text).format(SCHOOL_DAY) }.getOrDefault(text)

    private fun schoolLine(entry: NoSchool): String {
        val days = if (entry.from == entry.to) day(entry.from) else "${day(entry.from)} to ${day(entry.to)}"
        return "No school, $days" + entry.label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }

    private fun schoolLine(entry: HalfDay) = "Half-day, ${day(entry.date)}: ends %02d:%02d".format(Locale.UK, entry.endsAtMin / 60, entry.endsAtMin % 60)

    /** Each class code the tasks give, those named already, and the timetable's classes but its study period, as the phone's page lists them. */
    private fun classCodes(asked: Settings, tasks: TaskState): List<String> =
        (asked.classMap.keys + tasks.tasks.mapNotNull { it.className?.trim()?.takeIf(String::isNotEmpty) } +
            tasks.timetable?.classes.orEmpty().map { it.name.trim() }.filter { it.isNotEmpty() && !it.equals(asked.studyPeriodName.trim(), ignoreCase = true) })
            .distinct().sorted().take(SettingsLimits.MAX_CLASSES)

    /** The fields here that can wait once armed: the rest are about the work, yours to change at once (D-46). */
    private val WAITS = setOf("quietHours", "weekdayBlockFromMin", "weekendBlockFromMin", "workMinPerFreeMin", "photoFromMin", "freeAllowanceMin")

    /** The most of an email address the summary goes to (NF-02). */
    private const val SUMMARY_EMAIL_MAX = 120

    /** The sources' sign-ins the website can give (W-19): their names as the server's `Secret`s. */
    private val CREDENTIALS = listOf(
        "Power Planner login" to listOf(Triple("PowerPlannerUsername", "Username", false), Triple("PowerPlannerPassword", "Password", true)),
        "Gmail" to listOf(Triple("GmailAddress", "Address", false), Triple("GmailAppPassword", "App password", true)),
    )
}
