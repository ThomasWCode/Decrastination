package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SetBy
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.TaskState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Work page as the website gets it (W-14): every open task, its groups the phone's, and nothing of its own. */
class WorkViewTest {
    private val now = Fixtures.at("2026-10-12T17:00")
    private val monday = LocalDate.parse("2026-10-12")

    private fun task(id: String, due: String?, source: Source = Source.Teams, status: Status = Status.Open, mine: Mine = Mine()) = TaskItem(
        id = "${source.name.lowercase()}:$id", source = source, sourceId = id, title = id, dueAt = due?.let { Fixtures.at(it) }, kind = Kind.Homework,
        sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now, status = status, mine = mine, doneAt = if (status == Status.Done) now else null,
    )

    private fun view(vararg tasks: TaskItem, settings: Settings = Settings(), asOf: Long? = 9L, writable: Boolean = false): WorkView {
        val plan = Planner.plan(Planner.Input(tasks.toList(), now, LONDON, settings, windows = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))))
        return WorkViews.of(TaskState(tasks.toList()), plan, settings, now, LONDON, asOf, writable)
    }

    @Test
    fun `work that looks the same comes with what saying so sends, where it can be said`() {
        val teams = task("Essay on energy", "2026-10-14T12:00").copy(className = "12X/Ph1")
        val planner = task("Essay on energy", "2026-10-14T12:00", Source.PowerPlanner).copy(className = "12X/Ph1")
        val tile = view(teams, planner, writable = true).byDue.flatMap { it.tasks }.first { it.source == "Teams" }
        assertEquals("“Essay on energy” from Power Planner", tile.sameAs)
        assertNotNull(tile.same)
        assertNotNull(tile.different)
        val mirroring = view(teams, planner).byDue.flatMap { it.tasks }.first { it.source == "Teams" }
        assertEquals(tile.sameAs, mirroring.sameAs, "it's still said")
        assertNull(mirroring.same)
        assertNull(mirroring.different)
    }

    @Test
    fun `open tasks go in the phone's due groups, in order`() {
        val page = view(task("late", "2026-10-11T12:00"), task("today", "2026-10-12T22:00"), task("tomorrow", "2026-10-13T12:00"), task("friday", "2026-10-16T12:00"), task("next", "2026-10-22T12:00"), task("none", null))
        assertEquals(listOf("Overdue", "Today", "Tomorrow", "This week", "Later", "No due date"), page.byDue.map { it.title })
        assertEquals(listOf("late"), page.byDue[0].tasks.map { it.title })
        assertTrue(page.byDue[0].tasks.single().overdue)
        assertFalse(page.byDue[1].tasks.single().overdue)
    }

    @Test
    fun `a tile says where it's from, and every source is there for the filter`() {
        val page = view(task("a", "2026-10-13T12:00"), task("b", "2026-10-13T12:00", source = Source.Own))
        val tiles = page.byDue.flatMap { it.tasks }
        assertEquals(setOf(Source.Teams.label, Source.Own.label), tiles.map { it.source }.toSet())
        assertTrue(tiles.all { it.source in page.sources })
        assertTrue(tiles.first { it.title == "a" }.line.startsWith(Source.Teams.label))
        assertEquals(9L, page.asOf)
    }

    @Test
    fun `what's done goes to finished, what's said not to be a task to hidden, neither among the open`() {
        val page = view(task("done", "2026-10-13T12:00", status = Status.Done), task("no", "2026-10-13T12:00", mine = Mine(notATask = SetBy(true, By.You))))
        assertEquals(emptyList(), page.byDue.flatMap { it.tasks })
        assertEquals(listOf("done"), page.finished.map { it.title })
        assertEquals(listOf("no"), page.hidden.map { it.title })
        assertEquals(listOf("Not a task"), page.hidden.single().tags)
    }

    @Test
    fun `your due date is tagged, and a task with room has no word on why it's not planned`() {
        val page = view(task("a", "2026-10-13T12:00", mine = Mine(dueAt = SetBy(Fixtures.at("2026-10-14T12:00"), By.You))))
        assertEquals(listOf("Due date yours"), page.byDue.single().tasks.single().tags)
        assertEquals(emptyList(), page.notPlanned)
    }

    @Test
    fun `a task that can't start yet says why and how long, with its source for the filter`() {
        val later = task("later", "2026-10-14T12:00").copy(availableFrom = Fixtures.at("2026-10-13T09:00"))
        val gap = assertNotNull(view(later).notPlanned.singleOrNull())
        assertEquals(Source.Teams.label, gap.source)
        assertTrue(gap.why.startsWith("Can't be started before"), gap.why)
        assertTrue(gap.why.endsWith(Source.Teams.label), gap.why)
        assertEquals("30 min", gap.minutes)
    }

    @Test
    fun `each source says when it was read, one never read saying so`() {
        val line = view().read.first { it.source == Source.Teams.label }
        assertEquals("Not read yet", line.read)
        assertEquals(0, line.open)
        assertNull(line.error)
    }
}
