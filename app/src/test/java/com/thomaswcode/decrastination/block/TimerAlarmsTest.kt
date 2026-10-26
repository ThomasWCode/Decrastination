package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.felix.FelixRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The timer's notifications' rules (D-112): what [TimerAlarms.sync] says and keeps, apart from Android. */
class TimerAlarmsTest {
    private val timer = PieceTimer("teams:t#0", "teams:t", 0, "Q1", "t: Q1", minutes = 25, startedAt = 1_000_000L)

    @Test
    fun `a zero gone by unseen is told within the hold, and hours on it's marked told without a word`() {
        assertTrue(TimerAlarms.timely(timer, timer.endsAt))
        assertTrue(TimerAlarms.timely(timer, FelixRules.liftAt(timer) - 1))
        // A boot the next morning after a timer left over the evening: no "Time's up" for it then.
        assertFalse(TimerAlarms.timely(timer, FelixRules.liftAt(timer)))
        assertFalse(TimerAlarms.timely(timer, timer.endsAt + 9 * 3_600_000L))
    }

    @Test
    fun `"Time's up" stays only beside the timer past its zero it's about`() {
        assertTrue(TimerAlarms.keepsTimeUp(timer.copy(told = true)))
        // A fresh timer replacing one past its zero, a stop, or its piece done: it goes.
        assertFalse(TimerAlarms.keepsTimeUp(timer))
        assertFalse(TimerAlarms.keepsTimeUp(timer.copy(told = true, stoppedAt = timer.endsAt + 60_000L)))
        assertFalse(TimerAlarms.keepsTimeUp(null))
    }
}
