package com.thomaswcode.decrastination.tasks

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Speaker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Your instructions' service on temporary stores (docs/plan_v4): a quick change applied and
 * taken back; one refused, past a real deadline, on your own task as the form wouldn't take it, or in
 * a circle; one deleted, discarded or taken back, its card told; what one makes on the school calendar
 * and on your own tasks; and those waiting for Claude while it can't be asked. The rules themselves are
 * core's `InstructionsTest`'s.
 */
class InstructionsTest {
    private val h = TasksHarness()

    @AfterTest
    fun cleanUp() = h.close()

    /** An instruction Claude has read into [changes], not yet applied. */
    private fun understood(id: String, vararg changes: Change) =
        Instruction(id, "made up: $id", About(), h.clock.now(), state = InstructionStatus.Understood, changes = changes.toList())

    private suspend fun write(vararg instructions: Instruction) {
        h.stores.instructions.update { s -> s.copy(instructions = s.instructions + instructions) }
    }

    private fun state(id: String) = h.instructions.value.instructions.firstOrNull { it.id == id }

    private fun task(id: String) = h.stores.tasks.value.tasks.single { it.id == id }

    /** [task] waits until [first] is done. */
    private fun after(task: String, first: String) = Change(ChangeType.After, taskId = "teams:$task", afterTaskId = "teams:$first")

