package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.CalendarState
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.OnlyMigration
import com.thomaswcode.decrastination.data.OutsideMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.TasksMigration
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * What the app reads, through the Sync service on temporary stores (docs/plan_v4/architecture.md §The rules): a sync
 * with one source failing, the Teams widget asked and its answers said, the calendar read as busy
 * time with what the rules can't judge asked about once, and what's read again from the start; what
 * Android does is a list ([Ports]). The syncer's own rules are `SyncerTest`'s.
 */
class SyncTest {
    private val dir: File = Files.createTempDirectory("sync-service").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val calendar = JsonStore(File(dir, "calendar.json"), CalendarState.serializer(), ::CalendarState, migrations = listOf(RuntimeSplit.CALENDAR))
    private val instructions = JsonStore(File(dir, "instructions.json"), InstructionState.serializer(), ::InstructionState, migrations = listOf(TasksMigration.INSTRUCTIONS, StepOrderMigration.INSTRUCTIONS, OnlyMigration.INSTRUCTIONS, OutsideMigration.INSTRUCTIONS))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ports = Ports()

    /** The events asked about in Felix's name, by key; those settled; the plan let go, the calendar watched, the plan made again. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val forgot = AtomicInteger()
    private val watched = AtomicInteger()

    private class FakeSource(override val source: Source, var answer: suspend (ReadContext) -> SourceRead) : TaskSource {
        override suspend fun read(context: ReadContext): SourceRead = answer(context)
    }

    private val teams = FakeSource(Source.Teams) { SourceRead(listOf(Fetched("essay", "Essay", Kind.Homework))) }
    private val gmail = FakeSource(Source.Gmail) { throw IOException("No network") }

    private val sync = Sync(
        tasks, settings, calendar, instructions, clock, scope, Syncer(tasks, settings, listOf(teams, gmail), clock), ports,
        forgetPlan = { forgot.incrementAndGet() },
        watchCalendar = { watched.incrementAndGet() },
        askAboutEvent = { key, _, _, _, _ -> asked += key },
        settleEvent = {},
        replan = {},
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a sync through the service with one source failing merges the others' tasks, keeps its own, and says why`() = runBlocking {
        val report = sync.sync()
        assertEquals(mapOf(Source.Gmail to "No network"), report.failures)
        assertEquals(listOf("teams:essay"), tasks.value.tasks.map { it.id })
        assertEquals("No network", tasks.value.status(Source.Gmail).error)
        assertNull(tasks.value.status(Source.Teams).error)
        // Only Teams, as the widget's change asks: Gmail isn't read.
        assertEquals(emptyMap(), sync.sync(setOf(Source.Teams)).failures)
    }

    @Test
    fun `refreshing all asks the Teams widget only on a tap, and why it didn't start is said where its state shows`() = runBlocking {
        sync.refreshAll(teams = false)
        assertEquals(0, ports.requests.get())
        assertEquals(listOf<Set<Source>?>(null), ports.syncs)
        ports.answer = TeamsAnswer(started = false, reason = "service_off")
        sync.refreshAll(teams = true)
        until("the refusal is said") { tasks.value.status(Source.Teams).note == "The Teams widget's sync service is off" }
        assertEquals(2, ports.syncs.size)
        // Each answer, in words.
        ports.answer = TeamsAnswer(started = false, reason = "busy")
        assertEquals(Sync.TEAMS_BUSY, sync.requestTeamsSync())
        ports.answer = TeamsAnswer(started = false, reason = "something else")
        assertEquals("The Teams widget didn't start a sync", sync.requestTeamsSync())
        ports.answer = null
        assertEquals("The Teams widget didn't answer", sync.requestTeamsSync())
        ports.answer = TeamsAnswer(started = true, reason = null)
        assertNull(sync.requestTeamsSync())
    }

    @Test
    fun `the calendar is read as busy time, and an all-day event the rules can't judge is asked about once`() = runBlocking {
        val dentist = CalendarEvent(1, "Dentist", Fixtures.at("2026-10-20T10:00"), Fixtures.at("2026-10-20T11:00"), allDay = false)
        val openDay = CalendarEvent(2, "Open day", utc("2026-10-24"), utc("2026-10-25"), allDay = true)
        ports.events = listOf(dentist, openDay)
        sync.refreshCalendar()
        assertEquals(1, watched.get())
        assertTrue(sync.calendarTime.busy.any { it.start == dentist.start && it.end == dentist.end }, "the dentist is busy time")
        assertEquals(listOf(EventJudge.key(openDay)), asked)
        assertEquals(setOf(EventJudge.key(openDay)), calendar.value.eventsAsked)
        assertTrue(forgot.get() > 0, "the plan made before the read let go")
        assertEquals(sync.calendarTime, sync.calendarState.value)
        assertEquals(1, ports.redraws.get())
        // Read again: asked once only.
        sync.refreshCalendar()
        assertEquals(1, asked.size)
        // A read that fails judges the events last read again; with no permission, nothing's busy.
        ports.failing = true
        sync.refreshCalendar()
        assertTrue(sync.calendarTime.busy.isNotEmpty())
        ports.allowed = false
        sync.refreshCalendar()
        assertEquals(emptyList(), sync.calendarTime.busy)
    }

    @Test
    fun `from the start, the calendars picked read the calendar again, Anki's settings read Anki, and the Teams widget is watched`() = runBlocking {
        sync.start(emptyFlow())
        until("watching the Teams widget") { ports.watching != null }
        untilSeen("the calendar read again, that one left out", { ports.excluded.any { 3L in it } }) { n -> settings.update { it.copy(calendarsExcluded = listOf(3L, 100L + n)) } }
        untilSeen("Anki read again", { setOf(Source.Anki) in ports.syncs }) { n -> settings.update { it.copy(ankiTextbook = 2 + n) } }
    }

    /**
     * [change] made, a new value each time, till [seen]: the start's watches drop the first value they
     * see, so a change made before they're listening is taken for it, as at the app's start.
     */
    private suspend fun untilSeen(what: String, seen: () -> Boolean, change: suspend (Int) -> Unit) {
        var n = 0
        while (!seen()) {
            check(n < 200) { "never: $what" }
            change(n++)
            delay(25)
        }
    }

