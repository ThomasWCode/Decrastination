package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.gmail.Mime
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A task's own text as the chat and Felix are told it (D-93). */
class TaskTextTest {
    private val now = Fixtures.at("2026-10-12T22:45")
    private val task = TaskItem(id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Sheet", kind = Kind.Homework, firstSeenAt = now, lastSeenAt = now)

    @Test
    fun `a text past its room keeps its start and end, cut between words, however little the room`() {
        val words = task.copy(detail = "one two three four five six seven eight nine ten")
        assertEquals("one two\n[… 33 characters left out …]\nnine ten", TaskText.body(words, 20, ""))
        // No space near a cut, and room for a few characters or none: cut there, never a failure.
        val word = task.copy(detail = "x".repeat(500))
        for (max in 0..80) assertTrue("characters left out" in TaskText.body(word, max, "")!!, "$max")
    }

    @Test
    fun `what comes with it is said within its room, however many files and links it has`() {
        val files = (1..20).map { Mime.Attachment("worksheet-$it-" + "y".repeat(200) + ".pdf", "application/pdf", null) }
        val many = task.copy(
            source = Source.Gmail,
            extra = mapOf(GmailThreads.EXTRA_FROM_NAME to "Ms Example", GmailThreads.EXTRA_ATTACHMENTS to GmailThreads.attachmentsExtra(files), TeamsRows.EXTRA_LINKS to (1..9).joinToString("\n") { "https://example.org/$it" }),
        )
        val about = TaskText.about(many)
        assertTrue(about.length <= TaskText.MAX_ABOUT, "${about.length}")
        assertTrue(about.startsWith("Gmail, from Ms Example; attachments: worksheet-1-yyy"), about)
        assertTrue("yyy…, worksheet-2-" in about && ", and 14 more" in about, about)
    }

    @Test
    fun `a summary of a text too long to be read whole says it's of its start, its end after it`() {
        val long = task.copy(detail = "Start. " + "m".repeat(Prompts.MAX_TEXT) + " The deadline has moved to Friday.")
        val read = long.copy(enrichment = Enrichment(inputHash = Enrichments.inputHash(long), by = "test", at = now, summary = "A long brief."))
        val of = "Its reading's summary of its first ${Prompts.MAX_TEXT} of ${long.detail.length} characters: A long brief."
        assertEquals("  $of\n  [… 8 characters left out …]\n  The deadline has moved to Friday.", TaskText.brief(read, 600))
        val whole = TaskText.whole(read, 8_000)!!
        assertTrue(whole.startsWith("  $of\n  Start. mmm"), whole.take(200))
        assertTrue(whole.endsWith("The deadline has moved to Friday."), whole.takeLast(200))
        // One its reading saw whole is of all of it.
        val seen = task.copy(detail = "Start. " + "m".repeat(2_000)).let { it.copy(enrichment = read.enrichment!!.copy(inputHash = Enrichments.inputHash(it))) }
        assertEquals("  Its reading's summary of all ${seen.detail.length} characters: A long brief.", TaskText.brief(seen, 600))
        assertTrue(TaskText.whole(seen, 1_000)!!.startsWith("  Its reading's summary of all of it: A long brief.\n  Start."))
    }
}
