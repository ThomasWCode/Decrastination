package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.sync.GmailFinishes
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
    fun `what's remembered that has run out isn't in the profile, and is let go of, silently`() = runBlocking {
        val hour = 3_600_000L
        stores.memory.update {
            MemoryState(
                facts = listOf(Fact("football", "on Tuesdays", now - hour), Fact("headache", "for the evening", now - hour, until = now + hour)),
                rules = listOf(Rule("not tonight", now - hour, until = now - 1)),
            )
        }
        val profile = conversation.profile(now).text
        assertTrue("- headache: for the evening (until " in profile, profile)
        assertFalse("not tonight" in profile, profile)
        felix.settle()
        assertEquals(emptyList(), stores.memory.value.rules)
        // An hour on, the headache's gone too, with no line in the chat.
        conversation.forgetExpired(now + 2 * hour)
        assertEquals(listOf("football"), stores.memory.value.facts.map { it.topic })
        assertTrue(stores.chat.value.messages.isEmpty())
    }

    @Test
    fun `what's run out is let go of before anything's remembered, so it takes no rule's place`() = runBlocking {
        val hour = 3_600_000L
        stores.memory.update { MemoryState(rules = (1..Memories.RULE_MAX).map { Rule("rule $it", now - 2 * hour, until = now - hour) }) }
        val edits = conversation.remember(listOf(Given(text = "be blunter", kind = Given.RULE)))
        assertEquals(listOf("Felix will: be blunter"), edits.map { it.line })
        assertEquals(listOf("be blunter"), stores.memory.value.rules.map { it.text })
    }

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
    fun `a check-in's answer given while a message of yours waits leaves it waiting, and it's answered after`() = runBlocking {
        // Claude off: your message waits, saying why.
        stores.settings.update { it.copy(aiEnabled = false) }
        assertTrue(conversation.ask("What's due tomorrow?"))
        felix.settle()
        conversation.remindCheckIn()
        val question = his().last()
        assertTrue(conversation.ask("4", replyTo = question.id))
        // The "4", answered as it came with its question's kind, doesn't hide what waits.
        assertEquals(MessageKind.CheckIn, stores.chat.value.messages.last().kind)
        assertEquals("What's due tomorrow?", stores.chat.value.unanswered?.text)
        stores.settings.update { it.copy(aiEnabled = true) }
        felix.answers(felix.chatAnswer("The forces worksheet, due Wednesday."))
        conversation.answerChat()
        assertNull(stores.chat.value.unanswered)
        assertEquals("The forces worksheet, due Wednesday.", stores.chat.value.messages.last { it.from == Speaker.Felix && it.kind == MessageKind.Chat }.text)
    }

    @Test
    fun `with his notifications off the check-in is asked in the chat all the same, and the form's reminder says so`() = runBlocking {
        felix.shown = false
        conversation.remindCheckIn()
        assertEquals(MessageKind.CheckIn, his().single().kind)
        assertEquals(1, felix.ports.forms)
        assertNull(felix.ports.showing)
    }

    @Test
    fun `a week before's check-in questions never finished are settled as a new one starts`() = runBlocking {
        conversation.remindCheckIn()
        val old = his().single()
        // A week on, nothing answered: this week's asked, and last week's question no longer takes an answer.
        felix.clock.time += 7 * 86_400_000L
        conversation.remindCheckIn()
        assertNotNull(stores.chat.value.messages.single { it.id == old.id }.answeredAt)
        assertEquals(2, his().size)
        assertNull(his().last().answeredAt)
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
        // In his register; either form taken (the words the app keeps, or his).
        assertEquals(listOf("harder", "about right", "easier"), question.suggestedReplies)
        assertTrue(stores.chat.value.felix.assessLater.isEmpty())
        assertTrue(conversation.ask("Harder", replyTo = question.id))
        assertEquals(listOf(Triple("teams:sheet", ask.doneAt!!, "harder")), felix.assessed)
    }

    @Test
    fun `how work went, handed over again after a stop, is asked once`() = runBlocking {
        val ask = AssessLater("teams:sheet", "Forces worksheet", now - 3_600_000L)
        // Kept with Claude on; then, Claude off, the completions handed over again (twice) and the kept ones asked.
        conversation.askHowItWent(listOf(ask))
        stores.settings.update { it.copy(aiEnabled = false) }
        conversation.askHowItWent(listOf(ask))
        conversation.askHowItWent(listOf(ask))
        conversation.askKept()
        assertEquals(listOf(listOf(ask)), his().filter { it.kind == MessageKind.Completions }.map { it.asks })
        assertTrue(stores.chat.value.felix.assessLater.isEmpty())
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
    fun `the box's words to his nudge take none of its subject, told him as they are, and still answer it`() = runBlocking {
        stores.tasks.update { it.copy(tasks = it.tasks + sheet) }
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Starting the worksheet now?", now - 60_000L, About(taskId = sheet.id, taskTitle = sheet.title), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting", "Later")))
        felix.answers(felix.chatAnswer("Tomorrow's the worksheet."))
        assertTrue(conversation.ask("what's due tomorrow?", replyTo = "n1", takeItsSubject = false))
        felix.settle()
        val mine = stores.chat.value.messages.single { it.from == Speaker.You }
        assertEquals("n1" to About(), mine.replyTo to mine.about)
        val sent = felix.server.takeRequest().body.readUtf8()
        assertFalse("(About the task" in sent)
        assertFalse("The task this message is about" in sent)
        // Answered by it, all the same; a reply you chose (a swipe) takes its subject, as ever.
        assertFalse("n1" in Replies.awaitingIds(stores.chat.value.messages, checkInUnderWay = false, now = now, zone = felix.clock.zone()))
        felix.answers(felix.chatAnswer("Fine."))
        assertTrue(conversation.ask("can it wait till seven?", replyTo = "n1"))
        felix.settle()
        assertEquals(sheet.id, stores.chat.value.messages.last { it.from == Speaker.You }.about.taskId)
    }

    @Test
    fun `his answer names what of his still waiting your words answered, and only those the context listed are marked`() = runBlocking {
        stores.tasks.update { it.copy(tasks = it.tasks + sheet) }
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Starting the worksheet now?", now - 60_000L, About(taskId = sheet.id, taskTitle = sheet.title), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting", "Later")))
        felix.outbox.send(ChatMessage("c1", Speaker.Felix, "All done for today.", now - 30_000L))
        felix.answers(felix.chatAnswer("Good, keep going.", answered = """["n1","c1","nobody's"]"""))
        // Typed, not a reply to it: it waits till his answer says your words answered it.
        assertTrue(conversation.ask("started it, on question 2"))
        felix.settle()
        val sent = felix.server.takeRequest().body.readUtf8()
        assertTrue("Your messages still waiting for their answer (id" in sent && "- n1 | " in sent)
        // His answer to you isn't one that waits: not listed, and not marked by naming it.
        assertFalse("- c1 | " in sent)
        assertEquals(now, his().single { it.id == "n1" }.answeredAt)
        assertNull(his().single { it.id == "c1" }.answeredAt)
        // Said under his answer, quietly: no Undo, nothing wrong.
        val line = his().last().acts.single()
        assertEquals("Taken as answered: “Starting the worksheet now?”" to null, line.line to line.action)
        assertFalse(line.problem)
        assertFalse("n1" in Replies.awaitingIds(stores.chat.value.messages, checkInUnderWay = false, now = now, zone = felix.clock.zone()))
    }

    @Test
    fun `a message of his written after yours, while its answer waits, isn't one your words can answer`() = runBlocking {
        stores.tasks.update { it.copy(tasks = it.tasks + sheet) }
        // Your words first; his nudge after them, before the call that answers them is made.
        stores.chat.update { it.copy(messages = it.messages + ChatMessage("y1", Speaker.You, "started it", now - 120_000L)) }
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Starting the worksheet now?", now - 60_000L, About(taskId = sheet.id, taskTitle = sheet.title), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting", "Later")))
        felix.answers(felix.chatAnswer("Good.", answered = """["n1"]"""))
        conversation.answerChat()
        felix.settle()
        assertFalse("- n1 | " in felix.server.takeRequest().body.readUtf8())
        assertNull(his().single { it.id == "n1" }.answeredAt)
    }

    @Test
    fun `a question of his whose answer the app keeps isn't his to mark answered, so its answer isn't lost`() = runBlocking {
        stores.tasks.update { it.copy(tasks = it.tasks + sheet) }
        val ask = AssessLater(sheet.id, sheet.title, now - 3_600_000L)
        felix.outbox.send(ChatMessage("q1", Speaker.Felix, "How was the worksheet?", now - 60_000L, kind = MessageKind.Completions, asks = listOf(ask), suggestedReplies = listOf("Harder", "As expected", "Easier")))
        felix.answers(felix.chatAnswer("Noted.", answered = """["q1"]"""))
        assertTrue(conversation.ask("the worksheet was harder than I thought"))
        felix.settle()
        assertFalse("- q1 | " in felix.server.takeRequest().body.readUtf8())
        // Still waiting for its reply, which records how it went.
        assertNull(his().single { it.id == "q1" }.answeredAt)
    }

    @Test
    fun `with nothing of his waiting, the context lists none, and an id his answer names is let go`() = runBlocking {
        felix.answers(felix.chatAnswer("Fine.", answered = """["n9"]"""))
        assertTrue(conversation.ask("what's due tomorrow?"))
        felix.settle()
        assertFalse("Your messages still waiting for their answer (id" in felix.server.takeRequest().body.readUtf8())
        assertTrue(stores.chat.value.messages.all { it.answeredAt == null || it.from == Speaker.You })
    }

    @Test
    fun `his done on a starred email finishes it in Gmail, says so, and its Undo takes the finish back`() = runBlocking {
        val email = sheet.copy(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip consent form", kind = Kind.Admin)
        stores.tasks.update { it.copy(tasks = it.tasks + email) }
        felix.answers(felix.chatAnswer("Finished it.", actions = """[{"type":"done","taskId":"gmail:t1","stepIndex":-1,"setting":"","value":""}]"""))
        assertTrue(conversation.ask("I'm done with the trip email"))
        felix.settle()
        val answer = his().last()
        assertEquals("Finished: “Trip consent form” (unstarring and archiving it in Gmail)", answer.acts.single().line)
        // Its own finish's id kept, for its Undo to take back that one.
        assertEquals("w-gmail:t1", answer.acts.single().after)
        assertEquals(listOf("gmail:t1"), felix.owners.gmailFinished)
        // Still open here: Gmail's read closes it once the write is made.
        assertTrue(stores.tasks.value.tasks.single { it.id == "gmail:t1" }.isOpen)
        assertNull(conversation.undoAct(answer.id, 0))
        assertEquals(listOf("gmail:t1"), felix.owners.gmailUnfinished)
        assertTrue(his().last().acts.single().undone)
    }

    @Test
    fun `his done on an email can still be taken back with its task gone, as after a backup's restored`() = runBlocking {
        val email = sheet.copy(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip consent form", kind = Kind.Admin)
        stores.tasks.update { it.copy(tasks = it.tasks + email) }
        felix.answers(felix.chatAnswer("Finished it.", actions = """[{"type":"done","taskId":"gmail:t1","stepIndex":-1,"setting":"","value":""}]"""))
        assertTrue(conversation.ask("I'm done with the trip email"))
        felix.settle()
        val answer = his().last()
        // Restored: the chat and the journal's receipt back, but no task of the email, unstarred in Gmail.
        stores.tasks.update { it.copy(tasks = it.tasks.filterNot { t -> t.id == "gmail:t1" }) }
        val act = answer.acts.single()
        assertTrue(ChatActs.canUndo(act, stores.tasks.value.tasks, stores.settings.value, stores.chat.value.messages, finishing = setOf("w-gmail:t1")))
        assertFalse(ChatActs.canUndo(act, stores.tasks.value.tasks, stores.settings.value, stores.chat.value.messages))
        assertNull(conversation.undoAct(answer.id, 0))
        assertEquals(listOf("gmail:t1"), felix.owners.gmailUnfinished)
    }

    @Test
    fun `the very first finish says Gmail waits for your OK, and with the switch off his done is refused as before`() = runBlocking {
        val email = sheet.copy(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Trip consent form", kind = Kind.Admin)
        stores.tasks.update { it.copy(tasks = it.tasks + email) }
        felix.owners.gmailFinish = GmailFinishes.Finish.Awaiting
        felix.answers(felix.chatAnswer("Finished it.", actions = """[{"type":"done","taskId":"gmail:t1","stepIndex":-1,"setting":"","value":""}]"""))
        assertTrue(conversation.ask("I'm done with the trip email"))
        felix.settle()
        assertEquals("Finished here: “Trip consent form”; Gmail waits for your OK in the app", his().last().acts.single().line)
        stores.settings.update { it.copy(gmailFinishes = false) }
        felix.answers(felix.chatAnswer("Finished it.", actions = """[{"type":"done","taskId":"gmail:t1","stepIndex":-1,"setting":"","value":""}]"""))
        assertTrue(conversation.ask("done with it"))
        felix.settle()
        assertTrue("“Trip consent form” is done when Gmail says so" in his().last().note.orEmpty())
        assertEquals(1, felix.owners.gmailFinished.size)
    }

    @Test
    fun `marked answered, his messages wait no more, but a check-in's question under way, and their Undo puts them back`() = runBlocking {
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Starting now?", now - 60_000L, kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting")))
        conversation.askAboutEvent("open day", "Open day", "Saturday 24 October", now + 86_400_000L, CalendarTime.ANSWERS.map { it.first })
        val event = his().single { it.kind == MessageKind.EventQuestion }.id
        val marked = conversation.markAnswered(listOf("n1", event, "nobody's"))
        assertEquals(setOf("n1", event), marked.ids)
        assertTrue(his().all { it.answeredAt == now })
        // Out of his notification, too.
        assertNull(felix.ports.showing)
        conversation.unmarkAnswered(marked)
        assertTrue(his().all { it.answeredAt == null })
        // The check-in's question waits on it while it's under way.
        conversation.remindCheckIn()
        val question = his().last()
        assertEquals(MessageKind.CheckIn, question.kind)
        assertEquals(emptySet(), conversation.markAnswered(listOf(question.id)).ids)
        assertNull(his().last().answeredAt)
    }

    @Test
    fun `his button doesn't count a message you've replied to from his notification, and counts what he says after`() = runBlocking {
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "Up at 17:00.", now - 60_000L, kind = MessageKind.SlotStart))
        assertEquals(1, conversation.unread.first())
        stores.chat.update { it.copy(messages = it.messages + ChatMessage("y1", Speaker.You, "on it", now - 30_000L, replyTo = "n1")) }
        assertEquals(0, conversation.unread.first())
        felix.outbox.send(ChatMessage("n2", Speaker.Felix, "Good, keep going.", now, kind = MessageKind.SlotStart))
        assertEquals(1, conversation.unread.first())
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
    fun `a reply to a question scrolled back to in the archive is answered as it, with no call`() = runBlocking {
        val question = ChatMessage("e1", Speaker.Felix, "Is the open day all day?", now - 70 * 86_400_000L, About(eventKey = "open day", eventTitle = "Open day"), kind = MessageKind.EventQuestion, byApp = true, suggestedReplies = CalendarTime.ANSWERS.map { it.first })
        felix.stores.chatArchive.update { it.copy(messages = listOf(question)) }
        assertEquals(Conversation.Asked.Answer, conversation.send(CalendarTime.ANSWERS[0].first, replyTo = "e1"))
        assertEquals(listOf("open day" to CalendarTime.ANSWERS[0].second), felix.owners.events)
        // Answered there, so it takes no second answer.
        assertNotNull(felix.stores.chatArchive.value.messages.single().answeredAt)
        assertEquals(0, felix.server.requestCount)
    }

    @Test
    fun `an answer with no call from his notification, while a message of yours waits, asks for no work`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        assertTrue(conversation.ask("What's due tomorrow?"))
        felix.settle()
        conversation.remindCheckIn()
        assertTrue(conversation.replyFromNotification("4", his().last().id))
        assertEquals(0, felix.ports.later)
        // A turn of the chat from it does.
        felix.outbox.send(ChatMessage("n1", Speaker.Felix, "the worksheet's up at 17:00", now, kind = MessageKind.SlotStart))
        assertTrue(conversation.replyFromNotification("on it", "n1"))
        assertEquals(1, felix.ports.later)
    }

    @Test
    fun `of messages that waited together, those he wasn't sent say so, and wait for their Try again`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        (1..14).forEach { assertTrue(conversation.ask("question $it")) }
        felix.settle()
        stores.settings.update { it.copy(aiEnabled = true) }
        felix.answers(felix.chatAnswer("All of them, in order."))
        conversation.answerChat()
        val mine = stores.chat.value.messages.filter { it.from == Speaker.You }
        // He's sent the conversation's last twelve: the first two weren't in it, and say so, with Try again.
        assertEquals((1..14).map { it > 2 }, mine.map { it.answeredAt != null })
        assertEquals("question 2", stores.chat.value.unanswered?.text)
        assertTrue(mine.take(2).all { it.note != null && it.stuck })
        // Not asked unasked: only Try again asks for it.
        conversation.answerChat()
        assertEquals(1, felix.server.requestCount)
    }

    @Test
    fun `a message sent while he answers lets go what that answer would still look up, with no call for it`() = runBlocking {
        // His answer to the first comes late and asks to look something up: the second is sent meanwhile.
        val looks = felix.chatAnswer("Let me check when it opens.").replace("\"research\":\"\"", "\"research\":\"When does the science museum open on Saturdays?\"")
        felix.answers(looks, delayMs = 1_000)
        felix.answers(felix.chatAnswer("It opens at 10:00; moved the physics to 8."))
        assertTrue(conversation.ask("when does the museum open?"))
        withTimeout(5_000) { while (conversation.answeringSince.value == null) delay(10) }
        assertTrue(conversation.ask("and move the physics to 8"))
        felix.settle()
        // No search for the answer let go: his one answer, to both, the only other call.
        assertEquals(2, felix.server.requestCount)
        assertEquals("It opens at 10:00; moved the physics to 8.", his().last().text)
    }

    @Test
    fun `a check-in question from a week long gone, scrolled back to in the archive, is a turn of the chat while this week's is asked`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        val old = ChatMessage("c0", Speaker.Felix, FelixWords.CHECK_IN[0], now - 70 * 86_400_000L, kind = MessageKind.CheckIn, byApp = true)
        felix.stores.chatArchive.update { it.copy(messages = listOf(old)) }
        conversation.remindCheckIn()
        val draft = stores.chat.value.felix.checkInDraft
        assertNotNull(draft)
        assertEquals(Conversation.Asked.Turn, conversation.send("3", replyTo = "c0"))
        felix.settle()
        // This week's is where it was, its question still waiting for its answer.
        assertEquals(draft, stores.chat.value.felix.checkInDraft)
        assertNull(his().last { it.kind == MessageKind.CheckIn }.answeredAt)
    }

    @Test
    fun `taken back, the newest of yours waiting leaves the one before it saying why it waits`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        assertTrue(conversation.ask("What's due tomorrow?"))
        felix.settle()
        assertTrue(conversation.ask("And on Friday?"))
        felix.settle()
        val why = stores.chat.value.unanswered?.note
        assertNotNull(why)
        conversation.withdrawQuestion()
        assertEquals("What's due tomorrow?" to why, stores.chat.value.unanswered?.let { it.text to it.note })
    }

    @Test
    fun `one that waited for its Try again still does when the newest of yours is taken back`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        assertTrue(conversation.ask("What's due tomorrow?"))
        felix.settle()
        // Its answer couldn't be read: it waits for Try again.
        stores.chat.update { s -> s.copy(messages = s.messages.map { if (it.text == "What's due tomorrow?") it.copy(note = "Claude's answer couldn't be read: Try again", stuck = true) else it }) }
        assertTrue(conversation.ask("And on Friday?"))
        felix.settle()
        conversation.withdrawQuestion()
        assertEquals(true, stores.chat.value.unanswered?.stuck)
        // Claude back: nothing's asked by itself.
        stores.settings.update { it.copy(aiEnabled = true) }
        conversation.answerChat()
        assertEquals(0, felix.server.requestCount)
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
