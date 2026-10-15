package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
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
    fun `a completion planned by your own minutes teaches nothing of the app's estimates`() {
        // Timed, and twice its estimate: but the estimate was yours, which no multiplier is laid over.
        val yours = done("a", estimate = 30, worked = 60).copy(yourEstimate = true, assessment = Calibrator.HARDER)
        assertEquals(emptyMap(), Calibrator.multipliers(listOf(yours)))
        assertEquals(emptyMap(), Calibrator.timed(listOf(yours)))
    }

    @Test
    fun `a completion with blocks ticked by hand teaches no times`() {
        val ticked = done("a", estimate = 30, worked = 10).copy(byHand = true)
        assertEquals(emptyMap(), Calibrator.multipliers(listOf(ticked)))
    }
    @Test
    fun `a completion partly found done by a photo check teaches no times`() {
        // 5 timed minutes of 40: the rest was photographed, not timed, so 5 isn't how long it took.
        val photographed = done("a", estimate = 40, worked = 5).copy(photo = true)
        assertEquals(emptyMap(), Calibrator.multipliers(listOf(photographed)))
        // Its "harder" still counts, as an answer alone does: for the kind, as the class has no timed pieces.
        val harder = photographed.copy(assessment = Calibrator.HARDER)
        assertEquals(1.1, Calibrator.multipliers(listOf(harder)).getValue("Homework|"), 1e-9)
    }


    @Test
    fun `a calibration from before evidence was counted is made again under its rule`() {
        // Learned before 1.8.0: the class's own from one timed piece, and no counts kept.
        val one = listOf(done("a", worked = 64))
        val old = Calibration(multipliers = mapOf("Homework|" to 1.18, "Homework|12.1 Physics" to 1.18), marginDays = mapOf(Kind.Homework to 2))
        val now = Calibrator.upgraded(old, one)!!
        assertTrue(now.counted)
        assertNull(now.multipliers["Homework|12.1 Physics"])
        assertEquals(1.18, now.multipliers.getValue("Homework|"), 1e-9)
        assertEquals(1, now.timed["Homework|12.1 Physics"])
        assertEquals(mapOf(Kind.Homework to 2), now.marginDays)
        // One made since: left as it is, even from your answers alone, with no timed pieces to count.
        assertNull(Calibrator.upgraded(now, one))
        assertNull(Calibrator.upgraded(Calibration(multipliers = mapOf("Homework|" to 1.1), counted = true), one))
        assertNull(Calibrator.upgraded(Calibration(), one))
        assertTrue(Calibrator.learn(ActivityLog(), Calibration(), today = LocalDate.parse("2026-10-11")).calibration.counted)
    }

    @Test
    fun `timed work moves the multiplier towards actual over estimate, within bounds`() {
        val m = Calibrator.multipliers(listOf(done("a", worked = 64), done("b", worked = 64, doneAt = Fixtures.at("2026-10-19T19:00"))))
        // 1.6× twice, at a weight of 0.3: 1 → 1.18 → 1.306, for the kind as a whole...
        assertEquals(1.306, m.getValue("Homework|"), 1e-9)
        // ...and, from its third timed piece, for the class itself (IMP-P2-016).
        assertNull(m["Homework|12.1 Physics"])
        val three = Calibrator.multipliers((1..3).map { done("t$it", worked = 64, doneAt = Fixtures.at("2026-10-17T19:00") + it * DAY) })
        assertEquals(1.0 + 0.6 * (1 - 0.7 * 0.7 * 0.7), three.getValue("Homework|12.1 Physics"), 1e-9)
        val wild = Calibrator.multipliers((1..20).map { done("t$it", worked = 600, doneAt = Fixtures.at("2026-10-01T19:00") + it * DAY) })
        assertEquals(Calibrator.MAX_MULTIPLIER, wild.getValue("Homework|12.1 Physics"))
    }

    @Test
    fun `harder and easier nudge it, more with no timed minutes to go on`() {
        assertEquals(1.10, Calibrator.multipliers(listOf(done("a", assessment = Calibrator.HARDER))).getValue("Homework|"), 1e-9)
        assertEquals(0.90, Calibrator.multipliers(listOf(done("a", assessment = Calibrator.EASIER))).getValue("Homework|"), 1e-9)
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
        // Late against dates the app or Claude set: plans, which teach no margin (PL-14).
        assertEquals(emptyMap(), Calibrator.margins(late.map { it.copy(hardDeadline = false) }))
    }

    @Test
    fun `a multiplier gone back to its fallback is said`() {
        val previous = Calibration(multipliers = mapOf("Homework|12.1 Physics" to 1.5))
        val learned = Calibrator.learn(ActivityLog(), previous, today = LocalDate.parse("2026-10-11"))
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
    fun `what changed is said in words`() {
        val learned = Calibrator.learn(ActivityLog(completions = listOf(done("a", worked = 80))), Calibration(), today = LocalDate.parse("2026-10-11"))
        // One timed piece: the kind's figure, not yet the class's own (IMP-P2-016).
        assertTrue(learned.changes.any { "Homework, any class takes ×1.30" in it }, learned.changes.toString())
        assertTrue(learned.changes.none { "12.1 Physics" in it }, learned.changes.toString())
    }
}

