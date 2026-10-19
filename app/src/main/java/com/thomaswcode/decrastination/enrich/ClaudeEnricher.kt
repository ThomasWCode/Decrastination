package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.TaskItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * The model's enrichment (docs/spec/readings.md): Claude Opus 5.5 at high effort, its answer held
 * to a JSON schema ([ReadingPrompts]), with the API's server-side fallback on for a request the model's
 * safety classifiers decline. Off until switched on in Settings; [AiUsage] keeps it under the
 * monthly cap, from each call's token counts ([Pricing]). It sends through the [client] it's given
 * ([ModelGateway.client]): the tests' points at their fake server, the phone's plumbing check's at a stand-in.
 */
class ClaudeEnricher(
    private val client: AnthropicClient,
    private val zone: ZoneId,
    private val model: String = MODEL,
    /** Who the student is (AI-05): read for each call, as it changes with what's remembered. */
    private val profile: () -> Profile.Block = { Profile.Block.NONE },
    /** The vocabulary decks' topics (DX-10), for an assignment's sections. */
    private val decks: () -> Map<String, String> = { emptyMap() },
) : Enricher {
    override val by = model

    override suspend fun enrich(task: TaskItem, job: Enrichments.Job, now: Long): Enricher.Result {
        val photo = photoOf(task)
        val message = send(job) { item(task, job, photo, ReadingPrompts.describe(task, job, now, zone, previous(task), decks()).take(Pricing.MAX_INPUT_CHARS)) }
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
        val strict = ReadingAnswers.read(job, text, task, answeredBy, now, zone, salvage = false)
        // A reading the photo was in says so (AI-23).
        fun seen(e: com.thomaswcode.decrastination.core.Enrichment?) = if (photo != null) e?.copy(fromPhoto = true) else e
        val complaint = strict.complaint ?: return Enricher.Result(seen(strict.enrichment), cost, usage = usage, answer = text)
        // A check it failed (AI-01): read with the dates that can't be left out meanwhile, and the
        // answer and its problem kept to put back once ([repair]).
        return Enricher.Result(seen(ReadingAnswers.parse(job, text, task, answeredBy, now, zone)), cost, repair = Enricher.Repair(text, complaint), usage = usage, answer = text)
    }

    /**
     * The second time of asking (AI-01): the item, the model's first answer as its own turn, and
     * what was wrong with it; read with the dates that can't be left out. The item is cut to leave
     * room for the answer, so the call is within what every call's held to ([Pricing.MAX_INPUT_CHARS]);
     * with too little, it isn't sent.
     */
    override suspend fun repair(task: TaskItem, job: Enrichments.Job, now: Long, repair: Enricher.Repair): Enricher.Result {
        val asked = ReadingPrompts.repair(repair.complaint)
        val room = Pricing.MAX_INPUT_CHARS - repair.answer.length - asked.length
        if (room < MIN_REPAIR_ROOM) return Enricher.Result(null)
        val photo = photoOf(task)
        val message = send(job) {
            item(task, job, photo, ReadingPrompts.describe(task, job, now, zone, previous(task), decks()).take(room))
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
        val read = ReadingAnswers.parse(job, text, task, answeredBy, now, zone)
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
                com.anthropic.models.messages.ContentBlockParam.ofText(com.anthropic.models.messages.TextBlockParam.builder().text(ReadingPrompts.photo(job) + "\n\n" + text).build()),
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
            .systemOfTextBlockParams(ModelCalls.systemBlocks(ReadingPrompts.system(job, student.year), student.text))
            .apply(turns)
            .outputConfig(
                OutputConfig.builder()
                    .effort(ModelEffort.of(job))
                    .format(JsonOutputFormat.builder().schema(schema(ReadingPrompts.schema(job))).build())
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
