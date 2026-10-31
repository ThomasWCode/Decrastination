package com.thomaswcode.decrastination.plan

import com.thomaswcode.decrastination.core.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The plan made again whenever anything it depends on changes ([changes]: the tasks, the settings,
 * your instructions, the calendar, the school calendar, and today's slot starts and the
 * calibration), once they've been quiet [quietMs]; or when [poke]d (a slot's alarm). Each new plan's
 * slots are kept first ([keep]: today's starts, so the next plan lays the day's pieces where they
 * were, which changes nothing it watches when they're as they were), then [hooks] run on it in order:
 * the blocker's plan let go, the slot alarms, your reminders and their alarm, the calendar write, the
 * widget, and the work done ahead (PL-13). Android-free but for what it's given.
 */
@OptIn(FlowPreview::class)
class PlanWatcher(
    private val scope: CoroutineScope,
    private val changes: Flow<*>,
    private val plan: () -> Plan,
    private val keep: suspend (Plan) -> Unit,
    private val hooks: List<suspend (Plan) -> Unit>,
    private val onFailure: (Throwable) -> Unit = {},
    private val quietMs: Long = QUIET_MS,
) {
    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Watches from now on: a first plan once things are quiet, then one after each change. */
    fun start(): Job = scope.launch {
        merge(changes.map { }, pokes).debounce(quietMs).collect { run() }
    }

    /** Plans again (once things are quiet), whatever's changed: a slot begun or over. */
    fun poke() {
        pokes.tryEmit(Unit)
    }

    private suspend fun run() {
        val made = runCatching { plan() }.onFailure(onFailure).getOrNull() ?: return
        runCatching { keep(made) }.onFailure(onFailure)
        for (hook in hooks) runCatching { hook(made) }.onFailure(onFailure)
    }

    companion object {
        /** How long what the plan depends on is quiet before it's made again: a sync's writes come together. */
        const val QUIET_MS = 2_000L
    }
}
