package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.data.ChatArchiveStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.notify.NotifyRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Felix's outbox (docs/spec/felix.md §Notifications and replies): every message in his name is put in the chat first,
 * so a notification can't point at one that isn't there; what's past the chat's live window then moves
 * to its archive; then his one notification is made again from the chat's unread ([notify]), rebuilt,
 * not added to. Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and his
 * ports in its constructor.
 */
class Outbox(
    private val chat: JsonStore<ChatState>,
    private val archive: ChatArchiveStore,
    private val settings: JsonStore<Settings>,
    private val notices: Notices,
    private val ports: FelixPorts,
    /** Whether the chat is on screen: nothing sounds then, as you're reading it. */
    private val chatOnScreen: () -> Boolean,
    /** Whether the chat on screen has been seen (Q120): only then is his notification taken away. */
    private val chatSeen: () -> Boolean,
    private val clock: WallClock,
) {
    /**
     * [message], his, put in the chat, with what it changes of his state ([state]) in the same write;
     * then the window moved, and his notification made again, sounding for it where [alert] and it's in it.
     */
    suspend fun send(message: ChatMessage, alert: Boolean = true, state: (FelixState) -> FelixState = { it }) {
        chat.update { s -> s.copy(messages = s.messages.filterNot { it.id == message.id } + message, felix = state(s.felix)) }
        delivered(message.id.takeIf { alert && message.text.isNotBlank() })
    }

    /** After a message is written to the chat: the window moved, and his notification made again, sounding for [alertFor] where it's in it. */
    suspend fun delivered(alertFor: String? = null) {
        val now = clock.now()
        moveWindow(now)
        notify(now, alertFor)
    }

    /** Held while messages move to the archive, and while the chat's cleared: a move under way can't put back what a clear took. */
    private val moving = Mutex()

    /**
     * What's past the live window and read moved to the archive ([past]): the archive's update first,
     * then the window's trim, so a stop between leaves a copy (dropped by id at the next move), never a hole.
     * Only as it was copied: one changed meanwhile (a question answered as the archive was written) stays,
     * to move as it now is at the next move, so the archive's copy never stands for a newer one.
     */
    suspend fun moveWindow(now: Long): Unit = moving.withLock {
        val past = past(chat.value, now)
        if (past.isEmpty()) return@withLock
        archive.add(past)
        val moved = past.associateBy { it.id }
        chat.update { s -> s.copy(messages = s.messages.filterNot { moved[it.id] == it }) }
    }

    /** The chat emptied, its archive too ("Clear the chat"): his state stays, and so does what its cards made. */
    suspend fun clear() {
        moving.withLock {
            chat.update { it.copy(messages = emptyList()) }
            // The window's copies with it (D-94): what's cleared isn't kept in one.
            chat.letGoOfCopies()
            archive.clear()
        }
        notify(clock.now())
    }

    /**
     * Message [id] in the archive marked answered at [at] (a question of his answered from there), where it's
     * there and waiting. One in the live window is answered there: the archive isn't read for it.
     */
    suspend fun answeredInArchive(id: String, at: Long) {
        if (chat.value.messages.any { it.id == id }) return
        archive.answered(id, at)
    }

    /** Message [id] in the archive as [change] makes it (your rating of it, D-119); one in the live window is the chat's, and the archive isn't read for it. */
    suspend fun changeInArchive(id: String, change: (ChatMessage) -> ChatMessage) {
        if (chat.value.messages.any { it.id == id }) return
        archive.change(id, change)
    }

    /** Message [id] in the archive waiting for its answer again (a reply under it that wasn't one); one in the live window is the chat's. */
    suspend fun waitingInArchive(id: String) {
        if (chat.value.messages.any { it.id == id }) return
        archive.unanswered(id)
    }

    /** Message [id], from the live window or, past it, the archive (a reply to one scrolled back to). */
    fun find(id: String): ChatMessage? = chat.value.messages.firstOrNull { it.id == id } ?: archive.find(id)

    /** Held while his notification is made from the chat and posted, so the last posted is made from the newest. */
    private val posting = Any()

    /**
     * His notification made again from the chat ([notice]), sounding for [alertFor] where it's in it; or
     * taken away with nothing to show, his channel off, or the chat on screen and seen, where you're reading
     * them. On screen and not seen yet (Q120), it's made again as it is, never sounding.
     */
    fun notify(now: Long, alertFor: String? = null) = synchronized(posting) {
        val quiet = BlockPolicy.isQuiet(now, clock.zone(), settings.value)
        val onScreen = chatOnScreen()
        val notice = if ((onScreen && chatSeen()) || !notices.shown(Channels.FELIX)) null else notice(chat.value, settings.value.felixName, quiet, alertFor.takeUnless { onScreen })
        if (notice == null) ports.cancel() else ports.show(notice)
    }

    /**
     * His one notification as the chat makes it (docs/spec/felix.md §Notifications and replies): his [name]; its [lines],
     * oldest first, his unread and your replies to them; a reply answers [replyTo] (the newest of his),
     * offering its [replies]; a tap opens the chat at [openAt] (the first unread); it sounds where [alert].
     */
    data class Notice(val name: String, val lines: List<Line>, val replyTo: String, val replies: List<String>, val openAt: String, val alert: Boolean) {
        /** One message in it: its words, its time, and whether it's [yours]. */
        data class Line(val id: String, val text: String, val at: Long, val yours: Boolean)
    }

    companion object {
        /**
         * The live window keeps the newest so many messages, and all of those within [WINDOW_MS], whichever
         * keeps more (D-96): a fortnight covers the week his review reads, and the chat's Undo reaches as far.
         */
        const val WINDOW = 200
        const val WINDOW_MS = 14 * 24 * 3_600_000L

        /**
         * His messages you've replied to ([ChatMessage.replyTo]): seen, as you answered them, from his notification too,
         * though the read position stays for it. Only those: what the notification left out of its room stays unread.
         */
        fun repliedTo(state: ChatState): Set<String> = state.messages.mapNotNullTo(HashSet()) { m -> m.replyTo.takeIf { m.from == Speaker.You } }

        /** Whether [message] is his, with words, and unread at [readAt]: shown nowhere yet, so never moved. */
        fun unread(message: ChatMessage, readAt: Long): Boolean = message.from == Speaker.Felix && message.at > readAt && message.text.isNotBlank()

        /**
         * The messages of [state] past its live window at [now]: older than the newest [WINDOW] and than
         * [WINDOW_MS] both, and read; never an unread one, nor yours waiting for its answer, nor yours replying to
         * one of his still unread, which says that one's seen ([repliedTo]).
         */
        fun past(state: ChatState, now: Long): List<ChatMessage> {
            val newest = state.messages.takeLast(WINDOW).mapTo(HashSet()) { it.id }
            val waiting = state.unanswered?.id
            val unreadIds = state.messages.filter { unread(it, state.readAt) }.mapTo(HashSet()) { it.id }
            return state.messages.filter { m -> m.id !in newest && m.at < now - WINDOW_MS && m.id != waiting && !unread(m, state.readAt) && m.replyTo !in unreadIds }
        }

        /**
         * His notification from [state] (pure): his unread messages with words, but those answered already
         * (a question settled here or elsewhere) and those of a kind that waits for the morning in quiet
         * hours ([NotifyRules.waitsForMorning]); your replies to those under them; as many as fit its room,
         * the newest first ([roomed]). None, and it's null.
         * What waits for the morning waits for its briefing: one written before the morning's message is
         * shown from then on, every time it's made again, quiet hours that outlast the briefing or not.
         */
        fun notice(state: ChatState, name: String, quiet: Boolean, alertFor: String? = null): Notice? {
            val after = state.messages.filter { it.at > state.readAt }
            val morning = state.messages.lastOrNull { it.kind == MessageKind.Morning }?.at ?: Long.MIN_VALUE
            val his = after.filter { unread(it, state.readAt) && it.answeredAt == null && !(it.at > morning && NotifyRules.waitsForMorning(it.kind, quiet)) }
            if (his.isEmpty()) return null
            val shown = his.mapTo(HashSet()) { it.id }
            // In the chat's order, which is the order they were said in.
            val lines = after.filter { it.id in shown || (it.from == Speaker.You && it.replyTo in shown) }.flatMap(::lines)
            val newest = his.last()
            // A tap opens at the first you haven't replied to: one you have, you've seen.
            val replied = repliedTo(state)
            return Notice(name, roomed(lines), newest.id, newest.suggestedReplies, (his.firstOrNull { it.id !in replied } ?: newest).id, alert = alertFor != null && alertFor in shown)
        }

        /**
         * His notification's room, its lines' characters together: about what One UI shows of an expanded
         * notification above its Reply and chips. Its height is held, and what runs past it is cut off, the
         * buttons with it (on the phone: three long answers left no Reply).
         */
        const val NOTICE_ROOM = 360

        /** Less room than this left, an older line isn't begun: it would be a word or two. */
        private const val LINE_MIN = 60

        /**
         * [lines] within [NOTICE_ROOM], the newest first: each that runs past what's left ended at its last whole
         * sentence ([FelixAnswers.message]); the older ones that don't fit left to the chat, which a tap opens.
         */
        private fun roomed(lines: List<Notice.Line>): List<Notice.Line> {
            var left = NOTICE_ROOM
            val kept = ArrayList<Notice.Line>()
            for (l in lines.asReversed()) {
                if (left < LINE_MIN && kept.isNotEmpty()) break
                val text = FelixAnswers.message(l.text, maxOf(left, LINE_MIN))
                kept += l.copy(text = text)
                left -= text.length
            }
            return kept.asReversed()
        }

        /**
         * [message] as his notification's lines: his a line a text (D-120), as the chat shows them, the first ([id]#0) above
         * the last, which carries the message's id and the gist of what it made; yours, one.
         */
        private fun lines(message: ChatMessage): List<Notice.Line> {
            val texts = FelixAnswers.texts(message)
            return texts.dropLast(1).mapIndexed { i, text -> Notice.Line("${message.id}#$i", text, message.at, false) } +
                Notice.Line(message.id, line(message, texts.last()), message.at, message.from == Speaker.You)
        }

        /**
         * [message] in words, its [text] (its last text, for his), as his notification shows it: his, and the gist of what
         * it made (as the chat's history tells him of his cards); yours, and why it waits for his answer, if it does.
         */
        private fun line(message: ChatMessage, text: String = message.text): String = when (message.from) {
            Speaker.You -> text + (message.note?.takeIf { message.answeredAt == null }?.let { "\n($it)" } ?: "")
            Speaker.Felix -> (
                listOf(text) +
                    message.proposals.filter { it.state == ProposalState.Applied }.map { p -> p.task?.let { "Added “${it.title}”" } ?: p.lines.joinToString("; ") } +
                    message.acts.filter { it.action != null && !it.undone }.map { it.line }
                ).filter { it.isNotBlank() }.joinToString("\n")
        }
    }
}
