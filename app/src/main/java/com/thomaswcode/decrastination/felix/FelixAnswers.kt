package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.FelixAnswer
import kotlinx.serialization.json.Json

/** His answer read and kept within its bounds. Pure. */
object FelixAnswers {
    const val MAX_MESSAGE = 240

    /** The morning's: it's the whole briefing, with no list of the day under it (LR-04, your call, 16 Oct). */
    const val MAX_MORNING = 480
    const val MAX_REPLIES = 3
    const val MAX_REPLY = 40
    const val MIN_CHECK_MIN = 5
    private const val MAX_CHECK_MIN = 24 * 60
    private const val MAX_MEMORIES = 5

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] as his answer, within bounds; null when it isn't one. */
    fun parse(text: String, maxMessage: Int = MAX_MESSAGE): FelixAnswer? {
        val answer = runCatching { json.decodeFromString(FelixAnswer.serializer(), text) }.getOrNull() ?: return null
        return FelixAnswer(
            message = message(answer.message.trim(), maxMessage),
            replies = answer.replies.map { cut(it.trim(), MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(MAX_REPLIES),
            nextCheckMin = answer.nextCheckMin?.coerceIn(MIN_CHECK_MIN, MAX_CHECK_MIN),
            memories = answer.memories.take(MAX_MEMORIES),
        )
    }

    /**
     * His message no longer than [max]: over it, ended at its last whole sentence that leaves at
     * least half the room used, so it never stops mid-sentence (16 Oct: the morning's did); else cut
     * at a word, with an ellipsis.
     */
    internal fun message(text: String, max: Int): String {
        if (text.length <= max) return text
        val room = text.take(max)
        val end = SENTENCE_END.findAll(room).lastOrNull()?.let { it.range.first + 1 }
        return if (end != null && end >= max / 2) room.take(end).trimEnd() else cut(text, max)
    }

    private val SENTENCE_END = Regex("""[.!?](?=\s|$)""")

    /** [text] no longer than [max]: cut at a word where it can be, with an ellipsis. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val room = text.take(max - 1)
        return room.substringBeforeLast(' ', room).trimEnd(' ', ',', ';', ':') + "…"
    }
}
