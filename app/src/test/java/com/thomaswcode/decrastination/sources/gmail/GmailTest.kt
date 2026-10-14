package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.Prompts
import java.io.ByteArrayInputStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up messages in the shapes Gmail's IMAP gave on 8 Oct and 14 Oct (docs/phase0-findings.md §4): the mailbox isn't committed. */
class GmailTest {
    private val now = Fixtures.at("2026-10-08T23:00")

    private fun email(subject: String, from: String? = "someone@example.com", body: String = "", sent: Boolean = false) =
        EmailRules.triage(EmailRules.Email("Someone", from, subject, body, sent), now, LONDON)

    @Test
    fun `your own notes are things to deal with`() {
        val triage = email("Zip card", from = "thomas@example.com", sent = true)
        assertEquals(Kind.Admin, triage.kind)
        assertEquals(10, triage.effortMin)
    }

    @Test
    fun `a shared document is opened`() {
        assertEquals(5, email("Document shared with you: ‘Durham visit’", from = "drive-shares-dm-noreply@google.com").effortMin)
    }

    @Test
    fun `a notification you starred is read and decided, five minutes`() {
        // Starred, nothing is only to read (AI-02).
        val linkedIn = email("I want to connect", from = "invitations@linkedin.com")
        assertEquals(Kind.Admin, linkedIn.kind)
        assertEquals(5, linkedIn.effortMin)
        assertEquals("Read it and decide", linkedIn.nextStep)
        assertEquals(Kind.Admin, email("Your photos are online", from = "info@sportograf.com").kind)
        assertEquals(Kind.Admin, email("Offers", body = "To unsubscribe click here").kind)
    }

    @Test
    fun `an email that asks for something is work, whoever sent it and whatever else it says`() {
        // A registration with a date: not an event, and due by the date after "by".
        val registration = email("Registration required", from = "admissions@example.com", body = "Please complete registration by 12 October 2026.")
        assertEquals(Kind.Admin, registration.kind)
        assertEquals(Fixtures.at("2026-10-12T23:59"), registration.dueAt)
        // From a no-reply address: not a notification.
        val consent = email("Trip consent", from = "noreply@school.example", body = "Please return the consent form tomorrow.")
        assertEquals(Kind.Admin, consent.kind)
        assertNull(consent.dueAt)
        // Due by the date written after "by", not the earliest near it.
        assertEquals(Fixtures.at("2026-10-20T23:59"), email("Registration", body = "Please complete registration by 20 Oct 2026; the portal opens 12 Oct.").dueAt)
        assertEquals(Kind.Admin, email("Action required: your application", from = "updates@ucas.example").kind)
        assertEquals(Kind.Admin, email("Forms", from = "office@school.example", body = "Reply to this email by Friday 16 October with your choices.").kind)
    }

    @Test
    fun `asking for nothing, a notification is to read and decide and a booking an event`() {
        assertEquals(5, email("Your receipt", from = "noreply@shop.example", body = "Please do not reply to this email. To unsubscribe click here.").effortMin)
        assertEquals(Kind.Event, email("Your e-tickets", from = "tickets@venue.example", body = "See you on 25 October. Please do not reply.").kind)
    }

    @Test
    fun `a booking with a date ahead is an event that appears the day before`() {
        val triage = email("Essential information ahead of our Open Day", from = "opendays@warwick.ac.uk", body = "We look forward to seeing you on Saturday 10 October.")
        assertEquals(Kind.Event, triage.kind)
        assertEquals(Fixtures.at("2026-10-09T00:00"), triage.availableFrom)
        assertEquals(Fixtures.at("2026-10-12T00:00"), triage.dueAt)
    }

    @Test
    fun `an event's days are calendar days across the clocks going back`() {
        // British Summer Time ends at 02:00 on Sunday 25 Oct 2026.
        val triage = EmailRules.triage(
            EmailRules.Email("Venue", "tickets@venue.example", "Your tickets", "See you on 25 October", false),
            Fixtures.at("2026-10-20T12:00"),
            LONDON,
        )
        assertEquals(Fixtures.at("2026-10-24T00:00"), triage.availableFrom)
        assertEquals(Fixtures.at("2026-10-27T00:00"), triage.dueAt)
    }

    @Test
    fun `a booking with no date ahead is an ordinary email`() {
        assertEquals(Kind.Admin, email("Your booking", body = "Thanks for booking on 1 October.").kind)
    }

