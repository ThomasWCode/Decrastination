package com.thomaswcode.decrastination.enrich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Calls that overlap (AC-05) and the rest after a failure (AC-04). */
class AiUsageOrderTest {
    private val usage = AiUsage(month = "2026-10")

    @Test
    fun `a call sent before a failure doesn't end the rest that failure set`() {
        val failed = usage.failure("busy", at = 2_000, restMs = 300_000, kind = Failure.RateLimit)
        val older = failed.record(0.01, refused = false, at = 1_000)
        assertEquals(302_000L, older.restUntil)
        assertEquals(1, older.failuresInRow)
        assertEquals(1, older.calls)
        // One sent after it does.
        assertNull(failed.record(0.01, refused = false, at = 3_000).restUntil)
    }

    @Test
    fun `a failure with no rest of its own leaves a longer one standing`() {
        val resting = usage.failure("busy", at = 1_000, restMs = 300_000, kind = Failure.RateLimit)
        assertEquals(301_000L, resting.failure("no network", at = 2_000, restMs = 0, kind = Failure.Network).restUntil)
        assertEquals(902_000L, resting.failure("busy", at = 2_000, restMs = 900_000, kind = Failure.RateLimit).restUntil)
    }
}
