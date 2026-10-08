package com.thomaswcode.decrastination.widget

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.TaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetModelTest {
    private val now = Fixtures.at("2026-10-09T17:00")

    private fun task(id: String, due: String, effort: Int = 40) = TaskItem(
        id = "teams:$id",
        source = Source.Teams,
        sourceId = id,
        title = id,
        dueAt = Fixtures.at(due),
        kind = Kind.Homework,
        sourceEffortMin = effort,
        firstSeenAt = now,
        lastSeenAt = now,
    )

    private fun model(tasks: List<TaskItem>, sources: Map<Source, SourceStatus> = emptyMap()): WidgetModel {
        val state = TaskState(tasks, sources)
        return WidgetModel.from(Planner.plan(Planner.Input(tasks, now, LONDON, Settings())), state, LONDON)
    }

    @Test
    fun `the next thing to do, its badge and minutes, and what comes after`() {
        val model = model(
            listOf(
                task("Statics", "2026-10-05T14:30"),
                task("Chapter 17", "2026-10-09T23:59", effort = 30),
                task("Essay", "2026-10-09T21:00", effort = 20),
                task("PREP 2", "2026-10-11T08:00"),
            ),
        )
        assertEquals("Do: Statics", model.headline)
        assertEquals("Overdue", model.badge)
        assertTrue(model.urgent)
        assertEquals("40 min", model.minutes)
        assertEquals("Then: Essay", model.then)
        assertEquals("3 today · 1 tomorrow", model.summary)
        // The list goes on from there: neither the next nor the one after it again.
        assertEquals(listOf("Chapter 17"), model.list.map { it.text })
        assertEquals("teams:Statics", model.taskId)
    }

    @Test
    fun `a chunk due later today shows its time`() {
        val model = model(listOf(task("Chapter 17", "2026-10-09T23:59")))
        assertEquals("Due 23:59", model.badge)
    }

    @Test
    fun `with nothing planned it says so`() {
        val model = model(emptyList())
        assertEquals("Nothing due", model.headline)
        assertEquals("Nothing due today or tomorrow", model.summary)
        assertNull(model.badge)
        assertFalse(model.urgent)
        assertNull(model.taskId)
    }

    @Test
    fun `a source that can't be read, then stale Teams data, are warned about`() {
        val failing = mapOf(Source.Gmail to SourceStatus(error = "No network"))
        assertEquals("Can't read Gmail", model(emptyList(), failing).warning)
        val stale = mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = now - 30 * 3_600_000L))
        assertEquals("Teams synced 30 h ago", model(emptyList(), stale).warning)
        assertNull(model(emptyList(), mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = now - 3_600_000L))).warning)
    }
}

class WidgetLayoutTest {
    @Test
    fun `a 2x1 shows the next thing, centred, without the refresh button`() {
        val layout = WidgetLayout.of(width = 160f, height = 60f, hasWarning = true)
        assertEquals(1, layout.headlineLines)
        assertFalse(layout.refresh)
        assertFalse(layout.then)
        assertFalse(layout.warning)
        assertEquals(0, layout.listLines)
        assertTrue(layout.centred)
    }

    @Test
    fun `a 4x1 on a phone with tall rows fits the then line and refresh`() {
        val layout = WidgetLayout.of(width = 360f, height = 82f, hasWarning = false)
        assertTrue(layout.refresh)
        assertTrue(layout.then)
        assertFalse(layout.summary)
    }

    @Test
    fun `a whole page fills with today's list`() {
        val layout = WidgetLayout.of(width = 360f, height = 430f, hasWarning = true)
        assertEquals(2, layout.headlineLines)
        assertTrue(layout.summary && layout.warning)
        assertTrue(layout.listLines >= 12)
        assertFalse(layout.centred)
    }
}
