package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.CompletionRecord
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.learn.Stats
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What he asks for before answering beside the tasks' texts (8c): the week's numbers (G6) and the calendar further out (G7). */
class ChatAskedTest {
    // A Tuesday evening: this week began yesterday.
    private val now = Fixtures.at("2026-10-20T19:00")

    private fun slot(date: String, outcome: SlotOutcome, minutes: Int, className: String?) = SlotRecord(
        date = date, key = "teams:t#-1#1", taskId = "teams:t", kind = Kind.Homework, className = className, label = "A piece", plannedMin = minutes,
        start = Fixtures.at("${date}T17:00"), end = Fixtures.at("${date}T17:00") + minutes * 60_000L, window = WindowKind.Evening, outcome = outcome,
        workedMin = if (outcome.kept) minutes else 0, at = Fixtures.at("${date}T18:00"),
    )

    private fun done(at: String, className: String?, due: String?, hard: Boolean = true) = CompletionRecord(
        taskId = "teams:$at", title = "Work", source = Source.Teams, kind = Kind.Homework, className = className, estimateMin = 30, workedMin = 30,
        dueAt = due?.let(Fixtures::at), firstSeenAt = Fixtures.at(at) - 86_400_000L, doneAt = Fixtures.at(at), hardDeadline = hard,
    )

    private val log = ActivityLog(
        slots = listOf(
            slot("2026-10-19", SlotOutcome.Kept, 45, "12X/Ph1"),
            slot("2026-10-20", SlotOutcome.Missed, 30, "12X/Ph1"),
            slot("2026-10-13", SlotOutcome.Kept, 60, "12X/Ch1"),
            slot("2026-10-14", SlotOutcome.KeptLate, 30, "12X/Ph1"),
            slot("2026-10-15", SlotOutcome.Missed, 30, null),
            slot("2026-10-16", SlotOutcome.Moved, 30, "12X/Ch1"),
            // The week before last: in neither.
            slot("2026-10-09", SlotOutcome.Kept, 90, "12X/Ch1"),
        ),
        completions = listOf(
            done("2026-10-19T18:00", "12X/Ph1", due = "2026-10-20T09:00"),
            done("2026-10-14T20:00", "12X/Ch1", due = "2026-10-14T09:00"),
            // A date the app set is a plan, not a deadline met or missed (PL-14).
            done("2026-10-15T20:00", null, due = "2026-10-16T09:00", hard = false),
        ),
    )

    @Test
    fun `the week's numbers are this week so far and last week, each with its subjects, busiest first`() {
        val lines = Stats.weekLines(log, mapOf("12X/Ph1" to "Physics", "12X/Ch1" to "Chemistry"), now, LONDON)
        assertEquals(
            listOf(
                "This week so far (Mon 19 Oct to Tue 20 Oct): 45 min worked in 1 slots kept, 1 missed; 1 finished, 1 of 1 deadlines met",
                "  - Physics: 45 min in 1 slots; 1 finished",
                "Last week (Mon 12 Oct to Sun 18 Oct): 1 h 30 min worked in 2 slots kept, 1 missed; 2 finished, 0 of 1 deadlines met",
                "  - Chemistry: 1 h in 1 slots; 1 finished",
                "  - Physics: 30 min in 1 slots; 0 finished",
                "  - Other: 0 min in 0 slots; 1 finished",
            ),
            lines,
        )
        // At most twenty lines, however many subjects: the rest counted.
        val many = ActivityLog(slots = (1..30).map { slot("2026-10-19", SlotOutcome.Kept, 10 + it, "class $it") })
        val crowded = Stats.weekLines(many, emptyMap(), now, LONDON)
        assertTrue(crowded.size <= Stats.MAX_WEEK_LINES, crowded.joinToString("\n"))
        assertTrue("  - and 22 more subjects" in crowded, crowded.joinToString("\n"))
        // Both weeks crowded: each its share, the count of the rest in it, nothing of either cut.
        val both = ActivityLog(slots = (1..30).flatMap { listOf(slot("2026-10-19", SlotOutcome.Kept, 10 + it, "class $it"), slot("2026-10-13", SlotOutcome.Kept, 10 + it, "class $it")) })
        val full = Stats.weekLines(both, emptyMap(), now, LONDON)
        assertEquals(Stats.MAX_WEEK_LINES, full.size)
        assertEquals(2, full.count { it == "  - and 22 more subjects" })
        assertEquals(listOf("This week so far", "Last week"), crowded.filter { !it.startsWith(" ") }.map { it.substringBefore(" (") })
    }

    private fun event(id: Long, title: String, start: String, hours: Long = 1) = CalendarEvent(id, title, Fixtures.at(start), Fixtures.at(start) + hours * 3_600_000L, allDay = false)

