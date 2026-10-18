package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.FelixRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The Conversation's flows that had no tests (docs/plan_v4, Stage 4), on temporary stores, his answers
 * read by the real chat reader from a stand-in for Claude's API ([FelixHarness]): an answer's cards
 * made as it comes and undone; a card applied and taken back; the check-in's five questions; and a
 * reply from his notification landing under the message it answers.
 */
class ConversationTest {
    private val felix = FelixHarness()
    private val conversation = felix.conversation
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    /** A Teams worksheet, due Wednesday. */
    private val sheet = TaskItem(
        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-21T09:00"),
        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
    )

    @Test
    fun `an answer's cards are made as it comes, and its Undo takes what it made back`() = runBlocking {
        felix.answers(felix.chatAnswer("Added the dentist on Wednesday at 16:20.", newTasks = """[{"title":"Dentist","kind":"Event","subject":"","dueDate":"2026-10-21","dueTime":"16:20","startDate":"","minutes":0,"notes":"","steps":[]}]"""))
        assertTrue(conversation.ask("The dentist is on Wednesday at 4:20"))
        felix.settle()
        val (mine, his) = stores.chat.value.messages
        assertEquals(Speaker.You, mine.from)
        assertNotNull(mine.answeredAt)
        assertEquals("Added the dentist on Wednesday at 16:20.", his.text)
        // Made at once: the card applied, your own task there.
        val card = his.proposals.single()
        assertEquals(ProposalState.Applied, card.state)
        val made = assertNotNull(card.made)
        assertEquals("Dentist", stores.tasks.value.tasks.single { it.id == made }.title)
        // Your message went to Claude.
        assertTrue("The dentist is on Wednesday at 4:20" in felix.server.takeRequest().body.readUtf8())
        // Its Undo: the task gone, the card to be made again.
        conversation.undoProposal(his.id, card.id)
        assertTrue(stores.tasks.value.tasks.none { it.id == made })
        val undone = stores.chat.value.messages.last().proposals.single()
        assertEquals(ProposalState.Proposed, undone.state)
        assertNull(undone.made)
    }

    @Test
    fun `a card is applied as an instruction in the words that asked for it, and taken back`() = runBlocking {
        val change = Change(ChangeType.Minutes, taskId = sheet.id, minutes = 90)
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        stores.chat.update {
            ChatState(
                listOf(
                    ChatMessage("m1", Speaker.You, "Make the worksheet 90 minutes", now - 60_000L, answeredAt = now - 30_000L),
                    ChatMessage("m2", Speaker.Claude, "I'd make it 90 minutes.", now - 30_000L, proposals = listOf(Proposal("p1", changes = listOf(change)))),
                ),
            )
        }
        conversation.applyProposal("m2", "p1")
        val instruction = stores.instructions.value.instructions.single()
        assertEquals(InstructionStatus.Applied, instruction.state)
        assertEquals("Make the worksheet 90 minutes", instruction.text)
        assertEquals(listOf(change), instruction.changes)
        val card = stores.chat.value.messages.last().proposals.single()
        assertEquals(ProposalState.Applied, card.state)
        assertEquals(instruction.id, card.made)
        // A second tap finds it made, and makes nothing more.
        conversation.applyProposal("m2", "p1")
        assertEquals(1, stores.instructions.value.instructions.size)
        conversation.undoProposal("m2", "p1")
        assertTrue(stores.instructions.value.instructions.isEmpty())
        assertEquals(ProposalState.Proposed, stores.chat.value.messages.last().proposals.single().state)
    }

    @Test
    fun `the check-in's five questions are asked one by one, each reply its answer, then kept and reviewed`() = runBlocking {
        conversation.remindCheckIn()
        fun asked() = stores.log.value.felix.last()
        assertEquals(CheckInFlow.QUESTIONS[0], asked().text)
        assertEquals((1..5).map(Int::toString), asked().replies)
        assertEquals(asked().id, felix.ports.posted.last().id)
        assertEquals(0, felix.ports.forms)
        // Not a number: asked again, saying what it wants.
        assertTrue(conversation.replyToFelix("Good", asked().id))
        assertEquals("A number from 1 to 5: ${CheckInFlow.QUESTIONS[0]}", asked().text)
        for (answer in listOf("4", "Physics questions", "Football", "Less on Fridays", "Mornings")) assertTrue(conversation.replyToFelix(answer, asked().id))
        val checkIn = stores.log.value.checkIns.single()
        assertEquals(listOf<Any>(4, "Physics questions", "Football", "Less on Fridays", "Mornings"), listOf(checkIn.feel, checkIn.avoided, checkIn.inTheWay, checkIn.change, checkIn.energy))
        assertNull(stores.runtime.value.checkInDraft)
        assertEquals("Thanks: the week's review comes next.", felix.ports.posted.last().text)
        assertEquals(1, felix.ports.reviews)
        // Every question on record with its answer.
        assertEquals(6, stores.log.value.felix.count { it.reason == FelixPrompts.Reason.CheckIn.name && it.replyText != null })
    }

    @Test
    fun `a reply from his notification is asked in the chat under his message, and its answer is his next notification`() = runBlocking {
        val his = FelixRecord("r1", now - 5 * 60_000L, FelixPrompts.Reason.SlotEnd.name, "The worksheet's slot went by. Still on for tonight?", listOf("Yes", "Later"))
        stores.log.update { it.copy(felix = listOf(his)) }
        felix.answers(felix.chatAnswer("Good: after dinner, then."))
        assertTrue(conversation.replyToFelix("Yes, after dinner", "r1"))
        // Shown as sent under his message while he answers, the answer asked as work of its own.
        assertEquals("Yes, after dinner", felix.ports.posted.last().sent)
        val (id, asked, words) = felix.ports.later.single()
        assertEquals("r1" to "Yes, after dinner", id to words)
        // As the reply's worker runs it.
        conversation.answerReply(id, asked, words)
        val messages = stores.chat.value.messages
        assertEquals(listOf("r1", asked), messages.take(2).map { it.id })
        assertEquals(Speaker.Claude to his.text, messages[0].from to messages[0].text)
        assertEquals(About(), messages[0].about)
        assertEquals(Speaker.You to words, messages[1].from to messages[1].text)
        val answer = messages[2]
        assertEquals(Speaker.Claude to "Good: after dinner, then.", answer.from to answer.text)
        assertEquals(answer.id to answer.text, felix.ports.posted.last().let { it.id to it.text })
        // On record: your reply on his message, his answer as one of his.
        assertEquals("Yes, after dinner", stores.log.value.felix.single { it.id == "r1" }.replyText)
        assertEquals(FelixPrompts.Reason.Reply.name, stores.log.value.felix.single { it.id == answer.id }.reason)
    }
}
