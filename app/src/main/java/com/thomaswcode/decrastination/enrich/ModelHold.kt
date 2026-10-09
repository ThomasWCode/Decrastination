package com.thomaswcode.decrastination.enrich

import java.time.ZoneId

/**
 * What stops the model being asked now, when something does. Every call checks it under
 * `AppGraph.modelCalls`, just before sending, so the call before it (its failure, its cost) counts.
 */
enum class ModelHold {
    /** Switched off, or its key not (yet) in use. */
    Off,

    /** Its last call failed (no connection, a bad key): it's left alone for [REST_MS] after. */
    Resting,

    /** No room under this month's cap, where one is set, for another call at its dearest. */
    Capped;

    companion object {
        const val REST_MS = 3_600_000L

        /** What holds the model at [now], or null if it can be asked. */
        fun of(on: Boolean, usage: AiUsage, now: Long, zone: ZoneId, capUsd: Int?): ModelHold? = when {
            !on -> Off
            usage.lastError != null && now - (usage.lastCallAt ?: 0L) < REST_MS -> Resting
            !usage.forMonth(AiUsage.monthOf(now, zone)).allows(capUsd) -> Capped
            else -> null
        }
    }
}
