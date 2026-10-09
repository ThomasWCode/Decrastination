package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.SessionRecord
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private fun done(
    task: String,
    kind: Kind = Kind.Homework,
    className: String? = "12.1 Physics",
    estimate: Int = 40,
    worked: Int = 0,
    dueAt: Long? = Fixtures.at("2026-10-20T09:00"),
    doneAt: Long = Fixtures.at("2026-10-18T19:00"),
    assessment: String? = null,
) = CompletionRecord(task, task, Source.Teams, kind, className, estimate, worked, dueAt, 0, doneAt, assessment)

class CalibratorTest {
    @Test
    fun `a completion with blocks ticked by hand teaches no times`() {
        val ticked = done("a", estimate = 30, worked = 10).copy(byHand = true)
        assertEquals(emptyMap(), Calibrator.multipliers(listOf(ticked)))
    }

    @Test
    fun `timed work moves the multiplier towards actual over estimate, within bounds`() {
        val m = Calibrator.multipliers(listOf(done("a", worked = 64), done("b", worked = 64, doneAt = Fixtures.at("2026-10-19T19:00"))))
        // 1.6× twice, at a weight of 0.3: 1 → 1.18 → 1.306.
        assertEquals(1.306, m.getValue("Homework|12.1 Physics"), 1e-9)
        // The kind as a whole learns too, for classes not yet seen.
        assertEquals(1.306, m.getValue("Homework|"), 1e-9)
        val wild = Calibrator.multipliers((1..20).map { done("t$it", worked = 600, doneAt = Fixtures.at("2026-10-01T19:00") + it * DAY) })
        assertEquals(Calibrator.MAX_MULTIPLIER, wild.getValue("Homework|12.1 Physics"))
    }

    @Test
    fun `harder and easier nudge it, more with no timed minutes to go on`() {
        assertEquals(1.10, Calibrator.multipliers(listOf(done("a", assessment = Calibrator.HARDER))).getValue("Homework|12.1 Physics"), 1e-9)
        assertEquals(0.90, Calibrator.multipliers(listOf(done("a", assessment = Calibrator.EASIER))).getValue("Homework|12.1 Physics"), 1e-9)
        // No minutes and no answer: nothing learned.
        assertEquals(emptyMap(), Calibrator.multipliers(listOf(done("a"))))
    }

    @Test
    fun `two late finishes in a row add a day of margin, five early ones take it back`() {
        val due = Fixtures.at("2026-10-20T09:00")
        val late = (0..1).map { done("l$it", dueAt = due + it * DAY, doneAt = due + it * DAY - 30 * 60_000L) }
        assertEquals(mapOf(Kind.Homework to 2), Calibrator.margins(late))
        val early = (2..6).map { done("e$it", dueAt = due + it * DAY, doneAt = due + it * DAY - 3 * DAY) }
        assertEquals(emptyMap(), Calibrator.margins(late + early))
        // Never past three days.
        val veryLate = (0..9).map { done("v$it", dueAt = due + it * DAY, doneAt = due + it * DAY + HOUR) }
        assertEquals(mapOf(Kind.Homework to 3), Calibrator.margins(veryLate))
    }

    @Test
    fun `the box that ends in finished work wins once there are enough sessions, and a replay agrees`() {
        val onTime = (1..12).map { done("t$it") }
        val sessions = (1..12).flatMap { i ->
            listOf(
                SessionRecord("t$i", Kind.Homework, label = "t$i", plannedMin = 25, workedMin = 25, startedAt = 0, endedAt = 0, completed = true, box = 25),
                SessionRecord("x$i", Kind.Homework, label = "x$i", plannedMin = 60, workedMin = 20, startedAt = 0, endedAt = 0, completed = false, box = 60),
            )
        }
        val log = ActivityLog(sessions = sessions, completions = onTime)
        assertEquals(mapOf(Kind.Homework to 25), Calibrator.boxes(log, defaultBox = 45, week = 7))
        assertEquals(Calibrator.boxes(log, 45, week = 7), Calibrator.boxes(log, 45, week = 7))
    }

