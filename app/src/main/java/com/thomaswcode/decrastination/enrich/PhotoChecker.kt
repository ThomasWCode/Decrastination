package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.Base64

/**
 * The photo check (docs/scheduler.md §3, chunk completion 2; Q16): a photo of written work and
 * the piece it's for in, `{done, confidence, reason}` out. A piece can't be confirmed done at its
 * source, so this stands in for the timer. Built, and offered only while Claude is on.
 */
class PhotoChecker(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    @Serializable
    data class Verdict(val done: Boolean, val confidence: Double, val reason: String)

    data class Result(val verdict: Verdict?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Checks [jpeg] against [piece] ("Prep 30/09: Chapter 17 review, part 2 of 3"). */
    suspend fun check(jpeg: ByteArray, piece: String): Result {
        val image = ImageBlockParam.builder()
            .source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(jpeg)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
            .build()
        val schema = JsonOutputFormat.Schema.builder().apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(SYSTEM)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(TextBlockParam.builder().text("The piece of work: $piece").build()),
                ),
            )
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true)
            StopReason.MAX_TOKENS -> return Result(null, cost)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val verdict = runCatching { json.decodeFromString(Verdict.serializer(), text) }.getOrNull()
            ?.let { it.copy(confidence = it.confidence.coerceIn(0.0, 1.0), reason = it.reason.trim().take(200)) }
        return Result(verdict, cost)
    }

    companion object {
        /** How sure the model must be before a piece counts as done. */
        const val ACCEPT = 0.7

        /** Photos are sent no bigger than this on their long side: what the model reads at full detail. */
        const val MAX_SIDE = 1568

        val SYSTEM = """
            You check a photo of a UK sixth-form student's written work (Year 12) for the planner app that times their homework. Say whether it shows the piece of work described done: answers or writing for all of it, not blank pages, the questions alone, a few lines, or a different task. Be fair, and not easily fooled. Give your confidence from 0 to 1, and one short reason the student will read.
        """.trimIndent()

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "done" to mapOf("type" to "boolean"),
                "confidence" to mapOf("type" to "number"),
                "reason" to mapOf("type" to "string"),
            ),
            "required" to listOf("done", "confidence", "reason"),
            "additionalProperties" to false,
        )
    }
}
