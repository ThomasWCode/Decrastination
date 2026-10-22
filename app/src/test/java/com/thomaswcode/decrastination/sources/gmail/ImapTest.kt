package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.SourceUnavailable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ImapTest {

    private fun reader(text: String) = ImapReader(ByteArrayInputStream(text.replace("\n", "\r\n").toByteArray()))

    @Test
    fun `status lines keep their text whole`() {
        val line = reader("* OK [CAPABILITY IMAP4rev1 X-GM-EXT-1] Gimap ready (unbalanced \"\n").readLine()
        assertEquals("*", line.tag)
        assertEquals("OK", line.status)
        assertEquals("[CAPABILITY IMAP4rev1 X-GM-EXT-1] Gimap ready (unbalanced \"", line.text)
    }

    @Test
    fun `numbered and search responses are values`() {
        val read = reader("* 24 EXISTS\n* SEARCH 3 17 21919\n")
        assertEquals(listOf("24", "EXISTS"), read.readLine().values.map { it.string })
        assertEquals(listOf("SEARCH", "3", "17", "21919"), read.readLine().values.map { it.string })
    }

    @Test
    fun `lists nest, quoted strings unescape, NIL is nil`() {
        val line = reader("* 1 FETCH (UID 7 X-GM-LABELS (\"\\\\Important\" \"\\\\Sent\") ENVELOPE (NIL \"Say \\\"hi\\\"\" ((\"Me\" NIL \"me\" \"example.com\"))))\n").readLine()
        val pairs = line.values[2].list
        assertEquals("UID", pairs[0].string)
        assertEquals(listOf("\\Important", "\\Sent"), pairs[3].list.map { it.string })
        val envelope = pairs[5].list
        assertEquals(ImapValue.Nil, envelope[0])
        assertEquals("Say \"hi\"", envelope[1].string)
        assertEquals("example.com", envelope[2].list[0].list[3].string)
    }

    @Test
    fun `literals hold anything, line breaks included, and the line carries on after`() {
        val body = "Line one\r\n(not a list\r\n"
        val text = "* 2 FETCH (UID 9 BODY[1]<0> {${body.length}}\r\n$body UID2 x)\r\n"
        val line = ImapReader(ByteArrayInputStream(text.toByteArray())).readLine()
        val pairs = line.values[2].list
        assertEquals("BODY[1]<0>", pairs[2].string)
        assertEquals(body, pairs[3].string)
        assertEquals("UID2", pairs[4].string)
    }

    @Test
    fun `a section spec with spaces is one atom`() {
        val line = reader("* 3 FETCH (BODY[HEADER.FIELDS (FROM SUBJECT)] \"x\")\n").readLine()
        assertEquals("BODY[HEADER.FIELDS (FROM SUBJECT)]", line.values[2].list[0].string)
    }

    @Test
    fun `a closed connection is an error, not an empty answer`() {
        assertFailsWith<EOFException> { reader("* 1 FETCH (UID").readLine() }
    }

    /** A scripted server: what it answers, and what the client sent. */
    private class Script(answers: String) {
        val sent = ByteArrayOutputStream()
        val client = ImapClient(ByteArrayInputStream(answers.replace("\n", "\r\n").toByteArray()), sent)
        val commands: List<String> get() = sent.toString().trimEnd().split("\r\n")
    }

    @Test
    fun `a session reads the inbox without changing it`() {
        val script = Script(
            """
            * OK Gimap ready
            d1 OK me@example.com authenticated (Success)
            * FLAGS (\Answered \Seen)
            * 2 EXISTS
            * 0 RECENT
            d2 OK [READ-ONLY] INBOX selected. (Success)
            * SEARCH 5 9
            d3 OK SEARCH completed (Success)
            * 1 FETCH (UID 5 X-GM-MSGID 100 X-GM-THRID 100)
            * 2 FETCH (UID 9 X-GM-MSGID 101 X-GM-THRID 100)
            d4 OK Success
            * BYE LOGOUT Requested
            d5 OK 73 good day (Success)
            """.trimIndent() + "\n",
        )
        val imap = script.client
        imap.greeting()
        imap.login("me@example.com", "pa\"ss")
        assertEquals(2, imap.examine("INBOX").exists)
        assertEquals(listOf(5L, 9L), imap.uidSearch("ALL"))
        val fetched = imap.uidFetch(listOf(5, 9), "UID X-GM-MSGID X-GM-THRID")
        assertEquals(listOf("101"), fetched.drop(1).map { it["X-GM-MSGID"]?.string })
        imap.logout()
        assertEquals(
            listOf(
                "d1 LOGIN \"me@example.com\" \"pa\\\"ss\"",
                "d2 EXAMINE \"INBOX\"",
                "d3 UID SEARCH ALL",
                "d4 UID FETCH 5,9 (UID X-GM-MSGID X-GM-THRID)",
                "d5 LOGOUT",
            ),
            script.commands,
        )
    }

    @Test
    fun `mailboxes are listed with their special uses, whatever they're called`() {
        val script = Script(
            """
            * LIST (\HasNoChildren) "/" "INBOX"
            * LIST (\HasChildren \Noselect) "/" "[Gmail]"
            * LIST (\All \HasNoChildren) "/" "[Gmail]/All Mail"
            * LIST (\HasNoChildren \Trash) "/" "[Gmail]/Bin"
            * LIST (\Flagged \HasNoChildren) "/" "[Gmail]/Starred"
            d1 OK Success
            """.trimIndent() + "\n",
        )
        val boxes = script.client.list()
        assertEquals(listOf("INBOX", "[Gmail]", "[Gmail]/All Mail", "[Gmail]/Bin", "[Gmail]/Starred"), boxes.map { it.name })
        assertEquals(setOf("\\all", "\\hasnochildren"), boxes[2].attributes)
        assertEquals("[Gmail]/Starred", boxes.single { "\\flagged" in it.attributes }.name)
        assertEquals(listOf("d1 LIST \"\" \"*\""), script.commands)
    }

    @Test
    fun `examining a mailbox says how many it holds and the validity of its UIDs`() {
        val script = Script(
            """
            * FLAGS (\Answered \Flagged \Draft \Deleted \Seen)
            * OK [PERMANENTFLAGS ()] Flags permitted.
            * OK [UIDVALIDITY 11] UIDs valid.
            * 24 EXISTS
            * 0 RECENT
            * OK [UIDNEXT 70400] Predicted next UID.
            d1 OK [READ-ONLY] [Gmail]/All Mail selected. (Success)
            """.trimIndent() + "\n",
        )
        assertEquals(Examined(24, 11), script.client.examine("[Gmail]/All Mail"))
    }

    @Test
    fun `the login's address and password aren't logged`() {
        val logged = mutableListOf<String>()
        val client = ImapClient(ByteArrayInputStream("d1 OK me@example.com authenticated (Success)\r\n".toByteArray()), ByteArrayOutputStream()) { logged += it }
        client.login("me@example.com", "secret")
        assertEquals(listOf("d1 LOGIN"), logged)
    }

    /** All Mail listed, examined under validity 11, and the starred message [starred] searched for, then its conversation's [uids]. */
    private fun listing(starred: String, uids: String, more: String = "") = Script(
        """
        * LIST (\All \HasNoChildren) "/" "[Gmail]/All Mail"
        * LIST (\Flagged \HasNoChildren) "/" "[Gmail]/Starred"
        d1 OK Success
        * OK [UIDVALIDITY 11] UIDs valid.
        * 3 EXISTS
        d2 OK [READ-ONLY] [Gmail]/All Mail selected. (Success)
        * SEARCH $starred
        d3 OK SEARCH completed (Success)
        * SEARCH $uids
        d4 OK SEARCH completed (Success)
        """.trimIndent() + "\n" + more,
    )

    private fun envelope(uid: Long, at: String, name: String, sent: Boolean = false) =
        """* $uid FETCH (UID $uid X-GM-MSGID ${1000 + uid} X-GM-THRID 100 X-GM-LABELS (${if (sent) "\"\\\\Sent\"" else ""}) INTERNALDATE "$at" """ +
            """ENVELOPE ("Thu, 8 Oct 2026" "Trip" (("$name" NIL "${name.lowercase()}" "example.com")) NIL NIL NIL NIL NIL NIL "<$uid@x>"))"""

    /** The conversation of [uids] as a read leaves it stored: under validity 11, its text read. */
    private fun storedConversation(vararg uids: Long): TaskItem {
        val messages = uids.map { MailMessage(it, "${1000 + it}", "100", it, "Trip", "Hughes", "hughes@example.com", sent = false) }
        val conversation = GmailThreads.conversation(messages, 11)
        val fetched = GmailThreads.fetched(listOf(conversation), mapOf(conversation.version to GmailThreads.Body("Bring the form.")), 0, ZoneId.of("Europe/London"))
        return Merge.apply(emptyList(), Source.Gmail, fetched, 0).tasks.single()
    }

    @Test
    fun `a listing with nothing new fetches no envelope`() {
        val script = listing(starred = "9", uids = "5 9")
        val listed = StarredConversations.list(script.client, listOf(storedConversation(5, 9)))
        assertEquals(listOf(listOf(5L, 9L)), listed.conversations.map { it.uids })
        assertEquals(
            listOf("d1 LIST \"\" \"*\"", "d2 EXAMINE \"[Gmail]/All Mail\"", "d3 UID SEARCH FLAGGED", "d4 UID SEARCH NOT DRAFT X-GM-THRID 100"),
            script.commands,
        )
    }

    @Test
    fun `a reply nobody starred brings its conversation's envelopes, to be read whole`() {
        val script = listing(
            starred = "9",
            uids = "5 9 12",
            more = envelope(12, "08-Oct-2026 19:10:00 +0100", "Tom", sent = true) + "\nd5 OK Success\n" +
                envelope(5, "07-Oct-2026 09:00:00 +0100", "Hughes") + "\n" + envelope(9, "08-Oct-2026 18:02:00 +0100", "Hughes") + "\nd6 OK Success\n",
        )
        val listed = StarredConversations.list(script.client, listOf(storedConversation(5, 9)))
        val conversation = listed.conversations.single()
        assertEquals(listOf(5L, 9L, 12L), conversation.uids)
        // The reply's yours: the conversation is still with Mr Hughes.
        assertEquals("hughes@example.com", conversation.fromAddress)
        assertEquals(listOf("d5 UID FETCH 12 (${StarredConversations.ENVELOPE})", "d6 UID FETCH 5,9 (${StarredConversations.ENVELOPE})"), script.commands.drop(4))
    }

    @Test
    fun `no All Mail, no read`() {
        val script = Script("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\nd1 OK Success\n")
        assertFailsWith<SourceUnavailable> { StarredConversations.list(script.client, emptyList()) }
    }

    @Test
    fun `a refused command throws`() {
        val script = Script("* OK Gimap ready\nd1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\n")
        script.client.greeting()
        val error = assertFailsWith<ImapClient.CommandFailed> { script.client.login("me", "wrong") }
        assertIs<IOException>(error)
        assertEquals("LOGIN: NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)", error.message)
    }

    @Test
    fun `line breaks can't be smuggled into a command`() {
        assertFailsWith<IllegalArgumentException> { ImapClient.quote("x\r\nd9 DELETE INBOX") }
    }
}
