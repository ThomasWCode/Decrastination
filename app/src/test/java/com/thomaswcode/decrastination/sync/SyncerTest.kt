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

    @Test
    fun `one source failing leaves the others' tasks merged and its own as they were`() = runTest {
        val teams = FakeSource(Source.Teams) { items("a", "b") }
        val gmail = FakeSource(Source.Gmail) { items("m") }
        val syncer = Syncer(tasks, settings, listOf(teams, gmail), clock)
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
    fun `a source that hangs times out as a failure`() = runTest {
        val slow = FakeSource(Source.PowerPlanner) { awaitCancellation() }
        val report = Syncer(tasks, settings, listOf(slow), clock, timeoutMs = 1_000).sync()
        assertEquals("No answer in 1 s", report.failures[Source.PowerPlanner])
    }

    @Test
    fun `Anki is read last, and sees what the others just found`() = runTest {
        val anki = FakeSource(Source.Anki) { SourceRead(emptyList(), ankiDay = AnkiDay("2026-10-08", 12, "Textbook 1::1.2")) }
        val teams = FakeSource(Source.Teams) { items("vocab") }
        Syncer(tasks, settings, listOf(anki, teams), clock).sync()
        assertEquals(listOf("teams:vocab"), anki.seen.single().known.map { it.id })
        assertEquals(12L, tasks.value.ankiDay?.deckId)
    }

    @Test
    fun `only the asked-for sources are read, and listeners hear of changes`() = runTest {
        val teams = FakeSource(Source.Teams) { items("a") }
        val gmail = FakeSource(Source.Gmail) { items("m") }
        val syncer = Syncer(tasks, settings, listOf(teams, gmail), clock)
        var heard = 0
        syncer.addListener { heard += it.added.size }
        syncer.sync(setOf(Source.Gmail))
        assertTrue(teams.seen.isEmpty())
        assertEquals(1, heard)
        syncer.sync(setOf(Source.Gmail))
        assertEquals(1, heard)
    }
}
