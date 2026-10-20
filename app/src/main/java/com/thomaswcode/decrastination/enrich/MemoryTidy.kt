package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The weekly tidy of what's remembered (AI-21, Q-51): once the facts are past [DUE_SHARE] of their
 * room, the review sends them to the model, which rewrites them into fewer, current lines; the
 * before and after are kept with the week's review. Standing instructions are never tidied. Pure:
 * [MemoryTidier] makes the call.
 */
object MemoryTidy {
    /** Due past this share of the facts' room ([Memories.FACT_BUDGET_CHARS]). */
    const val DUE_SHARE = 0.7

    fun due(facts: List<Fact>): Boolean = Memories.chars(facts) > Memories.FACT_BUDGET_CHARS * DUE_SHARE

    /**
     * Whether [fact] is one a tidy may rewrite (10a): one with neither an until nor a task. Those with either go by
     * themselves, and a merge would lose when; they count to [due] all the same.
     */
    fun tidied(fact: Fact): Boolean = fact.until == null && fact.taskId == null

    /** [after], a tidy's facts from those [before] it could rewrite ([tidied]), with the rest of [before] as they were. */
    fun withExpiring(before: List<Fact>, after: List<Fact>): List<Fact> = (after + before.filterNot(::tidied)).sortedBy { it.at }

    /** The most lines a tidy gives back: two thirds of those it was given, so it makes room. */
    fun maxLines(before: List<Fact>): Int = (before.size * 2 / 3).coerceAtLeast(1)

    /** The tidy's, for a student in Year [year], giving back at most [lines] lines. */
    fun system(year: Int, lines: Int): String = """
        You look after what the planner app remembers about a UK sixth-form student (Year $year): facts it was told in the chat, each under a short topic, with the day it was remembered. They go with every call the app makes, so they should be few, current and true.

        Rewrite these remembered facts about the student into fewer, current lines: merge duplicates, drop what's stale or contradicted by a newer line, keep the newest on each topic, keep every rule-like preference. At most $lines lines, each a short topic and one sentence. Add nothing that isn't in them.
    """.trimIndent()

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)

    /** The facts as the model reads them: today, then each, oldest first, with the day it was remembered. */
    fun describe(facts: List<Fact>, now: Long, zone: ZoneId): String = buildString {
        appendLine("Today is ${day(now, zone)}.")
        appendLine("Remembered, oldest first:")
        facts.sortedBy { it.at }.forEach { appendLine("- ${it.topic}: ${it.text} (${day(it.at, zone)})") }
    }.trimEnd()

    private fun day(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).format(DAY)

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "facts" to mapOf("type" to "array", "items" to InstructionPrompts.obj("topic" to InstructionPrompts.STRING, "text" to InstructionPrompts.STRING)),
    )

    @Serializable
    data class Line(val topic: String = "", val text: String = "")

    @Serializable
    data class Answer(val facts: List<Line> = emptyList())

    /**
     * [answer]'s lines as the facts to keep, within the bounds any are kept in ([Memories.apply]);
     * a topic that was there keeps its time, so the oldest still go first, and a new one (a merge's)
     * is [now]'s. Null when it isn't a tidy: no line back, or no room made.
     */
    fun after(answer: Answer, before: List<Fact>, now: Long): List<Fact>? {
        val given = answer.facts.take(maxLines(before)).map { Given(it.topic, it.text) }.filter { it.topic.isNotBlank() && it.text.isNotBlank() }
        if (given.isEmpty()) return null
        val times = before.groupBy { it.topic.trim().lowercase() }.mapValues { (_, same) -> same.maxOf { it.at } }
        val facts = Memories.apply(MemoryState(), given, now).first.facts
            .map { f -> f.copy(at = times[f.topic.trim().lowercase()] ?: now) }
            .sortedBy { it.at }
        return facts.takeIf { Memories.chars(it) < Memories.chars(before) }
    }

    /**
     * [state] with its facts as [after], if they're still the [before] the tidy was given; null when
     * one was remembered or forgotten while it was out (left as they are, for next week's).
     */
    fun applied(state: MemoryState, before: List<Fact>, after: List<Fact>): MemoryState? = state.takeIf { it.facts == before }?.copy(facts = after)
}
