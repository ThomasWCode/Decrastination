package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        assertFailsWith<java.io.EOFException> { reader("* 1 FETCH (UID").readLine() }
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
        assertEquals(2, imap.examine("INBOX"))
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
    fun `a refused command throws`() {
        val script = Script("* OK Gimap ready\nd1 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\n")
        script.client.greeting()
        val error = assertFailsWith<ImapClient.CommandFailed> { script.client.login("me", "wrong") }
        assertIs<java.io.IOException>(error)
        assertEquals("LOGIN: NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)", error.message)
    }

    @Test
    fun `line breaks can't be smuggled into a command`() {
        assertFailsWith<IllegalArgumentException> { ImapClient.quote("x\r\nd9 DELETE INBOX") }
    }
}
