package com.thomaswcode.decrastination.felix

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
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

    data class Result(val reading: ChatAnswers.Reading?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)


    /**
     * Claude's answer to the last of [messages] (yours), with what the app knows now ([context]:
     * [ChatPrompts.context] and [ChatPrompts.plan]), what a web search it asked for [found], and the
     * tasks whose whole text it asked to [read] (D-93); its reminders and notes checked against [felix]'s (Stage 8a).
     */
    suspend fun answer(messages: List<ChatMessage>, tasks: List<TaskItem>, events: List<CalendarEvent>, context: ChatPrompts.Context, now: Long, found: Found? = null, read: List<TaskItem> = emptyList(), quoted: List<ChatMessage> = emptyList(), felix: FelixStore = FelixStore()): Result {
        val schema = JsonOutputFormat.Schema.builder().apply { ChatPrompts.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(ChatPrompts.system(settings, profile.year), profile.text))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Chat)).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        ChatPrompts.turns(messages, context.known, tasks.associateBy { it.id }, zone, plan = context.plan, found = found, read = read, events = events, quoted = quoted).forEach { (from, text) ->
            if (from == Speaker.You) builder.addUserMessage(text) else builder.addAssistantMessage(text)
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
        return Result(ChatAnswers.parse(text, tasks, events, about, now, zone, settings, felix), cost, usage = usage)
    }
}
