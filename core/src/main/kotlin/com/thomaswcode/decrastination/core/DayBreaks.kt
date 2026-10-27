package com.thomaswcode.decrastination.core

/**
 * A day's breaks outside as a change to them is checked (D-116): each by the time it's named by (a settings break's own
 * time, an added one's minute), [at] the minute it starts now (moved or not), each [length] minutes long; today's [begun]
 * by those names, kept as laid whatever's asked of them (D-113); and the times the day's own are named by, taken off or
 * not ([reserved]: its settings' on a day with breaks), which no break added may be.
 */
data class DayBreaks(val at: Map<Int, Int>, val length: Int, val begun: Set<Int> = emptySet(), val reserved: Set<Int> = emptySet()) {
    /** As [change], one to the day's breaks, leaves them: a break moved, put back or added at its time, one or all taken off. */
    fun with(change: Change): DayBreaks = when (change.type) {
        ChangeType.OutsideAt -> change.startMin?.let { copy(at = at + (change.outsideMin to it)) } ?: this
        ChangeType.NoOutside -> copy(at = if (change.outsideMin < 0) emptyMap() else at - change.outsideMin)
        ChangeType.OutsideAdded -> change.startMin?.let { copy(at = at + (it to it)) } ?: this
        else -> this
    }

    /** Whether a break from [start] would be over another of the day's than the one named [own] (-1 for none). */
    fun over(start: Int, own: Int = -1): Boolean = at.any { (name, minute) -> name != own && minute < start + length && start < minute + length }

    companion object {
        val NONE = DayBreaks(emptyMap(), 0)
    }
}
