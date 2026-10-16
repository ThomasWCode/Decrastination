package com.thomaswcode.decrastination.enrich

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import com.thomaswcode.decrastination.core.WebSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Felix's web search (your call, 16 Oct): when the chat's answer asks to look something up
 * ([ChatAnswers.Reading.research]), a call of its own with Claude's web search tool finds it, in
 * words, and he's asked again with what it found ([ChatPrompts.turns]). Not in the chat's own call:
 * the tool beside the chat's answer schema makes a grammar the API refuses as too large (a live
 * call, 16 Oct). Pure: the wording, and the pages kept.
 */
object ResearchPrompts {

    /** A question longer than this is cut. */
    const val MAX_QUESTION = 300

    /** What's found, at most, as the chat is told it. */
    const val MAX_FOUND = 3_000

    /** The searches one look may make: enough to look a thing up from two sides, and a third. */
    const val MAX_SEARCHES = 3

    /** The pages a look keeps: the first few, each once. */
    const val MAX_SOURCES = 4

    private const val MAX_SOURCE_TITLE = 120
    private val TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

    /** The search's instructions, for a student in Year [year] (AI-06). No profile goes with it: nothing of theirs is searched for. */
    fun system(year: Int = 12): String = """
        You look things up on the web for the assistant in a UK sixth-form student's (Year $year) planner app, to plan their work by. Search for what it asks (a few searches at most), then answer in plain UK English in at most 15 short lines: the facts that matter for planning the work (what's asked of them, its dates and deadlines, its rules, word or time limits, what they'll need, how it's judged), each as you found it, and say where something is unsure or wasn't found. Give a date as its day, month and year. Don't plan the work or give advice, don't make anything up, and never search for anything about the student themselves.
    """.trimIndent()

    /** What's asked, as the search reads it: today, the student's words for what it's about, and what to look up. */
    fun describe(question: String, asked: String, now: Long, zone: ZoneId): String = buildString {
        appendLine("Today: ${Instant.ofEpochMilli(now).atZone(zone).format(TODAY)}")
        appendLine("The student wrote: “${asked.trim().take(ChatPrompts.MAX_TEXT)}”")
        append("Look up: ${question.trim().take(MAX_QUESTION)}")
    }

    /** The pages [found] (title and address, in the order found), each once, the first [MAX_SOURCES] with an address. */
    fun sources(found: List<Pair<String, String>>): List<WebSource> =
        found.filter { (_, url) -> url.startsWith("https://") || url.startsWith("http://") }.distinctBy { it.second }.take(MAX_SOURCES)
            .map { (title, url) -> WebSource(title.trim().take(MAX_SOURCE_TITLE), url) }

    /** [found] as the chat's newest turn carries it, to answer and act on. */
    fun told(found: Found): String =
        "What a web search found for “${found.question}” (answer and act on it, say in a few words where it's from, and leave research empty):\n" +
            found.text + (if (found.sources.isEmpty()) "" else "\nFrom: " + found.sources.joinToString("; ") { it.title.ifBlank { it.url } })
}

/** What a web search found ([ResearchPrompts]): its answer in words, what it was asked and searched for, and the pages it read. */
data class Found(val question: String, val text: String, val searched: List<String>, val sources: List<WebSource>)

/** Claude's web search tool (16 Oct): [ResearchPrompts.MAX_SEARCHES] searches at most, from the UK, in [zone]. */
internal fun searchTool(zone: ZoneId): WebSearchTool20250305 =
    WebSearchTool20250305.builder().maxUses(ResearchPrompts.MAX_SEARCHES.toLong()).userLocation(UserLocation.builder().country("GB").timezone(zone.id).build()).build()

/** How many times a look its searching paused is sent back to go on. */
private const val MAX_PAUSES = 2

/** Claude looking something up on the web for the chat ([ResearchPrompts]), as the enrichment's calls are made. */
class WebResearcher(apiKey: String, endpoint: String? = null, val model: String = ClaudeEnricher.MODEL, private val year: Int = 12) {

    data class Result(val found: Found?, val costUsd: Double, val refused: Boolean = false, val usage: ModelCalls.Usage = ModelCalls.Usage.NONE)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (endpoint != null) baseUrl(endpoint) }
        .timeout(Duration.ofMinutes(3))
        .maxRetries(2)
        .build()

    /** What the web says for [question], asked about the student's [asked], at [now]; none found, a null [Result.found]. */
    suspend fun look(question: String, asked: String, now: Long, zone: ZoneId): Result {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(ClaudeEnricher.MAX_TOKENS)
            .systemOfTextBlockParams(ModelCalls.systemBlocks(ResearchPrompts.system(year), ""))
            .addUserMessage(ResearchPrompts.describe(question, asked, now, zone))
            .addTool(searchTool(zone))
            .outputConfig(OutputConfig.builder().effort(ModelEffort.of(ModelEffort.Job.Research)).build())
            .putAdditionalHeader("anthropic-beta", ClaudeEnricher.FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        val replies = mutableListOf(withContext(Dispatchers.IO) { client.messages().create(builder.build()) })
        // A look its searching left paused (pause_turn) is sent back as it is to go on, twice at most.
        while (replies.last().stopReason().orElse(null) == StopReason.PAUSE_TURN && replies.size <= MAX_PAUSES) {
            builder.addMessage(replies.last())
            replies += withContext(Dispatchers.IO) { client.messages().create(builder.build()) }
        }
        val message = replies.last()
        val cost = replies.sumOf { Pricing.costUsd(it) }
        val usage = replies.map { ModelCalls.usage(it) }.reduce { a, b -> ModelCalls.Usage(a.inTokens + b.inTokens, a.outTokens + b.outTokens, a.cacheRead + b.cacheRead, b.stop) }
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return Result(null, cost, refused = true, usage = usage)
            StopReason.MAX_TOKENS, StopReason.PAUSE_TURN -> return Result(null, cost, usage = usage)
            else -> Unit
        }
        // Its answer: the words after its last search's results (any before them said on the way).
        val blocks = message.content()
        val text = blocks.drop(blocks.indexOfLast { it.isWebSearchToolResult() } + 1).mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        val searched = replies.flatMap { r -> r.content().mapNotNull { b -> b.serverToolUse().orElse(null)?._input()?.asObject()?.orElse(null)?.get("query")?.asString()?.orElse(null)?.trim() } }
            .filter { it.isNotEmpty() }.distinct()
        val pages = replies.flatMap { r -> r.content().mapNotNull { it.webSearchToolResult().orElse(null) }.flatMap { res -> res.content().resultBlocks().orElse(emptyList()).map { it.title() to it.url() } } }
        val found = text.takeIf { it.isNotEmpty() }?.let { Found(question.trim().take(ResearchPrompts.MAX_QUESTION), it.take(ResearchPrompts.MAX_FOUND), searched, ResearchPrompts.sources(pages)) }
        return Result(found, cost, usage = usage)
    }
}
