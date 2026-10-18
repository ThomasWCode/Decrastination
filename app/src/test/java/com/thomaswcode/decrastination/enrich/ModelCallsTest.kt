package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.data.CallRecord
import com.thomaswcode.decrastination.data.plusCall
import kotlin.test.Test
import kotlin.test.assertEquals

/** The record of the model's calls (AC-07). */
class ModelCallsTest {
    private val read = Enrichment("h", "claude", 0)

    @Test
    fun `an enrichment's call says how it went`() {
        assertEquals("refused", ModelCalls.outcome(Enricher.Result(null, refused = true)))
        assertEquals("repaired", ModelCalls.outcome(Enricher.Result(read, repair = Enricher.Repair("{}", "Step 1 is late"))))
        assertEquals("empty", ModelCalls.outcome(Enricher.Result(null)))
        assertEquals("dropped:a step of 300 minutes", ModelCalls.outcome(Enricher.Result(read.copy(dropped = "a step of 300 minutes"))))
        assertEquals("salvaged", ModelCalls.outcome(Enricher.Result(read.copy(adjusted = listOf("'Revise': planned by the task's date")))))
        assertEquals("used", ModelCalls.outcome(Enricher.Result(read)))
        assertEquals("empty", ModelCalls.outcome(refused = false, empty = true))
    }

    @Test
    fun `the last hundred calls are kept, each said whole to copy`() {
        val call = CallRecord(at = Fixtures.at("2026-10-15T16:05"), job = "Assignment", title = "Moments Prep", effort = "high", inTokens = 1234, outTokens = 456, cacheRead = 800, latencyMs = 12_300, stop = "end_turn", outcome = "used", costUsd = 0.042)
        val kept = (1..120).fold(emptyList<CallRecord>()) { list, i -> list.plusCall(call.copy(at = call.at + i)) }
        assertEquals(CallRecord.MAX, kept.size)
        assertEquals(call.at + 120, kept.last().at)
        // A call back after one sent since goes where it began.
        val late = listOf(call.copy(at = call.at + 2)).plusCall(call.copy(at = call.at + 1))
        assertEquals(listOf(call.at + 1, call.at + 2), late.map { it.at })
        assertEquals(
            "2026-10-15 16:05 · Assignment · “Moments Prep”\nEffort high · 1234 in (800 from the cache), 456 out · 12.3 s · end_turn\nused · $0.0420",
            call.details(Fixtures.LONDON),
        )
    }
}
