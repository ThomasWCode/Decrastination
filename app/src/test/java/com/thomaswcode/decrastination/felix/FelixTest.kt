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
import com.thomaswcode.decrastination.learn.CheckIn
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
        val text = FelixPrompts.system(settings.copy(felixName = "Max", felixLine = "a coach who doesn't let things slide"))
        assertTrue(text.startsWith("You're Max, 22, British, middle-class. The app describes you to them as “a coach who doesn't let things slide”."), text)
        // About their age, and no year of theirs said anywhere (D-120).
        assertTrue("about your age" in text && "Year" !in text, text)
        assertTrue("give three empty texts" in text)
        // His voice in his own register, then the pairs, then the app's rules.
        assertTrue(text.indexOf("how you text\n- one thought a text") < text.indexOf("how you feel\n") && text.indexOf("how you feel\n") < text.indexOf("the difference (made up"), text)
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
        // The story first (D-120), here none, then the details.
        assertTrue(text.startsWith("Today so far (Monday 12 October 2026, 17:10):\n- nothing yet\n"), text)
        val lines = text.lines().dropWhile { it != "Details:" }
        assertEquals("Reason: SlotStart", lines[1])
        assertEquals("Piece: Statics, 30 min, slot ${hm(slot.start)}–${hm(slot.end)}; it earns its free time with a photo of it, which it needs to earn", lines[2])
        assertEquals("First action: none yet", lines[3])
        assertTrue(lines[4].startsWith("Today: 1 kept, 0 missed, 2 to come"), lines[4])
        // With no story, his last six messages, the reply under the one it answered, the app's kinds left out.
        assertTrue("- Mon 16:10, you texted: message 3" in text && "message 2" !in text && "SlotStart" !in text.substringAfter("Reason: SlotStart"), text)
        assertTrue(text.endsWith("you texted: message 8\n- Mon 17:09, they replied: on it"), text)
        // Those in today's story aren't said again below it.
        val since = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, today, recent, null, settings, now, LONDON, story = listOf("17:00, you texted: “message 8”"), since = now - 2_000_000L)
        assertTrue(since.startsWith("Today so far (Monday 12 October 2026, 17:10):\n- 17:00, you texted: “message 8”\n"), since)
        assertTrue("you texted: message 5" in since && "you texted: message 6" !in since, since)
    }

    @Test
    fun `how he feels is said under today's slots, and nothing at neutral (D-119)`() {
        val slot = plan.todaySlots.first { it.chunk?.title == "Statics" }
        val strong = Mood.Feeling(Mood.Level.Strong, listOf("3 slots missed today"))
        val lines = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON, feeling = strong, diary = listOf("sun 11 oct: all 2 done")).lines()
        // Under the story, in a person's words, with nothing of what it rests on (D-120); then the diary.
        assertEquals(listOf("- nothing yet", "How you feel, at most: properly fed up"), lines.subList(1, 3))
        assertEquals(listOf("Your diary:", "- sun 11 oct: all 2 done"), lines.dropWhile { it != "Your diary:" }.take(2))
        assertTrue(lines.none { "3 slots missed" in it })
        val neutral = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON, feeling = Mood.NEUTRAL)
        assertEquals(FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), emptyList(), null, settings, now, LONDON), neutral)
    }

    @Test
    fun `for each reason, what it's about and nothing that isn't there`() {
        for (reason in FelixPrompts.Reason.entries) {
            val text = FelixPrompts.describe(reason, plan, null, task("Statics", 30), emptyList(), emptyList(), "Is the test on the 12th or the 19th?".takeIf { reason == FelixPrompts.Reason.Question }, settings, now, LONDON, freeMin = 25)
            assertTrue(text.startsWith("Today so far (") && "\nDetails:\nReason: $reason\n" in text, text)
            assertTrue("Task: Statics" in text && "Free time now: 25 min" in text && "Your texts before today" !in text, text)
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
        fun greets(vararg said: ChatMessage) = "Your first text in a while: open with a quick greeting" in
            FelixPrompts.describe(FelixPrompts.Reason.SlotStart, plan, slot, null, emptyList(), said.toList(), null, settings, now, LONDON)
        // Nothing said at all, or nothing for three hours: a greeting.
        assertTrue(greets())
        assertTrue(greets(his("statics at 17:00", FelixPrompts.GREET_AFTER_MIN * 60_000L)))
        // His message an hour ago, or yours ten minutes ago after his of four hours ago: none.
        assertTrue(!greets(his("statics at 17:00", 3_600_000L)))
        assertTrue(!greets(his("statics at 17:00", 4 * 3_600_000L), ChatMessage("y1", Speaker.You, "what's next?", now - 600_000L)))
        // His silence, an empty message, isn't something said.
        assertTrue(greets(his("", 600_000L)))
        assertTrue("Where you're told it's your first text in a while, open with a quick greeting" in FelixPrompts.system(settings))
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
            "Done so far, of today's tasks and the ones your texts are about: “Essay”: 1 of 3 steps ticked, “Pick a question”; " +
                "“Lab write-up”: finished Mon 16:10; “Old form”: not a task, as they said, so off the list; “Cards”: no longer due, not done; " +
                "“Copy”: the same work as “Essay”, as they said, so off the list",
            line,
        )
        // Before his messages, which it answers; with nothing done, no line.
        assertTrue(text.indexOf(line) < text.indexOf("Your texts before today, oldest first:"), text)
        assertTrue("Done so far" !in FelixPrompts.describe(FelixPrompts.Reason.Morning, day, null, null, emptyList(), recent, null, settings, now, LONDON, tasks = listOf(task("Statics", 30))))
        // Of many ticked, the last three named.
        val long = essay.copy(subSteps = (1..5).map { SubStep("Part $it", 10, done = it <= 4) })
        assertTrue("“Essay”: 4 of 5 steps ticked, “Part 2”, “Part 3”, “Part 4”" in FelixPrompts.describe(FelixPrompts.Reason.SlotStart, day, null, long, emptyList(), emptyList(), null, settings, now, LONDON, tasks = listOf(long)))
        assertTrue("What you're told is done is done: don't ask for it, even where your earlier texts did." in FelixPrompts.system(settings))
    }

    @Test
    fun `his answer is kept within bounds`() {
        val long = "word ".repeat(80).trim()
        val answer = FelixAnswers.parse("""{"texts":["$long"],"send":1,"replies":["Starting now"," ","Give me 15 minutes, I'm finishing dinner first","Starting now","Not tonight","Later"],"nextCheckMin":2,"memories":[]}""")!!
        assertTrue(answer.message.length <= FelixAnswers.MAX_MESSAGE && answer.message.endsWith("word…"), answer.message)
        // How long it was as he wrote it, before it was cut: what the scenarios' report checks it by.
        assertEquals(long.length, FelixAnswers.messageLength("""{"texts":["  $long "],"send":1,"replies":[]}"""))
        assertEquals(0, FelixAnswers.messageLength("not JSON"))
        assertEquals(listOf("Starting now", "Give me 15 minutes, I'm finishing…", "Not tonight"), answer.replies)
        assertEquals(FelixAnswers.MIN_CHECK_MIN, answer.nextCheckMin)
        // No next check given: the default is the caller's (20 minutes, Q-31); three empty texts, his silence.
        assertEquals(FelixAnswer(), FelixAnswers.parse("""{"texts":["","",""],"send":1,"replies":[],"nextCheckMin":null,"memories":[]}"""))
        assertNull(FelixAnswers.parse("not JSON"))
        // His note to himself comes first (D-119), read with the answer and kept with it, held to its room; none, empty.
        val noted = """{"feeling":" irritated: that's two ","texts":["physics went by too"],"send":1,"replies":[],"nextCheckMin":null,"memories":[]}"""
        assertEquals(FelixAnswer("physics went by too", feeling = "irritated: that's two"), FelixAnswers.parse(noted))
        assertEquals(FelixAnswers.MAX_FEELING, FelixAnswers.parse("""{"feeling":"${"x".repeat(900)}","texts":["hey"],"send":1,"replies":[]}""")?.feeling?.length)
        assertEquals("", FelixAnswers.parse("""{"texts":["hey"],"send":1,"replies":[]}""")?.feeling)
    }

    @Test
    fun `of his three texts the one he chose is sent, the others kept, and a look is two texts at most (D-120)`() {
        val three = """{"feeling":"flat","texts":["maths went too\nphysics is on at 19:40"," two gone tonight ","you said 7 for the maths"],"send":2,"replies":[],"nextCheckMin":10,"memories":[]}"""
        val chosen = FelixAnswers.parse(three)!!
        assertEquals("two gone tonight", chosen.message)
        assertEquals(listOf("maths went too\nphysics is on at 19:40", "you said 7 for the maths"), chosen.unsent)
        // Chosen past the three: the first. Lines past the second joined onto it; empty lines gone.
        val past = FelixAnswers.parse("""{"texts":["one\n\n two \nthree","b"],"send":7,"replies":[]}""")!!
        assertEquals("one\ntwo three" to listOf("b"), past.message to past.unsent)
        // A text past its room ends at its first line's end where that leaves half the room used: the second text left out.
        val first = "word ".repeat(30).trim()
        assertEquals(first, FelixAnswers.message(first + "\n" + "more ".repeat(40).trim(), FelixAnswers.MAX_MESSAGE))
        // His messages from his prompt shown as texts, a line each; the morning, his chat's answers and yours whole.
        fun said(kind: MessageKind, from: Speaker = Speaker.Felix, byApp: Boolean = false) = ChatMessage("m", from, "a\nb", now, kind = kind, byApp = byApp)
        assertEquals(listOf("a", "b"), FelixAnswers.texts(said(MessageKind.SlotEnd)))
        assertEquals(listOf("a\nb"), FelixAnswers.texts(said(MessageKind.Morning)))
        assertEquals(listOf("a\nb"), FelixAnswers.texts(said(MessageKind.Chat)))
        assertEquals(listOf("a\nb"), FelixAnswers.texts(said(MessageKind.Reminder, byApp = true)))
        assertEquals(listOf("a\nb"), FelixAnswers.texts(said(MessageKind.Chat, from = Speaker.You)))
    }

    @Test
    fun `today's story is told in order, with what was said and his notes, and his diary a line a day (D-120)`() {
        val today = listOf(
            SlotRecord(date = "2026-10-12", key = "teams:a#-1#1", taskId = "teams:a", kind = Kind.Homework, label = "Physics sheet", plannedMin = 30, start = Fixtures.at("2026-10-12T16:00"), end = Fixtures.at("2026-10-12T16:30"),
                window = WindowKind.Evening, outcome = SlotOutcome.Missed, workedMin = 0, at = Fixtures.at("2026-10-12T16:35")),
        )
        val start = Fixtures.at("2026-10-12T00:00")
        val messages = listOf(
            ChatMessage("old", Speaker.Felix, "yesterday's", start - 3_600_000L, kind = MessageKind.SlotEnd),
            ChatMessage("f1", Speaker.Felix, "physics went by\nwhat happened", Fixtures.at("2026-10-12T16:36"), About(taskId = "teams:a", taskTitle = "Physics sheet"), kind = MessageKind.SlotEnd, feeling = "bit puzzled"),
            ChatMessage("y1", Speaker.You, "starting at 7", Fixtures.at("2026-10-12T16:40"), replyTo = "f1"),
            ChatMessage("y2", Speaker.You, "can we push maths", Fixtures.at("2026-10-12T16:50")),
            ChatMessage("c2", Speaker.Felix, "done, maths at 7:30", Fixtures.at("2026-10-12T16:51")),
            ChatMessage("silent", Speaker.Felix, "", Fixtures.at("2026-10-12T16:55"), kind = MessageKind.SlotStart),
        )
        assertEquals(
            listOf(
                "16:00–16:30 Physics sheet: went by undone",
                "16:36, you texted about Physics sheet: “physics went by / what happened” (you felt: “bit puzzled”)",
                "16:40, they replied: “starting at 7”",
                "16:50, they said in the chat: “can we push maths”",
                "16:51, you answered in the chat: “done, maths at 7:30”",
            ),
            Story.today(today, messages, start, LONDON),
        )
        // For the chat, its own exchanges are its turns.
        assertEquals(2, Story.today(today, messages, start, LONDON, chat = true).size)
        // The diary: a line a day with a slot on record, his levels that day, the week before in a line, the week's check-in.
        val slots = listOf(
            SlotRecord(date = "2026-10-10", key = "teams:a#-1#1", taskId = "teams:a", kind = Kind.Homework, label = "Physics sheet", plannedMin = 30, start = 0, end = 0, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 30, at = 0),
            SlotRecord(date = "2026-10-11", key = "teams:b#-1#1", taskId = "teams:b", kind = Kind.Homework, label = "Essay", plannedMin = 30, start = 0, end = 1, window = WindowKind.Evening, outcome = SlotOutcome.Missed, workedMin = 0, at = 2),
            SlotRecord(date = "2026-10-11", key = "teams:c#-1#1", taskId = "teams:c", kind = Kind.Homework, label = "Maths", plannedMin = 30, start = 0, end = 1, window = WindowKind.Evening, outcome = SlotOutcome.KeptLate, workedMin = 30, at = 5),
            SlotRecord(date = "2026-10-02", key = "teams:d#-1#1", taskId = "teams:d", kind = Kind.Homework, label = "Old", plannedMin = 30, start = 0, end = 0, window = WindowKind.Evening, outcome = SlotOutcome.Kept, workedMin = 30, at = 0),
        )
        val moods = listOf(ChatMessage("m", Speaker.Felix, "essay went by", Fixtures.at("2026-10-11T19:00"), kind = MessageKind.SlotEnd, mood = "Firm"))
        val checkIn = CheckIn("2026-10-05", Fixtures.at("2026-10-11T18:00"), 3, "the essay", "", "", "low")
        assertEquals(
            listOf(
                "sat 10 oct: 1 of 1 done",
                "sun 11 oct: 1 of 2 done; gone by: Essay; made up late: Maths; you were not impressed once",
                "the week before: 1 of 1 done",
                "their check-in, sun 11 oct, in their words: the week felt 3 of 5; put off: “the essay”; energy: “low”",
            ),
            Story.diary(slots, listOf(checkIn), moods, monday, LONDON),
        )
    }

    @Test
    fun `the steps he rebalanced in a morning's answer are read beside it, every one (D-110)`() {
        val one = """{"taskId":"teams:sheet","stepIndex":0,"firstTitle":"Question 1","firstMinutes":25,"secondTitle":"Questions 2 and 3","secondMinutes":40}"""
        val text = """{"texts":["morning."],"send":1,"replies":[],"nextCheckMin":null,"memories":[],"rebalances":[$one,$one,$one,$one]}"""
        val read = FelixAnswers.rebalances(text)
        // A fourth, beyond what he's asked about, is read too: it isn't one, so his message isn't sent.
        assertEquals(4, read.size)
        assertEquals(Rebalance.Given("teams:sheet", 0, "Question 1", 25, "Questions 2 and 3", 40), read.first())
        // Read beside his answer, which keeps none of them.
        assertEquals("morning.", FelixAnswers.parse(text)!!.message)
        assertEquals(emptyList(), FelixAnswers.rebalances("""{"texts":["morning."]}"""))
        assertEquals(emptyList(), FelixAnswers.rebalances("not JSON"))
        assertTrue("Never cut one piece of work by time" in FelixPrompts.system(Settings()))
    }

    @Test
    fun `a message past its room ends at its last whole sentence, the morning's with more room`() {
        val first = "Ten slots today, starting with handing in the physics sheet at 10:55, then the translation question and the vocab in the lunch study period."
        val second = "The evening runs from the essay plan at five to the statics questions at nine, with a break at seven for dinner, and nothing after half past nine."
        val message = "$first $second"
        assertTrue(message.length in (FelixAnswers.MAX_MESSAGE + 1)..FelixAnswers.MAX_MORNING, "${message.length}")
        // Over a nag's 240: its first sentence whole, not cut mid-way with an ellipsis.
        assertEquals(first, FelixAnswers.parse("""{"texts":["$message"],"send":1,"replies":[],"nextCheckMin":null,"memories":[]}""")!!.message)
        // The morning's room holds both.
        assertEquals(message, FelixAnswers.parse("""{"texts":["$message"],"send":1,"replies":[],"nextCheckMin":null,"memories":[]}""", FelixAnswers.MAX_MORNING)!!.message)
        // A sentence too short to stand for the rest: cut at a word instead.
        val short = "Go. " + "word ".repeat(80).trim()
        assertTrue(FelixAnswers.parse("""{"texts":["$short"],"send":1,"replies":[],"nextCheckMin":null,"memories":[]}""")!!.message.endsWith("word…"))
    }
}
