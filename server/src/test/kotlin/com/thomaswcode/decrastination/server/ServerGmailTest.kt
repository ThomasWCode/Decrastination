package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sources.gmail.GmailWriter
import com.thomaswcode.decrastination.sources.gmail.GmailWriter.Change
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.FinishGmail
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.UnfinishGmail
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Emails finished in Gmail by the server once the phone sends commands (5.15.0): a finish a phone's command journals, or one the
 * phone journaled while it mirrored, is written by the server's job by its own sign-in, and its Undo puts it back. Gmail's a
 * stand-in that keeps what it's asked.
 */
class ServerGmailTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** Gmail, faked: one conversation of one message, starred and in the inbox; what was stored. */
    private class FakeGmail : GmailWriter {
        var message = GmailWriter.Message(11, "101", starred = true, inbox = true)
        val stored = mutableListOf<Change>()

        override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T = block(object : GmailWriter.Session {
            override fun thread(threadId: String): List<Long> = listOf(message.uid)
            override fun messages(uids: List<Long>): List<GmailWriter.Message> = listOf(message)
            override fun store(uids: List<Long>, change: Change): List<Long> {
                stored += change
                message = when (change) {
                    Change.Unstar -> message.copy(starred = false)
                    Change.Star -> message.copy(starred = true)
                    Change.Archive -> message.copy(inbox = false)
                    Change.Inbox -> message.copy(inbox = true)
                }
                return emptyList()
            }
        })
    }

    private val dir: File = Files.createTempDirectory("server-gmail").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val gmail = FakeGmail()
    private val graph = ServerGraph(db, dir, Plain, "test", gmailWriter = { gmail })

    @AfterTest
    fun cleanUp() {
        runBlocking { graph.stopForATest() }
        db.close()
        dir.deleteRecursively()
    }

    private val email = TaskItem(
        id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "A made-up email", dueAt = null, kind = Kind.Admin,
        firstSeenAt = 1, lastSeenAt = 1,
    )

    private suspend fun ready() {
        graph.stores.tasks.update { it.copy(tasks = listOf(email)) }
        graph.stores.settings.update { it.copy(gmailFinishes = true, hintsSeen = it.hintsSeen + GmailWrites.OK_HINT) }
    }

    /** [action] from the phone, made [ago] ms back, applied here. */
    private suspend fun fromThePhone(id: String, action: Action, ago: Long) =
        assertEquals(Outcome.APPLIED, graph.applier.apply(CommandBatch(listOf(Command(id, System.currentTimeMillis() - ago, action))), "phone").single().state)

    @Test
    fun `a finish a phone's command journals is written by the server's job once its grace is over, and its Undo puts it back`() = runBlocking {
        ready()
        // Made past its grace on the phone, so it's due as it arrives.
        fromThePhone("c-1", FinishGmail(email.id), ago = GmailWrites.GRACE_MS + 1_000)
        assertEquals(GmailWrites.State.Going, GmailWrites.state(graph.stores.gmailWrites.value.writes, email.id))
        graph.gmailDue()
        assertEquals(listOf(Change.Unstar, Change.Archive), gmail.stored)
        assertEquals(GmailWrites.State.Made, GmailWrites.state(graph.stores.gmailWrites.value.writes, email.id))
        fromThePhone("c-2", UnfinishGmail(email.id), ago = 0)
        graph.owners.gmailDueAt = System.currentTimeMillis() - 1
        graph.gmailDue()
        assertEquals(listOf(Change.Unstar, Change.Archive, Change.Star, Change.Inbox), gmail.stored)
        assertTrue(gmail.message.starred && gmail.message.inbox)
    }

    @Test
    fun `a finish the phone journaled while it mirrored is the server's to write once it applies commands`() = runBlocking {
        ready()
        val due = System.currentTimeMillis() - GmailWrites.GRACE_MS - 1_000
        graph.stores.gmailWrites.update { it.copy(writes = listOf(GmailWrite("w-1", email.id, email.title, email.sourceId, due, notBefore = due + GmailWrites.GRACE_MS))) }
        // As at a start in commands mode: the journal's writes set going again.
        graph.owners.start()
        assertNotNull(graph.owners.gmailDueAt)
        graph.gmailDue()
        assertEquals(listOf(Change.Unstar, Change.Archive), gmail.stored)
    }
}
