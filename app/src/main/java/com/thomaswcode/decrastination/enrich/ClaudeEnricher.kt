package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.TaskItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZoneId

/**
 * The model's enrichment (docs/data-sources.md §5): Claude Opus 5.5 at high effort, its answer held
 * to a JSON schema ([Prompts]), with the API's server-side fallback on for a request the model's
 * safety classifiers decline. Off until switched on in Settings; [AiUsage] keeps it under the
 * monthly cap, from each call's token counts ([Pricing]). [baseUrl] points it elsewhere: the tests'
 * fake server, and the phone's plumbing check.
 */
class ClaudeEnricher(
    apiKey: String,
    private val zone: ZoneId,
    endpoint: String? = null,
    private val model: String = MODEL,
) : Enricher {
    override val by = model

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    override suspend fun enrich(task: TaskItem, job: Enrichments.Job, now: Long): Enricher.Result {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(Prompts.system(job))
            .addUserMessage(Prompts.describe(task, job, now, zone))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.HIGH)
                    .format(JsonOutputFormat.builder().schema(schema(Prompts.schema(job))).build())
                    .build(),
            )
            // A request declined by the model's safety classifiers is run again on the model the API chooses.
            .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Enricher.Result(null, cost, refused = true)
            // Cut off mid-answer: nothing to trust.
            StopReason.MAX_TOKENS -> return Enricher.Result(null, cost)
            else -> Unit
        }
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return Enricher.Result(Answers.parse(job, text, task, by, now, zone), cost)
    }

    private fun schema(map: Map<String, Any>): JsonOutputFormat.Schema =
        JsonOutputFormat.Schema.builder().apply { map.forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }.build()

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Room for thinking at high effort and the answer; a call costs only what it uses. */
        const val MAX_TOKENS = 16_000L
    }
}

/** List prices in US dollars per million tokens: Opus 5.5, and the older Opus a fallback may run on. */
object Pricing {
    data class Rates(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    private val OPUS_5_5 = Rates(input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 5.0)
    private val OLDER_OPUS = Rates(input = 5.0, output = 25.0, cacheRead = 0.50, cacheWrite = 6.25)

    /** The most one call can cost: a long email in, and all of [ClaudeEnricher.MAX_TOKENS] out, at the dearer rates. */
    const val WORST_CALL_USD = 0.45

    fun rates(model: String): Rates = if (model.startsWith(ClaudeEnricher.MODEL)) OPUS_5_5 else OLDER_OPUS

    fun costUsd(model: String, input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0): Double {
        val r = rates(model)
        return (input * r.input + output * r.output + cacheRead * r.cacheRead + cacheWrite * r.cacheWrite) / 1_000_000.0
    }

    fun costUsd(message: Message): Double {
        val usage = message.usage()
        return costUsd(
            model = message.model().asString(),
            input = usage.inputTokens(),
            output = usage.outputTokens(),
            cacheRead = usage.cacheReadInputTokens().orElse(0L),
            cacheWrite = usage.cacheCreationInputTokens().orElse(0L),
        )
    }
}
