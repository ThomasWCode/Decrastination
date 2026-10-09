package com.thomaswcode.decrastination.block

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Earned free time for one day (docs/scheduler.md §4; about a minute per three of work, Q22).
 * Earned whenever work is done; spendable only when nothing is due today or tomorrow, and only
 * while a blocked app is in front. What's left at midnight is gone.
 */
@Serializable
data class Credit(val day: String = "", val earnedMs: Long = 0, val spentMs: Long = 0) {

    val leftMs: Long get() = (earnedMs - spentMs).coerceAtLeast(0)

    /** This credit as of [today]: yesterday's is gone. */
    fun on(today: LocalDate): Credit = if (day == today.toString()) this else Credit(today.toString())

    fun earn(today: LocalDate, minutes: Double): Credit = on(today).let { it.copy(earnedMs = it.earnedMs + (minutes * 60_000).roundToLong()) }

    fun spend(today: LocalDate, ms: Long): Credit = on(today).let { it.copy(spentMs = (it.spentMs + ms).coerceAtMost(it.earnedMs)) }

    companion object {
        /** A finished focus session of [minutes] earns a third of them (with the default ratio). */
        fun forSession(minutes: Int, workMinPerFreeMin: Int): Double = minutes.toDouble() / workMinPerFreeMin.coerceAtLeast(1)

        /**
         * A task the source confirms done earns its remaining estimate at the same rate, the work
         * no session counted, up to 30 minutes (docs/scheduler.md §4's ceiling). There's no floor:
         * with one, archiving a pile of emails would earn five minutes each.
         */
        fun forCompletion(remainingMin: Int, workMinPerFreeMin: Int): Double =
            (remainingMin.toDouble() / workMinPerFreeMin.coerceAtLeast(1)).coerceIn(0.0, 30.0)
    }
}
