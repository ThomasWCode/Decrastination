package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Plan

/**
 * Work done ahead (AC-10), after each plan: Felix's next message written before it's due, so it's
 * sent the moment it is. (A piece's first steps, phase 7's micro-plans, come here too.)
 */
object Ahead {
    suspend fun run(graph: AppGraph, plan: Plan, now: Long) {
        graph.felix.draftAhead(plan, now)
    }
}
