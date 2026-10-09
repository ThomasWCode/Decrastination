package com.thomaswcode.decrastination.debug

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.FocusService
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.ui.OpenTaskActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Commands for setting up and testing the app from a PC, over adb:
 *
 *     adb shell am start -n com.thomaswcode.decrastination/.debug.Command --es cmd <command>
 *
 * - `import-credentials`: moves `files/credentials.import` (a JSON object of [Secret] names to
 *   values, written there with `run-as`) into the encrypted store, then deletes it. The values
 *   are never logged; `scripts/load_credentials.py` writes the file from your Windows user
 *   environment without printing them.
 * - `sync`, optionally `--es sources teams,gmail`: reads the sources now, as a job (see SyncWorker).
 * - `state`: logs each source's status and the open tasks' titles (`adb logcat -s Decrastination`).
 * - `open --es task <id>`: opens that task where it lives, as the widget's tap does.
 * - `force-block --ei minutes 10`: blocking hours apply for that long, whatever the time, for
 *   testing at night; 0 ends it. It can only tighten: quiet and school hours stop protecting.
 * - `protection [--ez repair true]`: runs the watchdog and logs what it found.
 * - `enrich`: enriches what's new or changed now (the rules, or the model if it's on) and logs
 *   who enriched what.
 * - `ai-check --es base http://127.0.0.1:8089`: one model call per job through the real client
 *   to a stand-in server (`adb reverse` to the PC), with a dummy key, logging what it reads back.
 *   Nothing reaches Anthropic and nothing is stored: it checks the client works on the phone.
 * - Test hooks: `test-arm`, `test-disarm` (at once, unlike the app's own disarming), `remove-admin`,
 *   `clear-parent-code`, `offer-teams-sync` (the countdown banner now, whatever the rules), and
 *   `clean-up` (this app's notifications, a delayed Teams sync, forced blocking hours and the
 *   watchdog's restarts cleared).
 *
 * The manifest guards the alias with DUMP, which the adb shell holds and no ordinary app can, so
 * nothing else on the phone can reach these. The activity itself isn't exported.
 */
class CommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viaAlias = intent.component?.className == ALIAS
        val command = intent.getStringExtra("cmd")
        // Starting another app's screen has to happen while this one is in front.
        if (viaAlias && command == "open") startActivity(OpenTaskActivity.intent(this, intent.getStringExtra("task")))
        finish()
        if (!viaAlias || command == null) return
        val graph = AppGraph.get(this)
        graph.scope.launch {
            runCatching { run(graph, command) }.onFailure { Log.w(TAG, "Command $command failed", it) }
        }
    }

    private suspend fun run(graph: AppGraph, command: String) {
        when (command) {
            "import-credentials" -> importCredentials(graph)
            "sync" -> {
                val only = intent.getStringExtra("sources")?.split(",")?.mapNotNull { name ->
                    Source.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                }?.toSet()
                // As a job: this activity is gone in a moment, and the app's network with it.
                SyncWorker.syncNow(this, only)
                Log.i(TAG, "Sync asked for; the worker logs what it found")
            }
            "state" -> logState(graph)
            "force-block" -> {
                // Testing at night: blocking hours apply for this many minutes (tightening only).
                val minutes = intent.getIntExtra("minutes", 10).coerceIn(0, 120)
                val until = graph.clock.now() + minutes * 60_000L
                graph.runtime.update { it.copy(forceActiveUntil = if (minutes == 0) null else until) }
                Log.i(TAG, "Blocking hours apply for $minutes min; verdict now ${graph.focus.verdict()}")
            }
            // Test hooks, from a PC only: arming and disarming at once, as no screen can.
            "test-arm" -> graph.settings.update { it.copy(armed = true) }.also { Log.i(TAG, "Armed (test)") }
            "test-disarm" -> {
                graph.settings.update { it.copy(armed = false) }
                graph.runtime.update { it.copy(pending = it.pending.filterNot { change -> change.field == "armed" }) }
                Log.i(TAG, "Disarmed (test)")
            }
            "remove-admin" -> {
                getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(this))
                Log.i(TAG, "Device admin removed")
            }
            "clean-up" -> {
                // After testing: this app's notifications gone, and no Teams sync left waiting.
                NotificationManagerCompat.from(this).cancelAll()
                graph.runtime.update {
                    it.copy(
                        teamsAuto = TeamsAutoSync.State(),
                        forceActiveUntil = null,
                        protection = it.protection.copy(stoppedSince = null, restartedAt = null, restartTries = 0),
                    )
                }
                Log.i(TAG, "Cleaned up after testing")
            }
            "clear-parent-code" -> graph.secrets.put(Secret.TotpSecret, null).also { Log.i(TAG, "Parent code cleared") }
            "offer-teams-sync" -> sendBroadcast(Intent(FocusService.ACTION_OFFER_TEAMS_SYNC).setPackage(packageName)).also { Log.i(TAG, "Asked the focus service to offer a Teams sync") }
            "protection" -> {
                Watchdog.check(this, repair = intent.getBooleanExtra("repair", false))
                Log.i(TAG, "Protection: ${Watchdog.report(this)}; problems ${graph.runtime.value.protection.problems}")
            }
            "enrich" -> {
                graph.enrichNow()
                val open = graph.tasks.value.tasks.filter { Enrichments.jobFor(it) != null }
                Log.i(TAG, "Enriched: " + open.groupingBy { it.enrichment?.by ?: "nothing" }.eachCount())
                open.filter { it.subSteps.isNotEmpty() && it.enrichment?.subSteps != null }
                    .forEach { Log.i(TAG, "  ${it.title}: ${it.subSteps.joinToString(" | ") { s -> "${s.title} (${s.minutes})" }}") }
            }
            "ai-check" -> {
                val base = intent.getStringExtra("base")
                if (base == null) {
                    Log.w(TAG, "ai-check needs --es base <url>")
                } else {
                    val model = ClaudeEnricher("dummy-key-for-a-stand-in", graph.clock.zone(), endpoint = base)
                    for (job in Enrichments.Job.entries) {
                        val task = graph.tasks.value.tasks.firstOrNull { Enrichments.jobFor(it) == job } ?: continue
                        val result = runCatching { model.enrich(task, job, graph.clock.now()) }
                        val said = result.fold({ "${it.enrichment} (cost ${"%.4f".format(it.costUsd)} USD, refused ${it.refused})" }, { "failed: $it" })
                        Log.i(TAG, "ai-check $job on '${task.title}': $said")
                    }
                }
            }
            else -> Log.w(TAG, "Unknown command $command")
        }
    }

    private suspend fun importCredentials(graph: AppGraph) {
        val file = File(filesDir, IMPORT_FILE)
        if (!file.exists()) {
            Log.w(TAG, "No $IMPORT_FILE to import")
            return
        }
        try {
            val values = withContext(Dispatchers.IO) {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(file.readText())
            }
            val secrets = values.mapNotNull { (key, value) -> Secret.entries.firstOrNull { it.name == key }?.let { it to value } }.toMap()
            // A new Power Planner login invalidates the saved session.
            val reset = if (Secret.PowerPlannerUsername in secrets || Secret.PowerPlannerPassword in secrets) {
                mapOf(Secret.PowerPlannerSession to null, Secret.PowerPlannerAccountId to null)
            } else {
                emptyMap()
            }
            graph.secrets.put(reset + secrets)
            Log.i(TAG, "Imported ${secrets.keys.joinToString()}; ignored ${(values.keys - secrets.keys.map { it.name }.toSet()).size} unknown")
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun logState(graph: AppGraph) {
        val state = graph.tasks.value
        for (source in Source.entries) {
            val status = state.status(source)
            Log.i(TAG, "${source.label}: ok ${status.lastSuccessAt}, tried ${status.lastAttemptAt}, error ${status.error}, data as of ${status.dataAsOf}, note ${status.note}")
        }
        for (task in state.tasks.sortedWith(compareBy({ it.source }, { it.dueAt }))) {
            Log.i(TAG, "  [${task.status}] ${task.source.label} ${task.kind.label} ${task.effortMin}m due ${task.dueAt} | ${task.title}")
        }
        Log.i(TAG, "Secrets set: ${graph.secrets.present.value.joinToString()}")
    }

    companion object {
        const val TAG = "Decrastination"
        const val ALIAS = "com.thomaswcode.decrastination.debug.Command"
        const val IMPORT_FILE = "credentials.import"
    }
}
