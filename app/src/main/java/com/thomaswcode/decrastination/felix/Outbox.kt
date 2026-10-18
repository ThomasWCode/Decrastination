package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.FelixState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notices
import com.thomaswcode.decrastination.notify.NotifyRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Felix's outbox (docs/plan_v4/05-the-chat.md §2): every message in his name is put in the chat first,
 * so a notification can't point at one that isn't there; what's past the chat's live window then moves
 * to its archive; then his one notification is made again from the chat's unread ([notify]), rebuilt,
 * not added to. Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and his
 * ports in its constructor.
 */
class Outbox(
    private val chat: JsonStore<ChatState>,
    private val archive: JsonStore<ChatArchive>,
    private val settings: JsonStore<Settings>,
    private val notices: Notices,
    private val ports: FelixPorts,
    /** Whether the chat is on screen: nothing's posted then, as you're reading it. */
    private val chatOnScreen: () -> Boolean,
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
     */
    suspend fun moveWindow(now: Long): Unit = moving.withLock {
        val past = past(chat.value, now)
        if (past.isEmpty()) return@withLock
        archive.update { a ->
            val there = a.messages.mapTo(HashSet()) { it.id }
            a.copy(messages = a.messages + past.filter { it.id !in there })
        }
        val moved = past.mapTo(HashSet()) { it.id }
        chat.update { s -> s.copy(messages = s.messages.filterNot { it.id in moved }) }
    }

    /** The chat emptied, its archive too ("Clear the chat"): his state stays, and so does what its cards made. */
    suspend fun clear() {
        moving.withLock {
            chat.update { it.copy(messages = emptyList()) }
            archive.update { ChatArchive() }
        }
        notify(clock.now())
    }

    /** Message [id] in the archive marked answered at [at] (a question of his answered from there), where it's there and waiting. */
    suspend fun answeredInArchive(id: String, at: Long) {
        if (archive.value.messages.none { it.id == id && it.answeredAt == null }) return
        archive.update { a -> a.copy(messages = a.messages.map { if (it.id == id && it.answeredAt == null) it.copy(answeredAt = at) else it }) }
    }

    /** Message [id], from the live window or, past it, the archive (a reply to one scrolled back to). */
    fun find(id: String): ChatMessage? = chat.value.messages.firstOrNull { it.id == id } ?: archive.value.messages.firstOrNull { it.id == id }

    /** Held while his notification is made from the chat and posted, so the last posted is made from the newest. */
    private val posting = Any()

    /**
     * His notification made again from the chat ([notice]), sounding for [alertFor] where it's in it; or
     * taken away with nothing to show, his channel off, or the chat on screen, where you're reading them.
     * The morning's briefing makes it as the [morning]: what waited overnight is in it, quiet hours that
     * outlast the briefing or not.
     */
    fun notify(now: Long, alertFor: String? = null, morning: Boolean = false) = synchronized(posting) {
        val quiet = !morning && BlockPolicy.isQuiet(now, clock.zone(), settings.value)
        val notice = if (chatOnScreen() || !notices.shown(Channels.FELIX)) null else notice(chat.value, settings.value.felixName, quiet, alertFor)
        if (notice == null) ports.cancel() else ports.show(notice)
    }

    /**
     * His one notification as the chat makes it (docs/plan_v4/05-the-chat.md §3): his [name]; its [lines],
     * oldest first, his unread and your replies to them; a reply answers [replyTo] (the newest of his),
     * offering its [replies]; a tap opens the chat at [openAt] (the first unread); it sounds where [alert].
     */
    data class Notice(val name: String, val lines: List<Line>, val replyTo: String, val replies: List<String>, val openAt: String, val alert: Boolean) {
        /** One message in it: its words, its time, and whether it's [yours]. */
        data class Line(val id: String, val text: String, val at: Long, val yours: Boolean)
    }

    companion object {
        /** The live window keeps the newest so many messages, and all of those within [WINDOW_MS], whichever keeps more. */
        const val WINDOW = 200
        const val WINDOW_MS = 60 * 24 * 3_600_000L

        /** Whether [message] is his, with words, and unread at [readAt]: shown nowhere yet, so never moved. */
        fun unread(message: ChatMessage, readAt: Long): Boolean = message.from == Speaker.Felix && message.at > readAt && message.text.isNotBlank()

        /**
         * The messages of [state] past its live window at [now]: older than the newest [WINDOW] and than
         * [WINDOW_MS] both, and read; never an unread one, nor yours waiting for its answer.
         */
        fun past(state: ChatState, now: Long): List<ChatMessage> {
            val newest = state.messages.takeLast(WINDOW).mapTo(HashSet()) { it.id }
            val waiting = state.unanswered?.id
            return state.messages.filter { m -> m.id !in newest && m.at < now - WINDOW_MS && m.id != waiting && !unread(m, state.readAt) }
        }

        /**
         * His notification from [state] (pure): his unread messages with words, but those answered already
         * (a question settled here or elsewhere) and those of a kind that waits for the morning in quiet
         * hours ([NotifyRules.waitsForMorning]); your replies to those under them. None, and it's null.
         */
        fun notice(state: ChatState, name: String, quiet: Boolean, alertFor: String? = null): Notice? {
            val after = state.messages.filter { it.at > state.readAt }
            val his = after.filter { unread(it, state.readAt) && it.answeredAt == null && !NotifyRules.waitsForMorning(it.kind, quiet) }
            if (his.isEmpty()) return null
            val shown = his.mapTo(HashSet()) { it.id }
            // In the chat's order, which is the order they were said in.
            val lines = after.filter { it.id in shown || (it.from == Speaker.You && it.replyTo in shown) }.map { Notice.Line(it.id, line(it), it.at, it.from == Speaker.You) }
            val newest = his.last()
            return Notice(name, lines, newest.id, newest.suggestedReplies, his.first().id, alert = alertFor != null && alertFor in shown)
        }

        /**
         * [message] in words, as his notification shows it: his, and the gist of what it made (as the
         * chat's history tells him of his cards); yours, and why it waits for his answer, if it does.
         */
        private fun line(message: ChatMessage): String = when (message.from) {
            Speaker.You -> message.text + (message.note?.takeIf { message.answeredAt == null }?.let { "\n($it)" } ?: "")
            Speaker.Felix -> (
                listOf(message.text) +
                    message.proposals.filter { it.state == ProposalState.Applied }.map { p -> p.task?.let { "Added “${it.title}”" } ?: p.lines.joinToString("; ") } +
                    message.acts.filter { it.action != null && !it.undone }.map { it.line }
                ).filter { it.isNotBlank() }.joinToString("\n")
        }
    }
}
