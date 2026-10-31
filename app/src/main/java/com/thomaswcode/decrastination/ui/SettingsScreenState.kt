package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.thomaswcode.decrastination.core.Blocklist
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.protect.SettingsChanges
import kotlinx.serialization.json.JsonElement

/**
 * What the Settings screen holds (docs/plan_v4/architecture.md §The rules; C-Q11): your edits ([draft]) over what the
 * screen showed as you began ([base]), each field as typed ([raw], so one that doesn't parse keeps its
 * words and its error however far it scrolls), and which don't parse ([invalid]); the page open
 * ([section], UI-18), the apps list's search ([find], IMP-P2-009) and what the last save said
 * ([message]). And what's done with them: a change made elsewhere laid under your edits ([shown]), the
 * lists' edits, and the save ([save]). A plain class, tested without Compose: the screen keeps one
 * through its being made again, a turn of the phone ([SAVER]; BUG-P2-013, BUG-P2-027), and draws from it.
 */
class SettingsScreenState(asked: Settings) {
    /** An app the phone can launch, by its name and package. */
    data class App(val label: String, val packageName: String)

    /** What the screen showed as you began: what's been asked for, waiting changes included. */
    var base: Settings by mutableStateOf(asked)
        private set

    /** Your edits, over [base]. */
    var draft: Settings by mutableStateOf(asked)

    /** Each field's words as typed, by key. */
    var raw: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** The fields whose words don't parse, by key: nothing's saved while there's one. */
    var invalid: Set<String> by mutableStateOf(emptySet())
        private set

    /** The page open, or the list of them (null). */
    var section: String? by mutableStateOf(null)

    /** The apps list's search. */
    var find: String by mutableStateOf("")

    /** What the last save said. */
    var message: String? by mutableStateOf(null)
        private set

    /** Whether there's a save to make: something changed, and every field parses. */
    val canSave: Boolean get() = invalid.isEmpty() && draft != base

    /**
     * What's asked for now ([asked]: the settings with what's waiting applied). A change made elsewhere
     * meanwhile (Setup putting a key to use) shows at once, your edits laid over it; saved, only what
     * you changed goes, over what's asked for then (BUG-P2-014).
     */
    fun shown(asked: Settings) {
        if (asked != base) {
            draft = SettingsChanges.edited(base, draft, asked)
            base = asked
        }
    }

    /** [key]'s words as typed. */
    fun typed(key: String, text: String) {
        raw = raw + (key to text)
    }

    /** Whether [key]'s words parse. */
    fun valid(key: String, ok: Boolean) {
        invalid = if (ok) invalid - key else invalid + key
    }

    /** What you'd have Claude know (AI-05), a line each, as typed. */
    fun profileTyped(text: String) {
        raw = raw + (PROFILE to text)
        draft = draft.copy(profileLines = text.lines().map { it.trim().take(SettingsLimits.MAX_PROFILE_LINE) }.filter { it.isNotEmpty() }.take(SettingsLimits.MAX_PROFILE_LINES))
    }

    /** The blocked sites, one a line, as typed. */
    fun sitesTyped(text: String) {
        raw = raw + (SITES to text)
        draft = draft.copy(blockedSites = text.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct())
    }

    /**
     * Each class code the sources give in [tasks], those named already, and the [timetable]'s classes
     * with no task yet but its study period (G8), so each Power Planner class can be given the subject that ties
     * it to its Teams code: the ones to say the subject of.
     */
    fun classCodes(tasks: List<TaskItem>, timetable: Timetable? = null): List<String> =
        (draft.classMap.keys + tasks.mapNotNull { it.className?.trim()?.takeIf(String::isNotEmpty) } +
            timetable?.classes.orEmpty().map { it.name.trim() }.filter { it.isNotEmpty() && !it.equals(draft.studyPeriodName.trim(), ignoreCase = true) })
            .distinct().sorted().take(SettingsLimits.MAX_CLASSES)

    /** The class [code] is [subject]: blank leaves it the code. */
    fun subject(code: String, subject: String) {
        draft = draft.copy(classMap = if (subject.isBlank()) draft.classMap - code else draft.classMap + (code to subject.take(SettingsLimits.MAX_NAME)))
    }