/** How much of each kind of day's free time is used (PL-06), from the days' records. */
class YieldTest {
    private val sunday = LocalDate.parse("2026-10-18")

    /** [date]'s record: [planned] minutes, [done] of them by the day's end. */
    private fun day(date: String, planned: Int, done: Int?) = DayRecord(date, planned, emptyList(), doneMin = done, full = done?.let { it >= planned })

    /** The [n] weekend days back from [sunday], each with [done] of 100 minutes done. */
    private fun weekends(n: Int, done: (Int) -> Int): List<DayRecord> =
        generateSequence(sunday) { it.minusDays(1) }.filter { DayKind.of(it) == DayKind.Weekend }.take(n).mapIndexed { i, d -> day(d.toString(), 100, done(i)) }.toList()

    @Test
    fun `a kind of day's yield is the median of done over planned, once it has seven days`() {
        // Six weekend days aren't enough; a seventh gives the median of the seven: 60 %.
        val shares = listOf(90, 40, 60, 100, 55, 70, 50)
        assertNull(Calibrator.yields(weekends(6) { shares[it] }, emptyList(), sunday)[DayKind.Weekend])
        assertEquals(0.6, Calibrator.yields(weekends(7) { shares[it] }, emptyList(), sunday)[DayKind.Weekend]!!, 1e-9)
        assertEquals(7, Calibrator.yieldDays(weekends(7) { shares[it] }, emptyList(), sunday)[DayKind.Weekend])
        // A day with nothing planned, or not yet finished, isn't one to judge by.
        val idle = weekends(6) { shares[it] } + day("2026-10-03", 0, 0) + day("2026-09-27", 100, null)
        assertNull(Calibrator.yields(idle, emptyList(), sunday)[DayKind.Weekend])
        // Only the last fourteen of a kind count: older days are left out.
        val old = weekends(14) { 100 } + weekends(30) { 20 }.drop(14)
        assertEquals(1.0, Calibrator.yields(old, emptyList(), sunday)[DayKind.Weekend]!!, 1e-9)
    }

    @Test
    fun `a yield is kept between a half and the whole of a day's free time`() {
        // Seven weekends at 40 %: half their time (PL-06's Done when); doing more than planned is all of it.
        assertEquals(Calibrator.MIN_YIELD, Calibrator.yields(weekends(7) { 40 }, emptyList(), sunday)[DayKind.Weekend]!!, 1e-9)
        assertEquals(Calibrator.MAX_YIELD, Calibrator.yields(weekends(7) { 130 }, emptyList(), sunday)[DayKind.Weekend]!!, 1e-9)
    }

    @Test
    fun `each kind of day learns its own, so bad Saturdays don't move the weekdays`() {
        val weekdays = generateSequence(LocalDate.parse("2026-10-16")) { it.minusDays(1) }.filter { DayKind.of(it) == DayKind.Weekday }.take(7)
            .map { day(it.toString(), 100, 100) }.toList()
        val yields = Calibrator.yields(weekends(7) { 30 } + weekdays, emptyList(), sunday)
        assertEquals(0.5, yields[DayKind.Weekend]!!, 1e-9)
        assertEquals(1.0, yields[DayKind.Weekday]!!, 1e-9)
        // A record kept before kinds were is told by its date; one saying its kind is taken at its word.
        assertEquals(DayKind.Weekend, DayKind.of(LocalDate.parse("2026-10-17")))
        val said = weekdays.map { it.copy(kind = DayKind.Weekend) }
        assertNull(Calibrator.yields(said, emptyList(), sunday)[DayKind.Weekday])
    }

