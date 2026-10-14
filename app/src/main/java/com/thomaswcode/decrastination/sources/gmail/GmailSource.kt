package com.thomaswcode.decrastination.sources.gmail

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
 * Your starred emails as tasks, one per conversation (docs/data-sources.md §4). A conversation is
 * a task while any message of it is starred, and every message of it is read, starred or not, yours
 * included (the owner, 14 Oct): the star is the decision, and the rest of the conversation is what
 * it's about. Unstarred (or deleted), it's done; starred again, the same task reopens. Pure, for the
 * tests.
 */
object GmailThreads {
    const val EXTRA_MESSAGE_ID = "messageId"
    const val EXTRA_FROM = "from"

    /**
     * Who the conversation is with ([Conversation.fromName], [Conversation.fromAddress]), as the rules
     * read them: kept, so a conversation with nothing new is built from what's stored (DX-07).
     */
    const val EXTRA_FROM_NAME = "fromName"
    const val EXTRA_FROM_ADDRESS = "fromAddress"

    /** When the newest message arrived (epoch millis), so "next Friday" is read from then. */
    const val EXTRA_RECEIVED = "received"

    /** "true" for a conversation all yours: a note to self. */
    const val EXTRA_SENT = "sent"
    const val EXTRA_NEXT_STEP = "nextStep"
    const val EXTRA_MESSAGES = "messages"

    /**
     * Its messages' UIDs in All Mail, ascending, comma-separated (DX-07): known, they need no
     * envelope; and its text, unless still to be fetched, is of exactly these.
     */
    const val EXTRA_UIDS = "uids"

    /** The `UIDVALIDITY` those UIDs are good for: once All Mail's changes, every conversation is read afresh. */
    const val EXTRA_UID_VALIDITY = "uidValidity"

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

    /**
     * A conversation of several messages is kept within this, what the model is shown
     * (`Prompts.MAX_TEXT`), so its newest message is never what's cut: the newest messages go in
     * whole first, back to the oldest.
     */
    const val MAX_CONVERSATION_CHARS = 16_000

    /**
     * Said at the end of a message whose quotes of earlier messages, or signature, were cut (DX-06):
     * the conversation's own earlier messages are in it anyway, and what else was quoted isn't new.
     */
    const val CUT_QUOTED = "[Quoted text or a signature left out: %d characters.]"

    /** The least of an older message worth keeping when it's cut to fit; with less room, it's left out. */
    private const val LEAST_CUT = 400

    /** Kept for the line at the top of a conversation saying how many earlier messages were left out. */
    private const val LEFT_OUT_ROOM = 80

    /**
     * Its messages' attachments (DX-01), every message's, each once: a JSON list of [Mime.Attachment],
     * which reads back whole whatever a file is called.
     */
    const val EXTRA_ATTACHMENTS = "attachments"

    private val json = Json { ignoreUnknownKeys = true }
    private val ATTACHMENTS = ListSerializer(Mime.Attachment.serializer())

    /**
     * Which rules a stored text was made by ([EXTRA_TEXT_RULES]): raised whenever what's kept of a
     * message changes (what's cut, how a conversation is put together), so a text made by older
     * ones is read again, once, as one cut shorter than reads now go is. 1: review v2's (whole
     * conversations; the later messages' quotes cut, the oldest read whole; attachments named).
     */
    const val TEXT_RULES = 1
    const val EXTRA_TEXT_RULES = "textRules"

    /** A conversation's text, how far it was read ([EXTRA_TEXT_READ_TO]), what's attached to it, and by which [rules]. */
    data class Body(val text: String, val readTo: Int = MAX_BODY_CHARS, val attachments: List<Mime.Attachment> = emptyList(), val rules: Int = TEXT_RULES) {
        /** All there is, or as much as a read takes now, made as reads make it now: reading it again would add nothing. */
        val whole: Boolean get() = (text.length < readTo || readTo >= MAX_BODY_CHARS) && rules >= TEXT_RULES
    }

    /** [task]'s stored text, how far it was read, and by which rules: stored before those were kept, by none. */
    fun bodyOf(task: TaskItem): Body =
        Body(task.detail, task.extra[EXTRA_TEXT_READ_TO]?.toIntOrNull() ?: 0, attachmentsOf(task), task.extra[EXTRA_TEXT_RULES]?.toIntOrNull() ?: 0)

