package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Felix's prompt and the reading of his answer (AI-22). */
class FelixTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val now = Fixtures.at("2026-10-12T17:10")
    private val settings = Settings()

    private fun task(id: String, minutes: Int, kind: Kind = Kind.Homework) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = kind,
        sourceEffortMin = minutes, firstSeenAt = now, lastSeenAt = now,
    )

    private val plan = Planner.plan(
        Planner.Input(listOf(task("Statics", 30), task("Vocab", 15)), Fixtures.at("2026-10-12T16:00"), LONDON, settings, windows = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))),
    ).copy(now = now)

    @Test
    fun `his instructions name him and say who he is`() {
        val text = FelixPrompts.system(settings.copy(felixName = "Max", felixLine = "a coach who doesn't let things slide"), 13)
        assertTrue(text.startsWith("You are Max: a coach who doesn't let things slide."))
        assertTrue("(Year 13)" in text)
        assertTrue("give an empty message" in text)
    }

    @Test
    fun `for a slot begun, he's told the piece, what earns it, today's and his last messages`() {
        val slot = plan.todaySlots.first { it.chunk?.title == "Statics" }
        fun hm(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(LONDON).toLocalTime().toString()
        val today = listOf(
            SlotRecord(date = "2026-10-12", key = "teams:x#-1#1", taskId = "teams:x", kind = Kind.Admin, label = "Form", plannedMin = 10, start = now - 3_600_000L, end = now - 3_000_000L,
                window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 10, at = now - 3_000_000L),
        )
        val recent = (1..8).map { FelixRecord("r$it", now - (9 - it) * 600_000L, "SlotStart", "message $it", replyText = if (it == 8) "on it" else null, repliedAt = now - 30_000L) }
        val text = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, today, recent, null, settings, now, LONDON)
        val lines = text.lines()
        assertEquals("Reason: SlotStart", lines[0])
        assertEquals("Now: Monday 12 October 2026, 17:10", lines[1])
        assertEquals("Piece: Statics, 30 min, slot ${hm(slot.start)}–${hm(slot.end)}; it earns its free time with a photo of it, which it needs to earn", lines[2])
        assertEquals("First action: none yet", lines[3])
        assertTrue(lines[4].startsWith("Today: 1 kept, 0 missed, 2 to come"), lines[4])
        // The last six of his messages, the reply under the one it answered.
        assertTrue("- Mon 16:10, you (SlotStart): message 3" in text && "message 2" !in text, text)
        assertTrue(text.endsWith("you (SlotStart): message 8\n- Mon 17:09, they replied: on it"), text)
    }

    @Test
    fun `for each reason, what it's about and nothing that isn't there`() {
        for (reason in FelixPrompts.Reason.entries) {
            val text = FelixPrompts.describe(reason, plan, null, task("Statics", 30), emptyList(), emptyList(), "Is the test on the 12th or the 19th?".takeIf { reason == FelixPrompts.Reason.Question }, settings, now, LONDON, freeMin = 25)
            assertTrue(text.startsWith("Reason: $reason\nNow: "), text)
            assertTrue("Task: Statics" in text && "Free time now: 25 min" in text && "Recent" !in text, text)
            assertEquals(reason == FelixPrompts.Reason.Question, "Question: Is the test on the 12th or the 19th?" in text)
        }
        // A short piece earns on its tick; a longer one that isn't written work, by its source.
        val short = plan.todaySlots.first { it.chunk?.title == "Vocab" }
        assertTrue("earns its free time on its tick" in FelixPrompts.describe(FelixPrompts.Reason.SlotEnd, plan, short, null, emptyList(), emptyList(), null, settings, now, LONDON))
    }

    @Test
    fun `his message is told its task's own text, cut short, with what comes with it`() {
        // D-93: as much of the instructions as a piece's first steps are read from.
        val sheet = task("Statics", 30).copy(detail = "Answer questions 1 to 6.\n" + "x".repeat(2_000) + "\nHand it in on Friday.", extra = mapOf(TeamsRows.EXTRA_LINKS to "https://example.org/sheet"))
        val text = FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, sheet, emptyList(), emptyList(), "Which questions?", settings, now, LONDON)
        assertTrue("Its task's own text (Teams; links: https://example.org/sheet):\n  Answer questions 1 to 6." in text, text)
        // Its start and its end, the middle left out.
        assertTrue("  [… ${sheet.detail.length - FelixPrompts.TEXT} characters left out …]" in text, text)
        assertTrue("\n  Hand it in on Friday." in text, text)
        // Where its reading summarised all of it, that in place of the start and end.
        val summarised = sheet.copy(enrichment = com.thomaswcode.decrastination.core.Enrichment(inputHash = com.thomaswcode.decrastination.core.Enrichments.inputHash(sheet), by = "test", at = now, summary = "Questions 1 to 6, handed in on Friday."))
        val told = FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, summarised, emptyList(), emptyList(), "Which questions?", settings, now, LONDON)
        assertTrue("  Its reading's summary of all ${sheet.detail.length} characters: Questions 1 to 6, handed in on Friday." in told, told)
        // With none of its own, nothing's said of it.
        assertTrue("own text" !in FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, task("Statics", 30), emptyList(), emptyList(), "Which?", settings, now, LONDON))
    }

    @Test
    fun `his answer is kept within bounds`() {
        val long = "word ".repeat(80).trim()
        val answer = FelixAnswers.parse("""{"message":"$long","replies":["Starting now"," ","Give me 15 minutes, I'm finishing dinner first","Starting now","Not tonight","Later"],"nextCheckMin":2,"memories":[]}""")!!
        assertTrue(answer.message.length <= FelixAnswers.MAX_MESSAGE && answer.message.endsWith("word…"), answer.message)
        assertEquals(listOf("Starting now", "Give me 15 minutes, I'm finishing…", "Not tonight"), answer.replies)
        assertEquals(FelixAnswers.MIN_CHECK_MIN, answer.nextCheckMin)
        // No next check given: the default is the caller's (20 minutes, Q-31); an empty message, his silence.
        assertEquals(FelixAnswer(), FelixAnswers.parse("""{"message":"","replies":[],"nextCheckMin":null,"memories":[]}"""))
        assertNull(FelixAnswers.parse("not JSON"))
    }

    @Test
    fun `a message past its room ends at its last whole sentence, the morning's with more room`() {
        val first = "Ten slots today, starting with handing in the physics sheet at 10:55, then the translation question and the vocab in the lunch study period."
        val second = "The evening runs from the essay plan at five to the statics questions at nine, with a break at seven for dinner, and nothing after half past nine."
        val message = "$first $second"
        assertTrue(message.length in (FelixAnswers.MAX_MESSAGE + 1)..FelixAnswers.MAX_MORNING, "${message.length}")
        // Over a nag's 240: its first sentence whole, not cut mid-way with an ellipsis.
        assertEquals(first, FelixAnswers.parse("""{"message":"$message","replies":[],"nextCheckMin":null,"memories":[]}""")!!.message)
        // The morning's room holds both.
        assertEquals(message, FelixAnswers.parse("""{"message":"$message","replies":[],"nextCheckMin":null,"memories":[]}""", FelixAnswers.MAX_MORNING)!!.message)
        // A sentence too short to stand for the rest: cut at a word instead.
        val short = "Go. " + "word ".repeat(80).trim()
        assertTrue(FelixAnswers.parse("""{"message":"$short","replies":[],"nextCheckMin":null,"memories":[]}""")!!.message.endsWith("word…"))
    }
}
