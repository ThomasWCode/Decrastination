package com.thomaswcode.decrastination.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 *
 * The manifest guards the alias with DUMP, which the adb shell holds and no ordinary app can, so
 * nothing else on the phone can reach these. The activity itself isn't exported.
 */
class CommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viaAlias = intent.component?.className == ALIAS
        val command = intent.getStringExtra("cmd")
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
