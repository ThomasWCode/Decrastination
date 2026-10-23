package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.learn.Assessment
import com.thomaswcode.decrastination.learn.CalendarTime
import java.time.Instant
import java.time.ZoneId

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

        /** A reading's question's answer: an instruction on its task, read by Claude to judge whether it answers it, kept with the task, read again, where it does. */
        data object Question : Route

        /** One of how work went's three answers, as it's kept ([answer]): on each completion asked about, with no call. */
        data class Completions(val answer: String) : Route

        /** One of a calendar question's three answers, as it's kept ([answer]): for every event named [key], with no call. */
        data class Event(val key: String, val answer: String) : Route

        /** One of a reminder's replies: *Done* ([snoozeMin] null), or a snooze of [snoozeMin]: on reminder [id], with no call. */
        data class Reminder(val id: String, val snoozeMin: Int?) : Route

        /** A turn of the chat: his answer a call, told what it answers. */
        data object Chat : Route
    }

    /**
     * Whether [message] is a question of his still waiting for its answer: a check-in's while the
     * check-in is under way ([checkInUnderWay]; its thanks, after it, isn't one), a reading's about a
     * task, how work went, a calendar event's. The chat's box answers one that's his last message (D-98),
     * unless a nudge of his with replies of its own is newer ([pending]).
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
     * The ids of his [messages] waiting for your answer, shown so under their time (D-99), none answered
     * (as answers are, or marked so) nor replied to by a message of yours: his questions still waiting
     * ([waiting]), a calendar event's only till its event begins; and his nudges with replies of their own
     * (his answers in the chat aren't asks of his), each till a newer message of his is about the same
     * task (or, about none, of the same kind) or its day is over at [now] in [zone]: by then it's had its moment.
     */
    fun awaitingIds(messages: List<ChatMessage>, checkInUnderWay: Boolean, now: Long, zone: ZoneId): Set<String> {
        val replied = messages.mapNotNullTo(HashSet()) { m -> m.replyTo?.takeIf { m.from == Speaker.You } }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // A reminder's message is about its reminder: two about one task each wait for their own answer.
        fun about(m: ChatMessage): String = m.reminderId?.let { "reminder:$it" } ?: m.about.taskId ?: m.about.eventKey ?: m.kind.name
        // His newest message with words about each thing: the last of his about it wins.
        val newestAbout = HashMap<String, String>()
        messages.forEach { m -> if (m.from == Speaker.Felix && m.text.isNotBlank()) newestAbout[about(m)] = m.id }
        return messages.filter { m ->
            m.from == Speaker.Felix && m.answeredAt == null && m.id !in replied && if (waiting(m, checkInUnderWay)) {
                m.kind != MessageKind.EventQuestion || (m.about.eventStart ?: Long.MAX_VALUE) > now
            } else {
                m.kind != MessageKind.Chat && m.suggestedReplies.isNotEmpty() && newestAbout[about(m)] == m.id &&
                    Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate() == today
            }
        }.mapTo(HashSet()) { it.id }
    }

    /** A reminder's replies ([FelixWords.REMINDER_REPLIES]) and the snooze each is, in minutes: none for *Done*. */
    private val REMINDER_REPLIES: Map<String, Int?> = mapOf(FelixWords.REMINDER_DONE to null, FelixWords.REMINDER_SNOOZE to 15, FelixWords.REMINDER_SNOOZE_HOUR to 60)

    /** The chat's newest message as it's shown: yours, or his with words (one with none says nothing). */
    fun newest(messages: List<ChatMessage>): ChatMessage? = messages.lastOrNull { it.from == Speaker.You || it.text.isNotBlank() }

    /**
     * The message of his the box and his chips answer (D-98): his newest still waiting for your answer
     * ([awaitingIds]) since your last turn of the chat, a question or a nudge, so a nudge newer than his
     * question takes them, its replies yours to tap and your words its reply. An answer to one isn't a
     * turn of the chat, so with several asked together (how work went, a calendar's questions), the
     * one before it is next: they're answered one by one. Once you've talked to him, one from before that
     * doesn't take your words: it waits, shown so, for its line or a swipe.
     */
    fun pending(messages: List<ChatMessage>, checkInUnderWay: Boolean, now: Long, zone: ZoneId): ChatMessage? {
        val awaiting = awaitingIds(messages, checkInUnderWay, now, zone)
        val turn = messages.indexOfLast { it.from == Speaker.You && it.kind == MessageKind.Chat }
        return messages.drop(turn + 1).lastOrNull { it.id in awaiting }
    }

    /**
     * Where [words], your reply to [message], go: a question of his still waiting ([waiting]) takes its
     * answer (a check-in's, any words; a reading's, any words, read by Claude as an instruction; how work went
     * or a calendar event's, one of its three, whatever their case), with no call but a reading's; a reminder of yours not yet answered, *Done* or a
     * snooze; anything else, and any other words, is a turn of the chat, the message it answers told
     * to him.
     */
    fun route(message: ChatMessage?, words: String, checkInUnderWay: Boolean): Route {
        // A reminder's reply: *Done* or a snooze, whatever their case, while it's unanswered.
        if (message != null && message.from == Speaker.Felix && message.kind == MessageKind.Reminder && message.answeredAt == null) {
            val id = message.reminderId ?: return Route.Chat
            return REMINDER_REPLIES.entries.firstOrNull { it.key.equals(words.trim(), ignoreCase = true) }?.let { Route.Reminder(id, it.value) } ?: Route.Chat
        }
        if (message == null || !waiting(message, checkInUnderWay)) return Route.Chat
        val said = words.trim()
        return when (message.kind) {
            MessageKind.CheckIn -> Route.CheckIn
            MessageKind.Question -> Route.Question
            MessageKind.Completions -> Assessment.ANSWERS.firstOrNull { it.first.equals(FelixWords.chip(said), ignoreCase = true) }?.let { Route.Completions(it.second) } ?: Route.Chat
            MessageKind.EventQuestion -> message.about.eventKey?.let { key ->
                CalendarTime.ANSWERS.firstOrNull { it.first.equals(said, ignoreCase = true) }?.let { Route.Event(key, it.second) }
            } ?: Route.Chat
            else -> Route.Chat
        }
    }
}
