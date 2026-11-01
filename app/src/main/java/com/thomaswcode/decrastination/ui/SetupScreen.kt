package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
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
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.learn.CalendarReader
import com.thomaswcode.decrastination.protect.ProtectionActivity
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sync.ReplicaWorker
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.core.GmailWrites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A part of the setup checklist: each but This phone is a page of Settings too (UI-18). */
enum class SetupPart { Saved, Sources, Claude, Website, Phone, Backup }

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
    val device by graph.stores.device.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Credential?>(null) }
    var enteringKey by remember { mutableStateOf(false) }
    // Given here: what needs it reads now, from the answer itself, which comes back even to this
    // screen made afresh meanwhile (when the change below wouldn't see a change).
    var hadAnki by remember { mutableStateOf(AnkiProvider.hasPermission(activity)) }
    var hadCalendar by remember { mutableStateOf(CalendarReader.allowed(activity)) }
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
        if (granted[CalendarReader.PERMISSION] == true) {
            hadCalendar = true
            scope.launch { graph.sync.refreshCalendar() }
        }
    }
    val calendarAllowed = remember(refresh) { CalendarReader.allowed(activity) }
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
                val refused = withContext(Dispatchers.IO) {
                    val text = graph.backups.exportBackup()
                    // One too big for Import to read isn't saved as a backup: the empty file the picker made for it goes.
                    if (graph.backups.tooBig(text)) {
                        runCatching { DocumentsContract.deleteDocument(activity.contentResolver, uri) }
                        error("it's more than Import reads (8 MB), so it couldn't be restored")
                    }
                    val out = requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")) { "no file to write" }
                    out.use { it.write(text.toByteArray()) }
                    // One Import would refuse as it stands is saved all the same, so what the app keeps can still leave the
                    // phone (a build that reads it could restore it), and said.
                    graph.backups.unrestorable(text)
                }
                "Saved: the settings, the log, what the app has learned, your calendar answers and your instructions in use. No passwords or keys." +
                    (refused?.let { " Import would refuse it as it stands ($it)." } ?: "")
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
                if (bytes == null) BackupPage.TOO_BIG else graph.backups.importBackup(bytes.decodeToString())
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
            SetupItem(SetupPage.teams(state.status(Source.Teams)))
            SetupItem(SetupPage.powerPlanner(state.status(Source.PowerPlanner), Secret.PowerPlannerPassword in secrets), action = "Enter" to { editing = Credential.PowerPlanner })
            SetupItem(SetupPage.anki(state.status(Source.Anki), ankiAllowed), action = if (ankiAllowed) null else "Allow" to { requestAnki.launch(AnkiProvider.PERMISSION) })
            SetupItem(
                SetupPage.gmail(state.status(Source.Gmail), Secret.GmailAppPassword in secrets, graph.settings.value.gmailFinishes, GmailWrites.givenUp(graph.gmailFinishes.value)),
                action = "Enter" to { editing = Credential.Gmail },
            )
        }
        if (SetupPart.Claude in parts) Group("Claude") {
            val usage = model.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
            SetupItem(SetupPage.claude(Secret.AnthropicApiKey in secrets, settings, usage), action = "Enter key" to { enteringKey = true })
        }
        if (SetupPart.Website in parts) Group("The website") { WebsiteItem(graph) }
        if (SetupPart.Phone in parts) Group("This phone") {
            SetupItem(
                title = "Calendar",
                done = calendarAllowed,
                detail = if (calendarAllowed) "Its events come off your free time: lessons take their slot, trains don't, and you're asked about all-day and long ones." else "Read only, so lessons and plans come off your free time.",
                action = if (calendarAllowed) null else "Allow" to { requestCalendar.launch(arrayOf(CalendarReader.PERMISSION, android.Manifest.permission.WRITE_CALENDAR)) },
            )
            // The calendar the plan is written to (NF-07), picked here as Setup's step.
            if (calendarAllowed) CalendarSection(graph, activity, reading = false)
            SetupItem(
                title = "Blocking and protection",
                done = device.watchdog.problems.isEmpty() && device.watchdog.checkedAt != null,
                detail = device.watchdog.problems.firstOrNull() ?: if (settings.armed) "Armed." else "Blocking works; protection isn't armed.",
                action = "Open" to { activity.startActivity(Intent(activity, ProtectionActivity::class.java)) },
            )
        }
        if (SetupPart.Backup in parts) Group("Backup") {
            SetupItem(
                title = "Back up",
                done = null,
                detail = backupNote ?: BackupPage.BACK_UP,
                action = "Export" to { exporter.launch("decrastination-backup.json") },
            )
            SetupItem(
                title = "Restore",
                done = null,
                detail = restoreNote ?: BackupPage.RESTORE,
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
                graph.commands.changeSettings { it.copy(aiKeyActive = true) }
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

private enum class Credential(val source: Source, val title: String, val user: Secret, val userLabel: String, val password: Secret, val passwordLabel: String, val resets: List<Secret>) {
    PowerPlanner(Source.PowerPlanner, "Power Planner login", Secret.PowerPlannerUsername, "Username", Secret.PowerPlannerPassword, "Password", Secret.POWER_PLANNER_SESSION),
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

/**
 * The website (docs/spec/sync.md): this phone paired by the code its Phone page shows, then the last it heard. The server's
 * address and a test server are a debug command's.
 */
@Composable
private fun WebsiteItem(graph: AppGraph) {
    val sync by graph.replica.state.collectAsStateWithLifecycle()
    val secrets by graph.secrets.present.collectAsStateWithLifecycle()
    val paired = Secret.DeviceToken in secrets
    var pairing by remember { mutableStateOf(false) }
    val now = graph.clock.now()
    val problem = sync.lastError ?: sync.refused.takeIf { it.isNotEmpty() }?.let { "The server is behind this app, and can't take ${it.size} of what's here yet" }
    SetupItem(
        title = "This phone",
        done = if (paired) problem == null else null,
        detail = when {
            !paired -> "Not paired. Open the website's Phone page for a code. Nothing here depends on it."
            problem != null -> problem
            else -> sync.lastSyncAt?.let { "Paired. Last sent ${Format.ago(it, now)}, to server ${sync.serverVersion ?: "of unknown version"}." } ?: "Paired. Nothing sent yet."
        },
        action = if (paired) "Unpair" to { graph.scope.launch { graph.replica.forget() } } else "Pair" to { pairing = true },
    )
    if (sync.dropped.isNotEmpty()) {
        SetupItem(
            title = "Changes the server didn't apply",
            done = false,
            detail = sync.dropped.asReversed().joinToString("\n"),
            action = "Clear" to { graph.scope.launch { graph.replica.clearDropped() } },
        )
    }
    if (pairing) {
        PairDialog(onDismiss = { pairing = false }) { code, done ->
            graph.scope.launch {
                val why = graph.replica.pair(code, android.os.Build.MODEL)
                if (why == null) ReplicaWorker.now(graph.app)
                done(why)
            }
        }
    }
}

/** The website's six-digit code, and what came of it. */
@Composable
private fun PairDialog(onDismiss: () -> Unit, onPair: (String, (String?) -> Unit) -> Unit) {
    var code by remember { mutableStateOf("") }
    var why by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with the website") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(why ?: "The code on the website's Phone page. It works once, for five minutes.", style = MaterialTheme.typography.bodySmall, color = if (why != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == 6 && !busy, onClick = {
                busy = true
                onPair(code) { problem ->
                    busy = false
                    if (problem == null) onDismiss() else why = problem
                }
            }) { Text("Pair") }
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

/** One checklist row in the words both clients use. */
@Composable
private fun SetupItem(line: SetupPage.Line, action: Pair<String, () -> Unit>? = null) = SetupItem(line.title, line.done, line.detail, action)

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
