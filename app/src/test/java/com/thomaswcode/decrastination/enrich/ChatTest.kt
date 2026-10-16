package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.Why
import com.thomaswcode.decrastination.learn.CalendarEvent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTest {
    // Monday 12 Oct 2026, 22:45.
    private val now = Fixtures.at("2026-10-12T22:45")
    private val bridge = TaskItem(
        id = "teams:bridge", source = Source.Teams, sourceId = "bridge", title = "Bridge and Balances Beam Q's", dueAt = Fixtures.at("2026-10-13T08:30"),
        kind = Kind.Homework, sourceEffortMin = 100, firstSeenAt = now, lastSeenAt = now, sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-13T08:30"), null),
    )

    private fun parse(json: String, about: About = About()) = ChatAnswers.parse(json, listOf(bridge), emptyList(), about, now, LONDON)

    private val none = """"changes":[]"""

    @Test
    fun `a reply with a task and a change is read, the task as the form would take it`() {
        val reading = parse(
            """{"reply":"I'd add the statement and set the physics to 90 minutes.",
              "newTasks":[{"title":"UCAS personal statement","kind":"Admin","subject":null,"dueDate":"2026-12-01","dueTime":null,"startDate":null,"minutes":480,"notes":null,
                "steps":[{"title":"First draft","minutes":240,"startDate":null,"dueDate":"2026-11-08"},{"title":"Redraft","minutes":240,"startDate":null,"dueDate":null}]}],
              "changes":[{"type":"Minutes","taskId":"teams:bridge","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":90,"date":null,"weekday":null,"time":null,"endTime":null}]}""",
        )!!
        assertEquals("I'd add the statement and set the physics to 90 minutes.", reading.reply)
        val task = reading.tasks.single()
        assertEquals("UCAS personal statement", task.title)
        assertEquals(Kind.Admin, task.kind)
        // No time: the end of that day.
        assertEquals(Fixtures.at("2026-12-01T23:59"), task.dueAt)
        assertEquals(listOf(240, 240), task.steps.map { it.minutes })
        assertEquals(Fixtures.at("2026-11-08T23:59"), task.steps[0].dueAt)
        assertEquals(Change(ChangeType.Minutes, taskId = "teams:bridge", minutes = 90), reading.changes.single())
        assertTrue(reading.leftOut.isEmpty())
    }

    @Test
    fun `a task's total is Claude's, shared over steps that don't come to it`() {
        val reading = parse("""{"reply":"I'd add 8 hours of it.","newTasks":[{"title":"Statement","kind":"Admin","subject":null,"dueDate":"2026-12-01","dueTime":null,"startDate":null,"minutes":480,"notes":null,
            "steps":[{"title":"Draft","minutes":240,"startDate":null,"dueDate":null},{"title":"Redraft","minutes":180,"startDate":null,"dueDate":null}]}],$none}""")!!
        val task = reading.tasks.single()
        assertEquals(480, task.steps.sumOf { it.minutes })
        assertEquals(listOf(274, 206), task.steps.map { it.minutes })
    }

    @Test
    fun `an event of yours is at its time, or nine in the morning`() {
        val reading = parse("""{"reply":"I'd add it.","newTasks":[{"title":"Dentist","kind":"Event","subject":null,"dueDate":"2026-10-14","dueTime":"16:20","startDate":null,"minutes":0,"notes":null,"steps":[]}],$none}""")!!
        assertEquals(Fixtures.at("2026-10-14T16:20"), reading.tasks.single().dueAt)
        val untimed = parse("""{"reply":"I'd add it.","newTasks":[{"title":"Match","kind":"Event","subject":null,"dueDate":"2026-10-17","dueTime":null,"startDate":null,"minutes":0,"notes":null,"steps":[]}],$none}""")!!
        assertEquals(Fixtures.at("2026-10-17T09:00"), untimed.tasks.single().dueAt)
    }

    @Test
    fun `what can't be used is left out and said, the rest kept`() {
        val reading = parse(
            """{"reply":"Here you go.",
              "newTasks":[{"title":"","kind":"Admin","subject":null,"dueDate":null,"dueTime":null,"startDate":null,"minutes":20,"notes":null,"steps":[]},
                          {"title":"Far off","kind":"Admin","subject":null,"dueDate":"2031-01-01","dueTime":null,"startDate":null,"minutes":20,"notes":null,"steps":[]},
                          {"title":"Buy mum a present","kind":"Admin","subject":null,"dueDate":"2026-10-17","dueTime":null,"startDate":null,"minutes":45,"notes":null,"steps":[]}],
              "changes":[{"type":"DueBy","taskId":"teams:nope","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-15","weekday":null,"time":null,"endTime":null},
                         {"type":"DayLimit","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":0,"date":"2026-10-14","weekday":null,"time":null,"endTime":null}]}""",
        )!!
        assertEquals(listOf("Buy mum a present"), reading.tasks.map { it.title })
        assertEquals(listOf(Change(ChangeType.DayLimit, date = "2026-10-14", freeMin = 0)), reading.changes)
        assertEquals(3, reading.leftOut.size)
        assertTrue(reading.leftOut.any { "isn't listed" in it })
    }

    @Test
    fun `changes past the most taken at once are said, not dropped quietly`() {
        val one = """{"type":"DayLimit","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":0,"date":"2026-10-14","weekday":null,"time":null,"endTime":null}"""
        val reading = parse("""{"reply":"Here.","newTasks":[],"changes":[${List(ChatAnswers.MAX_CHANGES + 3) { one }.joinToString(",")}]}""")!!
        assertEquals(ChatAnswers.MAX_CHANGES, reading.changes.size)
        assertEquals(listOf("3 more changes than the app takes at once"), reading.leftOut)
    }

    @Test
    fun `an answer with no reply, or not an answer, can't be read`() {
        assertNull(parse("""{"reply":" ","newTasks":[],$none}"""))
        assertNull(parse("I'd add it"))
        // A question back, with nothing to do: read, with no cards.
        val question = parse("""{"reply":"When's the test, and in what?","newTasks":[],$none}""")!!
        assertTrue(question.tasks.isEmpty() && question.changes.isEmpty())
    }

    @Test
    fun `the conversation is sent from one of yours, with Claude's cards and what became of them, and now's context on the newest`() {
        fun you(text: String) = ChatMessage(text, Speaker.You, text, now, answeredAt = now)
        val card = Proposal("p", task = OwnTask(title = "Revise mechanics", kind = Kind.Revision, minutes = 180), state = ProposalState.Applied)
        val messages = listOf(
            ChatMessage("c0", Speaker.Claude, "an old answer cut off from its question", now),
            you("I've got a test soon"),
            ChatMessage("c1", Speaker.Claude, "When is it?", now, proposals = listOf(card)),
            ChatMessage("y2", Speaker.You, "Friday", now, about = About(taskId = "teams:bridge", taskTitle = "Bridge and Balances Beam Q's")),
        )
        val turns = ChatPrompts.turns(messages, "CONTEXT", mapOf(bridge.id to bridge), LONDON)
        assertEquals(listOf(Speaker.You, Speaker.Claude, Speaker.You), turns.map { it.first })
        assertEquals("I've got a test soon", turns[0].second)
        assertEquals("When is it?\n[Suggested: add the task “Revise mechanics” — the student applied it]", turns[1].second)
        assertTrue(turns[2].second.startsWith("CONTEXT\n\n(About the task teams:bridge, “Bridge and Balances Beam Q's”) Friday"))
    }

    @Test
    fun `the whole conversation keeps to one budget, the oldest going first and never your newest words`() {
        fun you(id: String, text: String) = ChatMessage(id, Speaker.You, text, now, answeredAt = now)
        val messages = listOf(
            you("y1", "a".repeat(300)),
            ChatMessage("c1", Speaker.Claude, "b".repeat(300), now),
            you("y2", "c".repeat(100)),
            ChatMessage("c2", Speaker.Claude, "d".repeat(100), now),
            ChatMessage("y3", Speaker.You, "the question", now),
        )
        val turns = ChatPrompts.turns(messages, "x".repeat(2_000), emptyMap(), LONDON, budget = 1_000)
        // The first exchange didn't fit history's share; the context was cut beside the question, which is whole.
        assertEquals(listOf(Speaker.You, Speaker.Claude, Speaker.You), turns.map { it.first })
        assertEquals(1_000, turns.sumOf { it.second.length })
        assertTrue(turns.last().second.endsWith("\n\nthe question"))
        assertEquals("c".repeat(100), turns.first().second)
    }

    @Test
    fun `a step dated out of range leaves its task out`() {
        val reading = parse("""{"reply":"I'd add it.","newTasks":[{"title":"Project","kind":"Homework","subject":null,"dueDate":"2026-11-30","dueTime":null,"startDate":null,"minutes":120,"notes":null,
            "steps":[{"title":"Plan","minutes":60,"startDate":"2019-01-01","dueDate":null},{"title":"Write","minutes":60,"startDate":null,"dueDate":null}]}],$none}""")!!
        assertTrue(reading.tasks.isEmpty())
        assertEquals(1, reading.leftOut.size)
    }

    @Test
    fun `the context lists the open tasks with their ids, minutes, minutes worked and dates, and the changes in use`() {
        val context = ChatPrompts.context(listOf(bridge.copy(workedMin = 25)), emptyList(), emptyList(), now, LONDON)
        assertTrue(context.startsWith("Today: Monday 12 October 2026, 22:45 (Europe/London)"))
        assertTrue("- teams:bridge | Bridge and Balances Beam Q's | Teams | Homework | Tue 13 Oct 2026 08:30 | 100 | 25 | -" in context, context)
        assertFalse("Changes already in use" in context)
        // An email's date Claude didn't quote is a plan, and said to be (PL-14).
        val unread = bridge.copy(id = "gmail:form", source = Source.Gmail, title = "Form", dueAt = Fixtures.at("2026-10-14T23:59"))
        val reading = Enrichment(inputHash = com.thomaswcode.decrastination.core.Enrichments.inputHash(unread), by = "claude", at = now, kind = Kind.Admin, deadline = Fixtures.at("2026-10-14T23:59"))
        val email = unread.copy(enrichment = reading)
        val withEmail = ChatPrompts.context(listOf(email), emptyList(), emptyList(), now, LONDON)
        assertTrue("| Gmail | Homework | Wed 14 Oct 2026 23:59 (a plan) |" in withEmail, withEmail)
        assertFalse("(a plan)" in ChatPrompts.context(listOf(email.copy(enrichment = reading.copy(deadlineQuoted = true))), emptyList(), emptyList(), now, LONDON))
        // The calendar's read as far as the plan reaches (DX-14); the chat's told of the fortnight's events.
        val drums = CalendarEvent(1, "Drums", Fixtures.at("2026-10-15T18:00"), Fixtures.at("2026-10-15T19:00"), allDay = false)
        val trip = CalendarEvent(2, "Trip", Fixtures.at("2026-11-02T08:00"), Fixtures.at("2026-11-02T18:00"), allDay = false)
        val withEvents = ChatPrompts.context(emptyList(), listOf(drums, trip), emptyList(), now, LONDON)
        assertTrue("| Drums |" in withEvents, withEvents)
        assertFalse("| Trip |" in withEvents, withEvents)
    }

    @Test
    fun `the context's list of tasks is cut by the dates shown, a plan's gone by not crowding out nearer ones`() {
        // Eighty tasks whose test dates Claude read are gone by, Teams' own at the end of November; one due tomorrow.
        val faded = (1..80).map { i -> bridge.copy(id = "teams:t$i", sourceId = "t$i", title = "t$i", dueAt = Fixtures.at("2026-10-01T08:30"), sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-11-30T23:59"), null)) }
        val context = ChatPrompts.context(faded + bridge, emptyList(), emptyList(), now, LONDON)
        assertTrue("- teams:bridge |" in context, context)
        assertTrue("- teams:t1 | t1 | Teams | Homework | Mon 30 Nov 2026 23:59 |" in context, context)
    }

    @Test
    fun `work behind only the safety margin isn't said not to fit`() {
        val margin = piece(bridge.id, bridge.title, null, 30, 1, 1, Fixtures.at("2026-10-14T23:59"), behind = true).copy(why = Why.Spread)
        val text = ChatPrompts.plan(plan.copy(buckets = listOf(DayBucket(today, 315, listOf(margin)))), LONDON)
        assertTrue("[why: spread] | due Wed 14 Oct 2026 23:59" in text, text)
        assertFalse("won't fit before its deadline" in text, text)
    }

    /** A piece of [taskId] as the planner gives one. */
    private fun piece(taskId: String, title: String, step: String?, minutes: Int, part: Int, parts: Int, dueAt: Long?, behind: Boolean = false) = Chunk(
        taskId = taskId, source = Source.Teams, kind = Kind.Homework, title = title, step = step, minutes = minutes, dueAt = dueAt, deadline = dueAt ?: now,
        soft = dueAt == null, overdue = false, dueToday = false, behind = behind, part = part, parts = parts, taskMinutes = minutes,
        why = when {
            dueAt == null -> Why.Soft
            behind -> Why.NoRoom
            else -> Why.Spread
        },
    )

    private val today = LocalDate.of(2026, 10, 12)

    /** Tonight's time gone with the physics still to do; nothing tomorrow; an essay's draft on Wednesday and its last part past the fortnight. */
    private val plan = Plan(
        now = now,
        today = today,
        buckets = (0L..14L).map { day ->
            val date = today.plusDays(day)
            val chunks = when (day) {
                0L -> listOf(34, 33, 33).mapIndexed { i, m -> piece(bridge.id, bridge.title, "part ${i + 1} of 3", m, i + 1, 3, bridge.dueAt, behind = true) }
                2L -> listOf(piece("own:essay", "Essay", "Draft", 40, 1, 2, Fixtures.at("2026-10-30T17:00")), piece("gmail:zip", "Zip card", null, 20, 1, 1, null, behind = true))
                14L -> listOf(piece("own:essay", "Essay", "Redraft", 20, 2, 2, Fixtures.at("2026-10-30T17:00")))
                else -> emptyList()
            }
            DayBucket(date, if (day == 0L) 0 else 315, chunks)
        },
        // An appointment tomorrow, under its day; a match after the fortnight, in the rest of Coming up.
        events = listOf(
            bridge.copy(id = "powerplanner:dentist", source = Source.PowerPlanner, sourceId = "dentist", title = "Dentist", kind = Kind.Event, dueAt = Fixtures.at("2026-10-13T16:20")),
            bridge.copy(id = "own:match", source = Source.Own, sourceId = "match", title = "Match", kind = Kind.Event, dueAt = Fixtures.at("2026-11-01T09:00")),
        ),
        unplanned = listOf(Unplanned("powerplanner:pg", Source.PowerPlanner, "Pg 60&61", 30, 1, Unplanned.Reason.Waits, after = bridge.title)),
    )

    @Test
    fun `the plan is sent as the Plan tab has it, a day at a time and a task a line, with what it leaves out and why`() {
        val text = ChatPrompts.plan(plan, LONDON)
        assertTrue(text.startsWith("The plan: the work the app has them do each day"), text)
        assertTrue("\nNext to do: Bridge and Balances Beam Q's: part 1 of 3 (34 min); then Bridge and Balances Beam Q's: part 2 of 3 (33 min)\n" in text, text)
        // Tonight over its time, the physics on one line with its pieces.
        assertTrue("\n- Today, Monday 12 October 2026: 100 min planned of 0 min of working time left, over by 100 min\n" in text, text)
        assertTrue(
            "\n  - teams:bridge | Bridge and Balances Beam Q's | 100 min: part 1 of 3 (34 min) [why: noroom], part 2 of 3 (33 min) [why: noroom], part 3 of 3 (33 min) [why: noroom] | due Tue 13 Oct 2026 08:30 | won't fit before its deadline\n" in text,
            text,
        )
        assertTrue("\n- Tomorrow, Tuesday 13 October 2026: nothing planned (315 min of working time)\n  - event at 16:20: powerplanner:dentist | Dentist | Power Planner\n" in text, text)
        assertTrue("\nEvents on the Plan other than those under their days below (past, after Sunday 25 October 2026, or with no date):\n- own:match | Match | Yours | Sun 1 Nov 2026 09:00\n" in text, text)
        // Undated work behind only its soft deadline, the app's own: not said to be late.
        assertTrue(
            "\n- Wednesday 14 October 2026: 60 min planned of 315 min of working time\n  - own:essay | Essay | 40 min: Draft (40 min) [why: spread] | due Fri 30 Oct 2026 17:00\n" +
                "  - gmail:zip | Zip card | 20 min: all of it (20 min) [why: soft] | no due date\n" in text,
            text,
        )
        // The fortnight in full, then a line for the rest.
        assertTrue("\n- Sunday 25 October 2026: nothing planned" in text, text)
        assertFalse("Monday 26 October 2026:" in text, text)
        assertTrue("\nPlanned after Sunday 25 October 2026: 20 min on 1 more day, the last Monday 26 October 2026, of: own:essay | Essay\n" in text, text)
        assertTrue("\nNot planned (open work the plan leaves out, and why):\n- powerplanner:pg | Pg 60&61 | 1 piece, 30 min | waits until “Bridge and Balances Beam Q's” is done, as they said\n" in text, text)
        // The summaries before the days, and the days last, the nearest first: a plan cut to fit loses its far days, not why something isn't planned.
        assertTrue(text.indexOf("Not planned") < text.indexOf("\nDay by day:\n- Today, "), text)
        assertTrue(text.endsWith("\n- Sunday 25 October 2026: nothing planned (315 min of working time)"), text)
    }

    @Test
    fun `the plan's summaries are kept short, saying how many more`() {
        val gaps = (1..25).map { Unplanned("own:t$it", Source.Own, "Task $it", 30, 1, Unplanned.Reason.NoRoom) }
        val text = ChatPrompts.plan(plan.copy(unplanned = gaps), LONDON)
        assertTrue("\n- own:t20 | Task 20 | 1 piece, 30 min | no room for it by Monday 26 October 2026, the plan's last day, within each day's limits\n- …and 5 more\nEvents on the Plan" in text, text)
        assertFalse("own:t21" in text, text)
    }

    @Test
    fun `a slot under way is what's being done now, with its time, as the Do now card has it`() {
        val first = plan.todayBucket!!.chunks.first()
        val slot = com.thomaswcode.decrastination.core.Slot(com.thomaswcode.decrastination.core.SlotKind.Work, first, now - 15 * 60_000L, now + 19 * 60_000L, com.thomaswcode.decrastination.core.WindowKind.Evening, pinned = false)
        val laid = plan.copy(buckets = plan.buckets.mapIndexed { i, b -> if (i == 0) b.copy(slots = listOf(slot)) else b })
        val text = ChatPrompts.plan(laid, LONDON)
        assertTrue("\nNow: 22:30–23:04 Bridge and Balances Beam Q's: part 1 of 3 (34 min); then " in text, text)
        assertFalse("Next to do" in text, text)
        // Today's slots each say whether they've gone by, are under way or are to come (AI-13).
        assertTrue("Today's slots (the times its pieces are laid at):\n- 22:30–23:04 Bridge and Balances Beam Q's: part 1 of 3 (34 min) [now]" in text, text)
        val earlier = slot.copy(start = now - 90 * 60_000L, end = now - 60 * 60_000L)
        val later = slot.copy(start = now + 30 * 60_000L, end = now + 64 * 60_000L)
        val day = ChatPrompts.plan(plan.copy(buckets = plan.buckets.mapIndexed { i, b -> if (i == 0) b.copy(slots = listOf(earlier, later)) else b }), LONDON)
        assertTrue("[past]" in day && "[ahead]" in day, day)
        assertTrue("a deadline later today is still ahead" in ChatPrompts.system())
    }

    @Test
    fun `the plan follows the list and keeps a third of the room from a long one, its near days first`() {
        val messages = listOf(ChatMessage("y", Speaker.You, "what's on tomorrow?", now))
        val whole = ChatPrompts.turns(messages, "LIST", emptyMap(), LONDON, plan = "PLAN").single().second
        assertEquals("LIST\n\nPLAN\n\nwhat's on tomorrow?", whole)
        // A long list is cut at its end; the plan keeps a third of the room, cut at its end.
        val room = 1_000 - "what's on tomorrow?".length - 2
        val cut = ChatPrompts.turns(messages, "l".repeat(5_000), emptyMap(), LONDON, budget = 1_000, plan = "p".repeat(800)).single().second
        assertEquals("l".repeat(room - 2 - room / 3) + "\n\n" + "p".repeat(room / 3) + "\n\nwhat's on tomorrow?", cut)
        // A short list leaves the plan the rest.
        val short = ChatPrompts.turns(messages, "LIST", emptyMap(), LONDON, budget = 1_000, plan = "p".repeat(5_000)).single().second
        assertEquals("LIST\n\n" + "p".repeat(room - 6) + "\n\nwhat's on tomorrow?", short)
    }

    @Test
    fun `the prompts are whole, the change types in both, with no stray indent`() {
        for (system in listOf(ChatPrompts.system(), InstructionPrompts.system())) {
            assertTrue("\n- Minutes: how long the whole task takes" in system, system)
            assertFalse(system.lines().any { it.startsWith(" ") }, system)
        }
        assertNotNull(ChatPrompts.SCHEMA["properties"])
        // Questions about a day go by the plan.
        assertTrue("what's on a day, how full it is, what to do next and why something isn't planned from the plan" in ChatPrompts.system())
    }

    @Test
    fun `the chat waits on your last message till it's answered`() {
        val asked = ChatMessage("y", Speaker.You, "hello", now)
        assertEquals(asked, ChatState(listOf(asked)).unanswered)
        assertNull(ChatState(listOf(asked.copy(answeredAt = now), ChatMessage("c", Speaker.Claude, "hi", now))).unanswered)
    }

    @Test
    fun `an answer's memories are read, an empty text forgetting`() {
        val text = """{"reply":"Noted.","newTasks":[],"changes":[],"memories":[{"topic":"football","text":"on Tuesdays","kind":"fact"},{"topic":"messages before 8","text":"","kind":"rule"}]}"""
        val reading = ChatAnswers.parse(text, emptyList(), emptyList(), com.thomaswcode.decrastination.core.About(), now, LONDON)!!
        assertEquals(
            listOf(com.thomaswcode.decrastination.core.Given("football", "on Tuesdays"), com.thomaswcode.decrastination.core.Given("messages before 8", "", com.thomaswcode.decrastination.core.Given.RULE)),
            reading.memories,
        )
        // One from before this was asked for has none.
        assertEquals(emptyList(), ChatAnswers.parse("""{"reply":"Noted.","newTasks":[],"changes":[]}""", emptyList(), emptyList(), com.thomaswcode.decrastination.core.About(), now, LONDON)!!.memories)
    }

    @Test
    fun `everything a reading gives is made as it comes, its changes on one card, then each new task, then a task to show`() {
        val reading = ChatAnswers.Reading(
            reply = "Moved it to 19:30, and added the statement and the half term.",
            tasks = listOf(OwnTask(title = "Statement", minutes = 60)),
            changes = listOf(Change(ChangeType.AtTime, taskId = "teams:bridge", time = Fixtures.at("2026-10-13T19:30")), Change(ChangeType.NoSchool, date = "2026-10-26", endDate = "2026-10-30")),
            leftOut = emptyList(),
            actions = listOf(com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Open, "teams:bridge")),
        )
        var n = 0
        val cards = ChatAnswers.cards(reading, mapOf(bridge.id to bridge), LONDON) { "c${++n}" }
        // Days off and a new task too (your call, 16 Oct): nothing waits for Add or Apply.
        assertEquals(listOf("c1" to true, "c2" to true), cards.take(2).map { it.id to it.instant })
        assertEquals(listOf(ChangeType.AtTime, ChangeType.NoSchool), cards[0].changes.map { it.type })
        assertEquals("Statement", cards[1].task?.title)
        assertEquals("teams:bridge", cards[2].open)
    }

    @Test
    fun `replies to offer are read, three at most, and the chat is Felix`() {
        val reading = parse("""{"reply":"Moved.","newTasks":[],$none,"memories":[],"suggestedReplies":[" Thanks ","","Undo that","Thanks","Move it to 20:00 instead, after football training finishes","More"]}""")!!
        assertEquals(listOf("Thanks", "Undo that", "Move it to 20:00 instead, after football"), reading.suggestedReplies)
        val system = ChatPrompts.system(com.thomaswcode.decrastination.data.Settings(felixName = "Max", felixLine = "a coach"), 13)
        assertTrue(system.startsWith("You are Max: a coach, and this is the chat in a UK sixth-form student's (Year 13) planner app."), system)
        assertTrue("is made as soon as you give it" in system && "give what puts it back" in system && "never what you'd do" in system)
    }

    @Test
    fun `a message of Felix's before any of yours goes with your first turn`() {
        val messages = listOf(
            ChatMessage("f", Speaker.Claude, "Ten minutes into the physics: starting?", now - 600_000L, reason = "SlotStart"),
            ChatMessage("r", Speaker.You, "Give me 15", now),
        )
        val turns = ChatPrompts.turns(messages, "", mapOf(bridge.id to bridge), LONDON)
        assertEquals(listOf(Speaker.You), turns.map { it.first })
        assertTrue("(You'd written to them: “Ten minutes into the physics: starting?”)" in turns.single().second, turns.single().second)
        assertTrue(turns.single().second.endsWith("Give me 15"))
    }

    @Test
    fun `two messages of Felix's in a row are one turn`() {
        val messages = listOf(
            ChatMessage("1", Speaker.You, "Move the physics", now - 3_000_000L, answeredAt = now - 2_900_000L),
            ChatMessage("2", Speaker.Claude, "Moved it to 19:30.", now - 2_900_000L),
            ChatMessage("3", Speaker.Claude, "Ten minutes into the physics: starting?", now - 600_000L, reason = "SlotStart"),
            ChatMessage("4", Speaker.You, "Give me 15", now),
        )
        val turns = ChatPrompts.turns(messages, "", mapOf(bridge.id to bridge), LONDON)
        assertEquals(listOf(Speaker.You, Speaker.Claude, Speaker.You), turns.map { it.first })
        assertEquals("Moved it to 19:30.\n\nTen minutes into the physics: starting?", turns[1].second)
    }

    @Test
    fun `a step said done is ticked, a task shown is a card, and what can't be is said`() {
        val essay = com.thomaswcode.decrastination.core.OwnTasks.create(
            com.thomaswcode.decrastination.core.OwnTask(title = "Essay", steps = listOf(com.thomaswcode.decrastination.core.OwnStep("Plan", 20), com.thomaswcode.decrastination.core.OwnStep("Write", 40))),
            "e", now,
        )
        val reading = ChatAnswers.parse(
            """{"reply":"Ticked the plan.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[{"type":"tickStep","taskId":"${essay.id}","stepIndex":0},{"type":"open","taskId":"${essay.id}","stepIndex":-1},{"type":"tickStep","taskId":"${essay.id}","stepIndex":7},{"type":"open","taskId":"nope","stepIndex":-1}]}""",
            listOf(essay), emptyList(), com.thomaswcode.decrastination.core.About(), now, LONDON,
        )!!
        assertEquals(
            listOf(
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.TickStep, essay.id, 0),
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Open, essay.id),
            ),
            reading.actions,
        )
        assertEquals(2, reading.leftOut.size)
        val cards = ChatAnswers.cards(reading, mapOf(essay.id to essay), LONDON)
        assertEquals(listOf(essay.id), cards.mapNotNull { it.open })
        // Its steps are listed by their place, for that.
        val context = ChatPrompts.context(listOf(essay), emptyList(), emptyList(), now, LONDON)
        assertTrue("    step 0: Plan (20 min)\n    step 1: Write (40 min)" in context, context)
    }

    @Test
    fun `a step to do again, a task of yours done, open again or deleted, and a setting are read, each only as it can be`() {
        val essay = com.thomaswcode.decrastination.core.OwnTasks.create(
            OwnTask(title = "Essay", steps = listOf(com.thomaswcode.decrastination.core.OwnStep("Plan", 20), com.thomaswcode.decrastination.core.OwnStep("Write", 40))),
            "e", now,
        ).let { t -> t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s }) }
        val finished = com.thomaswcode.decrastination.core.OwnTasks.create(OwnTask(title = "Letter", minutes = 20), "l", now)
            .copy(status = com.thomaswcode.decrastination.core.Status.Done, doneAt = now - 3_600_000L)
        val settings = com.thomaswcode.decrastination.data.Settings()
        fun action(type: String, task: String = "", step: Int = -1, setting: String = "", value: String = "") =
            """{"type":"$type","taskId":"$task","stepIndex":$step,"setting":"$setting","value":"$value"}"""
        val reading = ChatAnswers.parse(
            """{"reply":"Done.","newTasks":[],"changes":[],"memories":[],"suggestedReplies":[],"actions":[""" + listOf(
                action("untickStep", essay.id, 0), action("untickStep", essay.id, 1),
                action("done", essay.id), action("done", bridge.id),
                action("reopen", finished.id), action("delete", finished.id), action("delete", bridge.id),
                action("setting", setting = "boxMin", value = "40"), action("setting", setting = "boxMin", value = "4000"), action("setting", setting = "blockedApps", value = "none"),
            ).joinToString(",") + "]}",
            listOf(essay, finished, bridge), emptyList(), About(), now, LONDON, settings,
        )!!
        assertEquals(
            listOf(
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.UntickStep, essay.id, 0),
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Done, essay.id),
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Reopen, finished.id),
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Delete, finished.id),
                com.thomaswcode.decrastination.core.ChatAction(com.thomaswcode.decrastination.core.ChatAction.Type.Setting, "", setting = "boxMin", value = "40"),
            ),
            reading.actions.take(5),
        )
        assertEquals(5, reading.actions.size)
        // A step not done, a Teams task marked done or deleted, a box of 4000 minutes, and a blocker's setting: each said, not taken.
        assertEquals(5, reading.leftOut.size, reading.leftOut.toString())
        assertTrue(reading.leftOut.any { "is done when Teams says so" in it }, reading.leftOut.toString())
        assertTrue(reading.leftOut.any { "a setting the app doesn't let him change (blockedApps)" in it }, reading.leftOut.toString())
        // He's told of their own finished lately, and of the settings he can change, with their values.
        val context = ChatPrompts.context(listOf(essay, finished, bridge), emptyList(), emptyList(), now, LONDON, settings = settings)
        assertTrue("Their own tasks finished in the last week (id | title | finished):\n- ${finished.id} | Letter | " in context, context)
        assertTrue("- boxMin | A piece's length (minutes) | 30" in context, context)
        assertTrue("- weekdayHours | School-day working hours | 16:45-22:00" in context, context)
    }

    @Test
    fun `he searches the web only while he may, and keeps the pages found, each once`() {
        assertTrue("6. Search the web (web_search)" in ChatPrompts.system(com.thomaswcode.decrastination.data.Settings()))
        assertFalse("web_search" in ChatPrompts.system(com.thomaswcode.decrastination.data.Settings(felixSearches = false)))
        val found = listOf(
            "Essay prize" to "https://example.org/prize", "Essay prize again" to "https://example.org/prize",
            "Not a page" to "ftp://example.org/x", "Rules" to "https://example.org/rules",
            "A" to "https://a.example", "B" to "https://b.example", "C" to "https://c.example",
        )
        assertEquals(
            listOf("https://example.org/prize", "https://example.org/rules", "https://a.example", "https://b.example"),
            ChatAnswers.sources(found).map { it.url },
        )
        assertEquals("Essay prize", ChatAnswers.sources(found).first().title)
    }

    @Test
    fun `a time the plan couldn't keep is said where the piece went`() {
        val day = LocalDate.of(2026, 10, 16)
        fun at(h: Int, m: Int) = day.atTime(h, m).atZone(LONDON).toInstant().toEpochMilli()
        val test = piece("own:t", "Widget test", null, 5, 1, 1, null)
        val other = piece("own:u", "Essay", null, 30, 1, 1, null)
        fun slot(chunk: Chunk, start: Long, pinned: Boolean) = com.thomaswcode.decrastination.core.Slot(com.thomaswcode.decrastination.core.SlotKind.Work, chunk, start, start + chunk.minutes * 60_000L, com.thomaswcode.decrastination.core.WindowKind.Study, pinned)
        val buckets = listOf(DayBucket(day, 325, listOf(test, other), slots = listOf(slot(test, at(10, 45), pinned = true), slot(other, at(19, 0), pinned = true))))
        fun pin(taskId: String, time: Long, date: String? = "2026-10-16") = Change(ChangeType.AtTime, taskId = taskId, time = time, date = date)
        // 06:35 had nothing free, so the first free time after it (PL-04).
        assertEquals(listOf("06:35 had no free time: it's at 10:45."), ChatAnswers.movedPins(listOf(pin("own:t", at(6, 35))), buckets, LONDON))
        // Kept to the minute: nothing to say; nor of a change that isn't a time, or a day past the plan.
        assertEquals(emptyList(), ChatAnswers.movedPins(listOf(pin("own:u", at(19, 0)), Change(ChangeType.Minutes, taskId = "own:u", minutes = 20), pin("own:u", at(9, 0), "2026-11-30")), buckets, LONDON))
        // Not laid that day at all.
        assertEquals(listOf("21:50 had no free time, nor any after it that day."), ChatAnswers.movedPins(listOf(pin("own:v", at(21, 50))), buckets, LONDON))
    }
}
