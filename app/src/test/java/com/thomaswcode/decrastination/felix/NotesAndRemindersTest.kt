package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse

/**
 * Notes and reminders through the Conversation (docs/plan_v4/07-notes-and-reminders.md), on
 * temporary stores, his answers from a stand-in for Claude's API ([FelixHarness]): made at once from his
 * answer, each with its Undo, undone only while it's as he left it.
 */
class NotesAndRemindersTest {
    private val felix = FelixHarness("2026-10-20T17:00")
    private val conversation = felix.conversation
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    private companion object {
        const val MIN = 60_000L
    }

    /** A Teams worksheet, due Thursday. */
    private val sheet = TaskItem(
        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-22T09:00"),
        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
    )

    /** A Remind change (Q13: B), as his answer gives one. */
    private fun reminder(id: String = "", text: String = "call gran", time: String = "", taskId: String = "", whenIs: String = "at") =
        """{"type":"Remind","taskId":"$taskId","afterTaskId":"$id","eventKey":"$whenIs","eventTime":"","minutes":-1,"date":"","endDate":"","weekday":"","time":"$time","endTime":"","title":"$text","steps":[],"paced":false}"""

    /** A Note change on [taskId]. */
    private fun note(taskId: String, text: String) =
        """{"type":"Note","taskId":"$taskId","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"","endDate":"","weekday":"","time":"","endTime":"","title":"$text","steps":[],"paced":false}"""

    private fun answer(reply: String, reminders: List<String> = emptyList(), notes: List<String> = emptyList(), actions: List<String> = emptyList()) =
        """{"reply":"$reply","newTasks":[],"changes":[${(reminders + notes).joinToString(",")}],"memories":[],"suggestedReplies":[],"actions":[${actions.joinToString(",")}],"research":"","details":[]}"""

    private fun action(type: String, value: String) = """{"type":"$type","taskId":"","stepIndex":-1,"setting":"","value":"$value"}"""

    /** His answer to [words], made, and the message it is. */
    private fun asked(words: String, answer: String): ChatMessage = runBlocking {
        felix.answers(answer)
        assertTrue(conversation.ask(words))
        felix.settle()
        stores.chat.value.messages.last { it.from == Speaker.Felix }
    }

    private fun canUndo(message: ChatMessage, i: Int): Boolean {
        val m = stores.chat.value.messages.first { it.id == message.id }
        return ChatActs.canUndo(m.acts[i], stores.tasks.value.tasks, stores.settings.value, stores.chat.value.messages, stores.felix.value.notes, stores.felix.value.reminders)
    }

