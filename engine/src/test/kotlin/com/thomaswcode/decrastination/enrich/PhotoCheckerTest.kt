package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PhotoCheckerTest {
    @Test
    fun `the photo goes as a JPEG image block with the piece, and the verdict is read`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val text = """{"done":true,"confidence":1.4,"reason":"All eight answers are written out."}"""
            val body = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("msg"), "type" to JsonPrimitive("message"), "role" to JsonPrimitive("assistant"), "model" to JsonPrimitive("claude-opus-5-5"),
                    "content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                    "stop_reason" to JsonPrimitive("end_turn"), "stop_sequence" to JsonNull,
                    "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_600), "output_tokens" to JsonPrimitive(100))),
                ),
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
            val result = PhotoChecker(ModelGateway.build("test-key", server.url("").toString().trimEnd('/'))).check(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)), "The piece of work: Chapter 17 review, part 2 of 3, 30 min")
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val content = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            // A page each, then what they're of (AI-17).
            assertEquals(listOf("image", "image", "text"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            val image = content.first().jsonObject
            assertEquals("image/jpeg", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
            assertEquals("AQID", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            assertTrue("Chapter 17 review, part 2 of 3" in content[2].toString())
            assertEquals(true, result.verdict!!.done)
            // A confidence past 1 is read as 1.
            assertEquals(1.0, result.verdict!!.confidence)
            assertTrue("Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done" in PhotoChecker.system())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the readings the plan is made from think hard, the answers someone waits for less`() {
        val high = ModelEffort.Job.entries.filter { ModelEffort.of(it) == OutputConfig.Effort.HIGH }
        assertEquals(listOf("Email", "Assignment", "Effort", "Instruction", "Review", "PhotoToTask"), high.map { it.name })
        assertEquals(OutputConfig.Effort.MEDIUM, ModelEffort.of(ModelEffort.Job.Chat))
        assertEquals(OutputConfig.Effort.HIGH, ModelEffort.of(Enrichments.Job.Assignment))
    }

    @Test
    fun `a failed call is told by what failed`() {
        assertEquals(Failure.RateLimit, Failures.classify(429, key = false))
        assertEquals(Failure.Overloaded, Failures.classify(529, key = false))
        assertEquals(Failure.Server, Failures.classify(500, key = false))
        assertEquals(Failure.Timeout, Failures.classify(408, key = false))
        assertEquals(Failure.BadRequest, Failures.classify(400, key = false))
        assertEquals(Failure.Key, Failures.classify(401, key = false))
        assertEquals(Failure.Key, Failures.classify(400, key = true))
        assertEquals(Failure.Network, Failures.classify(RuntimeException(UnknownHostException("api.anthropic.com"))))
        assertEquals(Failure.Timeout, Failures.classify(SocketTimeoutException("read timed out")))
        assertEquals(Failure.Unknown, Failures.classify(IllegalStateException("odd")))
    }

    @Test
    fun `the rest after a failure goes by what failed and how many in a row`() {
        val minute = 60_000L
        assertEquals(listOf(5, 15, 60).map { it * minute }, (1..3).map { Failures.restMs(Failure.RateLimit, it) })
        assertEquals(listOf(15, 60).map { it * minute }, (1..2).map { Failures.restMs(Failure.Server, it) })
        assertEquals(0L, Failures.restMs(Failure.Network, 1))
        assertEquals(0L, Failures.restMs(Failure.BadRequest, 4))
        assertEquals(60 * minute, Failures.restMs(Failure.Key, 1))
        // A longer Retry-After stands.
        assertEquals(20 * minute, Failures.restMs(Failure.RateLimit, 1, retryAfterMs = 20 * minute))
        // Three failures already this hour: an hour, whatever the fourth was.
        val at = Fixtures.at("2026-10-12T17:00")
        val busy = AiUsage(failedAt = listOf(at - 50 * minute, at - 30 * minute, at - 10 * minute))
        assertEquals(60 * minute, busy.restAfter(Failure.Network, at))
        assertEquals(0L, AiUsage(failedAt = listOf(at - 70 * minute)).restAfter(Failure.Network, at))
        // Counted in a row till a call works.
        val twice = AiUsage().failure("busy", at, restMs = 5 * minute, kind = Failure.RateLimit).failure("busy", at + 6 * minute, restMs = 15 * minute, kind = Failure.RateLimit)
        assertEquals(2 to (at + 21 * minute), twice.failuresInRow to twice.restUntil)
        assertEquals(0 to null, twice.record(0.01, false, at + 30 * minute).let { it.failuresInRow to it.restUntil })
    }

    @Test
    fun `a rest holds what runs by itself, not what someone waits for, unless it's the key`() {
        val at = Fixtures.at("2026-10-12T17:00")
        val resting = AiUsage(restUntil = at + 600_000L)
        assertEquals(ModelHold.Resting, ModelHold.of(true, resting, at, LONDON, null))
        assertEquals(null, ModelHold.of(true, resting, at, LONDON, null, interactive = true))
        assertEquals(ModelHold.Resting, ModelHold.of(true, resting.copy(keyProblem = KeyProblem.Rejected), at, LONDON, null, interactive = true))
        assertEquals(null, ModelHold.of(true, resting, at + 600_000L, LONDON, null))
        assertEquals(10, ModelHold.restLeftMin(resting, at))
    }

    @Test
    fun `how hard it thinks is said by job, the plan's readings high`() {
        val table = ModelEffort.table().toMap()
        assertEquals(listOf("High", "Medium"), ModelEffort.table().map { it.first })
        assertTrue("emails" in table.getValue("High") && "the chat" in table.getValue("Medium"), table.toString())
    }

    @Test
    fun `a call from a month gone by is counted in the newer month, never taking the record back`() {
        val november = AiUsage("2026-11", spentUsd = 3.0, calls = 2)
        val late = november.forCall("2026-10").record(0.5, refused = false, at = Fixtures.at("2026-11-01T00:00"))
        assertEquals(Triple("2026-11", 3.5, 3), Triple(late.month, late.spentUsd, late.calls))
        // A newer month's own starts at nothing, as ever.
        assertEquals("2026-11" to 0.0, AiUsage("2026-10", spentUsd = 9.0).forCall("2026-11").let { it.month to it.spentUsd })
    }

    @Test
    fun `a failure older than a call that has gone through since brings no rest`() {
        val worked = AiUsage("2026-10").record(0.1, refused = false, at = NOW + 2_000)
        val late = worked.failure("503", NOW + 1_000, KeyProblem.Rejected, restMs = 15 * 60_000L)
        assertEquals(null to null, late.restUntil to late.keyProblem)
        assertEquals(0 to 1, late.failuresInRow to late.failed)
        // A newer one's failure rests it as ever.
        assertEquals(NOW + 3_000 + 15 * 60_000L, worked.failure("503", NOW + 3_000, restMs = 15 * 60_000L).restUntil)
    }

    @Test
    fun `the cap counts the calls still in flight at their dearest`() {
        // Room under a $10 cap for two more calls at their dearest: one in flight leaves room for one, two for none.
        val worst = Pricing.WORST_CALL_USD
        val usage = AiUsage(spentUsd = 10 - 2 * worst - 0.0001)
        assertTrue(usage.allows(10, inFlight = 0))
        assertTrue(usage.allows(10, inFlight = 1))
        assertFalse(usage.allows(10, inFlight = 2))
        assertTrue(AiUsage(spentUsd = 1_000.0).allows(null, inFlight = 9))
    }
}