    /** Epoch millis of [day]'s start in UTC, as an all-day event's are stored. */
    private fun utc(day: String) = Instant.parse("${day}T00:00:00Z").toEpochMilli()

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "never: $what" }
            Thread.sleep(10)
        }
    }

    /** What Android was asked: syncs, the Teams widget's requests and watch, the calendar's reads, the widget's redraws. */
    private class Ports : SyncPorts {
        val syncs: MutableList<Set<Source>?> = Collections.synchronizedList(mutableListOf())
        val requests = AtomicInteger()
        val redraws = AtomicInteger()
        val excluded: MutableList<Set<Long>> = Collections.synchronizedList(mutableListOf())
        @Volatile var answer: TeamsAnswer? = TeamsAnswer(started = true, reason = null)
        @Volatile var watching: (() -> Unit)? = null
        @Volatile var allowed = true
        @Volatile var failing = false
        @Volatile var events: List<CalendarEvent> = emptyList()

        override fun syncNow(sources: Set<Source>?) {
            syncs += sources
        }

        override fun requestTeamsSync(): TeamsAnswer? {
            requests.incrementAndGet()
            return answer
        }

        override fun watchTeams(onChange: () -> Unit) {
            watching = onChange
        }

        override fun calendarAllowed(): Boolean = allowed

        override fun readCalendar(from: Long, to: Long, excluded: Set<Long>): List<CalendarEvent> {
            this.excluded += excluded
            if (failing) throw IOException("The calendar couldn't be read")
            return events
        }

        override suspend fun redraw() {
            redraws.incrementAndGet()
        }
    }
}
