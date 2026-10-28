package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.core.Speaker
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

    /**
     * His answer as the model gives it ([FelixPrompts.SCHEMA], D-120): his note, three texts he might send and which
     * he'd send ([send], from 1), and the rest as kept.
     */
    @Serializable
    private data class Written(val feeling: String = "", val texts: List<String> = emptyList(), val send: Int = 1, val replies: List<String> = emptyList(), val nextCheckMin: Int? = null, val memories: List<Given> = emptyList())

    /**
     * [text] as his answer, within bounds; null when it isn't one. His message is the text he chose to send, or, chosen
     * past the three, the first; the others kept as [FelixAnswer.unsent] (D-120). All three empty, he'd rather not write.
     */
    fun parse(text: String, maxMessage: Int = MAX_MESSAGE): FelixAnswer? {
        val given = runCatching { json.decodeFromString(Written.serializer(), text) }.getOrNull() ?: return null
        val chosen = (given.send - 1).takeIf { it in given.texts.indices } ?: 0
        val answer = FelixAnswer(
            message = given.texts.getOrNull(chosen).orEmpty(), replies = given.replies, nextCheckMin = given.nextCheckMin, memories = given.memories, feeling = given.feeling,
            unsent = given.texts.filterIndexed { i, _ -> i != chosen },
        )
        return bounded(answer, maxMessage)
    }

    /** How long the text he chose in [text] is as he wrote it, before it's held to its room ([message]): 0 when it isn't one. */
    fun messageLength(text: String): Int = runCatching {
        val given = json.decodeFromString(Written.serializer(), text)
        given.texts.getOrNull((given.send - 1).takeIf { it in given.texts.indices } ?: 0).orEmpty().let(::lines).length
    }.getOrDefault(0)

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
            message = message(lines(answer.message), maxMessage),
            replies = answer.replies.map { cut(it.trim(), MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(MAX_REPLIES),
            nextCheckMin = answer.nextCheckMin?.coerceIn(MIN_CHECK_MIN, MAX_CHECK_MIN),
            memories = answer.memories.take(MAX_MEMORIES),
            feeling = answer.feeling.trim().take(MAX_FEELING),
            unsent = answer.unsent.map { message(lines(it), maxMessage) }.filter { it.isNotEmpty() }.take(MAX_UNSENT),
        )
    }

    /** The texts he didn't send kept with his message, at most (D-120): the two beside the one he chose. */
    const val MAX_UNSENT = 2

    /**
     * [text] as at most [MAX_TEXTS] lines, each a text of its own (D-120): each line trimmed, the empty ones gone, and
     * any past the second joined onto it, so a look is never more than two texts.
     */
    fun lines(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return (lines.take(MAX_TEXTS - 1) + listOfNotNull(lines.drop(MAX_TEXTS - 1).joinToString(" ").takeIf { it.isNotEmpty() })).joinToString("\n")
    }

    /** The texts a look of his is, at most (D-120): one thought each, the second for a second thing. */
    const val MAX_TEXTS = 2

    /**
     * [message]'s words as the texts they're shown as (D-120): each line a text of its own, for his messages written
     * unasked from his prompt; whole for the rest (his chat's answers, which can list, the morning's briefing, what's in
     * the app's words, yours), which keep their lines in one.
     */
    fun texts(message: ChatMessage): List<String> =
        if (message.from == Speaker.Felix && !message.byApp && message.kind in SPLIT) message.text.lines().filter { it.isNotBlank() }.ifEmpty { listOf(message.text) } else listOf(message.text)

    /** The kinds of his messages written from his prompt as texts ([FelixPrompts.system]), the morning's but, which is one briefing. */
    private val SPLIT = setOf(MessageKind.SlotStart, MessageKind.SlotEnd, MessageKind.SlotKept, MessageKind.DayDone, MessageKind.Question, MessageKind.Completions, MessageKind.Reminder)

    /**
     * His message no longer than [max]: over it, ended at its first line's end where that leaves at least half the room
     * used (the second text left out, D-120), else at its last whole sentence that does, so it never stops mid-sentence
     * (the morning's did); else cut at a word, with an ellipsis.
     */
    internal fun message(text: String, max: Int): String {
        if (text.length <= max) return text
        val first = text.substringBefore('\n')
        if (first.length < text.length && first.length <= max && first.length >= max / 2) return first.trimEnd()
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
