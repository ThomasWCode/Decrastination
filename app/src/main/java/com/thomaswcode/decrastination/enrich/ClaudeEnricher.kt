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
    /** Who the student is (AI-05): read for each call, as it changes with what's remembered. */
    private val profile: () -> Profile.Block = { Profile.Block.NONE },
    /** The vocabulary decks' topics (DX-10), for an assignment's sections. */
    private val decks: () -> Map<String, String> = { emptyMap() },
) : Enricher {
    override val by = model

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    override suspend fun enrich(task: TaskItem, job: Enrichments.Job, now: Long): Enricher.Result {
        val photo = photoOf(task)
        val message = send(job) { item(task, job, photo, Prompts.describe(task, job, now, zone, previous(task), decks()).take(Pricing.MAX_INPUT_CHARS)) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Enricher.Result(null, cost, refused = true, usage = usage)
            // Cut off mid-answer: nothing to trust.
            StopReason.MAX_TOKENS -> return Enricher.Result(null, cost, usage = usage)
            else -> Unit
        }
        val text = textOf(message)
        // By the model that answered: a fallback's answer isn't the asked-for model's (BUG-P3-001).
        val answeredBy = message.model().asString().ifBlank { by }
        val strict = Answers.read(job, text, task, answeredBy, now, zone, salvage = false)
        // A reading the photo was in says so (AI-23).
        fun seen(e: com.thomaswcode.decrastination.core.Enrichment?) = if (photo != null) e?.copy(fromPhoto = true) else e
        val complaint = strict.complaint ?: return Enricher.Result(seen(strict.enrichment), cost, usage = usage, answer = text)
        // A check it failed (AI-01): read with the dates that can't be left out meanwhile, and the
        // answer and its problem kept to put back once ([repair]).
        return Enricher.Result(seen(Answers.parse(job, text, task, answeredBy, now, zone)), cost, repair = Enricher.Repair(text, complaint), usage = usage, answer = text)
    }

    /**
     * The second time of asking (AI-01): the item, the model's first answer as its own turn, and
     * what was wrong with it; read with the dates that can't be left out. The item is cut to leave
     * room for the answer, so the call is within what every call's held to ([Pricing.MAX_INPUT_CHARS]);
     * with too little, it isn't sent.
     */
    override suspend fun repair(task: TaskItem, job: Enrichments.Job, now: Long, repair: Enricher.Repair): Enricher.Result {
        val asked = Prompts.repair(repair.complaint)
        val room = Pricing.MAX_INPUT_CHARS - repair.answer.length - asked.length
        if (room < MIN_REPAIR_ROOM) return Enricher.Result(null)
        val photo = photoOf(task)
        val message = send(job) {
            item(task, job, photo, Prompts.describe(task, job, now, zone, previous(task), decks()).take(room))
            addAssistantMessage(repair.answer)
            addUserMessage(asked)
        }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Enricher.Result(null, cost, refused = true, repaired = true, usage = usage)
            StopReason.MAX_TOKENS -> return Enricher.Result(null, cost, repaired = true, usage = usage)
            else -> Unit
        }
        val answeredBy = message.model().asString().ifBlank { by }
        val text = textOf(message)
        val read = Answers.parse(job, text, task, answeredBy, now, zone)
        return Enricher.Result(if (photo != null) read?.copy(fromPhoto = true) else read, cost, repaired = true, usage = usage, answer = text)
    }

    /** [task]'s photo of its work (AI-23), from its file; none if it has none, or it can't be read. */
    private suspend fun photoOf(task: TaskItem): ByteArray? = task.photo?.let { p -> withContext(Dispatchers.IO) { runCatching { java.io.File(p.path).readBytes() }.getOrNull() } }

    /** The item as the user's turn: with [photo], the photo first and the model told what it is. */
    private fun MessageCreateParams.Builder.item(task: TaskItem, job: Enrichments.Job, photo: ByteArray?, text: String) {
        if (photo == null) {
            addUserMessage(text)
            return
        }
        val image = com.anthropic.models.messages.ImageBlockParam.builder()
            .source(com.anthropic.models.messages.Base64ImageSource.builder().data(java.util.Base64.getEncoder().encodeToString(photo)).mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG).build())
            .build()
        addUserMessageOfBlockParams(
            listOf(
                com.anthropic.models.messages.ContentBlockParam.ofImage(image),
                com.anthropic.models.messages.ContentBlockParam.ofText(com.anthropic.models.messages.TextBlockParam.builder().text(Prompts.photo(job) + "\n\n" + text).build()),
            ),
        )
    }

    /** [task]'s steps as planned before (AI-08): its own, or its last reading's. */
    private fun previous(task: TaskItem): List<com.thomaswcode.decrastination.core.SubStep> = task.subSteps.ifEmpty { task.enrichment?.subSteps.orEmpty() }

    private fun textOf(message: Message): String = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")

    /** [job]'s request, its turns from [turns]: the system prompt and the profile, the effort, the schema, and the fallback on. */
    private suspend fun send(job: Enrichments.Job, turns: MessageCreateParams.Builder.() -> Unit): Message {
        val student = profile()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(Prompts.system(job, student.year), student.text))
            .apply(turns)
            .outputConfig(
                OutputConfig.builder()
                    .effort(ModelEffort.of(job))
                    .format(JsonOutputFormat.builder().schema(schema(Prompts.schema(job))).build())
                    .build(),
            )
            // A request declined by the model's safety classifiers is run again on the model the API chooses.
            .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        return withContext(Dispatchers.IO) { client.messages().create(params) }
    }

    private fun schema(map: Map<String, Any>): JsonOutputFormat.Schema =
        JsonOutputFormat.Schema.builder().apply { map.forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }.build()

    companion object {
        const val MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Room for thinking at high effort and the answer; a call costs only what it uses. */
        const val MAX_TOKENS = 16_000L

        /** The least of the item worth asking again with: less, and the first answer's reading stands. */
        private const val MIN_REPAIR_ROOM = 4_000
    }
}

