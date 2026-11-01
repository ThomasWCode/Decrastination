package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ChatSummary
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.Draft
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.felix.Replies
import com.thomaswcode.decrastination.felix.Unsent
import java.time.ZoneId

/**
 * What the chat's screen holds beside the chat (docs/spec/felix.md §The chat): the first of his
 * messages unread when it was opened, for the divider, and where it opens; how many of the archive's
 * messages are shown, scrolled back to; the message your next one replies to; and which of his messages
 * have what's under them open, and which get the label that slides out. A plain class, tested without
 * Compose: the screen keeps one while it's there and draws from [items].
 */
class ChatScreenState {
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

    /** Your message sent: the box replies to nothing till another's chosen or offered; and the visit's seen, as you've answered ([markSeen]). */
    fun sent() {
        replyingTo = null
        offered = false
        markSeen()
    }

    /** His messages with what's under them open: none once the screen's paused ([paused]), so coming back finds every one closed. */
    var open: Set<String> by mutableStateOf(emptySet())
        private set

    /** Each opening of the chat, counted ([opened]): a row looks again at whether it gets its label, as what's unread then has changed. */
    var visit by mutableIntStateOf(0)
        private set

    /** When the chat was opened ([opened]): a message of his after it arrived while it was on screen. */
    private var since = Long.MAX_VALUE

    /** This visit marked seen ([markSeen]) before its [READ_AFTER_MS] were up. */
    private var markedSeen = false

    /**
     * Whether this visit has been seen at [now] (Q120): on screen [READ_AFTER_MS] since it [opened], unbroken, or
     * marked so ([markSeen]: a message of yours sent, or opened from his notification). Only a seen visit reads
     * what it showed, and takes his notification away; one passed through leaves both as they were.
     */
    fun seen(now: Long): Boolean = markedSeen || (since != Long.MAX_VALUE && now - since >= READ_AFTER_MS)

    /** This visit seen now, whatever its time on screen. */
    fun markSeen() {
        markedSeen = true
    }

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
        // One you've replied to from it too: seen, as you answered it.
        val unread = Outbox.unreadOf(state, maxOf(state.readAt, leftAt))
        firstUnread = unread.firstOrNull()?.id
        unreadOnOpen = unread.mapTo(HashSet()) { it.id }
        since = now
        markedSeen = false
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

    /** The screen paused: every message closed again, and the visit's labels done with; what it showed read at [read], where it was ([seen]). */
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

    /** The list as it's drawn, by what this visit holds: [ChatRows.items]. */
    fun items(window: List<ChatMessage>, archive: List<ChatMessage>, zone: ZoneId, unread: Boolean = false, quotes: List<ChatMessage> = emptyList()): List<ChatRows.Item> =
        ChatRows.items(window, archive, earlier, firstUnread, zone, unread, quotes)

    companion object {
        const val PAGE = ChatRows.PAGE

        const val READ_AFTER_MS = ChatRows.READ_AFTER_MS

        /**
         * The app's draft, a screen made again after a stop of the process having [saved] one: what's newer. By time,
         * not by the holder being empty: an empty box can be a send's, newer than a lower chat's saved words, and those
         * mustn't come back into it. A holder at time 0 has had nothing in it yet, so saved words come back.
         */
        fun restoredDraft(held: Draft, saved: Draft): Draft = if (saved.at > held.at) saved else held

        /**
         * The box's [draft] with [words] handed in to it (*Split with Felix*, *What gives?*) to change or send: the words,
         * a blank line, then what you'd typed (Q127); as it is where it holds them first already, handed in again.
         */
        fun withHanded(draft: String, words: String): String = when {
            words.isBlank() || draft.startsWith(words) -> draft
            draft.isBlank() -> words
            else -> words + "\n\n" + draft
        }

        /**
         * The box's [draft] with [unsent] back in it, and whether its subject and reply come back too: into an empty box,
         * as it was; into words typed meanwhile, its words first, the box's subject and reply as you've set them since.
         */
        fun withUnsent(draft: String, unsent: Unsent): Pair<String, Boolean> =
            if (draft.isEmpty()) unsent.text to true else (unsent.text + "\n\n" + draft) to false
    }
}
