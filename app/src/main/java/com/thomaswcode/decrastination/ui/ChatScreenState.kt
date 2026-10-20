package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ChatSummary
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.felix.Replies
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the chat's screen holds beside the chat (docs/plan_v4/05-the-chat.md §4 to §6): the first of his
 * messages unread when it was opened, for the divider, and where it opens; how many of the archive's
 * messages are shown, scrolled back to; the message your next one replies to; and which of his messages
 * have what's under them open, and which get the label that slides out. A plain class, tested without
 * Compose: the screen keeps one while it's there and draws from [items].
 */
class ChatScreenState {
    /** One row of the list: a day's date, the divider over the first unread, a message, or the row that loads the archive's next page. */
    sealed interface Item {
        val key: String

        /** A day's date over its [first] message: keyed by that, as a message out of time order can bring a date back. */
        data class Day(val date: LocalDate, val first: String) : Item {
            override val key: String get() = "day-$first"
        }

        data object Divider : Item {
            override val key: String get() = "unread"
        }

        /**
         * A [message], with the message it replies to where that isn't the one above it ([quote]), shown over
         * its words; one from the archive ([archived]) shown with nothing to do, as only the window is acted on.
         */
        data class Message(val message: ChatMessage, val quote: ChatMessage? = null, val archived: Boolean = false) : Item {
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

    private var replying: ChatMessage? by mutableStateOf(null)

    /** The message your next one replies to (a swipe, Reply on a long press, or his question offered: [offer]), shown over the box. */
    var replyingTo: ChatMessage?
        get() = replying
        set(value) {
            replying = value
            // Chosen, sent or let go meanwhile: one still being read back from the archive gives way ([restoredReply]).
            restoringReply = null
        }

    /** The message replied to before the screen was made again, still being read from the archive. */
    private var restoringReply: String? = null

    /** [id], replied to before the screen was made again, to be read from the archive: put back by [restoredReply] unless the reply changes meanwhile. */
    fun restoringReply(id: String) {
        restoringReply = id
    }

    /** [message], read back from the archive, what the box replies to again, where nothing has changed that since [restoringReply]. */
    fun restoredReply(message: ChatMessage) {
        if (restoringReply == message.id) replyTo(message)
    }

    /** Whether [replyingTo] is the chat's own offer ([offer]), not a message you chose: a newer question takes its place. */
    var offered = false
        private set

    /** The question last offered to the box and turned down with ✕ ([notReplying]): not offered again. */
    private var declined: String? = null

    /** [message] chosen to reply to: a swipe, or Reply on a long press. */
    fun replyTo(message: ChatMessage) {
        replyingTo = message
        offered = false
    }

    /**
     * His [last] message offered to the box (D-98): where it's a question of his still waiting for its
     * answer ([Replies.waiting], a check-in's while [checkInUnderWay]), what you type answers it, as its
     * chips do, unless you've chosen another message or turned it down. The offer's let go once the
     * question's answered or isn't the last, or a newer nudge of his takes the box ([Replies.pending]).
     * A [nudge] of his still waiting is offered as a question is, where it isn't the message just above
     * the box: that one takes it with no strip, your words its reply.
     */
    fun offer(last: ChatMessage?, checkInUnderWay: Boolean, nudge: Boolean = false) {
        val question = last?.takeIf { (nudge || Replies.waiting(it, checkInUnderWay)) && it.id != declined }
        // A reply still being read back from the archive is one you chose: it isn't taken over ([restoringReply]).
        if (question != null && restoringReply == null && (replyingTo == null || offered)) {
            replyingTo = question
            offered = true
        } else if (question == null && offered) {
            replyingTo = null
            offered = false
        }
    }

    /** The strip's ✕: your words go to him as they are, and a question the chat offered isn't offered again. */
    fun notReplying() {
        if (offered) declined = replyingTo?.id
        replyingTo = null
        offered = false
    }

    /** Your message sent: the box replies to nothing till another's chosen or offered. */
    fun sent() {
        replyingTo = null
        offered = false
    }

    /** His messages with what's under them open (5c): none once the screen's paused ([paused]), so coming back finds every one closed. */
    var open: Set<String> by mutableStateOf(emptySet())
        private set

    /** Each opening of the chat, counted ([opened]): a row looks again at whether it gets its label, as what's unread then has changed. */
    var visit by mutableIntStateOf(0)
        private set

    /** When the chat was opened ([opened]): a message of his after it arrived while it was on screen. */
    private var since = Long.MAX_VALUE

    /** His messages unread when the chat was opened: each gets its label, once. */
    private var unreadOnOpen: Set<String> = emptySet()

    /** The labels shown this visit, so each slides out once. */
    private val shownLabels = mutableSetOf<String>()

    /** When the chat was last left with what it showed read ([paused]): read, though the write may not have reached the chat yet. */
    private var leftAt = 0L

