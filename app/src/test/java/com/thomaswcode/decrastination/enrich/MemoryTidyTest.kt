package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weekly tidy of what's remembered (AI-21). */
class MemoryTidyTest {
    private val now = Fixtures.at("2026-10-18T19:00")
    private val day = 24 * 3_600_000L

    private fun facts(n: Int, length: Int) = (1..n).map { Fact("topic $it", "x".repeat(length), now - (n - it + 1) * day) }

    @Test
    fun `due past seventy percent of the room, no sooner`() {
        assertFalse(MemoryTidy.due(facts(10, 400)))
        // 14 of "topic NN" and 300 characters: about 4,300 of 6,000.
        assertTrue(MemoryTidy.due(facts(14, 300)))
    }

    @Test
    fun `the model reads each fact with its day, oldest first, and today's`() {
        val text = MemoryTidy.describe(listOf(Fact("drums", "lessons on Fridays", now), Fact("sleep", "in bed by 22:30", now - day)), now, LONDON)
        assertEquals(
            listOf("Today is Sun 18 Oct 2026.", "Remembered, oldest first:", "- sleep: in bed by 22:30 (Sat 17 Oct 2026)", "- drums: lessons on Fridays (Sun 18 Oct 2026)"),
            text.lines(),
        )
        assertTrue("At most 4 lines" in MemoryTidy.system(12, 4))
    }

    @Test
    fun `the answer is kept as facts, a topic keeping its time, a merged one now's, at most two thirds of the lines`() {
        val before = listOf(
            Fact("football", "on Tuesdays", now - 3 * day),
            Fact("Football ", "on Tuesdays and Thursdays now", now - 2 * day),
            Fact("tennis", "on Saturdays", now - day),
        )
        val answer = MemoryTidy.Answer(
            listOf(MemoryTidy.Line("sport", "football Tue and Thu, tennis Sat"), MemoryTidy.Line("football", "Tue and Thu"), MemoryTidy.Line("tennis", "Saturdays")),
        )
        // Three in: two back at most, so the third goes.
        val after = MemoryTidy.after(answer, before, now)!!
        assertEquals(listOf("football" to now - 2 * day, "sport" to now), after.map { it.topic to it.at })
    }

    @Test
    fun `an answer that isn't a tidy is refused`() {
        val before = facts(3, 40)
        assertNull(MemoryTidy.after(MemoryTidy.Answer(), before, now))
        assertNull(MemoryTidy.after(MemoryTidy.Answer(listOf(MemoryTidy.Line("", "no topic"), MemoryTidy.Line("empty", " "))), before, now))
        // As long as before: no room made.
        assertNull(MemoryTidy.after(MemoryTidy.Answer(listOf(MemoryTidy.Line("all of it", "y".repeat(200)))), before, now))
    }

    @Test
    fun `it's laid on only facts still as they were, and the rules are never touched`() {
        val before = facts(3, 40)
        val after = listOf(Fact("all", "short", now))
        val rules = listOf(Rule("be blunter", now))
        assertEquals(MemoryState(after, rules), MemoryTidy.applied(MemoryState(before, rules), before, after))
        // One remembered while the call was out: left for next week's.
        assertNull(MemoryTidy.applied(MemoryState(before + Fact("new", "since", now), rules), before, after))
    }

    @Test
    fun `a fact with an until or a task isn't tidied but counts to its being due, and is kept as it was`() {
        val passing = listOf(Fact("cold", "x".repeat(2_000), now - day, until = now + day), Fact("trip", "x".repeat(2_000), now - 2 * day, taskId = "gmail:trip"))
        val plain = facts(2, 200)
        // Due by all of them: the passing ones alone fill most of the room.
        assertFalse(MemoryTidy.due(plain))
        assertTrue(MemoryTidy.due(plain + passing))
        assertEquals(plain, (plain + passing).filter(MemoryTidy::tidied))
        // Its answer for the plain ones, the passing ones beside it as they were.
        assertEquals((listOf(Fact("all", "short", now)) + passing).sortedBy { it.at }, MemoryTidy.withExpiring(plain + passing, listOf(Fact("all", "short", now))))
        // One of its topics one of theirs, which it wasn't shown: not a tidy, as one topic holds one fact.
        assertNull(MemoryTidy.withExpiring(plain + passing, listOf(Fact("Cold ", "merged", now))))
    }
}
