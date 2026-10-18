package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
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
import java.util.Base64

/**
 * The photo check (docs/spec/planner.md, chunk completion 2; Q16): photos of written work, a page
 * each (up to [MAX_PAGES], AI-17), and the piece they're for with its task's instructions (AI-16)
 * in, `{done, confidence, reason, seen}` out. A piece can't be confirmed done at its source, so
 * this stands in for the timer. Built, and offered only while Claude is on.
 */
class PhotoChecker(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL, private val profile: Profile.Block = Profile.Block.NONE) {

    /** [seen]: what the photos show, as the model names it (AI-16). */
    @Serializable
    data class Verdict(val done: Boolean, val confidence: Double, val reason: String, val seen: String = "")

    data class Result(val verdict: Verdict?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    private val json = Json { ignoreUnknownKeys = true }

    /** Checks [jpegs], a page each, against [about], the piece and its task as [context] says them. */
    suspend fun check(jpegs: List<ByteArray>, about: String): Result {
        val images = jpegs.take(MAX_PAGES).map { jpeg ->
            ContentBlockParam.ofImage(
                ImageBlockParam.builder().source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(jpeg)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build(),
            )
        }
        val schema = JsonOutputFormat.Schema.builder().apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(system(profile.year), profile.text))
            // Cut to the most text a call is costed for ([Pricing.WORST_CALL_USD]): instructions can be long.
            .addUserMessageOfBlockParams(images + ContentBlockParam.ofText(TextBlockParam.builder().text(about.take(Pricing.MAX_INPUT_CHARS)).build()))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.PhotoCheck)).format(JsonOutputFormat.builder().schema(schema).build()).build())
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
        val verdict = runCatching { json.decodeFromString(Verdict.serializer(), text) }.getOrNull()
            ?.let { it.copy(confidence = it.confidence.coerceIn(0.0, 1.0), reason = it.reason.trim().take(200), seen = it.seen.trim().take(MAX_SEEN)) }
        return Result(verdict, cost, usage = usage)
    }

    companion object {
        /** How sure the model must be before a piece counts as done. */
        const val ACCEPT = 0.7

        /** Photos are sent no bigger than this on their long side: what the model reads at full detail. */
        const val MAX_SIDE = 1568

        /** The most pages one check takes (AI-17). */
        const val MAX_PAGES = 3

        /** The most of the task's instructions sent. */
        private const val MAX_INSTRUCTIONS = 1_500

        private const val MAX_SEEN = 300

        /** The photo check's, for a student in Year [year] (AI-06). */
        fun system(year: Int = 12): String = """
            You check photos of a UK sixth-form student's written work (Year $year) for the planner app that times their homework: up to $MAX_PAGES photos, the pages of one piece of work. Say whether they show the piece of work described done: answers or writing for all of it, not blank pages, the questions alone, a few lines, or a different task. Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done. Name what you can see. Be fair, and not easily fooled. Give your confidence from 0 to 1; in reason, one short line the student will read ("Shows worked answers to Q5-8; Q9 isn't visible"); in seen, briefly, what the photos show.
        """.trimIndent()

        /**
         * [piece] and its [task], as the check reads them (AI-16): the piece and its minutes, the
         * task's title and subject, its steps done already, and its instructions.
         */
        fun context(piece: com.thomaswcode.decrastination.core.Chunk, task: com.thomaswcode.decrastination.core.TaskItem?): String = buildString {
            appendLine("The piece of work: ${piece.label}, ${piece.minutes} min")
            if (task != null) {
                appendLine("Task: ${task.title}")
                task.className?.let { appendLine("Subject: $it") }
                task.subSteps.filter { it.done }.takeIf { it.isNotEmpty() }?.let { done -> appendLine("Steps done already: " + done.joinToString("; ") { it.title }) }
                task.detail.trim().takeIf { it.isNotEmpty() }?.let {
                    appendLine()
                    appendLine("Instructions:")
                    append(it.take(MAX_INSTRUCTIONS))
                }
            }
        }.trimEnd()

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "done" to mapOf("type" to "boolean"),
                "confidence" to mapOf("type" to "number"),
                "reason" to mapOf("type" to "string"),
                "seen" to mapOf("type" to "string"),
            ),
            "required" to listOf("done", "confidence", "reason", "seen"),
            "additionalProperties" to false,
        )
    }
}
