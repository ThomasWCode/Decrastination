package com.thomaswcode.decrastination.sources.teams

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
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
        val statics = tasks.single { it.title == "Statics Prep" }
        assertEquals(Kind.Homework, statics.kind)
        assertEquals("12.2-PH3", statics.className)
        assertEquals("Page 7-11 of booklet.", statics.detail)
        assertEquals(Fixtures.at("2026-10-05T14:30"), statics.dueAt)
        assertEquals("PastDue", statics.extra[TeamsRows.EXTRA_TAB])
    }

    @Test
    fun `a row without a key is skipped, a blank title named`() {
        assertNull(TeamsRows.fetched(mapOf("title" to "x")))
        assertEquals("Untitled assignment", TeamsRows.fetched(mapOf("key" to "k", "title" to " "))!!.title)
    }

    @Test
    fun `the widget's own trouble is noted`() {
        assertEquals("The Teams widget's sync service is off", TeamsRows.note(mapOf("sync_service_enabled" to 0L, "status" to "idle")))
        assertEquals("The Teams widget's last sync failed: Couldn't read the Past due list", TeamsRows.note(mapOf("sync_service_enabled" to 1L, "status" to "failed", "status_message" to "Couldn't read the Past due list")))
        assertNull(TeamsRows.note(mapOf("sync_service_enabled" to 1L, "status" to "idle")))
    }
}
