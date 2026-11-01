package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/** What the server does by itself (docs/spec/server.md §Jobs), and what it holds that a restore has to carry. */
class ServerJobsTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("jobs").toFile()
    private val db = Database(File(dir, "test.db").path)
    private val clock = FixedClock(1_000_000L)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun jobs(vararg jobs: ServerJob, running: Boolean = true) = ServerJobs(db, clock, { running }, jobs.toList())

    @Test
    fun `in mirror mode nothing runs`() = runBlocking {
        var ran = 0
        jobs(ServerJob.every("a", 60_000) { ran++ }, running = false).tick()
        assertEquals(0, ran)
    }

    @Test
    fun `a job runs when it's due, and not again till its time comes round`() = runBlocking {
        var ran = 0
        val jobs = jobs(ServerJob.every("a", 60_000) { ran++ })
        jobs.tick()
        jobs.tick()
        assertEquals(1, ran)
        clock.time += 60_000
        jobs.tick()
        assertEquals(2, ran)
    }

    @Test
    fun `a restart neither repeats a job nor misses one`() = runBlocking {
        var ran = 0
        jobs(ServerJob.every("a", 60_000) { ran++ }).tick()
        jobs(ServerJob.every("a", 60_000) { ran++ }).tick()
        assertEquals(1, ran)
    }

    @Test
    fun `one that fails stops neither the others nor itself, and waits its turn`() = runBlocking {
        var tries = 0
        var after = 0
        val jobs = jobs(ServerJob.every("bad", 60_000) { tries++; error("no") }, ServerJob.every("good", 600_000) { after++ })
        jobs.tick()
        assertEquals(1, after, "the next still ran")
        jobs.tick()
        assertEquals(1, tries, "not round again at once")
        clock.time += 60_000
        jobs.tick()
        assertEquals(2, tries, "failed, it's tried again at its next turn")
        assertEquals(1, after, "and what worked isn't")
    }

    @Test
    fun `a job at a set time that fails waits its retry, not the next minute`() = runBlocking {
        var tries = 0
        val jobs = jobs(ServerJob("bad", due = { last, _ -> last == null }) { tries++; error("no") })
        jobs.tick()
        clock.time += 60_000
        jobs.tick()
        assertEquals(1, tries)
        clock.time += ServerJob.RETRY_MS
        jobs.tick()
        assertEquals(2, tries)
    }

    @Test
    fun `a job's checkpoint isn't lost with a command that's rolled back`() = runBlocking {
        var ran = 0
        val jobs = jobs(ServerJob.every("a", 60_000) { ran++ })
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    entered.complete(Unit)
                    letGo.await()
                    error("rolled back")
                }
            }
        }
        entered.await()
        val turn = async(Dispatchers.IO) { jobs.tick() }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        turn.await()
        jobs.tick()
        assertEquals(1, ran, "it isn't run again")
    }

    @Serializable
    private data class Seen(val by: List<String> = emptyList())

    private fun store(backend: SqliteBackend) = JsonStore(backend, "a.json", Seen.serializer(), ::Seen)

    @Test(timeout = STUCK_MS)
    fun `a store's write from outside a command waits for nothing, so the two can't wait for each other`() = runBlocking {
        val store = store(SqliteBackend(db))
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.command {
                store.update { it.copy(by = it.by + "command") }
                entered.complete(Unit)
                letGo.await()
                store.update { it.copy(by = it.by + "command again") }
            }
        }
        entered.await()
        store.update { it.copy(by = it.by + "job") }
        letGo.complete(Unit)
        command.await()
        val made = listOf("command", "job", "command again")
        assertEquals(made, store.value.by)
        store.reload()
        assertEquals(made, store.value.by, "the job's write didn't go over the command's, which takes it in")
    }

    @Test(timeout = STUCK_MS)
    fun `what's held back for a command that's rolled back is kept, in its order`() = runBlocking {
        val store = store(SqliteBackend(db))
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    entered.complete(Unit)
                    letGo.await()
                    store.update { it.copy(by = it.by + "command") }
                    error("rolled back")
                }
            }
        }
        entered.await()
        store.update { it.copy(by = it.by + "one") }
        store.update { it.copy(by = it.by + "two") }
        assertEquals(null, db.read { c -> c.prepareStatement("SELECT json FROM stores WHERE name = 'a.json'").use { q -> q.executeQuery().use { r -> if (r.next()) r.getString(1) else null } } }, "not yet")
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        store.reload()
        assertEquals(listOf("one", "two"), store.value.by)
    }

    @Test(timeout = STUCK_MS)
    fun `a credential sealed from outside a command waits for nothing either, and goes in as it closes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            db.command {
                entered.complete(Unit)
                letGo.await()
            }
        }
        entered.await()
        db.setCredentials("a", byteArrayOf(1), 0)
        assertEquals(null, db.credentials("a"), "not yet")
        letGo.complete(Unit)
        command.await()
        assertContentEquals(byteArrayOf(1), db.credentials("a"))
    }

    @Test
    fun `a loosening's wait is counted by the server's clock, across a restart, and a phone's mark is another clock's`() {
        val mark = requireNotNull(ServerClock(db).uptime())
        Thread.sleep(5)
        val later = requireNotNull(ServerClock(db).uptime())
        assertTrue(requireNotNull(later.since(mark)) > 0)
        assertEquals(null, later.since(Uptime(3, 1_000)))
        assertEquals(null, later.since(Uptime(Uptime.UNKNOWN_BOOT, 1_000)))
    }

    @Test
    fun `an own task whose steps are all done is finished, once the server applies commands`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        val scope = CoroutineScope(Dispatchers.Default)
        graph.start(scope)
        val id = "own:s-a"
        graph.applier.apply(CommandBatch(listOf(Command("one", clock.now(), SaveOwnTask("s-a", OwnTask(title = "A", steps = listOf(OwnStep("B", 30))))))), "website")
        assertTrue(graph.stores.tasks.value.tasks.single { it.id == id }.isOpen)
        graph.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { task -> task.copy(subSteps = task.subSteps.map { it.copy(done = true) }) }) }
        withTimeout(5_000) { graph.stores.tasks.state.first { state -> state.tasks.single { it.id == id }.status == Status.Done } }
        scope.cancel()
    }

    @Test
    fun `in mirror mode nothing watches the stores`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        val scope = CoroutineScope(Dispatchers.Default)
        graph.start(scope)
        graph.stores.tasks.update { it.copy(tasks = listOf(TaskItem("own:a", Source.Own, "a", "A", kind = Kind.Homework, subSteps = listOf(SubStep("B", 30, done = true)), firstSeenAt = 0, lastSeenAt = 0))) }
        delay(200)
        assertTrue(graph.stores.tasks.value.tasks.single().isOpen)
        scope.cancel()
    }

    @Test
    fun `a nightly file not written is a failure, to be tried again, not a night gone without`() = runBlocking {
        val folder = object : BackupService.Folder {
            override val available = true

            override fun write(name: String, text: String) = false

            override fun names() = emptyList<String>()

            override fun delete(name: String) = false
        }
        assertFailsWith<IllegalStateException> { ServerGraph(db, dir, Plain, "test", nightly = folder).nightly() }
        Unit
    }

    @Test
    fun `a reminder left to be asked for afresh is asked for, as the phone's worker would`() = runBlocking {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test")
        val now = System.currentTimeMillis()
        graph.stores.felix.update { it.copy(reminders = listOf(Reminder("r", "words", now - 600_000, By.Felix, due = Due(DueKind.At, now - 1_000)))) }
        graph.owners.ports.remindLater()
        withTimeout(5_000) { graph.stores.chat.state.first { chat -> chat.messages.any { it.reminderId == "r" } } }
        Unit
    }

    @Test
    fun `a write from outside a command waits for it, and isn't lost when it's rolled back`() = runBlocking {
        db.setMeta("probe", "before")
        val entered = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        val command = async(Dispatchers.IO) {
            runCatching {
                db.command {
                    db.transaction { c -> c.createStatement().use { it.execute("INSERT INTO meta(key, value) VALUES('command', 'x')") } }
                    entered.complete(Unit)
                    letGo.await()
                    error("rolled back")
                }
            }
        }
        entered.await()
        val job = async(Dispatchers.IO) { db.transaction { c -> c.createStatement().use { it.execute("INSERT INTO meta(key, value) VALUES('job', 'y')") } } }
        withContext(Dispatchers.IO) { Thread.sleep(150) }
        assertFalse(job.isCompleted, "it waits while the command's open")
        letGo.complete(Unit)
        assertTrue(command.await().isFailure)
        job.await()
        assertEquals("y", db.meta("job"), "kept, though the command went")
        assertEquals(null, db.meta("command"))
    }

    @Test
    fun `credentials are in the database, sealed, and a rebuilt box gets them back from it`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.setCredentials(mapOf("GmailAddress" to "someone@example.org", "AnthropicApiKey" to "not settable from the website"))
        assertEquals(setOf("GmailAddress"), graph.credentials())
        val sealed = assertNotNull(db.credentials("secrets.bin"))
        val file = File(dir, "secrets.bin")
        assertContentEquals(file.readBytes(), sealed)
        assertTrue(file.delete(), "the box is rebuilt: only the database came back")
        val rebuilt = ServerGraph(db, dir, Plain, "test")
        assertEquals("someone@example.org", rebuilt.stores.secrets[Secret.GmailAddress])
    }

    @Test
    fun `a blank credential is taken away`() = runBlocking {
        val graph = ServerGraph(db, dir, Plain, "test")
        graph.setCredentials(mapOf("GmailAppPassword" to "secret"))
        graph.setCredentials(mapOf("GmailAppPassword" to ""))
        assertEquals(emptySet(), graph.credentials())
    }

    private companion object {
        /** For what could hang: a coroutine's timeout can't reach a thread stopped on a lock, and JUnit's stops waiting for it. */
        const val STUCK_MS = 15_000L
    }
}
