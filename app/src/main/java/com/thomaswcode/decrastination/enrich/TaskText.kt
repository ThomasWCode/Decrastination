package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import java.util.Locale

/**
 * A task's own text as the chat and Felix are told it (D-93): what its source wrote (an
 * assignment's instructions, an email's conversation, a planner item's details, your notes), with
 * what's attached, its links, and who an email is from. Pure.
 */
object TaskText {

    /**
     * Where it's from and what comes with it: "Gmail, from Ms Example; attachments: form.pdf (107 KB)".
     * At most [MAX_ABOUT] characters, however many files or links it has, so it can't crowd out the
     * text it comes before.
     */
    fun about(task: TaskItem): String = listOfNotNull(
        task.source.label + (sender(task)?.let { ", from ${cut(it)}" } ?: ""),
        GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> "attachments: " + listed(files.map { it.label }) },
        TeamsRows.linksOf(task).takeIf { it.isNotEmpty() }?.let { "links: " + listed(it) },
    ).joinToString("; ").let { if (it.length <= MAX_ABOUT) it else it.take(MAX_ABOUT - 1) + "…" }

    /** The first [MAX_ITEMS] of [items], each cut short, saying how many more there are. */
    private fun listed(items: List<String>): String =
        items.take(MAX_ITEMS).joinToString(", ") { cut(it.trim()) } + (items.size - MAX_ITEMS).takeIf { it > 0 }?.let { ", and $it more" }.orEmpty()

    private fun cut(s: String): String = if (s.length <= MAX_ITEM) s else s.take(MAX_ITEM - 1) + "…"

    /**
     * What its text holds, as its line in the chat's list says it: who an email's from, what's attached and
     * linked, and how long its text is ("text 1,850 characters", or "no text"), within [max] characters; what's
     * attached and linked cut first, the length always there.
     */
    fun holds(task: TaskItem, max: Int): String {
        val text = task.detail.trim().length
        val length = if (text == 0) "no text" else "text " + "%,d".format(Locale.UK, text) + " characters"
        val with = listOfNotNull(
            sender(task)?.let { "from ${cut(it)}" },
            GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> "attachments: " + listed(files.map { it.label }) },
            TeamsRows.linksOf(task).takeIf { it.isNotEmpty() }?.let { "links: " + listed(it) },
        ).joinToString("; ")
        val room = max - length.length - 2
        return when {
            with.isEmpty() || room < MIN_HOLDS -> length
            with.length <= room -> "$with; $length"
            else -> with.take(room - 1) + "…; " + length
        }
    }

    /** The least of what's attached and linked worth saying in [holds]: less, the length alone. */
    private const val MIN_HOLDS = 20

    /** What [about] says at most: its files and links each, how many of them, and all of it. */
    private const val MAX_ITEM = 120
    private const val MAX_ITEMS = 6
    const val MAX_ABOUT = 800

    /**
     * Its text, each line after [indent], at most [max] characters of it: longer, its start and its
     * end, saying how much of the middle was left out. An email's conversation is kept oldest first,
     * so its end holds the newest replies, where a changed date or a new ask turns up; its start, the
     * ask itself. Null when it has none.
     */
    fun body(task: TaskItem, max: Int, indent: String = "  "): String? {
        val text = task.detail.trim().takeIf { it.isNotEmpty() } ?: return null
        val shown = if (text.length <= max) text else {
            val head = start(text, max / 2)
            val tail = end(text, max - max / 2)
            head.trimEnd() + "\n[… ${text.length - head.length - tail.length} characters left out …]\n" + tail.trimStart()
        }
        return indented(shown, indent)
    }

    // Each part cut at a space where there's one near, so no word is cut in two.
    private fun start(text: String, max: Int): String = text.take(max).let { h -> h.indexOfLast(Char::isWhitespace).takeIf { it >= 0 && it >= h.length - WORD }?.let(h::take) ?: h }
    private fun end(text: String, max: Int): String = text.takeLast(max).let { t -> t.indexOfFirst(Char::isWhitespace).takeIf { it in 0..WORD }?.let { t.substring(it + 1) } ?: t }

    private fun indented(text: String, indent: String): String = text.lines().joinToString("\n") { indent + it.trimEnd() }

    /** How far back or on a cut looks for a space. */
    private const val WORD = 40

    /**
     * Its text as the chat's list and his own messages give it: whole where it fits in [max]; past
     * it, what its reading made of it ([com.thomaswcode.decrastination.core.Enrichment.summary])
     * where there's one, else its start and end ([body]). A text too long for its reading to see
     * whole ([ReadingPrompts.MAX_TEXT]) has its summary said to be of its start, and its end after it.
     */
    fun brief(task: TaskItem, max: Int, indent: String = "  "): String? {
        val text = task.detail.trim()
        val summary = summary(task)?.takeIf { text.length > max } ?: return body(task, max, indent)
        val unseen = text.length - ReadingPrompts.MAX_TEXT
        if (unseen <= 0) return indented("Its reading's summary of all ${text.length} characters: $summary", indent)
        // The end its reading never saw, in the room the summary leaves (a third of it at least).
        val tail = end(text, minOf(unseen, maxOf(max - summary.length, max / 3)))
        val gap = (unseen - tail.length).takeIf { it > 0 }?.let { "[… $it characters left out …]\n" }.orEmpty()
        return indented("Its reading's summary of its first ${ReadingPrompts.MAX_TEXT} of ${text.length} characters: $summary\n" + gap + tail.trimStart(), indent)
    }

    /**
     * Its whole text as a message about it, or his asking to read it, carries it: within [max]; past
     * it, its start and end, after its reading's summary of it where there's one (of its start
     * alone, for a text too long for its reading to see whole).
     */
    fun whole(task: TaskItem, max: Int, indent: String = "  "): String? {
        val shown = body(task, max, indent) ?: return null
        val length = task.detail.trim().length
        val summary = summary(task)?.takeIf { length > max } ?: return shown
        val of = if (length <= ReadingPrompts.MAX_TEXT) "all of it" else "its first ${ReadingPrompts.MAX_TEXT} of $length characters"
        return indented("Its reading's summary of $of: $summary", indent) + "\n" + shown
    }

    /** What the task's reading in force made of its text, where it made anything. */
    private fun summary(task: TaskItem): String? = Enrichments.current(task)?.summary?.takeIf { it.isNotBlank() }

    /** Whether it has anything of its own to tell: its text, or what's attached to it. */
    fun has(task: TaskItem): Boolean = task.detail.isNotBlank() || GmailThreads.attachmentsOf(task).isNotEmpty() || TeamsRows.linksOf(task).isNotEmpty()

    /** An email's sender, by name where there is one; none for a note you sent yourself. */
    private fun sender(task: TaskItem): String? {
        if (task.source != Source.Gmail || task.extra[GmailThreads.EXTRA_SENT] == "true") return null
        return (task.extra[GmailThreads.EXTRA_FROM_NAME] ?: task.extra[GmailThreads.EXTRA_FROM])?.trim()?.takeIf { it.isNotEmpty() }
    }
}