    /** [browser] blocked outright, or not. */
    fun browserBlocked(browser: String, on: Boolean) {
        draft = draft.copy(
            blockedBrowsers = if (on) (draft.blockedBrowsers + browser).distinct() else draft.blockedBrowsers - browser,
            // Not blocked outright, one whose address bar can be read is watched for the sites again.
            checkedBrowsers = when {
                on -> draft.checkedBrowsers - browser
                browser in Blocklist.CHECKED_BROWSERS -> (draft.checkedBrowsers + browser).distinct()
                else -> draft.checkedBrowsers
            },
        )
    }

    /** [packageName] blocked, or not. */
    fun appBlocked(packageName: String, on: Boolean) {
        draft = draft.copy(blockedApps = if (on) (draft.blockedApps + packageName).distinct() else draft.blockedApps - packageName)
    }

    /**
     * The apps' rows, of those [installed] (but the app itself, [own]) and those [asked] or your edits
     * block: browsers have their own switches; known ones not installed keep a row once switched off,
     * so they can be switched back. Blocked ones first (as the screen opened or was last saved, so a
     * row doesn't jump as it's switched), then the rest by name; the search finds one by name or package.
     */
    fun appRows(installed: List<App>, asked: Settings, own: String): List<App> {
        val shownBrowsers = browsers(draft).toSet()
        val listed = (Blocklist.APPS + asked.blockedApps + draft.blockedApps).distinct()
            .filter { pkg -> installed.none { it.packageName == pkg } && pkg !in shownBrowsers }
            .map { App("${Blocklist.NAMES[it] ?: it} (not installed)", it) }
        val all = (listed + installed.filter { it.packageName != own && it.packageName !in shownBrowsers })
            .sortedWith(compareBy({ it.packageName !in base.blockedApps }, { it.label.lowercase() }))
        val query = find.trim().lowercase()
        return all.filter { query.isEmpty() || query in it.label.lowercase() || query in it.packageName }
    }

    /**
     * Your edits saved through [protection]: only what you changed, over what's asked for then
     * (BUG-P2-014), so once armed a change that loosens blocking waits its hours. Then [saved] (the
     * reminders' alarms set at their new times), what happened said, and the screen begun again from
     * what's asked for now.
     */
    suspend fun save(protection: Protection, change: suspend (Map<String, JsonElement>) -> Unit, saved: () -> Unit) {
        change(SettingsChanges.changed(base, draft))
        saved()
        val waiting = protection.pending
        message = if (waiting.isEmpty()) {
            "Saved."
        } else {
            "Saved. ${waiting.size} change${if (waiting.size == 1) " loosening blocking waits" else "s loosening blocking wait"} ${protection.loosenDelayHours} hours, shown below."
        }
        val now = protection.asked
        base = now
        draft = now
        raw = emptyMap()
    }

    companion object {
        const val SITES = "blockedSites"
        const val PROFILE = "profileLines"

        /** Every browser the blocker knows, and any the settings name. */
        fun browsers(settings: Settings): List<String> =
            (Blocklist.CHECKED_BROWSERS + Blocklist.BLOCKED_BROWSERS + settings.checkedBrowsers + settings.blockedBrowsers).distinct()

        fun browserName(pkg: String): String = Blocklist.NAMES[pkg] ?: pkg

        private fun encode(settings: Settings): String = JsonStore.json.encodeToString(Settings.serializer(), settings)

        private fun decode(text: String): Settings = JsonStore.json.decodeFromString(Settings.serializer(), text)

        /**
         * The screen's state through its being made again: the settings as their JSON, each field's words
         * as one list (key, words, key, words…), the fields that don't parse, the page and the search.
         * What the last save said isn't kept.
         */
        val SAVER: Saver<SettingsScreenState, Any> = listSaver(
            save = { state ->
                listOf(
                    encode(state.base),
                    encode(state.draft),
                    ArrayList(state.raw.flatMap { (key, text) -> listOf(key, text) }),
                    ArrayList(state.invalid),
                    state.section.orEmpty(),
                    state.find,
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                SettingsScreenState(decode(saved[0] as String)).apply {
                    draft = decode(saved[1] as String)
                    raw = (saved[2] as List<String>).chunked(2).associate { (key, text) -> key to text }
                    invalid = (saved[3] as List<String>).toSet()
                    section = (saved[4] as String).ifEmpty { null }
                    find = saved[5] as String
                }
            },
        )
    }
}
