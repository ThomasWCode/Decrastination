package com.thomaswcode.decrastination.sources.gmail

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.sources.SourceUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One connection's writes to Gmail for a finished email (docs/plan_v4/10-emails-done.md): the port the
 * journal's writes go through ([com.thomaswcode.decrastination.sync.GmailFinishes]), so they're tested
 * without a server.
 */
interface GmailWriter {
    /** Connects, logs in and opens All Mail for writing; runs [block] on it; logs out, whatever happens. */
    suspend fun <T> open(block: suspend (Session) -> T): T

    /** All Mail, open for writing. */
    interface Session {
        /** The UIDs of conversation [threadId]'s messages, as they are now (X-GM-THRID), whatever renumbered them. */
        fun thread(threadId: String): List<Long>

        /** [uids]' messages as they are: each one's message id (X-GM-MSGID), and whether it's starred and in the inbox. */
        fun messages(uids: List<Long>): List<Message>

        /** [change] made to [uids]: those refused, each alone (a message gone since). */
        fun store(uids: List<Long>, change: Change): List<Long>
    }

    /** A message of a conversation, as a write reads it first. */
    data class Message(val uid: Long, val id: Long, val starred: Boolean, val inbox: Boolean)

    /** What a write does to a message: a finish's two, and an Undo's. */
    enum class Change { Unstar, Archive, Star, Inbox }
}

/**
 * [GmailWriter] over IMAP with the app password: a connection of its own, as a sync's read has, All Mail
 * opened for writing, [ImapWriter] storing the changes (D-7 as amended). A cancelled write closes its socket,
 * so a blocking read ends.
 */
class ImapGmailWriter(private val secrets: SecretStore) : GmailWriter {
    override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T = withContext(Dispatchers.IO) {
        val address = secrets[Secret.GmailAddress]
        val password = secrets[Secret.GmailAppPassword]
        if (address.isNullOrBlank() || password.isNullOrBlank()) throw SourceUnavailable("No Gmail address and app password saved")
        val socket = GmailConnection.open()
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            socket.use {
                // Each command by its name and UIDs (the login's without its address), at info level: a write is worth seeing.
                val imap = ImapClient(socket.inputStream, socket.outputStream) { Log.i(AppGraph.TAG, "IMAP $it") }
                imap.greeting()
                imap.login(address, password)
                val writer = ImapWriter(imap)
                writer.select(StarredConversations.allMail(imap.list()))
                try {
                    block(session(imap, writer))
                } finally {
                    imap.logout()
                }
            }
        } finally {
            closer.cancel()
        }
    }

    private fun session(imap: ImapClient, writer: ImapWriter) = object : GmailWriter.Session {
        override fun thread(threadId: String): List<Long> = imap.uidSearch("X-GM-THRID $threadId")

        override fun messages(uids: List<Long>): List<GmailWriter.Message> = imap.uidFetch(uids, "X-GM-MSGID FLAGS X-GM-LABELS").mapNotNull { attributes ->
            val uid = attributes["UID"]?.string?.toLongOrNull() ?: return@mapNotNull null
            val id = attributes["X-GM-MSGID"]?.string?.toLongOrNull() ?: return@mapNotNull null
            val flags = attributes["FLAGS"]?.list.orEmpty().mapNotNull { it.string }
            val labels = attributes["X-GM-LABELS"]?.list.orEmpty().mapNotNull { it.string }
            GmailWriter.Message(uid, id, starred = flags.any { it.equals("\\Flagged", ignoreCase = true) }, inbox = labels.any { it.equals("\\Inbox", ignoreCase = true) })
        }

        override fun store(uids: List<Long>, change: GmailWriter.Change): List<Long> = writer.uidStore(
            uids,
            when (change) {
                GmailWriter.Change.Unstar -> ImapWriter.UNSTAR
                GmailWriter.Change.Archive -> ImapWriter.ARCHIVE
                GmailWriter.Change.Star -> ImapWriter.STAR
                GmailWriter.Change.Inbox -> ImapWriter.INBOX
            },
        )
    }
}
