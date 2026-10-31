package com.thomaswcode.decrastination.sources.gmail

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** A finished email's writes over a scripted server (docs/spec/sources.md §Gmail), and the reader with none of them. */
class ImapWriterTest {
    /** A scripted server: what it answers, and what was sent. */
    private class Script(answers: String) {
        val sent = ByteArrayOutputStream()
        val client = ImapClient(ByteArrayInputStream(answers.replace("\n", "\r\n").toByteArray()), sent)
        val writer = ImapWriter(client)
        val commands: List<String> get() = sent.toString().trimEnd().split("\r\n")
    }

    @Test
    fun `All Mail opened for writing, read, then unstarred and archived silently, and logged out`() {
        val script = Script(
            """
            * FLAGS (\Answered \Flagged \Draft \Deleted \Seen)
            * OK [UIDVALIDITY 11] UIDs valid.
            * 24 EXISTS
            d1 OK [READ-WRITE] [Gmail]/All Mail selected. (Success)
            * 1 FETCH (X-GM-MSGID 101 X-GM-LABELS (\Inbox \Important) UID 11 FLAGS (\Seen \Flagged))
            * 2 FETCH (X-GM-MSGID 102 X-GM-LABELS () UID 12 FLAGS (\Seen))
            d2 OK Success
            d3 OK Success
            d4 OK Success
            * BYE LOGOUT Requested
            d5 OK 73 good day (Success)
            """.trimIndent() + "\n",
        )
        assertEquals(Examined(24, 11), script.writer.select("[Gmail]/All Mail"))
        assertEquals(2, script.client.uidFetch(listOf(11L, 12L), "X-GM-MSGID FLAGS X-GM-LABELS").size)
        assertEquals(emptyList(), script.writer.uidStore(listOf(11L), ImapWriter.UNSTAR))
        assertEquals(emptyList(), script.writer.uidStore(listOf(11L), ImapWriter.ARCHIVE))
        script.client.logout()
        assertEquals(
            listOf(
                "d1 SELECT \"[Gmail]/All Mail\"",
                "d2 UID FETCH 11,12 (X-GM-MSGID FLAGS X-GM-LABELS)",
                "d3 UID STORE 11 -FLAGS.SILENT (\\Flagged)",
                "d4 UID STORE 11 -X-GM-LABELS.SILENT (\\Inbox)",
                "d5 LOGOUT",
            ),
            script.commands,
        )
    }

    @Test
    fun `a message gone since doesn't stop the rest, each stored alone where the set is refused`() {
        val script = Script(
            """
            d1 NO Some messages could not be stored
            d2 OK Success
            d3 NO No such message
            """.trimIndent() + "\n",
        )
        assertEquals(listOf(12L), script.writer.uidStore(listOf(11L, 12L), ImapWriter.STAR))
        assertEquals(listOf("d1 UID STORE 11,12 +FLAGS.SILENT (\\Flagged)", "d2 UID STORE 11 +FLAGS.SILENT (\\Flagged)", "d3 UID STORE 12 +FLAGS.SILENT (\\Flagged)"), script.commands)
        // Nothing to store, nothing sent.
        assertEquals(emptyList(), Script("").writer.uidStore(emptyList(), ImapWriter.INBOX))
    }

    @Test
    fun `a store not understood, or the connection going, fails the write`() {
        assertFailsWith<ImapClient.CommandFailed> { Script("d1 BAD Could not parse command\n").writer.uidStore(listOf(11L), ImapWriter.UNSTAR) }
        assertFailsWith<ImapClient.CommandFailed> { Script("d1 NO refused\nd2 BAD Could not parse command\n").writer.uidStore(listOf(11L, 12L), ImapWriter.UNSTAR) }
    }

    @Test
    fun `the reader's own commands only read, none opening a mailbox for writing or storing anything`() {
        val source = listOf(File("src/main/kotlin"), File("engine/src/main/kotlin")).first { it.isDirectory }
            .resolve("com/thomaswcode/decrastination/sources/gmail/Imap.kt").readText()
        // No command of its own opens a mailbox for writing, or stores anything.
        assertFalse("\"SELECT" in source)
        assertFalse("STORE" in source)
    }
}
