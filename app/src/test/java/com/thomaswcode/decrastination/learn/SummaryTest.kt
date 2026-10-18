package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.SummaryPrompts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weekly summary for home (NF-02). */
class SummaryTest {
    @Test
    fun `the week's numbers, its note, what was missed and what was asked for go in`() {
        fun slot(day: String, outcome: SlotOutcome) = SlotRecord(
            date = day, key = "teams:a#0", taskId = "teams:a", kind = Kind.Homework, label = "Moments Prep: Q1-8", plannedMin = 30,
            start = Fixtures.at("${day}T17:00"), end = Fixtures.at("${day}T17:30"), window = WindowKind.Evening, outcome = outcome,
            workedMin = if (outcome.kept) 30 else 0, at = Fixtures.at("${day}T17:35"),
        )
        val log = ActivityLog(
            slots = listOf(slot("2026-10-06", SlotOutcome.Kept), slot("2026-10-07", SlotOutcome.Missed)),
            completions = listOf(CompletionRecord("teams:a", "Moments Prep", Source.Teams, Kind.Homework, null, 40, 30, Fixtures.at("2026-10-08T08:30"), 0, Fixtures.at("2026-10-07T20:00"))),
            reviews = listOf(WeeklyReview(Fixtures.at("2026-10-11T19:00"), listOf("A steady week."), by = "claude", week = "2026-10-05")),
        )
        val asked = listOf(Instruction("i", "Nothing on Saturday", at = Fixtures.at("2026-10-09T18:00")))
        val text = Summary.input(log, asked, "2026-10-05", LONDON)
        assertTrue("- 30 minutes of work in 1 pieces kept, 1 missed, 0 moved; 0 earned free time." in text, text)
        assertTrue("- Finished: 1, 1 of the 1 with a deadline by it." in text, text)
        assertTrue("Missed: Moments Prep: Q1-8 (Wed 7 Oct)" in text, text)
        assertTrue("The review's note: A steady week." in text, text)
        assertTrue("Changes asked for: “Nothing on Saturday”" in text, text)
        assertEquals("Here's my week.", SummaryPrompts.parse("""{"summary":"  Here's my week. "}"""))
        assertNull(SummaryPrompts.parse("""{"summary":""}"""))
    }
}
