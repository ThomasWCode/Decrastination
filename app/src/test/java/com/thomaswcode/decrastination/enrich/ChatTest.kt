package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.OwnTask
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
    }

    @Test
    fun `the prompts are whole, the change types in both, with no stray indent`() {
        for (system in listOf(ChatPrompts.SYSTEM, InstructionPrompts.SYSTEM)) {
            assertTrue("\n- Minutes: how long the whole task takes" in system, system)
            assertFalse(system.lines().any { it.startsWith(" ") }, system)
        }
        assertNotNull(ChatPrompts.SCHEMA["properties"])
    }

    @Test
    fun `the chat waits on your last message till it's answered`() {
        val asked = ChatMessage("y", Speaker.You, "hello", now)
        assertEquals(asked, ChatState(listOf(asked)).unanswered)
        assertNull(ChatState(listOf(asked.copy(answeredAt = now), ChatMessage("c", Speaker.Claude, "hi", now))).unanswered)
    }
}
