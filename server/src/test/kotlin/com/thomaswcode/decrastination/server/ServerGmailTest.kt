package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.GmailWriteStore
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.sources.gmail.GmailWriter
import com.thomaswcode.decrastination.sources.gmail.GmailWriter.Change
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.FinishGmail
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.TickBlock
import com.thomaswcode.decrastination.sync.protocol.UndoAct
import com.thomaswcode.decrastination.sync.protocol.UnfinishGmail
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

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
        val stored: MutableList<Change> = Collections.synchronizedList(mutableListOf())

        /** What's done as each change is asked, before it's made. */
        var beforeChange: () -> Unit = {}

        override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T = block(object : GmailWriter.Session {
            override fun thread(threadId: String): List<Long> = listOf(message.uid)
            override fun messages(uids: List<Long>): List<GmailWriter.Message> = listOf(message)
            override fun store(uids: List<Long>, change: Change): List<Long> {
                beforeChange()
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

    /** The journal as the database holds it committed: what a restart would read. */
    private fun committedJournal(): GmailWriteStore = db.read { c ->
        c.prepareStatement("SELECT json FROM stores WHERE name = ?").use { q ->
            q.setString(1, "gmail-writes.json")
            q.executeQuery().use { if (it.next()) Json { ignoreUnknownKeys = true }.decodeFromString(GmailWriteStore.serializer(), it.getString(1)) else GmailWriteStore() }
        }
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
    fun `what an email holds is committed before Gmail's changed, the job waiting for a command open as it begins`() = runBlocking {
        ready()
        fromThePhone("c-1", FinishGmail(email.id), ago = GmailWrites.GRACE_MS + 1_000)
        // At Gmail's first change, what an Undo puts back stands in the database.
        var first: GmailWriteStore? = null
        gmail.beforeChange = { if (first == null) first = committedJournal() }
        // A command open as the job begins (a click on the website, say), which a write made meanwhile would wait for.
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val open = launch(Dispatchers.Default) { db.command { opened.complete(Unit); release.await() } }
        opened.await()
        val job = launch(Dispatchers.Default) { graph.gmailDue() }
        delay(300)
        assertEquals(emptyList(), gmail.stored, "nothing's changed in Gmail while the command's open")
        release.complete(Unit)
        open.join()
        job.join()
        assertEquals(listOf(Change.Unstar, Change.Archive), gmail.stored)
        val kept = assertNotNull(first).writes.single()
        assertEquals(listOf("101") to listOf("101"), kept.flagged to kept.inbox)
    }

    @Test
    fun `an email's last step ticked on the server finishes it in the journal, as its work's all done`() = runBlocking {
        ready()
        graph.stores.tasks.update { it.copy(tasks = listOf(email.copy(subSteps = listOf(SubStep("Read it", 10))))) }
        fromThePhone("c-1", TickBlock(email.id, 0, "Read it"), ago = 0)
        val finish = graph.stores.gmailWrites.value.writes.single()
        assertEquals(email.id to false, finish.taskId to finish.explicit)
    }

    @Test
    fun `the website's chat offers the Undo of his Done on an email while its finish stands`() = runBlocking {
        ready()
        fromThePhone("c-1", FinishGmail(email.id), ago = 0)
        val write = graph.stores.gmailWrites.value.writes.single().id
        val done = Act("Done “A made-up email”", ChatAction(ChatAction.Type.Done, email.id), after = write)
        graph.stores.chat.update { it.copy(messages = listOf(ChatMessage("m-1", Speaker.Felix, "Done.", System.currentTimeMillis(), acts = listOf(done)))) }
        val row = graph.chat(0, null).rows.single { it.id == "m-1" }
        assertEquals(listOf(UndoAct("m-1", 0)), row.lines.mapNotNull { it.undo })
    }

    @Test
    fun `a write given up is said on the website's Setup and its mark, once the server makes them`() = runBlocking {
        ready()
        // Every source read, so the mark's for the write alone.
        graph.stores.tasks.update { it.copy(sources = Source.SYNCED.associateWith { SourceStatus(lastSuccessAt = 1) }) }
        assertFalse(graph.notices().setup)
        graph.stores.gmailWrites.update {
            it.copy(writes = listOf(GmailWrite("w-1", email.id, email.title, email.sourceId, 1, notBefore = 1, tries = 5, lastError = "no connection", doneAt = 2, gaveUp = true)))
        }
        assertTrue(graph.setup().sources.single { it.title.startsWith("Gmail") }.detail.contains("“A made-up email”"))
        assertTrue(graph.notices().setup)
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
