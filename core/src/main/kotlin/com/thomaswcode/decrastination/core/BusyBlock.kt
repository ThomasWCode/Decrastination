package com.thomaswcode.decrastination.core

import java.time.LocalDate

/**
 * A busy time of yours on its [date], from [startMin] to [endMin] (D-117), named by its [title] where you or Felix gave
 * it one: written to your calendar as an event of its own, to block the time out.
 */
data class BusyBlock(val date: LocalDate, val startMin: Int, val endMin: Int, val title: String?) {
    /** Its key, by its times: one event a day for each. */
    val key: String get() = "$BUSY$startMin-$endMin"

    companion object {
        const val BUSY = "busy:"
    }
}
