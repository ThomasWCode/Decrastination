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

/** A change that loosens blocking, waiting its 24 hours (docs/scheduler.md §6, layer 4). */
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

    /** For each setting, whether a new value loosens blocking compared with the old. */
    private val LOOSER: Map<String, (Settings, Settings) -> Boolean> = mapOf(
        // Another textbook's decks: the old one's deck tasks can go, and with them their pressure.
        "ankiTextbook" to { old, new -> new.ankiTextbook != old.ankiTextbook },
        "ankiDeadlineMin" to { old, new -> new.ankiDeadlineMin > old.ankiDeadlineMin },
        // Any change to when you work can move work off today and tomorrow: more time spreads it
        // out, and less can carry a piece that won't fit (an Anki deck's day of cards) past them.
        "weekdayHours" to { old, new -> new.weekdayHours != old.weekdayHours },
        "weekendHours" to { old, new -> new.weekendHours != old.weekendHours },
        // Bigger pieces or smaller, either can move work off today and tomorrow (smaller ones fit
        // into later days' gaps), so either waits.
        "boxMin" to { old, new -> new.boxMin != old.boxMin },
        "marginDays" to { old, new -> new.marginDays < old.marginDays },
        "softDeadlineDays" to { old, new -> new.softDeadlineDays > old.softDeadlineDays },
        // Less a day can push overdue undated work out of today and tomorrow, lifting the pressure.
        "softMinPerDay" to { old, new -> new.softMinPerDay < old.softMinPerDay },
        "blockedApps" to { old, new -> !new.blockedApps.containsAll(old.blockedApps) },
        "blockedSites" to { old, new -> !new.blockedSites.containsAll(old.blockedSites) },
        "checkedBrowsers" to { old, new -> !new.checkedBrowsers.containsAll(old.checkedBrowsers) },
        "blockedBrowsers" to { old, new -> !new.blockedBrowsers.containsAll(old.blockedBrowsers) },
        "quietHours" to { old, new -> adds(old.quietHours, new.quietHours) },
        "weekdayBlockFromMin" to { old, new -> new.weekdayBlockFromMin > old.weekdayBlockFromMin },
        "workMinPerFreeMin" to { old, new -> new.workMinPerFreeMin < old.workMinPerFreeMin },
        // The automatic syncs are how new Teams work arrives: fewer or later ones can hide it.
        "teamsAutoSync" to { old, new -> old.teamsAutoSync && !new.teamsAutoSync },
        "teamsFirstUnlockMin" to { old, new -> new.teamsFirstUnlockMin > old.teamsFirstUnlockMin },
        "teamsSyncEveryMin" to { old, new -> new.teamsSyncEveryMin > old.teamsSyncEveryMin },
        "armed" to { old, new -> old.armed && !new.armed },
        "loosenDelayHours" to { old, new -> new.loosenDelayHours < old.loosenDelayHours },
        // The model's triage can put an email off or call it an event, lifting pressure: switching
        // it on, or letting it spend more, waits.
        "aiEnabled" to { old, new -> !old.aiEnabled && new.aiEnabled },
        "aiKeyActive" to { old, new -> !old.aiKeyActive && new.aiKeyActive },
        "aiMonthlyCapGbp" to { old, new -> new.aiMonthlyCapGbp > old.aiMonthlyCapGbp },
        "usdToGbp" to { old, new -> new.usdToGbp < old.usdToGbp },
        // Reminders' times: nothing is blocked by them.
        "briefingWeekdayMin" to { _, _ -> false },
        "briefingWeekendMin" to { _, _ -> false },
        "checkInMin" to { _, _ -> false },
    )

    private val LABELS = mapOf(
        "ankiTextbook" to "Current German textbook",
        "ankiDeadlineMin" to "Anki deadline",
        "weekdayHours" to "School-day working hours",
        "weekendHours" to "Weekend working hours",
        "boxMin" to "Box length",
        "marginDays" to "Safety margin (days)",
        "softDeadlineDays" to "Days given to undated work",
        "softMinPerDay" to "Undated work per day",
        "blockedApps" to "Blocked apps",
        "blockedSites" to "Blocked sites",
        "checkedBrowsers" to "Browsers whose sites are checked",
        "blockedBrowsers" to "Browsers blocked outright",
        "quietHours" to "Quiet hours",
        "weekdayBlockFromMin" to "School-day blocking starts",
        "workMinPerFreeMin" to "Minutes of work per free minute",
        "teamsAutoSync" to "Automatic Teams syncs",
        "teamsFirstUnlockMin" to "First-unlock Teams sync after",
        "teamsSyncEveryMin" to "Teams sync every (minutes)",
        "aiKeyActive" to "Claude's API key in use",
        "armed" to "Protection",
        "loosenDelayHours" to "Delay on loosening changes (hours)",
        "aiEnabled" to "Claude",
        "aiMonthlyCapGbp" to "Claude's monthly cap (£)",
        "usdToGbp" to "Pounds per dollar",
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
     * forward a day mustn't skip the wait (and the time the phone is off doesn't count).
     */
    fun applyDue(settings: Settings, pending: List<PendingChange>, now: Long, elapsedMs: Long): Outcome {
        val counted = pending.map { change ->
            val waited = change.waitedMs + elapsedMs.coerceAtLeast(0)
            change.copy(waitedMs = waited, applyAt = now + (change.waitMs - waited).coerceAtLeast(0))
        }
        val (due, waiting) = counted.partition { it.waitedMs >= it.waitMs }
        return Outcome(due.fold(settings, ::apply), waiting)
    }

    /** How often the uptime is counted (and saved) while nothing falls due. */
    const val COUNT_EVERY_MS = 5 * 60_000L

    /**
     * How much uptime to count towards [pending] now, from [mark] to [uptime]; null when it can
     * wait (little time, nothing due). A gap that can't be measured (a restart) counts nothing but
     * is never left waiting: the count starts again from now, so it can't freeze. Where restarts
     * can't be told apart, what's sure to have passed is counted, never more.
     */
    fun counting(pending: List<PendingChange>, mark: Uptime?, uptime: Uptime?, force: Boolean): Long? {
        val since = uptime?.atLeastSince(mark)
        val elapsed = since ?: 0L
        val due = pending.any { it.waitedMs + elapsed >= it.waitMs }
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
            "ankiDeadlineMin", "weekdayBlockFromMin", "teamsFirstUnlockMin", "briefingWeekdayMin", "briefingWeekendMin", "checkInMin" -> "$label: ${time(before)} → ${time(after)}"
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
