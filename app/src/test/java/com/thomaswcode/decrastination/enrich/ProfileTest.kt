package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who the student is, as every model call says it (AI-05, AI-06). */
class ProfileTest {
    private val now = Fixtures.at("2026-10-14T17:00")
    private val settings = Settings(classMap = mapOf("12.9-PH1" to "Physics"), profileLines = listOf("I play football on Tuesdays"))
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
                "Year 12 at a UK sixth form. Subjects: Physics (12.9-PH1), 12.1-MA2.",
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
    fun `what's passing says how long it holds, and its task by name`() {
        val memory = MemoryState(
            facts = listOf(Fact("cold", "feeling rough", now, until = Fixtures.at("2026-10-19T00:00")), Fact("maths", "can use the textbook's answers", now, taskId = maths.id)),
            rules = listOf(Rule("not tonight", now, until = Fixtures.at("2026-10-15T07:00"))),
        )
        val block = Profile.block(settings, listOf(maths), null, SchoolCalendar(), memory, now, LONDON)
        assertTrue("- not tonight (until Thu 15 Oct 07:00)" in block, block)
        assertTrue("- cold: feeling rough (until Sun 18 Oct)" in block, block)
        assertTrue("- maths: can use the textbook's answers (with “${maths.title}”)" in block, block)
        // Its task deleted, held while the delete can be undone: said so, not by its id.
        val gone = Profile.block(settings, emptyList(), null, SchoolCalendar(), memory, now, LONDON)
        assertTrue("- maths: can use the textbook's answers (with a task since deleted)" in gone, gone)
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
    fun `its own part held to its size, your lines first, and what's remembered whole within its own room`() {
        val lines = settings.copy(profileLines = (1..20).map { "a line of mine about the week, the " + "x".repeat(150) + " $it" })
        val many = MemoryState(
            facts = (1..150).map { Fact("topic $it", "a fact worth keeping about the week, number $it", now + it) },
            rules = listOf(Rule("don't message before 8", now)),
        )
        val text = Profile.block(lines, listOf(maths), null, SchoolCalendar(), many, now, LONDON)
        val own = text.substringBefore("How Felix should behave:")
        assertTrue(own.length <= Profile.MAX_CHARS, "${own.length}")
        assertTrue("Subjects: Physics (12.9-PH1)" in own)
        assertFalse(" 20" in own.lines().last())
        // Every instruction; the facts newest first, within their room.
        assertTrue("- don't message before 8" in text)
        assertTrue("- topic 150: " in text)
        assertFalse("- topic 1: " in text)
        val facts = text.substringAfter("Remembered:").lines().filter { it.startsWith("- ") }
        assertTrue(facts.sumOf { it.removePrefix("- ").length - 2 } <= com.thomaswcode.decrastination.core.Memories.FACT_BUDGET_CHARS)
    }

    @Test
    fun `the whole block is held to what the cap's dearest call counts on, the oldest facts going first`() {
        val many = MemoryState(
            facts = (1..2_000).map { Fact("t$it", "f", now + it) },
            rules = (1..20).map { Rule("rule $it " + "x".repeat(290), now) },
        )
        val long = settings.copy(
            classMap = (1..12).associate { "code-$it-" + "c".repeat(60) to "subject $it " + "s".repeat(60) },
            profileLines = (1..20).map { "line $it " + "x".repeat(150) },
        )
        val text = Profile.block(long, listOf(maths), null, SchoolCalendar(), many, now, LONDON)
        assertTrue(text.length <= Profile.MAX_BLOCK_CHARS, "${text.length}")
        assertTrue("- rule 20 " in text)
        assertTrue("- t2000: f" in text)
        assertFalse("- t1: f\n" in text + "\n")
    }
}
