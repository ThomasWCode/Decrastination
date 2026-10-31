package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.CheckInDraft
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.DayRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sunday check-in as Felix asks it (LR-02). */
class CheckInFlowTest {
    private fun slot(date: String, label: String, outcome: SlotOutcome, task: String = label) = SlotRecord(
        date = date, key = "$task#0", taskId = task, kind = Kind.Homework, label = label, plannedMin = 30,
        start = Fixtures.at("${date}T17:00"), end = Fixtures.at("${date}T17:30"), window = WindowKind.Evening, outcome = outcome, workedMin = 0, at = Fixtures.at("${date}T17:40"),
    )

    @Test
    fun `five questions, each reply its answer, the first a number, then the week's check-in`() {
        var draft = CheckInDraft("2026-10-05")
        assertEquals("ok so, 1 to 5, how was the week?", CheckInFlow.question(draft))
        assertEquals(listOf("1", "2", "3", "4", "5"), CheckInFlow.replies(draft, ActivityLog(), LONDON))
        assertNull(CheckInFlow.answered(draft, "pretty good"))
        // The number alone: not a digit found in other words.
        for (reply in listOf("10", "15", "I wanted 5 but got 2", "0", "6")) assertNull(CheckInFlow.answered(draft, reply), reply)
        assertEquals(3, CheckInFlow.answered(draft, " 3 ")!!.feel)
        // Out of five, as a mate would say it (D-57): the number's what's kept.
        assertEquals(listOf(4, 4, 2), listOf("4/5", "4 out of 5", "2 / 5").map { CheckInFlow.answered(draft, it)!!.feel })
        draft = CheckInFlow.answered(draft, "4")!!
        assertEquals("what did you put off, and why?", CheckInFlow.question(draft))
        for (answer in listOf("The essay, it felt huge", "Football", "Start earlier", "Before tea")) draft = CheckInFlow.answered(draft, answer)!!
        assertTrue(CheckInFlow.done(draft))
        assertEquals(CheckIn("2026-10-05", 10, 4, "The essay, it felt huge", "Football", "Start earlier", "Before tea"), CheckInFlow.checkIn(draft, 10))
    }

    @Test
    fun `what was put off is offered as replies to the second question`() {
        val log = ActivityLog(
            slots = listOf(slot("2026-10-06", "Moments Prep: Q1-8", SlotOutcome.Missed), slot("2026-10-07", "Essay: plan", SlotOutcome.Moved), slot("2026-10-08", "Essay: plan", SlotOutcome.Moved)),
            days = listOf(DayRecord("2026-10-09", plannedMin = 60, chunks = emptyList(), full = false)),
        )
        assertEquals(listOf("Moments Prep, missed", "Essay, moved 2 times", "Friday's plan"), CheckInFlow.replies(CheckInDraft("2026-10-05", step = 1), log, LONDON))
    }
}
