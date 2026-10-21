package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BlockRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.ProtectionRecord
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.WeekRollup
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTest {
    private val now = Fixtures.at("2026-10-09T20:00")

    private fun completion(id: String, done: String, due: String?) = CompletionRecord(
        taskId = id,
        title = id,
        source = Source.Teams,
        kind = Kind.Homework,
        estimateMin = 40,
        workedMin = 0,
        dueAt = due?.let(Fixtures::at),
        firstSeenAt = Fixtures.at("2026-10-01T09:00"),
        doneAt = Fixtures.at(done),
    )

    /** A 30-minute slot laid at [start], with how it went and the minutes worked in it. */
    private fun slot(start: String, outcome: SlotOutcome, worked: Int = if (outcome.kept) 30 else 0, earned: Boolean = false, photo: Boolean = false) = SlotRecord(
        date = start.take(10), key = "teams:t#-1#1", taskId = "teams:t", kind = Kind.Homework, label = "t", plannedMin = 30,
        start = Fixtures.at(start), end = Fixtures.at(start) + 30 * 60_000L, window = WindowKind.Evening, outcome = outcome,
        workedMin = worked, photo = photo, earned = earned, at = Fixtures.at(start) + 30 * 60_000L,
    )

    @Test
    fun `the fortnight's totals and each day's, newest first, leaving out what's older`() {
        val log = ActivityLog(
            completions = listOf(
                completion("a", "2026-10-09T18:00", due = "2026-10-10T08:30"),
                completion("b", "2026-10-08T18:00", due = "2026-10-07T08:30"),
                completion("c", "2026-10-08T19:00", due = null),
                // Done after a date the app set: a plan, so neither on time nor late (PL-14).
                completion("d", "2026-10-08T20:00", due = "2026-10-07T08:30").copy(hardDeadline = false),
                // Three weeks ago: out of the fortnight.
                completion("old", "2026-09-18T18:00", due = null),
            ),
            // Kept today, earning; yesterday one missed, one kept late with ten more minutes, one moved (neither).
            slots = listOf(
                slot("2026-10-09T17:00", SlotOutcome.Kept, earned = true),
                slot("2026-10-08T17:00", SlotOutcome.Missed),
                slot("2026-10-08T18:00", SlotOutcome.KeptLate, worked = 40, photo = true),
                slot("2026-10-08T19:00", SlotOutcome.Moved),
            ),
            blocks = listOf(
                BlockRecord(Fixtures.at("2026-10-09T17:40"), "com.google.android.youtube", "DueSoon"),
                BlockRecord(Fixtures.at("2026-10-09T18:40"), "com.google.android.youtube", "DueSoon"),
                BlockRecord(Fixtures.at("2026-10-08T18:40"), "youtube.com", "DueSoon"),
            ),
            protection = listOf(ProtectionRecord(Fixtures.at("2026-10-08T01:00"), listOf("The focus service has stopped"), repaired = true)),
            days = listOf(DayRecord("2026-10-08", plannedMin = 60, chunks = emptyList(), doneMin = 60, full = true)),
        )
        val stats = Stats.summary(log, now, LONDON)
        assertEquals(Stats.DAYS, stats.days.size)
        assertEquals(LocalDate.parse("2026-10-09"), stats.days.first().date)
        assertEquals(4, stats.completions)
        // Two had real deadlines; one was met.
        assertEquals(2, stats.dated)
        assertEquals(1, stats.onTime)
        assertEquals(3, stats.slotsPlanned)
        assertEquals(2, stats.slotsKept)
        assertEquals(1, stats.slotsEarned)
        assertEquals(1, stats.photoChecks)
        assertEquals(70, stats.slotMin)
        assertEquals(listOf("com.google.android.youtube" to 2, "youtube.com" to 1), stats.topBlocked)
        assertEquals(1, stats.protectionProblems)
        assertEquals(1, stats.protectionRepaired)
        val today = stats.days[0]
        assertEquals(listOf(1, 30, 2), listOf(today.completions, today.slotMin, today.blocks))
        assertEquals(null, today.planDone)
        val yesterday = stats.days[1]
        assertEquals(listOf(3, 40, 1), listOf(yesterday.completions, yesterday.slotMin, yesterday.blocks))
        assertEquals(true, yesterday.planDone)
    }

    @Test
    fun `a slot counts on the day it was laid, kept whenever it was ticked`() {
        // Laid on the 7th at 21:30, ticked after midnight: the 7th's.
        val late = slot("2026-10-07T21:30", SlotOutcome.KeptLate).copy(at = Fixtures.at("2026-10-08T00:20"))
        val summary = Stats.summary(ActivityLog(slots = listOf(late)), now, LONDON)
        assertEquals(30, summary.days.single { it.date.toString() == "2026-10-07" }.slotMin)
        assertEquals(0, summary.days.single { it.date.toString() == "2026-10-08" }.slotMin)
    }

    @Test
    fun `the calibration in words`() {
        val calibration = Calibration(
            multipliers = mapOf("Homework|Physics" to 1.34, "Homework|" to 1.1, "Revision|" to 0.9),
            marginDays = mapOf(Kind.Revision to 2),
            timed = mapOf("Homework|Physics" to 4, "Homework|" to 6, "Homework|German" to 2),
            yields = mapOf(DayKind.Weekend to 0.55),
            yieldDays = mapOf(DayKind.Weekend to 9),
        )
        assertEquals(
            listOf(
                "Homework: takes 1.1× the estimate (from 6 timed pieces)",
                "Homework, Physics: takes 1.3× the estimate (from 4 timed pieces)",
                "Revision: takes 0.9× the estimate (from your answers)",
                "Homework, German: 2 timed pieces so far; its own figure counts from 3",
                "Revision: finished 2 days before the deadline",
                "Weekends: about 55 % of the free time used (from 9 days)",
            ),
            Stats.calibrationLines(calibration),
        )
        assertEquals(emptyList(), Stats.calibrationLines(Calibration()))
        // A class's figure, and one coming, can be forgotten (LR-06); a kind's can't.
        assertEquals(listOf(null, "Homework|Physics", null, "Homework|German", null, null), Stats.calibrationRows(calibration).map { it.forget })
    }

    @Test
    fun `a slot counts on the day it was laid, as recorded, whatever the zone now`() {
        // Laid on the 9th at 23:30 in London, read where it's the 10th already: the 9th's.
        val late = slot("2026-10-09T23:30", SlotOutcome.Kept)
        val tokyo = ZoneId.of("Asia/Tokyo")
        val summary = Stats.summary(ActivityLog(slots = listOf(late)), Fixtures.at("2026-10-09T23:59"), tokyo)
        assertEquals(30, summary.days.single { it.date == LocalDate.parse("2026-10-09") }.slotMin)
        assertEquals(0, summary.days.single { it.date == LocalDate.parse("2026-10-10") }.slotMin)
    }

    @Test
    fun `each subject's minutes, pieces, deadlines met and time against the estimates, busiest first`() {
        fun slotIn(start: String, className: String?, worked: Int) = slot(start, SlotOutcome.Kept, worked).copy(className = className)
        val log = ActivityLog(
            slots = listOf(slotIn("2026-10-08T17:00", "12.9-PH1", 45), slotIn("2026-10-07T17:00", "12.9-PH1", 30), slotIn("2026-10-07T18:00", "German", 20), slotIn("2026-10-06T18:00", null, 10)),
            completions = listOf(
                completion("p1", "2026-10-08T18:00", "2026-10-09T08:30").copy(className = "12.9-PH1", estimateMin = 40, workedMin = 60),
                completion("g1", "2026-10-07T19:00", "2026-10-06T08:30").copy(className = "German"),
            ),
        )
        val rows = Stats.bySubject(log, mapOf("12.9-PH1" to "Physics"), now, LONDON)
        assertEquals(listOf("Physics", "German", Stats.OTHER), rows.map { it.subject })
        assertEquals(Stats.SubjectRow("Physics", 75, 2, 1, 1, 1, 1.5), rows[0])
        assertEquals(Stats.SubjectRow("German", 20, 1, 1, 0, 0, null), rows[1])
    }

    @Test
    fun `slots by weekday and hour, and the weekday hours they suggest`() {
        // Twelve kept on the weekdays of 5 to 9 October between 17:00 and 19:00, one late outlier at 22:00, one missed.
        val kept = (0 until 12).map { i -> slot("2026-10-0${5 + i % 5}T1${7 + i % 2}:00", SlotOutcome.Kept) }
        val log = ActivityLog(slots = kept + slot("2026-10-08T22:00", SlotOutcome.Kept) + slot("2026-10-08T17:00", SlotOutcome.Missed))
        val heat = Stats.heat(log, now, LONDON)
        assertEquals(1, heat[DayOfWeek.THURSDAY to 22]?.first)
        assertEquals(1, heat[DayOfWeek.THURSDAY to 17]?.second)
        val hours = Stats.suggestedWeekdayHours(heat)!!
        assertEquals(17 * 60, hours.startMin)
        assertEquals(19 * 60, hours.endMin)
        // Too few to go by: none.
        assertEquals(null, Stats.suggestedWeekdayHours(Stats.heat(ActivityLog(slots = kept.take(3)), now, LONDON)))
    }

    @Test
    fun `a week's rollup, and a period past what the log keeps reading the rollups`() {
        val log = ActivityLog(
            slots = listOf(slot("2026-10-06T17:00", SlotOutcome.Kept), slot("2026-10-07T17:00", SlotOutcome.Missed)),
            completions = listOf(completion("a", "2026-10-06T18:00", "2026-10-07T08:30")),
            blocks = listOf(BlockRecord(Fixtures.at("2026-10-06T17:10"), "com.example", "x")),
        )
        val week = Stats.rollup(log, LocalDate.parse("2026-10-05"), LONDON)
        assertEquals(WeekRollup("2026-10-05", 30, 1, 1, 1, 1, 0, 1, 1), week)
        // A rollup from five months back: in a 26-week period's totals, not a 2-week one's.
        val old = week.copy(week = "2026-05-04", minutes = 100, finished = 3)
        val withOld = log.copy(weeks = listOf(old))
        assertEquals(30 + 100, Stats.summary(withOld, now, LONDON, days = 26 * 7).slotMin)
        assertEquals(30, Stats.summary(withOld, now, LONDON).slotMin)
        // The week the log's end falls in (it keeps from 11 June): its rollup, whole, in place of
        // the records left of it.
        val boundary = week.copy(week = "2026-06-08", minutes = 200)
        val partial = log.copy(weeks = listOf(boundary), slots = log.slots + slot("2026-06-12T17:00", SlotOutcome.Kept))
        assertEquals(30 + 200, Stats.summary(partial, now, LONDON, days = 26 * 7).slotMin)
        // A week with no rollup keeps what's left of its records.
        assertEquals(30 + 30, Stats.summary(partial.copy(weeks = emptyList()), now, LONDON, days = 26 * 7).slotMin)
        // A rollup written at its review, before a slot kept later that Sunday, made again while the
        // log holds the week whole; one past that stays as it was.
        val late = log.copy(weeks = listOf(week, boundary), slots = log.slots + slot("2026-10-11T19:00", SlotOutcome.Kept))
        val refreshed = Stats.refreshedWeeks(late, now, LONDON)
        assertEquals(60, refreshed.single { it.week == "2026-10-05" }.minutes)
        assertEquals(200, refreshed.single { it.week == "2026-06-08" }.minutes)
    }

    @Test
    fun `this term began after the last run of days without school`() {
        // Made-up breaks: nine days in November, and the Christmas holidays.
        val breaks = listOf(LocalDate.parse("2026-11-14")..LocalDate.parse("2026-11-22"), LocalDate.parse("2026-12-19")..LocalDate.parse("2027-01-03"))
        fun school(d: LocalDate) = breaks.none { d in it } && d.dayOfWeek.value <= 5
        assertEquals(LocalDate.parse("2026-11-23"), Stats.termStart(LocalDate.parse("2026-12-04"), ::school))
        // On a weekend in term, and on the first day back.
        assertEquals(LocalDate.parse("2026-11-23"), Stats.termStart(LocalDate.parse("2026-12-06"), ::school))
        assertEquals(LocalDate.parse("2027-01-04"), Stats.termStart(LocalDate.parse("2027-01-04"), ::school))
        // In the holidays, this term is the one before them, not a day long.
        assertEquals(LocalDate.parse("2026-11-23"), Stats.termStart(LocalDate.parse("2026-12-30"), ::school))
        // None in the time looked back: that far.
        assertEquals(LocalDate.parse("2026-11-20").minusDays(120), Stats.termStart(LocalDate.parse("2026-11-20")) { it.dayOfWeek.value <= 5 })
    }

    @Test
    fun `the record as CSV, cells with commas, quotes and line breaks quoted`() {
        assertEquals("plain", Export.cell("plain"))
        assertEquals("\"a, b\"", Export.cell("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Export.cell("say \"hi\""))
        assertEquals("\"two\nlines\"", Export.cell("two\nlines"))
        // What a spreadsheet would take for a formula, shown as text; numbers as they are.
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, Export.cell("=HYPERLINK(\"x\")"))
        assertEquals("'+44 7700", Export.cell("+44 7700"))
        assertEquals("'@SUM(A1)", Export.cell("@SUM(A1)"))
        assertEquals("-5", Export.cell("-5"))
        assertEquals("0.75", Export.cell("0.75"))
        val files = Export.csv(ActivityLog(completions = listOf(completion("a", "2026-10-06T18:00", null))), Calibration(), LONDON)
        assertEquals(listOf("completions.csv", "slots.csv", "days.csv", "blocks.csv", "felix.csv", "calibration.csv"), files.keys.toList())
        assertEquals("task,title,source,kind,class,estimate_min,worked_min,due,hard_deadline,first_seen,done,assessment,note,by_hand,photo,your_estimate", files.getValue("completions.csv").lineSequence().first())
        assertEquals("a,a,Teams,Homework,,40,0,,true,2026-10-01T09:00,2026-10-06T18:00,,,false,false,false", files.getValue("completions.csv").lines()[1])
    }
}

