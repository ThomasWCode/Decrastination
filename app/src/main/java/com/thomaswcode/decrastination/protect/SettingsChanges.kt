package com.thomaswcode.decrastination.protect

import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A change that loosens blocking itself, waiting its 24 hours (docs/scheduler.md §6, layer 4). */
@Serializable
data class PendingChange(
    val id: String,
    /** The [Settings] property it changes. */
    val field: String,
    /** Its new value, as JSON. */
    val value: JsonElement,
    val description: String,
    val requestedAt: Long,
    /** When it should apply by the wall clock: what's shown. Setting the date forward doesn't hurry it. */
    val applyAt: Long,
    /** How long it waits, counted on the uptime clock (the time the phone is on), which the date can't move. */
    val waitMs: Long = applyAt - requestedAt,
    /** Waited so far by that clock. */
    val waitedMs: Long = 0,
)

/**
 * Layer 4 of the anti-tamper (docs/scheduler.md §6): once protection is armed, any change that
 * loosens blocking waits [Settings.loosenDelayHours] before it applies, shown as pending; one that
 * tightens applies at once; a parent code (layer 6) applies one pending change at once. Before
 * arming, everything applies at once (Q20). Pure.
 *
 * Changes are made field by field, so two pending changes never undo each other.
 */
object SettingsChanges {

    /** A setting about the work, not the blocker: it never waits. */
    private val NEVER: (Settings, Settings) -> Boolean = { _, _ -> false }

    /**
     * For each setting, whether a new value loosens blocking compared with the old. Only the
     * blocker's own settings can (your call, 12 Oct): what's blocked, when, how free time is earned,
     * and protection itself. Everything about the work (when you work, how it's cut up and spread,
     * Anki, the Teams syncs, Claude, the reminders) is yours to change at once, as due dates and
     * minutes are, though moving work off today and tomorrow can lift the pressure: blocked apps
     * still need earned free time then.
     */
    private val LOOSER: Map<String, (Settings, Settings) -> Boolean> = mapOf(
        "ankiTextbook" to NEVER,
        "yearGroupStart" to NEVER,
        "profileLines" to NEVER,
        "classMap" to NEVER,
        "felixName" to NEVER,
        "felixLine" to NEVER,
        "felixGraceMin" to NEVER,
        "felixDefaultCheckMin" to NEVER,
        "hintsSeen" to NEVER,
        "ankiDeadlineMin" to NEVER,
        "weekdayHours" to NEVER,
        "weekendHours" to NEVER,
        "studyPeriodName" to NEVER,
        "breakWindow" to NEVER,
        "lunchWindows" to NEVER,
        "minSchoolSlotMin" to NEVER,
        "boxMin" to NEVER,
        "slotBreakMin" to NEVER,
        "slotLongBreakMin" to NEVER,
        "slotLongBreakAfterMin" to NEVER,
        "outsideBreaks" to NEVER,
        "outsideBreakMin" to NEVER,
        "outsideTimes" to NEVER,
        // A higher line means more pieces earn on a tick, without a photo.
        "photoFromMin" to { old, new -> new.photoFromMin > old.photoFromMin },
        "freeAllowanceMin" to { old, new -> new.freeAllowanceMin > old.freeAllowanceMin },
        "calendarWriteId" to NEVER,
        "calendarsExcluded" to NEVER,
        "marginDays" to NEVER,
        "softDeadlineDays" to NEVER,
        "softMinPerDay" to NEVER,
        "blockedApps" to { old, new -> !new.blockedApps.containsAll(old.blockedApps) },
        "blockedSites" to { old, new -> !new.blockedSites.containsAll(old.blockedSites) },
        "checkedBrowsers" to { old, new -> !new.checkedBrowsers.containsAll(old.checkedBrowsers) },
        "blockedBrowsers" to { old, new -> !new.blockedBrowsers.containsAll(old.blockedBrowsers) },
        "quietHours" to { old, new -> adds(old.quietHours, new.quietHours) },
        "weekdayBlockFromMin" to { old, new -> new.weekdayBlockFromMin > old.weekdayBlockFromMin },
        "weekendBlockFromMin" to { old, new -> new.weekendBlockFromMin > old.weekendBlockFromMin },
        "workMinPerFreeMin" to { old, new -> new.workMinPerFreeMin < old.workMinPerFreeMin },
        "teamsAutoSync" to NEVER,
        "teamsFirstUnlockMin" to NEVER,
        "teamsSyncEveryMin" to NEVER,
        "armed" to { old, new -> old.armed && !new.armed },
        "loosenDelayHours" to { old, new -> new.loosenDelayHours < old.loosenDelayHours },
        "aiEnabled" to NEVER,
        "aiKeyActive" to NEVER,
        "aiMonthlyCapUsd" to NEVER,
        "briefingWeekdayMin" to NEVER,
        "briefingWeekendMin" to NEVER,
        "checkInMin" to NEVER,
    )

