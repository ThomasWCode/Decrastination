package com.thomaswcode.decrastination.felix

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
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.FelixStore
import com.thomaswcode.decrastination.enrich.ClaudeEnricher
import com.thomaswcode.decrastination.enrich.Found
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.Pricing
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.CalendarEvent
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Claude in the chat ([ChatPrompts]), as the enrichment's calls are made. */
class ChatReader(
    private val client: AnthropicClient,
    private val zone: ZoneId,
    val model: String = ClaudeEnricher.MODEL,
    private val profile: Profile.Block = Profile.Block.NONE,
    /** Felix's name and line, as the chat's him (AI-24). */
    private val settings: Settings = Settings(),
) {

    /** Its answer, with the ids of the messages that went in its turns ([sent], [ChatPrompts.sent]): only those are answered by it. */
    data class Result(val reading: ChatAnswers.Reading?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE, val sent: Set<String>? = null, val pictures: List<String> = emptyList())


    /**
     * Claude's answer to the last of [messages] (yours), with what the app knows now ([context]:
     * [ChatPrompts.context] and [ChatPrompts.plan]), what a web search it asked for [found], and the
     * tasks whose whole text it asked to [read] (D-93), and what else it asked for [brought] (the week's numbers,
     * the calendar further out, each a block of its own), why a search it asked for wasn't made [notLooked], the [last]
     * round said to be so; its reminders and notes
     * checked against [felix]'s. The photos that can be sent ([live], 6.8.0) go as pictures before the newest turn's words, read by
     * [photo], with those he asked to see again ([seen]).
     */
    suspend fun answer(messages: List<ChatMessage>, tasks: List<TaskItem>, events: List<CalendarEvent>, context: ChatPrompts.Context, now: Long, found: Found? = null, read: List<TaskItem> = emptyList(), quoted: List<ChatMessage> = emptyList(), felix: FelixStore = FelixStore(), brought: List<String> = emptyList(), last: Boolean = false, notLooked: String? = null, live: Set<String> = emptySet(), seen: List<String> = emptyList(), photo: (String) -> ByteArray? = { null }): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { ChatPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(ChatPrompts.system(settings), profile.felix))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Chat)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        val sent = ChatPrompts.sent(messages, context.known, tasks.associateBy { it.id }, zone, plan = context.plan, found = found, read = read, events = events, quoted = quoted, brought = brought, lastRound = last, notLooked = notLooked, live = live, seen = seen)
        sent.turns.forEachIndexed { i, (from, text) ->
            when {
                from != Speaker.You -> builder.addAssistantMessage(text)
                // The newest turn's photos (6.8.0), each named before its picture, then its words.
                i == sent.turns.lastIndex && sent.pictures.isNotEmpty() -> builder.addUserMessageOfBlockParams(pictures(sent.pictures, photo) + ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()))
                else -> builder.addUserMessage(text)
            }
        }
        val message = withContext(Dispatchers.IO) { client.messages().create(builder.build()) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> return Result(null, cost, usage = usage)
            else -> Unit
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val about = messages.lastOrNull { it.from == Speaker.You }?.about ?: About()
        return Result(ChatAnswers.parse(text, tasks, events, about, now, zone, settings, felix, context), cost, usage = usage, sent = sent.ids, pictures = sent.pictures)
    }

    /** [hashes]' pictures as the newest turn sends them (6.8.0): each named before it ([ChatPrompts.pictureName]); one whose file's gone since said so in its place. */
    private fun pictures(hashes: List<String>, photo: (String) -> ByteArray?): List<ContentBlockParam> = hashes.flatMapIndexed { i, hash ->
        val jpeg = photo(hash)
        listOfNotNull(
            ContentBlockParam.ofText(TextBlockParam.builder().text(ChatPrompts.pictureName(i, hash) + if (jpeg == null) " (gone before it could be sent)" else "").build()),
            jpeg?.let { ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder().data(Base64.getEncoder().encodeToString(it)).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build()) },
        )
    }
}
