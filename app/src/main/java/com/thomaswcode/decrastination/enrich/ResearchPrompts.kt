package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.WebSource
import com.thomaswcode.decrastination.felix.ChatPrompts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Felix's web search (D-62): when the chat's answer asks to look something up
 * ([ChatAnswers.Reading.research]), a call of its own with Claude's web search tool finds it, in
 * words, and he's asked again with what it found ([ChatPrompts.turns]). Not in the chat's own call:
 * the tool beside the chat's answer schema makes a grammar the API refuses as too large (a live
 * call). Pure: the wording, and the pages kept.
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
