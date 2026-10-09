package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.core.Fetched
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One inbox message's envelope, as Gmail's IMAP gives it. */
data class InboxMessage(
    val uid: Long,
    /** Gmail's `X-GM-MSGID`, decimal. */
    val messageId: String,
    /** Gmail's `X-GM-THRID`, decimal: the conversation, which is what the inbox shows and archives. */
    val threadId: String,
    val receivedAt: Long,
    val subject: String,
    val fromName: String?,
    val fromAddress: String?,
    /** Gmail's `\Sent` label: you wrote it. */
    val sent: Boolean,
)

/**
 * The inbox as tasks, one per conversation, as Gmail shows it (docs/data-sources.md §4). A
 * conversation's task is done once none of its messages is in INBOX: archived, deleted, or
 * snoozed, which Gmail's IMAP can't tell apart, and which all mean "not now". If it comes back
 * (a snooze waking), the same task reopens. Pure, for the tests.
 */
object GmailThreads {
    const val EXTRA_MESSAGE_ID = "messageId"
    const val EXTRA_FROM = "from"

    /** When the newest message arrived (epoch millis), so "next Friday" is read from then. */
    const val EXTRA_RECEIVED = "received"

    /** "true" for a message you sent: a note to self. */
    const val EXTRA_SENT = "sent"
    const val EXTRA_NEXT_STEP = "nextStep"
    const val EXTRA_MESSAGES = "messages"

    /** Its text isn't fetched yet (a read fetches at most so many): fetch it next time. */
    const val EXTRA_TEXT_PENDING = "textPending"

    /**
     * How far its text was read ([MAX_BODY_CHARS] at the time): text that long may have been cut,
     * so it's read again once reads go further. Text stored without it (1.1.0 and earlier) is read
     * again once, however long: those reads stopped at 4 000 characters, or sooner at 32 KB of a
     * part (quoted-printable takes up to nine bytes a character).
     */
    const val EXTRA_TEXT_READ_TO = "textReadTo"

    /**
     * A message's text is cut to this: past what the model is shown (`Prompts.MAX_TEXT`), so it's
     * told when there's more, and the rules see as far as a long email's footer.
     */
    const val MAX_BODY_CHARS = 20_000

    /** A conversation's text, and how far it was read ([EXTRA_TEXT_READ_TO]). */
    data class Body(val text: String, val readTo: Int = MAX_BODY_CHARS) {
        /** All there is, or as much as a read takes now: reading it again would add nothing. */
        val whole: Boolean get() = text.length < readTo || readTo >= MAX_BODY_CHARS
    }

    /** Each conversation's newest message, newest conversation first. */
    fun latest(messages: List<InboxMessage>): List<InboxMessage> =
        messages.groupBy { it.threadId }
            .map { (_, thread) -> thread.maxWith(compareBy({ it.receivedAt }, { it.uid })) }
            .sortedByDescending { it.receivedAt }

    fun fetched(messages: List<InboxMessage>, bodies: Map<String, Body>, now: Long, zone: ZoneId): List<Fetched> {
        val counts = messages.groupingBy { it.threadId }.eachCount()
        return latest(messages).map { message ->
            val body = bodies[message.messageId]
            val text = body?.text.orEmpty()
            val subject = message.subject.ifBlank { "(no subject)" }
            val triage = EmailRules.triage(EmailRules.Email(message.fromName, message.fromAddress, subject, text, message.sent), now, zone)
            val from = listOfNotNull(message.fromName, message.fromAddress?.let { "<$it>" }).joinToString(" ")
            Fetched(
                sourceId = message.threadId,
                title = subject,
                kind = triage.kind,
                detail = text,
                dueAt = triage.dueAt,
                availableFrom = triage.availableFrom,
                sourceEffortMin = triage.effortMin,
                extra = buildMap {
                    put(EXTRA_MESSAGE_ID, message.messageId)
                    put(EXTRA_NEXT_STEP, triage.nextStep)
                    put(EXTRA_MESSAGES, counts[message.threadId].toString())
                    put(EXTRA_RECEIVED, message.receivedAt.toString())
                    if (message.sent) put(EXTRA_SENT, "true")
                    if (from.isNotEmpty()) put(EXTRA_FROM, from)
                    if (body == null) put(EXTRA_TEXT_PENDING, "true") else put(EXTRA_TEXT_READ_TO, body.readTo.toString())
                },
            )
        }
    }

    /**
     * The text already stored for each conversation, by its newest message's id: read again only
     * when that changes, or when it was cut shorter than reads now go ([Body.whole]).
     */
    fun knownBodies(known: List<TaskItem>): Map<String, Body> =
        known.filter { it.source == Source.Gmail && EXTRA_TEXT_PENDING !in it.extra }
            .mapNotNull { task ->
                // Read before how far was kept: nowhere near far enough, so it's read again.
                task.extra[EXTRA_MESSAGE_ID]?.let { it to Body(task.detail, task.extra[EXTRA_TEXT_READ_TO]?.toIntOrNull() ?: 0) }
            }
            .toMap()

    /**
     * Whose text a read fetches: each conversation's newest message whose text isn't stored, or
     * was cut shorter than reads now go, newest first, at most [max]. The rest wait for the reads
     * after, a cut text staying as it is meanwhile.
     */
    fun toRead(messages: List<InboxMessage>, known: Map<String, Body>, max: Int): List<InboxMessage> =
        latest(messages).filter { known[it.messageId]?.whole != true }.take(max)

