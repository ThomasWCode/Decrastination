package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.AssessLater
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.thomaswcode.decrastination.learn.CalendarTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Conversation's flows (docs/plan_v4, Stages 4 and 5a), on temporary stores, his answers read by
 * the real chat reader from a stand-in for Claude's API ([FelixHarness]): an answer's cards made as it
 * comes and undone; a card applied and taken back; the check-in's five questions in the chat; a reply
 * from his notification, his chips or the chat under the message it answers, routed by what it is
 * (Stage 5b); his button's count; and what the app says in his name, each in the chat first and his
 * one notification after.
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

    /** His messages in the chat, oldest first. */
    private fun his() = stores.chat.value.messages.filter { it.from == Speaker.Felix }

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
        // His answer is his notification while the chat isn't on screen, sounding for it.
        val notice = assertNotNull(felix.ports.showing)
        assertEquals(listOf(his.id), notice.lines.filterNot { it.yours }.map { it.id })
        assertTrue(notice.alert)
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
                    ChatMessage("m2", Speaker.Felix, "I'd make it 90 minutes.", now - 30_000L, proposals = listOf(Proposal("p1", changes = listOf(change)))),
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
    fun `the check-in's five questions are asked in the chat one by one, each reply kept under its question, then kept and reviewed`() = runBlocking {
        conversation.remindCheckIn()
        fun asked() = his().last()
        assertEquals(FelixWords.CHECK_IN[0], asked().text)
        assertEquals(MessageKind.CheckIn to true, asked().kind to asked().byApp)
        assertEquals((1..5).map(Int::toString), asked().suggestedReplies)
        assertEquals(asked().id, felix.ports.showing?.replyTo)
        assertEquals(0, felix.ports.forms)
        // Not a number: asked again, saying what it wants.
        assertTrue(conversation.ask("Good", replyTo = asked().id))
        assertEquals(FelixWords.CHECK_IN_AGAIN + FelixWords.CHECK_IN[0], asked().text)
        for (answer in listOf("4", "Physics questions", "Football", "Less on Fridays", "Mornings")) assertTrue(conversation.ask(answer, replyTo = asked().id))
        val checkIn = stores.log.value.checkIns.single()
        assertEquals(listOf<Any>(4, "Physics questions", "Football", "Less on Fridays", "Mornings"), listOf(checkIn.feel, checkIn.avoided, checkIn.inTheWay, checkIn.change, checkIn.energy))
        assertNull(stores.chat.value.felix.checkInDraft)
        assertEquals(FelixWords.CHECK_IN_THANKS, asked().text)
        assertEquals(1, felix.ports.reviews)
        // Every question in the chat, answered, each with your reply under it; none of it a call.
        val messages = stores.chat.value.messages
        val questions = messages.filter { it.from == Speaker.Felix && it.text != FelixWords.CHECK_IN_THANKS }
        assertEquals(6, questions.size)
        for (q in questions) {
            assertNotNull(q.answeredAt, q.text)
            assertTrue(messages.any { it.from == Speaker.You && it.replyTo == q.id && it.answeredAt != null }, q.text)
        }
        assertEquals(0, felix.server.requestCount)
        // His notification holds only the thanks: the questions answered left it.
        assertEquals(listOf(FelixWords.CHECK_IN_THANKS), felix.ports.showing?.lines?.filterNot { it.yours }?.map { it.text })
    }

    @Test
    fun `a reply from his notification is kept under his message and answered in the chat, the answer his next message`() = runBlocking {
        val his = ChatMessage("r1", Speaker.Felix, "The worksheet's slot went by. Still on for tonight?", now - 5 * 60_000L, kind = MessageKind.SlotEnd, pieceKey = "teams:sheet#-1#1", suggestedReplies = listOf("Yes", "Later"))
        felix.outbox.send(his)
        assertEquals(listOf("Yes", "Later"), felix.ports.showing?.replies)
        felix.answers(felix.chatAnswer("Good: after dinner, then."))
        assertTrue(conversation.replyFromNotification("Yes, after dinner", "r1"))
        // Your words in his notification under his message, while he answers as work of its own.
        assertEquals(listOf("r1" to false, null to true), felix.ports.showing!!.lines.map { (if (it.yours) null else it.id) to it.yours })
        assertEquals(1, felix.ports.later)
        // As the reply's worker runs it.
        conversation.answerChat()
        val messages = stores.chat.value.messages
        assertEquals(listOf(Speaker.Felix to his.text, Speaker.You to "Yes, after dinner", Speaker.Felix to "Good: after dinner, then."), messages.map { it.from to it.text })
        assertEquals("r1", messages[1].replyTo)
        assertNotNull(messages[1].answeredAt)
        // His answer is in his notification too, after your words.
        assertEquals(listOf(his.text, "Yes, after dinner", "Good: after dinner, then."), felix.ports.showing!!.lines.map { it.text })
        // Sent with what it answers.
        assertTrue("The worksheet's slot went by" in felix.server.takeRequest().body.readUtf8())
    }

    @Test
    fun `the morning's list is his message in the app's words, every morning in the chat`() = runBlocking {
        conversation.morningList("Today: 2 things, 1 h", listOf("Forces worksheet · 30 min", "Vectors · 30 min"))
        val morning = his().single()
        assertEquals(MessageKind.Morning to true, morning.kind to morning.byApp)
        assertEquals("Today: 2 things, 1 h\nForces worksheet · 30 min\nVectors · 30 min", morning.text)
        assertEquals(morning.id, felix.ports.showing?.openAt)
    }

    @Test
    fun `a calendar event's question is his message, answered from its chips with no call, out of his notification`() = runBlocking {
        conversation.askAboutEvent("open day", "Open day", "Saturday 24 October", now + 86_400_000L, listOf("All of it", "A few hours", "None"))
        val question = his().single()
        assertEquals(MessageKind.EventQuestion to "open day", question.kind to question.about.eventKey)
        assertEquals(listOf("All of it", "A few hours", "None"), question.suggestedReplies)
        assertTrue(conversation.ask("a few hours", replyTo = question.id))
        assertEquals(listOf("open day" to CalendarTime.ANSWERS[1].second), felix.owners.events)
        assertNotNull(stores.chat.value.messages.single { it.id == question.id }.answeredAt)
        assertNull(felix.ports.showing)
        assertEquals(0, felix.server.requestCount)
    }

    @Test
    fun `how work went is kept for his evening question with Claude on, and asked at once in his name with it off`() = runBlocking {
        val ask = AssessLater("teams:sheet", "Forces worksheet", now - 3_600_000L)
        conversation.askHowItWent(listOf(ask))
        assertEquals(listOf(ask), stores.chat.value.felix.assessLater)
        assertTrue(his().isEmpty())
        // Claude off: what was kept asked now, a message each, its three answers his chips, answered with no call.
        stores.settings.update { it.copy(aiEnabled = false) }
        conversation.askKept()
        val question = his().single()
        assertEquals(MessageKind.Completions to listOf(ask), question.kind to question.asks)
        assertEquals(listOf("Harder", "As expected", "Easier"), question.suggestedReplies)
        assertTrue(stores.chat.value.felix.assessLater.isEmpty())
        assertTrue(conversation.ask("Harder", replyTo = question.id))
        assertEquals(listOf(Triple("teams:sheet", ask.doneAt!!, "harder")), felix.assessed)
    }

    @Test
    fun `the week's review and its summary are his messages, each with its button`() = runBlocking {
        conversation.reviewed("Nothing to change this week: the estimates held.")
        conversation.summaryReady("2026-10-12")
        val (review, summary) = his()
        assertEquals(MessageKind.Review to Conversation.OPENS_REVIEW, review.kind to review.opens)
        assertEquals(MessageKind.Summary to Conversation.OPENS_SUMMARY + "2026-10-12", summary.kind to summary.opens)
        assertEquals(FelixWords.SUMMARY_READY, summary.text)
        assertTrue(review.byApp && summary.byApp)
    }

    @Test
    fun `a reply in the chat to an older message of his is a turn of the chat, sent quoting it`() = runBlocking {
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "The essay plan's up at 18:00.", now - 20 * 60_000L, kind = MessageKind.SlotStart))
        felix.outbox.send(ChatMessage("n2", Speaker.Felix, "Ten minutes left on the worksheet.", now - 5 * 60_000L, kind = MessageKind.SlotEnd))
        felix.answers(felix.chatAnswer("Fine: 18:30 then."))
        assertTrue(conversation.ask("can it be half six?", replyTo = "n1"))
        felix.settle()
        val mine = stores.chat.value.messages.single { it.from == Speaker.You }
        assertEquals("n1" to "Fine: 18:30 then.", mine.replyTo to his().last().text)
        assertTrue("(Replying to your message: " in felix.server.takeRequest().body.readUtf8())
        // Not from his notification: nothing waits for work of its own.
        assertEquals(0, felix.ports.later)
    }

    @Test
    fun `his button counts his messages since you last left the chat, but a question you've answered`() = runBlocking {
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Up at 17:00.", now - 60_000L, kind = MessageKind.SlotStart))
        conversation.askAboutEvent("open day", "Open day", "Saturday 24 October", now + 86_400_000L, CalendarTime.ANSWERS.map { it.first })
        assertEquals(2, conversation.unread.first())
        assertTrue(conversation.ask(CalendarTime.ANSWERS[2].first, replyTo = his().last().id))
        assertEquals(1, conversation.unread.first())
        conversation.read()
        assertEquals(0, conversation.unread.first())
    }

    @Test
    fun `a reply while a message of yours still waits goes with it, and he answers both together`() = runBlocking {
        // Claude off: your message waits, saying why.
        stores.settings.update { it.copy(aiEnabled = false) }
        assertTrue(conversation.ask("What's due tomorrow?"))
        felix.settle()
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "the worksheet's up at 17:00", now, kind = MessageKind.SlotStart))
        assertTrue(conversation.replyFromNotification("on it", "n1"))
        felix.settle()
        // Both kept, only the newest saying why it waits.
        val waiting = stores.chat.value.messages.filter { it.from == Speaker.You }
        assertEquals(listOf("What's due tomorrow?", "on it"), waiting.map { it.text })
        assertNull(waiting.first().note)
        assertNotNull(waiting.last().note)
        // Claude back: one answer for both.
        stores.settings.update { it.copy(aiEnabled = true) }
        felix.answers(felix.chatAnswer("The worksheet, due Wednesday: go for it."))
        conversation.answerChat()
        assertTrue(stores.chat.value.messages.filter { it.from == Speaker.You }.all { it.answeredAt != null })
        assertEquals("The worksheet, due Wednesday: go for it.", his().last().text)
        val sent = felix.server.takeRequest().body.readUtf8()
        assertTrue("What's due tomorrow?" in sent && "on it" in sent)
        assertEquals(1, felix.server.requestCount)
    }

    @Test
    fun `a message sent while he answers lets that answer go, and he answers both together`() = runBlocking {
        // His answer to the first comes late: the second is sent while it's being made.
        felix.answers(felix.chatAnswer("Moved it to 19:00."), delayMs = 1_000)
        felix.answers(felix.chatAnswer("Moved it to 20:00, as you said."))
        assertTrue(conversation.ask("move the physics to 7"))
        withTimeout(5_000) { while (conversation.answeringSince.value == null) delay(10) }
        assertTrue(conversation.ask("no, make it 8"))
        felix.settle()
        val messages = stores.chat.value.messages
        // One answer, to both: the one made meanwhile let go, paid for and unused.
        assertEquals(
            listOf(Speaker.You to "move the physics to 7", Speaker.You to "no, make it 8", Speaker.Felix to "Moved it to 20:00, as you said."),
            messages.map { it.from to it.text },
        )
        assertTrue(messages.filter { it.from == Speaker.You }.all { it.answeredAt != null })
        assertEquals(2, felix.server.requestCount)
        felix.server.takeRequest()
        val second = felix.server.takeRequest().body.readUtf8()
        assertTrue("move the physics to 7" in second && "no, make it 8" in second)
    }
}
