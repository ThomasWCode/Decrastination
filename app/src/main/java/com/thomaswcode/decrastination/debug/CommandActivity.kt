package com.thomaswcode.decrastination.debug

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.FocusService
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.withoutEnrichment
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.enrich.Prompts
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.CheckIns
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Review
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.ui.OpenTaskActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * - `briefing`, `check-in`, `review`: the morning briefing, the Sunday check-in's reminder, and
 *   the week's review, now (the review learns the calibration from the log as Sunday's does).
 * - `calendar`: reads the calendar now, and logs how many events take time, load days, or need asking.
 * - `assess --es task <id>`: asks "how was it?" about that task, as a completion does.
 * - `ai-prompts --ei count 6`: writes `files/ai-prompts.json`, the exact prompts and schemas the
 *   model would get for up to that many tasks per job, for trying them out without the API (the
 *   file holds your tasks' text: pull it into the git-ignored `private/`, then delete it).
 * - `ai-check --es base http://127.0.0.1:8089`: one model call per job through the real client
 *   to a stand-in server (`adb reverse` to the PC), with a dummy key, logging what it reads back.
 *   Nothing reaches Anthropic and nothing is stored: it checks the client works on the phone.
 * - `instruction --es text "<words>"` (about `--es task <id>`, `--es event <name>` or `--es day
 *   YYYY-MM-DD`, or none): an instruction written as on the phone, read by Claude (a paid call)
 *   and left for you to apply; `instructions` logs them all with Claude's reading, and
 *   `instruction-discard --es id <id|all>` discards those not applied.
 * - Test hooks: `test-arm`, `test-disarm` (at once, unlike the app's own disarming), `remove-admin`,
 *   `clear-parent-code`, `offer-teams-sync` (the countdown banner now, whatever the rules), and
 *   `clean-up` (this app's notifications, a delayed Teams sync, forced blocking hours, the
 *   watchdog's restarts, and reviews run at other times than Sunday evening, cleared).
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

    /**
     * Read-only: each section deck's counts as the Anki source reads them, its words still new
     * (notes) beside its cards (one a side), and its new cards a day.
     */
    private suspend fun logAnkiCounts() = withContext(Dispatchers.IO) {
        val resolver = contentResolver
        for (deck in AnkiProvider.decks(resolver).filter { AnkiRules.section(it) != null }) {
            val words = resolver.query("content://com.ichi2.anki.flashcards/notes".toUri(), arrayOf("_id"), "deck:\"${deck.name}\" is:new", null, null)?.use { it.count } ?: 0
            Log.i(TAG, "anki-counts ${deck.name}: today learn ${deck.learn} review ${deck.review} new ${deck.new}; ${deck.newPerDay} new a day; $words words and ${AnkiProvider.unseenCards(resolver, deck.name)} cards never studied")
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
            "anki-counts" -> logAnkiCounts()
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
                // Reviews run from here (any but a Sunday evening's) were tests: dropped.
                val zone = graph.clock.zone()
                graph.log.update { log ->
                    log.copy(reviews = log.reviews.filter { r -> java.time.Instant.ofEpochMilli(r.at).atZone(zone).let { it.dayOfWeek == java.time.DayOfWeek.SUNDAY && it.hour * 60 + it.minute >= graph.settings.value.checkInMin } })
                }
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
            "briefing" -> Briefing.run(this).also { Log.i(TAG, "Briefing posted") }
            "check-in" -> CheckIns.remind(this).also { Log.i(TAG, "Check-in reminder posted") }
            "review" -> {
                Review.run(this, ifDue = false)
                Log.i(TAG, "Review: ${graph.log.value.reviews.lastOrNull()?.lines}; calibration ${graph.runtime.value.calibration}")
            }
            "calendar" -> {
                // --ez again true: the questions still unanswered are asked again (their notifications
                // gone): what's to ask about is read first, then no longer counted as asked.
                if (intent.getBooleanExtra("again", false)) {
                    CalendarTime.refresh(this)
                    val keys = graph.calendarTime.toAsk.map(EventJudge::key).toSet()
                    graph.runtime.update { it.copy(eventsAsked = it.eventsAsked - keys) }
                    Log.i(TAG, "Asking again about ${keys.size}: ${keys.joinToString()}")
                }
                CalendarTime.refresh(this)
                val time = graph.calendarTime
                Log.i(TAG, "Calendar: ${time.busy.size} busy, loads ${time.dayLoads}, ${time.toAsk.size} to ask about; allowed ${CalendarTime.allowed(this)}")
            }
            "assess" -> {
                val task = graph.tasks.value.tasks.firstOrNull { it.id == intent.getStringExtra("task") }
                if (task == null) Log.w(TAG, "assess needs --es task <id>") else Assessment.ask(this, listOf(task.copy(kind = Kind.Homework)))
            }
            "ai-prompts" -> {
                val count = intent.getIntExtra("count", 6)
                val now = graph.clock.now()
                val items = Enrichments.Job.entries.flatMap { job ->
                    graph.tasks.value.tasks.filter { Enrichments.jobFor(it) == job }.take(count).map { task ->
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(task.id),
                                "job" to JsonPrimitive(job.name),
                                "system" to JsonPrimitive(Prompts.system(job)),
                                "user" to JsonPrimitive(Prompts.describe(task, job, now, graph.clock.zone())),
                                "schema" to Prompts.schemaJson(job),
                            ),
                        )
                    }
                }
                File(filesDir, "ai-prompts.json").writeText(JsonArray(items).toString())
                Log.i(TAG, "Wrote ${items.size} prompts to files/ai-prompts.json")
            }
            "ai-endpoint" -> {
                // --es base <url> sends the model's calls to a stand-in till the app restarts; none, to Anthropic again.
                graph.modelEndpoint = intent.getStringExtra("base")
                Log.w(TAG, graph.modelEndpoint?.let { "Claude's calls now go to $it, not Anthropic, till this is cleared or the app restarts" } ?: "Claude's calls go to Anthropic again")
            }
            "enrich-task" -> {
                val id = intent.getStringExtra("task")
                if (graph.tasks.value.tasks.none { it.id == id }) {
                    Log.w(TAG, "enrich-task needs --es task <id> of a task")
                } else if (graph.focus.session?.taskId == id) {
                    // Its steps stay while a session works through them: try once it's ended.
                    Log.w(TAG, "enrich-task: $id has a focus session under way; try again after it")
                } else {
                    // Its enrichment forgotten, and the steps it gave: out of date, so this run asks for it
                    // again now, and its answer's steps aren't taken for the source's.
                    graph.tasks.update { state -> state.copy(tasks = state.tasks.map { if (it.id == id) it.withoutEnrichment() else it }) }
                    graph.enrichNow(only = id)
                    val task = graph.tasks.value.tasks.first { it.id == id }
                    val e = task.enrichment
                    Log.i(TAG, "enrich-task $id: by ${e?.by}, dropped ${e?.dropped}, ${task.subSteps.size} steps: ${task.subSteps.joinToString(" | ") { "${it.title} (${it.minutes})" }}")
                }
            }
            "instruction" -> {
                // --es text "<words>", about --es task <id>, --es event <name>, or --es day YYYY-MM-DD (or none):
                // written as on the phone, then read by Claude; it waits for you to apply it.
                val text = intent.getStringExtra("text")
                if (text.isNullOrBlank()) {
                    Log.w(TAG, "instruction needs --es text <words>")
                } else {
                    val task = intent.getStringExtra("task")?.let { id -> graph.tasks.value.tasks.firstOrNull { it.id == id } }
                    val event = intent.getStringExtra("event")?.let { key -> graph.calendarTime.events.firstOrNull { EventJudge.key(it) == key } }
                    val about = About(
                        taskId = task?.id,
                        taskTitle = task?.title,
                        eventKey = event?.let(EventJudge::key),
                        eventTitle = event?.title,
                        eventStart = event?.start,
                        day = intent.getStringExtra("day"),
                    )
                    val id = graph.addInstruction(text, about)
                    Log.i(TAG, "instruction $id written; `instructions` shows Claude's reading")
                }
            }
            "instruction-discard" -> {
                // --es id <id>, or all: discards those not applied (a test's), as Discard does.
                val which = intent.getStringExtra("id")
                val ids = graph.instructions.value.instructions.filter { it.state != InstructionStatus.Applied && (which == "all" || it.id == which) }.map { it.id }
                ids.forEach { graph.deleteInstruction(it) }
                Log.i(TAG, "instruction-discard: ${ids.size} discarded")
            }
            "instructions" -> {
                val tasks = graph.tasks.value.tasks.associateBy { it.id }
                graph.instructions.value.instructions.forEach { i ->
                    val read = i.changes.joinToString(" | ") { Instructions.describe(it, tasks, graph.clock.zone()) }
                    Log.i(TAG, "instruction ${i.id} ${i.state}: '${i.text}' -> ${read.ifEmpty { i.note ?: "-" }}")
                }
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
            // A new Claude key, as from Setup: the last one's failure and alert aren't its.
            if (Secret.AnthropicApiKey in secrets) {
                graph.runtime.update { it.copy(aiUsage = it.aiUsage.newKey()) }
                ModelAlerts.keyFixed(this)
            }
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