    @Test
    fun `a box session waits for its task's outcome before it counts`() {
        // Twenty full sessions at 25 on work not done yet: no outcome, so nothing settled or steered.
        val pending = ActivityLog(sessions = (1..20).map { SessionRecord("t$it", Kind.Homework, label = "t", plannedMin = 25, workedMin = 25, startedAt = 0, endedAt = 0, completed = true, box = 25) })
        val kept = (1L..40L).count { Calibrator.boxes(pending, defaultBox = 45, week = it).isEmpty() }
        assertTrue(kept >= 25, "kept $kept of 40")
    }

    @Test
    fun `your own box is a candidate, and with nothing to judge the others by, it stands most weeks`() {
        // Twenty sessions at a box that's neither yours (30) nor one the experiment tries: nothing
        // to judge them by, so yours is the best, and only the weeks it tries another move off it.
        val other = ActivityLog(
            sessions = (1..20).map { SessionRecord("b$it", Kind.Homework, label = "b", plannedMin = 40, workedMin = 40, startedAt = 0, endedAt = 0, completed = true, box = 40) },
            // Each one's task since done, so each is judged.
            completions = (1..20).map { done("b$it", doneAt = 1) },
        )
        val kept = (1L..40L).count { Calibrator.boxes(other, defaultBox = 30, week = it).isEmpty() }
        assertTrue(kept >= 25, "kept $kept of 40 weeks")
    }

    @Test
    fun `a session is judged by its own round of the task, not an earlier round's finish`() {
        val due = Fixtures.at("2026-10-10T09:00")
        // Done in time once, then reopened and done late: the second round's sessions didn't end well.
        val rounds = listOf(done("t", dueAt = due, doneAt = due - HOUR), done("t", dueAt = due + 5 * DAY, doneAt = due + 6 * DAY))
        val sessions = (1..20).map { SessionRecord("t", Kind.Homework, label = "t", plannedMin = 25, workedMin = 25, startedAt = due + 2 * DAY + it * HOUR, endedAt = 0, completed = true, box = 25) }
        assertEquals(emptyMap(), Calibrator.boxes(ActivityLog(sessions = sessions, completions = rounds), defaultBox = 45, week = 7))
    }

    @Test
    fun `an old box of yours doesn't settle the experiment`() {
        // Twenty sessions at your old box of 40; you've since set 30.
        val old = ActivityLog(
            sessions = (1..20).map { SessionRecord("b$it", Kind.Homework, label = "b", plannedMin = 40, workedMin = 40, startedAt = 0, endedAt = 0, completed = true, box = 40) },
            completions = (1..20).map { done("b$it", doneAt = 1) },
        )
        // Unsettled, some weeks try another box.
        val tried = (1L..40L).map { Calibrator.boxes(old, defaultBox = 30, week = it)[Kind.Homework] }
        assertTrue(tried.any { it != null && it != 30 })
    }

    @Test
    fun `a multiplier gone back to its fallback is said`() {
        val previous = Calibration(multipliers = mapOf("Homework|12.1 Physics" to 1.5))
        val learned = Calibrator.learn(ActivityLog(), previous, defaultBox = 45, week = 1)
        assertTrue(learned.changes.any { "Homework in 12.1 Physics takes ×1.00" in it }, learned.changes.toString())
    }

    @Test
    fun `a learned margin starts from your setting, and going back is going back to it`() {
        val due = Fixtures.at("2026-10-20T09:00")
        // A setting of three days, and five finishes a day or more early: two.
        val early = (0..4).map { done("e$it", dueAt = due + it * DAY, doneAt = due + it * DAY - 3 * DAY) }
        assertEquals(mapOf(Kind.Homework to 2), Calibrator.margins(early, start = 3))
        // Two late after that: back to three, which is the setting, so nothing of its own.
        val late = (5..6).map { done("l$it", dueAt = due + it * DAY, doneAt = due + it * DAY) }
        assertEquals(emptyMap(), Calibrator.margins(early + late, start = 3))
    }

