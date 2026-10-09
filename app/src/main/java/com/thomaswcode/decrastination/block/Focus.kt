package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BlockRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SessionRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The blocker's state and decisions, apart from Android: what a blocked app or site meets now
 * (the plan, the policy, earned time, a focus session), and the bookkeeping that follows from
 * work being done. The focus service asks it on every app change; the block screen and the
 * plan start and stop sessions through it.
 */
class Focus(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val log: JsonStore<ActivityLog>,
    private val clock: WallClock,
    private val planOf: (TaskState, Settings, Long) -> Plan,
) {
    /** What the blocker covers. */
    sealed interface Target {
        val name: String

        data class App(override val name: String) : Target
        data class Browser(override val name: String) : Target
        data class Site(override val name: String, val browser: String) : Target
    }

    private var cachedFor: Pair<TaskState, Settings>? = null
    private var cachedAt = 0L
    private var cachedPlan: Plan? = null

    /** The plan, worked out again only when the tasks or settings change, or a minute has passed. */
    fun plan(now: Long = clock.now()): Plan {
        val key = tasks.value to settings.value
        val cached = cachedPlan
        if (cached != null && cachedFor?.first === key.first && cachedFor?.second === key.second && now - cachedAt in 0 until PLAN_TTL_MS) return cached
        return planOf(key.first, key.second, now).also {
            cachedPlan = it
            cachedFor = key
            cachedAt = now
        }
    }

    fun today(now: Long = clock.now()): LocalDate = Instant.ofEpochMilli(now).atZone(clock.zone()).toLocalDate()

    /** Whether a blocked app may be in front now. */
    fun verdict(now: Long = clock.now()): BlockPolicy.Verdict {
        val state = runtime.value
        return BlockPolicy.decide(
            BlockPolicy.Input(
                now = now,
                zone = clock.zone(),
                settings = settings.value,
                pressure = plan(now).pressure,
                creditLeftMs = state.credit.on(today(now)).leftMs,
                session = state.session,
                overrideUntil = state.overrideUntil,
                forceActiveUntil = state.forceActiveUntil,
            ),
        )
    }

    /** [pkg] as something the blocker covers outright (an app, or a browser whose sites can't be read). */
    fun target(pkg: String): Target? {
        val s = settings.value
        return when (pkg) {
            in s.blockedApps -> Target.App(pkg)
            in s.blockedBrowsers -> Target.Browser(pkg)
            else -> null
        }
    }

    fun isCheckedBrowser(pkg: String): Boolean = pkg in settings.value.checkedBrowsers

    /** The blocked site a checked browser's address bar shows, if any. */
    fun siteTarget(browser: String, addressBar: String?): Target.Site? {
        val address = addressBar?.let(Blocklist::address) ?: return null
        return Blocklist.blockedSite(address, settings.value.blockedSites)?.let { Target.Site(it, browser) }
    }

    fun creditLeftMs(now: Long = clock.now()): Long = runtime.value.credit.on(today(now)).leftMs

    suspend fun spend(ms: Long) {
        if (ms <= 0) return
        val today = today()
        runtime.update { it.copy(credit = it.credit.spend(today, ms)) }
    }

    suspend fun recordBlock(target: Target, reason: BlockPolicy.Reason) {
        val now = clock.now()
        log.update { it.copy(blocks = it.blocks + BlockRecord(now, target.name, reason.name)).trimmed(now) }
    }

    val session: FocusSession? get() = runtime.value.session

    /** Starts a session on [taskId]'s chunk; one already running is ended first. */
    suspend fun startSession(taskId: String, label: String, step: String?, minutes: Int): FocusSession {
        stopSession()
        val session = FocusSession(taskId, label, step, minutes.coerceIn(1, MAX_SESSION_MIN), clock.now(), clock.uptime())
        runtime.update { it.copy(session = session) }
        return session
    }

    /**
     * Ends the session: a finished one ([FocusSession.endsAt] has passed) marks its step done (or
     * adds its minutes to the task's) and earns its share of free time; one stopped early only
     * adds the minutes worked. Returns what was recorded, or null if there was no session.
     */
    suspend fun stopSession(): SessionRecord? {
        // Taken and cleared in one step, so two callers at once (the ticker, the alarm and Stop)
        // can't both finish it: the second finds none.
        var claimed: FocusSession? = null
        runtime.update { state ->
            claimed = state.session
            if (state.session == null) state else state.copy(session = null)
        }
        val session = claimed ?: return null
        val now = clock.now()
        val completed = session.isDue(now, clock.uptime())
        val worked = if (completed) session.minutes else (session.ran(now, clock.uptime()) / 60_000L).toInt().coerceIn(0, session.minutes)
        var task: TaskItem? = null
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != session.taskId) return@map t
                    val stepIndex = if (completed && session.step != null) t.subSteps.indexOfFirst { !it.done && it.title == session.step } else -1
                    val next = if (stepIndex >= 0) {
                        t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == stepIndex) s.copy(done = true) else s }, workedMin = t.workedMin + worked)
                    } else {
                        t.copy(workedMin = t.workedMin + worked)
                    }
                    next.also { task = it }
                },
            )
        }
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        runtime.update { state ->
            state.copy(credit = if (completed) state.credit.earn(today, Credit.forSession(session.minutes, ratio)) else state.credit.on(today))
        }
        val record = SessionRecord(
            taskId = session.taskId,
            kind = task?.kind ?: Kind.Admin,
            className = task?.className,
            label = session.label,
            plannedMin = session.minutes,
            workedMin = worked,
            startedAt = session.startedAt,
            endedAt = now,
            completed = completed,
        )
        log.update { it.copy(sessions = it.sessions + record).trimmed(now) }
        return record
    }

    /**
     * Work a source has just confirmed done: it's logged, and earns free time for what no
     * session counted (docs/scheduler.md §4).
     */
    suspend fun onCompleted(completed: List<TaskItem>) {
        if (completed.isEmpty()) return
        // A task confirmed done while its session runs: the session ends now, its minutes count
        // as work on the task (not as a finished session's credit as well as the completion's).
        val sessionMin = runtime.value.session?.takeIf { s -> completed.any { it.id == s.taskId } }
            ?.let { s -> stopSession()?.let { record -> s.taskId to record.workedMin } }
        val now = clock.now()
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        // Reading or archiving an email, or an event passing, isn't work that earns time.
        val earned = completed.filter { it.kind != Kind.Info && it.kind != Kind.Event }.sumOf { task ->
            val worked = task.workedMin + if (sessionMin?.first == task.id) sessionMin.second else 0
            val remaining = (task.effortMin * (1 - task.sourceProgress)).roundToInt() - worked
            Credit.forCompletion(remaining.coerceAtLeast(0), ratio)
        }
        runtime.update { it.copy(credit = it.credit.earn(today, earned)) }
        log.update { state ->
            state.copy(
                completions = state.completions + completed.filter { it.status == Status.Done }.map { task ->
                    CompletionRecord(
                        taskId = task.id,
                        title = task.title,
                        source = task.source,
                        kind = task.kind,
                        className = task.className,
                        estimateMin = task.effortMin,
                        workedMin = task.workedMin + if (sessionMin?.first == task.id) sessionMin.second else 0,
                        dueAt = task.dueAt,
                        firstSeenAt = task.firstSeenAt,
                        doneAt = task.doneAt ?: now,
                    )
                },
            ).trimmed(now)
        }
    }

    companion object {
        private const val PLAN_TTL_MS = 60_000L
        const val MAX_SESSION_MIN = 180
    }
}