    private val LABELS = mapOf(
        "ankiTextbook" to "Current German textbook",
        "yearGroupStart" to "The September you went into Year 12",
        "profileLines" to "What you'd have Claude know",
        "classMap" to "Your classes",
        "felixName" to "Felix's name",
        "felixLine" to "Who Felix is",
        "felixGraceMin" to "Felix asks this far into a slot (minutes)",
        "felixDefaultCheckMin" to "Felix checks again after (minutes)",
        "hintsSeen" to "Hints put away",
        "ankiDeadlineMin" to "Anki deadline",
        "weekdayHours" to "School-day working hours",
        "weekendHours" to "Weekend working hours",
        "studyPeriodName" to "The timetable's name for a free period",
        "breakWindow" to "Morning break",
        "lunchWindows" to "Lunch",
        "minSchoolSlotMin" to "Shortest school time used (minutes)",
        "boxMin" to "Box length",
        "slotBreakMin" to "Break between pieces (minutes)",
        "slotLongBreakMin" to "Longer break (minutes)",
        "slotLongBreakAfterMin" to "Longer break after (minutes of work)",
        "outsideBreaks" to "Breaks outside on a free day",
        "outsideBreakMin" to "Each break outside (minutes)",
        "outsideTimes" to "Breaks outside at or after",
        "photoFromMin" to "Pieces this long earn with a photo (minutes)",
        "freeAllowanceMin" to "A day with nothing to do: free time (minutes)",
        "calendarWriteId" to "Calendar written to",
        "calendarsExcluded" to "Calendars not read",
        "marginDays" to "Safety margin (days)",
        "softDeadlineDays" to "Days given to undated work",
        "softMinPerDay" to "Undated work per day",
        "blockedApps" to "Blocked apps",
        "blockedSites" to "Blocked sites",
        "checkedBrowsers" to "Browsers whose sites are checked",
        "blockedBrowsers" to "Browsers blocked outright",
        "quietHours" to "Quiet hours",
        "weekdayBlockFromMin" to "School-day blocking starts",
        "weekendBlockFromMin" to "Other days' blocking starts",
        "workMinPerFreeMin" to "Minutes of work per free minute",
        "teamsAutoSync" to "Automatic Teams syncs",
        "teamsFirstUnlockMin" to "First-unlock Teams sync after",
        "teamsSyncEveryMin" to "Teams sync every (minutes)",
        "aiKeyActive" to "Claude's API key in use",
        "armed" to "Protection",
        "loosenDelayHours" to "Delay on loosening changes (hours)",
        "aiEnabled" to "Claude",
        "aiMonthlyCapUsd" to "Claude's monthly cap ($)",
        "briefingWeekdayMin" to "Morning briefing, school days",
        "briefingWeekendMin" to "Morning briefing, weekends",
        "checkInMin" to "Sunday check-in",
    )

    /** The settings that are lists of what's blocked or checked. */
    private val LISTS = setOf("blockedApps", "blockedSites", "checkedBrowsers", "blockedBrowsers")

    /** [new] has a minute of the day that [old] hasn't. */
    private fun adds(old: Window, new: Window): Boolean = (0 until 24 * 60).any { it in new && it !in old }

    /** Every setting with a rule: a test checks none is missing. */
    val fields: Set<String> get() = LOOSER.keys

