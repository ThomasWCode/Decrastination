package com.thomaswcode.decrastination.server

import com.sun.net.httpserver.HttpServer
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.learn.DayChunk
import com.thomaswcode.decrastination.learn.DayRecord
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a command sets off that reaches Claude waits for the command to close (docs/spec/server.md §Jobs): one that's rolled back
 * leaves nothing to ask, so nothing's paid for, and each retry of it pays nothing either. Claude's a stand-in that counts.
 */
class AfterCommandsTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("after").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }

    /** Each request made of Claude, answered with a failure: whether one was made is all that's asked here. */
    private val asked = AtomicInteger()
    private val claude = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            asked.incrementAndGet()
            exchange.sendResponseHeaders(400, -1)
            exchange.close()
        }
        start()
    }
    private val graph = ServerGraph(db, dir, Plain, "test", claudeKey = "a made-up key", claudeEndpoint = "http://127.0.0.1:${claude.address.port}")

    init {
        runBlocking { graph.stores.settings.update { it.copy(aiEnabled = true) } }
    }

    @AfterTest
    fun cleanUp() {
        // What's still asking Claude stopped first, or it would find its database closed.
        runBlocking { graph.stopForATest() }
        claude.stop(0)
        db.close()
        dir.deleteRecursively()
    }

    /**
     * Till Claude's asked: half a minute at most, as a first call can take many seconds to go out under a whole build's load, though
     * it takes two alone. Nothing else here asks in that time: the server's own sync is every 15 minutes.
     */
    private suspend fun reachesClaude() = withTimeout(30_000) { while (asked.get() == 0) delay(20) }

    /** [block] in a command that's rolled back, given time for whatever it set off to reach Claude before it is. */
    private suspend fun rolledBack(block: suspend () -> Unit) {
        assertFailsWith<IllegalStateException> {
            db.commandOr({ graph.stores.stored.forEach { it.reload() } }, { failed -> throw failed }) {
                block()
                withTimeoutOrNull(2_000) { while (asked.get() == 0) delay(20) }
                error("the commit fails")
            }
        }
        // What waited for it runs now.
        delay(500)
    }

    @Test
    fun `a message sent in a command that's rolled back is never asked of Claude`() = runBlocking {
        rolledBack { graph.owners.conversation.ask("Plan my evening", id = "m-1") }
        assertEquals(0, asked.get())
        assertTrue(graph.stores.chat.value.messages.none { it.id == "m-1" })
    }

    @Test
    fun `an instruction written in a command that's rolled back is never read by Claude`() = runBlocking {
        rolledBack { graph.owners.instructions.addInstruction("Maths on Friday", About()) }
        assertEquals(0, asked.get())
        assertTrue(graph.stores.instructions.value.instructions.isEmpty())
    }

    @Test
    fun `a message that stands is asked of Claude once the command's closed`() = runBlocking {
        db.command { graph.owners.conversation.ask("Plan my evening", id = "m-1") }
        reachesClaude()
    }

    @Test
    fun `what a read sets off reaching Claude goes on its own time, so a read inside a command can't wait for itself`() = runBlocking {
        graph.owners.conversation.ask("Plan my evening", id = "m-1")
        // A read merged inside a command, as a phone's read sent as one will be: its after-work isn't waited for in it.
        withTimeout(10_000) { db.command { graph.owners.syncer.sync(emptySet()) } }
        reachesClaude()
    }

    @Test
    fun `a reply that leaves a turn of the chat is answered at once, as the phone's worker answers it`() = runBlocking {
        // Kept while Claude's off, so it waits for its answer, with nothing asked yet.
        graph.stores.settings.update { it.copy(aiEnabled = false) }
        graph.owners.conversation.ask("Plan my evening", id = "m-1")
        delay(500)
        assertEquals(0, asked.get())
        graph.stores.settings.update { it.copy(aiEnabled = true) }
        graph.owners.ports.answerLater()
        reachesClaude()
    }

    @Test
    fun `a change of yours laid on the server makes today's record again, as the phone's does`() = runBlocking {
        graph.stores.settings.update { it.copy(aiEnabled = false) }
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(graph.clock.zone()).toLocalDate()
        // The morning's record, holding a piece that's neither done, worked nor in today's plan any more.
        graph.stores.log.update { it.copy(days = listOf(DayRecord(today.toString(), 30, listOf(DayChunk("gone", 30))))) }
        val saved = graph.applier.apply(CommandBatch(listOf(Command("c-1", System.currentTimeMillis(), SaveOwnTask("s-essay", OwnTask(title = "Essay", minutes = 30))))), Client.WEBSITE).single()
        assertEquals(Outcome.APPLIED, saved.state)
        val essay = graph.stores.tasks.value.tasks.single { it.title == "Essay" }
        val change = Change(ChangeType.OnDay, taskId = essay.id, date = today.plusDays(1).toString())
        assertEquals(Outcome.APPLIED, graph.applier.apply(CommandBatch(listOf(Command("c-2", System.currentTimeMillis(), AddOwnChange("i-1", "Tomorrow", About(taskId = essay.id, taskTitle = essay.title), change)))), Client.WEBSITE).single().state)
        val record = graph.stores.log.value.days.single { it.date == today.toString() }
        assertTrue(record.chunks.none { it.taskId == "gone" }, "today's record is made again from the plan as it now is")
    }

    @Test
    fun `a backup taken in on the server makes today's record again, as the phone's Import does`() = runBlocking {
        graph.stores.settings.update { it.copy(aiEnabled = false) }
        val backup = assertNotNull(graph.backup())
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(graph.clock.zone()).toLocalDate().toString()
        graph.stores.log.update { it.copy(days = listOf(DayRecord(today, 30, listOf(DayChunk("gone", 30))))) }
        assertTrue("Restored" in graph.importBackup(backup))
        withTimeout(5_000) { while (graph.stores.log.value.days.single { it.date == today }.chunks.any { it.taskId == "gone" }) delay(20) }
    }

    @Test
    fun `a day with nothing to do gets its free time from the server's minute, as the phone's tick gives it`() = runBlocking {
        graph.stores.settings.update { it.copy(freeAllowanceMin = 20, aiEnabled = false) }
        graph.jobs.tick()
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(graph.clock.zone()).toLocalDate()
        assertEquals(today.toString(), graph.stores.session.value.credit.on(today).allowanceOn)
    }
}