    /** What's attached to [task]'s conversation, as stored; none for a task from before attachments were kept. */
    fun attachmentsOf(task: TaskItem): List<Mime.Attachment> =
        task.extra[EXTRA_ATTACHMENTS]?.let { runCatching { json.decodeFromString(ATTACHMENTS, it) }.getOrNull() }.orEmpty()

    /**
     * Whether [task]'s text has been read as far as reads now go: not still to be fetched, nor cut
     * by an older, lower limit. Until it has, it isn't asked about, so nothing is planned from part
     * of an email.
     */
    fun textRead(task: TaskItem): Boolean = EXTRA_TEXT_PENDING !in task.extra && bodyOf(task).whole

    /** Which of All Mail's messages a text is of: [uids] under [uidValidity], whatever their order. */
    fun version(uidValidity: Long?, uids: List<Long>): String = "${uidValidity ?: ""}:" + uids.sorted().joinToString(",")

    private val NEWEST = compareBy<MailMessage>({ it.receivedAt }, { it.uid })

    /**
     * The first of [messages] to reach you: what it quotes is from before the conversation did, so
     * it's read whole, where the later messages' quotes are of messages shown above them (DX-06).
     */
    fun oldest(messages: List<MailMessage>): MailMessage = messages.minWith(NEWEST)

    /**
     * [messages], every message of one conversation, as its task is made from: named by its newest;
     * with whoever sent the newest message that isn't yours, and a note to self only if every
     * message is yours (a reply of yours at the end doesn't make the conversation your note).
     */
    fun conversation(messages: List<MailMessage>, uidValidity: Long?): Conversation {
        val newest = messages.maxWith(NEWEST)
        val with = messages.filterNot { it.sent }.maxWithOrNull(NEWEST) ?: newest
        return Conversation(
            threadId = newest.threadId,
            uids = messages.map { it.uid }.sorted(),
            uidValidity = uidValidity,
            subject = newest.subject,
            messageId = newest.messageId,
            receivedAt = newest.receivedAt,
            fromName = with.fromName,
            fromAddress = with.fromAddress,
            sent = messages.all { it.sent },
        )
    }

    /**
     * [task]'s conversation as stored, where its messages are [uids] as they were when it was read
     * (the same UIDs, under the same [uidValidity]): nothing in it to fetch again (DX-07). Null where
     * they've changed, or it was stored before conversations were kept.
     */
    fun stored(task: TaskItem, uids: List<Long>, uidValidity: Long?): Conversation? {
        if (uidValidity == null || task.extra[EXTRA_UID_VALIDITY] != uidValidity.toString()) return null
        if (task.extra[EXTRA_UIDS] != uids.sorted().joinToString(",")) return null
        val messageId = task.extra[EXTRA_MESSAGE_ID] ?: return null
        val received = task.extra[EXTRA_RECEIVED]?.toLongOrNull() ?: return null
        return Conversation(
            threadId = task.sourceId,
            uids = uids.sorted(),
            uidValidity = uidValidity,
            subject = task.title,
            messageId = messageId,
            receivedAt = received,
            fromName = task.extra[EXTRA_FROM_NAME],
            fromAddress = task.extra[EXTRA_FROM_ADDRESS],
            sent = task.extra[EXTRA_SENT] == "true",
        )
    }

    /**
     * The stored conversations' messages, by UID, under All Mail's [uidValidity] as it is now: their
     * envelopes needn't be fetched again (DX-07). None once it's changed: a full read.
     */
    fun knownUids(known: List<TaskItem>, uidValidity: Long?): Map<Long, TaskItem> {
        if (uidValidity == null) return emptyMap()
        return known.filter { it.source == Source.Gmail && it.extra[EXTRA_UID_VALIDITY] == uidValidity.toString() }
            .flatMap { task -> task.extra[EXTRA_UIDS].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.map { it to task } }
            .toMap()
    }

