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
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.StepOrigin
import com.thomaswcode.decrastination.core.SubStep
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Your instructions' service on temporary stores (docs/plan_v4/architecture.md §The rules): a quick change applied and
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
    fun `new steps on a task let go of its starts kept today still to come, so they're laid afresh (D-110)`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("sheet", minutes = 65))) }
        val now = h.clock.now()
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        val kept = mapOf("teams:sheet#0" to now + 3_600_000L, "teams:sheet#1" to now - 60_000L, "teams:other#-1#1" to now + 7_200_000L)
        h.stores.session.update { it.copy(slots = SlotState(today, previous = kept)) }
        write(understood("steps", Change(ChangeType.Steps, taskId = "teams:sheet", steps = listOf(OwnStep("Question 1", 25), OwnStep("Questions 2 and 3", 40)))))
        h.instructions.applyInstruction("steps")
        assertEquals(InstructionStatus.Applied, state("steps")!!.state)
        // Its start to come let go of; its step begun, and another task's, kept.
        assertEquals(kept - "teams:sheet#0", h.stores.session.value.slots.previous)
    }

    @Test
    fun `a step new steps leave as it was keeps its start kept today, so the times said of it hold (D-110)`() = runBlocking {
        val steps = listOf(SubStep("Questions 1 and 2", 40, id = "q12", origin = StepOrigin.Felix), SubStep("Question 3", 20, id = "q3", origin = StepOrigin.Felix), SubStep("Write it up", 30, id = "up", origin = StepOrigin.Felix))
        h.stores.tasks.update { it.copy(tasks = listOf(h.teams("sheet", minutes = 90).copy(subSteps = steps))) }
        val now = h.clock.now()
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        val kept = mapOf("teams:sheet#0" to now + 3_600_000L, "teams:sheet#1" to now + 7_200_000L, "teams:sheet#2" to now + 10_800_000L)
        h.stores.session.update { it.copy(slots = SlotState(today, previous = kept)) }
        // The first two rebalanced, the third as it was.
        val given = listOf(OwnStep("Question 1", 20, id = "q12"), OwnStep("Questions 2 and 3", 40, id = "q3"), OwnStep("Write it up", 30, id = "up"))
        write(understood("steps", Change(ChangeType.Steps, taskId = "teams:sheet", steps = given)))
        h.instructions.applyInstruction("steps")
        assertEquals(mapOf("teams:sheet#2" to now + 10_800_000L), h.stores.session.value.slots.previous)
    }

    @Test
    fun `your own task given new steps lets go of the starts of those it changes too, its change made on it first`() = runBlocking {
        val steps = listOf(OwnStep("Read chapter 3", 30, id = "ch3"), OwnStep("Notes on chapter 3", 20, id = "notes"), OwnStep("Flashcards", 15, id = "cards"))
        h.ownTasks.saveOwnTask("mine", OwnTask(title = "Revise", dueAt = Fixtures.at("2026-10-23T18:00"), minutes = 65, steps = steps))
        val now = h.clock.now()
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        val kept = mapOf("own:mine#0" to now + 3_600_000L, "own:mine#1" to now + 7_200_000L, "own:mine#2" to now + 10_800_000L)
        h.stores.session.update { it.copy(slots = SlotState(today, previous = kept)) }
        // Felix's change, given without ids as his are: the first two rewritten, the third as it was.
        val given = listOf(OwnStep("Read chapter 3, part 1", 15), OwnStep("Read chapter 3, part 2, then notes", 35), OwnStep("Flashcards", 15))
        write(understood("steps", Change(ChangeType.Steps, taskId = "own:mine", steps = given)))
        h.instructions.applyInstruction("steps")
        assertEquals(listOf("Read chapter 3, part 1", "Read chapter 3, part 2, then notes", "Flashcards"), task("own:mine").subSteps.map { it.title })
        assertEquals(mapOf("own:mine#2" to now + 10_800_000L), h.stores.session.value.slots.previous)
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

    @Test
    fun `your reply to his question is read shown the question it was given, before the chat has your reply to find it by`() = runBlocking {
        TasksHarness(claude = true).use { c ->
            c.stores.tasks.update { it.copy(tasks = listOf(c.teams("sheet"))) }
            c.answers("""{"understood":false,"question":null,"changes":[],"answersQuestion":true}""")
            c.instructions.addInstruction("yes", About(taskId = "teams:sheet", taskTitle = "sheet"), answer = true, question = "is the extension part of it?")
            c.until("its reading asked") { c.server.requestCount == 1 }
            val sent = c.server.takeRequest().body.readUtf8()
            // The question given, not the one found by its task (the harness's stands for his newest about it).
            assertTrue("is the extension part of it?" in sent && TasksHarness.QUESTION !in sent, sent.take(400))
            c.until("handed to Felix") { c.answersRead.isNotEmpty() }
            assertEquals(Triple("yes", true, false), c.answersRead.single())
        }
    }

    @Test
    fun `your reply written again while it's read is read again shown the question it was given`() = runBlocking {
        TasksHarness(claude = true).use { c ->
            c.stores.tasks.update { it.copy(tasks = listOf(c.teams("sheet"))) }
            c.answers("""{"understood":false,"question":null,"changes":[],"answersQuestion":true}""", delayMs = 1_000)
            c.answers("""{"understood":false,"question":null,"changes":[],"answersQuestion":true}""")
            val id = c.instructions.addInstruction("yes", About(taskId = "teams:sheet", taskTitle = "sheet"), answer = true, question = "is the extension part of it?")
            c.until("its reading asked") { c.server.requestCount == 1 }
            c.instructions.rereadInstruction(id, "yes, all of it")
            c.until("read again") { c.server.requestCount == 2 }
            c.server.takeRequest()
            val again = c.server.takeRequest().body.readUtf8()
            assertTrue("is the extension part of it?" in again && TasksHarness.QUESTION !in again, again.take(400))
        }
    }

    @Test
    fun `your reply to his question is read shown his words, and handed to Felix with whether it answers it and whether a change of it was made`() = runBlocking {
        TasksHarness(claude = true).use { c ->
            c.stores.tasks.update { it.copy(tasks = listOf(c.teams("sheet"))) }
            suspend fun reply(id: String, text: String) =
                c.stores.instructions.update { it.copy(instructions = it.instructions + Instruction(id, text, About(taskId = "teams:sheet", taskTitle = "sheet"), c.clock.now(), answer = true)) }
            // No answer, nothing to change: gone, handed on as such, and read shown his words.
            reply("a", "is the plan ticked in teams?")
            c.answers("""{"understood":false,"question":null,"changes":[],"answersQuestion":false}""")
            c.instructions.readInstructions()
            assertEquals(listOf(Triple("is the plan ticked in teams?", false, false)), c.answersRead.toList())
            assertNull(c.stores.instructions.value.instructions.firstOrNull { it.id == "a" })
            val sent = c.server.takeRequest().body.readUtf8()
            assertTrue("their reply to the question asked about it" in sent && TasksHarness.QUESTION in sent, sent.take(400))
            // No answer but a change, made: handed on as made; refused (after its real deadline), as none made.
            fun due(date: String) = """{"understood":true,"question":null,"answersQuestion":false,"changes":[{"type":"DueBy","taskId":"teams:sheet","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"$date","endDate":"","weekday":"","time":"","endTime":"","title":"","steps":[]}]}"""
            reply("b", "it's due thursday")
            c.answers(due("2026-10-22"))
            c.instructions.readInstructions()
            assertEquals(Triple("it's due thursday", false, true), c.answersRead.last())
            reply("c", "make it next month")
            val late = """{"understood":true,"question":null,"answersQuestion":false,"changes":[{"type":"AtTime","taskId":"teams:sheet","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"2026-11-20","endDate":"","weekday":"","time":"17:00","endTime":"","title":"","steps":[]}]}"""
            c.answers(late)
            c.instructions.readInstructions()
            assertEquals(Triple("make it next month", false, false), c.answersRead.last())
            // That one written again (Write again): read as an instruction of yours, no longer a reply, so not handed on again.
            c.instructions.rereadInstruction("c", "make it next month")
            c.answers(late)
            c.instructions.readInstructions()
            assertEquals(3, c.answersRead.size)
            repeat(2) { c.server.takeRequest() }
            assertTrue("their reply" !in c.server.takeRequest().body.readUtf8())
            // Discarded while its reading was out: nothing handed on.
            reply("d", "all of it")
            c.answers("""{"understood":false,"question":null,"changes":[],"answersQuestion":true}""", delayMs = 1_000)
            val reading = c.scope.launch { c.instructions.readInstructions() }
            c.until("its reading asked") { c.server.requestCount == 5 }
            c.instructions.deleteInstruction("d")
            reading.join()
            assertEquals(3, c.answersRead.size)
        }
    }
}
