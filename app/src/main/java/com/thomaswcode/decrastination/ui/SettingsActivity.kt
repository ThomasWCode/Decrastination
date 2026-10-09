package com.thomaswcode.decrastination.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Blocklist
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.protect.SettingsChanges
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything you can change (PLAN.md Phase 4): hours, Teams, planning, Anki, what's blocked, and
 * Claude. Saved through [AppGraph.changeSettings], so once protection is armed a change that
 * loosens blocking waits its 24 hours and shows here until it applies.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Editor(graph) } }
    }

    private data class App(val label: String, val packageName: String)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Editor(graph: AppGraph) {
        val saved by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        // What's been asked for, waiting changes included: setting one back cancels it.
        val asked = SettingsChanges.requested(saved, runtime.pending)
        var draft by remember { mutableStateOf(asked) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
        var message by remember { mutableStateOf<String?>(null) }
        var apps by remember { mutableStateOf(emptyList<App>()) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps() } }
        fun valid(key: String, ok: Boolean) {
            invalid = if (ok) invalid - key else invalid + key
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Settings") }) },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    Button(
                        enabled = invalid.isEmpty() && draft != asked,
                        onClick = {
                            scope.launch {
                                val wanted = draft
                                graph.changeSettings { wanted }
                                val waiting = graph.runtime.value.pending
                                message = if (waiting.isEmpty()) {
                                    "Saved."
                                } else {
                                    "Saved. ${waiting.size} change${if (waiting.size == 1) "" else "s"} loosening blocking wait ${graph.settings.value.loosenDelayHours} hours, shown below."
                                }
                                draft = SettingsChanges.requested(graph.settings.value, waiting)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item { Section("Claude") }
                item {
                    val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
                    SwitchRow("Use Claude for steps, estimates and email triage", draft.aiEnabled) { draft = draft.copy(aiEnabled = it) }
                    Note(
                        "Off, nothing is sent to Claude; the rules do what they can. On, it needs its API key (Setup) and stays under the monthly cap. " +
                            "This month: £%.2f in %d calls.".format(Locale.UK, usage.spentGbp(draft.usdToGbp), usage.calls),
                    )
                    NumberField("Monthly cap (£)", draft.aiMonthlyCapGbp, 1..1000, "aiMonthlyCapGbp", ::valid) { draft = draft.copy(aiMonthlyCapGbp = it) }
                    DecimalField("Pounds per dollar", draft.usdToGbp, 0.3..2.0, "usdToGbp", ::valid) { draft = draft.copy(usdToGbp = it) }
                }

                item { Section("Hours") }
                item {
                    WindowField("School days: work", draft.weekdayHours, "weekdayHours", ::valid) { draft = draft.copy(weekdayHours = it) }
                    WindowField("Weekends: work", draft.weekendHours, "weekendHours", ::valid) { draft = draft.copy(weekendHours = it) }
                    WindowField("Quiet hours (nothing blocked)", draft.quietHours, "quietHours", ::valid, overnight = true) { draft = draft.copy(quietHours = it) }
                    TimeField("School days: blocking from", draft.weekdayBlockFromMin, "weekdayBlockFromMin", ::valid) { draft = draft.copy(weekdayBlockFromMin = it) }
                }

                item { Section("Teams") }
                item {
                    SwitchRow("Sync Teams automatically", draft.teamsAutoSync) { draft = draft.copy(teamsAutoSync = it) }
                    TimeField("First unlock after", draft.teamsFirstUnlockMin, "teamsFirstUnlockMin", ::valid) { draft = draft.copy(teamsFirstUnlockMin = it) }
                    NumberField("Then every (minutes)", draft.teamsSyncEveryMin, 30..24 * 60, "teamsSyncEveryMin", ::valid) { draft = draft.copy(teamsSyncEveryMin = it) }
                }

                item { Section("Planning") }
                item {
                    NumberField("Work cut into pieces of (minutes)", draft.boxMin, 10..240, "boxMin", ::valid) { draft = draft.copy(boxMin = it) }
                    NumberField("Finish this many days before a deadline", draft.marginDays, 0..7, "marginDays", ::valid) { draft = draft.copy(marginDays = it) }
                    NumberField("Days given to undated work", draft.softDeadlineDays, 1..60, "softDeadlineDays", ::valid) { draft = draft.copy(softDeadlineDays = it) }
                    NumberField("Undated work a day, at most (minutes)", draft.softMinPerDay, 10..600, "softMinPerDay", ::valid) { draft = draft.copy(softMinPerDay = it) }
                    NumberField("Minutes of work for one of free time", draft.workMinPerFreeMin, 1..20, "workMinPerFreeMin", ::valid) { draft = draft.copy(workMinPerFreeMin = it) }
                }

                item { Section("Anki") }
                item {
                    TimeField("Daily cards due by", draft.ankiDeadlineMin, "ankiDeadlineMin", ::valid) { draft = draft.copy(ankiDeadlineMin = it) }
                    NumberField("Current textbook", draft.ankiTextbook, 1..9, "ankiTextbook", ::valid) { draft = draft.copy(ankiTextbook = it) }
                }

                item { Section("Blocked sites") }
                item {
                    var text by remember(asked.blockedSites) { mutableStateOf(draft.blockedSites.joinToString("\n")) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            draft = draft.copy(blockedSites = value.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct())
                        },
                        label = { Text("One per line: a site, or a site and path") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        minLines = 4,
                    )
                    Note("Covered in ${draft.checkedBrowsers.joinToString { browserName(it) }}, whose address bar is read.")
                }

                item { Section("Browsers") }
                item {
                    for (browser in browsers(draft)) {
                        val blocked = browser in draft.blockedBrowsers
                        SwitchRow("${browserName(browser)}: blocked outright", blocked) { on ->
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
                    }
                    Note("A browser not blocked outright has its address bar read for the blocked sites, if it's one that can be (Chrome, Brave).")
                }

                item { Section("Blocked apps") }
                // Browsers have their own switches above.
                val shownBrowsers = browsers(draft).toSet()
                // Known ones not installed keep a row once switched off, so they can be switched back.
                val listed = (Blocklist.APPS + asked.blockedApps + draft.blockedApps).distinct()
                    .filter { pkg -> apps.none { it.packageName == pkg } && pkg !in shownBrowsers }
                    .map { App("${Blocklist.NAMES[it] ?: it} (not installed)", it) }
                items(listed + apps.filter { it.packageName != packageName && it.packageName !in shownBrowsers }, key = { it.packageName }) { app ->
                    SwitchRow(app.label, app.packageName in draft.blockedApps) { on ->
                        draft = draft.copy(blockedApps = if (on) (draft.blockedApps + app.packageName).distinct() else draft.blockedApps - app.packageName)
                    }
                }

                if (runtime.pending.isNotEmpty()) {
                    item { Section("Waiting (protection is armed)") }
                    items(runtime.pending, key = { it.id }) { change ->
                        Note(
                            "${change.description}: applies about ${Format.at(change.applyAt, graph.clock.now(), graph.clock.zone())}, and is shown above as if it had. " +
                                "Set it back and save to cancel it; a parent code on the protection screen applies it now.",
                        )
                    }
                }
            }
        }
    }

    private fun launchableApps(): List<App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        /** Every browser the blocker knows, and any the settings name. */
        fun browsers(settings: Settings): List<String> =
            (Blocklist.CHECKED_BROWSERS + Blocklist.BLOCKED_BROWSERS + settings.checkedBrowsers + settings.blockedBrowsers).distinct()

        fun browserName(pkg: String): String = when (pkg) {
            "com.android.chrome" -> "Chrome"
            "com.brave.browser" -> "Brave"
            "org.mozilla.firefox" -> "Firefox"
            "org.mozilla.firefox_beta" -> "Firefox Beta"
            "org.mozilla.fenix" -> "Firefox Nightly"
            "org.torproject.torbrowser" -> "Tor"
            else -> pkg
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A text field for a value of [T], showing an error until what's typed parses. */
@Composable
private fun <T> ParsedField(label: String, shown: String, key: String, valid: (String, Boolean) -> Unit, parse: (String) -> T?, onChange: (T) -> Unit, number: Boolean) {
    var text by remember(shown) { mutableStateOf(shown) }
    val ok = parse(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            val parsed = parse(value)
            valid(key, parsed != null)
            if (parsed != null) onChange(parsed)
        },
        label = { Text(label) },
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, key: String, valid: (String, Boolean) -> Unit, onChange: (Int) -> Unit) =
    ParsedField(label, value.toString(), key, valid, { it.trim().toIntOrNull()?.takeIf { n -> n in range } }, onChange, number = true)

@Composable
private fun DecimalField(label: String, value: Double, range: ClosedFloatingPointRange<Double>, key: String, valid: (String, Boolean) -> Unit, onChange: (Double) -> Unit) =
    ParsedField(label, value.toString(), key, valid, { it.trim().toDoubleOrNull()?.takeIf { n -> n in range } }, onChange, number = true)

@Composable
private fun TimeField(label: String, minuteOfDay: Int, key: String, valid: (String, Boolean) -> Unit, onChange: (Int) -> Unit) =
    ParsedField(label, SettingsChanges.clock(minuteOfDay), key, valid, ::parseTime, onChange, number = false)

@Composable
private fun WindowField(label: String, window: Window, key: String, valid: (String, Boolean) -> Unit, overnight: Boolean = false, onChange: (Window) -> Unit) =
    ParsedField(
        "$label (from–to)",
        "${SettingsChanges.clock(window.startMin)}–${SettingsChanges.clock(window.endMin)}",
        key,
        valid,
        // Working hours end the day they start (the planner plans each day's own); quiet hours
        // may run past midnight.
        { text -> text.split('–', '-').map { it.trim() }.takeIf { it.size == 2 }?.let { (a, b) -> parseTime(a)?.let { s -> parseTime(b)?.let { e -> Window(s, e).takeIf { s != e && (overnight || e > s) } } } } },
        onChange,
        number = false,
    )

/** "16:45" or "1645" as minutes after midnight. */
internal fun parseTime(text: String): Int? {
    val digits = text.trim().replace(":", "").replace(".", "")
    if (digits.length !in 3..4 || digits.any { !it.isDigit() }) return null
    val hours = digits.dropLast(2).toInt()
    val minutes = digits.takeLast(2).toInt()
    return if (hours in 0..23 && minutes in 0..59) hours * 60 + minutes else null
}
