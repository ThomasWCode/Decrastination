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
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

/** A photo of homework read into tasks (NF-01), at the effort of the readings the plan is made from (AC-02). */
class PhotoTaskReader(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL, private val profile: Profile.Block = Profile.Block.NONE) {

    data class Result(val reading: PhotoTasks.Reading?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    /** [jpeg] read at [now] into tasks. */
    suspend fun read(jpeg: ByteArray, now: Long, zone: ZoneId): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { PhotoTasks.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val image = ImageBlockParam.builder().source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(jpeg)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(PhotoTasks.SYSTEM, profile.text))
            .addUserMessageOfBlockParams(listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(TextBlockParam.builder().text(PhotoTasks.describe(now, zone)).build())))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.PhotoToTask)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        return when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> Result(null, cost, usage = usage)
            else -> Result(PhotoTasks.parse(message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""), now, zone), cost, usage = usage)
        }
    }
}
