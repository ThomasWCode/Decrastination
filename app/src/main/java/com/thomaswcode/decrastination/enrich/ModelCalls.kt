package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.TextBlockParam

/** What every model call sends before its question (AC-01, AI-05). */
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
}
