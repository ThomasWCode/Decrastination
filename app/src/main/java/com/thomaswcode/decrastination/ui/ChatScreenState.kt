package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.Outbox
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the chat's screen holds beside the chat (docs/plan_v4/05-the-chat.md §4, §5): the first of his
 * messages unread when it was opened, for the divider, and where it opens; how many of the archive's
 * messages are shown, scrolled back to; and the message your next one replies to. A plain class,
 * tested without Compose: the screen keeps one while it's there and draws from [items].
 */
class ChatScreenState {
    /** One row of the list: a day's date, the divider over the first unread, a message, or the row that loads the archive's next page. */
    sealed interface Item {
        val key: String

        data class Day(val date: LocalDate) : Item {
            override val key: String get() = "day-$date"
        }

        data object Divider : Item {
            override val key: String get() = "unread"
        }

        /** A [message], with the message it replies to where that isn't the one above it ([quote]), shown over its words. */
        data class Message(val message: ChatMessage, val quote: ChatMessage? = null) : Item {
            override val key: String get() = message.id
        }

        data object Earlier : Item {
            override val key: String get() = "earlier"
        }
    }

    /** The first of his messages unread when the chat was opened ([opened]): the divider is over it, for this visit. */
    var firstUnread: String? by mutableStateOf(null)
        private set

    /** The message the list is scrolled to once it's drawn ([opened], [reveal]); null, the foot. */
    var scrollTo: String? by mutableStateOf(null)
        private set

    /** The message opened at from his notification, its bubble tinted for a moment. */
    var flash: String? by mutableStateOf(null)
        private set

    /** How many of the archive's newest messages are shown over the window's ([loadEarlier]). */
    var earlier: Int by mutableIntStateOf(0)
        private set

    /** The message your next one replies to (a swipe, or Reply on a long press), shown over the box. */
    var replyingTo: ChatMessage? by mutableStateOf(null)

    /**
     * The chat opened, at [state] as it was before anything in it was read: what's unread then is
     * marked for this visit, and the list opens at [at] (his notification's message), else at the first
     * unread, else at its foot.
     */
    fun opened(state: ChatState, at: String? = null) {
        firstUnread = state.messages.firstOrNull { Outbox.unread(it, state.readAt) }?.id
        scrollTo = at ?: firstUnread
        flash = at
    }

    /** The list scrolled where it was to be: not again. */
    fun scrolled() {
        scrollTo = null
    }

    /** The moment's tint over. */
    fun flashed() {
        flash = null
    }

    /** The archive's next [PAGE] shown over what's there, of the [archived] it holds: false when it has no more. */
    fun loadEarlier(archived: Int): Boolean {
        if (earlier >= archived) return false
        earlier = minOf(archived, earlier + PAGE)
        return true
    }

    /**
     * Message [id] shown and scrolled to (a quote tapped): the [archive]'s pages loaded as far back as
     * it is, where the [window] hasn't it; false where it's in neither.
     */
    fun reveal(id: String, window: List<ChatMessage>, archive: List<ChatMessage>): Boolean {
        if (window.none { it.id == id }) {
            val at = archive.indexOfFirst { it.id == id }
            if (at < 0) return false
            earlier = maxOf(earlier, archive.size - at)
        }
        scrollTo = id
        return true
    }

    /**
     * The list as it's drawn: the [archive]'s [earlier] newest messages, then the [window]'s, each day's
     * date over its first, the divider over the first unread, and over a reply what it replies to where
     * that isn't the message above it; the row that loads the next page at the top while the archive has
     * more. His silences (nothing to say) aren't drawn.
     */
    fun items(window: List<ChatMessage>, archive: List<ChatMessage>, zone: ZoneId): List<Item> = buildList {
        if (earlier < archive.size) add(Item.Earlier)
        val byId = (archive + window).associateBy { it.id }
        var day: LocalDate? = null
        var above: ChatMessage? = null
        for (m in (archive.takeLast(earlier) + window).distinctBy { it.id }) {
            if (m.from == Speaker.Felix && m.text.isBlank()) continue
            val on = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
            if (on != day) {
                add(Item.Day(on))
                day = on
            }
            if (m.id == firstUnread) add(Item.Divider)
            add(Item.Message(m, m.replyTo?.takeIf { it != above?.id }?.let(byId::get)?.takeIf { it.text.isNotBlank() }))
            above = m
        }
    }

    companion object {
        /** The archive read back a page at a time. */
        const val PAGE = 100

        private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
        private val DAY_YEAR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)

        /** [date] as the line between days says it, [today] being today: "Today", "Yesterday", "Tuesday 13 October" (with its year where it isn't this one). */
        fun dayLine(date: LocalDate, today: LocalDate): String = when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(if (date.year == today.year) DAY else DAY_YEAR)
        }
    }
}
