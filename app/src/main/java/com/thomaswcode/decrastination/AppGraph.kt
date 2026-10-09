package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.Enricher
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.sources.anki.AnkiSource
import com.thomaswcode.decrastination.sources.gmail.GmailSource
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerApi
import com.thomaswcode.decrastination.sources.powerplanner.PowerPlannerSource
import com.thomaswcode.decrastination.sources.teams.TeamsProvider
import com.thomaswcode.decrastination.sources.teams.TeamsSource
import com.thomaswcode.decrastination.sync.SyncWorker
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.widget.WidgetUpdater
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's singletons. The sync, the focus service, the widget and the screens all run in the
 * app's one process and share them, so every part sees the same stores.
 */
@OptIn(FlowPreview::class)
class AppGraph private constructor(context: Context) {
    val app: Context = context.applicationContext
    val clock: WallClock = DeviceClock(app)

    /** Work that outlives the screen or receiver that started it; a failure is logged, not fatal. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(TAG, "Background work failed", error) })

    val tasks = JsonStore(File(app.filesDir, "tasks.json"), TaskState.serializer(), ::TaskState)
    val settings = JsonStore(File(app.filesDir, "settings.json"), Settings.serializer(), ::Settings)
    val secrets = SecretStore(File(app.filesDir, "secrets.bin"), KeystoreCipher())
    val runtime = JsonStore(File(app.filesDir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState)
    val log = JsonStore(File(app.filesDir, "log.json"), ActivityLog.serializer(), ::ActivityLog)

    val syncer = Syncer(
        tasks = tasks,
        settings = settings,
        sources = listOf(
            TeamsSource(app.contentResolver),
            PowerPlannerSource(PowerPlannerApi(UrlConnectionHttp()), secrets),
            GmailSource(secrets),
            AnkiSource(app),
        ),
        clock = clock,
        onFailure = { source, error -> Log.w(TAG, "Reading ${source.label} failed", error) },
    )

    /** The plan now, from the stored tasks and settings. Cheap: dozens of tasks. */
    fun plan(state: TaskState = tasks.value, settings: Settings = this.settings.value, now: Long = clock.now()): Plan =
        Planner.plan(Planner.Input(state.tasks, now, clock.zone(), settings))

    /** The blocker's state and decisions (block/Focus.kt). */
    val focus = Focus(tasks, settings, runtime, log, clock) { state, s, now -> plan(state, s, now) }

    /** Applies a settings change: at once, or, once armed, pending if it loosens blocking. */
    suspend fun changeSettings(proposed: Settings) {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDueChanges(force = true)
        val now = clock.now()
        var outcome: SettingsChanges.Outcome? = null
        runtime.update { state ->
            outcome = SettingsChanges.propose(settings.value, proposed, state.pending, now) { java.util.UUID.randomUUID().toString() }
            val waiting = outcome!!.pending
            // Nothing was waiting: the count starts now, whatever an old mark says.
            val mark = if (state.pending.isEmpty()) clock.uptime() else state.uptimeMark ?: clock.uptime()
            state.copy(pending = waiting, uptimeMark = if (waiting.isEmpty()) null else mark)
        }
        outcome?.let { result -> settings.update { result.settings } }
    }

    /**
     * Counts the uptime since the last count towards the pending changes and applies those whose
     * wait is over. Called every half minute; it writes (and so counts) only every few minutes, or
     * when a change falls due, unless [force]d.
     */
    suspend fun applyDueChanges(force: Boolean = false) {
        if (runtime.value.pending.isEmpty()) return
        val now = clock.now()
        val uptime = clock.uptime()
        var applied: SettingsChanges.Outcome? = null
        runtime.update { state ->
            if (state.pending.isEmpty()) return@update state
            val elapsed = SettingsChanges.counting(state.pending, state.uptimeMark, uptime, force) ?: return@update state
            applied = SettingsChanges.applyDue(settings.value, state.pending, now, elapsed)
            state.copy(pending = applied!!.pending, uptimeMark = if (applied!!.pending.isEmpty()) null else uptime)
        }
        applied?.let { result -> settings.update { result.settings } }
    }

    /**
     * Reads every source now, as a job (it keeps its network after the screen that asked has
     * gone). With [teams], it first asks the Teams widget to sync Teams itself, which takes over
     * the screen for a minute, so only on a tap (Q21); what that finds comes back through the
     * widget's change notifications.
     */
    fun refreshAll(teams: Boolean) {
        if (teams) {
            scope.launch {
                // Refused (its service off, already syncing): say so where the widget shows it.
                // The next read of Teams replaces the note with the widget's own state.
                requestTeamsSync()?.let { reason ->
                    tasks.update { state -> state.copy(sources = state.sources + (Source.Teams to state.status(Source.Teams).copy(note = reason))) }
                }
            }
        }
        SyncWorker.syncNow(app)
    }