    @Test
    fun `an answer is kept on the completion it's about`() {
        val log = ActivityLog(completions = listOf(done("t", doneAt = Fixtures.at("2026-10-10T19:00")), done("t", doneAt = Fixtures.at("2026-10-12T19:00"))))
        val answered = Assessment.answered(log, "t", Fixtures.at("2026-10-10T19:00"), Calibrator.HARDER, null)
        assertEquals(listOf(Calibrator.HARDER, null), answered.completions.map { it.assessment })
    }

    @Test
    fun `a box length gone back to the default is said`() {
        val previous = Calibration(boxMin = mapOf(Kind.Homework to 25))
        val learned = Calibrator.learn(ActivityLog(), previous, defaultBox = 45, week = 1)
        assertTrue(learned.changes.any { "Homework: pieces of 45 minutes" in it }, learned.changes.toString())
    }

    @Test
    fun `what changed is said in words`() {
        val learned = Calibrator.learn(ActivityLog(completions = listOf(done("a", worked = 80))), Calibration(), defaultBox = 45, week = 1)
        assertTrue(learned.changes.any { "Homework in 12.1 Physics takes ×1.30" in it }, learned.changes.toString())
    }
}

class EventJudgeTest {
    private val start = Fixtures.at("2026-10-10T14:00")

    private fun event(title: String, allDay: Boolean = false, busy: Boolean = true, calendar: String = "Thomas", hours: Int = 2) = CalendarEvent(
        id = title.hashCode().toLong(), title = title, start = if (allDay) LocalDate.parse("2026-10-10").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else start,
        end = if (allDay) LocalDate.parse("2026-10-11").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else start + hours * HOUR,
        allDay = allDay, busy = busy, calendar = calendar,
    )

    @Test
    fun `a lesson takes its slot, a train doesn't, an all-day event is asked about`() {
        assertEquals(EventJudge.Judgement.Busy, EventJudge.judge(event("Drum lesson"), emptyMap()))
        assertEquals(EventJudge.Judgement.Free, EventJudge.judge(event("Train to Manchester"), emptyMap()))
        // Marked free in the calendar.
        assertEquals(EventJudge.Judgement.Free, EventJudge.judge(event("Football", busy = false), emptyMap()))
        assertEquals(EventJudge.Judgement.Ask, EventJudge.judge(event("Van hire", allDay = true), emptyMap()))
        // Booked 9 to 6 rather than all day: still asked about, not nine hours gone.
        assertEquals(EventJudge.Judgement.Ask, EventJudge.judge(event("Van hire", hours = 9), emptyMap()))
        assertEquals(EventJudge.Judgement.Free, EventJudge.judge(event("Bank holiday", allDay = true, calendar = "Holidays in United Kingdom"), emptyMap()))
    }

    @Test
    fun `your answer holds for every event of that name`() {
        val answers = mapOf("van hire" to "load:${EventJudge.FEW_HOURS_MIN}")
        val time = EventJudge.time(listOf(event("Van Hire", allDay = true), event("Drum lesson")), answers, LONDON)
        assertEquals(mapOf(LocalDate.parse("2026-10-10") to EventJudge.FEW_HOURS_MIN), time.dayLoads)
        assertEquals(1, time.busy.size)
        assertEquals(emptyList(), time.toAsk)
    }

    @Test
    fun `a timed event's few hours count once, an all-day event's on each day`() {
        val night = CalendarEvent(9, "Party", Fixtures.at("2026-10-10T22:00"), Fixtures.at("2026-10-11T02:00"), allDay = false)
        val time = EventJudge.time(listOf(night), mapOf(EventJudge.key(night) to "load:180"), LONDON)
        assertEquals(mapOf(LocalDate.parse("2026-10-10") to 180), time.dayLoads)
    }