    @Test
    fun `a quick change of yours is kept read, applied at once on its task, and taken back`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("essay"))) }
        val about = About(taskId = "teams:essay", taskTitle = "essay")
        h.instructions.addOwnChange("q1", "Takes 2 h in all (set in Tasks)", about, Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 120))
        val kept = state("q1")!!
        assertEquals(InstructionStatus.Applied, kept.state)
        assertTrue(kept.quick)
        assertEquals(120, task("teams:essay").mine.minutes?.minutes)
        assertTrue(h.instructions.quickInForce("teams:essay", ChangeType.Minutes))
        // Laid over everything it changes: the calendar read again, the widget drawn.
        assertTrue(h.rereads.get() > 0 && h.redraws.get() > 0)
        // A second of the same kind takes the first's place.
        h.instructions.addOwnChange("q2", "Takes 1 h 30 in all (set in Tasks)", about, Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 90))
        assertEquals(listOf("q2"), h.instructions.value.instructions.map { it.id })
        assertEquals(90, task("teams:essay").mine.minutes?.minutes)
        // Taken back: what its source says stands again.
        h.instructions.takeBackQuick("teams:essay", ChangeType.Minutes)
        assertEquals(emptyList(), h.instructions.value.instructions)
        assertNull(task("teams:essay").mine.minutes?.minutes)
        assertFalse(h.instructions.quickInForce("teams:essay", ChangeType.Minutes))
    }

    @Test
    fun `one is refused, saying why, past a real deadline, or leaving one of your own as the form wouldn't take it`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("essay", due = Fixtures.at("2026-10-20T09:00")))) }
        write(understood("late", Change(ChangeType.AtTime, taskId = "teams:essay", date = "2026-10-20", startMin = 10 * 60, time = Fixtures.at("2026-10-20T10:00"))))
        h.instructions.applyInstruction("late")
        assertEquals(InstructionStatus.Unclear, state("late")!!.state)
        assertEquals("It's due before then", state("late")!!.note)
        // One of your own given a start after its due date.
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", dueAt = Fixtures.at("2026-10-21T18:00")))
        write(understood("start", Change(ChangeType.StartFrom, taskId = "own:mine", time = Fixtures.at("2026-10-22T00:00"))))
        h.instructions.applyInstruction("start")
        assertEquals(InstructionStatus.Unclear, state("start")!!.state)
        assertEquals("It would leave “Revise” as it can't be: it can't start after it's due", state("start")!!.note)
        assertNull(task("own:mine").notBefore)
    }

    @Test
    fun `one that has tasks wait in a circle is refused, and so is taking back one that brings a circle back`() = runBlocking {
        write(understood("a-b", after("a", "b")), understood("b-c", after("b", "c")), understood("c-a", after("c", "a")))
        h.instructions.applyInstruction("a-b")
        h.clock.time += 60_000
        h.instructions.applyInstruction("b-c")
        h.clock.time += 60_000
        h.instructions.applyInstruction("c-a")
        assertEquals(InstructionStatus.Unclear, state("c-a")!!.state)
        assertEquals("It would have tasks wait for each other in a circle, so none would ever be planned", state("c-a")!!.note)
        // a waits for d instead: then c can wait for a.
        write(understood("a-d", after("a", "d")))
        h.clock.time += 60_000
        h.instructions.applyInstruction("a-d")
        h.stores.instructions.update { s -> s.copy(instructions = s.instructions.map { if (it.id == "c-a") it.copy(state = InstructionStatus.Understood, note = null) else it }) }
        h.clock.time += 60_000
        h.instructions.applyInstruction("c-a")
        assertEquals(InstructionStatus.Applied, state("c-a")!!.state)
        // Taking a's back would bring a waiting for b back, and the circle with it: it stays, saying so.
        h.instructions.deleteInstruction("a-d")
        assertEquals(InstructionStatus.Applied, state("a-d")!!.state)
        assertTrue(state("a-d")!!.note!!.startsWith("Taking this back would bring back an earlier instruction"))
    }

    @Test
    fun `deleted, one applied is taken back and its card can make it again, and one being read is discarded`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("essay"))) }
        write(understood("read", Change(ChangeType.Minutes, taskId = "teams:essay", minutes = 30)))
        h.stores.chat.update { it.copy(messages = listOf(ChatMessage("m1", Speaker.Felix, "Shall I?", h.clock.now(), proposals = listOf(Proposal("p1", state = ProposalState.Applied, made = "read"))))) }
        h.instructions.applyInstruction("read")
        assertEquals(30, task("teams:essay").mine.minutes?.minutes)
        h.instructions.deleteInstruction("read")
        assertNull(state("read"))
        assertNull(task("teams:essay").mine.minutes?.minutes)
        val card = h.stores.chat.value.messages.single().proposals.single()
        assertEquals(ProposalState.Proposed, card.state)
        assertNull(card.made)
        // One still being read is simply discarded.
        val id = h.instructions.addInstruction("Make the essay shorter", About(taskId = "teams:essay"))
        h.instructions.deleteInstruction(id)
        assertNull(state(id))
    }

    @Test
    fun `a day off goes into the school calendar and a due date onto your own task, and then the instruction's done with`() = runBlocking {
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", dueAt = Fixtures.at("2026-10-23T18:00")))
        write(understood("off", Change(ChangeType.NoSchool, date = "2026-10-26", endDate = "2026-10-30"), Change(ChangeType.DueBy, taskId = "own:mine", time = Fixtures.at("2026-10-24T18:00"))))
        h.instructions.applyInstruction("off")
        assertEquals(listOf(NoSchool("2026-10-26", "2026-10-30")), h.stores.school.value.noSchool)
        assertEquals(Fixtures.at("2026-10-24T18:00"), task("own:mine").dueAt)
        assertNull(state("off"))
    }

    @Test
    fun `days off and a half-day added and deleted in Settings go into the school calendar and out of it`() = runBlocking {
        val off = NoSchool("2026-10-26", "2026-10-30", "a holiday")
        val half = HalfDay("2026-10-23", 12 * 60 + 30)
        h.instructions.addSchoolDays(off)
        h.instructions.addSchoolDays(half)
        assertEquals(listOf(off), h.stores.school.value.noSchool)
        assertEquals(listOf(half), h.stores.school.value.halfDays)
        h.instructions.deleteSchoolDays(off)
        h.instructions.deleteSchoolDays(half)
        assertEquals(SchoolCalendar(), h.stores.school.value)
    }

    @Test
    fun `those waiting for Claude say why while it can't be asked`() = runBlocking {
        write(Instruction("wait", "Shorter, please", About(), h.clock.now()))
        h.instructions.readInstructions()
        val waiting = state("wait")
        assertNotNull(waiting)
        assertEquals(InstructionStatus.Reading, waiting.state)
        assertEquals("Waiting for Claude, which is off (Settings)", waiting.note)
    }
}
