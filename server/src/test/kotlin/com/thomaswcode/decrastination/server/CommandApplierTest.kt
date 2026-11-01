package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.protect.Totp
import com.thomaswcode.decrastination.sync.protocol.ApplyChangeNow
import com.thomaswcode.decrastination.sync.protocol.ChangeSettings
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.sync.protocol.UseParentCode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    private val at = System.currentTimeMillis() - 86_400_000L

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun own(title: String) = SaveOwnTask("s-$title", OwnTask(title = title))

    private suspend fun send(command: Command): Outcome = graph.applier.apply(CommandBatch(listOf(command)), "phone").single()

    private fun rows() = db.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM commands").use { it.next(); it.getInt(1) } } }

    private fun code(secret: ByteArray) = Totp.code(secret, Totp.step(System.currentTimeMillis()))

    private fun wrong(secret: ByteArray) = (0..999_999).asSequence().map { it.toString().padStart(Totp.DIGITS, '0') }.first { Totp.matchingStep(secret, it, System.currentTimeMillis()) == null }

    private suspend fun parentCode(): ByteArray = Totp.newSecret().also { graph.stores.secrets.put(Secret.TotpSecret, Totp.base32(it)) }

    private suspend fun armedWithAChange(): String {
        graph.stores.settings.update { it.copy(armed = true) }
        send(Command("loosen", at, ChangeSettings(mapOf("workMinPerFreeMin" to JsonPrimitive(1)))))
        return graph.stores.protection.value.pending.single().id
    }

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
    fun `an answer that says no is reported with its reason, and what it did stands`() = runBlocking {
        val secret = parentCode()
        val first = send(Command("one", at, UseParentCode(wrong(secret))))
        assertEquals(Outcome.DROPPED, first.state)
        assertEquals("Wrong code: 2 tries left", first.why)
        assertEquals("Wrong code: 1 try left", send(Command("two", at, UseParentCode(wrong(secret)))).why, "the first wrong one is counted")
        assertEquals(first.why, send(Command("one", at, UseParentCode(wrong(secret)))).why, "and a repeat answers as it did")
    }

    @Test
    fun `a task the form wouldn't take is dropped, saying why, and nothing's made`() = runBlocking {
        val outcome = send(Command("one", at, SaveOwnTask("s", OwnTask(title = " "))))
        assertEquals(Outcome.DROPPED, outcome.state)
        assertEquals("It needs a name", outcome.why)
        assertTrue(graph.stores.tasks.value.tasks.isEmpty())
    }

    @Test
    fun `a website's moment is the server's, whatever the browser says`() = runBlocking {
        val before = System.currentTimeMillis()
        for ((id, moment) in listOf("early" to at, "late" to System.currentTimeMillis() + 86_400_000L)) {
            val command = Command(id, moment, SaveOwnTask(id, OwnTask(title = id)))
            graph.applier.apply(CommandBatch(listOf(command)), Client.WEBSITE)
            assertTrue(graph.stores.tasks.value.tasks.single { it.title == id }.firstSeenAt in before..System.currentTimeMillis(), id)
        }
    }

    @Test
    fun `a phone's moment may be earlier, as one made offline is, but never later`() = runBlocking {
        send(Command("early", at, own("Earlier")))
        assertEquals(at, graph.stores.tasks.value.tasks.single { it.title == "Earlier" }.firstSeenAt)
        send(Command("late", System.currentTimeMillis() + 86_400_000L, own("Later")))
        assertTrue(graph.stores.tasks.value.tasks.single { it.title == "Later" }.firstSeenAt <= System.currentTimeMillis())
    }

    @Test
    fun `a client's clock can't outrun the lock on a code`() = runBlocking {
        val secret = parentCode()
        val hour = 3_600_000L
        val now = System.currentTimeMillis()
        repeat(3) { send(Command("wrong-$it", now, UseParentCode(wrong(secret)))) }
        val after = send(Command("later", now + 2 * hour, UseParentCode(code(secret))))
        assertEquals(Outcome.DROPPED, after.state)
        assertTrue(assertNotNull(after.why).startsWith("Too many wrong codes"), after.why)
    }

    @Test
    fun `a waiting change goes through only with its code, which isn't spent on one that's gone`() = runBlocking {
        val secret = parentCode()
        val id = armedWithAChange()
        val refused = send(Command("bad", at, ApplyChangeNow(id, wrong(secret))))
        assertEquals("Wrong code: 2 tries left", refused.why)
        assertEquals(listOf(id), graph.stores.protection.value.pending.map { it.id })
        assertEquals("That change isn't waiting now", send(Command("none", at, ApplyChangeNow("no such change", code(secret)))).why)
        assertEquals(Outcome.APPLIED, send(Command("good", System.currentTimeMillis(), ApplyChangeNow(id, code(secret)))).state, "the code wasn't used up on the last")
        assertTrue(graph.stores.protection.value.pending.isEmpty())
        assertEquals(1, graph.stores.settings.value.workMinPerFreeMin)
    }

    @Test
    fun `settings are held to what the screen would take`() = runBlocking {
        val box = graph.stores.settings.value.boxMin
        assertEquals("That's out of range: the box length", send(Command("low", at, ChangeSettings(mapOf("boxMin" to JsonPrimitive(1))))).why)
        assertEquals("That isn't a value it can have", send(Command("words", at, ChangeSettings(mapOf("boxMin" to JsonPrimitive("long"))))).why)
        assertEquals("That can't be changed", send(Command("shape", at, ChangeSettings(mapOf("version" to JsonPrimitive(1))))).why)
        assertEquals(box, graph.stores.settings.value.boxMin)
        assertEquals(Outcome.APPLIED, send(Command("fine", at, ChangeSettings(mapOf("boxMin" to JsonPrimitive(45))))).state)
        assertEquals(45, graph.stores.settings.value.boxMin)
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
    fun `a revision taken in putting a failed command right is heard, as any other is`() = runBlocking {
        send(Command("one", at, own("Essay")))
        // Only the tasks can be written: finishing one writes them, and fails at the next store it reaches.
        db.transaction { c ->
            c.createStatement().use { it.execute("CREATE TRIGGER only_tasks BEFORE INSERT ON store_history WHEN NEW.name <> 'tasks.json' BEGIN SELECT RAISE(ABORT, 'no room'); END") }
        }
        val heard = mutableListOf<Long>()
        var first = true
        // A job's write as the stores are read again: held, as the command's still being put right, and made afresh.
        val reload: suspend () -> Unit = {
            if (first) {
                first = false
                CoroutineScope(Dispatchers.IO).async { graph.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(detail = "A job's") }) } }.await()
            }
        }
        val applier = CommandApplier(db, graph.backend, Commands(graph.owners, graph.clock), graph.owners, graph.clock, reload) { heard += it }
        val before = db.currentRevision()
        val outcome = applier.apply(CommandBatch(listOf(Command("two", at, FinishOwnTask("own:s-Essay")))), "phone").single()
        assertEquals(Outcome.DROPPED, outcome.state)
        assertTrue(db.currentRevision() > before, "the job's write took one")
        assertEquals(listOf(db.currentRevision()), heard)
        assertEquals(db.currentRevision(), outcome.revision, "and what's on record is the state it left")
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
