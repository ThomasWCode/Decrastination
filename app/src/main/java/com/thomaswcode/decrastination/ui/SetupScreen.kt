package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.protect.ProtectionActivity
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sync.SyncWorker
import java.util.Locale
import kotlinx.coroutines.launch

/** The setup checklist (PLAN.md Phase 1): what each part of the app needs, and whether it has it. */
@Composable
fun SetupScreen(graph: AppGraph, activity: Activity) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val secrets by graph.secrets.present.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Credential?>(null) }
    var enteringKey by remember { mutableStateOf(false) }
    val requestAnki = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) SyncWorker.syncNow(activity, setOf(Source.Anki))
    }
    val ankiAllowed = remember(refresh) { AnkiProvider.hasPermission(activity) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
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
            detail = sourceDetail(state.status(Source.Gmail), if (Secret.GmailAppPassword in secrets) "Saved." else "Not saved. Read-only: nothing is ever sent or changed."),
            action = "Enter" to { editing = Credential.Gmail },
        )
        val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(graph.clock.now(), graph.clock.zone()))
        SetupItem(
            title = "Claude",
            done = settings.aiEnabled && Secret.AnthropicApiKey in secrets,
            detail = when {
                Secret.AnthropicApiKey !in secrets -> "No API key: the rules do what they can, and nothing is sent to Claude."
                !settings.aiEnabled -> "Key saved; switched off in Settings, so nothing is sent."
                else -> "On: £%.2f of £%d this month.".format(Locale.UK, usage.spentGbp(settings.usdToGbp), settings.aiMonthlyCapGbp)
            },
            action = "Enter key" to { enteringKey = true },
        )
        SetupItem(
            title = "Settings",
            done = null,
            detail = "Hours, planning, Anki, what's blocked, and Claude.",
            action = "Open" to { activity.startActivity(Intent(activity, SettingsActivity::class.java)) },
        )
        SetupItem(
            title = "Blocking and protection",
            done = runtime.protection.problems.isEmpty() && runtime.protection.checkedAt != null,
            detail = runtime.protection.problems.firstOrNull() ?: if (settings.armed) "Armed." else "Blocking works; protection isn't armed.",
            action = "Open" to { activity.startActivity(Intent(activity, ProtectionActivity::class.java)) },
        )
    }

    if (enteringKey) {
        KeyDialog(onDismiss = { enteringKey = false }) { key ->
            enteringKey = false
            scope.launch {
                graph.secrets.put(Secret.AnthropicApiKey, key)
                if (graph.settings.value.aiEnabled) EnrichWorker.enqueue(activity)
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
                Text("Stored encrypted on this phone. Claude is used only once it's switched on in Settings, and never past the monthly cap.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onSave(key.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One checklist row. [done] null means it isn't a thing to tick. */
@Composable
fun SetupItem(title: String, done: Boolean?, detail: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (done) {
                true -> "✓"
                false -> "✗"
                null -> "•"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (done == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
    }
    HorizontalDivider()
}
