package com.thomaswcode.decrastination.calendar

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.calendar.SlotCalendarDiff.Desired
import com.thomaswcode.decrastination.calendar.SlotCalendarDiff.Existing
import com.thomaswcode.decrastination.core.BusyBlock
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.CalendarState
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.StepOrderMigration
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.data.TasksMigration
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The plan written to the calendar (NF-07) through the writer, on temporary stores, the calendar
 * provider a calendar in memory ([Calendar]): the copy and move rules as a write meets them, beyond
 * [SlotCalendarDiff]'s own; a calendar no longer written to cleared; the watch; your answers.
 */
class CalendarWriterTest {
    private val dir: File = Files.createTempDirectory("calendar").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON)
    private val tasks = JsonStore(File(dir, "tasks.json"), TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val model = JsonStore(File(dir, "model.json"), ModelState.serializer(), ::ModelState)
    private val versions = JsonStore(File(dir, "calendar.json"), CalendarState.serializer(), ::CalendarState, migrations = listOf(RuntimeSplit.CALENDAR))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calendar = Calendar()

    /** The changes of yours a move made (its words, the change); while [changesFail], none can be. */
    private val changes: MutableList<Pair<String, Change>> = Collections.synchronizedList(mutableListOf())
    @Volatile private var changesFail = false

    /** Each move written back, by its slot's URI, and why. */
    private val refused: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** Your busy times, as the instructions in use give them; each one's event you deleted or changed, put back, by its URI, and whether deleted. */
    @Volatile private var busy: List<BusyBlock> = emptyList()
    private val putBack: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val replans = AtomicInteger()
    private val settled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val rereads = AtomicInteger()

    /** A writer as AppGraph makes one at a start, on these stores and this calendar, plans quiet after [quietMs]. */
    private fun writer(quietMs: Long = 100) = CalendarWriter(
        tasks, settings, model, versions, clock, scope, calendar,
        sayRefused = { move, _, why -> refused += move.desired.uri to why },
        addOwnChange = { _, text, _, change ->
            check(!changesFail) { "stopped before the change was in" }
            changes += text to change
        },
        replan = { replans.incrementAndGet() },
        settleEvent = { settled += it },
        reread = { rereads.incrementAndGet() },
        busyTimes = { from, to -> busy.filter { it.date in from..to } },
        sayPutBack = { want, deleted, why ->
            check(why == CalendarWriter.BUSY_CHANGED)
            putBack += want.uri to deleted
        },
        quietMs = quietMs,
    )

    private val writer = writer()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val today = clock.today()

    private fun at(time: String): Long = Fixtures.at("2026-10-19T$time")

    private fun chunk(taskId: String, title: String, part: Int = 1) = Chunk(
        taskId = taskId, source = Source.Teams, kind = Kind.Homework, title = title, step = null, minutes = 30, dueAt = Fixtures.at("2026-10-22T23:59"),
        deadline = Fixtures.at("2026-10-22T23:59"), soft = false, overdue = false, dueToday = false, behind = false, part = part, parts = 2, taskMinutes = 60, why = Why.Spread,
    )

    private fun work(chunk: Chunk, start: String) = Slot(SlotKind.Work, chunk, at(start), at(start) + 30 * 60_000L, WindowKind.Evening, pinned = false)

    /** Today's plan holding [slots] alone. */
    private fun plan(vararg slots: Slot) = Plan(clock.time, today, listOf(DayBucket(today, 300, slots.mapNotNull { it.chunk }, slots.toList())), emptyList())

    private fun task(id: String, due: Long = Fixtures.at("2026-10-22T23:59")) =
        TaskItem(id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = due, kind = Kind.Homework, sourceEffortMin = 60, firstSeenAt = clock.time, lastSeenAt = clock.time)

    private fun pickCalendar(id: Long) = runBlocking {
        settings.update { it.copy(calendarWriteId = id) }
        versions.update { it.copy(calendarWrittenTo = id) }
    }

    @Test
    fun `a plan's slots are written as events, and the same plan again asks the calendar nothing till it changes`() = runBlocking {
        pickCalendar(7)
        val essay = work(chunk("teams:essay", "Essay"), "17:00")
        val made = plan(essay)
        writer.watchCalendar()
        writer.writeSlots(made)
        val uri = SlotCalendarDiff.desired(made, LONDON).single().uri
        assertEquals(listOf(uri to at("17:00")), calendar.events.map { it.uri to it.start })
        assertEquals(mapOf(uri to at("17:00")), versions.value.calendarWritten)
        assertEquals(1, versions.value.written.size)
        assertEquals(2 to 1, calendar.reads to calendar.batches)
        // As written, and nothing changed in the calendar since (AR-08): not read again.
        writer.writeSlots(made)
        assertEquals(2 to 1, calendar.reads to calendar.batches)
        // A change there (the app's own write, or yours) has it read again: nothing differs, so nothing's written.
        calendar.changed()
        writer.writeSlots(made)
        assertEquals(4 to 1, calendar.reads to calendar.batches)
        // Nothing written without the permission, nor to no calendar.
        calendar.writable = false
        calendar.changed()
        writer.writeSlots(plan(work(chunk("teams:essay", "Essay"), "18:00")))
        assertEquals(4 to 1, calendar.reads to calendar.batches)
    }

    @Test
    fun `a break outside's event is known by its own time, one under the URI of its start deleted once and its own inserted (D-113)`() = runBlocking {
        pickCalendar(7)
        val start = at("13:00")
        val outside = Slot(SlotKind.Outside, null, start, start + 20 * 60_000L, WindowKind.Evening, pinned = false, outsideAt = 780)
        // As an earlier build wrote it, under the URI of its start.
        val old = calendar.add("${SlotCalendarDiff.URI_PREFIX}$today/outside:$start", Desired("", "Outside", SlotCalendarDiff.OUTSIDE, start, start + 20 * 60_000L, null))
        writer.writeSlots(plan(outside))
        assertEquals(listOf("${SlotCalendarDiff.URI_PREFIX}$today/outside:780"), calendar.events.map { it.uri })
        assertTrue(calendar.events.none { it.id == old })
        // Laid elsewhere, it's the same event moved: updated, not one deleted and another made.
        val id = calendar.events.single().id
        calendar.changed()
        writer.writeSlots(plan(outside.copy(start = at("14:00"), end = at("14:20"))))
        assertEquals(listOf(id to at("14:00")), calendar.events.map { it.id to it.start })
    }

    @Test
    fun `a burst of plans is one write, of the last, once plans have been quiet`() = runBlocking {
        pickCalendar(7)
        writer.start()
        until("the write is listening") { writer.listening.value > 0 }
        writer.planned(plan(work(chunk("teams:essay", "Essay"), "17:00")))
        writer.planned(plan(work(chunk("teams:essay", "Essay"), "18:00")))
        until("the write") { calendar.batches > 0 }
        assertEquals(1, calendar.batches)
        assertEquals(listOf(at("18:00")), calendar.events.map { it.start })
    }

    @Test
    fun `a copy that lost the app's marks goes, and an event of yours worded like one stays, never taken for a copy`() = runBlocking {
        pickCalendar(7)
        val essay = work(chunk("teams:essay", "Essay"), "17:00")
        val maths = work(chunk("teams:maths", "Maths"), "18:00")
        val made = plan(essay, maths)
        val (essayEvent, mathsEvent) = SlotCalendarDiff.desired(made, LONDON)
        // Yours, in the app's words at the essay slot's title and times, there before the app wrote any.
        val yours = calendar.add(null, essayEvent)
        writer.watchCalendar()
        writer.writeSlots(made)
        // The maths event's version remembered; the essay's, which yours already has, not.
        assertEquals(listOf(SlotCalendarDiff.version(7, mathsEvent.title, mathsEvent.start, mathsEvent.end)), versions.value.written)
        // The calendar's sync brings the maths event back without the app's marks.
        val copy = calendar.add(null, mathsEvent)
        calendar.changed()
        writer.writeSlots(made)
        val left = calendar.events.map { it.id }
        assertTrue(copy !in left, "the copy is deleted")
        assertTrue(yours in left, "yours is kept")
        assertEquals(setOf(essayEvent.uri, mathsEvent.uri, null), calendar.events.map { it.uri }.toSet())
    }

    @Test
    fun `a slot you moved there is your time for its task's next piece, counted as written once that's in`() = runBlocking {
        pickCalendar(7)
        tasks.update { it.copy(tasks = listOf(task("essay"))) }
        val made = plan(work(chunk("teams:essay", "Essay"), "17:00"))
        val uri = SlotCalendarDiff.desired(made, LONDON).single().uri
        writer.watchCalendar()
        writer.writeSlots(made)
        // You drag it to 18:00; the app stops before your change is in.
        calendar.move(uri, at("18:00"))
        changesFail = true
        assertFailsWith<IllegalStateException> { writer.writeSlots(made) }
        assertEquals(mapOf(uri to at("17:00")), versions.value.calendarWritten, "not counted as written: found a move again")
        assertEquals(at("18:00"), calendar.events.single().start, "not put back")
        // The next start finds it a move again, and takes it as your time.
        changesFail = false
        writer().writeSlots(made)
        val (text, change) = changes.single()
        assertEquals("Moved in the calendar", text)
        assertEquals(Change(ChangeType.AtTime, taskId = "teams:essay", date = "2026-10-19", startMin = 18 * 60, time = at("18:00")), change)
        assertEquals(mapOf(uri to at("18:00")), versions.value.calendarWritten)
        assertEquals(at("18:00"), calendar.events.single().start)
        assertEquals(emptyList(), refused)
    }

    @Test
    fun `a move the plan can't take is written back, and you're told why`() = runBlocking {
        pickCalendar(7)
        // The essay is due at 19:00 today; the old task has gone from the list.
        tasks.update { it.copy(tasks = listOf(task("essay", due = at("19:00")))) }
        val made = plan(work(chunk("teams:essay", "Essay"), "17:00"), work(chunk("teams:old", "Old"), "17:40"))
        val (essay, old) = SlotCalendarDiff.desired(made, LONDON).map { it.uri }
        writer.watchCalendar()
        writer.writeSlots(made)
        calendar.move(essay, at("19:30"))
        calendar.move(old, at("20:00"))
        writer.writeSlots(made)
        assertEquals(listOf(essay to "it's due before then", old to "its task is no longer there"), refused.sortedBy { it.first })
        assertEquals(mapOf(essay to at("17:00"), old to at("17:40")), calendar.events.associate { it.uri!! to it.start }, "both written back")
        assertEquals(emptyList(), changes)
        assertEquals(mapOf(essay to at("17:00"), old to at("17:40")), versions.value.calendarWritten)
    }

    @Test
    fun `a break outside moved in the calendar is written back, and you're told it's moved by asking Felix (D-116)`() = runBlocking {
        pickCalendar(7)
        val made = plan(Slot(SlotKind.Outside, null, at("17:00"), at("17:20"), WindowKind.Evening, pinned = false, outsideAt = 17 * 60))
        val uri = SlotCalendarDiff.desired(made, LONDON).single().uri
        writer.watchCalendar()
        writer.writeSlots(made)
        calendar.move(uri, at("18:00"))
        writer.writeSlots(made)
        assertEquals(listOf(uri to "a break outside is moved by asking Felix"), refused)
        assertEquals(at("17:00"), calendar.events.single().start, "written back")
        assertEquals(emptyList(), changes)
        assertEquals(mapOf(uri to at("17:00")), versions.value.calendarWritten)
    }

    @Test
    fun `your busy times are written as events of their own, and one moved or deleted by hand is put back and said (D-117)`() = runBlocking {
        pickCalendar(7)
        busy = listOf(BusyBlock(today, 18 * 60, 19 * 60 + 30, "Football"), BusyBlock(today.plusDays(1), 17 * 60, 18 * 60, null), BusyBlock(today.plusDays(7), 17 * 60, 18 * 60, null))
        val made = plan(work(chunk("teams:essay", "Essay"), "17:00"))
        writer.watchCalendar()
        writer.writeSlots(made)
        val football = "decrastination://slot/2026-10-19/busy:1080-1170"
        // Today's and tomorrow's beside the slot; a week on, not yet.
        assertEquals(mapOf(football to "Busy: Football", "decrastination://slot/2026-10-20/busy:1020-1080" to "Busy"), calendar.events.filter { it.description == SlotCalendarDiff.BUSY }.associate { it.uri!! to it.title })
        // Moved by hand: put back, and said.
        calendar.move(football, at("20:00"))
        writer.writeSlots(made)
        assertEquals(listOf(football to CalendarWriter.BUSY_CHANGED), refused)
        assertEquals(at("18:00"), calendar.events.single { it.uri == football }.start)
        // Deleted by hand: written again, and said.
        calendar.delete(football)
        writer.writeSlots(made)
        assertEquals(listOf(football to true), putBack)
        assertEquals(at("18:00"), calendar.events.single { it.uri == football }.start)
        // Stretched by hand, its start left: put back as it was, and said.
        calendar.stretch(football, at("20:00"))
        writer.writeSlots(made)
        assertEquals(listOf(football to true, football to false), putBack)
        assertEquals(at("19:30"), calendar.events.single { it.uri == football }.end)
        assertEquals(emptyList(), changes)
        // Taken back: its events go, unsaid.
        busy = emptyList()
        writer.writeSlots(made)
        assertTrue(calendar.events.none { it.description == SlotCalendarDiff.BUSY })
        assertEquals(2, putBack.size)
    }

    @Test
    fun `a calendar no longer written to is cleared of the app's events and their copies, tried again till it is`() = runBlocking {
        pickCalendar(7)
        val made = plan(work(chunk("teams:essay", "Essay"), "17:00"))
        val event = SlotCalendarDiff.desired(made, LONDON).single()
        writer.writeSlots(made)
        val copy = calendar.add(null, event)
        val yours = calendar.add(null, event.copy(title = "Essay club"))
        // Picked another while the calendar can't be written: kept on the list to clear.
        calendar.writable = false
        writer.start()
        settings.update { it.copy(calendarWriteId = 8) }
        until("the switch is seen") { replans.get() == 1 }
        assertEquals(listOf(7L), versions.value.calendarsToClear)
        assertEquals(8L, versions.value.calendarWrittenTo)
        assertEquals(emptyMap<String, Long>(), versions.value.calendarWritten)
        // Allowed again: the next write clears it first, the app's event and its copy, not yours.
        calendar.writable = true
        writer.writeSlots(made)
        assertEquals(emptyList(), versions.value.calendarsToClear)
        assertEquals(listOf(yours), calendar.events.filter { it.calendarId == 7L }.map { it.id })
        assertTrue(copy !in calendar.events.map { it.id })
        assertEquals(listOf(event.uri), calendar.events.filter { it.calendarId == 8L }.map { it.uri })
    }

    @Test
    fun `a write under way for the calendar switched from goes in before that's cleared, so none of its events is left there`() = runBlocking {
        pickCalendar(7)
        val made = plan(work(chunk("teams:essay", "Essay"), "17:00"))
        val release = CountDownLatch(1)
        calendar.hold = release
        writer.start()
        val writing = async(Dispatchers.Default) { writer.writeSlots(made) }
        assertTrue(withContext(Dispatchers.IO) { calendar.applying.await(10, TimeUnit.SECONDS) })
        settings.update { it.copy(calendarWriteId = 8) }
        // The switch waits for the write: the calendar switched from isn't cleared while its events are still going in.
        assertNull(withTimeoutOrNull(500) { versions.state.first { it.calendarWrittenTo == 8L } })
        release.countDown()
        writing.await()
        until("the switch is seen") { replans.get() == 1 }
        assertEquals(emptyList(), versions.value.calendarsToClear)
        assertTrue(calendar.events.none { it.calendarId == 7L })
    }

    @Test
    fun `the calendar is watched once it may be read, and each change it announces is passed on`() = runBlocking {
        calendar.readable = false
        writer.watchCalendar()
        assertEquals(0, calendar.watches)
        calendar.readable = true
        writer.watchCalendar()
        writer.watchCalendar()
        assertEquals(1, calendar.watches)
        val announced = async(start = CoroutineStart.UNDISPATCHED) { writer.calendarChanged.first() }
        calendar.changed()
        withTimeout(5_000) { announced.await() }
    }

    @Test
    fun `your answer about an event is kept for every event of its name, his question settled and the calendar read again`() = runBlocking {
        writer.answer("open day", "busy")
        assertEquals(mapOf("open day" to "busy"), versions.value.eventAnswers)
        assertEquals(listOf("open day"), settled)
        assertEquals(1, rereads.get())
        // Forgotten: for the rules to judge again.
        writer.answer("open day", null)
        assertEquals(emptyMap<String, String>(), versions.value.eventAnswers)
        assertEquals(2, rereads.get())
    }

    private fun FixedClock.today() = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate()

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "never: $what" }
            Thread.sleep(10)
        }
    }

    /** A calendar in memory, as the provider has it: the app's events (with a URI) and others' (without), and what was asked of it. */
    private class Calendar : CalendarProvider {
        data class Event(val id: Long, val calendarId: Long, val uri: String?, val start: Long, val end: Long, val title: String, val description: String)

        val events: MutableList<Event> = Collections.synchronizedList(mutableListOf())
        @Volatile var writable = true
        @Volatile var readable = true
        @Volatile var reads = 0
        @Volatile var batches = 0
        @Volatile var watches = 0
        @Volatile private var watching: (() -> Unit)? = null
        private var next = 100L

        /** While set, an insert into calendar 7 waits for it, saying first that it's waiting ([applying]). */
        @Volatile var hold: CountDownLatch? = null
        val applying = CountDownLatch(1)

        override fun allowed(): Boolean = writable

        override fun existing(calendarId: Long, from: Long, to: Long, uris: List<String>): List<Existing> {
            reads++
            return events.filter { it.calendarId == calendarId && it.uri != null && (it.start in from until to || it.uri in uris) }
                .map { Existing(it.id, it.uri!!, it.start, it.end, it.title, it.description) }
        }

        override fun unmarked(calendarId: Long): List<Existing> {
            reads++
            return events.filter { it.calendarId == calendarId && it.uri == null && SlotCalendarDiff.looksWritten(it.description) }
                .map { Existing(it.id, "", it.start, it.end, it.title, it.description, marked = false) }
        }

        override fun apply(calendarId: Long, zone: ZoneId, inserts: List<Desired>, updates: List<Pair<Long, Desired>>, deletes: List<Long>) {
            if (inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()) return
            hold?.takeIf { calendarId == 7L && inserts.isNotEmpty() }?.let {
                applying.countDown()
                check(it.await(10, TimeUnit.SECONDS))
            }
            batches++
            inserts.forEach { add(it.uri, it, calendarId) }
            updates.forEach { (id, want) -> events.replaceAll { if (it.id == id) it.copy(uri = want.uri, start = want.start, end = want.end, title = want.title, description = want.description) else it } }
            events.removeAll { it.id in deletes }
        }

        override fun deleteOwn(calendarId: Long) {
            events.removeAll { it.calendarId == calendarId && it.uri != null }
        }

        override fun watch(onChange: () -> Unit): Boolean {
            if (!readable) return false
            watches++
            watching = onChange
            return true
        }

        /** An event of [want]'s words and times in [calendarId], the app's if it has [uri]; its id. */
        fun add(uri: String?, want: Desired, calendarId: Long = 7): Long = (next++).also { events += Event(it, calendarId, uri, want.start, want.end, want.title, want.description) }

        /** The app's event of [uri] dragged by you to [start], keeping its length; the calendar says so. */
        fun move(uri: String, start: Long) {
            events.replaceAll { if (it.uri == uri) it.copy(start = start, end = start + (it.end - it.start)) else it }
            changed()
        }

        /** The app's event of [uri] stretched by you to end at [end], its start left; the calendar says so. */
        fun stretch(uri: String, end: Long) {
            events.replaceAll { if (it.uri == uri) it.copy(end = end) else it }
            changed()
        }

        /** The app's event of [uri] deleted by you; the calendar says so. */
        fun delete(uri: String) {
            events.removeAll { it.uri == uri }
            changed()
        }

        /** The calendar announcing a change. */
        fun changed() {
            watching?.invoke()
        }
    }
}
