package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.block.BlockPolicy
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Due
import com.thomaswcode.decrastination.core.DueKind
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Lessons
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.NoteScope
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.PpClass
import com.thomaswcode.decrastination.core.PpSchedule
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Reminder
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WebSource
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.FelixPrompts
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sources.gmail.Mime
import com.thomaswcode.decrastination.sources.teams.TeamsRows
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `docs/spec/felix-inputs.md`, everything Felix is sent: its examples made by the app's own code
 * from the made-up data here, and its prompts the golden copies, each in the fence after its
 * `<!-- generated: … -->` or `<!-- golden: … -->` mark. One out of date fails here;
 * `./gradlew testDebugUnitTest --tests '*FelixInputsDocTest*' -PupdateDocs=true` writes them again.
 * The words around them are the doc's own.
 */
class FelixInputsDocTest {
    // Power Planner's class names beside Teams' codes (G8): the same subject ties them.
    private val settings = Settings(classMap = mapOf("12X/Ph1" to "Physics", "12X/Ma1" to "Maths", "12X/Ch1" to "Chemistry", "Chem" to "Chemistry", "Phys" to "Physics"), profileLines = listOf("I work best straight after school."))

    /** Power Planner's two-week timetable (G8): chemistry on Wednesdays and Fridays, physics on Mondays and Thursdays. */
    private val timetable = Timetable(
        "2026-09-07T00:00:00",
        listOf(
            PpClass("c1", "Chem", listOf(PpSchedule("2026-09-05T10:00:00", "2026-09-05T11:00:00", 3), PpSchedule("2026-09-05T14:00:00", "2026-09-05T15:00:00", 5))),
            PpClass("p1", "Phys", listOf(PpSchedule("2026-09-05T08:50:00", "2026-09-05T09:45:00", 1), PpSchedule("2026-09-05T12:20:00", "2026-09-05T13:15:00", 4))),
        ),
    )
    private val school = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-22", "2026-10-23", "Training days")))
    private val memory = MemoryState(
        facts = listOf(Fact("football", "Training is on Tuesdays", Fixtures.at("2026-10-12T19:00"))),
        rules = listOf(Rule("Don't message before 8 in the morning", Fixtures.at("2026-10-12T19:00"))),
    )
    private val seen = Fixtures.at("2026-10-12T16:00")
    private fun task(id: String, source: Source, title: String, kind: Kind, due: String?, minutes: Int, className: String? = null, steps: List<SubStep> = emptyList(), detail: String = "", extra: Map<String, String> = emptyMap()) = TaskItem(
        id = id, source = source, sourceId = id.substringAfter(':'), title = title, detail = detail, className = className, dueAt = due?.let(Fixtures::at), kind = kind,
        sourceEffortMin = minutes, subSteps = steps, firstSeenAt = seen, lastSeenAt = seen, extra = extra,
    )
    private val worksheet = task(
        "teams:t1", Source.Teams, "Rates of reaction worksheet", Kind.Homework, "2026-10-15T08:30", 45, "12X/Ch1",
        detail = "Answer questions 1 to 8 on the sheet. For question 5, use the results table from Monday's practical.\nShow your working for the calculations.",
        extra = mapOf(TeamsRows.EXTRA_LINKS to "https://example.org/rates-sheet"),
    )
    private val vectors = task(
        "powerplanner:p1", Source.PowerPlanner, "Vectors exercise 4B", Kind.Homework, "2026-10-16T23:59", 60, "12X/Ma1",
        listOf(SubStep("Questions 1 to 6", 30), SubStep("Questions 7 to 12", 30)),
        detail = "Textbook exercise 4B, questions 1 to 12. Check your answers at the back.",
    )
    private val form = task(
        "gmail:m1", Source.Gmail, "Trip consent form", Kind.Admin, "2026-10-16T23:59", 10,
        detail = "Dear Year 12,\nPlease return the signed consent form for the museum trip by Friday 16 October; forms go back to the school office, not to your form tutor.\n" +
            "The trip is on Friday 30 October. The coach leaves at 08:15 from the front of the school and is back by 16:30.\n" +
            "Bring a packed lunch, a notebook and a pen: the museum's cafe is closed that day for a private event.\n" +
            "The department covers the cost, but tell us by Wednesday of any access or dietary needs we should pass on to the museum.\n" +
            "You'll fill in a short worksheet during the visit, due the following Monday.\nKind regards,\nMs Example\n" +
            "On Wednesday 14 October, Ms Example wrote:\nChange of plan: the coach now leaves at 08:00, so be at the front of the school by 07:50.",
        extra = mapOf(GmailThreads.EXTRA_FROM_NAME to "Ms Example", GmailThreads.EXTRA_ATTACHMENTS to GmailThreads.attachmentsExtra(listOf(Mime.Attachment("consent-form.pdf", "application/pdf", 112_000)))),
    ).let {
        // As a reading for this text would leave it: with its summary of all of it.
        it.copy(
            enrichment = Enrichment(
                inputHash = Enrichments.inputHash(it), by = "claude-opus-5-5", at = seen,
                summary = "Return the signed consent form to the school office by Friday 16 October. The trip is on Friday 30 October: the coach now leaves at 08:00 (be there by 07:50) and is back by 16:30. Bring a packed lunch, a notebook and a pen; tell the department of any access or dietary needs by Wednesday. A worksheet from the visit is due the Monday after.",
            ),
        )
    }
    private val statement = task("own:o1", Source.Own, "Personal statement first draft", Kind.Admin, null, 120, detail = "About 4,000 characters; the first draft by half term.")
    private val returned = task("own:o0", Source.Own, "Return the library book", Kind.Admin, null, 10).copy(status = Status.Done, doneAt = Fixtures.at("2026-10-12T18:10"))
    private val tasks = listOf(worksheet, vectors, form, statement, returned)
    private val calibration = Calibration(multipliers = mapOf("Homework|12X/Ch1" to 1.3, "Homework|" to 1.15), timed = mapOf("Homework|12X/Ch1" to 4, "Homework|" to 9))
    private val events = listOf(
        CalendarEvent(1, "Drum lesson", Fixtures.at("2026-10-14T18:00"), Fixtures.at("2026-10-14T18:45"), allDay = false),
        CalendarEvent(2, "Open day", LocalDate.parse("2026-10-17").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), LocalDate.parse("2026-10-18").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true, location = "The university's main hall", description = "Talks every hour from 10:30; subject stands all day."),
    )
    // What Felix keeps at your asking: a note of his on the worksheet, a standing one of yours for maths, a reminder about the form and one for the worksheet's slot.
    private val noteOnSheet = Note("n1", "Use Monday's results table for question 5", Fixtures.at("2026-10-12T19:04"), By.Felix, taskId = "teams:t1")
    private val notes = listOf(noteOnSheet, Note("n2", "Do these on paper, not in the textbook", Fixtures.at("2026-10-11T18:30"), By.You, scope = NoteScope(subject = "Maths")))
    private val reminders = listOf(
        Reminder("r1", "Bring the signed consent form", Fixtures.at("2026-10-12T19:04"), By.Felix, "gmail:m1", Due(DueKind.At, Fixtures.at("2026-10-14T08:00"))),
        Reminder("r2", "Ask about question 7", Fixtures.at("2026-10-12T19:04"), By.Felix, "teams:t1", Due(DueKind.SlotStart, Fixtures.at("2026-10-12T19:04"))),
    )
    private val applied = listOf(
        Instruction("i1", "Not much on Friday please", at = Fixtures.at("2026-10-12T19:05"), state = InstructionStatus.Applied, changes = listOf(Change(ChangeType.DayLimit, date = "2026-10-16", freeMin = 60)), appliedAt = Fixtures.at("2026-10-12T19:05")),
        Instruction(
            "i2", "Keep Saturday for the statement, cards too", at = Fixtures.at("2026-10-12T19:10"), state = InstructionStatus.Applied,
            changes = listOf(Change(ChangeType.DayOnly, taskId = "own:o1", date = "2026-10-17", alsoAllow = InstructionRules.ALSO_ANKI)), appliedAt = Fixtures.at("2026-10-12T19:10"), by = By.Felix,
        ),
    )
    private val evening = (0L..20L).associate { LocalDate.parse("2026-10-13").plusDays(it) to listOf(DayWindow(16 * 60 + 45, 22 * 60, WindowKind.Evening)) }

    /** The plan at [at] as the app makes it: the calendar judged (the open day still to be answered), your limits and busy times. */
    private fun plan(at: String): Plan {
        val now = Fixtures.at(at)
        val today = Planner.date(now, LONDON)
        val time = EventJudge.time(events, emptyMap(), LONDON, askUntil = now + 7 * 24 * 3_600_000L)
        val reach = today.plusDays(Planner.MAX_HORIZON_DAYS)
        return Planner.plan(
            Planner.Input(
                tasks, now, LONDON, settings, calibration = calibration, busy = time.busy, dayLoads = time.dayLoads,
                hardBusy = InstructionRules.busy(applied, LONDON, today, reach), dayCaps = InstructionRules.dayCaps(applied, today, reach), windows = evening,
                onlyDays = InstructionRules.onlyDays(applied, today, reach),
            ),
        )
    }

    /** Each example, by the name its mark gives it. */
    private fun examples(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val now = Fixtures.at("2026-10-13T17:20")
        val byId = tasks.associateBy { it.id }
        out["profile"] = Profile.block(settings, tasks, null, school, memory, now, LONDON)
        // The chat: a message about a task, after an earlier exchange.
        val earlier = ChatMessage("m1", Speaker.You, "What's due this week?", Fixtures.at("2026-10-12T19:02"))
        val reply = ChatMessage(
            "m2", Speaker.Felix, "The worksheet's due Thursday morning and the vectors on Friday. I've put the vectors' first half on Wednesday at 18:00.", Fixtures.at("2026-10-12T19:02"),
            proposals = listOf(Proposal("p1", changes = listOf(Change(ChangeType.AtTime, taskId = vectors.id, date = "2026-10-14", startMin = 18 * 60, time = Fixtures.at("2026-10-14T18:00"))), lines = listOf("“Vectors exercise 4B” at 18:00 on Wed 14 Oct"), state = ProposalState.Applied, instant = true)),
        )
        val asked = ChatMessage("m3", Speaker.You, "Can I do these tomorrow instead?", now, about = About(taskId = vectors.id, taskTitle = vectors.title))
        val planned = plan("2026-10-13T17:20")
        val moments = reminders.mapNotNull { r -> Reminders.moment(r, planned, byId, now)?.let { r.id to it } }.toMap()
        // Each class's next lessons (G8), the timetable read this morning: "the worksheet's due next lesson" has the worksheet's class and its lessons.
        val lessons = Lessons.Timetabled(Lessons.groups(Lessons.next(timetable, now, LONDON, school), settings.classMap), Fixtures.at("2026-10-13T07:00"), stale = false)
        // His nudge of 17:00 still waiting for your answer (D-99): listed by id, so an answer can name it answered.
        val waiting = ChatMessage("m4", Speaker.Felix, "Starting the worksheet? First: read question 1 and underline what it asks.", Fixtures.at("2026-10-13T17:00"), About(taskId = worksheet.id, taskTitle = worksheet.title), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting now", "Give me ten minutes"))
        // A timer started on the slot under way two minutes before (D-112).
        val doing = planned.slotAt(planned.now)!!.chunk!!
        val timer = PieceTimer(doing.key, doing.taskId, doing.stepIndex, doing.step, doing.label, doing.minutes, Fixtures.at("2026-10-13T17:18"))
        val context = ChatPrompts.Context(
            known = ChatPrompts.context(tasks, events, applied, now, LONDON, settings.classMap, emptyList(), settings, calibration, notes, reminders, moments, lessons, listOf(waiting)),
            // With whether blocking applies, and the free time (G5), as Focus says it then.
            plan = ChatPrompts.plan(
                planned, LONDON, blocking = Focus.blockingLine(BlockPolicy.Verdict.Block(BlockPolicy.Reason.TodayUndone), planned.todayBucket?.chunks.orEmpty(), 0, 16 * 60 + 45, null), timer = timer,
                only = InstructionRules.onlyDays(applied, planned.today, planned.today.plusDays(ChatPrompts.PLAN_DAYS.toLong())).mapValues { ChatPrompts.onlyLine(it.value, tasks.associateBy { t -> t.id }) },
            ),
        )
        ChatPrompts.turns(listOf(earlier, reply, asked), context.known, byId, LONDON, plan = context.plan, events = events).forEachIndexed { i, (from, text) -> out["chat turn $i: $from"] = text }
        // A reply from Felix's notification: his message leads your first turn.
        val his = ChatMessage("m4", Speaker.Felix, "Starting the worksheet? First: read question 1 and underline what it asks.", Fixtures.at("2026-10-13T17:00"), kind = MessageKind.SlotStart)
        val yours = ChatMessage("m5", Speaker.You, "Give me ten minutes", Fixtures.at("2026-10-13T17:02"))
        ChatPrompts.turns(listOf(his, yours), "(the context, as above)", byId, LONDON, plan = "(the plan, as above)").forEachIndexed { i, (from, text) -> out["reply turn $i: $from"] = text }
        // A reply swiped in the chat to his earlier message, which isn't the one just before: quoted.
        val answer = ChatMessage("m5a", Speaker.Felix, "Ten minutes, then.", Fixtures.at("2026-10-13T17:02"))
        val quoted = ChatMessage("m8", Speaker.You, "Can that be at seven instead?", now, replyTo = his.id)
        out["quoted reply"] = ChatPrompts.turns(listOf(his, yours.copy(answeredAt = answer.at), answer, quoted), "(the context, as above)", byId, LONDON, plan = "(the plan, as above)").last().second
        // With a web search's finding.
        val found = Found("the open day's programme and times", "Saturday 17 October 2026, 10:00 to 15:00: talks every hour from 10:30, subject stands all day.\nBooking isn't needed.", listOf("open day programme"), listOf(WebSource("Open day programme", "https://www.example.ac.uk/open-day")))
        out["chat turn with research"] = ChatPrompts.turns(listOf(ChatMessage("m6", Speaker.You, "What happens at the open day on Saturday?", now)), "(the context)", byId, LONDON, plan = "(the plan)", found = found).last().second
        // Asked again with a task he asked to read in full.
        out["chat turn with read"] = ChatPrompts.turns(listOf(ChatMessage("m7", Speaker.You, "What do I need for the museum trip?", now)), "(the context)", byId, LONDON, plan = "(the plan)", read = listOf(form)).last().second
        out["research system"] = ResearchPrompts.system(12)
        out["research user"] = ResearchPrompts.describe("the open day's programme and times", "What happens at the open day on Saturday?", now, LONDON)
        // Felix's own messages.
        val p = plan("2026-10-13T16:50")
        val slot = p.todaySlots.first { it.chunk != null }
        val chunk = slot.chunk!!
        val recent = listOf(
            ChatMessage("f1", Speaker.Felix, "Starting the vectors? First: copy out question 1's diagram.", Fixtures.at("2026-10-12T17:00"), kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting now", "Give me 10 minutes")),
            ChatMessage("f1-reply", Speaker.You, "Starting now", Fixtures.at("2026-10-12T17:02"), replyTo = "f1", answeredAt = Fixtures.at("2026-10-12T17:02")),
        )
        fun record(outcome: SlotOutcome, earned: Boolean) = SlotRecord(
            date = "2026-10-13", key = chunk.key, taskId = chunk.taskId, kind = Kind.Homework, label = chunk.label, plannedMin = chunk.minutes,
            start = slot.start, end = slot.end, window = WindowKind.Evening, outcome = outcome, workedMin = if (outcome.kept) chunk.minutes else 0, earned = earned, at = slot.end,
        )
        val at = slot.start + 10 * 60_000L
        out["felix SlotStart"] = FelixPrompts.describe(FelixPrompts.Reason.SlotStart, p, slot, worksheet, emptyList(), recent, null, settings, at, LONDON, firstAction = "Read question 1 and underline what it asks", reminder = "Ask about question 7", notes = listOf(noteOnSheet.text))
        val missed = record(SlotOutcome.Missed, false)
        out["felix SlotEnd (missed)"] = FelixPrompts.describe(FelixPrompts.Reason.SlotEnd, p, null, worksheet, listOf(missed), recent, null, settings, slot.end + 6 * 60_000L, LONDON, record = missed)
        val noPhoto = record(SlotOutcome.Kept, false)
        out["felix SlotEnd (kept, photo needed)"] = FelixPrompts.describe(FelixPrompts.Reason.SlotEnd, p, null, worksheet, listOf(noPhoto), recent, null, settings, slot.end + 2 * 60_000L, LONDON, record = noPhoto)
        val kept = record(SlotOutcome.Kept, true)
        out["felix SlotKept"] = FelixPrompts.describe(FelixPrompts.Reason.SlotKept, p, null, worksheet, listOf(kept), recent, null, settings, slot.end + 60_000L, LONDON, freeMin = 15, record = kept)
        out["felix DayDone"] = FelixPrompts.describe(FelixPrompts.Reason.DayDone, p, null, worksheet, listOf(kept), recent, null, settings, Fixtures.at("2026-10-13T21:40"), LONDON, freeMin = 25, record = kept)
        // The vectors' first questions ticked the night before, on their sheet: said as done.
        val ticked = vectors.copy(subSteps = vectors.subSteps.mapIndexed { i, step -> step.copy(done = i == 0) })
        out["felix Morning"] = FelixPrompts.describe(FelixPrompts.Reason.Morning, plan("2026-10-13T07:00"), null, null, emptyList(), recent, null, settings, Fixtures.at("2026-10-13T07:00"), LONDON, reminders = listOf("17:00 Ask about question 7"), tasks = listOf(worksheet, ticked))
        out["felix Question"] = FelixPrompts.describe(FelixPrompts.Reason.Question, p, null, worksheet, emptyList(), recent, "Does question 5 need Monday's results, or can it be done from the textbook?", settings, at, LONDON)
        out["felix Completions"] = FelixPrompts.describe(FelixPrompts.Reason.Completions, p, null, null, emptyList(), recent, null, settings, Fixtures.at("2026-10-13T19:10"), LONDON, done = listOf(worksheet.title, vectors.title))
        // A reminder, written ten minutes before its moment, as of it: the slot then and its task, your words.
        val form8 = Fixtures.at("2026-10-14T08:00")
        val morning = plan("2026-10-14T07:50")
        out["felix Reminder"] = FelixPrompts.describe(FelixPrompts.Reason.Reminder, morning, morning.slotAt(form8) ?: morning.nextSlot(form8), form, emptyList(), recent, null, settings, form8, LONDON, reminder = "Bring the signed consent form")
        return out
    }

    @Test
    fun `the doc of what Felix is sent is as the app sends it`() {
        val doc = listOf(File("docs/spec/felix-inputs.md"), File("../docs/spec/felix-inputs.md")).firstOrNull { it.isFile } ?: fail("No docs/spec/felix-inputs.md")
        val golden = listOf(File("src/test"), File("app/src/test")).first { it.isDirectory }.let { File(it, "resources/prompts") }
        val text = doc.readText().replace("\r\n", "\n")
        val examples = examples()
        val written = MARK.replace(text) { m ->
            val (how, name) = m.destructured
            val body = when (how) {
                "golden" -> File(golden, name).readText().replace("\r\n", "\n")
                else -> examples[name]?.let { if (how == FOLDED) folded(it) else it } ?: fail("The doc marks an example there's none of: “$name”")
            }.trimEnd('\n')
            assertTrue("```" !in body, "A fence inside “$name”")
            "<!-- $how: $name -->\n```text\n$body\n```"
        }
        // The one line of a prompt the doc quotes in its words: the chat's item 6 with searches off.
        val searchesOff = ChatPrompts.system(settings.copy(felixSearches = false), 12).lines().first { it.startsWith("6.") }
        assertTrue("“$searchesOff”" in written || "\"$searchesOff\"" in written, "The doc's words quote the chat's item 6 with searches off, now: $searchesOff")
        if (System.getProperty("updateDocs") == "true") {
            doc.writeText(written)
            return
        }
        assertEquals(written, text, "docs/spec/felix-inputs.md isn't what the app sends now: if that's meant, run with -PupdateDocs=true and commit it")
    }

    /** A run of days with nothing planned, past its first two, as one line saying how many. */
    private fun folded(text: String): String {
        val kept = mutableListOf<String>()
        val quiet = mutableListOf<String>()
        fun flush() {
            kept += quiet.take(2)
            if (quiet.size > 2) kept += "[… ${quiet.size - 2} more days, each “nothing planned” …]"
            quiet.clear()
        }
        for (line in text.lines()) {
            if (QUIET.containsMatchIn(line)) {
                quiet += line
                continue
            }
            flush()
            kept += line
        }
        flush()
        return kept.joinToString("\n")
    }

    private companion object {
        const val FOLDED = "generated, quiet days folded"

        /** A mark and the fence after it (empty, or up to its first closing line): the fence is written again, the mark kept. */
        val MARK = Regex("^<!-- (golden|generated|generated, quiet days folded): (.+?) -->\n```text\n(?:.*?\n)??```(?=\n|\\z)", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        val QUIET = Regex("^- .*: nothing planned \\(")
    }
}
