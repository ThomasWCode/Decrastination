package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.AssessLater
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.Calibrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a reply goes, by the kind of message it answers (docs/plan_v4/05-the-chat.md §5). */
class RepliesTest {
    private fun his(kind: MessageKind, about: About = About(), asks: List<AssessLater> = emptyList(), answeredAt: Long? = null) =
        ChatMessage("h", Speaker.Felix, "A question", 1, about, kind = kind, byApp = true, asks = asks, answeredAt = answeredAt)

    @Test
    fun `a check-in's question is one only while the check-in is under way, so its thanks after it is the chat's`() {
        val thanks = his(MessageKind.CheckIn)
        assertTrue(Replies.waiting(thanks, checkInUnderWay = true))
        assertFalse(Replies.waiting(thanks, checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(thanks, "and next week?", checkInUnderWay = false))
        // Nor one answered, nor a nudge, nor yours.
        assertFalse(Replies.waiting(his(MessageKind.CheckIn, answeredAt = 2), checkInUnderWay = true))
        assertFalse(Replies.waiting(his(MessageKind.SlotStart), checkInUnderWay = true))
        assertFalse(Replies.waiting(ChatMessage("y", Speaker.You, "mine", 1, kind = MessageKind.CheckIn), checkInUnderWay = true))
        assertFalse(Replies.waiting(null, checkInUnderWay = true))
    }

    @Test
    fun `the box answers the newest question still waiting since your last turn, and with several asked together the one before it next`() {
        fun asked(id: String, at: Long) = ChatMessage(id, Speaker.Felix, "How did it go?", at, kind = MessageKind.Completions, byApp = true, asks = listOf(AssessLater("t$id", "A task", at)))
        val turn = ChatMessage("y", Speaker.You, "what's left?", 1, answeredAt = 2)
        val answer = ChatMessage("a", Speaker.Felix, "The worksheet.", 2)
        val (one, two) = asked("1", 3) to asked("2", 4)
        assertEquals("2", Replies.pending(listOf(turn, answer, one, two), checkInUnderWay = false, now = 7)?.id)
        // The second answered with no call (its kind its question's): the first is next.
        val done = two.copy(answeredAt = 5)
        val harder = ChatMessage("h", Speaker.You, "Harder", 5, kind = MessageKind.Completions, replyTo = "2", answeredAt = 5)
        assertEquals("1", Replies.pending(listOf(turn, answer, one, done, harder), checkInUnderWay = false, now = 7)?.id)
        // A turn of the chat since: none, the conversation's moved on.
        val talk = ChatMessage("t", Speaker.You, "anyway, what's tomorrow?", 6)
        assertEquals(null, Replies.pending(listOf(turn, answer, one, done, harder, talk), checkInUnderWay = false, now = 7))
    }

    @Test
    fun `his newest message with replies of its own takes the box over an older question, which waits, shown so`() {
        val turn = ChatMessage("y", Speaker.You, "what's left?", 1, answeredAt = 2)
        val trip = ChatMessage("e", Speaker.Felix, "A trip on Monday: how much of the day does it take?", 3, About(eventKey = "trip", eventStart = 100), kind = MessageKind.EventQuestion, byApp = true, suggestedReplies = listOf("All of it", "A few hours", "None"))
        val start = ChatMessage("s", Speaker.Felix, "Starting now?", 4, About(taskId = "t1"), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting", "Later"))
        val slipped = ChatMessage("n", Speaker.Felix, "It slipped again: finding the email, or something else?", 5, About(taskId = "t1"), kind = MessageKind.SlotEnd, suggestedReplies = listOf("The email", "Something else"))
        val chat = listOf(turn, trip, start, slipped)
        assertEquals("n", Replies.pending(chat, checkInUnderWay = false, now = 6)?.id)
        // Waiting, shown so: the question, and the newest nudge; the nudge before it has had its moment.
        assertEquals(setOf("e", "n"), Replies.awaitingIds(chat, checkInUnderWay = false, now = 6))
        // The nudge marked answered: the question's the box's again.
        val marked = listOf(turn, trip, start, slipped.copy(answeredAt = 7))
        assertEquals("e", Replies.pending(marked, checkInUnderWay = false, now = 8)?.id)
        // Answered by a turn of the chat: the box moves on with the conversation, and the question still waits, shown.
        val talked = marked + ChatMessage("r", Speaker.You, "the email", 9)
        assertEquals(null, Replies.pending(talked, checkInUnderWay = false, now = 10))
        assertEquals(setOf("e"), Replies.awaitingIds(talked, checkInUnderWay = false, now = 10))
        // Once its event has begun, it waits no more.
        assertEquals(emptySet(), Replies.awaitingIds(talked, checkInUnderWay = false, now = 100))
        assertEquals(null, Replies.pending(marked, checkInUnderWay = false, now = 100))
    }

    @Test
    fun `your words after his nudge answer it, swiped to it or not, and a reply of yours to his question answers that`() {
        val trip = ChatMessage("e", Speaker.Felix, "A trip on Monday: how much of the day does it take?", 2, About(eventKey = "trip", eventStart = 100), kind = MessageKind.EventQuestion, byApp = true)
        val nudge = ChatMessage("n", Speaker.Felix, "Starting now?", 3, kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting"))
        assertEquals(setOf("e", "n"), Replies.awaitingIds(listOf(trip, nudge), checkInUnderWay = false, now = 4))
        val words = ChatMessage("y", Speaker.You, "in five", 5)
        assertEquals(setOf("e"), Replies.awaitingIds(listOf(trip, nudge, words), checkInUnderWay = false, now = 6))
        // Words that aren't one of its answers, a turn of the chat replying to it: it waits no more, unanswered in the store.
        val reply = ChatMessage("r", Speaker.You, "it depends on the ferry", 7, replyTo = "e")
        assertEquals(emptySet(), Replies.awaitingIds(listOf(trip, nudge, words, reply), checkInUnderWay = false, now = 8))
        // His reply to something isn't yours.
        assertEquals(setOf("e", "n"), Replies.awaitingIds(listOf(trip, nudge.copy(replyTo = "e")), checkInUnderWay = false, now = 4))
    }

    @Test
    fun `his answers in the chat, his messages with no replies, yours and one answered aren't waiting`() {
        val nudge = ChatMessage("n", Speaker.Felix, "Starting now?", 2, kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting"))
        assertEquals(setOf("n"), Replies.awaitingIds(listOf(nudge), checkInUnderWay = false, now = 3))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(nudge.copy(answeredAt = 3)), checkInUnderWay = false, now = 4))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(nudge.copy(suggestedReplies = emptyList())), checkInUnderWay = false, now = 3))
        // His answer in the chat isn't an ask of his, whatever it offers.
        assertEquals(emptySet(), Replies.awaitingIds(listOf(ChatMessage("a", Speaker.Felix, "Moved it to seven.", 2, suggestedReplies = listOf("Thanks"))), checkInUnderWay = false, now = 3))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(ChatMessage("y", Speaker.You, "hi", 2, kind = MessageKind.SlotStart, suggestedReplies = listOf("x"))), checkInUnderWay = false, now = 3))
        // One of his with no words after it isn't a message after it.
        assertEquals(setOf("n"), Replies.awaitingIds(listOf(nudge, ChatMessage("x", Speaker.Felix, "", 3)), checkInUnderWay = false, now = 4))
        // A check-in's question only while the check-in's under way.
        val asked = ChatMessage("c", Speaker.Felix, "How did the week feel?", 2, kind = MessageKind.CheckIn, byApp = true)
        assertEquals(setOf("c"), Replies.awaitingIds(listOf(asked), checkInUnderWay = true, now = 3))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(asked), checkInUnderWay = false, now = 3))
    }

    @Test
    fun `a check-in's question takes any words, with no call`() {
        assertEquals(Replies.Route.CheckIn, Replies.route(his(MessageKind.CheckIn), "4", checkInUnderWay = true))
        assertEquals(Replies.Route.CheckIn, Replies.route(his(MessageKind.CheckIn), "Physics questions", checkInUnderWay = true))
    }

    @Test
    fun `a reading's question about a task takes any words, and one about none is the chat's`() {
        assertEquals(Replies.Route.Question, Replies.route(his(MessageKind.Question, About(taskId = "t1")), "about two hours", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Question), "about two hours", checkInUnderWay = false))
    }

    @Test
    fun `how work went takes its three whatever their case, anything else is the chat's`() {
        val asked = his(MessageKind.Completions, asks = listOf(AssessLater("t1", "A task", 5)))
        assertEquals(Replies.Route.Completions(Calibrator.HARDER), Replies.route(asked, "Harder", checkInUnderWay = false))
        assertEquals(Replies.Route.Completions(Calibrator.AS_EXPECTED), Replies.route(asked, " as expected ", checkInUnderWay = false))
        assertEquals(Replies.Route.Completions(Calibrator.EASIER), Replies.route(asked, "EASIER", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(asked, "fine, but long", checkInUnderWay = false))
        // Nothing asked about on it: the chat's.
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Completions), "Harder", checkInUnderWay = false))
    }

    @Test
    fun `a calendar event's question takes its three for every event of its name, anything else is the chat's`() {
        val asked = his(MessageKind.EventQuestion, About(eventKey = "open day"))
        val (label, kept) = CalendarTime.ANSWERS[1]
        assertEquals(Replies.Route.Event("open day", kept), Replies.route(asked, label.uppercase(), checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(asked, "only till six", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.EventQuestion), label, checkInUnderWay = false))
    }

    @Test
    fun `a nudge, his answer, a question settled already, yours, or none is a turn of the chat`() {
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.SlotStart), "on it", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Chat), "thanks", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.CheckIn, answeredAt = 2), "4", checkInUnderWay = true))
        assertEquals(Replies.Route.Chat, Replies.route(ChatMessage("y", Speaker.You, "mine", 1), "and this", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(null, "hello", checkInUnderWay = false))
    }
}
