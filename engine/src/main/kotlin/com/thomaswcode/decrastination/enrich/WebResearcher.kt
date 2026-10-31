package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import com.thomaswcode.decrastination.core.WebSource
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a web search found ([ResearchPrompts]): its answer in words, what it was asked and searched for, and the pages it read. */
data class Found(val question: String, val text: String, val searched: List<String>, val sources: List<WebSource>)

/** Claude's web search tool: [ResearchPrompts.MAX_SEARCHES] searches at most, from the UK, in [zone]. */
internal fun searchTool(zone: ZoneId): WebSearchTool20250305 =
    WebSearchTool20250305.builder().maxUses(ResearchPrompts.MAX_SEARCHES.toLong()).userLocation(UserLocation.builder().country("GB").timezone(zone.id).build()).build()

/** Claude looking something up on the web for the chat ([ResearchPrompts]), as the enrichment's calls are made. */
class WebResearcher(private val client: AnthropicClient, val model: String = ClaudeEnricher.MODEL, private val year: Int = 12) {

    /** [paused]: its searching ran past the turn's limit (pause_turn), and it isn't sent on: one request a look, as the cap reserves. */
    data class Result(val found: Found?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE, val paused: Boolean = false)


    /** What the web says for [question], asked about the student's [asked], at [now]; none found, a null [Result.found]. */
    suspend fun look(question: String, asked: String, now: Long, zone: ZoneId): Result {
        // Its few lines aren't cached: under the cache's least, and a look is seldom made twice in an hour.
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .system(ResearchPrompts.system(year))
            .addUserMessage(ResearchPrompts.describe(question, asked, now, zone))
            .addTool(searchTool(zone))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Research)).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val message = withContext(Dispatchers.IO) { client.messages().create(params) }
        val cost = Pricing.costUsd(message)
        val usage = ModelCalls.usage(message)
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS -> return Result(null, cost, usage = usage)
            // Searched past the turn's limit: not sent on, which would be a second request the cap didn't reserve.
            StopReason.PAUSE_TURN -> return Result(null, cost, usage = usage, paused = true)
            else -> Unit
        }
        // Its answer: the words after its last search's results (any before them said on the way).
        val blocks = message.content()
        val text = blocks.drop(blocks.indexOfLast { it.isWebSearchToolResult() } + 1).mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        val searched = blocks.mapNotNull { b -> b.serverToolUse().orElse(null)?._input()?.asObject()?.orElse(null)?.get("query")?.asString()?.orElse(null)?.trim() }
            .filter { it.isNotEmpty() }.distinct()
        val pages = blocks.mapNotNull { it.webSearchToolResult().orElse(null) }.flatMap { res -> res.content().resultBlocks().orElse(emptyList()).map { it.title() to it.url() } }
        val found = text.takeIf { it.isNotEmpty() }?.let { Found(question.trim().take(ResearchPrompts.MAX_QUESTION), it.take(ResearchPrompts.MAX_FOUND), searched, ResearchPrompts.sources(pages)) }
        return Result(found, cost, usage = usage)
    }
}
