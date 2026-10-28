package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.core.SetBy
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import java.time.Instant
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
        fun hm(at: Long) = Instant.ofEpochMilli(at).atZone(LONDON).toLocalTime().toString()
        val today = listOf(
            SlotRecord(date = "2026-10-12", key = "teams:x#-1#1", taskId = "teams:x", kind = Kind.Admin, label = "Form", plannedMin = 10, start = now - 3_600_000L, end = now - 3_000_000L,
                window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 10, at = now - 3_000_000L),
        )
        // His eight in the chat, your reply to the last, and his answer to you, which isn't one of his own.
        val recent = (1..8).map { ChatMessage("r$it", Speaker.Felix, "message $it", now - (9 - it) * 600_000L, kind = MessageKind.SlotStart) } +
            ChatMessage("y8", Speaker.You, "on it", now - 30_000L, replyTo = "r8", answeredAt = now - 20_000L) +
            ChatMessage("c8", Speaker.Felix, "good", now - 20_000L)
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
    fun `how he feels is said under today's slots, and nothing at neutral (D-119)`() {
        val slot = plan.todaySlots.first { it.chunk?.title == "Statics" }
        val strong = Mood.Feeling(Mood.Level.Strong, listOf("3 slots missed today"))
        val lines = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON, feeling = strong).lines()
        assertTrue(lines[4].startsWith("Today: "), lines[4])
        assertEquals("How you feel, at most: let down, strongly (3 slots missed today)", lines[5])
        val neutral = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON, feeling = Mood.NEUTRAL)
        assertEquals(FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON), neutral)
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
        val summarised = sheet.copy(enrichment = Enrichment(inputHash = Enrichments.inputHash(sheet), by = "test", at = now, summary = "Questions 1 to 6, handed in on Friday."))
        val told = FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, summarised, emptyList(), emptyList(), "Which questions?", settings, now, LONDON)
        assertTrue("  Its reading's summary of all ${sheet.detail.length} characters: Questions 1 to 6, handed in on Friday." in told, told)
        // With none of its own, nothing's said of it.
        assertTrue("own text" !in FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, task("Statics", 30), emptyList(), emptyList(), "Which?", settings, now, LONDON))
        // A link (or a worksheet attached) with no text is the work too: said, with what comes with it.
        val linked = task("Statics", 30).copy(extra = mapOf(TeamsRows.EXTRA_LINKS to "https://example.org/sheet"))
        val bare = FelixPrompts.describe(FelixPrompts.Reason.Question, plan, null, linked, emptyList(), emptyList(), "Which?", settings, now, LONDON)
        assertTrue("Its task's own text (Teams; links: https://example.org/sheet):\n  (no text of its own)" in bare, bare)
    }

    @Test
    fun `his first message after three quiet hours opens with a greeting, and only that one (D-57)`() {
        val slot = plan.todaySlots.first { it.chunk?.title == "Statics" }
        fun his(text: String, ago: Long) = ChatMessage("f$ago", Speaker.Felix, text, now - ago, kind = MessageKind.SlotStart)
        fun greets(vararg said: ChatMessage) = "Your first message in a while: open with a quick greeting" in
            FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), said.toList(), null, settings, now, LONDON)
        // Nothing said at all, or nothing for three hours: a greeting.
        assertTrue(greets())
        assertTrue(greets(his("statics at 17:00", FelixPrompts.GREET_AFTER_MIN * 60_000L)))
        // His message an hour ago, or yours ten minutes ago after his of four hours ago: none.
        assertTrue(!greets(his("statics at 17:00", 3_600_000L)))
        assertTrue(!greets(his("statics at 17:00", 4 * 3_600_000L), ChatMessage("y1", Speaker.You, "what's next?", now - 600_000L)))
        // His silence, an empty message, isn't something said.
        assertTrue(greets(his("", 600_000L)))
        assertTrue("Where you're told it's your first message in a while, start with a quick greeting" in FelixPrompts.system(settings, 12))
    }

    @Test
    fun `he's told what's done of today's tasks and of his messages', so a step ticked isn't asked for again (D-57)`() {
        val essay = task("Essay", 60).copy(subSteps = listOf(SubStep("Pick a question", 15, done = true), SubStep("Research", 25), SubStep("Write", 20)))
        val handedIn = task("Lab write-up", 30).copy(status = Status.Done, doneAt = now - 3_600_000L)
        val dropped = task("Old form", 10).copy(mine = Mine(notATask = SetBy(true, By.You)))
        // An Anki quota gone by undone isn't finished; a task you said is the same work as another is off the list.
        val gone = task("Cards", 20).copy(status = Status.Missed)
        val same = task("Copy", 30).copy(mine = Mine(sameAs = SetBy(essay.id, By.You)))
        val notToday = task("Vectors", 30).copy(subSteps = listOf(SubStep("Questions 1 to 6", 15, done = true), SubStep("Questions 7 to 12", 15)))
        val day = Planner.plan(
            Planner.Input(listOf(essay, task("Statics", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, settings, windows = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))),
        ).copy(now = now)
        // His messages about the lab write-up and the old form; the vectors in neither today's pieces nor his messages.
        val recent = listOf(
            ChatMessage("f1", Speaker.Felix, "lab write-up's due tomorrow", now - 7_200_000L, about = About(taskId = handedIn.id), kind = MessageKind.SlotEnd),
            ChatMessage("f2", Speaker.Felix, "old form still not in", now - 5_400_000L, about = About(taskId = dropped.id), kind = MessageKind.Question),
            ChatMessage("f3", Speaker.Felix, "cards still to do", now - 3_600_000L, about = About(taskId = gone.id), kind = MessageKind.SlotEnd),
            ChatMessage("f4", Speaker.Felix, "the copy's still open", now - 1_800_000L, about = About(taskId = same.id), kind = MessageKind.SlotEnd),
        )
        val tasks = listOf(essay, task("Statics", 30), handedIn, dropped, gone, same, notToday)
        val text = FelixPrompts.describe(FelixPrompts.Reason.Morning, day, null, null, emptyList(), recent, null, settings, now, LONDON, tasks = tasks)
        val line = text.lines().single { it.startsWith("Done so far") }
        assertEquals(
            "Done so far, of today's tasks and the ones your messages below are about: “Essay”: 1 of 3 steps ticked, “Pick a question”; " +
                "“Lab write-up”: finished Mon 16:10; “Old form”: not a task, as they said, so off the list; “Cards”: no longer due, not done; " +
                "“Copy”: the same work as “Essay”, as they said, so off the list",
            line,
        )
        // Before his messages, which it answers; with nothing done, no line.
        assertTrue(text.indexOf(line) < text.indexOf("Recent, oldest first:"), text)
        assertTrue("Done so far" !in FelixPrompts.describe(FelixPrompts.Reason.Morning, day, null, null, emptyList(), recent, null, settings, now, LONDON, tasks = listOf(task("Statics", 30))))
        // Of many ticked, the last three named.
        val long = essay.copy(subSteps = (1..5).map { SubStep("Part $it", 10, done = it <= 4) })
        assertTrue("“Essay”: 4 of 5 steps ticked, “Part 2”, “Part 3”, “Part 4”" in FelixPrompts.describe(FelixPrompts.Reason.SlotStart, day, null, long, emptyList(), emptyList(), null, settings, now, LONDON, tasks = listOf(long)))
        assertTrue("What you're told is done is done: don't ask for it, even where your recent messages did." in FelixPrompts.system(settings, 12))
    }

    @Test
    fun `his answer is kept within bounds`() {
        val long = "word ".repeat(80).trim()
        val answer = FelixAnswers.parse("""{"message":"$long","replies":["Starting now"," ","Give me 15 minutes, I'm finishing dinner first","Starting now","Not tonight","Later"],"nextCheckMin":2,"memories":[]}""")!!
        assertTrue(answer.message.length <= FelixAnswers.MAX_MESSAGE && answer.message.endsWith("word…"), answer.message)
        // How long it was as he wrote it, before it was cut: what the scenarios' report checks it by.
        assertEquals(long.length, FelixAnswers.messageLength("""{"message":"  $long ","replies":[]}"""))
        assertEquals(0, FelixAnswers.messageLength("not JSON"))
        assertEquals(listOf("Starting now", "Give me 15 minutes, I'm finishing…", "Not tonight"), answer.replies)
        assertEquals(FelixAnswers.MIN_CHECK_MIN, answer.nextCheckMin)
        // No next check given: the default is the caller's (20 minutes, Q-31); an empty message, his silence.
        assertEquals(FelixAnswer(), FelixAnswers.parse("""{"message":"","replies":[],"nextCheckMin":null,"memories":[]}"""))
        assertNull(FelixAnswers.parse("not JSON"))
    }

    @Test
    fun `the steps he rebalanced in a morning's answer are read beside it, every one (D-110)`() {
        val one = """{"taskId":"teams:sheet","stepIndex":0,"firstTitle":"Question 1","firstMinutes":25,"secondTitle":"Questions 2 and 3","secondMinutes":40}"""
        val text = """{"message":"morning.","replies":[],"nextCheckMin":null,"memories":[],"rebalances":[$one,$one,$one,$one]}"""
        val read = FelixAnswers.rebalances(text)
        // A fourth, beyond what he's asked about, is read too: it isn't one, so his message isn't sent.
        assertEquals(4, read.size)
        assertEquals(Rebalance.Given("teams:sheet", 0, "Question 1", 25, "Questions 2 and 3", 40), read.first())
        // Read beside his answer, which keeps none of them.
        assertEquals("morning.", FelixAnswers.parse(text)!!.message)
        assertEquals(emptyList(), FelixAnswers.rebalances("""{"message":"morning."}"""))
        assertEquals(emptyList(), FelixAnswers.rebalances("not JSON"))
        assertTrue("Never cut one piece of work by time" in FelixPrompts.system(Settings(), 12))
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
