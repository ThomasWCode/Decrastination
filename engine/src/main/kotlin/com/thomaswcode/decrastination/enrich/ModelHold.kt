package com.thomaswcode.decrastination.enrich

import java.time.ZoneId

/**
 * What stops the model being asked now, when something does. Every call checks it under
 * [ModelGateway]'s lock, just before sending, so the call before it (its failure, its cost) counts.
 */
enum class ModelHold {
    /** Switched off, or its key not (yet) in use. */
    Off,

    /** Its last call failed: it's left alone for a while by what failed (AC-04), [AiUsage.restUntil]. */
    Resting,

    /** No room under this month's cap, where one is set, for another call at its dearest. */
    Capped;

    companion object {
        /** The rest a failure of no kind given takes. */
        const val REST_MS = 3_600_000L

        /**
         * What holds the model at [now], or null if it can be asked. A rest holds what runs by
         * itself; one [interactive] (the chat, a reply, a photo check) is asked through it, as
         * someone is waiting, unless the key is what failed.
         */
        fun of(on: Boolean, usage: AiUsage, now: Long, zone: ZoneId, capUsd: Int?, interactive: Boolean = false): ModelHold? = when {
            !on -> Off
            usage.restUntil?.let { now < it } == true && (!interactive || usage.keyProblem != null) -> Resting
            !usage.forMonth(AiUsage.monthOf(now, zone)).allows(capUsd) -> Capped
            else -> null
        }

        /** The minutes of rest left at [now], at least one while resting. */
        fun restLeftMin(usage: AiUsage, now: Long): Int = usage.restUntil?.let { ((it - now + 59_999) / 60_000).toInt().coerceAtLeast(0) } ?: 0
    }
}