    /**
     * The chat opened at [now], at [state] as it was before anything in it was read: what's unread then is
     * marked for this visit, and the list opens at [at] (his notification's message), else at the first
     * unread, else at its foot.
     */
    fun opened(state: ChatState, now: Long, at: String? = null) {
        // A question you've answered from his notification has been seen, as his button's count says; and
        // what it showed when it was left just now, whether or not that's reached the chat's read position.
        val readAt = maxOf(state.readAt, leftAt)
        // One you've replied to from it too: seen, as you answered it.
        val replied = Outbox.repliedTo(state)
        val unread = state.messages.filter { Outbox.unread(it, readAt) && it.answeredAt == null && it.id !in replied }
        firstUnread = unread.firstOrNull()?.id
        unreadOnOpen = unread.mapTo(HashSet()) { it.id }
        since = now
        visit++
        scrollTo = at ?: firstUnread
        flash = at
    }

    /** What's under message [id] opened, or closed again (its chevron). */
    fun toggle(id: String) {
        open = if (id in open) open - id else open + id
    }

    /** What's under message [id] opened (its label tapped). */
    fun unfold(id: String) {
        open = open + id
    }

    /**
     * Whether [message]'s label slides out now (Q5: A): one of his with something to say
     * ([ChatSummary.line]) that arrived while the chat was on screen, or was unread when it opened,
     * and hasn't had its label this visit.
     */
    fun labels(message: ChatMessage): Boolean =
        message.from == Speaker.Felix && message.id !in shownLabels && (message.at > since || message.id in unreadOnOpen) && !ChatSummary.line(message).isEmpty

    /** Message [id]'s label shown: not again this visit. */
    fun labelled(id: String) {
        shownLabels += id
    }

    /** The screen paused: every message closed again, and the visit's labels done with; what it showed read at [read], where it was. */
    fun paused(read: Long? = null) {
        open = emptySet()
        shownLabels.clear()
        unreadOnOpen = emptySet()
        since = Long.MAX_VALUE
        if (read != null) leftAt = maxOf(leftAt, read)
    }

    /** The list scrolled where it was to be: not again. */
    fun scrolled() {
        scrollTo = null
    }

    /** The moment's tint over. */
    fun flashed() {
        flash = null
    }

    /** The screen made again (turned): the archive's pages it had, and the message it was replying to, back, as offered or chosen. */
    fun restored(earlier: Int, replyingTo: ChatMessage?, offered: Boolean) {
        this.earlier = earlier
        this.replyingTo = replyingTo
        this.offered = offered && replyingTo != null
    }

    /** The chat cleared: nothing's left to reply to, nor of the archive to show. */
    fun cleared() {
        replyingTo = null
        offered = false
        declined = null
        earlier = 0
        firstUnread = null
    }

    /**
     * The archive's next [PAGE] shown over what's there, of the [archived] it holds: false when it has no
     * more. Null where it isn't read yet: its first page, shown once it is, and nothing more meanwhile.
     */
    fun loadEarlier(archived: Int?): Boolean {
        if (archived == null) {
            if (earlier > 0) return false
            earlier = PAGE
            return true
        }
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

    /** Message [id], [back] messages from the archive's newest, shown and scrolled to (a quote tapped, further back than the pages read). */
    fun revealBack(id: String, back: Int) {
        earlier = maxOf(earlier, back)
        scrollTo = id
    }

    /**
     * The list as it's drawn: the [archive]'s [earlier] newest messages, then the [window]'s, each day's
     * date over its first, the divider over the first unread, and over a reply what it replies to where
     * that isn't the message above it (from the [quotes] read of the archive, past its pages); the row that
     * loads the next page at the top while the archive has more, or holds some not read yet ([unread]). His
     * silences (nothing to say) aren't drawn.
     */
    fun items(window: List<ChatMessage>, archive: List<ChatMessage>, zone: ZoneId, unread: Boolean = false, quotes: List<ChatMessage> = emptyList()): List<Item> = buildList {
        if (earlier < archive.size || unread) add(Item.Earlier)
        val byId = (quotes + archive + window).associateBy { it.id }
        val live = window.mapTo(HashSet()) { it.id }
        var day: LocalDate? = null
        var above: ChatMessage? = null
        for (m in (archive.takeLast(earlier) + window).distinctBy { it.id }) {
            if (m.from == Speaker.Felix && m.text.isBlank()) continue
            val on = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
            if (on != day) {
                add(Item.Day(on, m.id))
                day = on
            }
            if (m.id == firstUnread) add(Item.Divider)
            add(Item.Message(m, m.replyTo?.takeIf { it != above?.id }?.let(byId::get)?.takeIf { it.text.isNotBlank() }, archived = m.id !in live))
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
