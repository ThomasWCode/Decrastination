package com.thomaswcode.decrastination.tasks

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Your own tasks' service on temporary stores (docs/plan_v4, Stage 6c): one kept, changed in place,
 * finished, opened again and deleted; finished by its steps; let go a fortnight after it's done; and
 * kept with what your instructions lay on it. The rules themselves are core's `OwnTasksTest`'s.
 */
class OwnTasksTest {
    private val h = TasksHarness()

    @AfterTest
    fun cleanUp() = h.close()

    private fun own(id: String = "own:mine") = h.stores.tasks.value.tasks.single { it.id == id }

    /** A chat card that added [made]. */
    private suspend fun card(made: String) {
        h.stores.chat.update { it.copy(messages = listOf(ChatMessage("m1", Speaker.Felix, "Shall I add it?", h.clock.now(), proposals = listOf(Proposal("p1", state = ProposalState.Applied, made = made))))) }
    }

    private fun cardState() = h.stores.chat.value.messages.single().proposals.single().let { it.state to it.made }

    @Test
    fun `one of yours is kept, changed in place, finished as you say, opened again and deleted`() = runBlocking {
        assertTrue(h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", dueAt = Fixtures.at("2026-10-23T18:00"), minutes = 45)))
        // Saved again under its id: changed, not a second task.
        assertTrue(h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise for the test", dueAt = Fixtures.at("2026-10-23T18:00"), minutes = 45)))
        assertEquals(listOf("own:mine"), h.stores.tasks.value.tasks.map { it.id })
        assertEquals("Revise for the test", own().title)
        // One the form wouldn't take isn't kept.
        assertFalse(h.ownTasks.saveOwnTask("bad", OwnTask(title = " ")))
        // Done as you say: on the queue a source's confirmation takes, its completions settled, once.
        h.ownTasks.finishOwnTask("own:mine")
        assertEquals(Status.Done, own().status)
        assertEquals(h.clock.now(), own().doneAt)
        assertEquals(listOf("own:mine"), h.stores.tasks.value.unrewarded.map { it.id })
        assertEquals(1, h.settled.get())
        h.ownTasks.finishOwnTask("own:mine")
        assertEquals(1, h.settled.get())
        h.ownTasks.reopenOwnTask("own:mine")
        assertTrue(own().isOpen)
        // Deleted: gone, and the card that added it can add it again.
        card("own:mine")
        h.ownTasks.deleteOwnTask("own:mine")
        assertEquals(emptyList(), h.stores.tasks.value.tasks)
        assertEquals(ProposalState.Proposed to null, cardState())
    }

    @Test
    fun `one whose steps are all done is finished by itself`() = runBlocking {
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", steps = listOf(OwnStep("Read the notes", 15), OwnStep("Do the questions", 15))))
        h.ownTasks.start()
        h.stores.tasks.update { s -> s.copy(tasks = s.tasks.map { t -> t.copy(subSteps = t.subSteps.map { it.copy(done = true) }) }) }
        h.until("finished") { !own().isOpen }
        assertEquals(Status.Done, own().status)
        assertEquals(1, h.settled.get())
    }

    @Test
    fun `yours done a fortnight ago go, and the card that added one can add it again`() = runBlocking {
        h.ownTasks.saveOwnTask("old", OwnTask(title = "Old one"))
        h.ownTasks.saveOwnTask("new", OwnTask(title = "New one"))
        h.ownTasks.finishOwnTask("own:old")
        h.clock.time += 10 * DAY_MS
        h.ownTasks.finishOwnTask("own:new")
        h.clock.time += 5 * DAY_MS
        card("own:old")
        h.ownTasks.pruneOwn()
        assertEquals(listOf("own:new"), h.stores.tasks.value.tasks.map { it.id })
        assertEquals(ProposalState.Proposed to null, cardState())
    }

    @Test
    fun `one kept takes what your instructions in force lay on it`() = runBlocking {
        // An instruction no form makes, laid over the task as any instruction is.
        val notATask = Instruction("not", "Not a task", About(taskId = "own:mine"), h.clock.now(), state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.NotATask, taskId = "own:mine")), appliedAt = h.clock.now())
        h.stores.instructions.update { it.copy(instructions = listOf(notATask)) }
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise"))
        assertTrue(own().mine.notATask != null)
        assertNull(own().doneAt)
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
