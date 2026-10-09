package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import com.thomaswcode.decrastination.sources.anki.AnkiDay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncerTest {
    private val dir: File = Files.createTempDirectory("sync").toFile()
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState)
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
    private val clock = FixedClock.at("2026-10-08T17:00:00+01:00")

    private class FakeSource(override val source: Source, var answer: suspend (ReadContext) -> SourceRead) : TaskSource {
        val seen = mutableListOf<ReadContext>()
        override suspend fun read(context: ReadContext): SourceRead {
            seen += context
            return answer(context)
        }
    }

    private fun items(vararg ids: String) = SourceRead(ids.map { Fetched(it, it, Kind.Homework) })

    /** Reads run on the test's own scheduler, so its virtual clock is the one that times them out. */
    private fun TestScope.syncer(sources: List<TaskSource>, timeoutMs: Long = 90_000) =
        Syncer(tasks, settings, sources, clock, timeoutMs, readContext = StandardTestDispatcher(testScheduler))

    @Test
    fun `one source failing leaves the others' tasks merged and its own as they were`() = runTest {
        val teams = FakeSource(Source.Teams) { items("a", "b") }
        val gmail = FakeSource(Source.Gmail) { items("m") }
        val syncer = syncer(listOf(teams, gmail))
        syncer.sync()

        gmail.answer = { throw java.io.IOException("No network") }
        teams.answer = { items("b") }
        val report = syncer.sync()

        assertEquals(mapOf(Source.Gmail to "No network"), report.failures)
        assertEquals(listOf("teams:a"), report.completed.map { it.id })
        assertEquals(Status.Open, tasks.value.tasks.single { it.id == "gmail:m" }.status)
        assertEquals("No network", tasks.value.status(Source.Gmail).error)
        assertEquals(clock.time, tasks.value.status(Source.Gmail).lastSuccessAt)
        assertNull(tasks.value.status(Source.Teams).error)
    }

    @Test
    fun `a read that blocks is abandoned at the timeout, not waited for`() = runTest {
        // A blocking call ignores cancellation: the sync must give up on it anyway.
        val release = java.util.concurrent.CountDownLatch(1)
        val stuck = FakeSource(Source.Gmail) {
            release.await()
            items("late")
        }
        val syncer = Syncer(tasks, settings, listOf(stuck), FixedClock(clock.time), timeoutMs = 200)
        val started = System.nanoTime()
        val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { syncer.sync() }
        release.countDown()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals("No answer in 0 s", report.failures[Source.Gmail])
    }

    @Test
    fun `a source that hangs times out as a failure`() = runTest {
        val slow = FakeSource(Source.PowerPlanner) { awaitCancellation() }
        val report = syncer(listOf(slow), timeoutMs = 1_000).sync()
        assertEquals("No answer in 1 s", report.failures[Source.PowerPlanner])
    }

    @Test
    fun `Anki is read last, and sees what the others just found`() = runTest {
        val anki = FakeSource(Source.Anki) { SourceRead(emptyList(), ankiDay = AnkiDay("2026-10-08", 12, "Textbook 1::1.2")) }
        val teams = FakeSource(Source.Teams) { items("vocab") }
        syncer(listOf(anki, teams)).sync()
        assertEquals(listOf("teams:vocab"), anki.seen.single().known.map { it.id })
        assertEquals(12L, tasks.value.ankiDay?.deckId)
    }

    @Test
    fun `only the asked-for sources are read, and listeners hear of changes`() = runTest {
        val teams = FakeSource(Source.Teams) { items("a") }
        val gmail = FakeSource(Source.Gmail) { items("m") }
        val syncer = syncer(listOf(teams, gmail))
        var heard = 0
        syncer.addListener { heard += it.added.size }
        syncer.sync(setOf(Source.Gmail))
        assertTrue(teams.seen.isEmpty())
        assertEquals(1, heard)
        syncer.sync(setOf(Source.Gmail))
        assertEquals(1, heard)
    }

    @Test
    fun `after-every-sync listeners hear every sync, changed or not`() = runTest {
        val syncer = syncer(listOf(FakeSource(Source.Gmail) { items("m") }))
        var syncs = 0
        syncer.addAfterEverySync { syncs++ }
        syncer.sync()
        syncer.sync()
        assertEquals(2, syncs)
    }
}
