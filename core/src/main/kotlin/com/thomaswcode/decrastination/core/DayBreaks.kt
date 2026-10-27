package com.thomaswcode.decrastination.core

/**
 * A day's breaks outside as a change to them is checked (D-116): each by the time it's named by (a settings break's own
 * time, an added one's minute), [at] the minute it starts now (moved or not), each [length] minutes long; today's [begun]
 * by those names, kept as laid whatever's asked of them (D-113).
 */
data class DayBreaks(val at: Map<Int, Int>, val length: Int, val begun: Set<Int> = emptySet()) {
    /** Whether a break from [start] would be over another of the day's than the one named [own] (-1 for none). */
    fun over(start: Int, own: Int = -1): Boolean = at.any { (name, minute) -> name != own && minute < start + length && start < minute + length }

    companion object {
        val NONE = DayBreaks(emptyMap(), 0)
    }
}
