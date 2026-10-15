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
import java.time.LocalDate
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
    }
}
