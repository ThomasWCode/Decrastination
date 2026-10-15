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

/** A piece's micro-plan asked of the model (AI-15), as Felix's call is, at the effort of the answers someone waits for (AC-02). */
class MicroPlanner(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL) {

    /** [text]: the answer as it came, for [MicroPlans.parse]; null when declined or cut off. */
    data class Result(val text: String?, val costUsd: Double, val refused: Boolean = false)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(2))
        .maxRetries(2)
        .build()

    /** The micro-plan for [user] (from [MicroPlans.describe]), with the student's [profile]. */
    suspend fun plan(profile: String, user: String): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { MicroPlans.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(MicroPlans.SYSTEM, profile))
            .addUserMessage(user.take(Pricing.MAX_INPUT_CHARS))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.MicroPlan)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        return when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> Result(null, cost, refused = true)
            StopReason.MAX_TOKENS -> Result(null, cost)
            else -> Result(message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""), cost)
        }
    }
}