/** List prices in US dollars per million tokens: Opus 5.5, and the older Opus a fallback may run on. */
object Pricing {
    data class Rates(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    private val OPUS_5_5 = Rates(input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 5.0)
    private val OLDER_OPUS = Rates(input = 5.0, output = 25.0, cacheRead = 0.50, cacheWrite = 6.25)

    /** A token is at least a byte, and a character at most three bytes of UTF-8 (a four-byte one is two characters). */
    private const val TOKENS_PER_CHAR = 3

    /** The instructions, the schema, and a photo check's pictures (up to three, AI-17; no more than 1 568 pixels a side, about 3 300 tokens each). */
    private const val PROMPT_TOKENS = 11_000

    /**
     * The most text any call sends, every call's cut to it: room for an email's [Prompts.MAX_TEXT]
     * characters and its lines, an instruction's open tasks and fortnight of events, a week's record.
     */
    const val MAX_INPUT_CHARS = 40_000

    /**
     * The most one call can cost, at the dearer rates: the longest text any call sends
     * ([MAX_INPUT_CHARS], and the profile sent with it, [Profile.MAX_BLOCK_CHARS], at
     * [TOKENS_PER_CHAR] tokens each, and [PROMPT_TOKENS] more), and all of [ClaudeEnricher.MAX_TOKENS]
     * out. $1.295. Every job's input is held to it, not only an email's (BUG-P2-015), so a cap is
     * never passed by a call it let through.
     */
    val WORST_CALL_USD: Double =
        (((MAX_INPUT_CHARS.toLong() + Profile.MAX_BLOCK_CHARS) * TOKENS_PER_CHAR + PROMPT_TOKENS) * OLDER_OPUS.input + ClaudeEnricher.MAX_TOKENS * OLDER_OPUS.output) / 1_000_000.0

    fun rates(model: String): Rates = if (model.startsWith(ClaudeEnricher.MODEL)) OPUS_5_5 else OLDER_OPUS

    /**
     * A web search's price (16 Oct): $10 a thousand, beside the tokens its results add (about 11 000
     * for one, on the 16th). The chat's worst case is within [WORST_CALL_USD]'s: its three tokens a
     * character leave room for [ChatPrompts.MAX_SEARCHES] searches' results.
     */
    const val WEB_SEARCH_USD = 0.01

    fun costUsd(model: String, input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0, searches: Long = 0): Double {
        val r = rates(model)
        return (input * r.input + output * r.output + cacheRead * r.cacheRead + cacheWrite * r.cacheWrite) / 1_000_000.0 + searches * WEB_SEARCH_USD
    }

    fun costUsd(message: Message): Double {
        val usage = message.usage()
        return costUsd(
            model = message.model().asString(),
            input = usage.inputTokens(),
            output = usage.outputTokens(),
            cacheRead = usage.cacheReadInputTokens().orElse(0L),
            cacheWrite = usage.cacheCreationInputTokens().orElse(0L),
            searches = usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L),
        )
    }
}
