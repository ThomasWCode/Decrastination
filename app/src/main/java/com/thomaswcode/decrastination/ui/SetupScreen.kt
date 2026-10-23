package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Backups
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.protect.ProtectionActivity
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.core.GmailWrites
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A part of the setup checklist: each but This phone is a page of Settings too (UI-18). */
enum class SetupPart { Saved, Sources, Claude, Phone, Backup }

/**
 * The setup checklist: what each part of the app needs, and whether it has it.
 * On its own page ([SetupActivity]), from the attention mark and on the first run (UI-18); [parts]
 * of it on Settings' pages, where the page scrolls ([scroll] false).
 */
@Composable
fun SetupScreen(graph: AppGraph, activity: Activity, parts: Set<SetupPart> = SetupPart.entries.toSet(), scroll: Boolean = true) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val secrets by graph.secrets.present.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val protection by graph.stores.protection.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Credential?>(null) }
    var enteringKey by remember { mutableStateOf(false) }
    // Given here: what needs it reads now, from the answer itself, which comes back even to this
    // screen made afresh meanwhile (when the change below wouldn't see a change).
    var hadAnki by remember { mutableStateOf(AnkiProvider.hasPermission(activity)) }
    var hadCalendar by remember { mutableStateOf(CalendarTime.allowed(activity)) }
    val requestAnki = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) {
            hadAnki = true
            SyncWorker.syncNow(activity, setOf(Source.Anki))
        }
    }
    val ankiAllowed = remember(refresh) { AnkiProvider.hasPermission(activity) }
    // Read and written: busy time, and the plan's slots written back (NF-07).
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        refresh++
        if (granted[CalendarTime.PERMISSION] == true) {
            hadCalendar = true
            scope.launch { graph.sync.refreshCalendar() }
        }
    }
    val calendarAllowed = remember(refresh) { CalendarTime.allowed(activity) }
    // Looked at again on coming back, so one given in Android's settings shows at once (BUG-P2-023).
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Given there: what needs it reads once this screen sees it.
    LaunchedEffect(ankiAllowed, calendarAllowed) {
        if (ankiAllowed && !hadAnki) SyncWorker.syncNow(activity, setOf(Source.Anki))
        if (calendarAllowed && !hadCalendar) graph.sync.refreshCalendar()
        hadAnki = ankiAllowed
        hadCalendar = calendarAllowed
    }
    var backupNote by remember { mutableStateOf<String?>(null) }
    var restoreNote by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupNote = runCatching {
                // The chat's archive read whole for it: off the main thread.
                withContext(Dispatchers.IO) {
                    val text = graph.backups.exportBackup()
                    // One Import couldn't read back isn't saved as a backup.
                    check(!graph.backups.tooBig(text)) { "it's more than Import reads (8 MB), so it couldn't be restored" }
                    val out = requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")) { "no file to write" }
                    out.use { it.write(text.toByteArray()) }
                }
                "Saved: the settings, the log, what the app has learned, your calendar answers and your instructions in use. No passwords or keys."
            }.getOrElse { "Couldn't save it: ${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            restoreNote = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    val input = requireNotNull(activity.contentResolver.openInputStream(uri)) { "no file to read" }
                    input.use { Backups.read(it) }
                }
                if (bytes == null) "That file is too big to be a backup." else graph.backups.importBackup(bytes.decodeToString())
            }.getOrElse { "Couldn't read it: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(bottom = 24.dp)) {
        // A saved file that couldn't be read whole at a start (IMP-P1-003), till you've seen it.
        if (SetupPart.Saved in parts && runtime.unreadable.isNotEmpty()) {
            Group("Saved data") {
                runtime.unreadable.forEach { found ->
                    SetupItem(
                        title = "Found unreadable " + Format.at(found.at, graph.clock.now(), graph.clock.zone()),
                        done = false,
                        detail = found.says(graph.stores::holds),
                        action = "Got it" to { scope.launch { graph.forgetUnreadable(found) } },
                    )
                }
            }
        }
        if (SetupPart.Sources in parts) Group("Sources") {
            SetupItem(
                title = "Teams Assignments widget",
                done = state.status(Source.Teams).works,
                detail = sourceDetail(state.status(Source.Teams), "Needs the widget, 0.3.1 or later, signed with the same key."),
            )
            SetupItem(
                title = "Power Planner login",
                done = Secret.PowerPlannerPassword in secrets && state.status(Source.PowerPlanner).works,
                detail = sourceDetail(state.status(Source.PowerPlanner), if (Secret.PowerPlannerPassword in secrets) "Saved." else "Not saved."),
                action = "Enter" to { editing = Credential.PowerPlanner },
            )
            SetupItem(
                title = "AnkiDroid access",
                done = ankiAllowed && state.status(Source.Anki).works,
                detail = if (ankiAllowed) sourceDetail(state.status(Source.Anki), "Allowed.") else "AnkiDroid asks you to allow it once.",
                action = if (ankiAllowed) null else "Allow" to { requestAnki.launch(AnkiProvider.PERMISSION) },
            )
            SetupItem(
                title = "Gmail app password",
                done = Secret.GmailAppPassword in secrets && state.status(Source.Gmail).works,
                // The star is the decision (DX-03): said where the account is set up.
                detail = sourceDetail(state.status(Source.Gmail), if (Secret.GmailAppPassword in secrets) "Saved." else "Not saved. Nothing is ever sent, deleted or marked read.") +
                    " The app reads your starred emails only, each with its whole conversation. Star an email in Gmail to make it a task; unstar it when it's done, or finish its work here" +
                    (if (graph.settings.value.gmailFinishes) ", which unstars and archives it in Gmail (Settings → Sources)." else ".") +
                    GmailWrites.givenUp(graph.gmailFinishes.value).joinToString("") { " " + GmailWrites.failure(it) + " Its sheet's It's sorted clears this." },
                action = "Enter" to { editing = Credential.Gmail },
            )
        }
        if (SetupPart.Claude in parts) Group("Claude") {
            val usage = model.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
            SetupItem(
                title = "Claude",
                done = settings.aiEnabled && settings.aiKeyActive && Secret.AnthropicApiKey in secrets && usage.lastError == null && usage.keyProblem == null,
                detail = when {
                    Secret.AnthropicApiKey !in secrets -> "No API key: the rules do what they can, and nothing is sent to Claude."
                    !settings.aiKeyActive -> "Key saved, not in use yet: enter it again to use it."
                    !settings.aiEnabled -> "Key saved; switched off in Settings, so nothing is sent."
                    // The key or its account can't be used: said with what puts it right.
                    usage.keyProblem != null -> "${usage.keyProblem.says}. ${usage.keyProblem.fix}: the rules stand in, and it's tried again an hour after each failed try."
                    // Its calls failing (a bad key, no connection): said, so it can be put right.
                    usage.lastError != null -> "On, but its last call failed (${usage.lastError.take(80)}): the rules stand in, and it's tried again after an hour."
                    else -> "On: $%.2f this month".format(Locale.UK, usage.spentUsd) + (settings.aiMonthlyCapUsd?.let { " of $$it" } ?: ", no cap: it stops when the account's credit runs out") + "."
                },
                action = "Enter key" to { enteringKey = true },
            )
        }
        if (SetupPart.Phone in parts) Group("This phone") {
            SetupItem(
                title = "Calendar",
                done = calendarAllowed,
                detail = if (calendarAllowed) "Its events come off your free time: lessons take their slot, trains don't, and you're asked about all-day and long ones." else "Read only, so lessons and plans come off your free time.",
                action = if (calendarAllowed) null else "Allow" to { requestCalendar.launch(arrayOf(CalendarTime.PERMISSION, android.Manifest.permission.WRITE_CALENDAR)) },
            )
            // The calendar the plan is written to (NF-07), picked here as Setup's step.
            if (calendarAllowed) CalendarSection(graph, activity, reading = false)
            SetupItem(
                title = "Blocking and protection",
                done = protection.protection.problems.isEmpty() && protection.protection.checkedAt != null,
                detail = protection.protection.problems.firstOrNull() ?: if (settings.armed) "Armed." else "Blocking works; protection isn't armed.",
                action = "Open" to { activity.startActivity(Intent(activity, ProtectionActivity::class.java)) },
            )
        }
        if (SetupPart.Backup in parts) Group("Backup") {
            SetupItem(
                title = "Back up",
                done = null,
                detail = backupNote ?: "The settings, the log, what the app has learned, your calendar answers and your instructions in use, to a file you choose. No passwords or keys.",
                action = "Export" to { exporter.launch("decrastination-backup.json") },
            )
            SetupItem(
                title = "Restore",
                done = null,
                detail = restoreNote ?: "From a backup. Once protection is armed, a setting that loosens blocking waits, as one changed in Settings does.",
                action = "Import" to { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
        }
    }

    if (enteringKey) {
        KeyDialog(onDismiss = { enteringKey = false }) { key ->
            enteringKey = false
            scope.launch {
                graph.secrets.put(Secret.AnthropicApiKey, key)
                // The last key's failure isn't this one's: it's tried at once, not after the rest, and
                // its alert goes.
                graph.gateway.newKey()
                ModelAlerts.keyFixed(activity)
                // Put to use at once, armed or not: Claude works on the plan, not the blocker (D-46).
                graph.protection.changeSettings { it.copy(aiKeyActive = true) }
                // A new key for one already in use: straight to work.
                if (graph.gateway.claudeKey() != null) EnrichWorker.enqueue(activity)
            }
        }
    }

    editing?.let { credential ->
        CredentialDialog(credential, onDismiss = { editing = null }) { values ->
            editing = null
            scope.launch {
                graph.secrets.put(values + credential.resets.associateWith { null })
                SyncWorker.syncNow(activity, setOf(credential.source))
            }
        }
    }
}

private val SourceStatus.works: Boolean get() = lastSuccessAt != null && error == null

private fun sourceDetail(status: SourceStatus, otherwise: String): String =
    status.error?.let { "Last read failed: $it" } ?: if (status.lastSuccessAt != null) "Reading it works." else otherwise

private enum class Credential(val source: Source, val title: String, val user: Secret, val userLabel: String, val password: Secret, val passwordLabel: String, val resets: List<Secret>) {
    PowerPlanner(Source.PowerPlanner, "Power Planner login", Secret.PowerPlannerUsername, "Username", Secret.PowerPlannerPassword, "Password", listOf(Secret.PowerPlannerSession, Secret.PowerPlannerAccountId)),
    Gmail(Source.Gmail, "Gmail", Secret.GmailAddress, "Address", Secret.GmailAppPassword, "App password", emptyList()),
}

@Composable
private fun CredentialDialog(credential: Credential, onDismiss: () -> Unit, onSave: (Map<Secret, String>) -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(credential.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text(credential.userLabel) }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(credential.passwordLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("Stored encrypted on this phone, and never shown again.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = user.isNotBlank() && password.isNotBlank(), onClick = {
                onSave(mapOf(credential.user to user.trim(), credential.password to password))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Claude API key: one field, stored encrypted and never shown again. */
@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claude API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("Key (sk-ant-…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("Stored encrypted on this phone. Claude is used only once it's switched on in Settings, and stops when the account's credit runs out, or at the monthly cap if you set one (Settings).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onSave(key.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One checklist row, a tile of a [Group]. [done] null means it isn't a thing to tick. */
@Composable
fun SetupItem(title: String, done: Boolean?, detail: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val (mark, container, content) = when (done) {
            true -> Triple("✓", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            false -> Triple("!", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            null -> Triple("•", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Text(mark, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (done == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
    }
}
