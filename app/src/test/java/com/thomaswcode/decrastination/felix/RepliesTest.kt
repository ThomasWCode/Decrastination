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

/** Where a reply goes, by the kind of message it answers (docs/plan_v4/05-the-chat.md §5). */
class RepliesTest {
    private fun his(kind: MessageKind, about: About = About(), asks: List<AssessLater> = emptyList(), answeredAt: Long? = null) =
        ChatMessage("h", Speaker.Felix, "A question", 1, about, kind = kind, byApp = true, asks = asks, answeredAt = answeredAt)

    @Test
    fun `a check-in's question takes any words, with no call`() {
        assertEquals(Replies.Route.CheckIn, Replies.route(his(MessageKind.CheckIn), "4"))
        assertEquals(Replies.Route.CheckIn, Replies.route(his(MessageKind.CheckIn), "Physics questions"))
    }

    @Test
    fun `a reading's question about a task takes any words, and one about none is the chat's`() {
        assertEquals(Replies.Route.Question, Replies.route(his(MessageKind.Question, About(taskId = "t1")), "about two hours"))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Question), "about two hours"))
    }

    @Test
    fun `how work went takes its three whatever their case, anything else is the chat's`() {
        val asked = his(MessageKind.Completions, asks = listOf(AssessLater("t1", "A task", 5)))
        assertEquals(Replies.Route.Completions(Calibrator.HARDER), Replies.route(asked, "Harder"))
        assertEquals(Replies.Route.Completions(Calibrator.AS_EXPECTED), Replies.route(asked, " as expected "))
        assertEquals(Replies.Route.Completions(Calibrator.EASIER), Replies.route(asked, "EASIER"))
        assertEquals(Replies.Route.Chat, Replies.route(asked, "fine, but long"))
        // Nothing asked about on it: the chat's.
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Completions), "Harder"))
    }

    @Test
    fun `a calendar event's question takes its three for every event of its name, anything else is the chat's`() {
        val asked = his(MessageKind.EventQuestion, About(eventKey = "open day"))
        val (label, kept) = CalendarTime.ANSWERS[1]
        assertEquals(Replies.Route.Event("open day", kept), Replies.route(asked, label.uppercase()))
        assertEquals(Replies.Route.Chat, Replies.route(asked, "only till six"))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.EventQuestion), label))
    }

    @Test
    fun `a nudge, his answer, a question settled already, yours, or none is a turn of the chat`() {
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.SlotStart), "on it"))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.Chat), "thanks"))
        assertEquals(Replies.Route.Chat, Replies.route(his(MessageKind.CheckIn, answeredAt = 2), "4"))
        assertEquals(Replies.Route.Chat, Replies.route(ChatMessage("y", Speaker.You, "mine", 1), "and this"))
        assertEquals(Replies.Route.Chat, Replies.route(null, "hello"))
    }
}
