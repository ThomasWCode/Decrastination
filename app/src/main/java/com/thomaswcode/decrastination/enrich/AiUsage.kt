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
) {
    fun forMonth(month: String): AiUsage = if (month == this.month) this else AiUsage(month = month)

    fun spentGbp(usdToGbp: Double): Double = spentUsd * usdToGbp

    /** Whether one more call stays under [capGbp] even at its dearest ([Pricing.WORST_CALL_USD]). */
    fun allows(capGbp: Int, usdToGbp: Double): Boolean = (spentUsd + Pricing.WORST_CALL_USD) * usdToGbp <= capGbp

    fun record(costUsd: Double, refused: Boolean, at: Long): AiUsage =
        copy(spentUsd = spentUsd + costUsd, calls = calls + 1, refused = this.refused + if (refused) 1 else 0, lastError = null, lastCallAt = at)

    fun failure(error: String, at: Long): AiUsage = copy(failed = failed + 1, lastError = error, lastCallAt = at)

    companion object {
        fun monthOf(now: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).toString()
    }
}
