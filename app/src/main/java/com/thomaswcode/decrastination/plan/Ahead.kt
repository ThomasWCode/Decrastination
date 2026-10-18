package com.thomaswcode.decrastination.plan

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.MicroPlanner
import com.thomaswcode.decrastination.enrich.MicroPlans
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.felix.FelixRules
import kotlinx.coroutines.sync.Mutex

/**
 * Work done ahead (AC-10), after each plan: Felix's next message written before it's due, so it's
 * sent the moment it is; and today's and tomorrow's pieces' first steps (AI-15), each written once
 * for its task as it is.
 */
class Ahead(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val gateway: ModelGateway,
    /** Who the student is, as each call sends it (AI-05). */
    private val profile: (Long) -> Profile.Block,
    /** Felix's next message written ahead ([com.thomaswcode.decrastination.felix.Looks.draftAhead]). */
    private val draftAhead: suspend (Plan, Long) -> Unit,
    /** The plan made again (its watcher poked), so its calendar events carry the first steps written. */
    private val replan: () -> Unit,
) {
    /** One run of the micro-plans at a time: a plan made meanwhile is caught up with by the next. */
    private val planning = Mutex()

    /** A run that found another under way: once that ends, the plan's made again, so the newer plan's pieces are seen to. */
    @Volatile
    private var missed = false

    suspend fun run(plan: Plan, now: Long) {
        // The piece of his next message first, so what he writes ahead can say its first action.
        FelixRules.upcoming(plan, settings.value, now)?.first?.slot?.chunk?.key?.let { microPlans(plan, now, only = it) }
        draftAhead(plan, now)
        microPlans(plan, now)
    }

    /**
     * The pieces due a micro-plan ([MicroPlans.due]) given one, a call each (counted under the cap
     * and held by a rest), at most [MicroPlans.MAX_A_RUN] a run; one declined or unread is kept
     * empty, so it isn't paid for again till its task changes. Those for days gone let go.
     */
    private suspend fun microPlans(plan: Plan, now: Long, only: String? = null) {
        if (!planning.tryLock()) {
            missed = true
            return
        }
        // Any written: the plan's made again, so its calendar events carry them.
        var written = false
        try {
            val tasks = this.tasks.value.tasks.associateBy { it.id }
            runtime.update { it.copy(microPlans = MicroPlans.kept(plan, it.microPlans, tasks)) }
            val caller = gateway.client(ModelEffort.Job.MicroPlan)?.let { MicroPlanner(it) } ?: return
            for ((chunk, task) in MicroPlans.due(plan, tasks, runtime.value.microPlans).filter { only == null || it.first.key == only }.take(MicroPlans.MAX_A_RUN)) {
                if (!gateway.available()) return
                val profile = profile(now).text
                val called = gateway.call(interactive = false, task = task, record = ModelCalls.OnRecord(ModelEffort.Job.MicroPlan, task.title) { r: MicroPlanner.Result -> r.usage to ModelCalls.outcome(r.refused, r.text == null) }, cost = { r: MicroPlanner.Result -> r.costUsd to r.refused }) { caller.plan(profile, MicroPlans.describe(chunk, task)) }
                val made = when (called) {
                    is Called.Answered -> called.value.text?.let { MicroPlans.parse(it, chunk, task, now) } ?: MicroPlans.none(chunk, task, now)
                    is Called.Failed -> {
                        Log.w(AppGraph.TAG, "A piece's first steps failed", called.error)
                        return
                    }
                    is Called.Held -> return
                }
                runtime.update { it.copy(microPlans = it.microPlans + (chunk.key to made)) }
                if (made.first.isNotEmpty()) written = true
            }
        } finally {
            planning.unlock()
            val again = missed
            missed = false
            if (written || again) replan()
        }
    }
}
