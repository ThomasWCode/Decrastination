package com.thomaswcode.decrastination.sources.gmail

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Invite
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.ZoneId
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One message's envelope, as Gmail's IMAP gives it. */
data class MailMessage(
    val uid: Long,
    /** Gmail's `X-GM-MSGID`, decimal. */
    val messageId: String,
    /** Gmail's `X-GM-THRID`, decimal: the conversation it's in. */
    val threadId: String,
    val receivedAt: Long,
    val subject: String,
    val fromName: String?,
    val fromAddress: String?,
    /** Gmail's `\Sent` label: you wrote it. */
    val sent: Boolean,
)

/**
 * A conversation with a starred message in it (DX-03), as a read finds it: every message of it,
 * starred or not, by its UID in All Mail, and what its task is named, dated and triaged by.
 */
data class Conversation(
    val threadId: String,
    /** Its messages' UIDs in All Mail, ascending: what its text is read from. */
    val uids: List<Long>,
    /** The `UIDVALIDITY` those UIDs are good for. */
    val uidValidity: Long?,
    /** Its newest message's subject, Gmail id and arrival: the task's title, and when it last moved. */
    val subject: String,
    val messageId: String,
    val receivedAt: Long,
    /** Who it's with: the sender of its newest message not yours, or yours where every one is. */
    val fromName: String?,
    val fromAddress: String?,
    /** Every message in it is yours: a note to self, perhaps added to. */
    val sent: Boolean,
) {
    /** Which messages its text is of: a stored text is used again while this stays the same. */
    val version: String get() = GmailThreads.version(uidValidity, uids)
}

/**
 * The conversations of your starred messages, as Gmail lists them over [ImapClient] (DX-03), with
 * nothing fetched that's already known (DX-07): a read's first half, before the texts.
 */
object StarredConversations {

    /**
     * What a listing found: every conversation; the envelopes it fetched, which a conversation whose
     * text is read takes its messages' senders and dates from; and the texts already stored.
     */
    data class Listed(val conversations: List<Conversation>, val envelopes: Map<Long, MailMessage>, val stored: Map<String, GmailThreads.Body>)

    /** What a message's envelope is fetched with: enough to name, date and place it. */
    const val ENVELOPE = "UID INTERNALDATE X-GM-MSGID X-GM-THRID X-GM-LABELS ENVELOPE"

    /** Conversations searched for in one command: forty keys keep it well under any line limit. */
    private const val THREADS_PER_SEARCH = 40
    private const val FETCH_BATCH = 200

    /**
     * All Mail, by its special use (RFC 6154, as its name is in the account's language); the starred
     * messages in it; and every message of their conversations, starred or not, yours included,
     * drafts aside. A message stored under All Mail's `UIDVALIDITY` as it is ([known]) needs no
     * envelope, nor does a conversation whose messages are all such and whose text is read: it's
     * built from its task. Any other conversation has every message's envelope fetched. Throws
     * rather than list less than there is: a conversation missing would be taken as unstarred.
     */
    fun list(imap: ImapClient, known: List<TaskItem>): Listed {
        val boxes = imap.list()
        val allMail = boxes.firstOrNull { "\\all" in it.attributes }?.name
            ?: boxes.firstOrNull { it.name.endsWith("/All Mail", ignoreCase = true) }?.name
            ?: throw SourceUnavailable("Gmail has no All Mail folder")
        val box = imap.examine(allMail)
        val knownUids = GmailThreads.knownUids(known, box.uidValidity)
        val envelopes = HashMap<Long, MailMessage>()
        fun describe(uids: List<Long>) {
            if (uids.isEmpty()) return
            uids.chunked(FETCH_BATCH).forEach { batch ->
                imap.uidFetch(batch, ENVELOPE).mapNotNull(GmailThreads::message).forEach { envelopes[it.uid] = it }
            }
            val described = uids.count { it in envelopes }
            if (described < uids.size) throw IOException("Gmail listed ${uids.size} messages but described $described")
        }
        // The starred messages, and the conversations they're in: a known one's as stored, a new one's
        // from its envelope.
        val starred = if (box.exists == 0) emptyList() else imap.uidSearch("FLAGGED")
        describe(starred.filter { it !in knownUids })
        val threads = starred.mapNotNull { knownUids[it]?.sourceId ?: envelopes[it]?.threadId }.distinct()
        val uids = threads.chunked(THREADS_PER_SEARCH).flatMap { imap.uidSearch("NOT DRAFT " + GmailThreads.anyThread(it)) }.distinct().sorted()
        describe(uids.filter { it !in knownUids && it !in envelopes })
        val byThread = uids.groupBy { knownUids[it]?.sourceId ?: envelopes.getValue(it).threadId }
        val stored = GmailThreads.knownBodies(known)
        val tasks = known.filter { it.source == Source.Gmail }.associateBy { it.sourceId }
        // As stored where nothing in it is new and its text is read; else from every message's
        // envelope, which reading its text needs too.
        val unchanged = byThread.mapNotNull { (thread, ids) ->
            tasks[thread]?.let { GmailThreads.stored(it, ids, box.uidValidity) }?.takeIf { stored[it.version]?.whole == true }?.let { thread to it }
        }.toMap()
        describe(byThread.filterKeys { it !in unchanged }.values.flatten().filter { it !in envelopes })
        val conversations = byThread.map { (thread, ids) -> unchanged[thread] ?: GmailThreads.conversation(ids.map(envelopes::getValue), box.uidValidity) }
        return Listed(conversations, envelopes, stored)
    }
}

