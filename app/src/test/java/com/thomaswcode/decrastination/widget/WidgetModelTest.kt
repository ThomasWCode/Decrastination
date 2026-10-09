package com.thomaswcode.decrastination.widget

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.data.RuntimeState
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
        // Each row opens its own task.
        assertEquals(listOf("teams:Chapter 17"), model.list.map { it.taskId })
        assertEquals("teams:Statics", model.taskId)
    }

    @Test
    fun `protection trouble comes first, loudest when armed`() {
        val off = RuntimeState(protection = ProtectionState(problems = listOf("The focus service is off: nothing is blocked")))
        val plan = Planner.plan(Planner.Input(emptyList(), now, LONDON, Settings()))
        assertEquals("The focus service is off: nothing is blocked", WidgetModel.from(plan, TaskState(), LONDON, off, armed = false).warning)
        assertEquals("PROTECTION OFF: The focus service is off: nothing is blocked", WidgetModel.from(plan, TaskState(), LONDON, off, armed = true).warning)
    }

    @Test
    fun `a focus session and free time show in the summary`() {
        val plan = Planner.plan(Planner.Input(emptyList(), now, LONDON, Settings()))
        val session = RuntimeState(session = com.thomaswcode.decrastination.block.FocusSession("teams:t", "Statics Prep", null, 25, now - 5 * 60_000L))
        assertEquals("Focus: Statics Prep, 20 min left", WidgetModel.from(plan, TaskState(), LONDON, session).summary)
        val credit = RuntimeState(credit = com.thomaswcode.decrastination.block.Credit().earn(java.time.LocalDate.of(2026, 10, 9), 15.0))
        assertEquals("Nothing due soon · 15 min of free time", WidgetModel.from(plan, TaskState(), LONDON, credit).summary)
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
        // Read, but never synced by the Teams widget: no Teams data at all.
        assertEquals("Teams hasn't synced yet", model(emptyList(), mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = null))).warning)
        // The Teams widget's own trouble is shown too: a refresh it refused, its service off.
        val off = mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = now - 3_600_000L, note = "The Teams widget's sync service is off"))
        assertEquals("The Teams widget's sync service is off", model(emptyList(), off).warning)
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

class WidgetRedrawTest {
    private val settings = Settings()

    private fun planAt(at: String, tasks: List<TaskItem> = emptyList()) =
        Planner.plan(Planner.Input(tasks, Fixtures.at(at), LONDON, settings))

    @Test
    fun `during the evening's hours it redraws every 15 minutes`() {
        assertEquals(Fixtures.at("2026-10-09T18:15"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T18:00"), LONDON, settings))
    }

    @Test
    fun `before them it redraws when they start, and after them at midnight`() {
        assertEquals(Fixtures.at("2026-10-09T16:45"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T12:00"), LONDON, settings))
        assertEquals(Fixtures.at("2026-10-10T00:00"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T22:10"), LONDON, settings))
    }

    @Test
    fun `a chunk that can't be started yet says from when, and the widget redraws then`() {
        // An Anki deck at 01:00 whose new cards come at 04:00, Anki's new day.
        val deck = TaskItem(
            id = "anki:deck", source = Source.Anki, sourceId = "deck", title = "deck", dueAt = Fixtures.at("2026-10-01T08:30"),
            kind = Kind.Homework, sourceEffortMin = 9, firstSeenAt = 0, lastSeenAt = 0,
            subSteps = listOf(SubStep("20 new cards", 9)), stepsPerDay = 1, notBefore = Fixtures.at("2026-10-09T04:00"),
        )
        val plan = planAt("2026-10-09T01:00", listOf(deck))
        assertEquals("From 04:00", WidgetModel.from(plan, TaskState(), LONDON).badge)
        assertEquals(Fixtures.at("2026-10-09T04:00"), WidgetUpdater.nextRedrawAt(plan, LONDON, settings))
    }

    @Test
    fun `it redraws when Teams' data turns stale`() {
        // Synced 23 h 30 min before noon: stale at 12:30, before the evening's hours start.
        val asOf = Fixtures.at("2026-10-09T12:00") - 23 * 3_600_000L - 30 * 60_000L
        assertEquals(Fixtures.at("2026-10-09T12:30"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T12:00"), LONDON, settings, asOf))
        // Already stale: nothing more to wait for.
        assertEquals(Fixtures.at("2026-10-09T16:45"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T12:00"), LONDON, settings, asOf - 3_600_000L))
    }

    @Test
    fun `a deadline before then comes first`() {
        val task = TaskItem(
            id = "teams:t", source = Source.Teams, sourceId = "t", title = "t", dueAt = Fixtures.at("2026-10-09T23:00"),
            kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = 0, lastSeenAt = 0,
        )
        assertEquals(Fixtures.at("2026-10-09T23:00"), WidgetUpdater.nextRedrawAt(planAt("2026-10-09T22:10", listOf(task)), LONDON, settings))
    }
}