    @Test
    fun `the review says when a kind of day's yield moves`() {
        val log = ActivityLog(days = weekends(7) { 40 })
        val learned = Calibrator.learn(log, Calibration(), today = sunday)
        assertEquals(0.5, learned.calibration.yieldFor(DayKind.Weekend), 1e-9)
        assertEquals(1.0, learned.calibration.yieldFor(DayKind.Weekday), 1e-9)
        assertTrue("Weekends hold about 50 % of their free time now (was 100 %)" in learned.changes, "${learned.changes}")
        // Learned again from the same days: nothing to say.
        assertTrue(Calibrator.learn(log, learned.calibration, today = sunday).changes.none { "free time" in it })
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
    fun `a timed event saying it's optional or tentative is asked about`() {
        // DX-12: its own words say it may not need you; an all-day one is asked about anyway.
        assertEquals(EventJudge.Judgement.Ask, EventJudge.judge(event("Revision club").copy(description = "Optional: drop in if you can"), emptyMap()))
        assertEquals(EventJudge.Judgement.Ask, EventJudge.judge(event("Planning call").copy(description = "Tentative, to be confirmed", location = "Zoom"), emptyMap()))
        assertEquals(EventJudge.Judgement.Busy, EventJudge.judge(event("Drum lesson").copy(description = "Bring the optionality worksheet"), emptyMap()))
        // Marked free, it's free whatever it says.
        assertEquals(EventJudge.Judgement.Free, EventJudge.judge(event("Revision club", busy = false).copy(description = "Optional"), emptyMap()))
    }

    @Test
    fun `your answer holds for every event of that name`() {
        val answers = mapOf("van hire" to "load:${EventJudge.FEW_HOURS_MIN}")
        val time = EventJudge.time(listOf(event("Van Hire", allDay = true), event("Drum lesson")), answers, LONDON, askUntil = Long.MAX_VALUE)
        assertEquals(mapOf(LocalDate.parse("2026-10-10") to EventJudge.FEW_HOURS_MIN), time.dayLoads)
        assertEquals(1, time.busy.size)
        assertEquals(emptyList(), time.toAsk)
    }

    @Test
    fun `a timed event's few hours count once, an all-day event's on each day`() {
        val night = CalendarEvent(9, "Party", Fixtures.at("2026-10-10T22:00"), Fixtures.at("2026-10-11T02:00"), allDay = false)
        val time = EventJudge.time(listOf(night), mapOf(EventJudge.key(night) to "load:180"), LONDON, askUntil = Long.MAX_VALUE)
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
    fun `an all-day event past the week ahead counts as a few hours of its day, not a question`() {
        // Seen on Thursday 8 Oct: the van hire on Saturday is asked about; a trip three weeks on
        // isn't asked yet (DX-14), but takes its few hours out of its day meanwhile.
        val now = Fixtures.at("2026-10-08T17:00")
        val trip = CalendarEvent(5, "School trip", LocalDate.parse("2026-10-29").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), LocalDate.parse("2026-10-30").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        val time = EventJudge.time(listOf(event("Van hire", allDay = true), trip), emptyMap(), LONDON, askUntil = now + 7 * DAY)
        assertEquals(listOf("Van hire"), time.toAsk.map { it.title })
        assertEquals(mapOf(LocalDate.parse("2026-10-29") to EventJudge.FEW_HOURS_MIN), time.dayLoads)
        // Answered, the answer holds there too.
        val answered = EventJudge.time(listOf(trip), mapOf(EventJudge.key(trip) to "busy"), LONDON, askUntil = now + 7 * DAY)
        assertEquals(mapOf(LocalDate.parse("2026-10-29") to 24 * 60), answered.dayLoads)
    }

    @Test
    fun `what can't be judged is listed to ask, and asked once per name`() {
        val time = EventJudge.time(listOf(event("Van hire", allDay = true), event("van hire ", allDay = true)), emptyMap(), LONDON, askUntil = Long.MAX_VALUE)
        assertEquals(2, time.toAsk.size)
        assertEquals(1, CalendarTime.questions(time.toAsk, emptySet(), start - 3_600_000L).size)
        assertEquals(emptyMap(), time.dayLoads)
    }
}

class CalendarQuestionsTest {
    private val now = Fixtures.at("2026-10-09T17:00")

    private fun event(id: Long, title: String, start: String, end: String) = CalendarEvent(id, title, Fixtures.at(start), Fixtures.at(end), allDay = false)

    @Test
    fun `the calendar is read to the end of the plan's last day`() {
        // 90 days on from Thursday 15 Oct is Wednesday 13 Jan: read until its midnight, whatever the time now.
        assertEquals(Fixtures.at("2027-01-14T00:00"), CalendarTime.readUntil(Fixtures.at("2026-10-15T17:00"), LONDON))
        assertEquals(Fixtures.at("2027-01-14T00:00"), CalendarTime.readUntil(Fixtures.at("2026-10-15T00:05"), LONDON))
    }

    @Test
    fun `an ended occurrence of a long event doesn't stand in for the next`() {
        val yesterday = event(7, "Course", "2026-10-08T09:00", "2026-10-08T15:00")
        val tomorrow = event(8, "Course", "2026-10-10T09:00", "2026-10-10T15:00")
        val time = EventJudge.time(listOf(yesterday, tomorrow), emptyMap(), LONDON, askUntil = Long.MAX_VALUE)
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
    /** A slot of [taskId]'s kept with [worked] minutes, laid at [start] (LR-09). */
    private fun kept(taskId: String, worked: Int, start: String) = SlotRecord(
        date = start.take(10), key = "$taskId#-1#1", taskId = taskId, kind = Kind.Homework, label = taskId, plannedMin = worked,
        start = Fixtures.at(start), end = Fixtures.at(start) + worked * 60_000L, window = WindowKind.Evening, outcome = SlotOutcome.Kept,
        workedMin = worked, at = Fixtures.at(start) + worked * 60_000L,
    )

    @Test
    fun `a day is done in full when its tasks were confirmed done or worked for their minutes`() {
        val day = DayRecord("2026-10-09", 70, listOf(DayChunk("teams:a", 40), DayChunk("teams:b", 30)))
        val log = ActivityLog(
            completions = listOf(done("teams:a", doneAt = Fixtures.at("2026-10-09T20:00"))),
            slots = listOf(kept("teams:b", 20, "2026-10-09T18:00")),
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
        // Planned in London; a slot at 21:30 there is that day's, though it's the next day in Tokyo.
        val day = DayRecord("2026-10-09", 30, listOf(DayChunk("teams:a", 30)), zone = "Europe/London")
        val log = ActivityLog(slots = listOf(kept("teams:a", 30, "2026-10-09T21:30")))
        assertEquals(30, Days.finish(day, log, java.time.ZoneId.of("Asia/Tokyo")).doneMin)
    }

    @Test
    fun `an answer that changes today keeps what was done and takes the rest from the plan now`() {
        val day = DayRecord("2026-10-09", 90, listOf(DayChunk("teams:a", 30), DayChunk("teams:b", 30), DayChunk("teams:c", 30)), zone = "Europe/London")
        val log = ActivityLog(
            completions = listOf(done("teams:a", doneAt = Fixtures.at("2026-10-09T17:30"))),
            slots = listOf(kept("teams:b", 10, "2026-10-09T18:00")),
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
    fun `a kept slot counts on the day it was laid, and a missed one not at all`() {
        val missed = kept("teams:a", 30, "2026-10-09T19:00").copy(outcome = SlotOutcome.Missed, workedMin = 0)
        val log = ActivityLog(slots = listOf(kept("teams:a", 30, "2026-10-09T23:00"), missed))
        assertEquals(30, Days.finish(DayRecord("2026-10-09", 60, listOf(DayChunk("teams:a", 60)), zone = "Europe/London"), log, LONDON).doneMin)
        assertEquals(0, Days.finish(DayRecord("2026-10-10", 60, listOf(DayChunk("teams:a", 60)), zone = "Europe/London"), log, LONDON).doneMin)
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

class EvidenceTest {
    @Test
    fun `one long evening can't set a new class's figure, and the timed pieces are counted`() {
        // A first German homework that took four times its estimate.
        val first = done("a", className = "12.1 German", worked = 160)
        val m = Calibrator.multipliers(listOf(first))
        assertNull(m["Homework|12.1 German"])
        assertEquals(1.9, m.getValue("Homework|"), 1e-9)
        assertEquals(mapOf("Homework|12.1 German" to 1, "Homework|" to 1), Calibrator.timed(listOf(first)))
        // Ticked by hand, or found done by a photo, isn't a timed piece.
        assertEquals(emptyMap(), Calibrator.timed(listOf(first.copy(byHand = true), first.copy(photo = true))))
    }
}
