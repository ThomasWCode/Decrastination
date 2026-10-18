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
        assertEquals("2", Replies.pending(listOf(turn, answer, one, two), checkInUnderWay = false)?.id)
        // The second answered with no call (its kind its question's): the first is next.
        val done = two.copy(answeredAt = 5)
        val harder = ChatMessage("h", Speaker.You, "Harder", 5, kind = MessageKind.Completions, replyTo = "2", answeredAt = 5)
        assertEquals("1", Replies.pending(listOf(turn, answer, one, done, harder), checkInUnderWay = false)?.id)
        // A turn of the chat since: none, the conversation's moved on.
        val talk = ChatMessage("t", Speaker.You, "anyway, what's tomorrow?", 6)
        assertEquals(null, Replies.pending(listOf(turn, answer, one, done, harder, talk), checkInUnderWay = false))
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
