package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Source
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** The client against a stand-in for the API: what it sends, and what it makes of the answers. */
class ClaudeEnricherTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun reply(text: String?, stop: String = "end_turn", model: String = "claude-opus-5-5"): MockResponse {
        val content = if (text == null) JsonArray(emptyList()) else JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))))
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive("msg_test"),
                "type" to JsonPrimitive("message"),
                "role" to JsonPrimitive("assistant"),
                "model" to JsonPrimitive(model),
                "content" to content,
                "stop_reason" to JsonPrimitive(stop),
                "stop_sequence" to kotlinx.serialization.json.JsonNull,
                "usage" to JsonObject(mapOf("input_tokens" to JsonPrimitive(1_200), "output_tokens" to JsonPrimitive(300))),
            ),
        )
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    private fun enricher() = ClaudeEnricher(ModelGateway.build("test-key", server.url("").toString().trimEnd('/')), LONDON)

    @Test
    fun `it asks Opus 5 point 5 at high effort for the schema's answer, with fallbacks on`() = runBlocking {
        server.enqueue(reply("""{"kind":"Admin","actionableFrom":null,"deadline":"2026-10-12T08:30","effortMin":10,"nextStep":"Sign the form"}"""))
        val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("test-key", request.getHeader("x-api-key"))
        assertTrue(ClaudeEnricher.FALLBACK_BETA in request.getHeader("anthropic-beta").orEmpty(), request.headers.toString())
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(16_000, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val config = body["output_config"]!!.jsonObject
        assertEquals("high", config["effort"]!!.jsonPrimitive.content)
        val format = config["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("kind", "actionableFrom", "deadline", "deadlineQuoted", "effortMin", "nextStep", "question", "reason", "blocks", "summary"), format["schema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("triage one email" in body["system"].toString(), body["system"].toString().take(120))
        assertTrue("Subject: Berlin trip form" in body["messages"].toString())
        // No thinking setting: Opus 5.5 always thinks, and rejects one that turns it off.
        assertNull(body["thinking"])

        val e = result.enrichment!!
        assertEquals("claude-opus-5-5", e.by)
        assertEquals(Fixtures.at("2026-10-12T08:30"), e.deadline)
        assertEquals(0.0108, result.costUsd, 1e-9)
    }

    @Test
    fun `an answer that fails a check is put back once, with the problem, and read again`() = runBlocking {
        val bad = """{"effortMin":200,"blocks":[{"title":"A","minutes":30,"from":null,"due":null}]}"""
        server.enqueue(reply(bad))
        val task = assignment("Revise for the mock")
        val first = enricher().enrich(task, Enrichments.Job.Effort, NOW)
        server.takeRequest()
        // Meanwhile, its reading as it can be: the total planned whole.
        assertEquals("The blocks add up to 30 minutes, not the 200 effortMin gives", first.repair!!.complaint)
        assertEquals(200, first.enrichment!!.effortMin)
        server.enqueue(reply("""{"effortMin":60,"blocks":[{"title":"A","minutes":30,"from":null,"due":null},{"title":"B","minutes":30,"from":null,"due":null}]}"""))
        val second = enricher().repair(task, Enrichments.Job.Effort, NOW, first.repair!!)
        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        fun said(turn: JsonElement): String = turn.jsonObject["content"]!!.let { c -> if (c is JsonPrimitive) c.content else c.jsonArray.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content } }
        assertEquals(bad, said(messages[1]))
        assertTrue(said(messages[2]).startsWith("Your answer had a problem: The blocks add up to 30 minutes"), said(messages[2]))
        assertTrue(second.repaired)
        assertEquals(listOf("A", "B"), second.enrichment!!.subSteps!!.map { it.title })
        // Wrong again: dropped, said so.
        server.enqueue(reply(bad))
        val again = enricher().repair(task, Enrichments.Job.Effort, NOW, first.repair!!)
        assertTrue(again.repaired)
        assertEquals("the blocks add up to 30 minutes, not the 200 effortMin gives", again.enrichment!!.dropped)
        // One that passes isn't put back.
        server.enqueue(reply("""{"effortMin":35,"blocks":[]}"""))
        assertNull(enricher().enrich(task, Enrichments.Job.Effort, NOW).repair)
    }

    @Test
    fun `a refusal is counted, and says nothing`() = runBlocking {
        server.enqueue(reply(null, stop = "refusal"))
        val result = enricher().enrich(email(), Enrichments.Job.Email, NOW)
        assertTrue(result.refused)
        assertNull(result.enrichment)
        assertEquals(0.0108, result.costUsd, 1e-9)
    }

    @Test
    fun `an answer cut off by its token limit is not trusted`() = runBlocking {
        server.enqueue(reply("""{"subSteps":[{"title":"Pa""", stop = "max_tokens"))
        assertNull(enricher().enrich(assignment("1. a\n2. b"), Enrichments.Job.Assignment, NOW).enrichment)
    }

    @Test
    fun `the API's refusal of a key, or of the account, is read from the SDK's exception`() = runBlocking {
        fun error(status: Int, type: String, message: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"$type","message":"$message"}}""")
        suspend fun failure(): Throwable = runCatching { enricher().enrich(email(), Enrichments.Job.Email, NOW) }.exceptionOrNull()!!
        server.enqueue(error(401, "authentication_error", "invalid x-api-key"))
        assertEquals(KeyProblem.Rejected, KeyProblem.of(failure()))
        server.enqueue(error(403, "permission_error", "Your API key does not have permission to use the specified resource."))
        assertEquals(KeyProblem.NotAllowed, KeyProblem.of(failure()))
        server.enqueue(error(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."))
        assertEquals(KeyProblem.NoCredit, KeyProblem.of(failure()))
        server.enqueue(error(400, "invalid_request_error", "messages: at least one message is required"))
        assertNull(KeyProblem.of(failure()))
    }

    @Test
    fun `a fallback model's answer is priced at its own rates`() = runBlocking {
        server.enqueue(reply("""{"effortMin":30}""", model = "claude-opus-4-8"))
        val item = assignment("Revise").copy(source = Source.PowerPlanner, id = "powerplanner:p")
        val result = enricher().enrich(item, Enrichments.Job.Effort, NOW)
        assertEquals(30, result.enrichment!!.effortMin)
        assertEquals(0.0135, result.costUsd, 1e-9)
        // And recorded as the fallback's, not the model asked for.
        assertEquals("claude-opus-4-8", result.enrichment!!.by)
    }
}
