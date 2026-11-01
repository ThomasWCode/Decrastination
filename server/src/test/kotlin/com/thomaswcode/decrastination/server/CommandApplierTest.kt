package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.ChangeSettings
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Commands on the server (docs/spec/server.md): each through its owner, all of it or none of it, once however often it's sent. */
class CommandApplierTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("commands").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val at = 1_792_000_000_000L

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun own(title: String) = SaveOwnTask("s-$title", OwnTask(title = title))

    private suspend fun send(command: Command): Outcome = graph.applier.apply(CommandBatch(listOf(command)), "phone").single()

    private fun rows() = db.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM commands").use { it.next(); it.getInt(1) } } }

    @Test
    fun `a command goes through its owner, at its own moment, as one revision`() = runBlocking {
        val outcome = send(Command("one", at, own("Essay")))
        assertEquals(Outcome.APPLIED, outcome.state)
        assertEquals(1L, outcome.revision)
        val task = graph.stores.tasks.value.tasks.single()
        assertEquals("Essay", task.title)
        assertEquals(at, task.firstSeenAt, "the moment it was done, not the moment it landed")
        assertEquals(listOf("tasks.json"), graph.state(0).stores.map { it.name })
    }

    @Test
    fun `a repeat of an id answers as the first time, and applies nothing`() = runBlocking {
        val first = send(Command("one", at, own("Essay")))
        val again = send(Command("one", at, own("Essay")))
        assertEquals(first, again)
        assertEquals(1, graph.stores.tasks.value.tasks.size)
        assertEquals(1L, db.currentRevision())
        assertEquals(1, rows())
    }

    @Test
    fun `a retry racing its first send applies nothing twice, and gets the same answer`() = runBlocking {
        val command = Command("one", at, own("Essay"))
        val answers = (1..8).map { async(Dispatchers.IO) { send(command) } }.awaitAll()
        assertEquals(1, answers.toSet().size, "every send heard the one outcome")
        assertEquals(Outcome.APPLIED, answers.first().state)
        assertEquals(1, rows())
        assertEquals(1L, db.currentRevision())
    }

    @Test
    fun `a repeat says no revision, as the stream only goes forward`() = runBlocking {
        val said = mutableListOf<Long>()
        val watching = launch(Dispatchers.Unconfined) { graph.revisions.collect { said += it } }
        send(Command("one", at, own("Essay")))
        send(Command("two", at, own("Maths")))
        send(Command("one", at, own("Essay")))
        watching.cancel()
        assertEquals(listOf(1L, 2L), said)
    }

    @Test
    fun `a batch goes in order, and a later one sees what an earlier one did`() = runBlocking {
        val task = "own:s-Essay"
        val outcomes = graph.applier.apply(CommandBatch(listOf(Command("one", at, own("Essay")), Command("two", at + 1, FinishOwnTask(task)))), "phone")
        assertEquals(listOf(Outcome.APPLIED, Outcome.APPLIED), outcomes.map { it.state })
        assertTrue(!graph.stores.tasks.value.tasks.single().isOpen)
    }

    @Test
    fun `a command on a task that's gone is dropped, says why, and is on record`() = runBlocking {
        val outcome = send(Command("one", at, FinishOwnTask("own:gone")))
        assertEquals(Outcome.DROPPED, outcome.state)
        assertEquals("That task had gone", outcome.why)
        assertEquals(0L, db.currentRevision())
        assertEquals(1, rows())
        assertEquals(outcome, send(Command("one", at, FinishOwnTask("own:gone"))))
    }

    @Test
    fun `one that fails leaves nothing, in the database or in memory, and the next goes on`() = runBlocking {
        send(Command("one", at, own("Essay")))
        val before = graph.stores.settings.value
        // Its one good field written, then a value no setting can be read as.
        val outcome = send(Command("two", at, ChangeSettings(mapOf("boxMin" to JsonPrimitive(55), "weekdayHours" to JsonPrimitive("not a number")))))
        assertEquals(Outcome.DROPPED, outcome.state)
        assertNotNull(outcome.why)
        assertEquals(before, graph.stores.settings.value)
        assertEquals(1L, db.currentRevision())
        assertEquals(Outcome.APPLIED, send(Command("three", at, own("Maths"))).state)
        assertEquals(setOf("Essay", "Maths"), graph.stores.tasks.value.tasks.map { it.title }.toSet())
    }

    @Test
    fun `a command waiting behind one that fails part-way builds on nothing it left in memory`() = runBlocking {
        send(Command("one", at, own("Essay")))
        // Only the tasks can be written: finishing one writes them, and fails at the next store it reaches.
        db.transaction { c ->
            c.createStatement().use { it.execute("CREATE TRIGGER only_tasks BEFORE INSERT ON store_history WHEN NEW.name <> 'tasks.json' BEGIN SELECT RAISE(ABORT, 'no room'); END") }
        }
        val free = CompletableDeferred<Unit>()
        val holding = launch(Dispatchers.IO) { db.command { free.await() } }
        delay(100)
        val failing = async(Dispatchers.IO) { send(Command("two", at, FinishOwnTask("own:s-Essay"))) }
        delay(100)
        val next = async(Dispatchers.IO) { send(Command("three", at, own("Maths"))) }
        delay(100)
        free.complete(Unit)
        holding.join()
        assertEquals(Outcome.DROPPED, failing.await().state)
        assertEquals(Outcome.APPLIED, next.await().state)
        val open = { graph.stores.tasks.value.tasks.filter { it.isOpen }.map { it.title }.toSet() }
        assertEquals(setOf("Essay", "Maths"), open())
        graph.stores.tasks.reload()
        assertEquals(setOf("Essay", "Maths"), open(), "and nothing of it reached the database")
    }

    @Test
    fun `a command changing thread on the way is still one transaction, and a read sees it only once it's done`() = runBlocking {
        db.setMeta("probe", "before")
        val during = db.command {
            withContext(Dispatchers.IO) { db.setMeta("probe", "during") }
            db.read { c -> c.prepareStatement("SELECT value FROM meta WHERE key = 'probe'").use { q -> q.executeQuery().use { it.next(); it.getString(1) } } }
        }
        assertEquals("before", during)
        assertEquals("during", db.meta("probe"))
    }

    @Test
    fun `one that throws in a command is gone`() = runBlocking {
        db.setMeta("probe", "before")
        val failed = runCatching {
            db.command {
                withContext(Dispatchers.IO) { db.setMeta("probe", "during") }
                error("no")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals("before", db.meta("probe"))
        assertNull(failed.getOrNull())
    }
}
