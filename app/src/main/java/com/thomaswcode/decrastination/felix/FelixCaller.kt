package com.thomaswcode.decrastination.felix

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.Pricing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Felix's call to the model (AI-22), made as the chat's is, at the effort of the answers someone waits for (AC-02). */
class FelixCaller(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL) {

    /** [model]: the model that answered, a fallback's if one did. */
    /** [rebalances]: his steps rebalanced in a morning's answer (D-110), read beside [answer], which is kept, and never kept themselves. */
    data class Result(val answer: FelixAnswer?, val costUsd: Double, val refused: Boolean = false, val model: String? = null, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE, val rebalances: List<Rebalance.Given> = emptyList())


    /** His message for [user] (from [FelixPrompts.describe]), with his [system] and the student's [profile]. */
    suspend fun say(system: String, profile: String, user: String, maxMessage: Int = FelixAnswers.MAX_MESSAGE): Result {
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
        return Result(FelixAnswers.parse(text, maxMessage), cost, model = message.model().asString().ifBlank { null }, usage = usage, rebalances = FelixAnswers.rebalances(text))
    }
}