    @Test
    fun `the calendar asked for with no month is the four weeks after the fortnight`() {
        val events = listOf(
            event(1, "In the fortnight", "2026-10-30T10:00"),
            event(2, "Three weeks on", "2026-11-10T10:00"),
            event(3, "Five weeks on", "2026-11-26T10:00"),
            event(4, "Seven weeks on", "2026-12-08T10:00"),
        )
        val block = ChatPrompts.calendarBlock(events, "", now, LONDON)
        assertTrue(block.startsWith("The calendar you asked for, the four weeks after the fortnight, Tuesday 3 November 2026 to Tuesday 1 December 2026"), block)
        assertTrue("| Three weeks on |" in block && "| Five weeks on |" in block, block)
        assertFalse("In the fortnight" in block || "Seven weeks on" in block, block)
    }

    @Test
    fun `a month asked for brings its events only, sixty at most, saying how many more and from when`() {
        val december = (1..65).map { i -> event(100L + i, "Rehearsal $i", "2026-12-%02dT%02d:00".format((i - 1) / 3 + 1, 9 + (i - 1) % 3)) }
        val others = listOf(event(1, "November's", "2026-11-30T10:00"), event(2, "January's", "2027-01-01T10:00"))
        val block = ChatPrompts.calendarBlock(others + december, "2026-12", now, LONDON)
        assertTrue(block.startsWith("The calendar you asked for, December 2026"), block)
        assertEquals(60, block.lines().count { it.startsWith("- ") && "Rehearsal" in it })
        assertTrue("- …and 5 more from Monday 21 December 2026, left out for room" in block, block)
        assertFalse("November's" in block || "January's" in block, block)
        // An empty month is said to be so, inside the read.
        assertTrue(ChatPrompts.calendarBlock(others, "2026-12", now, LONDON).endsWith("\n(none)"))
        // An all-day event by its date, as the calendar keeps it (midnight to midnight, UTC): one on the last day of
        // September isn't October's in summer time, and one on the first of October is.
        fun allDay(id: Long, title: String, date: String) = CalendarEvent(id, title, java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), java.time.LocalDate.parse(date).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
        val october = ChatPrompts.calendarBlock(listOf(allDay(1, "Last of September", "2026-09-30"), allDay(2, "First of October", "2026-10-01")), "2026-10", Fixtures.at("2026-09-20T12:00"), LONDON)
        assertTrue("| First of October |" in october && "Last of September" !in october, october)
    }

    @Test
    fun `a month the read ends in, and one past it, say where the read ends`() {
        val end = CalendarTime.readUntil(now, LONDON)
        // The read runs to the plan's reach: 19 January 2027 from 20 October.
        assertEquals(Fixtures.at("2027-01-19T00:00"), end)
        val january = ChatPrompts.calendarBlock(listOf(event(1, "Back to school", "2027-01-05T08:30")), "2027-01", now, LONDON)
        assertTrue("| Back to school |" in january, january)
        assertTrue(january.endsWith("The calendar is read only to Monday 18 January 2027, as far as the plan reaches: what comes after isn't known."), january)
        val march = ChatPrompts.calendarBlock(emptyList(), "2027-03", now, LONDON)
        assertTrue(march.endsWith("(none known: the calendar is read only to Monday 18 January 2027, as far as the plan reaches)"), march)
        // A month gone by is before the read.
        val september = ChatPrompts.calendarBlock(emptyList(), "2026-09", now, LONDON)
        assertTrue(september.endsWith("(none known: the calendar is read from Monday 19 October 2026 on)"), september)
    }

    @Test
    fun `a round for the week's numbers and December brings each under its own heading, and says so under his answer`() = runBlocking {
        val felix = FelixHarness(start = "2026-10-20T19:00", calendar = { EventJudge.Time(emptyList(), emptyMap(), emptyList(), events = listOf(event(1, "Choir concert", "2026-12-10T19:00"))) })
        felix.use {
            felix.stores.log.update { log }
            felix.answers("""{"reply":"Looking.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[],"research":"","details":["stats","calendar:2026-12"]}""")
            felix.answers(felix.chatAnswer("A good week, and the choir concert's on the 10th."))
            assertTrue(felix.conversation.ask("how did last week go, and what's on in December?"))
            felix.settle()
            val first = felix.server.takeRequest().body.readUtf8()
            assertFalse("The week's numbers you asked for" in first)
            val second = felix.server.takeRequest().body.readUtf8()
            assertTrue("The week's numbers you asked for" in second && "Last week (Mon 12 Oct to Sun 18 Oct)" in second, second)
            assertTrue("The calendar you asked for, December 2026" in second && "Choir concert" in second, second)
            val reply = felix.stores.chat.value.messages.last { it.from == Speaker.Felix }
            assertEquals("A good week, and the choir concert's on the 10th.", reply.text)
            assertEquals(listOf("Read: the week's numbers.", "Read: the calendar for December 2026."), reply.acts.map { it.line })
            // Two calls: his first answer and the round's.
            assertEquals(2, felix.stores.model.value.calls.size)
        }
    }

