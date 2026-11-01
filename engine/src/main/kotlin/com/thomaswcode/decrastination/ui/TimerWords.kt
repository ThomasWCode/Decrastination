package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.data.PieceTimer

/** What's said at a timer's zero. */
fun timerOverTitle(timer: PieceTimer): String = "Time's up: ${timer.label}"

fun timerOverText(timer: PieceTimer): String = "${Format.minutes(timer.minutes)} on it. Tick it when it's done, or stop the timer."

/** A running [timer]'s time at [now], as its piece shows it: "Timer: 12 min left", "Timer: time's up", "Timer: 5 min over". */
fun timerText(timer: PieceTimer, now: Long): String = when {
    timer.leftMin(now) > 0 -> "Timer: ${Format.minutes(timer.leftMin(now))} left"
    timer.overMin(now) == 0 -> "Timer: time's up"
    else -> "Timer: ${Format.minutes(timer.overMin(now))} over"
}
