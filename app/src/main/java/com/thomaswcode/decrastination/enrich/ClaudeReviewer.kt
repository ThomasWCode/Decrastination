package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.learn.ReviewInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * The weekly review by the model (docs/data-sources.md §5, job 4): the week in, a note and bounded
 * changes out ([ReviewInput]), as the enrichment's calls are made. Off until Claude is switched on.
 */
class ClaudeReviewer(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    data class Result(val answer: ReviewInput.Answer?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun review(week: String): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { ReviewInput.schema().forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(ReviewInput.SYSTEM)
            .addUserMessage(week)
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
        val answer = runCatching { json.decodeFromString(ReviewInput.Answer.serializer(), text) }.getOrNull()
            ?.let { it.copy(note = it.note.map(String::trim).filter(String::isNotEmpty).take(ReviewInput.MAX_NOTE)) }
        return Result(answer, cost)
    }
}
