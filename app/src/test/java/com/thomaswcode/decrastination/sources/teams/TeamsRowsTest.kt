package com.thomaswcode.decrastination.sources.teams

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The provider's rows, built from the widget's real store of 7 Oct (fixtures/teams_widget_state.json). */
class TeamsRowsTest {
    private val assignments = Json.parseToJsonElement(Fixtures.text("teams_widget_state.json")).jsonObject["assignments"]!!.jsonArray

    /** A row as the widget's provider returns it (its AssignmentsContract columns). */
    private val rows = assignments.map { element ->
        val a = element.jsonObject
        mapOf(
            "key" to a["key"]?.jsonPrimitive?.content,
            "title" to a["title"]?.jsonPrimitive?.content,
            "class_name" to a["className"]?.jsonPrimitive?.content,
            "description" to a["description"]?.jsonPrimitive?.content,
            "due_text" to a["dueText"]?.jsonPrimitive?.content,
            "due_at" to a["dueAt"]?.jsonPrimitive?.longOrNull,
            "tab" to a["tab"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `every row becomes homework with its deadline and instructions`() {
        val tasks = rows.mapNotNull(TeamsRows::fetched)
        assertEquals(11, tasks.size)
        val statics = tasks.single { it.title == "Moments Prep" }
        assertEquals(Kind.Homework, statics.kind)
        assertEquals("12.9-PH1", statics.className)
        assertEquals("Pages 3-5 of the booklet.", statics.detail)
        assertEquals(Fixtures.at("2026-10-05T14:30"), statics.dueAt)
        assertEquals("PastDue", statics.extra[TeamsRows.EXTRA_TAB])
    }

    @Test
    fun `an assignment's reference materials and links are kept, as an email's attachments are, and sent with it`() {
        val row = rows.first() + mapOf("attachments" to "Statics booklet.pdf\nMark scheme.pdf\n", "links" to "https://example.com/notes\nhttps://example.com/notes")
        val task = com.thomaswcode.decrastination.core.Merge.apply(emptyList(), com.thomaswcode.decrastination.core.Source.Teams, listOfNotNull(TeamsRows.fetched(row)), 0).tasks.single()
        assertEquals(listOf("Statics booklet.pdf", "Mark scheme.pdf"), com.thomaswcode.decrastination.sources.gmail.GmailThreads.attachmentsOf(task).map { it.name })
        assertEquals(listOf("https://example.com/notes"), TeamsRows.linksOf(task))
        val described = com.thomaswcode.decrastination.enrich.ReadingPrompts.describe(task, com.thomaswcode.decrastination.core.Enrichments.Job.Assignment, 0, Fixtures.LONDON)
        assertEquals(true, "Attachments: Statics booklet.pdf, Mark scheme.pdf" in described, described)
        assertEquals(true, "Links: https://example.com/notes" in described, described)
        // Its links are what it's read from too: changed, it's read again.
        assertEquals(false, com.thomaswcode.decrastination.core.Enrichments.inputHash(task) == com.thomaswcode.decrastination.core.Enrichments.inputHash(task.copy(extra = task.extra - TeamsRows.EXTRA_LINKS)))
        // A widget before 0.4.0 gives neither.
        val older = TeamsRows.fetched(rows.first())!!
        assertNull(older.extra[com.thomaswcode.decrastination.sources.gmail.GmailThreads.EXTRA_ATTACHMENTS])
        assertNull(older.extra[TeamsRows.EXTRA_LINKS])
    }

    @Test
    fun `a row without a key is skipped, a blank title named`() {
        assertNull(TeamsRows.fetched(mapOf("title" to "x")))
        // In a read, a row without its key fails it: skipped, its assignment would look handed in.
        kotlin.test.assertFailsWith<java.io.IOException> { TeamsRows.all(listOf(mapOf("key" to "k", "title" to "a"), mapOf("title" to "x"))) }
        kotlin.test.assertFailsWith<java.io.IOException> { TeamsRows.all(listOf(mapOf("key" to "k", "title" to "a"), mapOf("key" to " ", "title" to "x"))) }
        assertEquals(listOf("k"), TeamsRows.all(listOf(mapOf("key" to "k", "title" to "a"))).map { it.sourceId })
        assertEquals("Untitled assignment", TeamsRows.fetched(mapOf("key" to "k", "title" to " "))!!.title)
    }

    @Test
    fun `returned work with feedback is ten minutes' reading, due in three days, done by a tick`() {
        val row = mapOf("key" to "k1", "title" to "Moments Prep", "class_name" to "12.9-PH1", "returned_at" to Fixtures.at("2026-10-14T16:00"), "feedback" to "Good work; check your units in Q3.")
        val f = TeamsRows.returned(row)!!
        assertEquals("k1:feedback:${Fixtures.at("2026-10-14T16:00")}", f.sourceId)
        // Returned again later: feedback of its own.
        assertEquals("k1:feedback:${Fixtures.at("2026-10-21T16:00")}", TeamsRows.returned(row + ("returned_at" to Fixtures.at("2026-10-21T16:00")))!!.sourceId)
        assertEquals("Read the feedback on Moments Prep", f.title)
        assertEquals(Kind.Admin, f.kind)
        assertEquals("Good work; check your units in Q3.", f.detail)
        assertEquals(Fixtures.at("2026-10-17T16:00"), f.dueAt)
        assertEquals(10, f.sourceEffortMin)
        assertEquals("k1", f.extra[TeamsRows.EXTRA_FEEDBACK_ON])
        val task = com.thomaswcode.decrastination.core.Merge.apply(emptyList(), com.thomaswcode.decrastination.core.Source.Teams, listOf(f), 0).tasks.single()
        assertEquals(true, task.tickDone)
        // Not read by Claude: it's ten minutes as it is.
        assertNull(com.thomaswcode.decrastination.core.Enrichments.jobFor(task, GmailThreads::textRead))
        assertNull(TeamsRows.returned(mapOf("title" to "x")))
        kotlin.test.assertFailsWith<java.io.IOException> { TeamsRows.allReturned(listOf(mapOf("title" to "x"))) }
    }

    @Test
    fun `the widget's own trouble is noted`() {
        assertEquals("The Teams widget's sync service is off", TeamsRows.note(mapOf("sync_service_enabled" to 0L, "status" to "idle")))
        assertEquals("The Teams widget's last sync failed: Couldn't read the Past due list", TeamsRows.note(mapOf("sync_service_enabled" to 1L, "status" to "failed", "status_message" to "Couldn't read the Past due list")))
        assertNull(TeamsRows.note(mapOf("sync_service_enabled" to 1L, "status" to "idle")))
    }
}