    @Test
    fun `untitled events are answered apart`() {
        val a = CalendarEvent(1, "", Fixtures.at("2026-10-10T10:00"), Fixtures.at("2026-10-10T18:00"), allDay = false)
        val b = CalendarEvent(2, " ", Fixtures.at("2026-10-11T10:00"), Fixtures.at("2026-10-11T18:00"), allDay = false)
        assertTrue(EventJudge.key(a) != EventJudge.key(b))
        assertEquals("party", EventJudge.key(CalendarEvent(3, " Party ", Fixtures.at("2026-10-12T20:00"), Fixtures.at("2026-10-12T23:00"), allDay = false)))
    }

    @Test
    fun `what can't be judged is listed to ask, and asked once per name`() {
        val time = EventJudge.time(listOf(event("Van hire", allDay = true), event("van hire ", allDay = true)), emptyMap(), LONDON)
        assertEquals(2, time.toAsk.size)
        assertEquals(1, CalendarTime.questions(time.toAsk, emptySet(), start - 3_600_000L).size)
        assertEquals(emptyMap(), time.dayLoads)
    }
}

class CalendarQuestionsTest {
    private val now = Fixtures.at("2026-10-09T17:00")

    private fun event(id: Long, title: String, start: String, end: String) = CalendarEvent(id, title, Fixtures.at(start), Fixtures.at(end), allDay = false)

    @Test
    fun `an ended occurrence of a long event doesn't stand in for the next`() {
        val yesterday = event(7, "Course", "2026-10-08T09:00", "2026-10-08T15:00")
        val tomorrow = event(8, "Course", "2026-10-10T09:00", "2026-10-10T15:00")
        val time = EventJudge.time(listOf(yesterday, tomorrow), emptyMap(), LONDON)
        assertEquals(listOf(tomorrow), CalendarTime.questions(time.toAsk, emptySet(), now))
    }

    @Test
    fun `asked about only if not over, within the week, and not asked before`() {
        val over = event(1, "Van hire", "2026-10-08T09:00", "2026-10-08T18:00")
        val soon = event(2, "Open day", "2026-10-10T09:00", "2026-10-10T17:00")
        val far = event(3, "Trip", "2026-10-20T09:00", "2026-10-20T17:00")
        val asked = event(4, "Course", "2026-10-11T09:00", "2026-10-11T14:00")
        assertEquals(listOf(soon), CalendarTime.questions(listOf(over, soon, far, asked), setOf(EventJudge.key(asked)), now))
    }

    @Test
    fun `a question names a timed event's day where you are, and an all-day one's as stored`() {
        // Just after midnight in summer time: still Saturday here, though Friday in UTC.
        assertEquals("Saturday 10 October", CalendarTime.questionDay(event(5, "Party", "2026-10-10T00:30", "2026-10-10T05:00"), LONDON))
        val utcMidnight = LocalDate.parse("2026-10-12").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("Monday 12 October", CalendarTime.questionDay(CalendarEvent(6, "Holiday", utcMidnight, utcMidnight + DAY, allDay = true), LONDON))
    }
}

class DaysTest {
    @Test
    fun `a day is done in full when its tasks were confirmed done or worked for their minutes`() {
        val day = DayRecord("2026-10-09", 70, listOf(DayChunk("teams:a", 40), DayChunk("teams:b", 30)))
        val log = ActivityLog(
            completions = listOf(done("teams:a", doneAt = Fixtures.at("2026-10-09T20:00"))),
            sessions = listOf(SessionRecord("teams:b", Kind.Homework, label = "b", plannedMin = 30, workedMin = 20, startedAt = Fixtures.at("2026-10-09T18:00"), endedAt = 0, completed = false)),
        )
        val finished = Days.finish(day, log, LONDON)
        assertEquals(60, finished.doneMin)
        assertEquals(false, finished.full)
    }

    @Test
    fun `a task done on an earlier day and since reopened isn't done that day`() {
        val day = DayRecord("2026-10-09", 40, listOf(DayChunk("teams:a", 40)))
        val log = ActivityLog(completions = listOf(done("teams:a", doneAt = Fixtures.at("2026-10-07T20:00"))))
        assertEquals(0, Days.finish(day, log, LONDON).doneMin)
    }

