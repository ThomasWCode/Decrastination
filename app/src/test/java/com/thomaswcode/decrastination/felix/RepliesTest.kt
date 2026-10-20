package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
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
    fun `a reminder's Done and snoozes, whatever their case, are on it with no call, and any other words a turn of the chat`() {
        val reminder = ChatMessage("m", Speaker.Felix, "reminder: call gran", 1, kind = MessageKind.Reminder, byApp = true, reminderId = "r1", suggestedReplies = FelixWords.REMINDER_REPLIES)
        assertEquals(Replies.Route.Reminder("r1", null), Replies.route(reminder, "done", checkInUnderWay = false))
        assertEquals(Replies.Route.Reminder("r1", 15), Replies.route(reminder, FelixWords.REMINDER_SNOOZE, checkInUnderWay = false))
        assertEquals(Replies.Route.Reminder("r1", 60), Replies.route(reminder, " Snooze 1 h ", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(reminder, "done it already, and the essay", checkInUnderWay = false))
        // Answered already, or with no reminder: the chat's.
        assertEquals(Replies.Route.Chat, Replies.route(reminder.copy(answeredAt = 2), "Done", checkInUnderWay = false))
        assertEquals(Replies.Route.Chat, Replies.route(reminder.copy(reminderId = null), "Done", checkInUnderWay = false))
        // A nudge, not a question: it waits for your answer as nudges do, its day out (D-99).
        assertFalse(Replies.waiting(reminder, checkInUnderWay = false))
        assertEquals(setOf("m"), Replies.awaitingIds(listOf(reminder), checkInUnderWay = false, now = 2, zone = LONDON))
    }

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
        assertEquals("2", Replies.pending(listOf(turn, answer, one, two), checkInUnderWay = false, now = 7, zone = LONDON)?.id)
        // The second answered with no call (its kind its question's): the first is next.
        val done = two.copy(answeredAt = 5)
        val harder = ChatMessage("h", Speaker.You, "Harder", 5, kind = MessageKind.Completions, replyTo = "2", answeredAt = 5)
        assertEquals("1", Replies.pending(listOf(turn, answer, one, done, harder), checkInUnderWay = false, now = 7, zone = LONDON)?.id)
        // A turn of the chat since: none, the conversation's moved on.
        val talk = ChatMessage("t", Speaker.You, "anyway, what's tomorrow?", 6)
        assertEquals(null, Replies.pending(listOf(turn, answer, one, done, harder, talk), checkInUnderWay = false, now = 7, zone = LONDON))
    }

    /** Felix's [kind] message about [task] at [time] on 19 Oct, with replies of its own. */
    private fun nudge(id: String, time: String, task: String?, kind: MessageKind = MessageKind.SlotEnd) =
        ChatMessage(id, Speaker.Felix, "Still on?", Fixtures.at("2026-10-19T$time"), About(taskId = task), kind = kind, suggestedReplies = listOf("Yes", "Later"))

    private val evening = Fixtures.at("2026-10-19T19:00")

    @Test
    fun `his newest nudge takes the box over an older question, which waits, shown so, the box's again once the nudge is let go`() {
        val turn = ChatMessage("y", Speaker.You, "what's left?", Fixtures.at("2026-10-19T11:00"), answeredAt = 2)
        val trip = ChatMessage("e", Speaker.Felix, "A trip on Monday: how much of the day does it take?", Fixtures.at("2026-10-19T17:00"), About(eventKey = "trip", eventStart = Fixtures.at("2026-10-26T08:00")), kind = MessageKind.EventQuestion, byApp = true, suggestedReplies = listOf("All of it", "A few hours", "None"))
        val start = nudge("s", "17:40", "t1", MessageKind.SlotStart)
        val slipped = nudge("n", "17:55", "t1")
        val chat = listOf(turn, trip, start, slipped)
        assertEquals("n", Replies.pending(chat, checkInUnderWay = false, now = evening, zone = LONDON)?.id)
        // Waiting, shown so: the question, and the nudge; the one before it about the same task has had its moment.
        assertEquals(setOf("e", "n"), Replies.awaitingIds(chat, checkInUnderWay = false, now = evening, zone = LONDON))
        // The nudge marked answered: the question's the box's again.
        val marked = listOf(turn, trip, start, slipped.copy(answeredAt = evening))
        assertEquals("e", Replies.pending(marked, checkInUnderWay = false, now = evening, zone = LONDON)?.id)
        // A turn of the chat since: the box has moved on with the conversation, and the question still waits, shown.
        val talked = marked + ChatMessage("r", Speaker.You, "the email", evening + 1)
        assertEquals(null, Replies.pending(talked, checkInUnderWay = false, now = evening + 2, zone = LONDON))
        assertEquals(setOf("e"), Replies.awaitingIds(talked, checkInUnderWay = false, now = evening + 2, zone = LONDON))
        // Once its event has begun, it waits no more.
        val monday = Fixtures.at("2026-10-26T09:00")
        assertEquals(emptySet(), Replies.awaitingIds(talked, checkInUnderWay = false, now = monday, zone = LONDON))
    }

    @Test
    fun `a nudge waits till you reply to it, a newer message of his is about its task, or its day is over`() {
        val email = nudge("a", "17:55", "t1")
        val other = nudge("b", "18:30", "t2")
        // Of two tasks, each waits: the newer about another task doesn't settle the older.
        assertEquals(setOf("a", "b"), Replies.awaitingIds(listOf(email, other), checkInUnderWay = false, now = evening, zone = LONDON))
        // His newer one about the first task does; so does a reply of yours to it, the box's or a swipe's.
        val again = nudge("c", "18:45", "t1", MessageKind.SlotStart)
        assertEquals(setOf("b", "c"), Replies.awaitingIds(listOf(email, other, again), checkInUnderWay = false, now = evening, zone = LONDON))
        val reply = ChatMessage("y", Speaker.You, "the email", evening, replyTo = "b")
        assertEquals(setOf("c"), Replies.awaitingIds(listOf(email, other, again, reply), checkInUnderWay = false, now = evening + 1, zone = LONDON))
        // Your words replying to nothing, or yours to another, answer none (the box sends its nudge's as a reply to it).
        val words = ChatMessage("w", Speaker.You, "anyway", evening, replyTo = null)
        assertEquals(setOf("a", "b"), Replies.awaitingIds(listOf(email, other, words), checkInUnderWay = false, now = evening + 1, zone = LONDON))
        // The next day, none: their slots have gone.
        assertEquals(emptySet(), Replies.awaitingIds(listOf(email, other), checkInUnderWay = false, now = Fixtures.at("2026-10-20T07:00"), zone = LONDON))
        // About no task: the newer of its kind settles it.
        val morning = nudge("m1", "07:00", null, MessageKind.Morning)
        assertEquals(setOf("m2"), Replies.awaitingIds(listOf(morning, morning.copy(id = "m2", at = Fixtures.at("2026-10-19T08:00"))), checkInUnderWay = false, now = evening, zone = LONDON))
    }

    @Test
    fun `his answers in the chat, his messages with no replies, yours and one answered aren't waiting`() {
        val start = nudge("n", "18:00", "t1", MessageKind.SlotStart)
        assertEquals(setOf("n"), Replies.awaitingIds(listOf(start), checkInUnderWay = false, now = evening, zone = LONDON))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(start.copy(answeredAt = evening)), checkInUnderWay = false, now = evening, zone = LONDON))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(start.copy(suggestedReplies = emptyList())), checkInUnderWay = false, now = evening, zone = LONDON))
        // His answer in the chat isn't an ask of his, whatever it offers.
        assertEquals(emptySet(), Replies.awaitingIds(listOf(start.copy(kind = MessageKind.Chat)), checkInUnderWay = false, now = evening, zone = LONDON))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(start.copy(from = Speaker.You)), checkInUnderWay = false, now = evening, zone = LONDON))
        // One of his with no words, about the same task, isn't a message about it.
        assertEquals(setOf("n"), Replies.awaitingIds(listOf(start, start.copy(id = "x", text = "")), checkInUnderWay = false, now = evening, zone = LONDON))
        // His reply to something isn't yours.
        assertEquals(setOf("n"), Replies.awaitingIds(listOf(start, ChatMessage("h", Speaker.Felix, "", evening, replyTo = "n")), checkInUnderWay = false, now = evening, zone = LONDON))
        // A check-in's question only while the check-in's under way.
        val asked = ChatMessage("c", Speaker.Felix, "How did the week feel?", evening, kind = MessageKind.CheckIn, byApp = true)
        assertEquals(setOf("c"), Replies.awaitingIds(listOf(asked), checkInUnderWay = true, now = evening, zone = LONDON))
        assertEquals(emptySet(), Replies.awaitingIds(listOf(asked), checkInUnderWay = false, now = evening, zone = LONDON))
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