/**
 * Gmail over IMAP with an app password (docs/spec/sources.md), read-only: see [ImapClient].
 * A read examines All Mail, finds the starred messages in it, and lists every message of their
 * conversations, so a reply of yours or an earlier message nobody starred is read too (DX-03). A
 * conversation with nothing new in it is built from what's stored, without fetching its envelopes
 * (DX-07); a changed one has all of them fetched, since a read that left some out would take their
 * conversations as unstarred. Texts are fetched for the conversations that are new or changed (or
 * whose text was cut shorter than reads now go), each whole, at most [MAX_BODIES] messages a read;
 * the rest are fetched on the reads after.
 */
class GmailSource(private val secrets: SecretStore) : TaskSource {
    override val source = Source.Gmail

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val address = secrets[Secret.GmailAddress]
        val password = secrets[Secret.GmailAppPassword]
        if (address.isNullOrBlank() || password.isNullOrBlank()) throw SourceUnavailable("No Gmail address and app password saved")
        val started = System.nanoTime()
        val socket = connect()
        // A read the sync has given up on mustn't hold the connection open: closing the socket,
        // from another thread, ends whichever blocking read is under way.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            socket.use {
                // Each command at debug level, the login's left out: how a read with nothing new
                // fetching no envelope is checked by hand (DX-07).
                val imap = ImapClient(socket.inputStream, socket.outputStream) { Log.d(AppGraph.TAG, "IMAP $it") }
                imap.greeting()
                imap.login(address, password)
                val (conversations, envelopes, stored) = StarredConversations.list(imap, context.known)
                // Texts are fetched for up to [TEXTS_FOR_NS] from here, but never past [TEXTS_BY_NS]
                // into the read; and one conversation even then, so a read slow to list still gets
                // somewhere, up to [LAST_TEXT_BY_NS], each answer waited for no longer than that
                // leaves, so none runs past the sync's 90 s. The rest wait for the reads after, as those
                // past MAX_BODIES do (BUG-P2-019). A conversation is read whole or not at all.
                val textsUntil = minOf(System.nanoTime() + TEXTS_FOR_NS, started + TEXTS_BY_NS)
                val lastBy = started + LAST_TEXT_BY_NS
                val read = LinkedHashMap<String, GmailThreads.Body?>()
                var cut = false
                conversations@ for (conversation in GmailThreads.toRead(conversations, stored, MAX_BODIES)) {
                    if (System.nanoTime() > lastBy || (read.isNotEmpty() && System.nanoTime() > textsUntil)) break
                    val texts = mutableListOf<Pair<MailMessage, String>>()
                    val attachments = mutableListOf<List<Mime.Attachment>>()
                    val invites = mutableListOf<Invite?>()
                    // What the first message read quotes isn't shown (it came before the conversation
                    // reached you, or before the messages a read takes), so it's read whole; the later
                    // ones' quotes are of messages shown above them (DX-06).
                    val messages = conversation.uids.map(envelopes::getValue)
                    val reading = GmailThreads.reading(messages)
                    val oldest = reading.first()
                    for (uid in reading.map { it.uid }) {
                        val now = System.nanoTime()
                        if (now > lastBy) break@conversations
                        socket.soTimeout = ((lastBy - now) / 1_000_000).toInt().coerceIn(1, TIMEOUT_MS)
                        val (text, attached, invite) = try {
                            textAndAttachments(imap, envelopes.getValue(uid), quotesCut = uid != oldest.uid, context.zone)
                        } catch (e: SocketTimeoutException) {
                            // Out of time mid-answer: the connection's spent, but what's read stands.
                            cut = true
                            break@conversations
                        }
                        // A message gone since it was listed: this conversation is read again next time.
                        if (text == null) {
                            read[conversation.version] = null
                            continue@conversations
                        }
                        texts += envelopes.getValue(uid) to text
                        attachments += attached
                        invites += invite
                    }
                    read[conversation.version] = GmailThreads.Body(
                        GmailThreads.conversationText(texts, context.zone, notRead = messages.size - reading.size),
                        attachments = GmailThreads.attachments(attachments),
                        invite = GmailThreads.invite(invites),
                    )
                }
                val bodies = GmailThreads.withRead(stored, read)
                if (!cut) {
                    socket.soTimeout = LOGOUT_MS
                    runCatching { imap.logout() }
                }
                SourceRead(GmailThreads.fetched(conversations, bodies, context.now, context.zone))
            }
        } finally {
            closer.cancel()
        }
    }

    /**
     * The message's readable text, from its first plain (else HTML) part, cut to
     * [GmailThreads.MAX_BODY_CHARS]: empty if it has none, null if it couldn't be read (gone from
     * the mailbox since it was listed); its attachments, from the same structure (DX-01); and its
     * calendar invite, where it has one (DX-02), its times read in [zone] where they name none. The
     * part is fetched whole, up to [MAX_PART_BYTES]: HTML's text can come after 50 KB of markup and
     * styles (Warwick's Open Day email's did).
     */
    private fun textAndAttachments(imap: ImapClient, message: MailMessage, quotesCut: Boolean, zone: ZoneId): Triple<String?, List<Mime.Attachment>, Invite?> {
        val structure = imap.uidFetch(listOf(message.uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return Triple(null, emptyList(), null)
        val attachments = Mime.attachments(structure)
        val text = text(imap, message, structure, quotesCut)
        val invite = if (text == null) null else Mime.invitePart(structure)?.let { invite(imap, message, it, zone) }
        return Triple(text, attachments, invite)
    }

    /** [part], a message's calendar, as its invite: a small part, fetched as a text is. */
    private fun invite(imap: ImapClient, message: MailMessage, part: Mime.TextPart, zone: ZoneId): Invite? {
        val response = imap.uidFetch(listOf(message.uid), "UID BODY.PEEK[${part.section}]<0.$MAX_PART_BYTES>").firstOrNull() ?: return null
        val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value as? ImapValue.Str ?: return null
        return Mime.invite(Mime.decode(body.bytes, part), zone)
    }

    private fun text(imap: ImapClient, message: MailMessage, structure: ImapValue, quotesCut: Boolean): String? {
        val part = Mime.textPart(structure) ?: return ""
        val response = imap.uidFetch(listOf(message.uid), "UID BODY.PEEK[${part.section}]<0.$MAX_PART_BYTES>").firstOrNull() ?: return null
        val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value ?: return null
        // NIL: a part with nothing in it. A forward by its subject keeps what it forwards (DX-06).
        val read = (body as? ImapValue.Str)?.let { Mime.readable(it.bytes, part, GmailThreads.MAX_BODY_CHARS, forwarded = Mime.isForward(message.subject), cut = quotesCut) }
        // What it quotes and its signature left out, and said (DX-06).
        val text = read?.let { if (it.cutChars > 0) (it.text + "\n\n" + GmailThreads.CUT_QUOTED.format(Locale.UK, it.cutChars)).trim() else it.text }.orEmpty()
        // A part longer than a read takes, its text short of the most kept: the rest wasn't read
        // (styles can fill the part's start), and the model and you are told (BUG-P2-022).
        return if ((part.size ?: 0) > MAX_PART_BYTES && (read?.text?.length ?: 0) < GmailThreads.MAX_BODY_CHARS) (text + "\n\n" + CUT).trim() else text
    }

    /**
     * TLS to Gmail, with the host name checked against its certificate. Closed on any failure before
     * it's handed over, the TLS layer and the socket under it (BUG-P2-021): the caller's `use` only
     * begins once it is.
     */
    private fun connect(): SSLSocket {
        val plain = Socket()
        var socket: SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, HOST, PORT, true) as SSLSocket
            socket.soTimeout = TIMEOUT_MS
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, socket.session)) throw IOException("Gmail's certificate doesn't match $HOST")
            return socket
        } catch (e: Throwable) {
            runCatching { socket?.close() }
            runCatching { plain.close() }
            throw e
        }
    }

    private companion object {
        const val HOST = "imap.gmail.com"
        const val PORT = 993
        const val TIMEOUT_MS = 30_000

        /** Messages whose texts a read fetches at most: a conversation counts all of its. */
        const val MAX_BODIES = 60

        /**
         * Enough for [GmailThreads.MAX_BODY_CHARS] characters however they're sent (quoted-printable
         * takes nine bytes for a three-byte UTF-8 character), and for HTML's markup before its text.
         */
        const val MAX_PART_BYTES = 200_000

        /** Said at the end of a text whose part ran past [MAX_PART_BYTES]. */
        const val CUT = "[The rest of this email is too long to be read here.]"

        /** How long a read spends fetching texts, once the conversations are listed: half the sync's 90 s wait. */
        const val TEXTS_FOR_NS = 45_000_000_000L

        /** How far into the read texts may go, however long listing took: room left within the sync's 90 s to log out and return. */
        const val TEXTS_BY_NS = 70_000_000_000L

        /** The last moment the one conversation a slow listing still gets may take, answers included. */
        const val LAST_TEXT_BY_NS = 82_000_000_000L

        /** How long logging out is waited for: the snapshot is read, so it's only good manners. */
        const val LOGOUT_MS = 3_000
    }
}
