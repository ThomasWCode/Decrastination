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
    /** When [keyProblem] began: retries finding it again keep it. */
    val keyProblemSince: Long? = null,
    /**
     * Whether [keyProblem]'s alert has been shown. Not while notifications can't show it: then it's
     * tried again at the next failed call and when the app starts, so it's seen once it can be.
     */
    val keyAlerted: Boolean = false,
    /** Resting after a failed call till then (AC-04), by what failed and how many in a row; null when it isn't. */
    val restUntil: Long? = null,
    val failuresInRow: Int = 0,
    /** The last failure's kind ([Failure]), for Setup. */
    val lastFailureClass: String? = null,
    /** When the calls of the last hour failed: past [Failures.MAX_AN_HOUR] of them, an hour's rest. */
    val failedAt: List<Long> = emptyList(),
    /** When the latest call that went through was sent: one sent before it failing since says nothing new (AC-05). */
    val lastWorkedAt: Long? = null,
) {
    /** This month's, or a new one's from nothing; a key problem, not being the month's, carries over. */
    fun forMonth(month: String): AiUsage =
        if (month == this.month) this else AiUsage(
            month = month, keyProblem = keyProblem, keyProblemSince = keyProblemSince, keyAlerted = keyAlerted,
            // A rest, and the failures it's counted from, aren't the month's either.
            restUntil = restUntil, failuresInRow = failuresInRow, lastFailureClass = lastFailureClass, failedAt = failedAt,
            lastWorkedAt = lastWorkedAt,
        )

    /**
     * Where a call's outcome is counted (AC-05): its own month's, or a newer one's should that be
     * counted since (calls overlapping a month's end), so the record never goes back a month, losing
     * the newer one's spend and its cap with it.
     */
    fun forCall(month: String): AiUsage = forMonth(maxOf(month, this.month))

    /**
     * Whether one more call stays under [capUsd] even at its dearest ([Pricing.WORST_CALL_USD]), with
     * [inFlight] calls sent and not back yet counted at theirs (AC-05); with no cap, always.
     */
    fun allows(capUsd: Int?, inFlight: Int = 0): Boolean = capUsd == null || spentUsd + (inFlight + 1) * Pricing.WORST_CALL_USD <= capUsd

    /**
     * A call sent at [at] that went through: the key works, whatever was wrong with it before; but
     * one sent before a failure since (calls overlap, AC-05) is counted only, the rest and the key
     * problem that failure found standing.
     */
    fun record(costUsd: Double, refused: Boolean, at: Long): AiUsage {
        val counted = copy(
            spentUsd = spentUsd + costUsd, calls = calls + 1, refused = this.refused + if (refused) 1 else 0,
            lastCallAt = maxOf(lastCallAt ?: at, at), lastWorkedAt = maxOf(lastWorkedAt ?: at, at),
        )
        if (failedAt.any { it > at } || (keyProblemSince ?: Long.MIN_VALUE) > at) return counted
        return counted.copy(lastError = null, keyProblem = null, keyProblemSince = null, keyAlerted = false, restUntil = null, failuresInRow = 0)
    }

    /** Whether a call sent at [at] is older than one that has gone through since (calls overlap, AC-05). */
    fun stale(at: Long): Boolean = (lastWorkedAt ?: Long.MIN_VALUE) > at

    /**
     * A failed call, sent at [at]. One showing a problem with the key or the account ([problem])
     * begins a stretch of it, or carries on the one under way; any other (no network) leaves it as
     * it was. One older than a call that has gone through since ([stale]) is counted only: that
     * call showed the model works, so no rest, streak or key problem comes of it.
     */
    fun failure(error: String, at: Long, problem: KeyProblem? = null, restMs: Long = ModelHold.REST_MS, kind: Failure? = null): AiUsage = if (stale(at)) copy(failed = failed + 1) else copy(
        failed = failed + 1,
        lastError = error,
        lastCallAt = at,
        // A rest under way that's longer stands: another call's failure set it.
        restUntil = listOfNotNull(restUntil?.takeIf { it > at }, (at + restMs).takeIf { restMs > 0 }).maxOrNull(),
        failuresInRow = failuresInRow + 1,
        lastFailureClass = kind?.name ?: lastFailureClass,
        failedAt = (failedAt + at).filter { at - it < Failures.HOUR_MS },
        keyProblem = problem ?: keyProblem,
        keyProblemSince = if (problem != null && problem != keyProblem) at else keyProblemSince,
        keyAlerted = if (problem != null && problem != keyProblem) false else keyAlerted,
    )

    /** A key problem whose alert hasn't been shown yet. */
    val keyAlertDue: Boolean get() = keyProblem != null && !keyAlerted

    /** [problem]'s alert shown, if it's still the one standing. */
    fun alerted(problem: KeyProblem): AiUsage = if (keyProblem == problem) copy(keyAlerted = true) else this

    /** A new key: the last one's failure and problem aren't this one's, so it's tried at once. */
    fun newKey(): AiUsage = copy(lastError = null, keyProblem = null, keyProblemSince = null, keyAlerted = false, restUntil = null, failuresInRow = 0)

    /** The rest after [kind] failing at [at] (AC-04): by its kind and the failures in a row, and an hour past [Failures.MAX_AN_HOUR] an hour. */
    fun restAfter(kind: Failure, at: Long, retryAfterMs: Long? = null): Long {
        val rest = Failures.restMs(kind, failuresInRow + 1, retryAfterMs)
        val lastHour = failedAt.count { at - it < Failures.HOUR_MS } + 1
        return if (lastHour > Failures.MAX_AN_HOUR) maxOf(rest, Failures.HOUR_MS) else rest
    }

    companion object {
        fun monthOf(now: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).toString()
    }
}
