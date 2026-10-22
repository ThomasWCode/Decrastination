package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.By
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * His looks (LR-03) on temporary stores (docs/plan_v4), his message read by the real
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
