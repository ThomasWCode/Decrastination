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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up messages in the shapes Gmail's IMAP gave on 8 Oct (docs/phase0-findings.md §4): the inbox isn't committed. */
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
    fun `notifications are read and archived`() {
        assertEquals(Kind.Info, email("I want to connect", from = "invitations@linkedin.com").kind)
        assertEquals(Kind.Info, email("Your photos are online", from = "info@sportograf.com").kind)
        assertEquals(Kind.Info, email("Offers", body = "To unsubscribe click here").kind)
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

    @Test
    fun `a conversation is one task, named by its newest message`() {
        val messages = listOf(
            InboxMessage(1, "m1", "t1", 100, "Plans", "Dad", "dad@example.com", sent = false),
            InboxMessage(2, "m2", "t1", 200, "Re: Plans", "Tom", "tom@example.com", sent = true),
            InboxMessage(3, "m3", "t2", 150, "Ticket", "Shop", "noreply@shop.example", sent = false),
        )
        val tasks = GmailThreads.fetched(messages, mapOf("m2" to GmailThreads.Body("Sounds good")), now, LONDON)
        assertEquals(listOf("t1", "t2"), tasks.map { it.sourceId })
        val thread = tasks.first()
        assertEquals("Re: Plans", thread.title)
        assertEquals("Sounds good", thread.detail)
        assertEquals("m2", thread.extra[GmailThreads.EXTRA_MESSAGE_ID])
        assertEquals("2", thread.extra[GmailThreads.EXTRA_MESSAGES])
        assertEquals("Tom <tom@example.com>", thread.extra[GmailThreads.EXTRA_FROM])
        assertEquals(Kind.Admin, thread.kind)
    }

    @Test
    fun `stored text is reused while a conversation's newest message is the same`() {
        val message = InboxMessage(1, "m1", "t1", 1, "Hi", null, "a@b.c", false)
        val tasks = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(message), mapOf("m1" to GmailThreads.Body("Body")), now, LONDON), now).tasks
        assertEquals(mapOf("m1" to GmailThreads.Body("Body")), GmailThreads.knownBodies(tasks))
        assertEquals(emptyList(), GmailThreads.toRead(listOf(message), GmailThreads.knownBodies(tasks), 60))
    }

    @Test
    fun `text cut shorter than reads now go is read again, once`() {
        val message = InboxMessage(1, "m1", "t1", 1, "Hi", null, "a@b.c", false)
        fun stored(text: String, readTo: Int?) = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(message), mapOf("m1" to GmailThreads.Body(text)), now, LONDON), now).tasks
            .map { task -> task.copy(extra = if (readTo == null) task.extra - GmailThreads.EXTRA_TEXT_READ_TO else task.extra + (GmailThreads.EXTRA_TEXT_READ_TO to "$readTo")) }
        fun read(tasks: List<TaskItem>) = GmailThreads.toRead(listOf(message), GmailThreads.knownBodies(tasks), 60)
        // Stored before how far it was read was kept: read again, however long. Those reads stopped
        // at 4 000 characters, or sooner at 32 KB of a part.
        assertEquals(listOf(message), read(stored("x".repeat(4_000), readTo = null)))
        assertEquals(listOf(message), read(stored("x".repeat(100), readTo = null)))
        // Read as far as reads go now, cut or not, it stays; cut by a lower limit, it's read again,
        // but not if it stopped short of that limit: then it was all there was.
        assertEquals(emptyList(), read(stored("x".repeat(GmailThreads.MAX_BODY_CHARS), readTo = GmailThreads.MAX_BODY_CHARS)))
        assertEquals(listOf(message), read(stored("x".repeat(10_000), readTo = 10_000)))
        assertEquals(emptyList(), read(stored("x".repeat(5_000), readTo = 10_000)))
        // Read again, it's marked as read now.
        val again = GmailThreads.fetched(listOf(message), mapOf("m1" to GmailThreads.Body("x".repeat(5_000))), now, LONDON).single()
        assertEquals("${GmailThreads.MAX_BODY_CHARS}", again.extra[GmailThreads.EXTRA_TEXT_READ_TO])
    }

    @Test
    fun `a cut text waiting for a read with room keeps what it has`() {
        val messages = listOf(
            InboxMessage(1, "m1", "t1", 2, "New", null, "a@b.c", false),
            InboxMessage(2, "m2", "t2", 1, "Cut", null, "a@b.c", false),
        )
        val known = mapOf("m2" to GmailThreads.Body("x".repeat(4_000), readTo = 4_000))
        // A read with room for one: the newest conversation's text, never read, goes first.
        assertEquals(listOf("m1"), GmailThreads.toRead(messages, known, 1).map { it.messageId })
        // The cut one keeps its text, still marked as cut, so a later read takes it.
        val cut = GmailThreads.fetched(messages, known, now, LONDON).single { it.sourceId == "t2" }
        assertEquals("x".repeat(4_000), cut.detail)
        assertEquals("4000", cut.extra[GmailThreads.EXTRA_TEXT_READ_TO])
        assertNull(cut.extra[GmailThreads.EXTRA_TEXT_PENDING])
    }

    @Test
    fun `a text that couldn't be read again keeps what it had, and a new one waits`() {
        val known = mapOf("m1" to GmailThreads.Body("Old text", readTo = 0))
        // Gone between its listing and its fetch: as it was, still to be read again.
        assertEquals(known, GmailThreads.withRead(known, mapOf("m1" to null)))
        // No text in it: that's all there is.
        assertEquals(GmailThreads.Body(""), GmailThreads.withRead(known, mapOf("m1" to ""))["m1"])
        assertEquals(GmailThreads.Body("New text"), GmailThreads.withRead(known, mapOf("m1" to "New text"))["m1"])
        // A new message that couldn't be read is left to be fetched.
        assertNull(GmailThreads.withRead(known, mapOf("m2" to null))["m2"])
    }

    @Test
    fun `an email is asked about only once its text is read as far as reads go`() {
        val message = InboxMessage(1, "m1", "t1", 1, "Hi", null, "a@b.c", false)
        val task = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(message), mapOf("m1" to GmailThreads.Body("x".repeat(100))), now, LONDON), now).tasks.single()
        assertTrue(GmailThreads.textRead(task))
        // Stored before how far it was read was kept, or cut by a lower limit: not yet.
        assertEquals(false, GmailThreads.textRead(task.copy(extra = task.extra - GmailThreads.EXTRA_TEXT_READ_TO)))
        assertEquals(false, GmailThreads.textRead(task.copy(detail = "x".repeat(4_000), extra = task.extra + (GmailThreads.EXTRA_TEXT_READ_TO to "4000"))))
        // Nor while its text is still to be fetched.
        assertEquals(false, GmailThreads.textRead(Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(message), emptyMap(), now, LONDON), now).tasks.single()))
    }

    @Test
    fun `a message's text is kept past what the model is shown, so it's told when there's more`() {
        assertTrue(GmailThreads.MAX_BODY_CHARS > Prompts.MAX_TEXT)
    }

    @Test
    fun `a conversation whose text wasn't fetched yet is fetched next time`() {
        val tasks = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(InboxMessage(1, "m1", "t1", 1, "Hi", null, "a@b.c", false)), emptyMap(), now, LONDON), now).tasks
        assertEquals("true", tasks.single().extra[GmailThreads.EXTRA_TEXT_PENDING])
        assertEquals(emptyMap(), GmailThreads.knownBodies(tasks))
    }

    @Test
    fun `a message without Gmail's ids is skipped`() {
        assertNull(GmailThreads.message(mapOf("UID" to ImapValue.Atom("1"))))
    }
}