    /** `X-GM-THRID` search keys for any of [threadIds], nested as IMAP's OR takes two: `OR a OR b c`. */
    fun anyThread(threadIds: List<String>): String {
        require(threadIds.isNotEmpty() && threadIds.all { id -> id.isNotEmpty() && id.all(Char::isDigit) }) { "Thread ids are decimal" }
        val key = "X-GM-THRID ${threadIds.first()}"
        return if (threadIds.size == 1) key else "OR $key ${anyThread(threadIds.drop(1))}"
    }

    private val HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK)

    /**
     * A conversation's text, from each of its messages' ([messages], with their texts): one
     * message's alone; several oldest first, each headed `--- From …, 2026-10-08 18:02 ---` (yours
     * "From you"), within [MAX_CONVERSATION_CHARS]. The newest go in whole first; an older one too
     * long for the room left is cut to it, and those before it are left out, said at the top. The
     * time is written as numbers, so the rules' dates aren't read from a header.
     */
    fun conversationText(messages: List<Pair<MailMessage, String>>, zone: ZoneId): String {
        val ordered = messages.sortedWith(compareBy(NEWEST) { it.first })
        if (ordered.size == 1) return ordered.single().second
        val parts = ordered.map { (message, text) ->
            val from = if (message.sent) "you" else listOfNotNull(message.fromName, message.fromAddress?.let { "<$it>" }).joinToString(" ").ifEmpty { "someone" }
            "--- From $from, ${Instant.ofEpochMilli(message.receivedAt).atZone(zone).format(HEADER_TIME)} ---\n" + text.trim()
        }
        val kept = ArrayDeque<String>()
        // Room for the line saying what's left out, and a blank line between messages.
        var room = MAX_CONVERSATION_CHARS - LEFT_OUT_ROOM
        var leftOut = 0
        var leftChars = 0
        for (i in parts.indices.reversed()) {
            val part = parts[i]
            when {
                leftOut == 0 && part.length <= room -> {
                    kept.addFirst(part)
                    room -= part.length + 2
                }
                leftOut == 0 && room >= LEAST_CUT -> {
                    kept.addFirst(part.take(room - CUT_SHORT.length).trimEnd() + CUT_SHORT)
                    room = 0
                    leftOut = -1
                }
                else -> {
                    leftOut = maxOf(leftOut, 0) + 1
                    leftChars += ordered[i].second.length
                }
            }
        }
        if (leftOut > 0) kept.addFirst("[${if (leftOut == 1) "An earlier message" else "$leftOut earlier messages"} left out: $leftChars characters.]")
        return kept.joinToString("\n\n")
    }

    /** Where an older message of a conversation was cut to fit. */
    private const val CUT_SHORT = "\n[…]"

    /**
     * The tasks a read ends with, newest conversation first: each named, dated and triaged as the
     * rules read it, with its text from [bodies] (by [Conversation.version]), or marked to be
     * fetched where there's none.
     */
    fun fetched(conversations: List<Conversation>, bodies: Map<String, Body>, now: Long, zone: ZoneId): List<Fetched> =
        conversations.sortedByDescending { it.receivedAt }.map { conversation ->
            val body = bodies[conversation.version]
            val text = body?.text.orEmpty()
            val subject = conversation.subject.ifBlank { "(no subject)" }
            val triage = EmailRules.triage(EmailRules.Email(conversation.fromName, conversation.fromAddress, subject, text, conversation.sent), now, zone)
            val from = listOfNotNull(conversation.fromName, conversation.fromAddress?.let { "<$it>" }).joinToString(" ")
            Fetched(
                sourceId = conversation.threadId,
                title = subject,
                kind = triage.kind,
                detail = text,
                dueAt = triage.dueAt,
                availableFrom = triage.availableFrom,
                sourceEffortMin = triage.effortMin,
                extra = buildMap {
                    put(EXTRA_MESSAGE_ID, conversation.messageId)
                    put(EXTRA_NEXT_STEP, triage.nextStep)
                    put(EXTRA_MESSAGES, conversation.uids.size.toString())
                    put(EXTRA_RECEIVED, conversation.receivedAt.toString())
                    put(EXTRA_UIDS, conversation.uids.joinToString(","))
                    conversation.uidValidity?.let { put(EXTRA_UID_VALIDITY, it.toString()) }
                    if (conversation.sent) put(EXTRA_SENT, "true")
                    if (from.isNotEmpty()) put(EXTRA_FROM, from)
                    conversation.fromName?.let { put(EXTRA_FROM_NAME, it) }
                    conversation.fromAddress?.let { put(EXTRA_FROM_ADDRESS, it) }
                    if (body == null) {
                        put(EXTRA_TEXT_PENDING, "true")
                    } else {
                        put(EXTRA_TEXT_READ_TO, body.readTo.toString())
                        put(EXTRA_TEXT_RULES, body.rules.toString())
                    }
                    body?.attachments?.takeIf { it.isNotEmpty() }?.let { put(EXTRA_ATTACHMENTS, json.encodeToString(ATTACHMENTS, it)) }
                },
            )
        }

    /**
     * The text already stored for each conversation, by which messages it's of ([Conversation.version]):
     * read again only when they change (a reply, starred or not), or when it was cut shorter than
     * reads now go ([Body.whole]).
     */
    fun knownBodies(known: List<TaskItem>): Map<String, Body> =
        known.filter { it.source == Source.Gmail && EXTRA_TEXT_PENDING !in it.extra && EXTRA_UIDS in it.extra }
            .associate { task -> "${task.extra[EXTRA_UID_VALIDITY].orEmpty()}:${task.extra.getValue(EXTRA_UIDS)}" to bodyOf(task) }

    /**
     * Whose texts a read fetches: the conversations whose text isn't stored, or was cut shorter than
     * reads now go, newest first, each whole, as many as come to [max] messages (one always). The
     * rest wait for the reads after, a cut text staying as it is meanwhile.
     */
    fun toRead(conversations: List<Conversation>, known: Map<String, Body>, max: Int): List<Conversation> {
        val out = mutableListOf<Conversation>()
        var messages = 0
        for (conversation in conversations.sortedByDescending { it.receivedAt }) {
            if (known[conversation.version]?.whole == true) continue
            if (out.isNotEmpty() && messages + conversation.uids.size > max) break
            out += conversation
            messages += conversation.uids.size
        }
        return out
    }

    /**
     * The texts a read ends with: those [read] now, by [Conversation.version], over the [known] ones.
     * One that couldn't be read (null: a message of it gone between its listing and its fetch)
     * keeps the text it had, still marked as read only so far, so a later read tries again; a new
     * one is left to be fetched.
     */
    fun withRead(known: Map<String, Body>, read: Map<String, Body?>): Map<String, Body> =
        known + read.mapNotNull { (version, body) -> body?.let { version to it } }

    private val INTERNAL_DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH)

    /** One FETCH response, or null if it lacks what a task needs. */
    fun message(attributes: Map<String, ImapValue>): MailMessage? {
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
        return MailMessage(
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
 * Gmail over IMAP with an app password (docs/data-sources.md §4), read-only: see [ImapClient].
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
                    val attachments = mutableListOf<Mime.Attachment>()
                    // What the oldest message quotes came before the conversation reached you, so
                    // it's read whole; the later ones' quotes are of messages shown above them (DX-06).
                    val oldest = GmailThreads.oldest(conversation.uids.map(envelopes::getValue))
                    for (uid in conversation.uids) {
                        val now = System.nanoTime()
                        if (now > lastBy) break@conversations
                        socket.soTimeout = ((lastBy - now) / 1_000_000).toInt().coerceIn(1, TIMEOUT_MS)
                        val (text, attached) = try {
                            textAndAttachments(imap, envelopes.getValue(uid), quotesCut = uid != oldest.uid)
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
                    }
                    read[conversation.version] = GmailThreads.Body(GmailThreads.conversationText(texts, context.zone), attachments = attachments.distinct())
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
     * the mailbox since it was listed); and its attachments, from the same structure (DX-01). The
     * part is fetched whole, up to [MAX_PART_BYTES]: HTML's text can come after 50 KB of markup and
     * styles (Warwick's Open Day email's did).
     */
    private fun textAndAttachments(imap: ImapClient, message: MailMessage, quotesCut: Boolean): Pair<String?, List<Mime.Attachment>> {
        val structure = imap.uidFetch(listOf(message.uid), "UID BODYSTRUCTURE").firstOrNull()?.get("BODYSTRUCTURE") ?: return null to emptyList()
        val attachments = Mime.attachments(structure)
        return text(imap, message, structure, quotesCut) to attachments
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
