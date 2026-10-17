package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.teams.TeamsRows

/**
 * A task's own text as the chat and Felix are told it (D-93): what its source wrote (an
 * assignment's instructions, an email's conversation, a planner item's details, your notes), with
 * what's attached, its links, and who an email is from. Pure.
 */
object TaskText {

    /** Where it's from and what comes with it: "Gmail, from Ms Example; attachments: form.pdf (107 KB)". */
    fun about(task: TaskItem): String = listOfNotNull(
        task.source.label + (sender(task)?.let { ", from $it" } ?: ""),
        GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> "attachments: " + files.joinToString(", ") { it.label } },
        TeamsRows.linksOf(task).takeIf { it.isNotEmpty() }?.let { "links: " + it.joinToString(", ") },
    ).joinToString("; ")

    /**
     * Its text, each line after [indent], at most [max] characters of it: longer, its start and its
     * end, saying how much of the middle was left out. An email's conversation is kept oldest first,
     * so its end holds the newest replies, where a changed date or a new ask turns up; its start, the
     * ask itself. Null when it has none.
     */
    fun body(task: TaskItem, max: Int, indent: String = "  "): String? {
        val text = task.detail.trim().takeIf { it.isNotEmpty() } ?: return null
        val shown = if (text.length <= max) text else {
            // Each part cut at a space where there's one near, so no word is cut in two.
            val head = text.take(max / 2).let { h -> h.indexOfLast(Char::isWhitespace).takeIf { it >= h.length - WORD }?.let(h::take) ?: h }
            val tail = text.takeLast(max - max / 2).let { t -> t.indexOfFirst(Char::isWhitespace).takeIf { it in 0..WORD }?.let { t.substring(it + 1) } ?: t }
            head.trimEnd() + "\n[… ${text.length - head.length - tail.length} characters left out …]\n" + tail.trimStart()
        }
        return shown.lines().joinToString("\n") { indent + it.trimEnd() }
    }

    /** How far back or on a cut looks for a space. */
    private const val WORD = 40

    /**
     * Its text as the chat's list and his own messages give it: whole where it fits in [max]; past
     * it, what its reading made of all of it ([com.thomaswcode.decrastination.core.Enrichment.summary])
     * where there's one, else its start and end ([body]).
     */
    fun brief(task: TaskItem, max: Int, indent: String = "  "): String? {
        val length = task.detail.trim().length
        if (length > max) summary(task)?.let { return "${indent}Its reading's summary of all $length characters: $it" }
        return body(task, max, indent)
    }

    /**
     * Its whole text as a message about it, or his asking to read it, carries it: within [max]; past
     * it, its start and end, after its reading's summary of all of it where there's one.
     */
    fun whole(task: TaskItem, max: Int, indent: String = "  "): String? {
        val shown = body(task, max, indent) ?: return null
        val summary = summary(task)?.takeIf { task.detail.trim().length > max } ?: return shown
        return "${indent}Its reading's summary of all of it: $summary\n$shown"
    }

    /** What the task's reading in force made of its text, where it made anything. */
    private fun summary(task: TaskItem): String? = com.thomaswcode.decrastination.core.Enrichments.current(task)?.summary?.takeIf { it.isNotBlank() }

    /** Whether it has anything of its own to tell: its text, or what's attached to it. */
    fun has(task: TaskItem): Boolean = task.detail.isNotBlank() || GmailThreads.attachmentsOf(task).isNotEmpty() || TeamsRows.linksOf(task).isNotEmpty()

    /** An email's sender, by name where there is one; none for a note you sent yourself. */
    private fun sender(task: TaskItem): String? {
        if (task.source != Source.Gmail || task.extra[GmailThreads.EXTRA_SENT] == "true") return null
        return (task.extra[GmailThreads.EXTRA_FROM_NAME] ?: task.extra[GmailThreads.EXTRA_FROM])?.trim()?.takeIf { it.isNotEmpty() }
    }
}
