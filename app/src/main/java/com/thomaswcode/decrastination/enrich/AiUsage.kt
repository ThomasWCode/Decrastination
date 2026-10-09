package com.thomaswcode.decrastination.enrich

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * What the model has cost this month (docs/data-sources.md §5): each call's price from its token
 * counts, against the cap set in Settings. Kept in `runtime.json`; a new month starts at nothing.
 */
@Serializable
data class AiUsage(
    /** The month counted, like "2026-10". */
    val month: String = "",
    val spentUsd: Double = 0.0,
    val calls: Int = 0,
    /** Calls the model declined (its safety classifiers), counted in [calls]. */
    val refused: Int = 0,
    /** Calls that failed (no network, a bad key); not counted in [calls]. */
    val failed: Int = 0,
    val lastError: String? = null,
    val lastCallAt: Long? = null,
    /** What's wrong with the key or the account, from a failed call that showed it; gone at a call that works, or a new key. */
    val keyProblem: KeyProblem? = null,
    /** When [keyProblem] began: retries finding it again keep it, so it's alerted once. */
    val keyProblemSince: Long? = null,
) {
    /** This month's, or a new one's from nothing; a key problem, not being the month's, carries over. */
    fun forMonth(month: String): AiUsage = if (month == this.month) this else AiUsage(month = month, keyProblem = keyProblem, keyProblemSince = keyProblemSince)

    fun spentGbp(usdToGbp: Double): Double = spentUsd * usdToGbp

    /** Whether one more call stays under [capGbp] even at its dearest ([Pricing.WORST_CALL_USD]). */
    fun allows(capGbp: Int, usdToGbp: Double): Boolean = (spentUsd + Pricing.WORST_CALL_USD) * usdToGbp <= capGbp

    /** A call that went through: the key works, whatever was wrong with it before. */
    fun record(costUsd: Double, refused: Boolean, at: Long): AiUsage = copy(
        spentUsd = spentUsd + costUsd,
        calls = calls + 1,
        refused = this.refused + if (refused) 1 else 0,
        lastError = null,
        lastCallAt = at,
        keyProblem = null,
        keyProblemSince = null,
    )

    /**
     * A failed call. One showing a problem with the key or the account ([problem]) begins a
     * stretch of it, or carries on the one under way; any other (no network) leaves it as it was.
     */
    fun failure(error: String, at: Long, problem: KeyProblem? = null): AiUsage = copy(
        failed = failed + 1,
        lastError = error,
        lastCallAt = at,
        keyProblem = problem ?: keyProblem,
        keyProblemSince = if (problem != null && problem != keyProblem) at else keyProblemSince,
    )

    /** Whether [after] begins a key problem this didn't have (or another one): its alert is due. */
    fun startsKeyProblem(after: AiUsage): Boolean = after.keyProblem != null && after.keyProblemSince != keyProblemSince

    /** A new key: the last one's failure and problem aren't this one's, so it's tried at once. */
    fun newKey(): AiUsage = copy(lastError = null, keyProblem = null, keyProblemSince = null)

    companion object {
        fun monthOf(now: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).toString()
    }
}
