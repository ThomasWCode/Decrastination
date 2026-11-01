package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.data.BackupFolder
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.Crashes
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.learn.AnkiProgress
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.DailyAlarms
import com.thomaswcode.decrastination.learn.SummaryEmail
import com.thomaswcode.decrastination.protect.ProtectionPanel
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.sync.protocol.ChangeSettings
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything you can change: hours, Teams, planning, Anki, what's blocked, and
 * Claude. Saved through [com.thomaswcode.decrastination.protect.Protection.changeSettings], so once protection is armed a change that
 * loosens blocking waits its 24 hours and shows here until it applies.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Editor(graph) } }
    }

    @Composable
    private fun Editor(graph: AppGraph) {
        val saved by graph.settings.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        val model by graph.stores.model.state.collectAsStateWithLifecycle()
        val protection by graph.stores.protection.state.collectAsStateWithLifecycle()
        val learning by graph.stores.learning.state.collectAsStateWithLifecycle()
        // What's been asked for, waiting changes included: setting one back cancels it.
        val asked = SettingsChanges.requested(saved, protection.pending)
        // Your edits over what the screen showed as you began, each field as typed, which don't parse,
        // the page open and the apps' search ([SettingsScreenState]): kept through the screen being
        // made again, a turn of the phone (BUG-P2-013, BUG-P2-027).
        val screen = rememberSaveable(saver = SettingsScreenState.SAVER) { SettingsScreenState(asked) }
        var draft by screen::draft
        val raw by screen::raw
        val message by screen::message
        var apps by remember { mutableStateOf(emptyList<SettingsScreenState.App>()) }
        var find by screen::find
        val scope = rememberCoroutineScope()
        // Back comes to the list, your edits kept till they're saved.
        var section by screen::section
        BackHandler(enabled = section != null) { section = null }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps() } }
        // A change made elsewhere meanwhile shows at once, your edits laid over it ([SettingsScreenState.shown]).
        LaunchedEffect(asked) { screen.shown(asked) }
        val form = Form(raw, typed = screen::typed, valid = screen::valid)
        // What a field does, under it, as the draft has it (UI-13).
        val schoolCalendar by graph.school.state.collectAsStateWithLifecycle()
        val tasksState by graph.tasks.state.collectAsStateWithLifecycle()
        val today = Instant.ofEpochMilli(graph.clock.now()).atZone(graph.clock.zone()).toLocalDate()
        val effect: @Composable (String) -> Unit = { field -> SettingsEffects.line(field, draft, tasksState.timetable, schoolCalendar, today)?.let { Note(it) } }

        Scaffold(
            topBar = { BackBar(PAGES.firstOrNull { it.key == section }?.title ?: "Settings", this, onBack = if (section != null) ({ section = null }) else null) },
            floatingActionButton = { FelixFab(this) },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    Button(
                        enabled = screen.canSave,
                        onClick = {
                            // The reminders' alarms, at their new times.
                            scope.launch { screen.save(graph.protection, { graph.act(ChangeSettings(it)) }) { DailyAlarms.schedule(this@SettingsActivity) } }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save") }
                }
            },
        ) { padding ->
            // Room under the last row for Felix's button.
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = FELIX_ROOM)) {
                when (section) {
                    // Its pages (UI-18), and what's waiting its hours, whichever page it's on.
                    null -> {
                        itemsIndexed(PAGES, key = { _, it -> it.key }) { index, page ->
                            ListTile(index, PAGES.size, onClick = { section = page.key }) {
                                Text(page.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(page.says, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (protection.pending.isNotEmpty()) {
                            item { Section("Waiting (protection is armed)") }
                            items(protection.pending, key = { it.id }) { change ->
                                Note(
                                    "${change.description}: applies about ${Format.at(change.applyAt, graph.clock.now(), graph.clock.zone())}, and is shown on its page as if it had. " +
                                        "Set it back and save to cancel it; a parent code under Blocking and protection applies it now.",
                                )
                            }
                        }
                    }

                    // Who you are, as every call to Claude says it (AI-05).
                    ABOUT -> item {
                        NumberField("The September you went into Year 12", draft.yearGroupStart, SettingsLimits.YEAR_GROUP_START, "yearGroupStart", form) { draft = draft.copy(yearGroupStart = it) }
                        NumberField("Current German textbook", draft.ankiTextbook, SettingsLimits.TEXTBOOK, "ankiTextbook", form) { draft = draft.copy(ankiTextbook = it) }
                        OutlinedTextField(
                            value = raw[SettingsScreenState.PROFILE] ?: draft.profileLines.joinToString("\n"),
                            onValueChange = screen::profileTyped,
                            label = { Text("What you'd have Claude know, a line each") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            minLines = 3,
                        )
                        // Each class code the sources give, and the subject it is, or the Power Planner class it is (its lessons
                        // then go with it, Lessons.groups): blank leaves it the code.
                        Note("Each class: its subject, or the Power Planner class it is, so its lessons go with it.")
                        for (code in screen.classCodes(graph.tasks.value.tasks, graph.tasks.value.timetable)) {
                            OutlinedTextField(
                                value = draft.classMap[code].orEmpty(),
                                onValueChange = { subject -> screen.subject(code, subject) },
                                label = { Text("$code is") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            )
                        }
                        Note("Sent to Claude with every call, with your school days and what's remembered.")
                    }

                    HOURS -> item {
                        WindowField("School days: work", draft.weekdayHours, "weekdayHours", form) { draft = draft.copy(weekdayHours = it) }
                        effect("weekdayHours")
                        WindowField("Weekends: work", draft.weekendHours, "weekendHours", form) { draft = draft.copy(weekendHours = it) }
                        effect("weekendHours")
                        WindowField("Quiet hours (nothing blocked)", draft.quietHours, "quietHours", form, overnight = true) { draft = draft.copy(quietHours = it) }
                        TimeField("School days: blocking from", draft.weekdayBlockFromMin, "weekdayBlockFromMin", form) { draft = draft.copy(weekdayBlockFromMin = it) }
                        TimeField("Weekends and days off: blocking from", draft.weekendBlockFromMin, "weekendBlockFromMin", form) { draft = draft.copy(weekendBlockFromMin = it) }
                    }

                    // Days with no school and half-days (PL-16): saved as they're added or deleted.
                    SCHOOL -> item { SchoolSection(graph) }

                    PLANNING -> item {
                        NumberField("Work cut into pieces of (minutes)", draft.boxMin, SettingsLimits.BOX_MIN, "boxMin", form) { draft = draft.copy(boxMin = it) }
                        effect("boxMin")
                        NumberField("Finish this many days before a deadline", draft.marginDays, SettingsLimits.MARGIN_DAYS, "marginDays", form) { draft = draft.copy(marginDays = it) }
                        effect("marginDays")
                        NumberField("Days given to undated work", draft.softDeadlineDays, SettingsLimits.SOFT_DEADLINE_DAYS, "softDeadlineDays", form) { draft = draft.copy(softDeadlineDays = it) }
                        NumberField("Undated work a day, at most (minutes)", draft.softMinPerDay, SettingsLimits.SOFT_MIN_PER_DAY, "softMinPerDay", form) { draft = draft.copy(softMinPerDay = it) }
                        effect("softMinPerDay")
                        NumberField("Minutes of work for one of free time", draft.workMinPerFreeMin, SettingsLimits.WORK_MIN_PER_FREE_MIN, "workMinPerFreeMin", form) { draft = draft.copy(workMinPerFreeMin = it) }
                        effect("workMinPerFreeMin")
                        NumberField("Pieces this long earn with a photo (minutes)", draft.photoFromMin, SettingsLimits.PHOTO_FROM_MIN, "photoFromMin", form) { draft = draft.copy(photoFromMin = it) }
                        effect("photoFromMin")
                        NumberField("A day with nothing to do: free minutes", draft.freeAllowanceMin, SettingsLimits.FREE_ALLOWANCE_MIN, "freeAllowanceMin", form) { draft = draft.copy(freeAllowanceMin = it) }
                        effect("freeAllowanceMin")
                        NumberField("Outside breaks on a free day", draft.outsideBreaks, SettingsLimits.OUTSIDE_BREAKS, "outsideBreaks", form) { draft = draft.copy(outsideBreaks = it) }
                        NumberField("Each outside break (minutes)", draft.outsideBreakMin, SettingsLimits.OUTSIDE_BREAK_MIN, "outsideBreakMin", form) { draft = draft.copy(outsideBreakMin = it) }
                        effect("outsideBreaks")
                    }

                    // What the Protection page has (arming, what's in place, the parent code), and what's blocked.
                    BLOCKING -> {
                        item { ProtectionPanel(graph, this@SettingsActivity) }
                        item { Section("Blocked sites") }
                        item {
                            OutlinedTextField(
                                value = raw[SettingsScreenState.SITES] ?: draft.blockedSites.joinToString("\n"),
                                onValueChange = screen::sitesTyped,
                                label = { Text("One per line: a site, or a site and path") },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                minLines = 4,
                            )
                            Note("Covered in ${draft.checkedBrowsers.joinToString { SettingsScreenState.browserName(it) }}, whose address bar is read.")
                        }

                        item { Section("Browsers") }
                        item {
                            for (browser in SettingsScreenState.browsers(draft)) {
                                val blocked = browser in draft.blockedBrowsers
                                SwitchRow("${SettingsScreenState.browserName(browser)}: blocked outright", blocked) { on -> screen.browserBlocked(browser, on) }
                            }
                            Note("A browser not blocked outright has its address bar read for the blocked sites, if it's one that can be (Chrome, Brave).")
                        }

                        item { Section("Blocked apps") }
                        // Browsers have their own switches above; blocked ones first, the search finding one by name (IMP-P2-009).
                        val rows = screen.appRows(apps, asked, packageName)
                        item {
                            OutlinedTextField(
                                value = find,
                                onValueChange = { find = it },
                                label = { Text("Find an app") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(rows, key = { it.packageName }) { app ->
                            SwitchRow(app.label, app.packageName in draft.blockedApps) { on -> screen.appBlocked(app.packageName, on) }
                        }
                    }

                    // The voice of the app's messages (AI-22, LR-03); what he remembers, and how he's to
                    // behave (AI-21), saved as each is added or forgotten.
                    FELIX -> {
                        item {
                            TextField("His name", draft.felixName, SettingsLimits.MAX_NAME, "felixName", form) { draft = draft.copy(felixName = it) }
                            TextField("Who he is", draft.felixLine, SettingsLimits.MAX_FELIX_LINE, "felixLine", form) { draft = draft.copy(felixLine = it) }
                            NumberField("He asks this far into a slot with nothing done (minutes)", draft.felixGraceMin, SettingsLimits.FELIX_GRACE_MIN, "felixGraceMin", form) { draft = draft.copy(felixGraceMin = it) }
                            NumberField("He checks again after, if he doesn't say (minutes)", draft.felixDefaultCheckMin, SettingsLimits.FELIX_CHECK_MIN, "felixDefaultCheckMin", form) { draft = draft.copy(felixDefaultCheckMin = it) }
                            SwitchRow("He searches the web when a message needs it (about 5¢ a search)", draft.felixSearches) { draft = draft.copy(felixSearches = it) }
                        }
                        item { MemorySection(graph) }
                    }

                    // Its key, as Setup has it; on or off, its cap, and how hard it thinks.
                    CLAUDE -> {
                        item { SetupScreen(graph, this@SettingsActivity, setOf(SetupPart.Claude), scroll = false) }
                        item {
                            val usage = model.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
                            SwitchRow("Use Claude for steps, estimates and email triage", draft.aiEnabled) { draft = draft.copy(aiEnabled = it) }
                            Note(
                                "Off, nothing is sent to Claude; the rules do what they can. On, it needs its API key, above; with no cap, it stops when the account's credit runs out. " +
                                    "This month: $%.2f in %d calls.".format(Locale.UK, usage.spentUsd, usage.calls),
                            )
                            OptionalNumberField("Monthly cap ($, blank for none)", draft.aiMonthlyCapUsd, SettingsLimits.CAP_USD, "aiMonthlyCapUsd", form) { draft = draft.copy(aiMonthlyCapUsd = it) }
                        }
                        item {
                            Section("How hard it thinks")
                            ModelEffort.table().forEach { (effort, jobs) -> Note("$effort: $jobs") }
                            Note(SetupPage.EFFORT_NOTE)
                        }
                        item { RecentCalls(model.calls, graph.clock.zone()) }
                        item { LastCrash(this@SettingsActivity) }
                    }

                    // Each source as Setup has it, and how Teams and Anki are read.
                    SOURCES -> {
                        item { SetupScreen(graph, this@SettingsActivity, setOf(SetupPart.Sources), scroll = false) }
                        item { Section("Gmail") }
                        item {
                            // The one write outside the phone (D-7 as amended): yours to turn off.
                            SwitchRow("When you finish a starred email's work here, unstar and archive it in Gmail", draft.gmailFinishes) { draft = draft.copy(gmailFinishes = it) }
                        }
                        item { Section("Teams") }
                        item {
                            SwitchRow("Sync Teams automatically", draft.teamsAutoSync) { draft = draft.copy(teamsAutoSync = it) }
                            TimeField("First unlock after", draft.teamsFirstUnlockMin, "teamsFirstUnlockMin", form) { draft = draft.copy(teamsFirstUnlockMin = it) }
                            NumberField("Then every (minutes)", draft.teamsSyncEveryMin, SettingsLimits.TEAMS_EVERY_MIN, "teamsSyncEveryMin", form) { draft = draft.copy(teamsSyncEveryMin = it) }
                        }
                        item { Section("Anki") }
                        item {
                            TimeField("Daily cards due by", draft.ankiDeadlineMin, "ankiDeadlineMin", form) { draft = draft.copy(ankiDeadlineMin = it) }
                            // What a card takes in each deck, as learned from time in AnkiDroid (DX-11).
                            AnkiProgress.lines(learning.calibration, graph.tasks.value.tasks).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }

                    // The calendar written to and those read (NF-07, DX-13; UI-11): saved as they're picked.
                    CALENDAR -> item { CalendarSection(graph, this@SettingsActivity) }

                    NOTIFICATIONS -> item {
                        TimeField("Morning briefing, school days", draft.briefingWeekdayMin, "briefingWeekdayMin", form) { draft = draft.copy(briefingWeekdayMin = it) }
                        TimeField("Morning briefing, weekends and days off", draft.briefingWeekendMin, "briefingWeekendMin", form) { draft = draft.copy(briefingWeekendMin = it) }
                        TimeField("Sunday check-in", draft.checkInMin, "checkInMin", form) { draft = draft.copy(checkInMin = it) }
                        Note("The week's review follows the check-in an hour and a half later, answered or not. Those held back by quiet hours or the day's four go with the next briefing.")
                        // The weekly summary for home (NF-02): where it goes, and this week's to send now.
                        OptionalTextField("Weekly summary goes to (an email address, or none)", draft.summaryEmail, SUMMARY_EMAIL_MAX, "summaryEmail", form) { draft = draft.copy(summaryEmail = it) }
                        val latest = graph.log.value.reviews.filter { it.summary != null }.maxByOrNull { it.at }
                        val summary = latest?.summary
                        val week = latest?.week
                        if (summary != null && week != null) {
                            val context = LocalContext.current
                            TextButton(onClick = { runCatching { context.startActivity(SummaryEmail.email(draft.summaryEmail, week, summary)) } }) { Text("Send this week's summary") }
                        } else {
                            Note("The summary is written after each Sunday's review, while Claude is on.")
                        }
                    }

                    // This phone's pairing, as Setup has it.
                    WEBSITE -> item { SetupScreen(graph, this@SettingsActivity, setOf(SetupPart.Website), scroll = false) }

                    // A backup, and what couldn't be read at a start, as Setup has them.
                    BACKUP -> {
                        item { SetupScreen(graph, this@SettingsActivity, setOf(SetupPart.Saved, SetupPart.Backup), scroll = false) }
                        item { NightlyBackup(graph, runtime.lastBackupAt, graph.clock.now()) }
                    }
                    RESET -> item { ResetPage(graph) }
                }
            }
        }
    }

    private fun launchableApps(): List<SettingsScreenState.App> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { SettingsScreenState.App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

}

@Composable
private fun Section(title: String) = SectionHeading(title)

/** A page of Settings (UI-18): its key, its name, and what's on it. */
private data class Page(val key: String, val title: String, val says: String)

private const val ABOUT = "about"
private const val HOURS = "hours"
private const val SCHOOL = "school"
private const val PLANNING = "planning"
private const val BLOCKING = "blocking"
private const val FELIX = "felix"
private const val CLAUDE = "claude"
private const val SOURCES = "sources"
private const val CALENDAR = "calendar"
private const val NOTIFICATIONS = "notifications"
private const val WEBSITE = "website"
private const val BACKUP = "backup"
private const val RESET = "reset"

/** The most of an email address the summary goes to (NF-02). */
private const val SUMMARY_EMAIL_MAX = 120

/** Its pages, every control of the old Setup, Settings and Protection pages on one of them (UI-18). */
private val PAGES = listOf(
    Page(ABOUT, "About you", "Your year, your subjects, and what Claude should know"),
    Page(HOURS, "Hours and windows", "When you work, quiet hours, and when blocking starts"),
    Page(SCHOOL, "School", "Days with no school, and half-days"),
    Page(PLANNING, "Planning", "Pieces, margins, undated work and free time"),
    Page(BLOCKING, "Blocking and protection", "What's blocked, arming, and the parent code"),
    Page(FELIX, "Felix", "His name, when he asks, and what he remembers"),
    Page(CLAUDE, "Claude", "Its key, its cap, how hard it thinks, and this month's cost"),
    Page(SOURCES, "Sources", "Teams, Power Planner, AnkiDroid and Gmail"),
    Page(CALENDAR, "Calendar", "The calendars read, and the one your plan's written to"),
    Page(NOTIFICATIONS, "Notifications", "The morning briefing and Sunday's check-in"),
    Page(WEBSITE, "The website", "This phone paired with it, and when it last sent"),
    Page(BACKUP, "Backup", "The settings, the log and what's learned, saved or restored"),
    Page(RESET, "Reset", "Stats, what's learned, what Felix remembers, the chat, your changes"),
)

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

/**
 * The form's fields: each one's words as typed, by key ([raw], kept with the draft rather than in
 * the field, which a list drops once it's scrolled away), and where to say what's typed and
 * whether it parses.
 */
private class Form(val raw: Map<String, String>, val typed: (String, String) -> Unit, val valid: (String, Boolean) -> Unit)

/** A text field for a value of [T], showing an error until what's typed parses. */
@Composable
private fun <T> ParsedField(label: String, shown: String, key: String, form: Form, parse: (String) -> T?, onChange: (T) -> Unit, number: Boolean) {
    val text = form.raw[key] ?: shown
    val ok = parse(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            form.typed(key, value)
            val parsed = parse(value)
            form.valid(key, parsed != null)
            if (parsed != null) onChange(parsed)
        },
        label = { Text(label) },
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Some words, not blank and no longer than [max]. */
@Composable
private fun TextField(label: String, value: String, max: Int, key: String, form: Form, onChange: (String) -> Unit) =
    ParsedField(label, value, key, form, { it.trim().takeIf { s -> s.isNotEmpty() && s.length <= max } }, onChange, number = false)

/** Text up to [max] characters, or blank for none. */
@Composable
private fun OptionalTextField(label: String, value: String, max: Int, key: String, form: Form, onChange: (String) -> Unit) =
    ParsedField(label, value, key, form, { it.trim().takeIf { s -> s.length <= max } }, onChange, number = false)

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, key: String, form: Form, onChange: (Int) -> Unit) =
    ParsedField(label, value.toString(), key, form, { it.trim().toIntOrNull()?.takeIf { n -> n in range } }, onChange, number = true)

/** A whole number in [range], or blank for none. */
@Composable
private fun OptionalNumberField(label: String, value: Int?, range: IntRange, key: String, form: Form, onChange: (Int?) -> Unit) =
    ParsedField(
        label,
        value?.toString().orEmpty(),
        key,
        form,
        { text -> text.trim().let { if (it.isEmpty()) Optional.empty() else it.toIntOrNull()?.takeIf { n -> n in range }?.let { n -> Optional.of(n) } } },
        { onChange(it.orElse(null)) },
        number = true,
    )

/** A value shown as it is, picked in a dialog ([onPick]): tapping the field or its button opens it (UI-01). */
@Composable
private fun PickedField(label: String, shown: String, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onPick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) onPick() }
    OutlinedTextField(
        value = shown,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = { IconButton(onClick = onPick) { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Pick $label") } },
        interactionSource = source,
        modifier = modifier,
    )
}

/** A time of day (UI-01), shown as HH:MM and picked on a clock. [form] isn't needed: a picked time is always one. */
@Composable
private fun TimeField(label: String, minuteOfDay: Int, key: String, @Suppress("UNUSED_PARAMETER") form: Form, onChange: (Int) -> Unit) {
    var picking by rememberSaveable(key) { mutableStateOf(false) }
    PickedField(label, SettingsChanges.clock(minuteOfDay)) { picking = true }
    if (picking) TimeDialog(label, minuteOfDay, onClose = { picking = false }) { onChange(it); picking = false }
}

/**
 * A stretch of the day (UI-01): its start and its end, each picked on a clock, and how long it is.
 * Working hours end the day they start (the planner plans each day's own); quiet hours ([overnight])
 * may run past midnight. A pick that wouldn't make one says why, and changes nothing.
 */
@Composable
private fun WindowField(label: String, window: Window, key: String, @Suppress("UNUSED_PARAMETER") form: Form, overnight: Boolean = false, onChange: (Window) -> Unit) {
    var picking by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    var problem by rememberSaveable(key) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickedField("From", SettingsChanges.clock(window.startMin), Modifier.weight(1f)) { picking = true }
            PickedField("To", SettingsChanges.clock(window.endMin), Modifier.weight(1f)) { picking = false }
        }
        Text(
            problem ?: Format.minutes(window.minutes),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    picking?.let { start ->
        TimeDialog(if (start) "$label: from" else "$label: to", if (start) window.startMin else window.endMin, onClose = { picking = null }) { picked ->
            val next = if (start) window.copy(startMin = picked) else window.copy(endMin = picked)
            if (SettingsLimits.window(next, overnight)) {
                onChange(next)
                problem = null
            } else {
                problem = if (overnight) "It can't start and end at the same time" else "It has to end after it starts, the same day"
            }
            picking = null
        }
    }
}

/** "16:45" or "1645" as minutes after midnight. */
internal fun parseTime(text: String): Int? {
    val digits = text.trim().replace(":", "").replace(".", "")
    if (digits.length !in 3..4 || digits.any { !it.isDigit() }) return null
    val hours = digits.dropLast(2).toInt()
    val minutes = digits.takeLast(2).toInt()
    return if (hours in 0..23 && minutes in 0..59) hours * 60 + minutes else null
}

/** The model's recent calls (AC-07), newest first: a line each, a tap opening all it used, with Copy. */
@Composable
private fun RecentCalls(calls: List<CallRecord>, zone: ZoneId) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Long?>(null) }
    Column {
        Section("Recent calls")
        if (calls.isEmpty()) Note("None yet.")
        calls.asReversed().forEach { call ->
            val opened = open == call.at
            Column(Modifier.fillMaxWidth().clickable { open = if (opened) null else call.at }.padding(vertical = 4.dp)) {
                Text(
                    SetupPage.callLine(call, zone),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (opened) {
                    val details = call.details(zone)
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("A Claude call", details))
                    }) { Text("Copy") }
                }
            }
        }
    }
}

/** The app's last crash (AR-06), as it was written down, with Copy; or that there's been none. */
@Composable
private fun LastCrash(activity: Activity) {
    val crash = remember { Crashes.last(activity.filesDir) }
    var open by remember { mutableStateOf(false) }
    Column {
        Section("Last crash")
        if (crash == null) {
            Note("None.")
        } else {
            Text(
                if (open) crash else crash.lineSequence().take(3).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
            )
            TextButton(onClick = {
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("The last crash", crash))
            }) { Text("Copy") }
        }
    }
}

/**
 * What can be reset (LR-08), a row each: asked first, saying what goes (your changes listed), a copy
 * of everything saved before, and no parent code.
 */
@Composable
private fun ResetPage(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Reset?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    Column {
        Note("A copy of everything the app keeps is saved to Documents/Decrastination before each, to import from Backup.")
        Reset.entries.forEach { r ->
            // The page's margin, as its note has (start only: the button's own padding is its end's).
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge)
                    Text(r.takes.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { asking = r }) { Text("Reset") }
            }
        }
        said?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
    asking?.let { r ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(BackupPage.resetTitle(r)) },
            text = { Text(BackupPage.resetAsks(r, graph.instructions.value.applied)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    scope.launch { said = graph.backups.reset(r) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep it") } },
        )
    }
}

/**
 * The nightly backup (AR-07): where it goes, and when it last did, and how many of the chat's earliest
 * messages it leaves out to fit (D-96). And a reset's copies kept in the app's own files (LR-08), where
 * Documents couldn't be written: the file picker can't reach them, so each is restored from here.
 */
@Composable
private fun NightlyBackup(graph: AppGraph, last: Long?, now: Long) {
    val scope = rememberCoroutineScope()
    val copies = remember { graph.backups.resetCopies() }
    var said by remember { mutableStateOf<String?>(null) }
    // How many of the chat's earliest messages it leaves out, whether it's more than Import reads even so, and what Import would refuse in it.
    val fit by produceState(Triple(0, false, null as String?)) {
        value = withContext(Dispatchers.IO) {
            runCatching { graph.backups.encoded().let { (text, leftOut) -> Triple(leftOut, graph.backups.tooBig(text), graph.backups.unrestorable(text)) } }.getOrDefault(Triple(0, false, null))
        }
    }
    val (leftOut, tooBig, refused) = fit
    Column {
        Section("Nightly backup")
        if (!BackupFolder.available) {
            Note("The nightly backup needs Android 10 or later.")
        } else {
            Note("Every night, all the app keeps goes to Documents/Decrastination on the phone, the last two weeks of them kept. Import opens one through the file picker. A copy off the phone is yours to make: a sync app mirroring the folder, or Export.")
            Note("Last backup: " + (last?.let { Format.ago(it, now) } ?: "not yet"))
            BackupPage.fit(leftOut, tooBig, refused)?.let { Note(it) }
        }
        if (copies.isNotEmpty()) {
            Section("Copies saved before a reset")
            Note("Kept in the app's own files, as Documents couldn't be written then; the file picker can't reach them, so they're restored from here.")
            copies.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { said = graph.backups.restoreResetCopy(name) } }) { Text("Restore") }
                }
            }
            said?.let { Note(it) }
        }
    }
}

