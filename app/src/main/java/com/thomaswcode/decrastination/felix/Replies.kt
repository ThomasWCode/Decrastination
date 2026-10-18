package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.CalendarTime

/**
 * One entrance for replies (docs/plan_v4/05-the-chat.md §5): where your reply to a message goes, by the
 * kind of message it answers, the same whether it came from his notification, one of his chips, a swipe
 * or Reply in the chat, or the box answering his question ([Conversation.ask] with `replyTo`). Pure.
 */
object Replies {
    /** Where a reply goes. */
    sealed interface Route {
        /** A check-in's question's answer: kept, and the next asked, with no call. */
        data object CheckIn : Route

        /** A reading's question's answer: an instruction on its task, the task read again, with no call. */
        data object Question : Route

        /** One of how work went's three answers, as it's kept ([answer]): on each completion asked about, with no call. */
        data class Completions(val answer: String) : Route

        /** One of a calendar question's three answers, as it's kept ([answer]): for every event named [key], with no call. */
        data class Event(val key: String, val answer: String) : Route

        /** A turn of the chat: his answer a call, told what it answers. */
        data object Chat : Route
    }

    /**
     * Whether [message] is a question of his still waiting for its answer: a check-in's while the
     * check-in is under way ([checkInUnderWay]; its thanks, after it, isn't one), a reading's about a
     * task, how work went, a calendar event's. The chat's box answers one that's his last message (D-98).
     */
    fun waiting(message: ChatMessage?, checkInUnderWay: Boolean): Boolean =
        message != null && message.from == Speaker.Felix && message.answeredAt == null && when (message.kind) {
            MessageKind.CheckIn -> checkInUnderWay
            MessageKind.Question -> message.about.taskId != null
            MessageKind.Completions -> message.asks.isNotEmpty()
            MessageKind.EventQuestion -> message.about.eventKey != null
            else -> false
        }

    /**
     * Where [words], your reply to [message], go: a question of his still waiting ([waiting]) takes its
     * answer with no call (a check-in's, any words; a reading's, any words; how work went or a calendar
     * event's, one of its three, whatever their case); anything else, and any other words, is a turn of
     * the chat, the message it answers told to him.
     */
    fun route(message: ChatMessage?, words: String, checkInUnderWay: Boolean): Route {
        if (message == null || !waiting(message, checkInUnderWay)) return Route.Chat
        val said = words.trim()
        return when (message.kind) {
            MessageKind.CheckIn -> Route.CheckIn
            MessageKind.Question -> Route.Question
            MessageKind.Completions -> Assessment.ANSWERS.firstOrNull { it.first.equals(said, ignoreCase = true) }?.let { Route.Completions(it.second) } ?: Route.Chat
            MessageKind.EventQuestion -> message.about.eventKey?.let { key ->
                CalendarTime.ANSWERS.firstOrNull { it.first.equals(said, ignoreCase = true) }?.let { Route.Event(key, it.second) }
            } ?: Route.Chat
            else -> Route.Chat
        }
    }
}
