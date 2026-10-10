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
        // Work due later today before the overdue Statics: those deadlines can still be met.
        assertEquals("Do: Essay", model.headline)
        assertEquals("Essay", model.label)
        assertEquals("Due 21:00", model.badge)
        assertTrue(model.urgent)
        assertEquals("20 min", model.minutes)
        assertEquals("Then: Chapter 17", model.then)
        assertEquals("3 today · 1 tomorrow", model.summary)
        // The list goes on from there: neither the next nor the one after it again.
        assertEquals(listOf("Statics"), model.list.map { it.text })
        // Each row opens its own task.
        assertEquals(listOf("teams:Statics"), model.list.map { it.taskId })
        assertEquals("teams:Essay", model.taskId)
        assertEquals(0, model.more)
    }

    @Test
    fun `a long day's list says how many more the app has`() {
        val tasks = (1..34).map { task("t$it", "2026-10-09T23:00", effort = 5) }
        val model = model(tasks)
        assertEquals(30, model.list.size)
        assertEquals(2, model.more)
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
    fun `rows due today are urgent like the headline`() {
        val tasks = listOf(task("a", "2026-10-09T21:00", effort = 10), task("b", "2026-10-09T22:00", effort = 10), task("c", "2026-10-09T23:00", effort = 10))
        assertTrue(model(tasks).list.single().urgent)
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
        assertNull(model.label)
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
        // Read but never synced by the Teams widget, or not read at all: no Teams data at all.
        assertEquals("Teams hasn't synced yet", model(emptyList(), mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = null))).warning)
        assertEquals("Teams hasn't synced yet", model(emptyList()).warning)
        // The Teams widget's own trouble is shown too: a refresh it refused, its service off.
        val off = mapOf(Source.Teams to SourceStatus(lastSuccessAt = now, dataAsOf = now - 3_600_000L, note = "The Teams widget's sync service is off"))
        assertEquals("The Teams widget's sync service is off", model(emptyList(), off).warning)
    }
}

class WidgetLayoutTest {
    /** A next thing with all its parts: a badge, minutes, a "Then:" line and the label. */
    private fun next(warning: Boolean) = WidgetLayout.Parts(warning, badge = "Overdue", minutes = "40 min", then = true, label = true)

    @Test
    fun `a 2x1 shows the next thing, centred, without the refresh button`() {
        val layout = WidgetLayout.of(width = 160f, height = 60f, next(warning = true))
        assertFalse(layout.roomy)
        assertFalse(layout.label)
        assertEquals(1, layout.headlineLines)
        assertFalse(layout.refresh)
        assertFalse(layout.then)
        assertFalse(layout.warning)
        assertEquals(0, layout.listLines)
        assertTrue(layout.centred)
    }

    @Test
    fun `a 4x1 on a phone with tall rows fits the then line and refresh`() {
        val layout = WidgetLayout.of(width = 360f, height = 82f, next(warning = false))
        assertTrue(layout.refresh)
        assertTrue(layout.then)
        assertFalse(layout.summary)
        assertFalse(layout.roomy)
    }

    @Test
    fun `a 4x2 is one card, labelled, with the warning and the day's count`() {
        val layout = WidgetLayout.of(width = 360f, height = 180f, next(warning = true))
        assertFalse(layout.roomy)
        assertTrue(layout.label && layout.then && layout.warning && layout.summary)
        assertEquals(0, layout.listLines)
        assertTrue(layout.centred)
    }

    @Test
    fun `the list comes once two of its rows fit under the card`() {
        assertFalse(WidgetLayout.of(width = 360f, height = 230f, next(warning = true)).roomy)
        assertTrue(WidgetLayout.of(width = 360f, height = 230f, next(warning = false)).roomy)
        assertEquals(2, WidgetLayout.of(width = 360f, height = 212f, next(warning = false)).listLines)
    }

    @Test
    fun `a whole page fills with today's list`() {
        val layout = WidgetLayout.of(width = 360f, height = 430f, next(warning = true))
        assertTrue(layout.roomy)
        assertEquals(2, layout.headlineLines)
        assertTrue(layout.label && layout.summary && layout.warning)
        assertTrue(layout.listLines >= 6)
        assertFalse(layout.centred)
    }

    @Test
    fun `with nothing planned, the rows that aren't there leave room for the warning`() {
        // "Nothing due" alone: no pills, no "Then:" line, no label.
        val layout = WidgetLayout.of(width = 360f, height = 120f, WidgetLayout.Parts(warning = true))
        assertTrue(layout.warning)
        assertFalse(layout.then || layout.label)
        assertFalse(layout.roomy)
    }

    @Test
    fun `pills too wide to sit side by side are stacked, or plain where there's no height`() {
        val parts = WidgetLayout.Parts(badge = "Waiting a week", minutes = "1 h 20 min")
        assertEquals(WidgetLayout.Pills.Row, WidgetLayout.of(width = 360f, height = 60f, parts).pills)
        assertEquals(WidgetLayout.Pills.Plain, WidgetLayout.of(width = 160f, height = 60f, parts).pills)
        assertEquals(WidgetLayout.Pills.Stacked, WidgetLayout.of(width = 160f, height = 120f, parts).pills)
        // Roomy, inset in the widget, they stack where they would have fitted the small card.
        assertEquals(WidgetLayout.Pills.Row, WidgetLayout.of(width = 280f, height = 60f, parts).pills)
        val roomy = WidgetLayout.of(width = 280f, height = 400f, parts)
        assertTrue(roomy.roomy)
        assertEquals(WidgetLayout.Pills.Stacked, roomy.pills)
        assertEquals(WidgetLayout.Pills.Row, WidgetLayout.of(width = 360f, height = 400f, parts).pills)
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
