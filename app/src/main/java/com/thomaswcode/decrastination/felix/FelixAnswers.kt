package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Rebalance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** His answer read and kept within its bounds. Pure. */
object FelixAnswers {
    const val MAX_MESSAGE = 240

    /** The morning's: it's the whole briefing, with no list of the day under it (LR-04, D-65). */
    const val MAX_MORNING = 480
    const val MAX_REPLIES = 3
    const val MAX_REPLY = 40
    const val MIN_CHECK_MIN = 5
    private const val MAX_CHECK_MIN = 24 * 60
    private const val MAX_MEMORIES = 5

    /** His note on how he felt (D-119), kept with his message: a few words asked for, this many characters at most. */
    const val MAX_FEELING = 400

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] as his answer, within bounds; null when it isn't one. */
    fun parse(text: String, maxMessage: Int = MAX_MESSAGE): FelixAnswer? {
        val answer = runCatching { json.decodeFromString(FelixAnswer.serializer(), text) }.getOrNull() ?: return null
        return bounded(answer, maxMessage)
    }

    /** How long his message in [text] is as he wrote it, before it's held to its room ([message]): 0 when it isn't one. */
    fun messageLength(text: String): Int = runCatching { json.decodeFromString(FelixAnswer.serializer(), text).message.trim().length }.getOrDefault(0)

    /**
     * The steps he rebalanced in [text], a morning's answer (D-110), every one: each is checked against what he
     * was asked about as it's made, and one that isn't keeps his message, which may name it, from being sent.
     */
    fun rebalances(text: String): List<Rebalance.Given> =
        runCatching { json.decodeFromString(Rebalances.serializer(), text).rebalances }.getOrDefault(emptyList())

    @Serializable
    private data class Rebalances(val rebalances: List<Rebalance.Given> = emptyList())

    /** [answer] within the bounds his are kept to ([parse]'s): one kept from elsewhere, a restored backup's draft, held to them as it's used. */
    fun bounded(answer: FelixAnswer, maxMessage: Int = MAX_MESSAGE): FelixAnswer {
        return FelixAnswer(
            message = message(answer.message.trim(), maxMessage),
            replies = answer.replies.map { cut(it.trim(), MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(MAX_REPLIES),
            nextCheckMin = answer.nextCheckMin?.coerceIn(MIN_CHECK_MIN, MAX_CHECK_MIN),
            memories = answer.memories.take(MAX_MEMORIES),
            feeling = answer.feeling.trim().take(MAX_FEELING),
        )
    }

    /**
     * His message no longer than [max]: over it, ended at its last whole sentence that leaves at
     * least half the room used, so it never stops mid-sentence (the morning's did); else cut
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
