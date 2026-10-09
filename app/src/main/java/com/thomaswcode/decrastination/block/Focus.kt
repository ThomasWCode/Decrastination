package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BlockRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.EndedSession
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Rewarded
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SessionRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
                creditLeftMs = creditLeftMs(now),
                session = state.session,
                overrideUntil = state.overrideUntil,
                forceActiveUntil = state.forceActiveUntil,
                uptime = clock.uptime(),
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

    /** Spent but not yet saved ([spendSoon]). */
    private val unsavedMs = AtomicLong()

    /** Today's free time left, less what was spent a moment ago and is still being saved. */
    fun creditLeftMs(now: Long = clock.now()): Long = (runtime.value.credit.on(today(now)).leftMs - unsavedMs.get()).coerceAtLeast(0)

    suspend fun spend(ms: Long, day: LocalDate = today()) {
        if (ms <= 0) return
        runtime.update { it.copy(credit = it.credit.spend(day, ms)) }
    }

    /**
     * Takes [ms] off today's free time, saved on [scope] (the app's, which outlives the save).
     * What's left says so at once: a check made before the save lands (moving straight from one
     * blocked app to another) mustn't be given the time again.
     */
    fun spendSoon(ms: Long, scope: CoroutineScope): Job? {
        if (ms <= 0) return null
        val day = today()
        unsavedMs.addAndGet(ms)
        return scope.launch {
            try {
                spend(ms, day)
            } finally {
                unsavedMs.addAndGet(-ms)
            }
        }
    }

    suspend fun recordBlock(target: Target, reason: BlockPolicy.Reason) {
        val now = clock.now()
        log.update { it.copy(blocks = it.blocks + BlockRecord(now, target.name, reason.name)).trimmed(now) }
    }

    val session: FocusSession? get() = runtime.value.session

    /** Starts a session on [taskId]'s chunk; one already running is ended first. */
    suspend fun startSession(taskId: String, label: String, step: String?, minutes: Int): FocusSession {
        stopSession()
        // The box it's a piece of, as it is now: a review or a setting may change it while it runs.
        val kind = tasks.value.tasks.firstOrNull { it.id == taskId }?.kind ?: Kind.Admin
        val box = if (step == null || step.startsWith("part ")) runtime.value.calibration.boxMin[kind] ?: settings.value.boxMin else null
        val session = FocusSession(taskId, label, step, minutes.coerceIn(1, MAX_SESSION_MIN), clock.now(), clock.uptime(), box)
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
        // can't both finish it: the second finds none. What it's owed is saved in that same step,
        // so a stop before it's all given loses none of it.
        val now = clock.now()
        val uptime = clock.uptime()
        var ended: EndedSession? = null
        runtime.update { state ->
            val session = state.session ?: return@update state
            val completed = session.isDue(now, uptime)
            val worked = if (completed) session.minutes else (session.ran(now, uptime) / 60_000L).toInt().coerceIn(0, session.minutes)
            val end = EndedSession(session, worked, completed, now)
            ended = end
            state.copy(session = null, finishing = state.finishing + end)
        }
        return ended?.let { finish(it) }
    }

    /** Gives the sessions a stop left part-way ([RuntimeState.finishing]) what they're owed: at start-up. */
    suspend fun finishSessions() {
        runtime.value.finishing.forEach { finish(it) }
    }

    /**
     * Gives [ended] its due, each part once however often this runs: its minutes and step to its
     * task (which notes the session as counted), its record to the log (unless it's there), and
     * its free time in the step that takes it off [RuntimeState.finishing]. A finished session
     * earns free time only on its own day, as the rest of that day's.
     */
    private suspend fun finish(ended: EndedSession): SessionRecord {
        val session = ended.session
        var task: TaskItem? = null
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != session.taskId) return@map t
                    if (session.startedAt in t.sessionsCounted) return@map t.also { task = it }
                    val stepIndex = if (ended.completed && session.step != null) t.subSteps.indexOfFirst { !it.done && it.title == session.step } else -1
                    val steps = if (stepIndex >= 0) t.subSteps.mapIndexed { i, s -> if (i == stepIndex) s.copy(done = true) else s } else t.subSteps
                    t.copy(
                        subSteps = steps,
                        workedMin = t.workedMin + ended.workedMin,
                        sessionsCounted = (t.sessionsCounted + session.startedAt).takeLast(MAX_COUNTED),
                    ).also { task = it }
                },
            )
        }
        val record = SessionRecord(
            taskId = session.taskId,
            kind = task?.kind ?: Kind.Admin,
            className = task?.className,
            label = session.label,
            plannedMin = session.minutes,
            // The box its task was cut into, when the session was on a box, not a step of its own.
            box = session.box,
            workedMin = ended.workedMin,
            startedAt = session.startedAt,
            endedAt = ended.endedAt,
            completed = ended.completed,
        )
        log.update { state ->
            if (state.sessions.any { it.taskId == session.taskId && it.startedAt == session.startedAt }) state
            else state.copy(sessions = state.sessions + record).trimmed(ended.endedAt)
        }
        val now = clock.now()
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        runtime.update { state ->
            // Given already (another caller finished it first): nothing more.
            if (ended !in state.finishing) return@update state
            val earns = ended.completed && today(ended.endedAt) == today
            state.copy(
                finishing = state.finishing - ended,
                credit = if (earns) state.credit.earn(today, Credit.forSession(session.minutes, ratio)) else state.credit.on(today),
            )
        }
        return record
    }

    /**
     * A piece of [taskId] the photo check found done (Phase 5): as a finished session on it would,
     * its step is ticked (or its minutes added to the task's) and its share of free time earned.
     */
    suspend fun photoChecked(taskId: String, step: String?, minutes: Int): Boolean {
        val now = clock.now()
        // Applied only while the piece is still to do: the task open and, for a step, that step not
        // ticked meanwhile (another check, a session, a sync). Free time only for what was.
        var credited = 0
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { t ->
                    if (t.id != taskId || !t.isOpen) return@map t
                    // One of its own steps: ticked, once.
                    if (step != null && t.subSteps.any { it.title == step }) {
                        val i = t.subSteps.indexOfFirst { !it.done && it.title == step }
                        if (i < 0) return@map t
                        credited = minutes
                        return@map t.copy(subSteps = t.subSteps.mapIndexed { j, s -> if (j == i) s.copy(done = true) else s }, workedMin = t.workedMin + minutes)
                    }
                    // A piece of time (the whole task, or a part the planner cut it into): counted
                    // against what's left of its estimate, so the same work can't be counted past it.
                    val left = (t.effortMin * (1 - t.sourceProgress.coerceIn(0.0, 1.0))).roundToInt() - t.workedMin
                    if (left <= 0) return@map t
                    credited = minOf(minutes, left)
                    t.copy(workedMin = t.workedMin + credited)
                },
            )
        }
        if (credited > 0) runtime.update { it.copy(credit = it.credit.earn(today(now), Credit.forSession(credited, settings.value.workMinPerFreeMin))) }
        return credited > 0
    }

    /**
     * Gives what's owed for the completions syncs confirmed ([TaskState.unrewarded]), then clears
     * them. Stopped before that, they're given next time (after the next sync, or when the app
     * starts), and [onCompleted] gives each only once. Says whether it ended a running session.
     */
    suspend fun rewardCompletions(): Boolean {
        val waiting = tasks.value.unrewarded
        if (waiting.isEmpty()) return false
        val stopped = onCompleted(waiting)
        tasks.update { state -> state.copy(unrewarded = state.unrewarded.filterNot { t -> waiting.any { it.id == t.id && it.doneAt == t.doneAt } }) }
        return stopped
    }

    /**
     * Work a source confirmed done: free time for what was left of it, a record in the log, and
     * an end to a session still running on it. Each is given once per completion however often
     * it's offered: the free time with a ledger of what's had it ([RuntimeState.rewarded]), written
     * in the same step; the record only if the log hasn't got it. Says whether it ended a session.
     */
    suspend fun onCompleted(completed: List<TaskItem>): Boolean {
        if (completed.isEmpty()) return false
        // A task confirmed done while its session runs: the session ends now, its minutes count
        // as work on the task (not as a finished session's credit as well as the completion's).
        val sessionMin = runtime.value.session?.takeIf { s -> completed.any { it.id == s.taskId } }
            ?.let { s -> stopSession()?.let { record -> s.taskId to record.workedMin } }
        val now = clock.now()
        val today = today(now)
        val ratio = settings.value.workMinPerFreeMin
        // What was worked on it, a session just ended included: as stored, where it is (a session
        // ended on an earlier try is there already).
        fun worked(task: TaskItem): Int = tasks.value.tasks.firstOrNull { it.id == task.id }?.workedMin
            ?: (task.workedMin + if (sessionMin?.first == task.id) sessionMin.second else 0)
        fun key(task: TaskItem) = Rewarded(task.id, task.doneAt ?: task.lastSeenAt)
        runtime.update { state ->
            val fresh = completed.filter { key(it) !in state.rewarded }
            // Reading or archiving an email, or an event passing, isn't work that earns time. Free
            // time is for the day the work was confirmed: given late (the app stopped first), after
            // that day is over, it has gone as the rest of that day's has.
            val earned = fresh.filter { it.kind != Kind.Info && it.kind != Kind.Event && today(it.doneAt ?: now) == today }.sumOf { task ->
                val remaining = (task.effortMin * (1 - task.sourceProgress)).roundToInt() - worked(task)
                Credit.forCompletion(remaining.coerceAtLeast(0), ratio)
            }
            state.copy(
                credit = state.credit.earn(today, earned),
                rewarded = (state.rewarded + fresh.map(::key)).distinct().filter { it.doneAt > now - REWARDED_KEPT_MS },
            )
        }
        log.update { state ->
            val unrecorded = completed.filter { it.status == Status.Done }
                .filter { task -> state.completions.none { it.taskId == task.id && it.doneAt == (task.doneAt ?: now) } }
            state.copy(
                completions = state.completions + unrecorded.map { task ->
                    CompletionRecord(
                        taskId = task.id,
                        title = task.title,
                        source = task.source,
                        kind = task.kind,
                        className = task.className,
                        estimateMin = task.effortMin,
                        workedMin = worked(task),
                        dueAt = task.dueAt,
                        firstSeenAt = task.firstSeenAt,
                        doneAt = task.doneAt ?: now,
                    )
                },
            ).trimmed(now)
        }
        return sessionMin != null
    }

    companion object {
        private const val PLAN_TTL_MS = 60_000L

        /** How many sessions a task remembers counting: far more than one piece of work has. */
        private const val MAX_COUNTED = 100

        /** How long a completion stays in the ledger of those rewarded: far longer than any retry. */
        private const val REWARDED_KEPT_MS = 14 * 24 * 3_600_000L
        const val MAX_SESSION_MIN = 180
    }
}