    @Test
    fun `anything else is read and decided`() {
        val triage = email("RE: Help!")
        assertEquals(Kind.Admin, triage.kind)
        assertEquals(15, triage.effortMin)
    }

    @Test
    fun `dates are read in UK forms, from today on, within a year`() {
        val today = LocalDate.of(2026, 10, 8)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 15)),
            EmailRules.futureDates("Open day Sat 10th Oct; opens 30 October 2026; NPL 1/12/2026; RAL January 15; past 1 Oct 2026", today),
        )
        // With no year: a date a few weeks gone is past, one months gone is next year's.
        assertEquals(emptyList(), EmailRules.futureDates("1 September", today))
        assertEquals(listOf(LocalDate.of(2027, 3, 1)), EmailRules.futureDates("1 March", today))
    }

    private fun fetchLine(text: String) = ImapReader(ByteArrayInputStream(text.toByteArray())).readLine()

    @Test
    fun `a fetch response becomes a message`() {
        val line = fetchLine(
            "* 1 FETCH (UID 21919 X-GM-THRID 1874801471777266741 X-GM-MSGID 1874801471777266742 " +
                "X-GM-LABELS (\"\\\\Important\" \"\\\\Sent\") INTERNALDATE \"28-Aug-2026 20:46:52 +0000\" " +
                "ENVELOPE (\"Fri, 28 Aug 2026 20:46:46 +0000\" \"=?UTF-8?Q?Caf=C3=A9?=\" ((\"Thomas White\" NIL \"Tom\" \"Example.com\")) NIL NIL NIL NIL NIL NIL \"<id@x>\"))\r\n",
        )
        val attributes = line.values[2].list.let { pairs -> (0 until pairs.size step 2).associate { pairs[it].string!!.uppercase() to pairs[it + 1] } }
        val message = GmailThreads.message(attributes)!!
        assertEquals(21919L, message.uid)
        assertEquals("1874801471777266741", message.threadId)
        assertEquals("Café", message.subject)
        assertEquals("Thomas White", message.fromName)
        assertEquals("tom@example.com", message.fromAddress)
        assertEquals(true, message.sent)
        assertEquals(java.time.Instant.parse("2026-08-28T20:46:52Z").toEpochMilli(), message.receivedAt)
    }

    private fun message(uid: Long, thread: String, at: Long, subject: String, name: String?, address: String?, sent: Boolean = false) =
        MailMessage(uid, "m$uid", thread, at, subject, name, address, sent)

    /** [messages] as one conversation, under All Mail's validity 11, its text [body] where it's read. */
    private fun read(vararg messages: MailMessage, body: String? = null): Pair<Conversation, Map<String, GmailThreads.Body>> {
        val conversation = GmailThreads.conversation(messages.toList(), 11)
        return conversation to (body?.let { mapOf(conversation.version to GmailThreads.Body(it)) } ?: emptyMap())
    }

    /** [messages] as the task a read leaves, its text [body] (null: still to be fetched). */
    private fun stored(vararg messages: MailMessage, body: String? = "Body") =
        read(*messages, body = body).let { (c, bodies) -> Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(c), bodies, now, LONDON), now).tasks.single() }

    @Test
    fun `a conversation is one task, named by its newest message, with whoever it's with`() {
        val (plans, bodies) = read(
            message(1, "t1", 100, "Plans", "Dad", "dad@example.com"),
            message(2, "t1", 200, "Re: Plans", "Tom", "tom@example.com", sent = true),
            body = "Sounds good",
        )
        val ticket = GmailThreads.conversation(listOf(message(3, "t2", 150, "Ticket", "Shop", "noreply@shop.example")), 11)
        val tasks = GmailThreads.fetched(listOf(ticket, plans), bodies, now, LONDON)
        assertEquals(listOf("t1", "t2"), tasks.map { it.sourceId })
        val thread = tasks.first()
        assertEquals("Re: Plans", thread.title)
        assertEquals("Sounds good", thread.detail)
        assertEquals("m2", thread.extra[GmailThreads.EXTRA_MESSAGE_ID])
        assertEquals("2", thread.extra[GmailThreads.EXTRA_MESSAGES])
        assertEquals("1,2", thread.extra[GmailThreads.EXTRA_UIDS])
        assertEquals("11", thread.extra[GmailThreads.EXTRA_UID_VALIDITY])
        // Your reply last doesn't make it yours: it's with your dad, and not a note to self.
        assertEquals("Dad <dad@example.com>", thread.extra[GmailThreads.EXTRA_FROM])
        assertNull(thread.extra[GmailThreads.EXTRA_SENT])
        assertEquals(Kind.Admin, thread.kind)
    }

    @Test
    fun `a conversation is a note to self only when every message is yours`() {
        val note = GmailThreads.conversation(
            listOf(message(1, "t1", 100, "Zip card", "Tom", "tom@example.com", sent = true), message(2, "t1", 200, "Re: Zip card", "Tom", "tom@example.com", sent = true)),
            11,
        )
        assertTrue(note.sent)
        assertEquals("tom@example.com", note.fromAddress)
        val task = GmailThreads.fetched(listOf(note), mapOf(note.version to GmailThreads.Body("Renew it")), now, LONDON).single()
        assertEquals("true", task.extra[GmailThreads.EXTRA_SENT])
        assertEquals(10, task.sourceEffortMin)
    }

    @Test
    fun `a conversation's text is every message's, oldest first, each headed by who sent it and when`() {
        val text = GmailThreads.conversationText(
            listOf(
                message(9, "t", Fixtures.at("2026-10-08T19:10"), "Re: Trip", "Tom", "tom@example.com", sent = true) to "Yes, I'll bring it.",
                message(4, "t", Fixtures.at("2026-10-08T18:02"), "Trip", "Mr Hughes", "hughes@school.example") to "Please bring the form by Friday.",
            ),
            LONDON,
        )
        assertEquals(
            "--- From Mr Hughes <hughes@school.example>, 2026-10-08 18:02 ---\nPlease bring the form by Friday.\n\n--- From you, 2026-10-08 19:10 ---\nYes, I'll bring it.",
            text,
        )
        // Its headers' times aren't dates the rules read.
        assertEquals(emptyList(), EmailRules.futureDates(text.lines().filter { it.startsWith("---") }.joinToString("\n"), LocalDate.of(2026, 10, 8)))
        // One message is its text alone.
        assertEquals("Hi", GmailThreads.conversationText(listOf(message(1, "t", 1, "Hi", null, "a@b.c") to "Hi"), LONDON))
    }

    @Test
    fun `a long conversation keeps its newest messages whole, within what the model reads`() {
        val messages = (1..6).map { i -> message(i.toLong(), "t", i * 1_000L, "Re: Long", "Dad", "dad@example.com") to "x".repeat(5_000) + " message $i" }
        val text = GmailThreads.conversationText(messages, LONDON)
        assertTrue(text.length <= GmailThreads.MAX_CONVERSATION_CHARS, "${text.length}")
        assertTrue(text.endsWith(" message 6"))
        assertTrue(" message 5" in text)
        // The one too long for what's left is cut, those before it said to be left out.
        assertTrue(text.startsWith("[2 earlier messages left out: 10020 characters.]"), text.take(80))
        assertTrue("[…]" in text)
        assertTrue(GmailThreads.MAX_CONVERSATION_CHARS <= Prompts.MAX_TEXT)
    }

    @Test
    fun `a conversation with nothing new is built from its task, as it was read`() {
        val messages = arrayOf(message(1, "t1", 100, "Plans", "Dad", "dad@example.com"), message(2, "t1", 200, "Re: Plans", "Tom", "tom@example.com", sent = true))
        val task = stored(*messages)
        assertEquals(GmailThreads.conversation(messages.toList(), 11), GmailThreads.stored(task, listOf(2, 1), 11))
        // A message more, or another validity: fetched afresh.
        assertNull(GmailThreads.stored(task, listOf(1, 2, 3), 11))
        assertNull(GmailThreads.stored(task, listOf(1, 2), 12))
    }

    @Test
    fun `stored messages are known under the validity they were read under`() {
        val task = stored(message(5, "t1", 100, "Hi", null, "a@b.c"), message(9, "t1", 200, "Re: Hi", null, "a@b.c"))
        assertEquals(mapOf(5L to task, 9L to task), GmailThreads.knownUids(listOf(task), 11))
        // All Mail's validity changed: a full read.
        assertEquals(emptyMap(), GmailThreads.knownUids(listOf(task), 12))
        assertEquals(emptyMap(), GmailThreads.knownUids(listOf(task), null))
    }

    @Test
    fun `stored text is reused while a conversation's messages are the same`() {
        val hi = message(1, "t1", 1, "Hi", null, "a@b.c")
        val (conversation, _) = read(hi)
        val known = GmailThreads.knownBodies(listOf(stored(hi)))
        assertEquals(mapOf(conversation.version to GmailThreads.Body("Body")), known)
        assertEquals(emptyList(), GmailThreads.toRead(listOf(conversation), known, 60))
        // A reply, starred or not: its text is read again, the whole conversation.
        val (replied, _) = read(hi, message(2, "t1", 2, "Re: Hi", null, "d@e.f"))
        assertEquals(listOf(replied), GmailThreads.toRead(listOf(replied), known, 60))
    }

    @Test
    fun `text cut shorter than reads now go is read again, once`() {
        val hi = message(1, "t1", 1, "Hi", null, "a@b.c")
        val (conversation, _) = read(hi)
        fun stored(text: String, readTo: Int?) = stored(hi, body = text)
            .let { task -> task.copy(extra = if (readTo == null) task.extra - GmailThreads.EXTRA_TEXT_READ_TO else task.extra + (GmailThreads.EXTRA_TEXT_READ_TO to "$readTo")) }
        fun read(task: TaskItem) = GmailThreads.toRead(listOf(conversation), GmailThreads.knownBodies(listOf(task)), 60)
        // Stored before how far it was read was kept: read again, however long. Those reads stopped
        // at 4 000 characters, or sooner at 32 KB of a part.
        assertEquals(listOf(conversation), read(stored("x".repeat(4_000), readTo = null)))
        assertEquals(listOf(conversation), read(stored("x".repeat(100), readTo = null)))
        // Read as far as reads go now, cut or not, it stays; cut by a lower limit, it's read again,
        // but not if it stopped short of that limit: then it was all there was.
        assertEquals(emptyList(), read(stored("x".repeat(GmailThreads.MAX_BODY_CHARS), readTo = GmailThreads.MAX_BODY_CHARS)))
        assertEquals(listOf(conversation), read(stored("x".repeat(10_000), readTo = 10_000)))
        assertEquals(emptyList(), read(stored("x".repeat(5_000), readTo = 10_000)))
        // Read again, it's marked as read now.
        val again = GmailThreads.fetched(listOf(conversation), mapOf(conversation.version to GmailThreads.Body("x".repeat(5_000))), now, LONDON).single()
        assertEquals("${GmailThreads.MAX_BODY_CHARS}", again.extra[GmailThreads.EXTRA_TEXT_READ_TO])
    }

    @Test
    fun `a read takes whole conversations, newest first, as many messages as it has room for`() {
        val old = GmailThreads.conversation((1L..3).map { message(it, "old", it, "Old", null, "a@b.c") }, 11)
        val new = GmailThreads.conversation((4L..5).map { message(it, "new", 100 + it, "New", null, "a@b.c") }, 11)
        assertEquals(listOf(new), GmailThreads.toRead(listOf(old, new), emptyMap(), 4))
        assertEquals(listOf(new, old), GmailThreads.toRead(listOf(old, new), emptyMap(), 5))
        // One always, however long.
        assertEquals(listOf(new), GmailThreads.toRead(listOf(old, new), emptyMap(), 1))
    }

    @Test
    fun `a cut text waiting for a read with room keeps what it has`() {
        val new = GmailThreads.conversation(listOf(message(1, "t1", 2, "New", null, "a@b.c")), 11)
        val cut = GmailThreads.conversation(listOf(message(2, "t2", 1, "Cut", null, "a@b.c")), 11)
        val known = mapOf(cut.version to GmailThreads.Body("x".repeat(4_000), readTo = 4_000))
        // A read with room for one: the newest conversation's text, never read, goes first.
        assertEquals(listOf(new), GmailThreads.toRead(listOf(new, cut), known, 1))
        // The cut one keeps its text, still marked as cut, so a later read takes it.
        val task = GmailThreads.fetched(listOf(new, cut), known, now, LONDON).single { it.sourceId == "t2" }
        assertEquals("x".repeat(4_000), task.detail)
        assertEquals("4000", task.extra[GmailThreads.EXTRA_TEXT_READ_TO])
        assertNull(task.extra[GmailThreads.EXTRA_TEXT_PENDING])
    }

    @Test
    fun `a text that couldn't be read again keeps what it had, and a new one waits`() {
        val known = mapOf("11:1" to GmailThreads.Body("Old text", readTo = 0))
        // A message of it gone between its listing and its fetch: as it was, still to be read again.
        assertEquals(known, GmailThreads.withRead(known, mapOf("11:1" to null)))
        assertEquals(GmailThreads.Body(""), GmailThreads.withRead(known, mapOf("11:1" to GmailThreads.Body("")))["11:1"])
        assertEquals(GmailThreads.Body("New text"), GmailThreads.withRead(known, mapOf("11:1" to GmailThreads.Body("New text")))["11:1"])
        // A new one that couldn't be read is left to be fetched.
        assertNull(GmailThreads.withRead(known, mapOf("11:2" to null))["11:2"])
    }

    @Test
    fun `an email is asked about only once its text is read as far as reads go`() {
        val hi = message(1, "t1", 1, "Hi", null, "a@b.c")
        val task = stored(hi, body = "x".repeat(100))
        assertTrue(GmailThreads.textRead(task))
        // Stored before how far it was read was kept, or cut by a lower limit: not yet.
        assertEquals(false, GmailThreads.textRead(task.copy(extra = task.extra - GmailThreads.EXTRA_TEXT_READ_TO)))
        assertEquals(false, GmailThreads.textRead(task.copy(detail = "x".repeat(4_000), extra = task.extra + (GmailThreads.EXTRA_TEXT_READ_TO to "4000"))))
        // Nor while its text is still to be fetched.
        assertEquals(false, GmailThreads.textRead(stored(hi, body = null)))
    }

    @Test
    fun `a message's text is kept past what the model is shown, so it's told when there's more`() {
        assertTrue(GmailThreads.MAX_BODY_CHARS > Prompts.MAX_TEXT)
    }

    @Test
    fun `a conversation whose text wasn't fetched yet is fetched next time`() {
        val task = stored(message(1, "t1", 1, "Hi", null, "a@b.c"), body = null)
        assertEquals("true", task.extra[GmailThreads.EXTRA_TEXT_PENDING])
        assertEquals(emptyMap(), GmailThreads.knownBodies(listOf(task)))
    }

    @Test
    fun `a conversation listed in the last read and absent from this one comes back completed`() {
        // Unstarred: its only starred message isn't any more, so it isn't listed.
        val task = stored(message(1, "t1", 1, "Hi", null, "a@b.c"))
        val result = Merge.apply(listOf(task), Source.Gmail, emptyList(), now + 60_000)
        assertEquals(listOf(task.id), result.completed.map { it.id })
    }

    @Test
    fun `a conversation's attachments are kept with its text, and read back`() {
        val files = listOf(Mime.Attachment("consent-form.pdf", "application/pdf", 112_639), Mime.Attachment("Trip (final).docx", "application/msword"))
        val conversation = GmailThreads.conversation(listOf(message(1, "t1", 1, "Trip", "Mr Hughes", "hughes@school.example")), 11)
        val body = GmailThreads.Body("Sign it", attachments = files)
        val task = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(conversation), mapOf(conversation.version to body), now, LONDON), now).tasks.single()
        assertEquals(files, GmailThreads.attachmentsOf(task))
        assertEquals(body, GmailThreads.knownBodies(listOf(task))[conversation.version])
        // None kept, none said.
        assertNull(stored(message(2, "t2", 1, "Hi", null, "a@b.c")).extra[GmailThreads.EXTRA_ATTACHMENTS])
    }

    @Test
    fun `the oldest message of a conversation is the first to reach you, read whole`() {
        // What it quotes came before you were in the conversation: Dad's reply quoting John's question.
        val messages = listOf(message(9, "t", 300, "Re: Help", "Tom", "tom@example.com", sent = true), message(4, "t", 100, "RE: Help!", "Dad", "dad@example.com"), message(7, "t", 200, "Re: Help", "John", "john@example.com"))
        assertEquals(4L, GmailThreads.oldest(messages).uid)
    }

    @Test
    fun `conversations are searched for in one command`() {
        assertEquals("X-GM-THRID 1", GmailThreads.anyThread(listOf("1")))
        assertEquals("OR X-GM-THRID 1 OR X-GM-THRID 2 X-GM-THRID 3", GmailThreads.anyThread(listOf("1", "2", "3")))
        assertFailsWith<IllegalArgumentException> { GmailThreads.anyThread(listOf("1 OR ALL")) }
    }

    @Test
    fun `a message without Gmail's ids is skipped`() {
        assertNull(GmailThreads.message(mapOf("UID" to ImapValue.Atom("1"))))
    }
}
