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
import com.thomaswcode.decrastination.core.WeeklyRule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Your own tasks' service on temporary stores (docs/plan_v4/architecture.md §The rules): one kept, changed in place,
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
    fun `yours done a fortnight ago are kept as their record, and the card that added one stays made`() = runBlocking {
        h.ownTasks.saveOwnTask("old", OwnTask(title = "Old one", notes = "Pages 4 to 9", steps = listOf(OwnStep("Read", 15), OwnStep("Note", 15))))
        h.ownTasks.saveOwnTask("new", OwnTask(title = "New one", notes = "Pages 10 to 12"))
        h.ownTasks.finishOwnTask("own:old")
        h.clock.time += 10 * DAY_MS
        h.ownTasks.finishOwnTask("own:new")
        h.clock.time += 5 * DAY_MS
        card("own:old")
        val made = cardState()
        val old = h.stores.tasks.value.tasks.first { it.id == "own:old" }
        h.ownTasks.pruneOwn()
        // Both kept: the older without its notes, the rest of it (its title, steps, when it was done) as it was.
        assertEquals(listOf("own:old", "own:new"), h.stores.tasks.value.tasks.map { it.id })
        assertEquals(old.copy(detail = ""), h.stores.tasks.value.tasks.first { it.id == "own:old" })
        assertEquals("Pages 10 to 12", h.stores.tasks.value.tasks.first { it.id == "own:new" }.detail)
        assertEquals(made, cardState())
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

    /** A rule of weekly work in use, as a card of Felix's leaves it. */
    private suspend fun rule(rule: WeeklyRule, type: ChangeType = ChangeType.Weekly, id: String = "weekly-${rule.id}") {
        h.stores.instructions.update { state ->
            state.copy(instructions = state.instructions + Instruction(id, "weekly", at = h.clock.now(), state = InstructionStatus.Applied, changes = listOf(Change(type, weekly = rule)), appliedAt = h.clock.now()))
        }
    }

    private fun weeks() = h.stores.tasks.value.tasks.filter { it.weekly != null }

    @Test
    fun `weekly work is made as its rule says, once, and goes when it's taken back`() = runBlocking {
        val guitar = WeeklyRule("g", "Guitar practice", 180, setAt = h.clock.now())
        rule(guitar)
        h.ownTasks.rollWeeks()
        h.ownTasks.rollWeeks()
        // A Monday's rule: the whole of it, this week and next.
        assertEquals(listOf("2026-10-19" to 180, "2026-10-26" to 180), weeks().map { it.weekly!!.week to it.effortMin })
        assertEquals("2026-10-26", h.stores.tasks.value.weeksMade["g"])
        // Taken back with nothing done: they go, and made again with it, they come back.
        h.instructions.deleteInstruction("weekly-g")
        h.ownTasks.rollWeeks()
        assertEquals(emptyList(), weeks())
        assertEquals(emptyMap(), h.stores.tasks.value.weeksMade)
        rule(guitar)
        h.ownTasks.rollWeeks()
        assertEquals(2, weeks().size)
    }

    @Test
    fun `a week's task deleted stays gone, and a week ended short is kept, closed, with no way to open it`() = runBlocking {
        val guitar = WeeklyRule("g", "Guitar practice", 180, setAt = h.clock.now())
        rule(guitar)
        h.ownTasks.rollWeeks()
        val (first, next) = weeks()
        h.ownTasks.deleteOwnTask(next.id)
        h.ownTasks.rollWeeks()
        assertEquals(listOf(first.id), weeks().map { it.id })
        h.stores.tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == first.id) it.copy(workedMin = 60) else it }) }
        h.clock.time = Fixtures.at("2026-10-26T07:00")
        h.ownTasks.rollWeeks()
        val closed = weeks().single { it.id == first.id }
        assertEquals(Status.Missed, closed.status)
        assertEquals(60, closed.workedMin)
        h.ownTasks.reopenOwnTask(first.id)
        assertEquals(Status.Missed, weeks().single { it.id == first.id }.status)
        // The week deleted isn't made again; the one after it is.
        assertEquals(listOf("2026-11-02"), weeks().filter { it.isOpen }.map { it.weekly!!.week })
    }

    @Test
    fun `a rule's change comes in from the app's start`() = runBlocking {
        h.ownTasks.start()
        rule(WeeklyRule("g", "Guitar practice", 180, setAt = h.clock.now()))
        val until = System.currentTimeMillis() + 5_000
        while (weeks().size < 2 && System.currentTimeMillis() < until) delay(10)
        assertEquals(2, weeks().size)
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