    fun loosens(field: String, old: Settings, new: Settings): Boolean = LOOSER[field]?.invoke(old, new) ?: true

    data class Outcome(val settings: Settings, val pending: List<PendingChange>)

    /** The settings as asked for: [current] with every [pending] change applied. What the Settings screen shows. */
    fun requested(current: Settings, pending: List<PendingChange>): Settings = pending.fold(current, ::apply)

    /**
     * [latest] with what [draft] changed from [base] (the settings as a screen showed them when the
     * edit began) laid over it, field by field: a change made elsewhere meanwhile to a field the
     * edit didn't touch stands, rather than an old copy putting it back (BUG-P2-014).
     */
    fun edited(base: Settings, draft: Settings, latest: Settings): Settings {
        val before = encode(base)
        val after = encode(draft)
        return decode(JsonObject(encode(latest) + after.filter { (field, value) -> before[field] != value }))
    }

    /**
     * [change] to [fields] made at once, armed or not, with no wait: read from what's been asked for,
     * as a change of yours is, and those fields laid over [current]. A change waiting for one of them
     * is replaced by it, even one asking for the same value, which now applies; the others keep their
     * wait. For the weekly review's bounded changes, which you said needn't wait (11 Oct, IMP-P2-015).
     */
    fun atOnce(current: Settings, pending: List<PendingChange>, fields: Set<String>, change: (Settings) -> Settings): Outcome {
        val wanted = encode(change(requested(current, pending)))
        return Outcome(decode(JsonObject(encode(current) + wanted.filterKeys { it in fields })), pending.filterNot { it.field in fields })
    }

    /**
     * Applies what may apply now of the move to [proposed], and returns the rest as pending
     * changes, added to [pending]. [proposed] is read against what's been asked for ([requested]):
     * a field left as asked keeps its pending change and its wait; one set back to its current
     * value cancels it; any other change replaces it.
     */
    fun propose(current: Settings, proposed: Settings, pending: List<PendingChange>, now: Long, newId: () -> String): Outcome {
        val old = encode(current)
        val asked = encode(requested(current, pending))
        val new = encode(proposed)
        var applied = old
        val waiting = pending.toMutableList()
        for ((field, value) in new) {
            if (asked[field] == value) continue
            waiting.removeAll { it.field == field }
            if (old[field] == value) continue
            // A list (what's blocked): what's added applies at once, and only what's taken off
            // waits, as one change to the whole list. Taking off what was already waiting to go
            // keeps its wait, so adding something meanwhile doesn't start it again.
            if (current.armed && field in LISTS) {
                val was = (old[field] as JsonArray).toList()
                val will = (value as JsonArray).toList()
                val removed = was.filterNot { it in will }
                if (removed.isNotEmpty()) {
                    val added = will.filterNot { it in was }
                    val withAdded = JsonArray(was + added)
                    if (added.isNotEmpty()) applied = JsonObject(applied + (field to withAdded))
                    val previous = pending.firstOrNull { it.field == field }
                    val sameRemovals = previous != null && was.filterNot { it in (previous.value as JsonArray) } == removed
                    val description = describe(field, decode(JsonObject(old + (field to withAdded))), decode(JsonObject(old + (field to value))))
                    waiting += if (previous != null && sameRemovals) {
                        previous.copy(value = value, description = description)
                    } else {
                        PendingChange(
                            id = newId(),
                            field = field,
                            value = value,
                            description = description,
                            requestedAt = now,
                            applyAt = now + current.loosenDelayHours * 3_600_000L,
                        )
                    }
                    continue
                }
            }
            val single = decode(JsonObject(old + (field to value)))
            if (current.armed && loosens(field, current, single)) {
                waiting += PendingChange(
                    id = newId(),
                    field = field,
                    value = value,
                    description = describe(field, current, single),
                    requestedAt = now,
                    applyAt = now + current.loosenDelayHours * 3_600_000L,
                )
            } else {
                applied = JsonObject(applied + (field to value))
            }
        }
        return Outcome(decode(applied), waiting)
    }

