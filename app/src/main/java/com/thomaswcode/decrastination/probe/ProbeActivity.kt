package com.thomaswcode.decrastination.probe

import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 0: proves each data path on the phone (PLAN.md §5). Every probe can also be started from
 * a PC, which is how they were run:
 * `adb shell am start -n com.thomaswcode.decrastination/.probe.ProbeCommand --es probe teams`
 * with `teams`, `anki`, `sync`, `open` or `status`; results go to `adb logcat -s Decrastination`.
 * Only intents through that alias run a probe: it needs a permission the adb shell has and no
 * ordinary app can get, whereas this activity is exported to the launcher for anyone to start.
 */
class ProbeActivity : ComponentActivity() {

    private val requestAnki = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ProbeLog.add("AnkiDroid permission ${if (granted) "granted" else "refused"}")
        if (granted) readAnki()
    }

    private val teamsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            ProbeLog.add("Teams provider says it changed: $uri")
        }
    }
    private var observingTeams = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeTeams()
        setContent { ProbeTheme { ProbeScreen() } }
        run(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        run(intent)
    }

    override fun onDestroy() {
        if (observingTeams) contentResolver.unregisterContentObserver(teamsObserver)
        super.onDestroy()
    }

    private fun run(intent: Intent?) {
        val probe = intent?.getStringExtra(EXTRA_PROBE) ?: return
        if (intent.component?.className != COMMAND_ALIAS) {
            ProbeLog.add("Ignored probe=$probe: probes only run through ${COMMAND_ALIAS.substringAfterLast('.')}")
            intent.removeExtra(EXTRA_PROBE)
            return
        }
        when (probe) {
            "teams" -> readTeams()
            "anki" -> readAnki()
            "sync" -> callTeams(TeamsProvider.METHOD_REQUEST_SYNC)
            "open" -> openFirstAssignment()
            "status" -> logStatus()
            else -> ProbeLog.add("Unknown probe $probe")
        }
        intent.removeExtra(EXTRA_PROBE)
    }

    /** Registering needs the permission and the provider, so it also shows whether both are there. */
    private fun observeTeams() {
        observingTeams = try {
            contentResolver.registerContentObserver(TeamsProvider.root, true, teamsObserver)
            ProbeLog.add("Watching the Teams provider for changes")
            true
        } catch (e: Exception) {
            ProbeLog.add("Can't watch the Teams provider: $e")
            false
        }
    }

    private fun readTeams() = lifecycleScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { TeamsProvider.state(contentResolver) to TeamsProvider.assignments(contentResolver) }
        }.onSuccess { (state, rows) ->
            ProbeLog.add("Teams state: $state")
            ProbeLog.add("Teams: ${rows.size} assignment(s)")
            rows.forEach { row ->
                val due = (row["due_at"] as? Long)?.let(::formatTime) ?: row["due_text"]
                val instructions = (row["description"] as? String)?.length ?: 0
                ProbeLog.add("  $due | ${row["class_name"]} | ${row["title"]} | ${row["tab"]} | $instructions chars | ${row["key"]}")
            }
        }.onFailure { ProbeLog.add("Teams read failed: $it") }
    }

    private fun callTeams(method: String, arg: String? = null) = lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { TeamsProvider.call(contentResolver, method, arg) } }
            .onSuccess { result -> ProbeLog.add("Teams $method(${arg ?: ""}): started=${result?.getBoolean("started")} reason=${result?.getString("reason")}") }
            .onFailure { ProbeLog.add("Teams $method failed: $it") }
    }

    private fun openFirstAssignment() = lifecycleScope.launch {
        val key = runCatching { withContext(Dispatchers.IO) { TeamsProvider.assignments(contentResolver).firstOrNull()?.get("key") as? String } }
            .onFailure { ProbeLog.add("Teams read failed: $it") }
            .getOrNull() ?: return@launch ProbeLog.add("No assignment to open")
        callTeams(TeamsProvider.METHOD_OPEN, key)
    }

    private fun readAnki() {
        if (ContextCompat.checkSelfPermission(this, AnkiProvider.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ProbeLog.add("Asking for AnkiDroid's permission")
            requestAnki.launch(AnkiProvider.PERMISSION)
            return
        }
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AnkiProvider.decks(contentResolver) } }
                .onSuccess { decks ->
                    ProbeLog.add("AnkiDroid: ${decks.size} deck(s); deck_count as [learn, review, new]")
                    decks.forEach { deck ->
                        val raw = deck["deck_count"] as? String
                        ProbeLog.add("  ${deck["deck_name"]} | $raw -> ${DeckCounts.parse(raw)} | id ${deck["deck_id"]}")
                    }
                }
                .onFailure { ProbeLog.add("AnkiDroid read failed: $it") }
        }
    }

    private fun logStatus() {
        val anki = ContextCompat.checkSelfPermission(this, AnkiProvider.PERMISSION) == PackageManager.PERMISSION_GRANTED
        ProbeLog.add(
            "Status: focus service enabled=${FocusProbeService.isEnabled(this)} connected=${FocusProbeService.connected.value} " +
                "guard=${FocusProbeService.guardEnabled}; AnkiDroid permission=$anki; watching Teams=$observingTeams",
        )
    }

    @Composable
    private fun ProbeScreen() {
        val lines by ProbeLog.lines.collectAsStateWithLifecycle()
        val connected by FocusProbeService.connected.collectAsStateWithLifecycle()
        var guard by remember { mutableStateOf(FocusProbeService.guardEnabled) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Decrastination: Phase 0 probes", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { readTeams() }) { Text("Read Teams") }
                    Button(onClick = { readAnki() }) { Text("Read Anki") }
                    OutlinedButton(onClick = { callTeams(TeamsProvider.METHOD_REQUEST_SYNC) }) { Text("Sync Teams") }
                    OutlinedButton(onClick = { openFirstAssignment() }) { Text("Open first") }
                    OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Focus service ${if (connected) "on" else "off"} · settings guard")
                    Switch(checked = guard, onCheckedChange = {
                        guard = it
                        FocusProbeService.guardEnabled = it
                        ProbeLog.add("Settings guard ${if (it) "on" else "off"}")
                    })
                }
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    items(lines.asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROBE = "probe"

        /** The manifest's adb-only alias for this activity (see the class comment). */
        private const val COMMAND_ALIAS = "com.thomaswcode.decrastination.probe.ProbeCommand"
        private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

        private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormat)
    }
}

@Composable
fun ProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