    private val INTERNAL_DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH)

    /** One FETCH response, or null if it lacks what a task needs. */
    fun message(attributes: Map<String, ImapValue>): InboxMessage? {
        val uid = attributes["UID"]?.string?.toLongOrNull() ?: return null
        val messageId = attributes["X-GM-MSGID"]?.string ?: return null
        val threadId = attributes["X-GM-THRID"]?.string ?: messageId
        val envelope = attributes["ENVELOPE"]?.list.orEmpty()
        val from = envelope.getOrNull(2)?.list?.firstOrNull()?.list.orEmpty()
        val mailbox = from.getOrNull(2)?.string
        val host = from.getOrNull(3)?.string
        val labels = attributes["X-GM-LABELS"]?.list.orEmpty().mapNotNull { it.string }
        val received = attributes["INTERNALDATE"]?.string?.trim()
            ?.let { runCatching { ZonedDateTime.parse(it, INTERNAL_DATE).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        return InboxMessage(
            uid = uid,
            messageId = messageId,
            threadId = threadId,
            receivedAt = received,
            subject = envelope.getOrNull(1)?.string?.let(Mime::decodeHeader)?.trim().orEmpty(),
            fromName = from.getOrNull(0)?.string?.let(Mime::decodeHeader)?.trim()?.takeIf { it.isNotEmpty() },
            fromAddress = if (mailbox != null && host != null) "$mailbox@$host".lowercase() else null,
            sent = labels.any { it.equals("\\Sent", ignoreCase = true) },
        )
    }
}

/**
 * Gmail over IMAP with an app password (docs/data-sources.md §4), read-only: see [ImapClient].
 * A read lists every inbox message's envelope, in batches, since a read that left some out would
 * take their conversations as archived. It fetches the text of only those conversations whose
 * newest message is new since the last read (or whose text was cut shorter than reads now go), at
 * most [MAX_BODIES] a read; the rest are fetched on the reads after.
 */
class GmailSource(private val secrets: SecretStore) : TaskSource {
    override val source = Source.Gmail

    override suspend fun read(context: ReadContext): SourceRead = withContext(Dispatchers.IO) {
        val address = secrets[Secret.GmailAddress]
        val password = secrets[Secret.GmailAppPassword]
        if (address.isNullOrBlank() || password.isNullOrBlank()) throw SourceUnavailable("No Gmail address and app password saved")
        val known = GmailThreads.knownBodies(context.known)
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
                val imap = ImapClient(socket.inputStream, socket.outputStream)
                imap.greeting()
                imap.login(address, password)
                val count = imap.examine("INBOX")
                val uids = if (count == 0) emptyList() else imap.uidSearch("ALL").sorted()
                val messages = uids.chunked(FETCH_BATCH).flatMap { batch ->
                    imap.uidFetch(batch, "UID INTERNALDATE X-GM-MSGID X-GM-THRID X-GM-LABELS ENVELOPE").mapNotNull(GmailThreads::message)
                }
                if (messages.size < uids.size) throw IOException("Gmail listed ${uids.size} messages but described ${messages.size}")
                val bodies = known + GmailThreads.toRead(messages, known, MAX_BODIES).associate { it.messageId to GmailThreads.Body(text(imap, it.uid)) }
                imap.logout()
                SourceRead(GmailThreads.fetched(messages, bodies, context.now, context.zone))
            }
        } finally {
            closer.cancel()
        }
    }

    /**
     * The message's readable text, from its first plain (else HTML) part, cut to
     * [GmailThreads.MAX_BODY_CHARS]. The part is fetched whole, up to [MAX_PART_BYTES]: HTML's
     * text can come after 50 KB of markup and styles (Warwick's Open Day email's did).
     */
    private fun text(imap: ImapClient, uid: Long): String {
        val structure = imap.uidFetch(listOf(uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return ""
        val part = Mime.textPart(structure) ?: return ""
        val response = imap.uidFetch(listOf(uid), "UID BODY.PEEK[${part.section}]<0.$MAX_PART_BYTES>").firstOrNull() ?: return ""
        val body = response.entries.firstOrNull { it.key.startsWith("BODY[") }?.value as? ImapValue.Str ?: return ""
        return Mime.tidy(Mime.decode(body.bytes, part), GmailThreads.MAX_BODY_CHARS)
    }

    /** TLS to Gmail, with the host name checked against its certificate. */
    private fun connect(): SSLSocket {
        val plain = Socket()
        plain.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, HOST, PORT, true) as SSLSocket
        socket.soTimeout = TIMEOUT_MS
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        socket.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, socket.session)) {
            socket.close()
            throw IOException("Gmail's certificate doesn't match $HOST")
        }
        return socket
    }

    private companion object {
        const val HOST = "imap.gmail.com"
        const val PORT = 993
        const val TIMEOUT_MS = 30_000
        const val FETCH_BATCH = 200
        const val MAX_BODIES = 60

        /**
         * Enough for [GmailThreads.MAX_BODY_CHARS] characters however they're sent (quoted-printable
         * takes nine bytes for a three-byte UTF-8 character), and for HTML's markup before its text.
         */
        const val MAX_PART_BYTES = 200_000
    }
}
