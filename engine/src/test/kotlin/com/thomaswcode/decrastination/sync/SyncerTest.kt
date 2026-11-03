package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.AnkiDay
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.NotSameMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.WeeklyMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.data.TasksMigration
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sync.protocol.SourceObserved
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncerTest {
    private val dir: File = Files.createTempDirectory("sync").toFile()
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS, WeeklyMigration.TASKS, NotSameMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
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

        gmail.answer = { throw IOException("No network") }
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
    fun `a read that worked is known by when it began, so a change made while it ran isn't taken as seen`() = runTest {
        val began = clock.time
        val gmail = FakeSource(Source.Gmail) { clock.time += 5_000; items("m") }
        val syncer = syncer(listOf(gmail))
        syncer.sync()
        assertEquals(began + 5_000, tasks.value.status(Source.Gmail).lastSuccessAt)
        assertEquals(began, tasks.value.status(Source.Gmail).lastSuccessStartedAt)
        // A read failed since: when the one that worked began isn't kept.
        gmail.answer = { throw IOException("No network") }
        syncer.sync()
        assertNull(tasks.value.status(Source.Gmail).lastSuccessStartedAt)
    }

    @Test
    fun `a sync keeps what was finished a fortnight ago as its record, whichever source it's from`() = runTest {
        val day = 24 * 3_600_000L
        fun done(id: String, source: Source, daysAgo: Int) = TaskItem(
            id = id,
            source = source,
            sourceId = id.substringAfter(':'),
            title = id,
            detail = "Its text",
            kind = Kind.Admin,
            status = Status.Done,
            doneAt = clock.time - daysAgo * day,
            firstSeenAt = 0,
            lastSeenAt = clock.time - daysAgo * day,
            extra = mapOf(GmailThreads.EXTRA_UIDS to "1,2", GmailThreads.EXTRA_MESSAGE_ID to "m1"),
        )
        tasks.update { it.copy(tasks = listOf(done("gmail:m", Source.Gmail, 15), done("own:x", Source.Own, 13))) }
        syncer(listOf(FakeSource(Source.Teams) { items("a") })).sync()
        val after = tasks.value.tasks.associateBy { it.id }
        assertEquals("", after.getValue("gmail:m").detail)
        assertEquals(mapOf(GmailThreads.EXTRA_MESSAGE_ID to "m1"), after.getValue("gmail:m").extra)
        // Short of the fortnight: as it was.
        assertEquals("Its text", after.getValue("own:x").detail)
        assertEquals(setOf("gmail:m", "own:x", "teams:a"), after.keys)
    }

    @Test
    fun `the timetable read is kept, and a failed read leaves it as it was`() = runTest {
        val timetable = Timetable("2026-08-24T00:00:00", emptyList())
        val planner = FakeSource(Source.PowerPlanner) { SourceRead(emptyList(), timetable = timetable) }
        val syncer = syncer(listOf(planner))
        syncer.sync()
        assertEquals(timetable, tasks.value.timetable)
        planner.answer = { throw IOException("No network") }
        syncer.sync()
        assertEquals(timetable, tasks.value.timetable)
    }

    @Test
    fun `a read that blocks is abandoned at the timeout, not waited for`() = runTest {
        // A blocking call ignores cancellation: the sync must give up on it anyway.
        val release = CountDownLatch(1)
        val stuck = FakeSource(Source.Gmail) {
            release.await()
            items("late")
        }
        val syncer = Syncer(tasks, settings, listOf(stuck), FixedClock(clock.time), timeoutMs = 200)
        val started = System.nanoTime()
        val report = withContext(Dispatchers.Default) { syncer.sync() }
        release.countDown()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals("No answer in 0 s", report.failures[Source.Gmail])
    }

    @Test
    fun `a read given up on and still stuck isn't joined by another`() = runTest {
        val release = CountDownLatch(1)
        val stuck = FakeSource(Source.Anki) {
            release.await()
            items("late")
        }
        val syncer = Syncer(tasks, settings, listOf(stuck), FixedClock(clock.time), timeoutMs = 200)
        withContext(Dispatchers.Default) { syncer.sync() }
        val again = withContext(Dispatchers.Default) { syncer.sync() }
        assertEquals("Still waiting for the last read, which hasn't answered", again.failures[Source.Anki])
        assertEquals(1, stuck.seen.size)
        // Once it has wound down, the next sync reads again.
        release.countDown()
        Thread.sleep(300)
        withContext(Dispatchers.Default) { syncer.sync() }
        assertEquals(2, stuck.seen.size)
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

    @Test
    fun `every starred email, archived, is finished work to reward`() = runTest {
        val gmail = FakeSource(Source.Gmail) { SourceRead(listOf(Fetched("n", "I want to connect", Kind.Info), Fetched("a", "Sign the form", Kind.Admin))) }
        val syncer = syncer(listOf(gmail))
        syncer.sync()
        gmail.answer = { SourceRead(listOf(Fetched("n", "I want to connect", Kind.Info, done = true), Fetched("a", "Sign the form", Kind.Admin, done = true))) }
        syncer.sync()
        assertEquals(setOf("gmail:a", "gmail:n"), tasks.value.unrewarded.map { it.id }.toSet())
        // Finished all the same.
        assertEquals(Status.Done, tasks.value.tasks.first { it.id == "gmail:n" }.status)
    }

    @Test
    fun `a sync's completions are saved with the tasks' move to done, to be rewarded after`() = runTest {
        val teams = FakeSource(Source.Teams) { items("a", "b") }
        val syncer = syncer(listOf(teams))
        syncer.sync()
        teams.answer = { SourceRead(listOf(Fetched("a", "a", Kind.Homework), Fetched("b", "b", Kind.Homework, done = true))) }
        syncer.sync()
        assertEquals(listOf("teams:b"), tasks.value.unrewarded.map { it.id })
        assertEquals(Status.Done, tasks.value.tasks.first { it.id == "teams:b" }.status)
    }

    /** A phone keeping in step by commands: what it reads goes as what's seen ([seen]), merged here only as that's applied. */
    private fun TestScope.observing(sources: List<TaskSource>, seen: MutableList<List<SourceObserved>>) =
        Syncer(tasks, settings, sources, clock, readContext = StandardTestDispatcher(testScheduler), observing = { true }, observed = { reads -> seen += reads; SyncReport() })

    @Test
    fun `keeping in step by commands, what only the phone can read goes as one command a source, and what the server reads isn't read`() = runTest {
        val teams = FakeSource(Source.Teams) { items("essay") }
        val planner = FakeSource(Source.PowerPlanner) { items("worksheet") }
        val anki = FakeSource(Source.Anki) { error("AnkiDroid isn't there") }
        val seen = mutableListOf<List<SourceObserved>>()
        observing(listOf(teams, planner, anki), seen).sync()
        assertEquals(listOf(Source.Teams, Source.Anki), seen.map { it.single().source })
        assertEquals(listOf("essay"), seen[0].single().read?.items?.map { it.sourceId })
        assertEquals("AnkiDroid isn't there", seen[1].single().failed)
        assertTrue(planner.seen.isEmpty(), "Power Planner's the server's to read")
        assertTrue(tasks.value.tasks.isEmpty(), "nothing's merged till it's applied, here as on the server")
    }

    @Test
    fun `keeping in step by commands, Anki is read once Teams' command is applied, and sees the homework it just brought`() = runTest {
        val anki = FakeSource(Source.Anki) { SourceRead(emptyList(), ankiDay = AnkiDay("2026-10-08", 12, "Textbook 1::1.2")) }
        val teams = FakeSource(Source.Teams) { items("vocab") }
        lateinit var syncer: Syncer
        // Applied here as the command is, through the same syncer.
        syncer = Syncer(tasks, settings, listOf(anki, teams), clock, readContext = StandardTestDispatcher(testScheduler), observing = { true }, observed = { reads -> syncer.observe(reads) })
        syncer.sync()
        assertEquals(listOf("teams:vocab"), anki.seen.single().known.map { it.id })
        assertEquals(12L, tasks.value.ankiDay?.deckId)
    }

    @Test
    fun `a read whose command never went, the sync stopped first, is sent whole the next time`() = runTest {
        val teams = FakeSource(Source.Teams) { items("essay") }
        val seen = mutableListOf<List<SourceObserved>>()
        // The command waits its turn (another's being applied), and the sync's stopped meanwhile: nothing went.
        var gate: suspend () -> Unit = { awaitCancellation() }
        val syncer = Syncer(tasks, settings, listOf(teams), clock, readContext = StandardTestDispatcher(testScheduler), observing = { true }, observed = { reads -> gate(); seen += reads; SyncReport() })
        val stopped = launch { syncer.sync() }
        advanceUntilIdle()
        stopped.cancelAndJoin()
        gate = {}
        syncer.sync()
        assertEquals(listOf("essay"), seen.single().single().read?.items?.map { it.sourceId }, "sent whole, not as the same as before")
        // Nor where its command couldn't be put on record: the same read, tried again, goes whole.
        teams.answer = { items("essay", "poster") }
        gate = { throw IOException("The outbox couldn't be written") }
        assertFailsWith<IOException> { syncer.sync() }
        gate = {}
        syncer.sync()
        assertEquals(listOf("essay", "poster"), seen[1].single().read?.items?.map { it.sourceId })
    }

    @Test
    fun `a read the same as the last sent goes as its status alone, and a changed one whole`() = runTest {
        val teams = FakeSource(Source.Teams) { items("essay") }
        val seen = mutableListOf<List<SourceObserved>>()
        val syncer = observing(listOf(teams), seen)
        syncer.sync()
        syncer.sync()
        assertEquals(null, seen[1].single().read, "the same again")
        teams.answer = { items("essay", "poster") }
        syncer.sync()
        assertEquals(listOf("essay", "poster"), seen[2].single().read?.items?.map { it.sourceId })
    }

    @Test
    fun `what's seen and sent is merged as a read here is, and one read the same again keeps its tasks and says when it was read`() = runTest {
        val syncer = syncer(emptyList())
        syncer.observe(listOf(SourceObserved(Source.Teams, clock.time, items("essay"))))
        assertEquals(listOf("teams:essay"), tasks.value.tasks.map { it.id })
        clock.time += 60_000
        syncer.observe(listOf(SourceObserved(Source.Teams, clock.time)))
        assertEquals(listOf("teams:essay"), tasks.value.tasks.map { it.id }, "not taken as a read that lists nothing")
        assertEquals(Status.Open, tasks.value.tasks.single().status, "nor done, as gone from the list")
        assertEquals(clock.time, tasks.value.status(Source.Teams).lastSuccessAt)
        syncer.observe(listOf(SourceObserved(Source.Teams, clock.time, failed = "no answer")))
        assertEquals("no answer", tasks.value.status(Source.Teams).error)
        assertEquals(listOf("teams:essay"), tasks.value.tasks.map { it.id })
    }
}