    /** Asks the Teams widget to sync Teams. Returns why it didn't start, or null if it did. */
    suspend fun requestTeamsSync(): String? = withContext(Dispatchers.IO) {
        val result = runCatching { TeamsProvider.call(app.contentResolver, TeamsProvider.METHOD_REQUEST_SYNC) }.getOrNull()
        when {
            result?.getBoolean(TeamsProvider.RESULT_STARTED) == true -> null
            result == null -> "The Teams widget didn't answer"
            else -> when (result.getString(TeamsProvider.RESULT_REASON)) {
                "service_off" -> "The Teams widget's sync service is off"
                "busy" -> "The Teams widget is already syncing"
                else -> "The Teams widget didn't start a sync"
            }
        }
    }

    /**
     * The Teams widget announces every change to its list: a sync, or a hand-in it saw while
     * Teams was open. Reading it needs no network, so it's read here, once the changes stop, and
     * Anki with it: its homework decks come from Teams' assignments (Anki is read after Teams).
     */
    private val teamsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // Work a source confirms done earns free time and is logged; what's new or changed is enriched.
        syncer.addListener { report ->
            focus.onCompleted(report.completed)
            EnrichWorker.enqueue(app)
        }
        // Switched on, the model goes over what only the rules have seen.
        scope.launch {
            settings.state.map { it.aiEnabled }.distinctUntilChanged().drop(1).collect { on -> if (on) EnrichWorker.enqueue(app) }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                TeamsProvider.root,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        teamsChanged.tryEmit(Unit)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Can't watch the Teams widget", it) }
        scope.launch {
            teamsChanged.debounce(TEAMS_QUIET_MS).collect { syncer.sync(setOf(Source.Teams, Source.Anki)) }
        }
        // Redraw the widget whenever what it shows may have changed.
        scope.launch {
            combine(tasks.state, settings.state, runtime.state) { _, _, _ -> }.drop(1).debounce(WIDGET_DEBOUNCE_MS).collect {
                runCatching { WidgetUpdater.update(app) }.onFailure { Log.w(TAG, "Widget update failed", it) }
            }
        }
    }

    /** The rules' enrichment: always there, and the fallback for the model. */
    private val rules = RuleEnricher()

    /** The model, while it's switched on and has its key; else null. */
    fun modelEnricher(): Enricher? {
        val key = secrets[Secret.AnthropicApiKey]
        if (!settings.value.aiEnabled || key.isNullOrBlank()) return null
        return ClaudeEnricher(key, clock.zone())
    }

    /**
     * Enriches every task that's new or changed since it was last enriched, and, while the model is
     * on, those only the rules have seen; soonest due first. The model does it while it's on and
     * the month's spend leaves room under the cap, at most [MAX_MODEL_CALLS] a run; the rules do
     * the rest, and stand in for a call that fails (the model is then left alone for the run) or
     * that the model declines (recorded as the model's, so it isn't asked again).
     */
    suspend fun enrichNow(model: Enricher? = modelEnricher()) {
        var enricher = model
        var calls = 0
        val candidates = tasks.value.tasks
            .mapNotNull { task -> Enrichments.jobFor(task)?.let { task to it } }
            .filter { (task, _) -> Enrichments.stale(task, enricher != null, RuleEnricher.BY) }
            .sortedBy { (task, _) -> task.dueAt ?: Long.MAX_VALUE }
        for ((task, job) in candidates) {
            val now = clock.now()
            val month = AiUsage.monthOf(now, clock.zone())
            val s = settings.value
            val useModel = enricher != null && calls < MAX_MODEL_CALLS && runtime.value.aiUsage.forMonth(month).allows(s.aiMonthlyCapGbp, s.usdToGbp)
            var enrichment = if (useModel) {
                calls++
                val result = runCatching { enricher!!.enrich(task, job, now) }
                    .onFailure { error ->
                        Log.w(TAG, "The model's enrichment failed; the rules stand in", error)
                        runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).failure(error.message ?: error.javaClass.simpleName, now)) }
                        enricher = null
                    }
                    .getOrNull()
                result?.let { r -> runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, now)) } }
                when {
                    result?.enrichment != null -> result.enrichment
                    // Declined, or no answer it could read: the rules' say, under the model's name.
                    result != null -> rules.enrich(task, job, now).enrichment?.copy(by = enricher?.by ?: RuleEnricher.BY)
                    else -> null
                }
            } else {
                null
            }
            if (enrichment == null) enrichment = rules.enrich(task, job, now).enrichment
            val made = enrichment ?: continue
            tasks.update { state -> state.copy(tasks = state.tasks.map { if (it.id == task.id) it.withEnrichment(made) else it }) }
        }
    }

    companion object {
        const val TAG = "Decrastination"

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L
        private const val WIDGET_DEBOUNCE_MS = 1_000L

        /** The most model calls one enrichment run makes: the rest wait for the next. */
        const val MAX_MODEL_CALLS = 20

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
