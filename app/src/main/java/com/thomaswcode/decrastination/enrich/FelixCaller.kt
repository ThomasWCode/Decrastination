package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Felix's call to the model (AI-22), made as the chat's is, at the effort of the answers someone waits for (AC-02). */
class FelixCaller(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    /** [model]: the model that answered, a fallback's if one did. */
    data class Result(val answer: FelixAnswer?, val costUsd: Double, val refused: Boolean = false, val model: String? = null, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(2))
        .maxRetries(2)
        .build()

    /** His message for [user] (from [FelixPrompts.describe]), with his [system] and the student's [profile]. */
    suspend fun say(system: String, profile: String, user: String): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { FelixPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(system, profile))
            .addUserMessage(user.take(Pricing.MAX_INPUT_CHARS))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Felix)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> return Result(null, cost, usage = usage)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return Result(FelixAnswers.parse(text), cost, model = message.model().asString().ifBlank { null }, usage = usage)
    }
}
