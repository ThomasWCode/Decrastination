package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Busy
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.EmptyTimetableMigration
import com.thomaswcode.decrastination.data.CalendarState
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.NotSameMigration
import com.thomaswcode.decrastination.data.OnlyMigration
import com.thomaswcode.decrastination.data.OutsideMigration
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.WeeklyMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.data.TasksMigration
import com.thomaswcode.decrastination.data.TeamsSkipMigration
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * What the app reads, through the Sync service on temporary stores (docs/plan_v4/architecture.md §The rules): a sync
 * with one source failing, the Teams widget asked and its answers said, the calendar read as busy
 * time with what the rules can't judge asked about once, and what's read again from the start; what
 * Android does is a list ([Ports]). The syncer's own rules are `SyncerTest`'s.
 */
class SyncTest {
    private val dir: File = Files.createTempDirectory("sync-service").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS, WeeklyMigration.TASKS, NotSameMigration.TASKS, EmptyTimetableMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME, NotSameMigration.RUNTIME, TeamsSkipMigration.RUNTIME))
    private val calendar = JsonStore(File(dir, "calendar.json"), CalendarState.serializer(), ::CalendarState, migrations = listOf(RuntimeSplit.CALENDAR))
    private val instructions = JsonStore(File(dir, "instructions.json"), InstructionState.serializer(), ::InstructionState, migrations = listOf(TasksMigration.INSTRUCTIONS, StepOrderMigration.INSTRUCTIONS, OnlyMigration.INSTRUCTIONS, OutsideMigration.INSTRUCTIONS, WeeklyMigration.INSTRUCTIONS))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ports = Ports()

    /** The events asked about in Felix's name, by key; those settled; the plan let go, the calendar watched, the plan made again. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** The chips each event was asked with, by key. */
    private val chips: MutableMap<String, List<String>> = Collections.synchronizedMap(mutableMapOf())
    private val forgot = AtomicInteger()
    private val replans = AtomicInteger()
    private val watched = AtomicInteger()

    private class FakeSource(override val source: Source, var answer: suspend (ReadContext) -> SourceRead) : TaskSource {
        override suspend fun read(context: ReadContext): SourceRead = answer(context)
    }

    private val teams = FakeSource(Source.Teams) { SourceRead(listOf(Fetched("essay", "Essay", Kind.Homework))) }
    private val gmail = FakeSource(Source.Gmail) { throw IOException("No network") }

    /** What asking an event waits for first, or how it fails: nothing, by default. */
    @Volatile
    private var beforeAsk: suspend (String) -> Unit = {}

    /** Whether asking puts the event on record as asked at once, as while mirroring; not where its command waits for another's to close. */
    @Volatile
    private var recordsAsked = true

    private val sync: Sync = Sync(
        settings, calendar, instructions, clock, scope, Syncer(tasks, settings, listOf(teams, gmail), clock), ports,
        forgetPlan = { forgot.incrementAndGet() },
        watchCalendar = { watched.incrementAndGet() },
        askAboutEvent = { key, _, _, _, replies ->
            beforeAsk(key)
            asked += key
            chips[key] = replies
            if (recordsAsked) sync.eventAsked(key)
        },
        settleEvent = {},
        replan = { replans.incrementAndGet() },
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
        // An automatic one says so, for the widget to read less; yours, and a refresh's, don't.
        assertNull(sync.requestTeamsSync(automatic = true))
        assertEquals(true, ports.automatic.last())
        assertTrue(ports.automatic.dropLast(1).none { it })
    }

    @Test
    fun `the widget's list is read soon after it changes, and its state at the start and at each change, a read with it where its sync time or note moved`() = runBlocking {
        val reads = AtomicInteger()
        teams.answer = { reads.incrementAndGet(); SourceRead(listOf(Fetched("essay", "Essay", Kind.Homework))) }
        ports.widget = TeamsWidget(lastSuccessAt = 1_000, noticedAt = 500)
        sync.start(emptyFlow())
        until("the widget's state read at the start") { sync.teamsWidget.value == TeamsWidget(lastSuccessAt = 1_000, noticedAt = 500) }
        until("watching the Teams widget") { ports.watching != null && ports.watchingState != null }
        // The list changed: read within a second, not five.
        val start = System.currentTimeMillis()
        ports.watching!!()
        until("Teams read after its list changed") { reads.get() == 1 }
        assertTrue(System.currentTimeMillis() - start < 2_000)
        // A notice alone: the state's read, Teams isn't.
        ports.widget = TeamsWidget(lastSuccessAt = 1_000, noticedAt = 2_000)
        ports.watchingState!!()
        until("the notice seen") { sync.teamsWidget.value?.noticedAt == 2_000L }
        delay(800)
        assertEquals(1, reads.get())
        // A sync ended (its list's rows the same), both lists seen in full, or the widget's note changed: read.
        ports.widget = TeamsWidget(lastSuccessAt = 3_000, noticedAt = 2_000)
        ports.watchingState!!()
        until("Teams read after a sync") { reads.get() == 2 }
        ports.widget = TeamsWidget(lastSuccessAt = 3_000, listsSeenAt = 4_000, noticedAt = 2_000)
        ports.watchingState!!()
        until("Teams read after both lists were seen") { reads.get() == 3 }
        ports.widget = TeamsWidget(lastSuccessAt = 3_000, listsSeenAt = 4_000, noticedAt = 2_000, note = "The Teams widget's last sync failed")
        ports.watchingState!!()
        until("Teams read after its note changed") { reads.get() == 4 }
        // The widget gone: what was read stands.
        ports.widget = null
        ports.watchingState!!()
        delay(300)
        assertEquals(4_000L, sync.teamsWidget.value?.syncedAt)
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
    fun `an instruction's answer about an event, laid here or pulled from the server, judges the calendar again and makes the plan again`() = runBlocking {
        // Laid on the server and pulled, an instruction reaches this phone as its store changing alone: the 30-minute event
        // said to take an hour is busy for the hour, not its own half hour, without the calendar itself changing.
        val over = CalendarEvent(5, "Check over", Fixtures.at("2026-10-20T21:00"), Fixtures.at("2026-10-20T21:30"), allDay = false)
        ports.events = listOf(over)
        sync.refreshCalendar()
        assertEquals(listOf(Busy(over.start, over.end)), sync.calendarTime.busy)
        // Pulled before the watch begins: it counts all the same, as the watch compares with what the last read judged with.
        val change = Change(ChangeType.EventTime, eventKey = "check over", eventTitle = "Check over", eventAnswer = "load:60")
        instructions.update { it.copy(instructions = it.instructions + Instruction("i1", "it takes an hour", at = clock.now(), state = InstructionStatus.Applied, changes = listOf(change), appliedAt = clock.now())) }
        sync.start(emptyFlow())
        withTimeout(5_000) { while (sync.calendarTime.busy != listOf(Busy(over.start, Fixtures.at("2026-10-20T22:00")))) delay(20) }
        withTimeout(5_000) { while (replans.get() == 0) delay(20) }
        // Taken back: its own half hour again.
        instructions.update { it.copy(instructions = emptyList()) }
        withTimeout(5_000) { while (sync.calendarTime.busy != listOf(Busy(over.start, over.end))) delay(20) }
    }

    @Test
    fun `a short tentative event is asked about with half of it, a long one with a few hours`() = runBlocking {
        // Q149: Felix asks about a timed event marked tentative whatever its length; under four hours, half of it.
        val club = CalendarEvent(3, "Revision club", Fixtures.at("2026-10-21T18:00"), Fixtures.at("2026-10-21T19:30"), allDay = false, description = "Tentative")
        val hire = CalendarEvent(4, "Van hire", Fixtures.at("2026-10-22T09:00"), Fixtures.at("2026-10-22T18:00"), allDay = false)
        ports.events = listOf(club, hire)
        sync.refreshCalendar()
        assertEquals(listOf("all of it", "half of it", "none"), chips["revision club"])
        assertEquals(listOf("all of it", "a few hours", "none"), chips["van hire"])
    }

    @Test
    fun `the calendar's known once read, or once it can't be read at all, and not from the nothing a start has or a first read that failed`() = runBlocking {
        assertFalse(sync.calendarKnown.value, "a start's nothing isn't what the calendar holds")
        ports.failing = true
        sync.refreshCalendar()
        assertFalse(sync.calendarKnown.value, "nor a first read that failed")
        ports.failing = false
        sync.refreshCalendar()
        assertTrue(sync.calendarKnown.value)
        // No permission: nothing in it's to be planned by, said as known.
        val other = Sync(settings, calendar, instructions, clock, scope, Syncer(tasks, settings, emptyList(), clock), Ports().apply { allowed = false }, {}, {}, { _, _, _, _, _ -> }, {}, {})
        other.refreshCalendar()
        assertTrue(other.calendarKnown.value)
    }

    @Test
    fun `an event's asked about once the calendar's let go, so a read inside a command that asking waits for isn't stuck behind it`() = runBlocking {
        val openDay = CalendarEvent(2, "Open day", utc("2026-10-24"), utc("2026-10-25"), allDay = true)
        ports.events = listOf(openDay)
        // The gate a command holds while it's applied: asking goes as a command once the phone sends them, so waits for it.
        val gate = Mutex()
        val waiting = CompletableDeferred<Unit>()
        beforeAsk = {
            waiting.complete(Unit)
            gate.withLock {}
        }
        gate.lock()
        val first = launch(Dispatchers.Default) { sync.refreshCalendar() }
        waiting.await()
        // The command holding it reads the calendar again (a sync's after-work does): it isn't stuck, nor asks again.
        withTimeout(5_000) { sync.refreshCalendar() }
        gate.unlock()
        first.join()
        assertEquals(listOf(EventJudge.key(openDay)), asked)
        assertEquals(setOf(EventJudge.key(openDay)), calendar.value.eventsAsked)
    }

    @Test
    fun `an event whose question waits for another command to close isn't on record as asked till it's made, nor asked again meanwhile`() = runBlocking {
        val openDay = CalendarEvent(2, "Open day", utc("2026-10-24"), utc("2026-10-25"), allDay = true)
        ports.events = listOf(openDay)
        recordsAsked = false
        sync.refreshCalendar()
        assertEquals(emptySet(), calendar.value.eventsAsked, "a stop now leaves it to be asked again")
        sync.refreshCalendar()
        assertEquals(listOf(EventJudge.key(openDay)), asked, "not asked twice meanwhile")
        // Its command made, as the other's closed: on record.
        sync.eventAsked(EventJudge.key(openDay))
        assertEquals(setOf(EventJudge.key(openDay)), calendar.value.eventsAsked)
        sync.refreshCalendar()
        assertEquals(1, asked.size)
    }

    @Test
    fun `an event whose question was never asked, its asking failed, isn't on record as asked, so it's asked the next time`() = runBlocking {
        val openDay = CalendarEvent(2, "Open day", utc("2026-10-24"), utc("2026-10-25"), allDay = true)
        ports.events = listOf(openDay)
        beforeAsk = { throw IOException("The chat couldn't be written") }
        assertFailsWith<IOException> { sync.refreshCalendar() }
        assertEquals(emptySet(), calendar.value.eventsAsked)
        beforeAsk = {}
        sync.refreshCalendar()
        assertEquals(listOf(EventJudge.key(openDay)), asked)
        assertEquals(setOf(EventJudge.key(openDay)), calendar.value.eventsAsked)
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
        val automatic: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        @Volatile var watching: (() -> Unit)? = null
        @Volatile var watchingState: (() -> Unit)? = null
        @Volatile var widget: TeamsWidget? = null
        @Volatile var allowed = true
        @Volatile var failing = false
        @Volatile var events: List<CalendarEvent> = emptyList()

        override fun syncNow(sources: Set<Source>?) {
            syncs += sources
        }

        override fun requestTeamsSync(automatic: Boolean): TeamsAnswer? {
            requests.incrementAndGet()
            this.automatic += automatic
            return answer
        }

        override fun watchTeams(onList: () -> Unit, onState: () -> Unit) {
            watching = onList
            watchingState = onState
        }

        override fun teamsWidget(): TeamsWidget? = widget

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