    /**
     * Counts [elapsedMs] of uptime towards every pending change, moving its shown time to match,
     * and applies those that have waited long enough. Uptime, not the wall clock: setting the date
     * forward a day mustn't skip the wait (and the time the phone is off doesn't count). One that no
     * longer loosens blocking ([stillWaits]) applies now, whatever its wait.
     */
    fun applyDue(settings: Settings, pending: List<PendingChange>, now: Long, elapsedMs: Long): Outcome {
        val counted = pending.map { change ->
            val waited = change.waitedMs + elapsedMs.coerceAtLeast(0)
            change.copy(waitedMs = waited, applyAt = now + (change.waitMs - waited).coerceAtLeast(0))
        }
        val (due, waiting) = counted.partition { it.waitedMs >= it.waitMs || !stillWaits(settings, it) }
        return Outcome(due.fold(settings, ::apply), waiting)
    }

    /**
     * Whether [change] still loosens blocking from [settings]: one asked for before a setting stopped
     * waiting (the work's settings, from 2.0.0), or since made no looser (what it takes off a list
     * added back), needn't wait any more.
     */
    fun stillWaits(settings: Settings, change: PendingChange): Boolean = loosens(change.field, settings, apply(settings, change))

    /** How often the uptime is counted (and saved) while nothing falls due. */
    const val COUNT_EVERY_MS = 5 * 60_000L

    /**
     * How much uptime to count towards [pending] now, from [mark] to [uptime]; null when it can
     * wait (little time, nothing due). A gap that can't be measured (a restart) counts nothing but
     * is never left waiting: the count starts again from now, so it can't freeze. Where restarts
     * can't be told apart, what's sure to have passed is counted, never more. Given the [settings],
     * a change that no longer loosens them is due too.
     */
    fun counting(pending: List<PendingChange>, mark: Uptime?, uptime: Uptime?, force: Boolean, settings: Settings? = null): Long? {
        val since = uptime?.atLeastSince(mark)
        val elapsed = since ?: 0L
        val due = pending.any { it.waitedMs + elapsed >= it.waitMs || (settings != null && !stillWaits(settings, it)) }
        if (!force && !due && since != null && since < COUNT_EVERY_MS) return null
        return elapsed
    }

    fun apply(settings: Settings, change: PendingChange): Settings = decode(JsonObject(encode(settings) + (change.field to change.value)))

    fun describe(field: String, old: Settings, new: Settings): String {
        val label = LABELS[field] ?: field
        val before = encode(old)[field]
        val after = encode(new)[field]
        return when (field) {
            "blockedApps", "blockedSites", "checkedBrowsers", "blockedBrowsers" -> {
                val was = (before as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') }.orEmpty()
                val now = (after as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') }.orEmpty()
                listOfNotNull(
                    (was - now.toSet()).takeIf { it.isNotEmpty() }?.let { "remove ${it.joinToString()}" },
                    (now - was.toSet()).takeIf { it.isNotEmpty() }?.let { "add ${it.joinToString()}" },
                ).joinToString("; ").let { "$label: $it" }
            }
            "quietHours", "weekdayHours", "weekendHours" -> "$label: ${window(field, old)} → ${window(field, new)}"
            "ankiDeadlineMin", "weekdayBlockFromMin", "weekendBlockFromMin", "teamsFirstUnlockMin", "briefingWeekdayMin", "briefingWeekendMin", "checkInMin" -> "$label: ${time(before)} → ${time(after)}"
            "armed" -> if (new.armed) "Arm protection" else "Disarm protection"
            else -> "$label: $before → $after"
        }
    }

    private fun window(field: String, settings: Settings): String {
        val w: Window = when (field) {
            "quietHours" -> settings.quietHours
            "weekdayHours" -> settings.weekdayHours
            else -> settings.weekendHours
        }
        return "${clock(w.startMin)}–${clock(w.endMin)}"
    }

    private fun time(value: JsonElement?): String = value?.toString()?.toIntOrNull()?.let(::clock) ?: value.toString()

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    private fun encode(settings: Settings): JsonObject = JsonStore.json.encodeToJsonElement(Settings.serializer(), settings).jsonObject

    private fun decode(json: JsonObject): Settings = JsonStore.json.decodeFromJsonElement(Settings.serializer(), json)
}
