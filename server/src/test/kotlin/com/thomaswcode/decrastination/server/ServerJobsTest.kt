package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    fun `one that fails stops neither the others nor itself`() = runBlocking {
        var tries = 0
        var after = 0
        val jobs = jobs(ServerJob.every("bad", 60_000) { tries++; error("no") }, ServerJob.every("good", 60_000) { after++ })
        jobs.tick()
        assertEquals(1, after, "the next still ran")
        jobs.tick()
        assertEquals(2, tries, "failed, it's tried again at the next turn")
        assertEquals(1, after, "and what worked isn't")
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
}