    @Test
    fun `the last round a message has is said to be so, and more asked for after it is said under his answer`() = runBlocking {
        val felix = FelixHarness(start = "2026-10-20T19:00")
        felix.use {
            felix.stores.log.update { log }
            fun asking(details: String, research: String = "") = """{"reply":"Looking.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[],"research":"$research","details":[$details]}"""
            felix.answers(asking(""""stats""""))
            felix.answers(asking(""""calendar:2026-12""""))
            // Past the two rounds: a search asked for still.
            felix.answers(asking("", research = "the concert's dates"))
            assertTrue(felix.conversation.ask("how's my term looking?"))
            felix.settle()
            val sent = (1..3).map { felix.server.takeRequest().body.readUtf8() }
            assertFalse(sent.take(2).any { "That's the last that can be brought" in it })
            assertTrue("That's the last that can be brought for this message" in sent[2], sent[2])
            val reply = felix.stores.chat.value.messages.last { it.from == Speaker.Felix }
            assertTrue(reply.acts.any { it.problem && it.line.startsWith(com.thomaswcode.decrastination.core.Act.NOT_BROUGHT) }, reply.acts.toString())
            assertEquals(3, felix.stores.model.value.calls.size)
        }
    }

    @Test
    fun `a search that couldn't be made is said in the round after it, so it isn't asked for again unknowing`() = runBlocking {
        val felix = FelixHarness(start = "2026-10-20T19:00")
        felix.use {
            felix.stores.log.update { log }
            felix.answers("""{"reply":"Looking.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[],"research":"the term's dates","details":["stats"]}""")
            // The search refused by the API: a request it won't take, not tried again.
            felix.server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"no"}}"""))
            felix.answers(felix.chatAnswer("A steady week."))
            assertTrue(felix.conversation.ask("how's the week been, and when does term end?"))
            felix.settle()
            val sent = (1..3).map { felix.server.takeRequest().body.readUtf8() }
            assertTrue("Your web search couldn't be made (the search failed)" in sent[2] && "The week's numbers you asked for" in sent[2], sent[2])
            val reply = felix.stores.chat.value.messages.last { it.from == Speaker.Felix }
            assertEquals("A steady week.", reply.text)
            assertTrue(reply.acts.any { it.problem && it.line.startsWith(com.thomaswcode.decrastination.core.Act.NOT_LOOKED_UP) }, reply.acts.toString())
        }
    }

    @Test
    fun `what a round brings has a room of its own, a block too long cut at a whole line saying so, and the context keeps its room`() {
        // Sixty events with long titles and three long texts: more than the turn's room.
        val events = (1..60).map { i -> event(i.toLong(), "A long-titled rehearsal of the winter concert, part $i of many, in the main hall".repeat(2), "2026-12-%02dT10:00".format(1 + (i - 1) / 3)) }
        val block = ChatPrompts.calendarBlock(events, "2026-12", now, LONDON)
        assertTrue(block.length > 12_000, "${block.length}")
        val long = (1..3).map { i -> com.thomaswcode.decrastination.core.TaskItem(id = "teams:l$i", source = Source.Teams, sourceId = "l$i", title = "Long $i", kind = Kind.Homework, detail = "w ".repeat(6_000), firstSeenAt = now, lastSeenAt = now) }
        val asked = com.thomaswcode.decrastination.core.ChatMessage("y1", Speaker.You, "What's on, and what do these say?", now)
        val newest = ChatPrompts.turns(listOf(asked), "CONTEXT ".repeat(2_000), long.associateBy { it.id }, LONDON, read = long, brought = listOf(block)).single().second
        val calendar = newest.substringAfter("The calendar you asked for")
        assertTrue(calendar.contains("more lines left out for room)"), calendar.takeLast(300))
        // Cut at a whole line: the last event line kept ends as an event's does.
        assertTrue(calendar.substringBefore("\n(…").trimEnd().endsWith("10:00 to Thu 10 Dec 2026 11:00") || calendar.substringBefore("\n(…").lines().last().contains(" | "), calendar.substringBefore("\n(…").takeLast(200))
        // The context keeps room beside it.
        assertTrue("CONTEXT CONTEXT" in newest, newest.take(200))
        assertTrue(newest.length <= com.thomaswcode.decrastination.enrich.Pricing.MAX_INPUT_CHARS)
    }
}
