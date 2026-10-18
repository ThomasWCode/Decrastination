package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.CalendarTime

/**
 * One entrance for replies (docs/plan_v4/05-the-chat.md §5): where your reply to a message goes, by the
 * kind of message it answers, the same whether it came from his notification, one of his chips, or a
 * swipe or Reply in the chat ([Conversation.ask] with `replyTo`). Pure.
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
     * Where [words], your reply to [message], go: a question of his not answered yet takes its answer
     * with no call (a check-in's, any words; a reading's about a task, any words; how work went or a
     * calendar event's, one of its three, whatever their case); anything else, and any other words, is
     * a turn of the chat, the message it answers told to him.
     */
    fun route(message: ChatMessage?, words: String): Route {
        if (message == null || message.from != Speaker.Felix || message.answeredAt != null) return Route.Chat
        val said = words.trim()
        return when (message.kind) {
            MessageKind.CheckIn -> Route.CheckIn
            MessageKind.Question -> if (message.about.taskId != null) Route.Question else Route.Chat
            MessageKind.Completions -> Assessment.ANSWERS.firstOrNull { it.first.equals(said, ignoreCase = true) }
                ?.takeIf { message.asks.isNotEmpty() }
                ?.let { Route.Completions(it.second) } ?: Route.Chat
            MessageKind.EventQuestion -> message.about.eventKey?.let { key ->
                CalendarTime.ANSWERS.firstOrNull { it.first.equals(said, ignoreCase = true) }?.let { Route.Event(key, it.second) }
            } ?: Route.Chat
            else -> Route.Chat
        }
    }
}
