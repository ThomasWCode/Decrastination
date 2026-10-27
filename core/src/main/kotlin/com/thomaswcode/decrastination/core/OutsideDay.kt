package com.thomaswcode.decrastination.core

/**
 * A day's breaks outside as you and Felix gave them (D-116): those [moved] to a time (by their own time, `outsideTimes`'
 * minute, to the minute they're laid at), those [off] (by their own time), all of the day's settings breaks off
 * ([noneAtAll]), and those [added] (by their minute), the latest change for a break winning.
 */
data class OutsideDay(
    val moved: Map<Int, Int> = emptyMap(),
    val off: Set<Int> = emptySet(),
    val noneAtAll: Boolean = false,
    val added: List<Int> = emptyList(),
) {
    companion object {
        val NONE = OutsideDay()
    }
}
