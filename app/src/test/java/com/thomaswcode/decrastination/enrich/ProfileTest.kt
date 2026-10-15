package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.sources.powerplanner.PpClass
import com.thomaswcode.decrastination.sources.powerplanner.PpSchedule
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who the student is, as every model call says it (AI-05, AI-06). */
class ProfileTest {
    private val now = Fixtures.at("2026-10-14T17:00")
    private val settings = Settings(classMap = mapOf("12.2-PH3" to "Physics"), profileLines = listOf("I play football on Tuesdays"))
    private val maths = TaskItem(
        id = "teams:m", source = Source.Teams, sourceId = "m", title = "Mechanics", className = "12.1-MA2", kind = Kind.Homework,
        firstSeenAt = now, lastSeenAt = now,
    )

    @Test
    fun `the year group moves on each September`() {
        assertEquals(12, Profile.yearGroup(Fixtures.at("2026-10-14T12:00"), LONDON, 2026))
        assertEquals(12, Profile.yearGroup(Fixtures.at("2027-08-31T12:00"), LONDON, 2026))
        assertEquals(13, Profile.yearGroup(Fixtures.at("2027-09-01T12:00"), LONDON, 2026))
    }

    @Test
    fun `the block says who the student is, their week, what they've said, and what's remembered`() {
        val school = SchoolCalendar(listOf(NoSchool("2026-10-26", "2026-10-30", "half term"), NoSchool("2026-09-01", "2026-09-02")))
        val memory = MemoryState(facts = listOf(Fact("sleep", "in bed by 22:30", now - 1), Fact("drums", "lessons on Fridays", now)), rules = listOf(Rule("don't message before 8", now)))
        val text = Profile.block(settings, listOf(maths), null, school, memory, now, LONDON)
        assertEquals(
            listOf(
                "About the student",
                "Year 12 at a UK sixth form. Subjects: Physics (12.2-PH3), 12.1-MA2.",
                "School days: break 10:45–11:15; lunch 13:15–14:35 (eating 13:40–13:55).",
                // Only the days off still to come.
                "Works school days 16:45–22:00, weekends and days off 08:30–22:30. No school: 26 Oct–30 Oct (half term).",
                "German textbook 1.",
                "From the student:",
                "- I play football on Tuesdays",
                "How Felix should behave:",
                "- don't message before 8",
                "Remembered:",
                "- drums: lessons on Fridays",
                "- sleep: in bed by 22:30",
            ),
            text.lines(),
        )
    }

    @Test
    fun `the school day comes from the week's lessons, study periods by day`() {
        val week = Timetable(
            "2026-08-24T00:00:00",
            listOf(
                PpClass("g", "German", listOf(PpSchedule("2026-09-05T08:50:00", "2026-09-05T09:45:00", dayOfWeek = 1))),
                PpClass("s", "Study Period", listOf(PpSchedule("2026-09-05T14:35:00", "2026-09-05T15:30:00", dayOfWeek = 2))),
            ),
        )
        val text = Profile.block(Settings(), emptyList(), week, SchoolCalendar(), MemoryState(), now, LONDON)
        assertTrue("School days: lessons 08:50–15:30; study periods Tue 14:35–15:30; break 10:45–11:15;" in text, text)
    }

    @Test
    fun `cut at its limit, what's remembered goes first, the oldest first, and the subjects stay`() {
        val many = MemoryState(facts = (1..100).map { Fact("topic $it", "a fact worth keeping about the week, number $it", now + it) })
        val text = Profile.block(settings, listOf(maths), null, SchoolCalendar(), many, now, LONDON)
        assertTrue(text.length <= Profile.MAX_CHARS, "${text.length}")
        assertTrue("Subjects: Physics (12.2-PH3)" in text)
        assertTrue("- topic 100: " in text)
        assertFalse("- topic 1: " in text)
    }
}
