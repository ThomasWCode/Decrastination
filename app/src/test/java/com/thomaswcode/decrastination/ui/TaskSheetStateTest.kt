package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.saveable.SaverScope
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.tasks.TasksHarness
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The task sheet's state and changes without Compose (docs/plan_v4, Stage 6c), through the real
 * instructions' and own tasks' services on temporary stores: one of your own changed on it or refused,
 * a source's given a quick change and its Undo, tomorrow, not a task and later today, yours done, not
 * done, for home and deleted, and the state kept through a turn of the phone.
 */
class TaskSheetStateTest {
    private val h = TasksHarness()
    private val sheet = TaskSheetState(h.instructions, h.ownTasks)

    @AfterTest
    fun cleanUp() = h.close()

    private fun task(id: String) = h.stores.tasks.value.tasks.single { it.id == id }

    @Test
    fun `a change to one of your own is made on it, and one the form wouldn't take says why`() = runBlocking {
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", dueAt = Fixtures.at("2026-10-23T18:00")))
        sheet.make(task("own:mine"), Change(ChangeType.DueBy, taskId = "own:mine", time = Fixtures.at("2026-10-24T18:00")), "")
        assertEquals(Fixtures.at("2026-10-24T18:00"), task("own:mine").dueAt)
        assertNull(sheet.message)
        assertEquals(emptyList(), h.instructions.value.instructions)
        // A start after its due date: said, and not made.
        sheet.make(task("own:mine"), Change(ChangeType.StartFrom, taskId = "own:mine", time = Fixtures.at("2026-10-25T00:00")), "")
        assertEquals("It can't start after it's due", sheet.message)
        assertNull(task("own:mine").notBefore)
    }

    @Test
    fun `a source's task gets a quick change of yours, which Undo takes back`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("essay"))) }
        val (change, text) = minutesChange(task("teams:essay"), 90)
        sheet.make(task("teams:essay"), change, text)
        val quick = h.instructions.value.instructions.single()
        assertTrue(quick.quick)
        assertEquals(InstructionStatus.Applied, quick.state)
        assertEquals(text, quick.text)
        assertEquals(90, task("teams:essay").userEffortMin)
        sheet.undo(task("teams:essay"), ChangeType.Minutes)
        assertEquals(emptyList(), h.instructions.value.instructions)
        assertNull(task("teams:essay").userEffortMin)
    }

    @Test
    fun `tomorrow, not a task and later today are changes of yours, each said`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("essay"))) }
        sheet.tomorrow(task("teams:essay"), LocalDate.parse("2026-10-20"))
        assertEquals("Tomorrow: undone in the chat's Your changes", sheet.said)
        assertEquals(Change(ChangeType.OnDay, taskId = "teams:essay", date = "2026-10-20"), h.instructions.value.applied.single().changes.single())
        sheet.notATask(task("teams:essay"), LocalDate.parse("2026-10-19"), LONDON)
        assertTrue(task("teams:essay").userNotATask)
        // Off the plan now: today has no time for its piece.
        sheet.later(piece("teams:essay"), h.clock.now(), LONDON)
        assertEquals("No time left today for it", sheet.said)
    }

    @Test
    fun `yours done, not done, kept for home and deleted`() = runBlocking {
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise"))
        sheet.homeOnly(task("own:mine"), true)
        assertEquals("home", OwnTasks.draftOf(task("own:mine")).where)
        sheet.homeOnly(task("own:mine"), false)
        assertEquals("anywhere", OwnTasks.draftOf(task("own:mine")).where)
        sheet.finish(task("own:mine"))
        assertFalse(task("own:mine").isOpen)
        sheet.reopen(task("own:mine"))
        assertTrue(task("own:mine").isOpen)
        sheet.delete(task("own:mine"))
        assertEquals(emptyList(), h.stores.tasks.value.tasks)
    }

    @Test
    fun `kept through a turn of the phone`() {
        sheet.picking = OwnChange.Minutes
        sheet.confirming = "delete"
        sheet.message = "Couldn't read the photo."
        sheet.said = "Read again by Claude"
        sheet.showInstructions = true
        val saver = TaskSheetState.saver(h.instructions, h.ownTasks)
        val again = saver.restore(with(saver) { SaverScope { true }.save(sheet) }!!)
        assertNotNull(again)
        assertEquals(OwnChange.Minutes, again.picking)
        assertEquals("delete", again.confirming)
        assertEquals("Couldn't read the photo.", again.message)
        assertEquals("Read again by Claude", again.said)
        assertTrue(again.showInstructions)
        // Nothing open, nothing said: nothing comes back.
        val fresh = TaskSheetState(h.instructions, h.ownTasks)
        val none = saver.restore(with(saver) { SaverScope { true }.save(fresh) }!!)!!
        assertNull(none.picking)
        assertNull(none.confirming)
        assertNull(none.message)
        assertNull(none.said)
        assertFalse(none.showInstructions)
    }

    /** A piece of [taskId]'s, as a plan would lay it. */
    private fun piece(taskId: String) = Chunk(
        taskId = taskId, source = Source.Teams, kind = Kind.Homework, title = taskId, step = null, minutes = 30, dueAt = null,
        deadline = Fixtures.at("2026-10-26T23:59"), soft = true, overdue = false, dueToday = false, behind = false, part = 1, parts = 1, taskMinutes = 30, why = Why.Spread,
    )
}