    @Test
    fun `a day is finished by its own midnights, wherever the phone is now`() {
        // Planned in London; a session at 23:30 there is that day's, though it's the next day in Tokyo.
        val day = DayRecord("2026-10-09", 30, listOf(DayChunk("teams:a", 30)), zone = "Europe/London")
        val log = ActivityLog(sessions = listOf(SessionRecord("teams:a", Kind.Homework, label = "a", plannedMin = 30, workedMin = 30, startedAt = Fixtures.at("2026-10-09T23:30"), endedAt = 0, completed = true)))
        assertEquals(30, Days.finish(day, log, java.time.ZoneId.of("Asia/Tokyo")).doneMin)
    }

    @Test
    fun `an answer that changes today keeps what was done and takes the rest from the plan now`() {
        val day = DayRecord("2026-10-09", 90, listOf(DayChunk("teams:a", 30), DayChunk("teams:b", 30), DayChunk("teams:c", 30)), zone = "Europe/London")
        val log = ActivityLog(
            completions = listOf(done("teams:a", doneAt = Fixtures.at("2026-10-09T17:30"))),
            sessions = listOf(SessionRecord("teams:b", Kind.Homework, label = "b", plannedMin = 30, workedMin = 10, startedAt = Fixtures.at("2026-10-09T18:00"), endedAt = 0, completed = false)),
        )
        // The evening's event takes most of it: today now holds b's last 20 minutes; c has moved on.
        val again = Days.replan(day, listOf(DayChunk("teams:b", 20)), log, Fixtures.at("2026-10-09T18:30"), LONDON)
        assertEquals(listOf(DayChunk("teams:a", 30), DayChunk("teams:b", 10), DayChunk("teams:b", 20)), again.chunks)
        assertEquals(60, again.plannedMin)
        // b finished that evening: the day was done in full.
        val finished = Days.finish(again, log.copy(completions = log.completions + done("teams:b", doneAt = Fixtures.at("2026-10-09T21:00"))), LONDON)
        assertEquals(true, finished.full)
    }

    @Test
    fun `a session across midnight counts on each day only its own minutes`() {
        val log = ActivityLog(sessions = listOf(SessionRecord("teams:a", Kind.Homework, label = "a", plannedMin = 180, workedMin = 180, startedAt = Fixtures.at("2026-10-09T23:30"), endedAt = 0, completed = true)))
        assertEquals(30, Days.finish(DayRecord("2026-10-09", 60, listOf(DayChunk("teams:a", 60)), zone = "Europe/London"), log, LONDON).doneMin)
        assertEquals(150, Days.finish(DayRecord("2026-10-10", 200, listOf(DayChunk("teams:a", 200)), zone = "Europe/London"), log, LONDON).doneMin)
    }

    @Test
    fun `fewer than four in ten days done in full over a fortnight brings advice, not before`() {
        fun days(full: Int, total: Int) = (1..total).map { DayRecord("2026-10-%02d".format(it), 60, emptyList(), doneMin = 0, full = it <= full) }
        val today = LocalDate.parse("2026-10-14")
        assertNull(Days.capacityAdvice(days(full = 0, total = 9), today))
        assertTrue(Days.capacityAdvice(days(full = 3, total = 14), today)!!.startsWith("Only 3 of the last 14"))
        assertNull(Days.capacityAdvice(days(full = 6, total = 14), today))
        // Old failures don't stand in for recent days with no work: the fortnight has five plans, all done.
        val old = (1..20).map { DayRecord("2026-08-%02d".format(it), 60, emptyList(), doneMin = 0, full = false) }
        val recent = (10..14).map { DayRecord("2026-10-%02d".format(it), 60, emptyList(), doneMin = 60, full = true) }
        assertNull(Days.capacityAdvice(old + recent, today))
    }
}
