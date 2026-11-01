package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.data.PieceTimer

/** A running [timer]'s time at [now], as its piece shows it: "Timer: 12 min left", "Timer: time's up", "Timer: 5 min over". */
fun timerText(timer: PieceTimer, now: Long): String = when {
    timer.leftMin(now) > 0 -> "Timer: ${Format.minutes(timer.leftMin(now))} left"
    timer.overMin(now) == 0 -> "Timer: time's up"
    else -> "Timer: ${Format.minutes(timer.overMin(now))} over"
}
