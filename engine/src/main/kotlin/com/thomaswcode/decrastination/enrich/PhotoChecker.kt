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
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.CheckNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * The photo check (docs/spec/planner.md, chunk completion 2; Q16): photos of written work, a page
 * each (up to [MAX_PAGES], AI-17), each named, and the piece they're for with its task's instructions (AI-16) and what earlier
 * checks of the task saw (6.4.0) in, `{done, confidence, reason, seen, covered, fresh}` out. A piece can't be confirmed done at its source, so
 * this is what earns a longer piece of written work its free time (BL-08). Built, and offered only while Claude is on.
 */
class PhotoChecker(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL, private val profile: Profile.Block = Profile.Block.NONE) {

    /**
     * [seen]: what the photos show, as the model names it (AI-16). [covered]: exactly which parts of the task's work they show done,
     * kept for later checks to rely on, empty where they show none (6.4.0). [fresh]: they show work no check that passed has shown.
     */
    @Serializable
    data class Verdict(val done: Boolean, val confidence: Double, val reason: String, val seen: String = "", val covered: String = "", val fresh: Boolean = false)

    data class Result(val verdict: Verdict?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks [jpegs], a page each, against [about], the piece and its task as [context] says them; with [kept], pages of earlier checks
     * kept with the task (6.5.0), sent again after them where there's room, named so as not to be taken for new work.
     */
    suspend fun check(jpegs: List<ByteArray>, about: String, kept: List<ByteArray> = emptyList()): Result {
        val pages = jpegs.take(MAX_PAGES)
        val named = pages.mapIndexed { i, jpeg -> "Page ${i + 1}:" to jpeg } + kept.take(MAX_PAGES - pages.size).mapIndexed { i, jpeg -> "Kept page ${i + 1}, from an earlier check:" to jpeg }
        // Each page named before its picture, so the answer can say which shows what (6.4.0).
        val images = named.flatMap { (name, jpeg) ->
            listOf(
                ContentBlockParam.ofText(TextBlockParam.builder().text(name).build()),
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder().source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(jpeg)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build(),
                ),
            )
        }
        val schema = JsonOutputFormat.Schema.builder().apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(system(profile.year), profile.text))
            // Cut to its own bound, the room its pictures leave in what a call is costed for ([Pricing.WORST_CALL_USD]).
            .addUserMessageOfBlockParams(images + ContentBlockParam.ofText(TextBlockParam.builder().text(about.take(MAX_TEXT)).build()))
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
            ?.let { it.copy(confidence = it.confidence.coerceIn(0.0, 1.0), reason = it.reason.trim().take(200), seen = it.seen.trim().take(MAX_SEEN), covered = it.covered.trim().take(MAX_COVERED)) }
        return Result(verdict, cost, usage = usage)
    }

    companion object {
        /** How sure the model must be before a piece counts as done. */
        const val ACCEPT = 0.7

        /** Photos are sent no bigger than this on their long side: what the model reads at full detail. */
        const val MAX_SIDE = 1568

        /** The most pages one check takes (AI-17): ten from 6.4.0. */
        const val MAX_PAGES = 10

        /**
         * The most text a check sends: what a call may send ([Pricing.MAX_INPUT_CHARS]) less its pictures' worth, each at its dearest
         * shape, so ten pages, the text at this and the prompt cost no more than [Pricing.WORST_CALL_USD].
         */
        const val MAX_TEXT = Pricing.MAX_INPUT_CHARS - MAX_PAGES * Pricing.PICTURE_CHARS

        /** The most of the task's instructions sent. */
        private const val MAX_INSTRUCTIONS = 1_500

        private const val MAX_SEEN = 300

        /** The most of what a check covered that's kept and sent again: a line or two, by question, page or section. */
        const val MAX_COVERED = 500

        private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

        /** The photo check's, for a student in Year [year] (AI-06). */
        fun system(year: Int = 12): String = """
            You check photos of a UK sixth-form student's written work (Year $year) for the planner app that times their homework: up to $MAX_PAGES photos, each named ("Page 1:"), the pages of one piece of work. Say whether they show the piece of work described done: answers or writing for all of it, not blank pages, the questions alone, a few lines, or a different task. Blank or crossed-out pages, a photo of a screen, printed answers, or work for a different task are not done. Name what you can see. Be fair, and not easily fooled.

            You may be told what earlier checks of the same task saw. Their photos aren't sent again, but what they showed counts as shown: the piece is done when that and these photos together show all of it. A check that passed has earned its time already, so a later one must also show work no passed check showed: say in fresh whether these photos do (true where no earlier check passed). Photos showing only what a passed check showed are not fresh, however much they show. Pages named "Kept page" are from earlier checks, sent again to be seen: they show what those checks showed, never new work.

            A piece may be one part of a task's work, done in order, its share said ("part 1 of 2: with the parts before it, about half of the task's work"). A part is done when the task's work shown, by these photos and earlier checks together, reaches its share from the start of the work: it needs no more than its share, and no less.

            Give your confidence from 0 to 1; in reason, one short line the student will read ("Shows worked answers to Q5-8; Q9 isn't visible"), naming a page where it helps; in seen, briefly, what the photos show; in covered, exactly which parts of this task's work these photos show done, by question, page or section ("Exercise 4C Q1-12, each worked in full"), as a later check will rely on it; leave covered empty where they show none of it.
        """.trimIndent()

        /**
         * [piece] and its [task], as the check reads them (AI-16): the piece and its minutes, the
         * task's title and subject, its steps done already, and its instructions; then [notes], what earlier checks of the task saw
         * (6.4.0), each with its piece, when, as of [zone], and whether it passed.
         */
        fun context(piece: Chunk, task: TaskItem?, notes: List<CheckNote> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): String = buildString {
            appendLine("The piece of work: ${piece.label}, ${piece.minutes} min")
            // A part's share of the task, so a first sitting's half passes on its half (your answer, 19 Nov): only where the task's
            // divided with no step named, as parts are numbered task-wide and a named step, or its sitting, is its own scope.
            if (piece.parts > 1 && piece.step == null) appendLine("It's part ${piece.part} of ${piece.parts}: with the parts before it, ${share(piece.part, piece.parts)} of the task's work (${piece.taskMinutes} min in all)")
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
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Earlier checks of this task, their photos not sent again (what each showed counts as shown):")
                notes.forEach { note ->
                    val at = Instant.ofEpochMilli(note.at).atZone(zone).format(WHEN)
                    appendLine("- $at, for \"${note.label}\", ${if (note.passed) "counted in a check that passed (earned already)" else "not passed"}: ${note.covered}")
                }
            }
        }.trimEnd()

        /** How much of a task's work parts 1 to [part] of [parts] are, in words: about half, about a third, all of it. */
        fun share(part: Int, parts: Int): String = when {
            part >= parts -> "all"
            part * 2 == parts -> "about half"
            part * 3 == parts -> "about a third"
            part * 3 == parts * 2 -> "about two thirds"
            part * 4 == parts -> "about a quarter"
            part * 4 == parts * 3 -> "about three quarters"
            else -> "about ${part * 100 / parts}%"
        }

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "done" to mapOf("type" to "boolean"),
                "confidence" to mapOf("type" to "number"),
                "reason" to mapOf("type" to "string"),
                "seen" to mapOf("type" to "string"),
                "covered" to mapOf("type" to "string"),
                "fresh" to mapOf("type" to "boolean"),
            ),
            "required" to listOf("done", "confidence", "reason", "seen", "covered", "fresh"),
            "additionalProperties" to false,
        )
    }
}
