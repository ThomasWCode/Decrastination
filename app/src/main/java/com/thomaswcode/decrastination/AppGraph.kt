package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.DeviceClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.KeystoreCipher
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Held while the settings and their pending changes are changed: two files, written in turn,
     * so each change finishes before the next reads them.
     */
    private val changing = Mutex()

    /**
     * Changes the settings as [change] says, from what's been asked for (the settings with what's
     * waiting applied), so a change to one field leaves the others' waiting changes be.
     */
    suspend fun changeSettings(change: (Settings) -> Settings) = changing.withLock {
        // What's waiting is counted up to now first, so a new change's wait starts now.
        applyDue(force = true)
        val state = runtime.value
        val proposed = change(SettingsChanges.requested(settings.value, state.pending))
        val outcome = SettingsChanges.propose(settings.value, proposed, state.pending, clock.now()) { java.util.UUID.randomUUID().toString() }
        // Nothing was waiting: the count starts now, whatever an old mark says.
        val mark = if (state.pending.isEmpty()) clock.uptime() else state.uptimeMark ?: clock.uptime()
        // The pending list first: stopped between the two, what applies now is lost (and seen to
        // be), but a waiting change this one replaced can't come back.
        runtime.update { it.copy(pending = outcome.pending, uptimeMark = if (outcome.pending.isEmpty()) null else mark) }
        settings.update { outcome.settings }
    }

    /**
     * Counts the uptime since the last count towards the pending changes and applies those whose
     * wait is over. Called every half minute; it writes (and so counts) only every few minutes, or
     * when a change falls due, unless [force]d.
     */
    suspend fun applyDueChanges(force: Boolean = false) {
        if (runtime.value.pending.isEmpty()) return
        changing.withLock { applyDue(force) }
    }

    /** [applyDueChanges], with [changing] held. */
    private suspend fun applyDue(force: Boolean) {
        val state = runtime.value
        if (state.pending.isEmpty()) return
        val uptime = clock.uptime()
        val elapsed = SettingsChanges.counting(state.pending, state.uptimeMark, uptime, force) ?: return
        val outcome = SettingsChanges.applyDue(settings.value, state.pending, clock.now(), elapsed)
        val waiting = outcome.pending.map { it.id }.toSet()
        val due = state.pending.filter { it.id !in waiting }
        // The settings first: stopped before the pending list is saved, a change that fell due is
        // applied again (to the same value), never lost.
        if (due.isNotEmpty()) settings.update { latest -> due.fold(latest, SettingsChanges::apply) }
        runtime.update { it.copy(pending = outcome.pending, uptimeMark = if (outcome.pending.isEmpty()) null else uptime) }
    }

    /** Drops the pending change [id]: what it would loosen stays as it is. Never waits, since it tightens. */
    suspend fun cancelChange(id: String) = changing.withLock {
        runtime.update { state ->
            val rest = state.pending.filterNot { it.id == id }
            state.copy(pending = rest, uptimeMark = if (rest.isEmpty()) null else state.uptimeMark)
        }
    }

    /** Applies the pending change [id] now (a parent's code allowed it), if it's still waiting. */
    suspend fun applyNow(id: String) = changing.withLock {
        val change = runtime.value.pending.firstOrNull { it.id == id } ?: return@withLock
        // Saved before its pending entry goes, as in [applyDue].
        settings.update { SettingsChanges.apply(it, change) }
        runtime.update { state ->
            val rest = state.pending.filterNot { it.id == id }
            state.copy(pending = rest, uptimeMark = if (rest.isEmpty()) null else state.uptimeMark)
        }
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
        // Work a source confirms done earns free time and is logged.
        syncer.addListener { focus.rewardCompletions() }
        // Any a stop left ungiven.
        scope.launch { focus.rewardCompletions() }
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

    companion object {
        const val TAG = "Decrastination"

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L
        private const val WIDGET_DEBOUNCE_MS = 1_000L

        // It holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context).also { instance = it }
        }
    }
}
