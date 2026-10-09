package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.BlockRecord
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.ProtectionRecord
import com.thomaswcode.decrastination.data.SessionRecord
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

    private fun session(start: String, minutes: Int, completed: Boolean) = SessionRecord(
        taskId = "teams:t",
        kind = Kind.Homework,
        label = "t",
        plannedMin = 30,
        workedMin = minutes,
        startedAt = Fixtures.at(start),
        endedAt = Fixtures.at(start) + minutes * 60_000L,
        completed = completed,
    )

    @Test
    fun `the fortnight's totals and each day's, newest first, leaving out what's older`() {
        val log = ActivityLog(
            completions = listOf(
                completion("a", "2026-10-09T18:00", due = "2026-10-10T08:30"),
                completion("b", "2026-10-08T18:00", due = "2026-10-07T08:30"),
                completion("c", "2026-10-08T19:00", due = null),
                // Three weeks ago: out of the fortnight.
                completion("old", "2026-09-18T18:00", due = null),
            ),
            sessions = listOf(session("2026-10-09T17:00", 30, completed = true), session("2026-10-08T17:00", 12, completed = false)),
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
        assertEquals(3, stats.completions)
        // Two had deadlines; one was met.
        assertEquals(2, stats.dated)
        assertEquals(1, stats.onTime)
        assertEquals(2, stats.sessions)
        assertEquals(1, stats.sessionsFinished)
        assertEquals(42, stats.focusMin)
        assertEquals(listOf("com.google.android.youtube" to 2, "youtube.com" to 1), stats.topBlocked)
        assertEquals(1, stats.protectionProblems)
        assertEquals(1, stats.protectionRepaired)
        val today = stats.days[0]
        assertEquals(listOf(1, 30, 2), listOf(today.completions, today.focusMin, today.blocks))
        assertEquals(null, today.planDone)
        val yesterday = stats.days[1]
        assertEquals(listOf(2, 12, 1), listOf(yesterday.completions, yesterday.focusMin, yesterday.blocks))
        assertEquals(true, yesterday.planDone)
    }

    @Test
    fun `the calibration in words`() {
        val calibration = Calibration(
            multipliers = mapOf("Homework|Physics" to 1.34, "Homework|" to 1.1),
            marginDays = mapOf(Kind.Revision to 2),
            boxMin = mapOf(Kind.Homework to 45),
        )
        assertEquals(
            listOf(
                "Homework: takes 1.1× the estimate",
                "Homework, Physics: takes 1.3× the estimate",
                "Revision: finished 2 days before the deadline",
                "Homework: cut into 45-minute pieces",
            ),
            Stats.calibrationLines(calibration),
        )
        assertEquals(emptyList(), Stats.calibrationLines(Calibration()))
    }
}
