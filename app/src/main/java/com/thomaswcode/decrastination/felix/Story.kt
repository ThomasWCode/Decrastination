package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CheckIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Felix is told of the day and the week as a person would know it (D-120): today's story, in order, with what
 * was said and his notes on how he felt; and his diary, a line a day for the last week, the week before in a line,
 * and the week's check-in in their words. From the stores the app keeps, with no call. Pure.
 */
object Story {
    /** Today's story, the newest so many lines of it. */
    const val LINES = 24

    /** A message's words in the story, at most: its point, not all of it. */
    const val TEXT = 300

    /** The days the diary has a line each for, before today. */
    const val DAYS = 7

    /** A check-in from before the start of the day this many days back isn't this week's: Sunday's is told him till the next Sunday's, a day's slack beside it. */
    private const val CHECK_IN_DAYS = 8L

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /**
     * Today's story from [since] (the day's start): each slot on record in [today] as it went, when it went so, and each
     * message with words in [messages], his with his note on how he felt ([ChatMessage.feeling], D-119), theirs as a
     * reply, an answer or something said in the chat; [chat] leaves the chat's own exchanges out, which its turns carry.
     * Oldest first, the newest [LINES] of it.
     */
    fun today(today: List<SlotRecord>, messages: List<ChatMessage>, since: Long, zone: ZoneId, chat: Boolean = false): List<String> {
        fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)
        val slots = today.map { r ->
            val how = when (r.outcome) {
                SlotOutcome.Kept -> "kept"
                SlotOutcome.KeptLate -> if (r.at > r.end) "kept late, at ${clock(r.at)}" else "kept"
                SlotOutcome.Missed -> "went by undone"
                SlotOutcome.Moved -> "moved by them"
            }
            r.at to "${clock(r.start)}–${clock(r.end)} ${r.label}: $how"
        }
        val said = messages.filter { it.at >= since && it.text.isNotBlank() && !(chat && it.kind == MessageKind.Chat) }
        val words = said.map { m ->
            val text = "“" + cut(m.text) + "”"
            m.at to "${clock(m.at)}, " + when {
                m.from == Speaker.Felix && m.byApp -> "the app texted them for you: $text"
                m.from == Speaker.Felix && m.kind == MessageKind.Chat -> "you answered in the chat: $text"
                m.from == Speaker.Felix -> "you texted" + (m.about.taskTitle?.let { " about $it" } ?: "") + ": $text"
                m.replyTo != null || said.any { his -> his.from == Speaker.Felix && ChatHistory.replyOf(his, said)?.id == m.id } -> "they replied: $text"
                m.kind == MessageKind.Chat -> "they said in the chat: $text"
                else -> "they answered: $text"
            } + m.feeling.takeIf { m.from == Speaker.Felix && it.isNotBlank() }?.let { " (you felt: “${cut(it)}”)" }.orEmpty()
        }
        return (slots + words).sortedBy { it.first }.map { it.second }.takeLast(LINES)
    }

    /**
     * His diary as of [today]: a line for each of the [DAYS] days before it with a slot on record in [slots], what was
     * done of what was laid, what went by and what was made up late, and how he felt in his messages of that day by the
     * levels kept on them ([ChatMessage.mood], Q103); the week before in a line; and the newest of [checkIns] from the
     * week, in their words. Facts, for him to say his way or not at all.
     */
    fun diary(slots: List<SlotRecord>, checkIns: List<CheckIn>, messages: List<ChatMessage>, today: LocalDate, zone: ZoneId): List<String> {
        val lines = mutableListOf<String>()
        for (back in DAYS downTo 1) {
            val day = today.minusDays(back.toLong())
            val its = slots.filter { it.date == day.toString() && it.outcome != SlotOutcome.Moved }
            if (its.isEmpty()) continue
            val kept = its.count { it.outcome.kept }
            val parts = mutableListOf(if (kept == its.size && kept > 1) "all $kept done" else "$kept of ${its.size} done")
            its.filter { it.outcome == SlotOutcome.Missed }.map { it.label }.distinct().takeIf { it.isNotEmpty() }?.let { parts += "gone by: " + it.joinToString(", ") }
            its.filter { it.outcome == SlotOutcome.KeptLate && it.at > it.end }.map { it.label }.distinct().takeIf { it.isNotEmpty() }?.let { parts += "made up late: " + it.joinToString(", ") }
            felt(messages, day, zone)?.let { parts += it }
            lines += "${day.format(DAY).lowercase(Locale.UK)}: " + parts.joinToString("; ")
        }
        val before = slots.filter { it.outcome != SlotOutcome.Moved && it.date >= today.minusDays(2L * DAYS).toString() && it.date < today.minusDays(DAYS.toLong()).toString() }
        if (before.isNotEmpty()) lines += "the week before: ${before.count { it.outcome.kept }} of ${before.size} done"
        val from = today.minusDays(CHECK_IN_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        checkIns.filter { it.at >= from }.maxByOrNull { it.at }?.let { c ->
            val said = listOfNotNull(
                "the week felt ${c.feel} of 5".takeIf { c.feel in 1..5 },
                c.avoided.trim().takeIf { it.isNotEmpty() }?.let { "put off: “${cut(it)}”" },
                c.inTheWay.trim().takeIf { it.isNotEmpty() }?.let { "in the way: “${cut(it)}”" },
                c.change.trim().takeIf { it.isNotEmpty() }?.let { "to change: “${cut(it)}”" },
                c.energy.trim().takeIf { it.isNotEmpty() }?.let { "energy: “${cut(it)}”" },
            )
            if (said.isNotEmpty()) lines += "their check-in, ${Instant.ofEpochMilli(c.at).atZone(zone).format(DAY).lowercase(Locale.UK)}, in their words: " + said.joinToString("; ")
        }
        return lines
    }

    /** How he felt in his messages of [day], by the levels kept on them: "you were not impressed twice", none where he was neutral. */
    private fun felt(messages: List<ChatMessage>, day: LocalDate, zone: ZoneId): String? {
        val levels = messages.filter { it.from == Speaker.Felix && it.mood.isNotEmpty() && Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == day }
            .mapNotNull { m -> Mood.Level.entries.firstOrNull { it.name == m.mood } }
        if (levels.isEmpty()) return null
        return "you were " + levels.groupingBy { it }.eachCount().entries.sortedBy { it.key.ordinal }.joinToString(", ") { (level, n) ->
            Mood.words(level) + when (n) {
                1 -> " once"
                2 -> " twice"
                else -> " $n times"
            }
        }
    }

    /** [text] on one line, at most [TEXT] characters, cut at a word with an ellipsis. */
    private fun cut(text: String): String {
        val line = text.trim().replace(Regex("""\s*\n\s*"""), " / ")
        if (line.length <= TEXT) return line
        val room = line.take(TEXT - 1)
        return room.substringBeforeLast(' ', room).trimEnd(' ', ',', ';', ':') + "…"
    }
}
