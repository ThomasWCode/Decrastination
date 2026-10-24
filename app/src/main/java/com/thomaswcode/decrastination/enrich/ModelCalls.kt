package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.TextBlockParam

/** What every model call sends before its question (AC-01, AI-05), and how each call goes on record (AC-07). */
object ModelCalls {
    /**
     * The job's system prompt, marked to be cached for an hour (AC-01: its instructions and limits
     * are the same from call to call, so a call within the hour reads them at a tenth of the price;
     * the model ignores the mark below 512 tokens), then the student's profile (AI-05), which
     * changes with what's remembered, so it isn't.
     */
    fun systemBlocks(system: String, profile: String): List<TextBlockParam> = listOfNotNull(
        TextBlockParam.builder().text(system).cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()).build(),
        profile.takeIf { it.isNotBlank() }?.let { TextBlockParam.builder().text(it).build() },
    )

    /** What a call used (AC-07): its tokens in and out, those read from the cache, and why it stopped. */
    data class Usage(val inTokens: Long = 0, val outTokens: Long = 0, val cacheRead: Long = 0, val stop: String? = null) {
        companion object {
            val NONE = Usage()
        }
    }

    /** [message]'s usage, as the record of calls keeps it. */
    fun usage(message: Message): Usage = message.usage().let { u ->
        Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), message.stopReason().orElse(null)?.toString())
    }

    /**
     * How a call goes on record (AC-07, [ModelGateway.call]): its job and what it was about, and,
     * from its answer, what it used and how it went.
     */
    class OnRecord<T>(val job: ModelEffort.Job, val title: String? = null, val read: (T) -> Pair<Usage, String>)

    /** How a call that gives one answer went: refused, empty (nothing it could read, or cut off), or used. */
    fun outcome(refused: Boolean, empty: Boolean): String = when {
        refused -> "refused"
        empty -> "empty"
        else -> "used"
    }

    /** How an enrichment's call went: refused, put back to be put right (AI-01), empty, its plan dropped and why, salvaged, or used. */
    fun outcome(result: Enricher.Result): String {
        val e = result.enrichment
        return when {
            result.refused -> "refused"
            result.repair != null -> "repaired"
            e == null -> "empty"
            e.dropped != null -> "dropped:" + e.dropped
            e.adjusted.isNotEmpty() -> "salvaged"
            else -> "used"
        }
    }
}
