package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.StepOrigin
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * His looks (LR-03) on temporary stores (docs/plan_v4/architecture.md §The rules), his message read by the real
 * caller from a stand-in for Claude's API ([FelixHarness]): written into the chat, his one notification
 * made from it, his next look set.
 */
class LooksTest {
    private val felix = FelixHarness()
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    /** Due tomorrow morning: tonight's work, whatever the time; its slot's start kept and the clock past his grace into it. */
    private suspend fun underWay(): String {
        stores.tasks.update {
            it.copy(
                tasks = listOf(
                    TaskItem(
                        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-20T08:30"),
                        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
                    ),
                ),
            )
        }
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        stores.session.update { it.copy(slots = SlotState(today, previous = mapOf(slot.chunk!!.key to slot.start))) }
        felix.clock.time = slot.start + (stores.settings.value.felixGraceMin + 1) * 60_000L
        return slot.chunk!!.key
    }

    @Test
    fun `a look at a slot under way with nothing done writes in the chat, makes his notification from it and sets his next`() = runBlocking {
        val key = underWay()
        felix.answers("""{"message":"Ten minutes in: started the worksheet?","replies":["Yes","Not yet"],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        val message = stores.chat.value.messages.single()
        assertEquals(Speaker.Felix to MessageKind.SlotStart, message.from to message.kind)
        assertEquals("teams:sheet" to key, message.about.taskId to message.pieceKey)
        assertEquals("Forces worksheet", message.about.taskTitle)
        assertEquals("Ten minutes in: started the worksheet?" to listOf("Yes", "Not yet"), message.text to message.suggestedReplies)
        assertEquals(felix.clock.time + 15 * 60_000L, message.nextCheckAt)
        val notice = felix.ports.showing!!
        assertEquals(listOf(message.id to message.text), notice.lines.map { it.id to it.text })
        assertEquals(message.id to listOf("Yes", "Not yet"), notice.replyTo to notice.replies)
        assertTrue(notice.alert)
        val state = stores.chat.value.felix
        assertEquals(felix.clock.time + 15 * 60_000L, state.nextCheckAt)
        assertEquals(felix.clock.time, state.lastSentAt)
        assertTrue(felix.ports.looks.isNotEmpty())
        // Looked at again before the check he set: nothing more to say.
        felix.looks.check()
        assertEquals(1, stores.chat.value.messages.size)
        assertEquals(1, felix.server.requestCount)
    }

    @Test
    fun `his words for the next slot are asked for once, one that came back with none not paid for again at each look`() = runBlocking {
        underWay()
        // Five minutes before its message's moment (its start and his grace, the slot kept where it was laid): his words
        // for it asked for ahead.
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        felix.clock.time = slot.start + (stores.settings.value.felixGraceMin - 5) * 60_000L
        felix.answers("not an answer")
        felix.looks.check()
        val asked = felix.server.requestCount
        assertTrue(asked >= 1, "asked once")
        felix.clock.time += 30_000L
        felix.looks.check()
        assertEquals(asked, felix.server.requestCount)
    }

    @Test
    fun `his words written ahead aren't sent once something's been said since, yours too, but written again (D-57)`() = runBlocking {
        underWay()
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        val grace = stores.settings.value.felixGraceMin * 60_000L
        // Five minutes before its moment: written ahead, opening with a greeting.
        felix.clock.time = slot.start + grace - 5 * 60_000L
        felix.answers("""{"message":"hey, the worksheet's on. starting?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertNotNull(stores.chat.value.felix.draft)
        // You say something in the chat meanwhile, twice, with a look after each: not written again ahead, so no call an exchange.
        for (i in 1..2) {
            val said = felix.clock.time + 60_000L
            stores.chat.update { it.copy(messages = it.messages + ChatMessage("y$i", Speaker.You, "what's left tonight?", said, answeredAt = said)) }
            felix.clock.time = said + 30_000L
            felix.looks.check()
        }
        assertEquals(1, felix.server.requestCount)
        // At its moment it's written again, not sent as it was.
        felix.clock.time = slot.start + grace + 60_000L
        felix.answers("""{"message":"worksheet's 10 min in and nothing yet. starting?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertEquals("worksheet's 10 min in and nothing yet. starting?", stores.chat.value.messages.last { it.from == Speaker.Felix }.text)
        assertEquals(2, felix.server.requestCount)
    }

    @Test
    fun `his words written ahead go at their moment as written while the plan they were written from stands`() = runBlocking {
        underWay()
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        val grace = stores.settings.value.felixGraceMin * 60_000L
        felix.clock.time = slot.start + grace - 5 * 60_000L
        felix.answers("""{"message":"worksheet's on till its end. starting?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertNotNull(stores.chat.value.felix.draft)
        felix.clock.time = slot.start + grace + 60_000L
        felix.looks.check()
        assertEquals("worksheet's on till its end. starting?", stores.chat.value.messages.last { it.from == Speaker.Felix }.text)
        assertEquals(1, felix.server.requestCount)
    }

    @Test
    fun `his words written ahead aren't sent once their slot has moved, but written again at its moment`() = runBlocking {
        val key = underWay()
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        val grace = stores.settings.value.felixGraceMin * 60_000L
        felix.clock.time = slot.start + grace - 5 * 60_000L
        felix.answers("""{"message":"worksheet's on till its old end. starting?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertNotNull(stores.chat.value.felix.draft)
        // Its slot moved five minutes, its piece the same: what was written for its old times would say what isn't so.
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        stores.session.update { it.copy(slots = SlotState(today, previous = mapOf(key to slot.start + 5 * 60_000L))) }
        felix.clock.time = slot.start + 5 * 60_000L + grace + 60_000L
        assertEquals(slot.start + 5 * 60_000L, felix.planning.plan().todaySlots.first { it.chunk?.key == key }.start)
        felix.answers("""{"message":"worksheet's on, five later. starting?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertEquals("worksheet's on, five later. starting?", stores.chat.value.messages.last { it.from == Speaker.Felix }.text)
        assertEquals(2, felix.server.requestCount)
    }

    @Test
    fun `his notifications off silence only them, and he still writes in the chat`() = runBlocking {
        underWay()
        felix.shown = false
        felix.answers("""{"message":"Ten minutes in: started the worksheet?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertEquals("Ten minutes in: started the worksheet?", stores.chat.value.messages.single().text)
        assertNull(felix.ports.showing)
    }

    @Test
    fun `his silence is kept in the chat, shown nowhere, and counted as said`() = runBlocking {
        underWay()
        felix.answers("""{"message":"","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        val silent = stores.chat.value.messages.single()
        assertEquals("" to MessageKind.SlotStart, silent.text to silent.kind)
        assertNull(felix.ports.showing)
        assertEquals(1, FelixRules.sent(stores.chat.value.messages, 0).size)
    }

    @Test
    fun `the morning's call may rebalance two of his steps to fit a free time at school, made at once with an Undo, and not asked about once taken back`() = runBlocking {
        worksheetInSteps()
        felix.answers(REBALANCED)
        assertTrue(felix.looks.morning())
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("Steps you may rebalance" in asked && "teams:sheet | Forces worksheet | 0" in asked && "10:45, 30 min" in asked, asked)
        val message = stores.chat.value.messages.single()
        val proposal = message.proposals.single()
        assertEquals(MessageKind.Morning to ProposalState.Applied, message.kind to proposal.state)
        // His change on the task, named by what it made, its two steps moved and their ids kept.
        val made = stores.instructions.value.instructions.single { it.id == proposal.made }
        assertEquals(By.Felix, made.by)
        assertEquals("“Forces worksheet”: “Question 1” (25 min), then “Questions 2 and 3” (40 min)", made.text)
        val steps = made.changes.single().steps!!
        assertEquals(listOf("Question 1" to 25, "Questions 2 and 3" to 40), steps.map { it.title to it.minutes })
        assertEquals(listOf("q12", "q3"), steps.map { it.id })
        // Taken back: the change gone, and the task no longer one to ask about.
        felix.conversation.undoProposal(message.id, proposal.id)
        assertTrue(stores.instructions.value.instructions.none { it.id == proposal.made })
        assertEquals(setOf("teams:sheet"), Rebalance.declined(stores.chat.value.messages))
        assertEquals(emptyList(), Rebalance.candidates(felix.planning.plan(), stores.tasks.value.tasks, Rebalance.declined(stores.chat.value.messages), felix.clock.now()))
    }

    @Test
    fun `the reading's steps at a learned pace are rebalanced with his total at that pace beside them, both taken back by its Undo`() = runBlocking {
        // The worksheet's two steps the reading's, homework's pace learned at 1.1: laid at 50 and 22 minutes, 72 in all.
        worksheetInSteps()
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { t -> t.copy(subSteps = t.subSteps.map { it.copy(origin = StepOrigin.Reading) }) }) }
        stores.learning.update { it.copy(calibration = it.calibration.copy(multipliers = mapOf("Homework|" to 1.1))) }
        felix.answers(REBALANCED.replace("\"secondMinutes\":40", "\"secondMinutes\":47"))
        assertTrue(felix.looks.morning())
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("“Questions 1 and 2” (50 min), then “Question 3” (22 min)" in asked, asked)
        val message = stores.chat.value.messages.single()
        val proposal = message.proposals.single()
        val made = stores.instructions.value.instructions.single { it.id == proposal.made }
        assertEquals(listOf(ChangeType.Steps, ChangeType.Minutes), made.changes.map { it.type })
        assertEquals(listOf(25, 47), made.changes[0].steps!!.map { it.minutes })
        // As the plan takes the task: his total at your pace, which still teaches the pace against the reading's 65.
        val planned = felix.planning.asPlanned().single()
        assertEquals(listOf(72, 65), listOf(planned.effortMin, planned.mine.minutes?.paced?.from))
        assertTrue(planned.pacedTeaches)
        // Taken back: the reading's steps and its total as they were.
        felix.conversation.undoProposal(message.id, proposal.id)
        assertTrue(stores.instructions.value.instructions.none { it.id == proposal.made })
        val back = felix.planning.asPlanned().single()
        assertEquals(65 to listOf(45, 20), back.effortMin to back.subSteps.map { it.minutes })
    }

    @Test
    fun `a rebalance isn't made for a task finished while the morning's call was out, nor his words for it sent`() = runBlocking {
        worksheetInSteps()
        felix.answers(REBALANCED, delayMs = 1_000)
        val finished = launch {
            delay(300)
            stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = felix.clock.now()) }) }
        }
        // The task he was asked about is done: nothing made, and his message, which says it was, not sent.
        assertFalse(felix.looks.morning())
        finished.join()
        assertEquals(emptyList(), stores.chat.value.messages)
        assertEquals(emptyList(), stores.instructions.value.instructions)
    }

    @Test
    fun `a morning whose rebalance can't be made isn't his, as his words would say it was`() = runBlocking {
        worksheetInSteps()
        // 35 minutes for break's 30: not one, so not made.
        felix.answers(REBALANCED.replace("\"firstMinutes\":25", "\"firstMinutes\":35").replace("\"secondMinutes\":40", "\"secondMinutes\":30"))
        assertFalse(felix.looks.morning())
        assertEquals(emptyList(), stores.chat.value.messages)
        assertEquals(emptyList(), stores.instructions.value.instructions)
    }

    @Test
    fun `a rebalance whose first step a plan tried with it would lay elsewhere isn't made, nor his words for it sent`() = runBlocking {
        // Two worksheets: the first is shown break, the second lunch. He rebalances the second alone, and
        // break, left free, would take its first step: not the lunch his message names.
        worksheetInSteps()
        stores.tasks.update { s -> s.copy(tasks = s.tasks + s.tasks.single().copy(id = "teams:sheet2", sourceId = "sheet2", title = "Waves worksheet")) }
        felix.answers(
            """{"message":"morning. waves q1 at lunch.","replies":[],"nextCheckMin":null,"memories":[],
              "rebalances":[{"taskId":"teams:sheet2","stepIndex":0,"firstTitle":"Question 1","firstMinutes":20,"secondTitle":"Questions 2 and 3","secondMinutes":45}]}""",
        )
        assertFalse(felix.looks.morning())
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("teams:sheet | Forces worksheet | 0" in asked && "teams:sheet2 | Waves worksheet | 0" in asked && "13:15, 25 min" in asked, asked)
        assertEquals(emptyList(), stores.chat.value.messages)
        assertEquals(emptyList(), stores.instructions.value.instructions)
    }

    @Test
    fun `a rebalance is tried by the settings as they are once the call is back, a rest changed while it was out among them`() = runBlocking {
        // A reading kept at break and vocabulary at the second lunch's start: the first lunch is the free time
        // shown, its end 15 minutes before the vocabulary.
        worksheetInSteps()
        stores.tasks.update { s -> s.copy(tasks = s.tasks + atSchool("teams:reading", "Reading", 25) + atSchool("teams:vocab", "Vocabulary", 20)) }
        val chunks = felix.planning.plan().todayBucket!!.chunks
        val starts = mapOf("teams:reading" to "2026-10-20T10:45", "teams:vocab" to "2026-10-20T13:55")
        stores.session.update { it.copy(slots = SlotState("2026-10-20", previous = starts.entries.associate { (id, at) -> chunks.first { c -> c.taskId == id }.key to Fixtures.at(at) })) }
        felix.answers(REBALANCED, delayMs = 1_000)
        // A long rest after a run of 20 minutes, and of 20, set while the call is out: 15 are too few after the 25 he'd put there.
        val changed = launch {
            delay(300)
            stores.settings.update { it.copy(slotLongBreakAfterMin = 20, slotLongBreakMin = 20) }
        }
        assertFalse(felix.looks.morning())
        changed.join()
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("13:15, 25 min" in asked, asked)
        assertEquals(emptyList(), stores.chat.value.messages)
        assertEquals(emptyList(), stores.instructions.value.instructions)
    }

    /** Due tomorrow morning, in one step that can be done at school: today's work, a start kept at school its own. */
    private fun atSchool(id: String, title: String, minutes: Int) = TaskItem(
        id = id, source = Source.Teams, sourceId = id.substringAfter(':'), title = title, dueAt = Fixtures.at("2026-10-21T08:30"),
        kind = Kind.Homework, sourceEffortMin = minutes, firstSeenAt = now, lastSeenAt = now,
        subSteps = listOf(SubStep(title, minutes, where = "anywhere", id = id.substringAfter(':'), origin = StepOrigin.Reading)),
    )

    /**
     * A school day's 07:00: the worksheet, due tomorrow morning, in two of his steps; 45 minutes is longer
     * than break (30) or either lunch (25 and 40), so its first is tonight's.
     */
    private suspend fun worksheetInSteps() {
        felix.clock.time = Fixtures.at("2026-10-20T07:00")
        stores.tasks.update {
            it.copy(
                tasks = listOf(
                    TaskItem(
                        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-21T08:30"),
                        kind = Kind.Homework, sourceEffortMin = 65, firstSeenAt = now, lastSeenAt = now,
                        subSteps = listOf(
                            SubStep("Questions 1 and 2", 45, where = "anywhere", id = "q12", origin = StepOrigin.Felix),
                            SubStep("Question 3", 20, where = "anywhere", id = "q3", origin = StepOrigin.Felix),
                        ),
                    ),
                ),
            )
        }
    }

    private companion object {
        /** His morning's answer, the worksheet's first two steps rebalanced. */
        const val REBALANCED = """{"message":"morning. question 1 at break, the rest tonight.","replies":[],"nextCheckMin":null,"memories":[],
            "rebalances":[{"taskId":"teams:sheet","stepIndex":0,"firstTitle":"Question 1","firstMinutes":25,"secondTitle":"Questions 2 and 3","secondMinutes":40}]}"""
    }
}
