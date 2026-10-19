package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.MicroPlan
import kotlinx.serialization.Serializable

/** What calling Claude keeps (the gateway's; the micro-plans its callers write ahead): the month's cost, the last calls, what was refused or warned of. Kept in `model.json`. */
@Serializable
data class ModelState(
    /** What the model has cost this month. */
    val aiUsage: AiUsage = AiUsage(),
    /** The model's last calls (AC-07), oldest first: [CallRecord.MAX] of them. */
    val calls: List<CallRecord> = emptyList(),
    /** Tasks whose call the API wouldn't take as asked (AC-04), with the input it refused: sent again once that changes. */
    val enrichFailed: Map<String, String> = emptyMap(),
    /**
     * Dropped-plan warnings shown: each task's id, and the content (its enrichment's input hash) of
     * the plan it was about. One not here (laid while notifications couldn't show it) is shown once
     * they can; a later plan dropped for changed content is another warning.
     */
    val droppedAlerted: Map<String, String> = emptyMap(),
    /** Today's and tomorrow's pieces' first steps (AI-15), by piece key: written ahead, as the plan has them. */
    val microPlans: Map<String, MicroPlan> = emptyMap(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)

/**
 * One model call as it went (AC-07): when, its job and what it was about, how hard it thought, its
 * tokens (in, out, and read from the cache), how long it took, why it stopped, how it went (used,
 * repaired, salvaged, dropped:<why>, refused, failed:<why>, empty), and what it cost.
 */
@Serializable
data class CallRecord(
    val at: Long,
    val job: String,
    val title: String? = null,
    val effort: String,
    val inTokens: Long = 0,
    val outTokens: Long = 0,
    val cacheRead: Long = 0,
    val latencyMs: Long = 0,
    val stop: String? = null,
    val outcome: String,
    val costUsd: Double = 0.0,
) {
    /** All of it as the record's row shows it opened, to copy. */
    fun details(zone: java.time.ZoneId): String = buildString {
        append(java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime().withNano(0).toString().replace('T', ' '))
        append(" · ").append(job)
        title?.let { append(" · “").append(it).append('”') }
        append('\n').append("Effort ").append(effort).append(" · ").append(inTokens).append(" in")
        if (cacheRead > 0) append(" (").append(cacheRead).append(" from the cache)")
        append(", ").append(outTokens).append(" out · ").append("%.1f s".format(java.util.Locale.UK, latencyMs / 1000.0))
        stop?.let { append(" · ").append(it) }
        append('\n').append(outcome).append(" · ").append("$%.4f".format(java.util.Locale.UK, costUsd))
    }

    companion object {
        /** The most kept: the last hundred. */
        const val MAX = 100
    }
}

/** These calls and [call], by when each began (one comes back after another sent since), as many as are kept. */
fun List<CallRecord>.plusCall(call: CallRecord): List<CallRecord> = (this + call).sortedBy { it.at }.takeLast(CallRecord.MAX)
