package com.thomaswcode.decrastination.debug

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.FocusService
import com.thomaswcode.decrastination.block.TeamsAutoSync
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.withoutEnrichment
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.FelixSettings
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.ChatReader
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.felix.FelixCaller
import com.thomaswcode.decrastination.felix.FelixPrompts
import com.thomaswcode.decrastination.enrich.InstructionReader
import com.thomaswcode.decrastination.enrich.MemoryTidier
import com.thomaswcode.decrastination.enrich.MicroPlanner
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.enrich.PhotoTaskReader
import com.thomaswcode.decrastination.enrich.Prompts
import com.thomaswcode.decrastination.enrich.SummaryWriter
import com.thomaswcode.decrastination.enrich.WebResearcher
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.CalendarTime
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
 * - `chat-context`: writes `files/chat-context.txt`, what the chat would send Claude with a message
 *   now (the tasks, the calendar, the changes in use and the plan), for the chat's eval; it too
 *   holds your tasks' text, to pull into `private/` and delete.
 * - `chat-ask --es text "<words>"`: a message to *Ask Claude*, as sent on the phone (a paid call),
 *   logging Claude's reply; `chat-clear` empties the chat, as its menu's *Clear the chat* does.
 * - `ai-check --es base http://127.0.0.1:8089`: one model call per job through the real client
 *   to a stand-in server (`scripts/claude_stub.py`, `adb reverse` to the PC), with a dummy key, logging what it reads back.
 *   Nothing reaches Anthropic and nothing is stored: it checks the client works on the phone.
 * - `instruction --es text "<words>"` (about `--es task <id>`, `--es event <name>` or `--es day
 *   YYYY-MM-DD`, or none): an instruction written as on the phone, read by Claude (a paid call)
 *   and left for you to apply; `instructions` logs them all with Claude's reading, and
 *   `instruction-discard --es id <id|all>` discards those not applied.
 * - Your own tasks (2.0.0): `own-add --es title "<name>"` (with `--es due 2026-10-16T17:00`,
 *   `--ei minutes 45`, `--es kind Revision`), logging its id; `own-done`, `own-reopen` and
 *   `own-delete --es task <id>`; and `quick-minutes --es task <id> --ei minutes N` (`--ei minutes 0`
 *   takes it back), a task's minutes as its sheet sets them. `forget-task --es task own:<id>` leaves no
 *   trace of a test's task of your own: gone, and its completions, slots and question with it.
 * - Test hooks: `test-arm`, `test-disarm` (at once, unlike the app's own disarming), `remove-admin`,
 *   `clear-parent-code`, `offer-teams-sync` (the countdown banner now, whatever the rules),
 *   `free-time --ei minutes 2` (free time's countdown and heads-up notifications, nothing spent), and
 *   `clean-up` (this app's notifications, a delayed Teams sync, forced blocking hours, the
 *   watchdog's restarts, and reviews run at other times than Sunday evening, cleared).
 * - The debug page (the menu's *Debug*, on a debug build) has a button for each command that takes
 *   nothing ([PLAIN]), which starts this activity from inside the app.
 *
 * The manifest guards the alias with DUMP, which the adb shell holds and no ordinary app can, so
 * nothing else on the phone can reach these. The activity itself isn't exported: only the app
 * starts it without the alias, as the debug page does, and then only for [PLAIN]'s, on a debug build.
 */
class CommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viaAlias = intent.component?.className == ALIAS
        val command = intent.getStringExtra("cmd")
        // The debug page's buttons (AR-03): from the app itself, as the activity isn't exported.
        val fromDebugPage = !viaAlias && com.thomaswcode.decrastination.BuildConfig.DEBUG && command in PLAIN
        // Starting another app's screen has to happen while this one is in front.
        if (viaAlias && command == "open") startActivity(OpenTaskActivity.intent(this, intent.getStringExtra("task")))
        finish()
        if (!(viaAlias || fromDebugPage) || command == null) return
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
            // The plan's next seven days: each day's minutes, its slots and what has none (phase 3).
            "plan" -> {
                val plan = graph.planning.plan()
                val zone = graph.clock.zone()
                fun hm(time: Long) = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalTime().toString()
                for (bucket in plan.buckets.take(7)) {
                    val slots = bucket.slots.joinToString { "${it.kind} ${hm(it.start)}-${hm(it.end)} ${it.key}${if (it.pinned) " pinned" else ""}" }
                    Log.i(TAG, "plan ${bucket.date}: ${bucket.plannedMin} of ${bucket.capacityMin} min; slots [$slots]; unslotted ${bucket.unslotted.map { it.key }}")
                }
                Log.i(TAG, "plan next ${plan.next?.key}, then ${plan.then?.key}; kept ${graph.runtime.value.slots}; ${graph.runtime.value.calendarWritten.size} written to calendar ${graph.settings.value.calendarWriteId}")
            }
            // The app's events in the calendar written to, with their reminders (NF-07: it sets none,
            // but a calendar's own default notifications could be laid on them as they sync).
            "calendar-written" -> withContext(Dispatchers.IO) {
                val calendarId = graph.settings.value.calendarWriteId ?: return@withContext Log.w(TAG, "No calendar written to")
                val zone = graph.clock.zone()
                contentResolver.query(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    arrayOf(android.provider.CalendarContract.Events._ID, android.provider.CalendarContract.Events.CUSTOM_APP_URI, android.provider.CalendarContract.Events.DTSTART, android.provider.CalendarContract.Events.HAS_ALARM),
                    "${android.provider.CalendarContract.Events.CALENDAR_ID} = ? AND ${android.provider.CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${android.provider.CalendarContract.Events.DELETED} = 0",
                    arrayOf(calendarId.toString(), packageName), android.provider.CalendarContract.Events.DTSTART,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val reminders = android.provider.CalendarContract.Reminders.query(contentResolver, c.getLong(0), arrayOf(android.provider.CalendarContract.Reminders.MINUTES))?.use { it.count } ?: 0
                        Log.i(TAG, "calendar-written ${c.getString(1)} at ${java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(zone).toLocalDateTime()}, hasAlarm ${c.getInt(3)}, reminders $reminders")
                    }
                }
            }
            // A calendar sync asked for, for each Google account with a calendar here (as pulling to
            // refresh in a calendar app does): a calendar just made at calendar.google.com comes down.
            "calendar-sync" -> withContext(Dispatchers.IO) {
                val accounts = mutableSetOf<Pair<String, String>>()
                contentResolver.query(
                    android.provider.CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, android.provider.CalendarContract.Calendars.ACCOUNT_TYPE), null, null, null,
                )?.use { c -> while (c.moveToNext()) accounts += c.getString(0).orEmpty() to c.getString(1).orEmpty() }
                for ((name, type) in accounts.filter { it.second == "com.google" }) {
                    val extras = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    }
                    runCatching { android.content.ContentResolver.requestSync(android.accounts.Account(name, type), android.provider.CalendarContract.AUTHORITY, extras) }
                        .onSuccess { Log.i(TAG, "Calendar sync asked for one account") }.onFailure { Log.w(TAG, "Calendar sync not asked for", it) }
                }
            }
            // The calendar the plan's written to, picked by its name, as Settings → Calendar does (NF-07).
            "calendar-pick" -> {
                val name = intent.getStringExtra("name")
                val calendar = com.thomaswcode.decrastination.calendar.SlotCalendar.calendars(this).firstOrNull { it.writable && it.name == name }
                if (calendar == null) {
                    Log.w(TAG, "No calendar named $name to write to")
                } else {
                    graph.protection.changeSettings { it.copy(calendarWriteId = calendar.id) }
                    Log.i(TAG, "Writing the plan to calendar ${calendar.id} ($name)")
                }
            }
            // One of the app's own slot events moved (--ei by <minutes>) or deleted, as a hand in Google
            // Calendar would, to check what the app makes of it (NF-07): --es key <slot key>.
            "calendar-move", "calendar-delete" -> withContext(Dispatchers.IO) {
                val key = intent.getStringExtra("key")
                val calendarId = graph.settings.value.calendarWriteId
                if (key == null || calendarId == null) return@withContext Log.w(TAG, "$command needs --es key and a calendar written to")
                val events = android.provider.CalendarContract.Events.CONTENT_URI
                val found = contentResolver.query(
                    events, arrayOf(android.provider.CalendarContract.Events._ID, android.provider.CalendarContract.Events.DTSTART, android.provider.CalendarContract.Events.DTEND),
                    "${android.provider.CalendarContract.Events.CALENDAR_ID} = ? AND ${android.provider.CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${android.provider.CalendarContract.Events.CUSTOM_APP_URI} LIKE ? AND ${android.provider.CalendarContract.Events.DELETED} = 0",
                    arrayOf(calendarId.toString(), packageName, "%/$key"), null,
                )?.use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getLong(2)) else null }
                if (found == null) return@withContext Log.w(TAG, "No event of the app's for $key")
                val (id, start, end) = found
                val uri = android.content.ContentUris.withAppendedId(events, id)
                if (command == "calendar-delete") {
                    contentResolver.delete(uri, null, null)
                    Log.i(TAG, "Deleted the event for $key")
                } else {
                    val by = intent.getIntExtra("by", 30) * 60_000L
                    contentResolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(android.provider.CalendarContract.Events.DTSTART, start + by)
                            put(android.provider.CalendarContract.Events.DTEND, end + by)
                        },
                        null, null,
                    )
                    Log.i(TAG, "Moved the event for $key by ${by / 60_000} min")
                }
            }
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
                graph.notifier.cancelAll()
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
            // Free time's countdown and its last minute's heads-up, as spending it shows them, for checking the
            // notifications from a PC: --ei minutes <n> till it would end; nothing is spent or given, and clean-up takes them away.
            "free-time" -> {
                val minutes = intent.getIntExtra("minutes", 2).coerceIn(1, 60)
                com.thomaswcode.decrastination.block.FreeTimeNotice.show(this, "A test", graph.clock.now() + minutes * 60_000L)
                com.thomaswcode.decrastination.block.FreeTimeNotice.warn(this, "A test")
                Log.i(TAG, "free-time: the countdown to $minutes min and the heads-up posted (notifications ${if (graph.notifier.allowed()) "allowed" else "not allowed"})")
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
            // One of the settings Felix may change (data/FelixSettings.kt), by name and value, as he sets it.
            "setting" -> {
                val name = intent.getStringExtra("name").orEmpty()
                val value = intent.getStringExtra("value").orEmpty()
                var said: String? = null
                graph.protection.changeSettings { s -> FelixSettings.set(s, name, value)?.also { said = it.second }?.first ?: s }
                Log.i(TAG, "setting: " + (said ?: "$name not set to $value"))
            }
            "briefing" -> Briefing.run(this).also { Log.i(TAG, "Briefing posted") }
            "check-in" -> graph.conversation.remindCheckIn().also { Log.i(TAG, "Check-in reminder posted") }
            // The nightly backup's work, once (AR-07).
            "backup-now" -> Log.i(TAG, "Backup: ${if (graph.backups.backupNow()) "written" else "not written"}; last ${graph.runtime.value.lastBackupAt}")
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
                // How far it was read (DX-14: as far as the plan reaches).
                val last = time.events.maxOfOrNull { it.start }?.let { java.time.Instant.ofEpochMilli(it).atZone(graph.clock.zone()).toLocalDate() }
                Log.i(TAG, "Calendar: ${time.events.size} events, the last on $last; ${time.busy.size} busy, loads ${time.dayLoads}, ${time.toAsk.size} to ask about; allowed ${CalendarTime.allowed(this)}")
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
            "chat-context" -> {
                // What the chat sends Claude with your newest message now, the plan included; and the
                // conversation's turns as your newest message was sent, a reply's quote among them (5b).
                File(filesDir, "chat-context.txt").writeText(graph.conversation.chatContext().whole)
                val messages = graph.chat.value.messages
                val sent = messages.take(messages.indexOfLast { it.from == Speaker.You } + 1)
                val turns = ChatPrompts.turns(sent, "(the context)", graph.tasks.value.tasks.associateBy { it.id }, graph.clock.zone(), plan = "(the plan)")
                File(filesDir, "chat-turns.txt").writeText(turns.joinToString("\n\n") { (from, text) -> "== $from\n$text" })
                Log.i(TAG, "Wrote the chat's context to files/chat-context.txt, and the turns your newest message was sent with to files/chat-turns.txt")
            }
            "chat-ask" -> {
                // --es text "<words>": sent to the chat as on the phone (a paid call); Claude's reply logged.
                // --es replyTo <message id>: a reply to that message, routed as a swipe's or his notification's
                // is (5b): a question of his answered with no call, logged as what follows it.
                val text = intent.getStringExtra("text")
                val asked = if (text.isNullOrBlank()) Conversation.Asked.Nothing else graph.conversation.send(text, replyTo = intent.getStringExtra("replyTo"))
                if (asked == Conversation.Asked.Nothing) {
                    Log.w(TAG, "chat-ask needs --es text <words>")
                } else if (asked == Conversation.Asked.Answer) {
                    // Answered with no call: what followed it, no answer of his waited for.
                    val last = graph.chat.value.messages.last()
                    Log.i(TAG, "chat-ask: answered with no call; then ${last.from}: ${last.text}")
                } else {
                    // Waits its turn behind the answer ask started, or answers it itself.
                    graph.conversation.answerChat()
                    val last = graph.chat.value.messages.last()
                    Log.i(TAG, "chat-ask: ${last.from}: ${last.text}${last.note?.let { " [$it]" }.orEmpty()}; ${last.proposals.size} cards")
                }
            }
            "chat-clear" -> graph.conversation.clearChat().also { Log.i(TAG, "Chat cleared") }
            // Felix (LR-03): his state and his last messages of his own, from the chat; a look now; or a message for a reason, whatever the rules say (a paid call).
            "felix" -> {
                val chat = graph.chat.value
                val state = chat.felix
                Log.i(TAG, "felix: next check ${state.nextCheckAt?.let { java.time.Instant.ofEpochMilli(it).atZone(graph.clock.zone()).toLocalTime() }}, last sent ${state.lastSentAt}, activity ${state.activity.keys}, questions ${state.questions.size}, to ask how it went ${state.assessLater.size}, check-in ${state.checkInDraft?.step}; read to ${chat.readAt}")
                ChatHistory.his(chat.messages).takeLast(10).forEach { m ->
                    Log.i(TAG, "felix ${m.kind}${if (m.byApp) " (the app's words)" else ""} ${m.pieceKey}: ${m.text.ifBlank { "(silent)" }} ${m.suggestedReplies}${ChatHistory.replyOf(m, chat.messages)?.let { r -> " / reply: ${r.text}" }.orEmpty()}")
                }
            }
            "felix-check" -> graph.looks.check().also { Log.i(TAG, "felix-check done") }
            "felix-say" -> {
                val reason = intent.getStringExtra("reason")?.let { name -> FelixPrompts.Reason.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                if (reason == null) Log.w(TAG, "felix-say needs --es reason, one of ${FelixPrompts.Reason.entries.joinToString()}")
                else Log.i(TAG, "felix-say $reason: ${graph.looks.say(reason)?.let { "${it.text.ifBlank { "(silent)" }} ${it.suggestedReplies}" } ?: "Claude is off"}")
            }
            // What Felix remembers (AI-21): its standing instructions and facts, a line each.
            "memory" -> graph.memory.value.let { m ->
                Log.i(TAG, "memory: ${m.facts.size} facts, ${com.thomaswcode.decrastination.core.Memories.chars(m.facts)} of ${com.thomaswcode.decrastination.core.Memories.FACT_BUDGET_CHARS} characters; ${m.rules.size} rules")
                m.rules.forEach { Log.i(TAG, "memory rule: ${it.text}") }
                m.facts.forEach { Log.i(TAG, "memory fact: ${it.topic}: ${it.text}") }
            }
            // The weekly tidy now, due or not (a paid call), applied as Sunday's would be.
            "memory-tidy" -> {
                val tidy = com.thomaswcode.decrastination.learn.Review.tidyMemory(graph, graph.clock.now(), force = true)
                Log.i(TAG, tidy?.let { "memory-tidy: ${com.thomaswcode.decrastination.learn.Review.tidyLine(it)}" } ?: "memory-tidy: nothing tidied (no facts, Claude held or failed, or no room made)")
            }
            // --es do apply|undo: the last reply's first card applied, or taken back, as its buttons do.
            "chat-card" -> {
                val message = graph.chat.value.messages.lastOrNull { it.proposals.isNotEmpty() }
                val card = message?.proposals?.firstOrNull()
                if (message == null || card == null) {
                    Log.w(TAG, "chat-card: no card to act on")
                } else {
                    when (intent.getStringExtra("do")) {
                        "apply" -> graph.conversation.applyProposal(message.id, card.id)
                        "undo" -> graph.conversation.undoProposal(message.id, card.id)
                    }
                    val now = graph.chat.value.messages.firstOrNull { it.id == message.id }?.proposals?.firstOrNull { it.id == card.id }
                    Log.i(TAG, "chat-card ${intent.getStringExtra("do")}: ${card.lines} -> ${now?.state}")
                }
            }
            "ai-effort" -> {
                // --es job <Chat|Email|…> --es level <low|medium|high>, or level none: back to its own (AC-02); till the app restarts.
                val job = intent.getStringExtra("job")?.let { name -> com.thomaswcode.decrastination.enrich.ModelEffort.Job.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                val level = intent.getStringExtra("level")
                when {
                    job == null -> Log.w(TAG, "ai-effort needs --es job, one of ${com.thomaswcode.decrastination.enrich.ModelEffort.Job.entries.joinToString()}")
                    level == "none" -> com.thomaswcode.decrastination.enrich.ModelEffort.overrides.remove(job).also { Log.i(TAG, "ai-effort: $job as its own again") }
                    else -> runCatching { com.anthropic.models.messages.OutputConfig.Effort.of(level.orEmpty().lowercase()) }
                        .onSuccess { com.thomaswcode.decrastination.enrich.ModelEffort.overrides[job] = it; Log.i(TAG, "ai-effort: $job at $it till the app restarts") }
                        .onFailure { Log.w(TAG, "ai-effort needs --es level low, medium or high") }
                }
            }
            "ai-endpoint" -> {
                // --es base <url> sends the model's calls to a stand-in till the app restarts; none, to Anthropic again.
                graph.gateway.endpoint = intent.getStringExtra("base")
                Log.w(TAG, graph.gateway.endpoint?.let { "Claude's calls now go to $it, not Anthropic, till this is cleared or the app restarts" } ?: "Claude's calls go to Anthropic again")
            }
            "enrich-task" -> {
                val id = intent.getStringExtra("task")
                if (graph.tasks.value.tasks.none { it.id == id }) {
                    Log.w(TAG, "enrich-task needs --es task <id> of a task")
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
                    val id = graph.instructions.addInstruction(text, about)
                    Log.i(TAG, "instruction $id written; `instructions` shows Claude's reading")
                }
            }
            "instruction-discard" -> {
                // --es id <id>, or all: discards those not applied (a test's), as Discard does.
                val which = intent.getStringExtra("id")
                val ids = graph.instructions.value.instructions.filter { it.state != InstructionStatus.Applied && (which == "all" || it.id == which) }.map { it.id }
                ids.forEach { graph.instructions.deleteInstruction(it) }
                Log.i(TAG, "instruction-discard: ${ids.size} discarded")
            }
            "instruction-delete" -> {
                // --es id <id>: one applied taken back, as the instructions' Take back does (or one not applied
                // discarded); a refusal (a circle it would bring back) leaves it, saying why.
                val instruction = intent.getStringExtra("id")?.let { id -> graph.instructions.value.instructions.firstOrNull { it.id == id } }
                if (instruction == null) {
                    Log.w(TAG, "instruction-delete needs --es id <an instruction's id>: `instructions` lists them")
                } else {
                    graph.instructions.deleteInstruction(instruction.id)
                    val kept = graph.instructions.value.instructions.firstOrNull { it.id == instruction.id }
                    Log.i(TAG, "instruction-delete ${instruction.id}: " + if (kept != null) "kept: ${kept.note}" else if (instruction.state == InstructionStatus.Applied) "taken back" else "discarded")
                }
            }
            "own-add" -> {
                val title = intent.getStringExtra("title")
                val zone = graph.clock.zone()
                val due = intent.getStringExtra("due")?.let { runCatching { java.time.LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli() }.getOrNull() }
                val kind = intent.getStringExtra("kind")?.let { k -> Kind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } } ?: Kind.Homework
                val task = OwnTask(title = title.orEmpty(), kind = kind, dueAt = due, minutes = intent.getIntExtra("minutes", kind.defaultEffortMin))
                val problem = OwnTasks.problem(task)
                if (problem != null) {
                    Log.w(TAG, "own-add: $problem")
                } else {
                    val id = java.util.UUID.randomUUID().toString()
                    graph.ownTasks.saveOwnTask(id, task)
                    Log.i(TAG, "own-add: ${TaskItem.id(Source.Own, id)}")
                }
            }
            "own-done" -> intent.getStringExtra("task")?.let { graph.ownTasks.finishOwnTask(it) }.also { Log.i(TAG, "own-done") }
            "own-reopen" -> intent.getStringExtra("task")?.let { graph.ownTasks.reopenOwnTask(it) }.also { Log.i(TAG, "own-reopen") }
            "own-delete" -> intent.getStringExtra("task")?.let { id -> graph.ownTasks.deleteOwnTask(id).also { Log.i(TAG, "own-delete: $id") } }
            "forget-task" -> {
                val id = intent.getStringExtra("task")?.takeIf { it.startsWith("own:") }
                if (id == null) {
                    Log.w(TAG, "forget-task needs --es task own:<id>: only a task of your own, never a source's record")
                } else {
                    graph.conversation.forgetAsks(id)
                    graph.log.update { log -> log.copy(completions = log.completions.filterNot { it.taskId == id }, slots = log.slots.filterNot { it.taskId == id }) }
                    graph.ownTasks.deleteOwnTask(id)
                    Log.i(TAG, "forget-task: $id gone, with its log")
                }
            }
            "quick-minutes" -> {
                val task = intent.getStringExtra("task")?.let { id -> graph.tasks.value.tasks.firstOrNull { it.id == id } }
                val minutes = intent.getIntExtra("minutes", -1)
                when {
                    task == null -> Log.w(TAG, "quick-minutes needs --es task <id>")
                    minutes == 0 -> graph.instructions.takeBackQuick(task.id, ChangeType.Minutes).also { Log.i(TAG, "quick-minutes: taken back") }
                    minutes in OwnTasks.MINUTES -> {
                        val (change, text) = com.thomaswcode.decrastination.ui.minutesChange(task, minutes)
                        graph.instructions.addOwnChange(java.util.UUID.randomUUID().toString(), text, About(taskId = task.id, taskTitle = task.title), change)
                        Log.i(TAG, "quick-minutes: ${task.title} takes $minutes min")
                    }
                    else -> Log.w(TAG, "quick-minutes needs --ei minutes 1..${OwnTasks.MINUTES.last} (0 takes it back)")
                }
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
                    // Every job's reader once, through a client made as the gateway makes one, each at its job's
                    // timeout, to the stand-in with a dummy key: the readings with a task of each job, the rest
                    // with made-up words.
                    val stand = ModelGateway.build("dummy-key-for-a-stand-in", base)
                    fun client(job: ModelEffort.Job) = stand.withOptions { it.timeout(ModelEffort.timeout(job)) }
                    val now = graph.clock.now()
                    val zone = graph.clock.zone()
                    suspend fun check(job: ModelEffort.Job, call: suspend () -> String) =
                        Log.i(TAG, "ai-check $job: ${runCatching { call() }.getOrElse { "failed: $it" }}")
                    val model = ClaudeEnricher(client(ModelEffort.Job.Email), zone)
                    for (job in Enrichments.Job.entries) {
                        val task = graph.tasks.value.tasks.firstOrNull { Enrichments.jobFor(it) == job } ?: continue
                        val result = runCatching { model.enrich(task, job, now) }
                        val said = result.fold({ "${it.enrichment} (cost ${"%.4f".format(it.costUsd)} USD, refused ${it.refused})" }, { "failed: $it" })
                        Log.i(TAG, "ai-check $job on '${task.title}': $said")
                    }
                    val words = "A check of the plumbing."
                    check(ModelEffort.Job.Chat) { ChatReader(client(ModelEffort.Job.Chat), zone).answer(listOf(ChatMessage("ai-check", Speaker.You, words, now)), emptyList(), emptyList(), ChatPrompts.Context(words, ""), now).let { "answered, reading ${it.reading != null}" } }
                    check(ModelEffort.Job.Felix) { FelixCaller(client(ModelEffort.Job.Felix)).say(words, "", words).let { "answered, message ${it.answer != null}" } }
                    check(ModelEffort.Job.Instruction) { InstructionReader(client(ModelEffort.Job.Instruction), zone).read(Instruction("ai-check", words, at = now), emptyList(), emptyList(), now).let { "answered, reading ${it.reading != null}" } }
                    check(ModelEffort.Job.Review) { ClaudeReviewer(client(ModelEffort.Job.Review)).review(words).let { "answered, note ${it.answer != null}" } }
                    check(ModelEffort.Job.Tidy) { MemoryTidier(client(ModelEffort.Job.Tidy)).tidy(listOf(Fact("check", words, now)), now, zone).let { "answered, tidied ${it.answer != null}" } }
                    check(ModelEffort.Job.MicroPlan) { MicroPlanner(client(ModelEffort.Job.MicroPlan)).plan("", words).let { "answered, text ${it.text != null}" } }
                    check(ModelEffort.Job.PhotoCheck) { PhotoChecker(client(ModelEffort.Job.PhotoCheck)).check(listOf(byteArrayOf(1, 2, 3)), words).let { "answered, verdict ${it.verdict != null}" } }
                    check(ModelEffort.Job.PhotoToTask) { PhotoTaskReader(client(ModelEffort.Job.PhotoToTask)).read(byteArrayOf(1, 2, 3), now, zone).let { "answered, reading ${it.reading != null}" } }
                    check(ModelEffort.Job.Summary) { SummaryWriter(client(ModelEffort.Job.Summary)).write("", words).let { "answered, text ${it.text != null}" } }
                    check(ModelEffort.Job.Research) { WebResearcher(client(ModelEffort.Job.Research)).look("a check", words, now, zone).let { "answered, found ${it.found != null}" } }
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

        /** The commands that take nothing: the debug page's buttons (AR-03). */
        val PLAIN = listOf("sync", "enrich", "briefing", "review", "calendar", "protection", "backup-now")
    }
}
