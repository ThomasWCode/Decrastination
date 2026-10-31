package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The weekly summary's call to the model (NF-02), at its own effort. */
class SummaryWriter(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL) {

    data class Result(val text: String?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    /** The summary of [user] (from [com.thomaswcode.decrastination.learn.Summary.input]), with the student's [profile]. */
    suspend fun write(profile: String, user: String): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { SummaryPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(SummaryPrompts.SYSTEM, profile))
            .addUserMessage(user.take(Pricing.MAX_INPUT_CHARS))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Summary)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        return when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> Result(null, cost, usage = usage)
            else -> Result(SummaryPrompts.parse(message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")), cost, usage = usage)
        }
    }
}
