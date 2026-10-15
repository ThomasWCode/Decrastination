package com.thomaswcode.decrastination.enrich

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan
import kotlinx.coroutines.sync.Mutex

/**
 * Work done ahead (AC-10), after each plan: Felix's next message written before it's due, so it's
 * sent the moment it is; and today's and tomorrow's pieces' first steps (AI-15), each written once
 * for its task as it is.
 */
object Ahead {
    /** One run of the micro-plans at a time: a plan made meanwhile is caught up with by the next. */
    private val planning = Mutex()

    suspend fun run(graph: AppGraph, plan: Plan, now: Long) {
        // The piece of his next message first, so what he writes ahead can say its first action.
        com.thomaswcode.decrastination.learn.FelixRules.upcoming(plan, graph.settings.value, now)?.first?.slot?.chunk?.key?.let { microPlans(graph, plan, now, only = it) }
        graph.felix.draftAhead(plan, now)
        microPlans(graph, plan, now)
    }

    /**
     * The pieces due a micro-plan ([MicroPlans.due]) given one, a call each (counted under the cap
     * and held by a rest), at most [MicroPlans.MAX_A_RUN] a run; one declined or unread is kept
     * empty, so it isn't paid for again till its task changes. Those for days gone let go.
     */
    private suspend fun microPlans(graph: AppGraph, plan: Plan, now: Long, only: String? = null) {
        if (!planning.tryLock()) return
        try {
            graph.runtime.update { it.copy(microPlans = MicroPlans.kept(plan, it.microPlans)) }
            val caller = graph.microPlanner() ?: return
            val tasks = graph.tasks.value.tasks.associateBy { it.id }
            for ((chunk, task) in MicroPlans.due(plan, tasks, graph.runtime.value.microPlans).filter { only == null || it.first.key == only }.take(MicroPlans.MAX_A_RUN)) {
                if (!graph.modelAvailable()) return
                val profile = graph.profile(now).text
                val called = graph.callModel(interactive = false, task = task, cost = { r: MicroPlanner.Result -> r.costUsd to r.refused }) { caller.plan(profile, MicroPlans.describe(chunk, task)) }
                val made = when (called) {
                    is AppGraph.Called.Answered -> called.value.text?.let { MicroPlans.parse(it, chunk, task, now) } ?: MicroPlans.none(chunk, task, now)
                    is AppGraph.Called.Failed -> {
                        Log.w(AppGraph.TAG, "A piece's first steps failed", called.error)
                        return
                    }
                    is AppGraph.Called.Held -> return
                }
                graph.runtime.update { it.copy(microPlans = it.microPlans + (chunk.key to made)) }
            }
        } finally {
            planning.unlock()
        }
    }
}
