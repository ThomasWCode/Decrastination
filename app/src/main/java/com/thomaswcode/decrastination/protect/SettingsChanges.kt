package com.thomaswcode.decrastination.protect

import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import kotlinx.serialization.Serializable
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
        "ankiTextbook" to { _, _ -> false },
        "ankiDeadlineMin" to { old, new -> new.ankiDeadlineMin > old.ankiDeadlineMin },
        // Any minute to work in that wasn't there means less can land on today and tomorrow: a
        // window moved later adds time tonight even at the same length.
        "weekdayHours" to { old, new -> adds(old.weekdayHours, new.weekdayHours) },
        "weekendHours" to { old, new -> adds(old.weekendHours, new.weekendHours) },
        "boxMin" to { old, new -> new.boxMin > old.boxMin },
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
        "teamsAutoSync" to { _, _ -> false },
        "teamsFirstUnlockMin" to { _, _ -> false },
        "teamsSyncEveryMin" to { _, _ -> false },
        "armed" to { old, new -> old.armed && !new.armed },
        "loosenDelayHours" to { old, new -> new.loosenDelayHours < old.loosenDelayHours },
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
        "armed" to "Protection",
        "loosenDelayHours" to "Delay on loosening changes (hours)",
    )

    /** [new] has a minute of the day that [old] hasn't. */
    private fun adds(old: Window, new: Window): Boolean = (0 until 24 * 60).any { it in new && it !in old }

    /** Every setting with a rule: a test checks none is missing. */
    val fields: Set<String> get() = LOOSER.keys

    fun loosens(field: String, old: Settings, new: Settings): Boolean = LOOSER[field]?.invoke(old, new) ?: true

    data class Outcome(val settings: Settings, val pending: List<PendingChange>)

    /**
     * Applies what may apply now of the move from [current] to [proposed], and returns the rest as
     * pending changes, added to [pending]. A new change to a field replaces any pending one for it.
     */
    fun propose(current: Settings, proposed: Settings, pending: List<PendingChange>, now: Long, newId: () -> String): Outcome {
        val old = encode(current)
        val new = encode(proposed)
        var applied = old
        val waiting = pending.toMutableList()
        for ((field, value) in new) {
            if (old[field] == value) continue
            waiting.removeAll { it.field == field }
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
     * is never left waiting: the count starts again from now, so it can't freeze.
     */
    fun counting(pending: List<PendingChange>, mark: Uptime?, uptime: Uptime?, force: Boolean): Long? {
        val since = uptime?.since(mark)
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
            "ankiDeadlineMin", "weekdayBlockFromMin", "teamsFirstUnlockMin" -> "$label: ${time(before)} → ${time(after)}"
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
