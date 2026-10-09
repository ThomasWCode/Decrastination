package com.thomaswcode.decrastination

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Sessions
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.withEnrichment
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.Backup
import com.thomaswcode.decrastination.data.Backups
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
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.enrich.EnrichWorker
import com.thomaswcode.decrastination.enrich.Enricher
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.enrich.ModelAlerts
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.Briefing
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.net.UrlConnectionHttp
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.protect.Watchdog
import com.thomaswcode.decrastination.sources.anki.AnkiRules
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
        // Still to be read as it will be: never yet, changed since, or the rules' reading while the
        // model is there to upgrade it. An email archived meanwhile waits for it.
        unread = { task -> Enrichments.stale(task, modelAvailable(), RuleEnricher.BY) },
    )

    /** The plan now, from the stored tasks and settings. Cheap: dozens of tasks. */
    /** The calendar's busy time and per-day loads (Phase 5), as last read and judged. */
    @Volatile
    /** The calendar as the planner counts it, from its last read: a plan made before that read is dropped with it. */
    var calendarTime: com.thomaswcode.decrastination.learn.EventJudge.Time = com.thomaswcode.decrastination.learn.EventJudge.Time(emptyList(), emptyMap(), emptyList())
        set(value) {
            field = value
            focus.forgetPlan()
        }

    fun plan(state: TaskState = tasks.value, settings: Settings = this.settings.value, now: Long = clock.now()): Plan =
        Planner.plan(
            Planner.Input(
                tasks = state.tasks,
                now = now,
                zone = clock.zone(),
                settings = settings,
                calibration = runtime.value.calibration,
                busy = calendarTime.busy,
                dayLoads = calendarTime.dayLoads,
            ),
        )

    /** The blocker's state and decisions (block/Focus.kt). */
    val focus = Focus(tasks, settings, runtime, log, clock) { state, s, now -> plan(state, s, now) }

    /** Applies a settings change: at once, or, once armed, pending if it loosens blocking. */
    /**
     * Held while the settings and their pending changes are changed: two files, written in turn,
     * so each change finishes before the next reads them.
     */
    private val changing = Mutex()

    /**
     * Held for each call to the model, from checking it's under the cap to recording what it cost:
     * the enrichment, the weekly review and the photo check can't together pass the cap.
     */
    val modelCalls = Mutex()

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
                "busy" -> TEAMS_BUSY
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

    /** The calendar announces each change; it's read again once they stop. */
    private val calendarChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var calendarWatched = false

    /**
     * Watches the calendar for changes, once it may be read: at start, and at each read of it
     * after (so allowing it later, in Setup or Android's settings, starts the watching too).
     */
    @Synchronized
    fun watchCalendar() {
        if (calendarWatched || !CalendarTime.allowed(app)) return
        runCatching {
            app.contentResolver.registerContentObserver(
                android.provider.CalendarContract.Events.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        calendarChanged.tryEmit(Unit)
                    }
                },
            )
        }.onSuccess { calendarWatched = true }.onFailure { Log.w(TAG, "Can't watch the calendar", it) }
    }

    init {
        // Disarmed (once the wait is over, or at once by a parent's code): the device admin goes
        // too, so uninstalling is allowed again, as the protection screen says. Checked at start as
        // well as on each change, so a disarm the app stopped before seeing through is finished,
        // and so is an arming left part-way (the admin given, then the app stopped mid-wizard).
        scope.launch {
            var starting = true
            settings.state.map { it.armed }.distinctUntilChanged().collect { armed ->
                val wasArmed = runtime.value.adminArmed
                if (armed && !wasArmed) runtime.update { it.copy(adminArmed = true) }
                if (!armed && (wasArmed || (starting && Watchdog.isAdminActive(app)))) {
                    runCatching { app.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Watchdog.admin(app)) }
                    runtime.update { it.copy(adminArmed = false) }
                }
                starting = false
            }
        }
        // Work a source confirms done earns free time, is logged and asked about.
        syncer.addListener {
            settleCompletions()
            CalendarTime.refresh(app)
        }
        // Any a stop left ungiven: completions, and sessions' endings.
        scope.launch {
            focus.finishSessions()
            focus.finishPhotos()
            settleCompletions()
        }
        // "How was it?" questions kept while notifications were off, once they're on.
        syncer.addAfterEverySync { Assessment.askLater(app) }
        // What's new or changed, even in place, is enriched.
        syncer.addAfterEverySync {
            val modelOn = modelAvailable()
            val modelOff = claudeKey() == null
            if (tasks.value.tasks.any { Enrichments.jobFor(it) != null && Enrichments.stale(it, modelOn, RuleEnricher.BY, modelOff) }) EnrichWorker.enqueue(app)
        }
        scope.launch { CalendarTime.refresh(app) }
        scope.launch { calendarChanged.debounce(CALENDAR_QUIET_MS).collect { CalendarTime.refresh(app) } }
        // Anki's deadline or textbook changed (saved, or a waiting change fallen due): its tasks are
        // made with them, so it's read again now.
        scope.launch {
            settings.state.map { it.ankiDeadlineMin to it.ankiTextbook }.distinctUntilChanged().drop(1).collect { SyncWorker.syncNow(app, setOf(Source.Anki)) }
        }
        // Switched off: the rules go over what the model read, as its reading goes with it.
        scope.launch {
            settings.state.map { it.aiEnabled && it.aiKeyActive }.distinctUntilChanged().drop(1).collect { on -> if (!on) EnrichWorker.enqueue(app) }
        }
        // Able to be asked again (switched on, its key put to use, a higher cap or another exchange
        // rate saved, its rest after a failure over), the model goes over what only the rules have seen.
        scope.launch {
            combine(settings.state, runtime.state) { _, _ -> modelAvailable() }.distinctUntilChanged().drop(1).collect { on -> if (on) EnrichWorker.enqueue(app) }
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
        // A key problem standing from before, whose alert couldn't be shown then.
        reconcileAlerts()
        // Dropped plans' warnings kept in step with the tasks, from the start.
        scope.launch {
            tasks.state.debounce(WIDGET_DEBOUNCE_MS).collect { state ->
                runCatching { droppedAlerts(state.tasks) }.onFailure { Log.w(TAG, "Dropped-plan warnings failed", it) }
            }
        }
        // Redraw the widget whenever what it shows may have changed.
        scope.launch {
            combine(tasks.state, settings.state, runtime.state) { _, _, _ -> }.drop(1).debounce(WIDGET_DEBOUNCE_MS).collect {
                runCatching { WidgetUpdater.update(app) }.onFailure { Log.w(TAG, "Widget update failed", it) }
            }
        }
    }

    /**
     * The completions syncs saved: free time and the log, then "How was it?" for finished homework
     * and revision (asked, or kept till notifications show), and only then off the queue, so a stop
     * part-way loses neither. A session on one ends with it, and so do its notification and alarm.
     */
    private suspend fun settleCompletions() {
        val stopped = focus.rewardCompletions { completed ->
            runCatching { Assessment.ask(app, completed) }.onFailure { Log.w(TAG, "Couldn't ask how the work went", it) }
        }
        if (stopped) Sessions.clear(app)
    }

    /** The rules' enrichment: always there, and the fallback for the model. */
    private val rules = RuleEnricher()

    /** A backup of what the sources can't give back, as text ([Backups]). */
    fun exportBackup(): String = Backups.encode(
        Backup(
            exportedAt = clock.now(),
            versionName = BuildConfig.VERSION_NAME,
            // As asked for: a change still waiting its delay is in it, and restored goes through
            // the same wait (or, restored here, keeps waiting) rather than being lost.
            settings = SettingsChanges.requested(settings.value, runtime.value.pending),
            log = log.value,
            calibration = runtime.value.calibration,
            eventAnswers = runtime.value.eventAnswers,
            aiUsage = runtime.value.aiUsage,
        ),
    )

    /**
     * Restores the backup [text] and says what happened. The settings go through [changeSettings],
     * so once armed a loosening one waits. The log, the calibration and the calendar answers are
     * restored only while unarmed: armed, an edited file could teach the planner to plan less.
     */
    suspend fun importBackup(text: String): String {
        val backup = Backups.decode(text) ?: return "That isn't a Decrastination backup (or it's from a newer version)."
        val armed = settings.value.armed
        changeSettings { Backups.importedSettings(it, backup) }
        // This month's spend on Claude, armed or not: it can only rise, so the cap isn't given again.
        val month = AiUsage.monthOf(clock.now(), clock.zone())
        runtime.update { it.copy(aiUsage = Backups.mergeUsage(it.aiUsage, backup.aiUsage, month)) }
        // The reminders' alarms at the restored times, as a save in Settings sets them.
        Daily.schedule(app)
        val waiting = runtime.value.pending.size
        val waits = if (waiting > 0) " $waiting change${if (waiting == 1) "" else "s"} that loosen blocking wait ${settings.value.loosenDelayHours} hours." else ""
        if (armed) return "Settings restored.$waits Protection is armed, so the log and what the app learned were left as they are."
        log.update { Backups.mergeLog(it, backup.log, clock.now()) }
        // Your answers on this phone win over the backup's.
        runtime.update { it.copy(calibration = backup.calibration, eventAnswers = backup.eventAnswers + it.eventAnswers) }
        // Read with the restored answers, and today's record made again, as an answer given here does.
        scope.launch {
            CalendarTime.refresh(app)
            Briefing.replanToday(app)
        }
        return "Restored the settings, ${backup.log.completions.size} completions and ${backup.log.sessions.size} sessions, what the app had learned, and your calendar answers.$waits"
    }

    /**
     * Debug only (`ai-endpoint`): where the model's calls go instead of Anthropic, a stand-in on the
     * PC, so its failures and answers can be tried on the phone through the real paths without a
     * paid call. Never stored: a restart forgets it.
     */
    @Volatile
    var modelEndpoint: String? = null

    /** [modelEndpoint], said loudly each time a client is made with it. */
    private fun endpoint(): String? = modelEndpoint?.also { Log.w(TAG, "Claude's calls go to $it, not Anthropic (debug ai-endpoint)") }

    /** The photo check, while Claude is switched on and has its key; else null. */
    fun photoChecker(): PhotoChecker? = claudeKey()?.let { PhotoChecker(it, endpoint()) }

    /** The model's weekly review, while Claude is switched on and has its key; else null. */
    fun modelReviewer(): ClaudeReviewer? = claudeKey()?.let { ClaudeReviewer(it, endpoint()) }

    /** Claude's API key, while Claude is switched on and the key is in use; else null. */
    fun claudeKey(): String? {
        val s = settings.value
        if (!s.aiEnabled || !s.aiKeyActive) return null
        return secrets[Secret.AnthropicApiKey]?.takeIf { it.isNotBlank() }
    }

    fun modelEnricher(): Enricher? = claudeKey()?.let { ClaudeEnricher(it, clock.zone(), endpoint()) }

    /**
     * A failed model call, counted in [month]: the model rests ([ModelHold.Resting]), and one showing
     * that the key or the account can't be used ([KeyProblem]) is alerted, once a stretch of it,
     * not at each hourly retry. The rules stand in meanwhile.
     */
    suspend fun modelFailed(error: Throwable, month: String, at: Long) {
        val problem = KeyProblem.of(error)
        val after = runtime.update { state -> state.copy(aiUsage = state.aiUsage.forMonth(month).failure(error.message ?: error.javaClass.simpleName, at, problem)) }
        alertKeyProblem(after.aiUsage)
        // Once its hour's rest is over, it's tried again on what's still the rules' (a key topped up
        // or put right meanwhile works then), rather than at whatever next changes.
        EnrichWorker.retryAfter(app, ModelHold.REST_MS + RETRY_SLACK_MS)
    }

    /**
     * Shows the alerts not shown yet, a key problem and dropped plans, as notifications may have
     * come on since (allowed, or the channel switched back on): at the start, whenever the app comes
     * to the front (back from notification settings), and once the permission is granted.
     */
    fun reconcileAlerts() {
        scope.launch {
            runCatching {
                alertKeyProblem(runtime.value.aiUsage)
                droppedAlerts(tasks.value.tasks)
            }.onFailure { Log.w(TAG, "Showing waiting alerts failed", it) }
        }
    }

    /**
     * The dropped-plan warnings in step with [all]: those whose task no longer has one withdrawn
     * (done or gone, which the enrichment loop never sees again, or planned since), and any not
     * shown yet shown, once notifications can show it.
     */
    private suspend fun droppedAlerts(all: List<TaskItem>) {
        val standing = ModelAlerts.standing(all)
        ModelAlerts.withdrawExcept(app, standing.mapTo(HashSet()) { it.first.id })
        val shown = ModelAlerts.unshown(standing, runtime.value.droppedAlerted).filter { (task, why) -> ModelAlerts.dropped(app, task, why) }
        runtime.update { state ->
            val kept = state.droppedAlerted.filterKeys { id -> standing.any { it.first.id == id } }
            state.copy(droppedAlerted = kept + shown.mapNotNull { (task, _) -> task.enrichment?.let { task.id to it.inputHash } })
        }
    }

    /**
     * [usage]'s key problem alerted, if it hasn't been yet and can be seen: one that couldn't
     * (notifications off) is tried again at the next failed call and when the app starts.
     */
    private suspend fun alertKeyProblem(usage: AiUsage) {
        val problem = usage.keyProblem?.takeIf { usage.keyAlertDue } ?: return
        if (ModelAlerts.keyProblem(app, problem)) runtime.update { it.copy(aiUsage = it.aiUsage.alerted(problem)) }
    }

    /** A model call that went through, counted in [month]: its cost, and the key working again if it wasn't. */
    suspend fun modelWorked(costUsd: Double, refused: Boolean, month: String, at: Long) {
        var mended = false
        runtime.update { state ->
            val before = state.aiUsage.forMonth(month)
            mended = before.keyProblem != null
            state.copy(aiUsage = before.record(costUsd, refused, at))
        }
        if (mended) ModelAlerts.keyFixed(app)
    }

    /**
     * What stops the model being asked now ([ModelHold]), or null: off, resting after a failed
     * call, or no room under the cap. Each call checks it under [modelCalls] just before sending.
     */
    fun modelHold(): ModelHold? {
        val s = settings.value
        return ModelHold.of(claudeKey() != null, runtime.value.aiUsage, clock.now(), clock.zone(), s.aiMonthlyCapUsd)
    }

    /**
     * Whether the model can be asked now: on with its key in use, not resting after a failed call,
     * and with room under this month's cap for another. When it can't, what only the rules have
     * seen isn't due for it, so it isn't redone every sync while the cap is used up.
     */
    fun modelAvailable(): Boolean = modelHold() == null

    /**
     * Enriches every task that's new or changed since it was last enriched, and, while the model is
     * on, those only the rules have seen; soonest due first. The model does it while it's on and
     * the month's spend leaves room under the cap, at most [MAX_MODEL_CALLS] a run; the rules do
     * the rest, and stand in for a call that fails (the model is then left alone for the run) or
     * that the model declines (recorded as the model's, so it isn't asked again).
     */
    suspend fun enrichNow(model: Enricher? = modelEnricher(), only: String? = null) {
        // Not while it rests after a failed call (no network, a bad key), nor with no room under the
        // cap: then what only the rules have seen isn't due for it.
        var enricher = model.takeIf { modelAvailable() }
        var calls = 0
        var decksChanged = false
        val candidates = tasks.value.tasks
            // [only]: that task alone (the debug `enrich-task`), so nothing else is sent anywhere.
            .filter { only == null || it.id == only }
            .mapNotNull { task -> Enrichments.jobFor(task)?.let { task to it } }
            .filter { (task, _) -> Enrichments.stale(task, enricher != null, RuleEnricher.BY, modelOff = claudeKey() == null) }
            .sortedBy { (task, _) -> task.dueAt ?: Long.MAX_VALUE }
        for ((snapshot, _) in candidates) {
            // Read afresh: a sync since the run began may have closed or changed it.
            val task = tasks.value.tasks.firstOrNull { it.id == snapshot.id } ?: continue
            // Being worked on in a focus session: its steps stay till the session ends, or the
            // step it's on would be gone when it does. It's done at the next run after.
            if (focus.session?.taskId == task.id) continue
            val job = Enrichments.jobFor(task) ?: continue
            if (!Enrichments.stale(task, enricher != null, RuleEnricher.BY, modelOff = claudeKey() == null)) continue
            val now = clock.now()
            // Switched off (or its key removed) while this runs: nothing more is sent.
            if (claudeKey() == null) enricher = null
            var enrichment = if (enricher != null && calls < MAX_MODEL_CALLS) modelCalls.withLock call@{
                // Checked again under the lock, just before sending, so every other call counts:
                // switched off, resting after a failed call (one made while this waited), or no
                // room under the cap. The rules then do the rest of the run.
                if (modelHold() != null) {
                    enricher = null
                    return@call null
                }
                calls++
                // Counted in the month it's made in, as the cap was checked for: it may have
                // waited for the lock past midnight.
                val at = clock.now()
                val month = AiUsage.monthOf(at, clock.zone())
                val result = runCatching { enricher!!.enrich(task, job, now) }
                    .onFailure { error ->
                        Log.w(TAG, "The model's enrichment failed; the rules stand in", error)
                        modelFailed(error, month, at)
                        enricher = null
                    }
                    .getOrNull()
                result?.let { r -> modelWorked(r.costUsd, r.refused, month, at) }
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
            // Laid only over the task as it was read (changed during the call, the next run does it),
            // and not over one a session started on during the call: its steps stay till it ends.
            var laid = false
            tasks.update { state ->
                state.copy(
                    tasks = state.tasks.map {
                        if (it.id == task.id && Enrichments.inputHash(it) == made.inputHash && focus.session?.taskId != task.id) it.withEnrichment(made).also { laid = true } else it
                    },
                )
            }
            if (!laid) continue
            // A plan of the model's dropped (out of range, not adding up) is said once it's laid,
            // by droppedAlerts, as the tasks change.
            // Its deck tasks take their sections and due date from it.
            val after = task.withEnrichment(made)
            val sections = AnkiRules.sectionsOf(after)
            if (sections != AnkiRules.sectionsOf(task) || (sections.isNotEmpty() && after.dueAt != task.dueAt)) decksChanged = true
        }
        // Sections the deck pattern missed, or a deadline moved: their deck tasks come from reading Anki again, now.
        if (decksChanged) SyncWorker.syncNow(app, setOf(Source.Anki))
    }

    companion object {
        const val TAG = "Decrastination"

        /** Past the model's hour of rest, so the retry finds it over. */
        private const val RETRY_SLACK_MS = 60_000L

        /** [requestTeamsSync]'s answer when the widget is syncing already: as good as one started. */
        const val TEAMS_BUSY = "The Teams widget is already syncing"

        /** The widget sends a change for each step of a sync; read once they've stopped. */
        private const val TEAMS_QUIET_MS = 5_000L
        private const val WIDGET_DEBOUNCE_MS = 1_000L

        private const val CALENDAR_QUIET_MS = 5_000L

        /** After a failed call, the model is left alone this long. */

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
