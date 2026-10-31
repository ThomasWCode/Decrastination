package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.Fact
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The weekly tidy's call to the model (AI-21), made as the review's is. Off until Claude is switched on. */
class MemoryTidier(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL, private val profile: Profile.Block = Profile.Block.NONE) {

    /** [model]: the model that answered, a fallback's if one did. */
    data class Result(val answer: MemoryTidy.Answer?, val costUsd: Double, val refused: Boolean = false, val model: String? = null, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    private val json = Json { ignoreUnknownKeys = true }

    suspend fun tidy(facts: List<Fact>, now: Long, zone: ZoneId): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { MemoryTidy.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(MemoryTidy.system(profile.year, MemoryTidy.maxLines(facts)), profile.text))
            .addUserMessage(MemoryTidy.describe(facts, now, zone).take(Pricing.MAX_INPUT_CHARS))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Tidy)).format(JsonOutputFormat.builder().schema(schema).build()).build())
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
        val answer = runCatching { json.decodeFromString(MemoryTidy.Answer.serializer(), text) }.getOrNull()
        return Result(answer, cost, model = message.model().asString().ifBlank { null }, usage = usage)
    }
}
