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
    fun `what can't be judged is listed to ask, once per name`() {
        val time = EventJudge.time(listOf(event("Van hire", allDay = true), event("van hire ", allDay = true)), emptyMap(), LONDON)
        assertEquals(1, time.toAsk.size)
        assertEquals(emptyMap(), time.dayLoads)
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
    fun `fewer than four in ten days done in full over a fortnight brings advice, not before`() {
        fun days(full: Int, total: Int) = (1..total).map { DayRecord("2026-10-%02d".format(it), 60, emptyList(), doneMin = 0, full = it <= full) }
        assertNull(Days.capacityAdvice(days(full = 0, total = 9)))
        assertTrue(Days.capacityAdvice(days(full = 3, total = 14))!!.startsWith("Only 3 of the last 14"))
        assertNull(Days.capacityAdvice(days(full = 6, total = 14)))
    }
}
