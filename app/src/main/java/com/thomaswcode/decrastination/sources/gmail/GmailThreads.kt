package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Invite
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Your starred emails as tasks, one per conversation (docs/spec/sources.md). A conversation is
 * a task while any message of it is starred, and every message of it is read, starred or not, yours
 * included (D-10): the star is the decision, and the rest of the conversation is what
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
     * A message's text is cut to this: past what the model is shown (`ReadingPrompts.MAX_TEXT`), so it's
     * told when there's more, and the rules see as far as a long email's footer.
     */
    const val MAX_BODY_CHARS = 20_000

    /**
     * A conversation of several messages is kept within this, what the model is shown
     * (`ReadingPrompts.MAX_TEXT`), so its newest message is never what's cut: the newest messages go in
     * whole first, back to the oldest.
     */
    const val MAX_CONVERSATION_CHARS = 16_000

    /**
     * Said at the end of a message whose quotes of earlier messages, or signature, were cut (DX-06):
     * the conversation's own earlier messages are in it anyway, and what else was quoted isn't new.
     */
    const val CUT_QUOTED = "[Quoted text or a signature left out: %d characters.]"

    /**
     * The most of a conversation's messages a read takes, its newest: a conversation too long to
     * read whole within a sync's time would otherwise be started again at every sync, never
     * finished, and keep every other from being read. The text keeps its newest messages first
     * anyway; those not read are said to be left out.
     */
    const val MAX_MESSAGES_READ = 20

    /** The least of an older message worth keeping when it's cut to fit; with less room, it's left out. */
    private const val LEAST_CUT = 400

    /** Kept for the line at the top of a conversation saying how many earlier messages were left out. */
    private const val LEFT_OUT_ROOM = 80

    /**
     * Its messages' attachments (DX-01), every message's, each once: a JSON list of [Mime.Attachment],
     * which reads back whole whatever a file is called. A Teams assignment's reference materials are
     * kept so too (DX-08), so the sheet and the model see them alike.
     */
    const val EXTRA_ATTACHMENTS = Enrichments.EXTRA_ATTACHMENTS

    private val json = Json { ignoreUnknownKeys = true }
    private val ATTACHMENTS = ListSerializer(Mime.Attachment.serializer())

    /**
     * Which rules a stored text was made by ([EXTRA_TEXT_RULES]): raised whenever what's kept of a
     * message changes (what's cut, how a conversation is put together), so a text made by older
     * ones is read again, once, as one cut shorter than reads now go is. 1: review v2's (whole
     * conversations; the later messages' quotes cut, the oldest read whole; attachments named).
     * 2: calendar invites read (DX-02), so those starred already are found.
     */
    const val TEXT_RULES = 2
    const val EXTRA_TEXT_RULES = "textRules"

    /**
     * The extras that hold its text, or say which messages and how far it was read: let go with the
     * text once it's long finished ([com.thomaswcode.decrastination.core.trimmed], C-Q3), so a read of
     * it listed again fetches it afresh.
     */
    val TEXT_EXTRAS = setOf(EXTRA_MESSAGES, EXTRA_UIDS, EXTRA_UID_VALIDITY, EXTRA_TEXT_PENDING, EXTRA_TEXT_READ_TO, EXTRA_TEXT_RULES, EXTRA_ATTACHMENTS)

    /** A conversation's text, how far it was read ([EXTRA_TEXT_READ_TO]), what's attached to it, by which [rules], and its [invite] (DX-02). */
    data class Body(val text: String, val readTo: Int = MAX_BODY_CHARS, val attachments: List<Mime.Attachment> = emptyList(), val rules: Int = TEXT_RULES, val invite: Invite? = null) {
        /** All there is, or as much as a read takes now, made as reads make it now: reading it again would add nothing. */
        val whole: Boolean get() = (text.length < readTo || readTo >= MAX_BODY_CHARS) && rules >= TEXT_RULES
    }

    /** [task]'s stored text, how far it was read, and by which rules: stored before those were kept, by none. */
    fun bodyOf(task: TaskItem): Body =
        Body(task.detail, task.extra[EXTRA_TEXT_READ_TO]?.toIntOrNull() ?: 0, attachmentsOf(task), task.extra[EXTRA_TEXT_RULES]?.toIntOrNull() ?: 0, task.extra[Invite.EXTRA]?.let(Invite::read))

    /** [files] as [EXTRA_ATTACHMENTS] keeps them. */
    fun attachmentsExtra(files: List<Mime.Attachment>): String = json.encodeToString(ATTACHMENTS, files)

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

    /** Which of a conversation's [messages] a read takes: its newest [MAX_MESSAGES_READ], oldest first. */
    fun reading(messages: List<MailMessage>): List<MailMessage> = messages.sortedWith(NEWEST).takeLast(MAX_MESSAGES_READ)

    /**
     * A conversation's attachments, from each message's ([perMessage], oldest first): a file a later
     * message attaches again (a reply quoting it, a forward) is listed once, by its name, type and
     * size; two alike in one message are two files, and both stay.
     */
    fun attachments(perMessage: List<List<Mime.Attachment>>): List<Mime.Attachment> {
        val out = mutableListOf<Mime.Attachment>()
        val earlier = HashSet<Mime.Attachment>()
        for (files in perMessage) {
            out += files.filterNot { it in earlier }
            earlier += files
        }
        return out
    }

    /**
     * A conversation's invite (DX-02), from each message's ([perMessage], oldest first, null where it
     * has none): the newest's, an update moving it; none where that calls it off.
     */
    fun invite(perMessage: List<Invite?>): Invite? = perMessage.lastOrNull { it != null }?.takeUnless { it.cancelled }

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
            // The email's own subject, as the source last gave it: not a rename of yours or Felix's laid over it (I-01).
            subject = task.sourceValues?.title ?: task.title,
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
    fun conversationText(messages: List<Pair<MailMessage, String>>, zone: ZoneId, notRead: Int = 0): String {
        val ordered = messages.sortedWith(compareBy(NEWEST) { it.first })
        if (ordered.size == 1 && notRead == 0) return ordered.single().second
        val parts = ordered.map { (message, text) ->
            val from = if (message.sent) "you" else listOfNotNull(message.fromName, message.fromAddress?.let { "<$it>" }).joinToString(" ").ifEmpty { "someone" }
            "--- From $from, ${Instant.ofEpochMilli(message.receivedAt).atZone(zone).format(HEADER_TIME)} ---\n" + text.trim()
        }
        val kept = ArrayDeque<String>()
        // Room for the line saying what's left out, and a blank line between messages.
        var room = MAX_CONVERSATION_CHARS - LEFT_OUT_ROOM
        var leftOut = 0
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
                else -> leftOut = maxOf(leftOut, 0) + 1
            }
        }
        // The older messages a read didn't take ([notRead]) are left out too.
        val out = maxOf(leftOut, 0) + notRead
        if (out > 0) kept.addFirst("[${if (out == 1) "An earlier message" else "$out earlier messages"} left out.]")
        return kept.joinToString("\n\n")
    }

    /** Where an older message of a conversation was cut to fit. */
    private const val CUT_SHORT = "\n[…]"

    /**
     * The tasks a read ends with, newest conversation first: each named, dated and triaged as the
     * rules read it, with its text from [bodies] (by [Conversation.version]), or marked to be
     * fetched where there's none. One with an invite is due at its start (DX-02).
     */
    fun fetched(conversations: List<Conversation>, bodies: Map<String, Body>, zone: ZoneId): List<Fetched> =
        conversations.sortedByDescending { it.receivedAt }.map { conversation ->
            val body = bodies[conversation.version]
            val text = body?.text.orEmpty()
            val subject = conversation.subject.ifBlank { "(no subject)" }
            // Read as of the day it came, not today: its dates are then of the email alone, so the deadline it quotes stays
            // once it's passed (shown missed, readings.md) and an unchanged email's reading stays its own (I-02).
            val triage = EmailRules.triage(EmailRules.Email(conversation.fromName, conversation.fromAddress, subject, text, conversation.sent), conversation.receivedAt, zone)
            val from = listOfNotNull(conversation.fromName, conversation.fromAddress?.let { "<$it>" }).joinToString(" ")
            val invite = body?.invite
            Fetched(
                sourceId = conversation.threadId,
                title = subject,
                // Starred, a task: an invite's time is when it's due, and Add to calendar on its sheet.
                kind = triage.kind,
                detail = text,
                dueAt = invite?.start ?: triage.dueAt,
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
                    body?.attachments?.takeIf { it.isNotEmpty() }?.let { put(EXTRA_ATTACHMENTS, attachmentsExtra(it)) }
                    invite?.let { put(Invite.EXTRA, it.stored) }
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
            val reads = minOf(conversation.uids.size, MAX_MESSAGES_READ)
            if (out.isNotEmpty() && messages + reads > max) break
            out += conversation
            messages += reads
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
