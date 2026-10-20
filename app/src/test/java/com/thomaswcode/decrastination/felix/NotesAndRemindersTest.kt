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
import kotlinx.coroutines.runBlocking

/**
 * Notes and reminders through the Conversation (docs/plan_v4/07-notes-and-reminders.md, Stage 8a), on
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

    private fun reminder(id: String = "", text: String = "call gran", time: String = "", taskId: String = "", whenIs: String = "at") =
        """{"id":"$id","text":"$text","date":"","time":"$time","taskId":"$taskId","when":"$whenIs","minutesBefore":-1}"""

    private fun answer(reply: String, reminders: List<String> = emptyList(), notes: List<String> = emptyList(), actions: List<String> = emptyList()) =
        """{"reply":"$reply","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[${actions.joinToString(",")}],"research":"","details":[],"reminders":[${reminders.joinToString(",")}],"notes":[${notes.joinToString(",")}]}"""

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
            answer("I'll remind you at 7, and it's noted.", reminders = listOf(reminder(time = "19:00")), notes = listOf("""{"taskId":"${sheet.id}","text":"show the working"}""")),
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
                at("call gran", moment).copy(draft = FelixAnswer("oi, you said to call gran. between slots now, so good time"), draftedAt = moment - 8 * MIN),
                at("stretch", now - 40 * MIN).copy(draft = FelixAnswer("stretch now"), draftedAt = now - 45 * MIN),
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
    fun `one whose task closes before it comes is let go, said in a note of his, and never sent`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet.copy(status = Status.Done, doneAt = now))) }
        stores.felix.update { it.copy(reminders = listOf(Reminder("r-form", "bring the form", now - 10 * MIN, By.Felix, sheet.id, Due(DueKind.SlotStart, now - 10 * MIN)))) }
        felix.looks.sweepReminders(now)
        val note = stores.chat.value.messages.single { it.kind == MessageKind.Note }
        assertEquals(FelixWords.letGo("bring the form", "Forces worksheet", "is done"), note.text)
        assertTrue(note.byApp && note.about.taskId == sheet.id)
        assertNotNull(stores.felix.value.reminders.single().cancelledAt)
        assertTrue(stores.chat.value.messages.none { it.kind == MessageKind.Reminder })
        // Let go once.
        felix.looks.sweepReminders(now + MIN)
        assertEquals(1, stores.chat.value.messages.count { it.kind == MessageKind.Note })
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
    fun `a cancel and a note's delete mark what they name, kept whole for their Undo, which brings it back`() = runBlocking {
        stores.tasks.update { it.copy(tasks = listOf(sheet)) }
        asked("remind me at 7 to call gran, and note the sheet", answer("Done.", reminders = listOf(reminder(time = "19:00")), notes = listOf("""{"taskId":"${sheet.id}","text":"show the working"}""")))
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