    @Test
    fun `a reminder and a note from his answer are made at once, each said with its Undo, which takes it back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        val his = asked(
            "remind me at 7 to call gran, and note on the sheet to show the working",
            answer("I'll remind you at 7, and it's noted.", reminders = listOf(reminder(time = "19:00")), notes = listOf(note(sheet.id, "show the working"))),
        )
        val reminder = stores.felix.value.reminders.single()
        assertEquals(Due(DueKind.At, Fixtures.at("2026-10-20T19:00")) to "call gran", reminder.due to reminder.text)
        val note = stores.felix.value.notes.single()
        assertEquals(sheet.id to "show the working", note.taskId to note.text)
        assertEquals(listOf("Reminder set: Tue 20 Oct 19:00, “call gran”", "Noted on “Forces worksheet”: show the working"), his.acts.map { it.line })
        assertEquals(listOf(ChatAction.Type.SetReminder, ChatAction.Type.AddNote), his.acts.map { it.action?.type })
        assertTrue(canUndo(his, 0) && canUndo(his, 1))
        // Undone: both gone, and their Undo with them.
        assertNull(conversation.undoAct(his.id, 0))
        assertNull(conversation.undoAct(his.id, 1))
        assertTrue(stores.felix.value.reminders.isEmpty() && stores.felix.value.notes.isEmpty())
        assertFalse(canUndo(his, 0) || canUndo(his, 1))
    }

    @Test
    fun `a reminder's Undo is gone once it's sent, snoozed or moved again, and a move's puts its old time and words back`() = runBlocking {
        val set = asked("remind me at 7 to call gran", answer("At 7.", reminders = listOf(reminder(time = "19:00"))))
        val id = stores.felix.value.reminders.single().id
        val moved = asked("push it to 8 and say ring gran", answer("Moved to 20:00.", reminders = listOf(reminder(id = id, text = "ring gran", time = "20:00"))))
        assertEquals("Moved: “ring gran” to Tue 20 Oct 20:00", moved.acts.single().line)
        assertEquals("call gran", moved.acts.single().reminder?.text)
        // The set can't be taken back from under the move; the move can.
        assertFalse(canUndo(set, 0))
        assertTrue(canUndo(moved, 0))
        assertNull(conversation.undoAct(moved.id, 0))
        val back = stores.felix.value.reminders.single()
        assertEquals(Due(DueKind.At, Fixtures.at("2026-10-20T19:00")) to "call gran", back.due to back.text)
        // With the move taken back, the set can be.
        assertTrue(canUndo(set, 0))
        // Sent, it can't.
        stores.felix.update { s -> s.copy(reminders = s.reminders.map { it.copy(sentAt = now) }) }
        assertFalse(canUndo(set, 0))
        assertEquals("It's changed since, so it can't be undone here", conversation.undoAct(set.id, 0))
        assertEquals(1, stores.felix.value.reminders.size)
    }

    /** A reminder of [text] at [at], set from the chat ten minutes before now. */
    private fun at(text: String, at: Long, taskId: String? = null) = Reminder("r-$text", text, now - 10 * MIN, By.Felix, taskId, Due(DueKind.At, at))

    @Test
    fun `a reminder whose moment has come is his message in your words, the app's, with its replies, and then it's done with`() = runBlocking {
        // Claude off: no words of his, written ahead or asked for at its moment, so yours go.
        stores.settings.update { it.copy(aiEnabled = false) }
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 5 * MIN))) }
        felix.looks.sweepReminders(now)
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        felix.clock.time = now + 5 * MIN
        felix.looks.sweepReminders(felix.clock.time)
        val sent = stores.chat.value.messages.single { it.kind == MessageKind.Reminder }
        assertEquals(FelixWords.reminder("call gran"), sent.text)
        assertTrue(sent.byApp && sent.from == Speaker.Felix && sent.reminderId == "r-call gran")
        assertEquals(FelixWords.REMINDER_REPLIES, sent.suggestedReplies)
        // On his notification, sounding for it.
        assertEquals(sent.id, felix.ports.showing?.replyTo)
        assertTrue(felix.ports.showing!!.alert)
        assertEquals(now + 5 * MIN, stores.felix.value.reminders.single().sentAt)
        // Swept again, nothing more.
        felix.looks.sweepReminders(felix.clock.time + MIN)
        assertEquals(1, stores.chat.value.messages.count { it.kind == MessageKind.Reminder })
    }

    @Test
    fun `with his words written for its moment they go as his, and late yours go, marked so`() = runBlocking {
        val moment = now + 5 * MIN
        stores.felix.update { s ->
            s.copy(reminders = listOf(
                at("call gran", moment).copy(draft = FelixAnswer("oi, you said to call gran. between slots now, so good time"), draftedAt = moment - 8 * MIN, draftFor = moment),
                at("stretch", now - 40 * MIN).copy(draft = FelixAnswer("stretch now"), draftedAt = now - 45 * MIN, draftFor = now - 40 * MIN),
            ))
        }
        felix.clock.time = moment
        felix.looks.sweepReminders(moment)
        val (late, his) = stores.chat.value.messages.filter { it.kind == MessageKind.Reminder }
        assertEquals("oi, you said to call gran. between slots now, so good time", his.text)
        assertFalse(his.byApp)
        // Forty-five minutes late: the moment his words were for has gone, so yours, with when it was for.
        assertEquals(FelixWords.reminder("stretch") + " " + FelixWords.late("16:20"), late.text)
        assertTrue(late.byApp)
        assertTrue(stores.felix.value.reminders.all { it.draft == null && it.sentAt == moment })
    }

    @Test
    fun `a reminder's chips act only on one that came, not one moved while its message went`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 60 * MIN))) }
        // Its message stands from the moment it was moved away from; it never came.
        val sent = ChatMessage("reminder-r-call gran-1", Speaker.Felix, FelixWords.reminder("call gran"), now, kind = MessageKind.Reminder, byApp = true, reminderId = "r-call gran", suggestedReplies = FelixWords.REMINDER_REPLIES)
        stores.chat.update { it.copy(messages = it.messages + sent) }
        assertTrue(conversation.ask(FelixWords.REMINDER_DONE, replyTo = sent.id))
        felix.settle()
        val r = stores.felix.value.reminders.single()
        assertTrue(r.doneAt == null && Reminders.unsent(r), r.toString())
        assertEquals(0, felix.server.requestCount)
    }

    @Test
    fun `a draft past his bounds, as a restored backup's could be, goes held to them`() = runBlocking {
        val moment = now + 5 * MIN
        val long = FelixAnswer("call gran now. " + "on and on ".repeat(100), replies = (1..9).map { "reply $it" })
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-gran", "call gran", now - 10 * MIN, By.Felix, due = Due(DueKind.At, moment), draft = long, draftedAt = now, draftFor = moment))) }
        felix.clock.time = moment
        felix.looks.sweepReminders(moment)
        val sent = stores.chat.value.messages.single { it.kind == MessageKind.Reminder }
        assertTrue(sent.text.length <= FelixAnswers.MAX_MESSAGE && sent.text.startsWith("call gran now."), sent.text)
    }

    @Test
    fun `a reminder before a deadline the same answer sets is made, its changes being made first`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(dueAt = null))) }
        val due = """{"type":"DueBy","taskId":"${sheet.id}","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"2026-10-23","endDate":"","weekday":"","time":"09:00","endTime":"","title":"","steps":[],"paced":false}"""
        val before = """{"type":"Remind","taskId":"${sheet.id}","afterTaskId":"","eventKey":"beforeDue","eventTime":"","minutes":1440,"date":"","endDate":"","weekday":"","time":"","endTime":"","title":"start the worksheet","steps":[],"paced":false}"""
        val his = asked("it's due Friday at 9, remind me the day before", answer("Due Friday, and I'll remind you the day before.", notes = listOf(due, before)))
        assertTrue(his.acts.any { it.action?.type == ChatAction.Type.SetReminder }, his.acts.toString() + his.note)
        assertEquals(DueKind.BeforeDue, stores.felix.value.reminders.single().due.kind)
    }

    @Test
    fun `a note whose task went while he answered isn't made, and says so`() = runBlocking {
        val other = sheet.copy(id = "teams:other", sourceId = "other", title = "Optics sheet")
        stores.tasks.update { it.copy(tasks = listOf(sheet, other)) }
        felix.answers(answer("Noted.", notes = listOf(note(other.id, "use the ray box"))), delayMs = 1_500)
        assertTrue(conversation.ask("note on the optics sheet: use the ray box"))
        delay(500)
        stores.tasks.update { s -> s.copy(tasks = s.tasks.filter { it.id != other.id }) }
        felix.settle()
        val his = stores.chat.value.messages.last { it.from == Speaker.Felix }
        assertTrue(stores.felix.value.notes.isEmpty())
        assertTrue(his.acts.single().let { it.problem && "its task went" in it.line }, his.acts.toString())
    }

    @Test
    fun `one whose task closes before it comes is let go, said in a note of his, and never sent`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(status = Status.Done, doneAt = now))) }
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-form", "bring the form", now - 10 * MIN, By.Felix, sheet.id, Due(DueKind.SlotStart, now - 10 * MIN)))) }
        felix.looks.sweepReminders(now)
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertEquals(FelixWords.letGo("bring the form", "Forces worksheet", "is done"), note.text)
        // Said under an id of its own, before it's marked, so a failure between says it once, again.
        assertEquals("letgo-r-form", note.id)
        assertTrue(note.byApp && note.about.taskId == sheet.id)
        assertNotNull(stores.felix.value.reminders.single().letGoAt)
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        // Let go once.
        felix.looks.sweepReminders(now + MIN)
        assertEquals(1, stores.chat.value.messages.count { it.kind == MessageKind.Note })
    }

    @Test
    fun `a slot's reminder goes by the plan just made, not the one kept for the minute, and one on an event is let go`() = runBlocking {
        // Claude off: which plan it goes by is what's asked here, not whose words.
        stores.settings.update { it.copy(aiEnabled = false) }
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-form", "bring the form", now - 10 * MIN, By.Felix, sheet.id, Due(DueKind.SlotStart, now - 10 * MIN)))) }
        val kept = felix.planning.plan(now = now)
        val slots = kept.buckets.flatMap { it.slots }.filter { it.chunk?.taskId == sheet.id }
        assertTrue(slots.isNotEmpty() && slots.all { it.start > now })
        felix.looks.sweepReminders(now)
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        // A plan made of what the minute's isn't kept by (your pace, say), its slot for the sheet begun a minute ago.
        val made = kept.copy(buckets = kept.buckets.map { b -> b.copy(slots = b.slots.map { s -> if (s.chunk?.taskId == sheet.id) s.copy(start = now - MIN) else s }) })
        felix.looks.sweepReminders(now, made)
        assertEquals("reminder-r-form-${now - MIN}", stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.id)
        // On an event, which never has a slot of work, it's let go, and said why.
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(kind = Kind.Event))) }
        stores.felix.update { it.copy(reminders = it.reminders + Reminder("r-pen", "bring a pen", now, By.Felix, sheet.id, Due(DueKind.SlotStart, now))) }
        felix.looks.sweepReminders(now + MIN)
        assertNotNull(stores.felix.value.reminders.single { it.id == "r-pen" }.letGoAt)
        assertEquals(FelixWords.letGo("bring a pen", "Forces worksheet", "is an event, with no slots of work"), stores.chat.value.messages.single { it.kind == MessageKind.Note }.text)
    }

    @Test
    fun `one let go is back once its task is open again, said in the let-go's note, and not lost to a tick undone`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(status = Status.Done, doneAt = now))) }
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-form", "bring the form", now - 10 * MIN, By.Felix, sheet.id, Due(DueKind.SlotStart, now - 10 * MIN)))) }
        felix.looks.sweepReminders(now)
        assertNotNull(stores.felix.value.reminders.single().letGoAt)
        // The tick undone: open again, it's back, the same note saying so, moved to the end.
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        felix.looks.sweepReminders(now + MIN)
        val back = stores.felix.value.reminders.single()
        assertTrue(back.letGoAt == null && Reminders.pending(back), back.toString())
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertEquals("letgo-r-form", note.id)
        assertEquals(FelixWords.back("bring the form", "Forces worksheet", "is on the plan again"), note.text)
        // Back once: the next sweep says nothing more.
        felix.looks.sweepReminders(now + 2 * MIN)
        assertEquals(note, stores.chat.value.messages.single { it.kind == MessageKind.Note })
    }

    /** His answer for a reminder, as his calls give it. */
    private fun his(message: String) = """{"message":"$message","replies":[],"nextCheckMin":null,"memories":[]}"""

    @Test
    fun `a reminder set three minutes ahead is written for at once, with no plan or look between`() = runBlocking {
        felix.answers(answer("I'll remind you at 17:03.", reminders = listOf(reminder(text = "stretch", time = "17:03"))))
        felix.answers(his("stretch time: you said so"))
        assertTrue(conversation.ask("remind me in 3 minutes to stretch"))
        felix.settle()
        val drafted = stores.felix.value.reminders.single()
        assertEquals("stretch time: you said so", drafted.draft?.message)
        assertEquals(now, drafted.draftedAt)
        // His call was told it's a reminder, in their words, for its moment.
        felix.server.takeRequest()
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("Reason: Reminder" in asked && "Their reminder, in their words: “stretch”" in asked && "17:03" in asked, asked)
        // At its time, his words go.
        felix.clock.time = Fixtures.at("2026-10-20T17:03")
        felix.looks.sweepReminders(felix.clock.time)
        assertEquals("stretch time: you said so", stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.text)
    }

    @Test
    fun `a reminder in his window is written for once while that fits, again after a snooze, and not with Claude off, when yours go`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 8 * MIN))) }
        felix.answers(his("call gran now, before the next slot"))
        felix.looks.draftReminders(now)
        felix.looks.draftReminders(now + MIN)
        assertEquals(1, felix.server.requestCount)
        assertEquals("call gran now, before the next slot", stores.felix.value.reminders.single().draft?.message)
        // Out of the window: not yet.
        stores.felix.update { s -> s.copy(reminders = s.reminders.map { Reminders.snoozed(it.copy(sentAt = now), now, 60) }) }
        felix.looks.draftReminders(now + MIN)
        assertEquals(1, felix.server.requestCount)
        assertNull(stores.felix.value.reminders.single().draft)
        // Claude off: no call, and at its time your words go.
        stores.settings.update { it.copy(aiEnabled = false) }
        felix.looks.draftReminders(now + 55 * MIN)
        assertEquals(1, felix.server.requestCount)
        felix.clock.time = now + 60 * MIN
        felix.looks.sweepReminders(felix.clock.time)
        assertEquals(FelixWords.reminder("call gran"), stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.text)
    }

    @Test
    fun `a reminder's words written ahead, with something said since, are asked for again at its moment, and with Claude off yours go (D-57)`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 8 * MIN), at("stretch", now + 9 * MIN))) }
        felix.answers(his("hey, call gran now"))
        felix.answers(his("hey, time to stretch"))
        felix.looks.draftReminders(now)
        assertEquals("hey, call gran now", stores.felix.value.reminders.first().draft?.message)
        // You write in the chat before its time: his words, written before, would talk past it, so he's asked again then.
        stores.chat.update { it.copy(messages = it.messages + ChatMessage("y1", Speaker.You, "what's on tonight?", now + 2 * MIN, answeredAt = now + 2 * MIN)) }
        felix.answers(his("call gran now, you're free till the next slot"))
        felix.clock.time = now + 8 * MIN
        // The sweep makes no call: it leaves it to work of its own, which asks and then sends it.
        assertTrue(felix.looks.sweepReminders(felix.clock.time))
        assertEquals(1, felix.ports.reminders)
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        assertEquals(2, felix.server.requestCount)
        felix.looks.remindNow(felix.clock.time)
        assertEquals("call gran now, you're free till the next slot", stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.text)
        assertEquals(3, felix.server.requestCount)
        // Claude off by the second's moment: yours go.
        stores.settings.update { it.copy(aiEnabled = false) }
        felix.clock.time = now + 9 * MIN
        felix.looks.sweepReminders(felix.clock.time)
        assertEquals(FelixWords.reminder("stretch"), stores.chat.value.messages.last { it.kind == MessageKind.Reminder }.text)
        assertEquals(3, felix.server.requestCount)
    }

    @Test
    fun `a reminder's words that came back with none aren't asked for again at each look, but once more at its moment, and with none again yours go`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 8 * MIN))) }
        felix.answers("not an answer")
        felix.looks.draftReminders(now)
        felix.looks.draftReminders(now + MIN)
        assertEquals(1, felix.server.requestCount)
        val asked = stores.felix.value.reminders.single()
        assertTrue(asked.draft == null && asked.draftFor == now + 8 * MIN, asked.toString())
        felix.answers("not an answer")
        felix.clock.time = now + 8 * MIN
        assertTrue(felix.looks.sweepReminders(felix.clock.time))
        felix.looks.remindNow(felix.clock.time)
        assertEquals(2, felix.server.requestCount)
        assertEquals(FelixWords.reminder("call gran"), stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.text)
    }

    @Test
    fun `a reminder cancelled while his words for its moment are asked for isn't sent`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("stretch", now))) }
        assertTrue(felix.looks.sweepReminders(now))
        felix.answers(his("time to stretch"), delayMs = 1_000)
        val writing = async(Dispatchers.Default) { felix.looks.remindNow(now) }
        assertNotNull(felix.server.takeRequest(10, TimeUnit.SECONDS))
        stores.felix.update { it.copy(reminders = it.reminders.map { r -> r.copy(cancelledAt = now).undrafted() }) }
        writing.await()
        // Cancelled as he wrote: his words aren't kept on it, and it isn't sent.
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        assertNull(stores.felix.value.reminders.single().sentAt)
        assertNull(stores.felix.value.reminders.single().draft)
    }

    @Test
    fun `a reminder whose task finishes while his words for its moment are asked for is let go, not sent`() = runBlocking {
        val task = sheet.copy(dueAt = now + 60 * MIN)
        stores.tasks.update { it.copy(tasks = listOf(task)) }
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-sheet", "finish the worksheet", now - MIN, By.Felix, task.id, Due(DueKind.BeforeDue, minutesBefore = 60)))) }
        assertTrue(felix.looks.sweepReminders(now))
        felix.answers(his("worksheet's due in an hour"), delayMs = 1_000)
        val writing = async(Dispatchers.Default) { felix.looks.remindNow(now) }
        assertNotNull(felix.server.takeRequest(10, TimeUnit.SECONDS))
        stores.tasks.update { it.copy(tasks = listOf(task.copy(status = Status.Done, doneAt = now))) }
        writing.await()
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        assertNotNull(stores.felix.value.reminders.single().letGoAt)
        assertEquals(FelixWords.letGo("finish the worksheet", "Forces worksheet", "is done"), stores.chat.value.messages.single { it.kind == MessageKind.Note }.text)
    }

    @Test
    fun `a reminder whose words can't be asked for at its moment, the call failing, goes in yours`() = runBlocking {
        stores.felix.update { it.copy(reminders = listOf(at("stretch", now))) }
        assertTrue(felix.looks.sweepReminders(now))
        felix.server.enqueue(MockResponse().setResponseCode(500))
        felix.looks.remindNow(now)
        assertEquals(FelixWords.reminder("stretch"), stores.chat.value.messages.single { it.kind == MessageKind.Reminder }.text)
        assertEquals(now, stores.felix.value.reminders.single().sentAt)
    }

    @Test
    fun `a reminder's snooze from its chips has it come again, with no call, and its Done ends it`() = runBlocking {
        // Claude off, his words written for its moment: no call at either moment.
        stores.settings.update { it.copy(aiEnabled = false) }
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now).copy(draft = FelixAnswer("oi"), draftedAt = now - MIN, draftFor = now))) }
        felix.looks.sweepReminders(now)
        val sent = stores.chat.value.messages.single { it.kind == MessageKind.Reminder }
        felix.clock.time = now + MIN
        assertTrue(conversation.ask(FelixWords.REMINDER_SNOOZE, replyTo = sent.id))
        felix.settle()
        val snoozed = stores.felix.value.reminders.single()
        assertEquals(now + 16 * MIN, snoozed.snoozedTo)
        assertTrue(Reminders.pending(snoozed) && snoozed.draft == null)
        // Kept under it, answered, and no call made.
        assertEquals(0, felix.server.requestCount)
        assertTrue(stores.chat.value.messages.any { it.from == Speaker.You && it.replyTo == sent.id && it.answeredAt != null })
        assertNotNull(stores.chat.value.messages.first { it.id == sent.id }.answeredAt)
        // It comes again at its snooze, and Done ends it.
        felix.clock.time = now + 16 * MIN
        felix.looks.sweepReminders(felix.clock.time)
        val again = stores.chat.value.messages.last { it.kind == MessageKind.Reminder }
        assertTrue(again.id != sent.id)
        assertTrue(conversation.ask("Done", replyTo = again.id))
        felix.settle()
        assertEquals(now + 16 * MIN, stores.felix.value.reminders.single().doneAt)
        assertFalse(Reminders.pending(stores.felix.value.reminders.single()))
        assertEquals(0, felix.server.requestCount)
    }

    @Test
    fun `a note from the sheet is yours, ten a task at most, and one deleted there is marked and shown nowhere`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        assertNull(conversation.addNote(sheet.id, "  show the working  "))
        val note = stores.felix.value.notes.single()
        assertEquals(Triple(sheet.id, "show the working", By.You), Triple(note.taskId, note.text, note.by))
        assertEquals("A note needs some words", conversation.addNote(sheet.id, " "))
        assertEquals("That task isn't there now", conversation.addNote("teams:gone", "words"))
        repeat(9) { assertNull(conversation.addNote(sheet.id, "note $it")) }
        assertEquals("It has 10 notes already: delete one first", conversation.addNote(sheet.id, "eleventh"))
        conversation.deleteNote(note.id)
        assertNotNull(stores.felix.value.notes.first { it.id == note.id }.deletedAt)
        assertTrue(Notes.on(sheet.id, stores.felix.value.notes).none { it.id == note.id })
    }

    @Test
    fun `his message at a slot's start is told its task's notes and your reminder that came at it, so he doesn't say it twice`() = runBlocking {
        // Due tomorrow morning, so it's planned this evening.
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(dueAt = Fixtures.at("2026-10-21T09:00")))) }
        val slot = felix.planning.plan().let { it.slotAt(now) ?: it.nextSlot(now) }
        assertNotNull(slot)
        assertEquals(sheet.id, slot.chunk?.taskId)
        stores.felix.update { s ->
            s.copy(
                notes = listOf(Note("n1", "show the working", now, By.Felix, taskId = sheet.id)),
                reminders = listOf(Reminder("r1", "bring the form", now - 10 * MIN, By.Felix, sheet.id, Due(DueKind.SlotStart, now - 10 * MIN), sentAt = slot.start)),
            )
        }
        felix.answers(his("Starting the worksheet? Show the working."))
        felix.looks.say(FelixPrompts.Reason.SlotStart, now)
        val asked = felix.server.takeRequest().body.readUtf8()
        assertTrue("Notes on it: show the working" in asked, asked)
        assertTrue("Their reminder for this slot, sent at its start, so not to say again: “bring the form”" in asked, asked)
    }

    @Test
    fun `the morning's prune keeps what an Undo can still bring back, a deleted own task's notes with it, and lets go of the rest`() = runBlocking {
        val own = OwnTaskRules.create(OwnTask(title = "Poster"), "p", now)
        stores.tasks.update { it.copy(tasks = listOf(sheet, own)) }
        asked("note the poster: A3", answer("Noted.", notes = listOf(note(own.id, "A3"))))
        // Deleted from the chat: its act keeps the task, and its note waits with it.
        asked("delete the poster", answer("Deleted.", actions = listOf("""{"type":"delete","taskId":"${own.id}","stepIndex":-1,"setting":"","value":""}""")))
        assertTrue(stores.tasks.value.tasks.none { it.id == own.id })
        stores.felix.update { s ->
            s.copy(
                notes = s.notes + Note("gone", "deleted on its sheet", now, By.You, taskId = sheet.id, deletedAt = now),
                reminders = listOf(
                    at("old", now - 40 * 24 * 60 * MIN).copy(sentAt = now - 31 * 24 * 60 * MIN), at("cancelled", now + MIN).copy(cancelledAt = now),
                    at("let go", now + MIN).copy(letGoAt = now), at("to come", now + 60 * MIN),
                ),
            )
        }
        conversation.pruneFelix(now)
        assertEquals(listOf(own.id), stores.felix.value.notes.map { it.taskId })
        // A cancel no act holds goes; one let go is kept a while, to come back if its task's to do again.
        assertEquals(listOf("let go", "to come"), stores.felix.value.reminders.map { it.text })
        // The delete's message out of the window: nothing brings the task back, so its note goes too.
        stores.chat.update { it.copy(messages = emptyList()) }
        conversation.pruneFelix(now)
        assertTrue(stores.felix.value.notes.isEmpty())
    }

    @Test
    fun `what changed while he answered is counted again as it's made, and what no longer can be is said, not dropped`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        repeat(9) { assertNull(conversation.addNote(sheet.id, "note $it")) }
        stores.felix.update { it.copy(reminders = listOf(at("call gran", now + 60 * MIN))) }
        felix.answers(answer("Noted, and cancelled.", notes = listOf(note(sheet.id, "his tenth")), actions = listOf(action("cancelReminder", "r-call gran"))), delayMs = 1_500)
        assertTrue(conversation.ask("note the sheet, and cancel gran"))
        // Meanwhile a tenth from the sheet, and the reminder came.
        delay(500)
        assertNull(conversation.addNote(sheet.id, "your tenth"))
        stores.felix.update { s -> s.copy(reminders = s.reminders.map { it.copy(sentAt = now) }) }
        felix.settle()
        val his = stores.chat.value.messages.last { it.from == Speaker.Felix }
        assertEquals(10, Notes.on(sheet.id, stores.felix.value.notes).size)
        assertTrue(his.acts.all { it.problem && it.action == null }, his.acts.toString())
        assertEquals(2, his.acts.size)
        assertNull(stores.felix.value.reminders.single().cancelledAt)
    }

    @Test
    fun `a slot's reminder whose task is finished while he answers isn't made, and says so`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        felix.answers(answer("I'll remind you when you next sit down to it.", reminders = listOf(reminder(text = "bring the form", taskId = sheet.id, whenIs = "slotStart"))), delayMs = 1_500)
        assertTrue(conversation.ask("remind me to bring the form when I next do the worksheet"))
        delay(500)
        stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(status = Status.Done, doneAt = now) }) }
        felix.settle()
        val his = stores.chat.value.messages.last { it.from == Speaker.Felix }
        assertTrue(stores.felix.value.reminders.isEmpty())
        assertTrue(his.acts.single().let { it.problem && it.line.contains("bring the form") }, his.acts.toString())
    }

    @Test
    fun `a cancel and a note's delete mark what they name, kept whole for their Undo, which brings it back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        asked("remind me at 7 to call gran, and note the sheet", answer("Done.", reminders = listOf(reminder(time = "19:00")), notes = listOf(note(sheet.id, "show the working"))))
        val r = stores.felix.value.reminders.single()
        val n = stores.felix.value.notes.single()
        val gone = asked("cancel that and delete the note", answer("Cancelled, and deleted.", actions = listOf(action("cancelReminder", r.id), action("deleteNote", n.id))))
        assertEquals(listOf("Cancelled: the reminder “call gran”", "Note deleted: “show the working”"), gone.acts.map { it.line })
        assertNotNull(stores.felix.value.reminders.single().cancelledAt)
        assertNotNull(stores.felix.value.notes.single().deletedAt)
        assertTrue(canUndo(gone, 0) && canUndo(gone, 1))
        assertNull(conversation.undoAct(gone.id, 0))
        assertNull(conversation.undoAct(gone.id, 1))
        assertNull(stores.felix.value.reminders.single().cancelledAt)
        assertNull(stores.felix.value.notes.single().deletedAt)
    }
}
