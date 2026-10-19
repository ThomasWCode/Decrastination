package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.Message

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
     * The most text any call sends, every call's cut to it: room for an email's [ReadingPrompts.MAX_TEXT]
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
     * for one, on the 16th). A look's worst case is within [WORST_CALL_USD]'s: its input is a few
     * lines, so the tokens it allows leave room for [ResearchPrompts.MAX_SEARCHES] searches' results.
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
