package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.Backups
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.DeleteOwnTask
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.ResetData
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A backup out and in, and the resets, on the server (docs/spec/storage-and-backups.md). */
class ServerBackupTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** The nightly place: what's sent, by name. */
    private class Sent : BackupService.Folder {
        val files: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
        override val available = true

        override fun write(name: String, text: String): Boolean {
            files[name] = text
            return true
        }

        override fun names(): List<String> = emptyList()

        override fun delete(name: String) = false
    }

    private val dir: File = Files.createTempDirectory("backups").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val sent = Sent()
    private val graph = ServerGraph(db, dir, Plain, "test", nightly = sent)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun send(action: Action, id: String = "c-${action.hashCode()}"): Outcome =
        graph.applier.apply(CommandBatch(listOf(Command(id, System.currentTimeMillis(), action))), Client.WEBSITE).single()

    private suspend fun withTask() {
        send(SaveOwnTask("s", OwnTask(title = "Made-up essay", minutes = 30)))
    }

    private fun titles() = graph.stores.tasks.value.tasks.map { it.title }

    private fun copies() = File(dir, "reset-copies").list().orEmpty().toList()

    @Test
    fun `a backup taken in brings back what went, as one revision`() = runBlocking<Unit> {
        withTask()
        val backup = assertNotNull(graph.backup())
        send(DeleteOwnTask("own:s"))
        assertTrue(titles().isEmpty())
        val before = db.currentRevision()
        val heard = async(start = CoroutineStart.UNDISPATCHED) { graph.revisions.first() }
        val said = graph.importBackup(backup)
        assertTrue(said.startsWith("Restored the settings"), said)
        assertEquals(listOf("Made-up essay"), titles())
        assertEquals(before + 1, db.currentRevision())
        assertEquals(before + 1, withTimeout(5_000) { heard.await() }, "the pages hear it")
        val cause = db.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT DISTINCT cause FROM store_history WHERE revision = ${before + 1}").use { it.next(); it.getString(1) } } }
        assertEquals("import", cause)
    }

    @Test
    fun `a backup that can't be taken in leaves the stores as they were`() = runBlocking<Unit> {
        withTask()
        val backup = assertNotNull(Backups.decode(assertNotNull(graph.backup()), graph.stores.shapes))
        val before = graph.stores.tasks.value
        val said = graph.importBackup(Backups.fitted(backup.copy(log = ActivityLog(checkIns = listOf(CheckIn("2026-10-12", 1L, 9, "", "", "", ""))))).first)
        assertTrue(said.startsWith("Nothing was restored"), said)
        assertEquals(before, graph.stores.tasks.value)
    }

    @Test
    fun `what isn't a backup says so, and nothing changes`() = runBlocking<Unit> {
        withTask()
        val before = db.currentRevision()
        assertEquals("That isn't a Decrastination backup (or it's from a newer version).", graph.importBackup("not one"))
        assertEquals(before, db.currentRevision())
    }

    @Test
    fun `a backup's calendar answers go where the server reads them, those here winning`() = runBlocking<Unit> {
        graph.calendarRead.update { it.copy(answers = mapOf("Both" to "busy")) }
        val backup = assertNotNull(Backups.decode(assertNotNull(graph.backup()), graph.stores.shapes))
        graph.importBackup(Backups.fitted(backup.copy(eventAnswers = mapOf("Both" to "load:60", "Only there" to "busy"))).first)
        assertEquals(mapOf("Both" to "busy", "Only there" to "busy"), graph.calendarRead.value.answers)
    }

    @Test
    fun `a reset keeps a copy on the box, sends it on, and that copy brings it back`() = runBlocking<Unit> {
        withTask()
        val fact = Fact("made up", "A made-up fact", 1L)
        graph.stores.memory.update { it.copy(facts = listOf(fact)) }
        val outcome = send(ResetData(Reset.Memories))
        assertEquals(Outcome.APPLIED, outcome.state)
        assertTrue(graph.stores.memory.value.facts.isEmpty())
        val name = copies().single()
        assertTrue(assertNotNull(outcome.said).endsWith("A copy of everything from before is in the app's own files ($name), which the Backup page can restore."), outcome.said)
        withTimeout(5_000) { while (name !in sent.files) delay(10) }
        assertEquals(File(dir, "reset-copies/$name").readText(), sent.files[name])
        assertTrue(graph.restoreCopy(name).startsWith("Restored the settings"))
        assertEquals(listOf(fact), graph.stores.memory.value.facts)
    }

    @Test
    fun `only a copy that's there can be restored`() = runBlocking<Unit> {
        File(dir, "reset-copies").mkdirs()
        File(dir, "secret.json").writeText("{}")
        assertEquals("That copy isn't there.", graph.restoreCopy("../secret.json"))
        assertEquals("That copy isn't there.", graph.restoreCopy("reset-made-up.json"))
    }

    @Test
    fun `a reset that's retried resets once`() = runBlocking<Unit> {
        assertEquals(Outcome.APPLIED, send(ResetData(Reset.Calibration), "again").state)
        assertEquals(Outcome.APPLIED, send(ResetData(Reset.Calibration), "again").state)
        assertEquals(1, copies().size)
    }

    @Test
    fun `a review that was out when the record was reset isn't kept, so what went stays gone`() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>()
        val letGo = CompletableDeferred<Unit>()
        // What's remembered held, which a review comes to after it's noted how many resets there have been.
        val holder = launch(Dispatchers.Default) {
            graph.stores.memory.update {
                inside.complete(Unit)
                runBlocking { letGo.await() }
                it
            }
        }
        inside.await()
        val review = launch(Dispatchers.Default) { graph.owners.learning.review(ifDue = false) }
        delay(1_000)
        assertEquals(Outcome.APPLIED, send(ResetData(Reset.Stats)).state)
        letGo.complete(Unit)
        holder.join()
        review.join()
        assertTrue(graph.stores.log.value.reviews.isEmpty(), "it would have put back what the reset took")
    }

    @Test
    fun `with nowhere for it to go, a nightly file is nothing to fail at, and a backup can still be had`() = runBlocking<Unit> {
        val other = Database(File(dir, "other.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
        val alone = ServerGraph(other, File(dir, "other").apply { mkdirs() }, Plain, "test")
        alone.nightly()
        assertTrue(assertNotNull(alone.backup()).startsWith("{"))
        other.close()
    }

    @Test
    fun `the page says what a backup comes to, and what a reset left`() = runBlocking<Unit> {
        val first = graph.backupView()
        assertTrue(first.canDownload)
        assertEquals(Reset.entries.size, first.resets.size)
        send(ResetData(Reset.Calibration))
        assertEquals(copies(), graph.backupView().copies)
    }

    @Test
    fun `a backup Import would refuse is said, and can still be had`() = runBlocking<Unit> {
        graph.stores.log.update { it.copy(reviews = listOf(WeeklyReview(1L, listOf("A made-up note."), by = "rules", week = "not a week"))) }
        assertTrue("a review's week" in assertNotNull(graph.backupView().fit))
        assertNotNull(graph.backup())
    }

    @Test
    fun `Setup needs you while a source fails, but not once they've read`() = runBlocking<Unit> {
        fun read(status: SourceStatus) = Source.SYNCED.associateWith { status }
        graph.stores.tasks.update { it.copy(sources = read(SourceStatus(lastSuccessAt = 1L))) }
        assertFalse(graph.notices().setup)
        graph.stores.tasks.update { it.copy(sources = read(SourceStatus(lastSuccessAt = 1L)) + (Source.Gmail to SourceStatus(error = "A made-up failure"))) }
        assertTrue(graph.notices().setup)
    }
}
