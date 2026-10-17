package com.thomaswcode.decrastination.block

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Earned free time for one day (docs/spec/blocking-and-protection.md; about a minute per three of work, Q22).
 * Earned whenever work is done; spendable only when nothing is planned for today, and only
 * while a blocked app is in front. What's left at midnight is gone.
 */
@Serializable
data class Credit(
    val day: String = "",
    val earnedMs: Long = 0,
    val spentMs: Long = 0,
    /** The day a day with nothing to do was given its free time (BL-07), so it's given once. */
    val allowanceOn: String = "",
) {

    val leftMs: Long get() = (earnedMs - spentMs).coerceAtLeast(0)

    /** This credit as of [today]: yesterday's is gone. */
    fun on(today: LocalDate): Credit = if (day == today.toString()) this else Credit(today.toString())

    /**
     * Whether the ledger has moved on past [day]: a write for it that lands late (a save begun
     * before midnight) changes nothing, as that day's credit has gone, and mustn't reset the newer.
     */
    private fun past(day: LocalDate): Boolean = this.day.isNotEmpty() && LocalDate.parse(this.day).isAfter(day)

    fun earn(today: LocalDate, minutes: Double): Credit =
        if (past(today)) this else on(today).let { it.copy(earnedMs = it.earnedMs + (minutes * 60_000).roundToLong()) }

    /** [minutes] of what was earned [today] given back (a keep undone, UI-03): no more than is left unspent. */
    fun takeBack(today: LocalDate, minutes: Double): Credit =
        if (past(today)) this else on(today).let { it.copy(earnedMs = (it.earnedMs - (minutes * 60_000).roundToLong()).coerceAtLeast(it.spentMs)) }

    fun spend(today: LocalDate, ms: Long): Credit =
        if (past(today)) this else on(today).let { it.copy(spentMs = (it.spentMs + ms).coerceAtMost(it.earnedMs)) }

    companion object {
        /** A kept slot of [minutes] earns a third of them (with the default ratio), BL-08. */
        fun forSlot(minutes: Int, workMinPerFreeMin: Int): Double = minutes.toDouble() / workMinPerFreeMin.coerceAtLeast(1)

        /**
         * A task the source confirms done earns its remaining estimate at the same rate, the work
         * no kept slot counted, up to 30 minutes (docs/spec/blocking-and-protection.md's ceiling). There's no floor:
         * with one, archiving a pile of emails would earn five minutes each.
         */
        fun forCompletion(remainingMin: Int, workMinPerFreeMin: Int): Double =
            (remainingMin.toDouble() / workMinPerFreeMin.coerceAtLeast(1)).coerceIn(0.0, 30.0)
    }
}
