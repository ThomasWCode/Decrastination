package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.StopTimer
import com.thomaswcode.decrastination.sync.protocol.UndoKeep
import com.thomaswcode.decrastination.ui.DayModel
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day as the website gets it (W-14): the phone's model in the phone's words, with the minutes it's laid out by. */
class DayViewTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val evening = listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    private fun plan() = Planner.plan(Planner.Input(listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = mapOf(monday to evening)))

    private fun view(
        date: LocalDate = monday,
        events: List<CalendarEvent> = emptyList(),
        records: List<SlotRecord> = emptyList(),
        now: Long = Fixtures.at("2026-10-12T17:10"),
        judge: (CalendarEvent) -> EventJudge.Judgement = { EventJudge.Judgement.Busy },
        timer: PieceTimer? = null,
        writable: Boolean = false,
    ) = DayViews.of(plan(), date, events, records, evening, now, LONDON, emptyList(), judge, emptyMap(), asOf = 5L, timer = timer, writable = writable)

    @Test
    fun `today has its moment, its next thing and each piece by the minute, with the phone's words`() {
        val day = view()
        assertTrue(day.today)
        assertEquals("Monday 12 October", day.title)
        assertEquals(17 * 60 + 10, day.nowMin)
        assertEquals(listOf(WindowView("Your time", 17 * 60, 22 * 60)), day.windows)
        assertEquals(listOf("now", "ahead"), day.blocks.map { it.state })
        assertEquals(listOf("piece", "piece"), day.blocks.map { it.kind })
        assertEquals(17 * 60, day.blocks.first().startMin)
        assertTrue(day.blocks.first().endMin > day.blocks.first().startMin)
        assertEquals("a", assertNotNull(day.next).label.take(1))
        assertEquals(5L, day.asOf)
    }

    private val tasks = listOf(task("a", 30), task("b", 30)).associateBy { it.id }

    /** Today at 17:10, the tasks and their answers given, writable or not. */
    private fun sheets(events: List<CalendarEvent> = emptyList(), records: List<SlotRecord> = emptyList(), answers: Map<String, String> = emptyMap(), instructed: Set<String> = emptySet(), writable: Boolean = true) =
        DayViews.of(plan(), monday, events, records, evening, Fixtures.at("2026-10-12T17:10"), LONDON, emptyList(), { EventJudge.judge(it, answers) }, emptyMap(), asOf = 5L, writable = writable, tasks = tasks, answers = answers, instructed = instructed)

    @Test
    fun `a piece opens its own sheet, its times and why, its buttons on its own key, its task's sheet a step away`() {
        val day = sheets()
        val (first, second) = day.blocks
        val sheet = assertNotNull(second.sheet)
        assertEquals("piece", sheet.kind)
        assertEquals(second.title, sheet.title)
        assertEquals(second.times, sheet.lines.first())
        val done = sheet.actions.single { it.label == "Done" }
        // Done on the piece clicked, not the task's next piece as the task's sheet has it.
        val key = plan().todaySlots.first { it.chunk?.taskId == "teams:b" }.chunk!!.key
        assertEquals(TaskAsk(TaskAsk.DONE, at = Fixtures.at("2026-10-12T17:10"), key = key), done.ask)
        assertEquals(UndoKeep(key, Fixtures.at("2026-10-12T17:10")), done.undo)
        assertEquals("teams:b", sheet.taskId)
        assertTrue(sheet.taskSheet)
        assertEquals(About(taskId = "teams:b", taskTitle = "b"), sheet.about)
        assertEquals(listOf("Start", "Done", "Later today"), assertNotNull(first.sheet).actions.map { it.label })
    }

    @Test
    fun `mirroring, a block's sheet opens to read, its buttons absent`() {
        val sheet = assertNotNull(sheets(writable = false).blocks.first().sheet)
        assertTrue(sheet.actions.isEmpty())
        assertTrue(sheet.lines.isNotEmpty())
    }

    @Test
    fun `a missed slot's sheet has what MissedActions gives it, Done on its piece, and a kept one has none here yet`() {
        val piece = plan().todaySlots.first().chunk!!
        val missed = SlotRecord(date = "2026-10-12", key = piece.key, taskId = piece.taskId, kind = Kind.Homework, label = "a", plannedMin = 30, start = Fixtures.at("2026-10-12T16:00"), end = Fixtures.at("2026-10-12T16:30"), window = WindowKind.Evening, outcome = SlotOutcome.Missed, workedMin = 0, at = Fixtures.at("2026-10-12T16:30"))
        val day = sheets(records = listOf(missed))
        val sheet = assertNotNull(day.blocks.single { it.kind == "record" }.sheet)
        assertEquals("missed", sheet.kind)
        assertEquals("Missed, 16:00–16:30", sheet.lines.first())
        assertEquals(listOf("Done"), sheet.actions.map { it.label })
        assertEquals(piece.key, sheet.actions.single().ask?.key)
        assertNotNull(sheet.about)
        assertNull(sheets(records = listOf(missed.copy(outcome = SlotOutcome.Kept))).blocks.single { it.kind == "record" }.sheet)
    }

    @Test
    fun `an event's sheet says how the plan counts it and offers its answers by its length, kept as your quick change, none where your instruction says`() {
        val event = CalendarEvent(7L, "Football", Fixtures.at("2026-10-12T18:00"), Fixtures.at("2026-10-12T19:30"), allDay = false, location = "The park")
        val sheet = assertNotNull(sheets(events = listOf(event)).blocks.single { it.kind == "event" }.sheet)
        assertEquals("event", sheet.kind)
        assertTrue("The park" in sheet.lines)
        assertTrue(sheet.lines.any { it.startsWith("The plan counts it: ") })
        assertEquals(CalendarTime.answers(event).map { it.first }, sheet.actions.map { it.label })
        val change = (sheet.actions.first().action as AddOwnChange).change
        assertEquals(ChangeType.EventTime, change.type)
        assertEquals(EventJudge.key(event), change.eventKey)
        assertEquals("For every event of that name.", sheet.note)
        assertEquals(About(eventKey = EventJudge.key(event), eventTitle = "Football", eventStart = event.start), sheet.about)
        val told = assertNotNull(sheets(events = listOf(event), instructed = setOf(EventJudge.key(event))).blocks.single { it.kind == "event" }.sheet)
        assertTrue(told.actions.isEmpty())
        assertTrue(told.lines.any { it.startsWith("Your instruction says how it counts") })
        // An all-day one has its sheet too.
        val holiday = CalendarEvent(8L, "Half term", Fixtures.at("2026-10-12T00:00"), Fixtures.at("2026-10-13T00:00"), allDay = true)
        assertEquals("All day", assertNotNull(sheets(events = listOf(holiday)).allDay.single().sheet).lines.first())
    }

    @Test
    fun `the page's told the shortest a block's drawn, as they're laid out by it`() {
        assertEquals(24, view().shortest)
    }

    @Test
    fun `mirroring, there's nothing to do and nothing to drag`() {
        val day = view()
        assertFalse(day.canChange)
        assertNull(day.form)
        assertTrue(assertNotNull(day.next).actions.isEmpty())
        assertNull(day.next?.taskId)
        assertTrue(day.blocks.all { it.drag == null })
    }

    @Test
    fun `each piece can be dragged, whatever its state, and opens its task`() {
        val day = view(writable = true)
        assertTrue(day.canChange)
        assertNotNull(day.form)
        val (now, ahead) = day.blocks
        assertEquals("now", now.state)
        assertNotNull(now.drag)
        assertEquals("teams:a", now.taskId)
        assertEquals(plan().todaySlots.first().key, now.drag?.key)
        assertEquals(30, ahead.drag?.minutes)
    }

    @Test
    fun `a break outside can be dragged only before it's begun, and opens no task`() {
        val out = DayModel.Block(Fixtures.at("2026-10-12T18:00"), Fixtures.at("2026-10-12T18:20"), DayModel.Row.Planned(Slot(SlotKind.Outside, null, Fixtures.at("2026-10-12T18:00"), Fixtures.at("2026-10-12T18:20"), WindowKind.Evening, false, outsideAt = 18 * 60), DayModel.State.Ahead))
        val ahead = DayViews.block(out, monday, LONDON, { EventJudge.Judgement.Busy }, emptyMap(), writable = true)
        assertEquals(20, ahead?.drag?.minutes)
        assertNull(ahead?.taskId)
        assertNull(DayViews.block(out, monday, LONDON, { EventJudge.Judgement.Busy }, emptyMap(), writable = false)?.drag, "nor while mirroring")
        val begun = out.copy(item = (out.item as DayModel.Row.Planned).copy(state = DayModel.State.Now))
        assertNull(DayViews.block(begun, monday, LONDON, { EventJudge.Judgement.Busy }, emptyMap(), writable = true)?.drag)
    }

    @Test
    fun `what's next can be started, done or put off`() {
        val next = assertNotNull(view(writable = true).next)
        assertEquals("teams:a", next.taskId)
        assertEquals(listOf("Start", "Done", "Later today"), next.actions.map { it.label })
        assertEquals(TaskAsk.TIMER, next.actions[0].ask?.kind)
        assertEquals(TaskAsk.DONE, next.actions[1].ask?.kind)
        assertNotNull(next.actions[1].undo)
    }

    @Test
    fun `a timer on what's next is stopped there, and says its time till its next whole minute`() {
        val now = Fixtures.at("2026-10-12T17:10")
        val key = assertNotNull(view(writable = true).next?.actions?.first()?.ask?.key)
        val running = PieceTimer(key, "teams:a", null, null, "A piece", 30, now - 90_000L)
        val day = view(now = now, timer = running, writable = true)
        assertEquals(listOf("Done", "Later today"), day.next?.actions?.map { it.label }, "its Stop's with its time, not twice")
        val timer = assertNotNull(day.timer)
        assertEquals("A piece", timer.label)
        assertEquals("Timer: 29 min left", timer.says)
        assertEquals(now + 30_000L, timer.nextAt)
        assertEquals(StopTimer, timer.stop)
    }

    @Test
    fun `a timer on another piece is said all the same, and stopped there`() {
        val now = Fixtures.at("2026-10-12T17:10")
        val running = PieceTimer("another", "teams:b", null, null, "Another piece", 30, now)
        val day = view(now = now, timer = running, writable = true)
        assertEquals("Start", day.next?.actions?.first()?.label)
        assertEquals("Another piece", day.timer?.label)
        assertNull(view(now = now, timer = running.copy(stoppedAt = now), writable = true).timer, "and one stopped isn't said")
        assertNull(view(now = now, timer = running).timer?.stop, "nor stopped from here while mirroring")
    }

    @Test
    fun `your own task can be finished from the card`() {
        val own = TaskItem(id = "own:s", source = Source.Own, sourceId = "s", title = "Mine", dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"))
        val plan = Planner.plan(Planner.Input(listOf(own), Fixtures.at("2026-10-12T16:00"), LONDON, Settings(), windows = mapOf(monday to evening)))
        val day = DayViews.of(plan, monday, emptyList(), emptyList(), evening, Fixtures.at("2026-10-12T17:10"), LONDON, emptyList(), { EventJudge.Judgement.Busy }, emptyMap(), asOf = 5L, writable = true)
        assertEquals("All of it done", day.next?.actions?.last()?.label)
    }

    @Test
    fun `another day has neither moment nor next, and the week's strip says which is chosen`() {
        val day = view(date = monday.plusDays(2))
        assertFalse(day.today)
        assertNull(day.nowMin)
        assertNull(day.next)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), day.week.map { it.weekday })
        assertEquals(listOf(14), day.week.filter { it.chosen }.map { it.day })
        assertEquals(listOf(12), day.week.filter { it.today }.map { it.day })
    }

    @Test
    fun `an event over a piece goes beside it, as the phone lays them`() {
        val first = plan().todaySlots.first { it.kind == SlotKind.Work }
        val event = CalendarEvent(1, "Club", first.start, first.end, false)
        val day = view(events = listOf(event))
        val piece = day.blocks.first { it.kind == "piece" && it.startMin == 17 * 60 }
        val beside = day.blocks.single { it.kind == "event" }
        assertEquals(2, beside.columns)
        assertEquals(2, piece.columns)
        assertTrue(beside.column != piece.column)
    }

    @Test
    fun `an event says where it is, a blank one has a name, and what the plan counts free is marked`() {
        val at = Fixtures.at("2026-10-12T12:00")
        val day = view(
            events = listOf(CalendarEvent(1, "", at, at + 3_600_000L, false, location = "A room"), CalendarEvent(2, "Lunch", at + 7_200_000L, at + 10_800_000L, false)),
            judge = { if (it.id == 2L) EventJudge.Judgement.Free else EventJudge.Judgement.Busy },
        )
        val (blank, lunch) = day.blocks.filter { it.kind == "event" }
        assertEquals("(no title)", blank.title)
        assertEquals("A room", blank.note)
        assertFalse(blank.free)
        assertTrue(lunch.free)
    }

    @Test
    fun `an event of no length stays at its moment, and one past midnight runs to the day's end`() {
        val at = Fixtures.at("2026-10-12T12:00")
        val late = CalendarEvent(2, "Late film", Fixtures.at("2026-10-12T23:00"), Fixtures.at("2026-10-13T01:00"), false)
        val blocks = view(events = listOf(CalendarEvent(1, "A reminder", at, at, false), late)).blocks.filter { it.kind == "event" }
        assertEquals(listOf(720 to 720, 1380 to 1440), blocks.map { it.startMin to it.endMin })
    }

    @Test
    fun `what's on record says how it went`() {
        val slot = plan().todaySlots.first { it.kind == SlotKind.Work }
        fun record(key: String, outcome: SlotOutcome, hours: Long) = SlotRecord(
            date = "2026-10-12", key = key, taskId = "teams:x", kind = Kind.Admin, label = "x", plannedMin = 20,
            start = slot.start - hours * 3_600_000L, end = slot.start - (hours - 1) * 3_600_000L, window = WindowKind.Evening, outcome = outcome, workedMin = 25, at = slot.start,
        )
        val day = view(records = listOf(record("1", SlotOutcome.Kept, 3), record("2", SlotOutcome.Missed, 5), record("3", SlotOutcome.KeptLate, 7)))
        assertEquals(setOf("kept", "missed", "late"), day.blocks.filter { it.kind == "record" }.mapNotNull { it.outcome }.toSet())
    }
}
