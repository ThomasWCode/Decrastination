package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.learn.CalendarEvent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstructionsTest {
    private val now = Fixtures.at("2026-10-09T21:00")

    private fun task(id: String, due: String?, effort: Int = 60, source: Source = Source.Teams) = TaskItem(
        id = "${source.name.lowercase()}:$id",
        source = source,
        sourceId = id,
        title = id,
        dueAt = due?.let(Fixtures::at),
        kind = Kind.Homework,
        sourceEffortMin = effort,
        firstSeenAt = now,
        lastSeenAt = now,
        sourceValues = SourceValues(Kind.Homework, due?.let(Fixtures::at), null),
    )

    private fun applied(vararg changes: Change, at: Long = now) = Instruction("i$at", "words", at = at, state = InstructionStatus.Applied, changes = changes.toList(), appliedAt = at)

    private fun plan(tasks: List<TaskItem>, applied: List<Instruction> = emptyList(), at: Long = now): Plan {
        val today = Planner.date(at, LONDON)
        return Planner.plan(
            Planner.Input(
                tasks,
                at,
                LONDON,
                Settings(),
                busy = Instructions.busy(applied, LONDON, today, today.plusDays(90)),
                dayCaps = Instructions.dayCaps(applied, today, today.plusDays(90)),
            ),
        )
    }

    // --- Tasks ---

    @Test
    fun `a due date of yours is laid over the source's, kept through a sync, and taken back`() {
        val essay = task("essay", "2026-10-12T09:00")
        val due = Fixtures.at("2026-10-16T23:59")
        val overrides = Instructions.taskOverrides(listOf(applied(Change(ChangeType.DueBy, taskId = essay.id, time = due))))
        val moved = essay.withOverrides(overrides.getValue(essay.id))
        assertEquals(due, moved.dueAt)
        // A sync reads the source's date again: yours still stands.
        val synced = Merge.apply(listOf(moved), Source.Teams, listOf(Fetched("essay", "essay", Kind.Homework, dueAt = Fixtures.at("2026-10-12T09:00"), sourceEffortMin = 60)), now).tasks.single()
        assertEquals(due, synced.dueAt)
        // Taken back: the source's again.
        assertEquals(Fixtures.at("2026-10-12T09:00"), synced.withOverrides(TaskOverrides.NONE).dueAt)
    }

    @Test
    fun `a later instruction's change to a task wins over an earlier one's`() {
        val first = applied(Change(ChangeType.StartFrom, taskId = "teams:a", time = 1L), at = now)
        val second = applied(Change(ChangeType.StartFrom, taskId = "teams:a", time = 2L), Change(ChangeType.NotATask, taskId = "teams:a"), at = now + 1)
        val state = InstructionState(listOf(second, first))
        val o = Instructions.taskOverrides(state.applied).getValue("teams:a")
        assertEquals(2L, o.from)
        assertTrue(o.notATask)
    }

    @Test
    fun `a start of yours holds a task back, but never brings it forward`() {
        val later = Fixtures.at("2026-10-14T00:00")
        val held = task("a", "2026-10-20T09:00").copy(availableFrom = Fixtures.at("2026-10-15T00:00"), sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-20T09:00"), Fixtures.at("2026-10-15T00:00")))
        assertEquals(Fixtures.at("2026-10-15T00:00"), held.withOverrides(TaskOverrides(from = later)).availableFrom)
        assertEquals(later, task("b", "2026-10-20T09:00").withOverrides(TaskOverrides(from = later)).availableFrom)
    }

    @Test
    fun `a task you've said isn't one, or that waits for another still open, isn't planned`() {
        val email = task("note", null, source = Source.Gmail).withOverrides(TaskOverrides(notATask = true))
        val first = task("first", "2026-10-12T09:00")
        val second = task("second", "2026-10-13T09:00").withOverrides(TaskOverrides(after = first.id))
        val planned = plan(listOf(email, first, second)).ordered.map { it.taskId }.toSet()
        assertEquals(setOf(first.id), planned)
        assertTrue(email.hidden)
        // The first done, the second is planned.
        val after = plan(listOf(first.copy(status = Status.Done), second)).ordered.map { it.taskId }.toSet()
        assertEquals(setOf(second.id), after)
    }

    @Test
    fun `a task said not to be one isn't read by the enrichment`() {
        assertNull(Enrichments.jobFor(task("a", null, source = Source.Gmail).copy(userNotATask = true)))
    }

    // --- Days ---

    @Test
    fun `a day with no time holds no work, and the work moves to other days`() {
        // Friday evening; essay due Monday morning. Saturday off.
        val essay = task("essay", "2026-10-12T09:00", effort = 120)
        val off = applied(Change(ChangeType.DayLimit, date = "2026-10-10", freeMin = 0))
        val p = plan(listOf(essay), listOf(off))
        val saturday = p.buckets.first { it.date == LocalDate.parse("2026-10-10") }
        assertEquals(0, saturday.capacityMin)
        assertTrue(saturday.chunks.isEmpty())
        assertEquals(120, p.buckets.sumOf { b -> b.chunks.filter { it.taskId == essay.id }.sumOf { it.minutes } })
    }

    @Test
    fun `a date's own limit beats its weekday's, and a weekday's holds every week`() {
        val rules = listOf(
            applied(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0), at = now),
            applied(Change(ChangeType.DayLimit, date = "2026-10-17", freeMin = 90), at = now + 1),
        )
        val caps = Instructions.dayCaps(rules, LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-24"))
        assertEquals(0, caps[LocalDate.parse("2026-10-10")])
        assertEquals(90, caps[LocalDate.parse("2026-10-17")])
        assertEquals(0, caps[LocalDate.parse("2026-10-24")])
        assertNull(caps[LocalDate.parse("2026-10-11")])
    }

    @Test
    fun `busy times of yours come off a day's time`() {
        // Within a school day's hours (from 16:45).
        val rule = applied(Change(ChangeType.BusyTime, weekday = 2, startMin = 18 * 60, endMin = 20 * 60))
        val busy = Instructions.busy(listOf(rule), LONDON, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-14"))
        assertEquals(listOf(Busy(Fixtures.at("2026-10-13T18:00"), Fixtures.at("2026-10-13T20:00"))), busy)
        val tuesday = LocalDate.parse("2026-10-13")
        val input = Planner.Input(emptyList(), Fixtures.at("2026-10-13T08:00"), LONDON, Settings(), busy = busy)
        val without = Planner.Input(emptyList(), Fixtures.at("2026-10-13T08:00"), LONDON, Settings())
        assertEquals(120, Planner.capacity(tuesday, without) - Planner.capacity(tuesday, input))
    }

    @Test
    fun `an event's answer from an instruction is the calendar's`() {
        val rule = applied(Change(ChangeType.EventTime, eventKey = "drum lesson", eventAnswer = "free"))
        assertEquals(mapOf("drum lesson" to "free"), Instructions.eventAnswers(listOf(rule)))
    }

    @Test
    fun `only a change of due date needs a parent code`() {
        assertTrue(Instructions.needsCode(listOf(Change(ChangeType.NotATask, taskId = "a"), Change(ChangeType.DueBy, taskId = "a", time = 1))))
        assertFalse(Instructions.needsCode(listOf(Change(ChangeType.NotATask, taskId = "a"), Change(ChangeType.DayLimit, date = "2026-10-10", freeMin = 0), Change(ChangeType.StartFrom, taskId = "a", time = 1))))
    }

    @Test
    fun `changes read in words`() {
        val essay = task("Essay", "2026-10-12T09:00")
        val tasks = mapOf(essay.id to essay)
        assertEquals("“Essay”: due Fri 16 Oct 23:59 (it says Mon 12 Oct 09:00)", Instructions.describe(Change(ChangeType.DueBy, taskId = essay.id, time = Fixtures.at("2026-10-16T23:59")), tasks, LONDON))
        assertEquals("“Essay”: can't be started before Wed 14 Oct", Instructions.describe(Change(ChangeType.StartFrom, taskId = essay.id, time = Fixtures.at("2026-10-14T00:00")), tasks, LONDON))
        assertEquals("Sat 10 Oct: no work", Instructions.describe(Change(ChangeType.DayLimit, date = "2026-10-10", freeMin = 0), tasks, LONDON))
        assertEquals("Every Friday: at most 1 h 30 min of work", Instructions.describe(Change(ChangeType.DayLimit, weekday = 5, freeMin = 90), tasks, LONDON))
        assertEquals("Every Tuesday, 16:00–18:00: busy, no work", Instructions.describe(Change(ChangeType.BusyTime, weekday = 2, startMin = 960, endMin = 1080), tasks, LONDON))
        assertEquals("“Train”: takes 2 h of its day", Instructions.describe(Change(ChangeType.EventTime, eventKey = "train", eventTitle = "Train", eventAnswer = "load:120"), tasks, LONDON))
    }

    // --- Claude's reading, checked ---

    private val essay = task("essay", "2026-10-12T09:00")
    private val prep = task("prep", "2026-10-13T09:00")
    private val drums = CalendarEvent(1, "Drum lesson", Fixtures.at("2026-10-13T17:00"), Fixtures.at("2026-10-13T18:00"), allDay = false)

    private fun parse(json: String) = InstructionAnswers.parse(json, listOf(essay, prep), listOf(drums), About(), now, LONDON)

    @Test
    fun `a reading that names listed tasks, events and plausible dates is taken`() {
        val reading = parse(
            """{"understood":true,"question":null,"changes":[
              {"type":"After","taskId":"teams:prep","afterTaskId":"teams:essay","eventKey":null,"eventTime":null,"minutes":null,"date":null,"weekday":null,"time":null,"endTime":null},
              {"type":"DueBy","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-16","weekday":null,"time":null,"endTime":null},
              {"type":"EventTime","taskId":null,"afterTaskId":null,"eventKey":"drum lesson","eventTime":"hours","minutes":90,"date":null,"weekday":null,"time":null,"endTime":null},
              {"type":"DayLimit","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":0,"date":null,"weekday":"Saturday","time":null,"endTime":null},
              {"type":"BusyTime","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-14","weekday":null,"time":"16:00","endTime":"18:30"}
            ]}""",
        )
        val changes = assertIs<InstructionAnswers.Reading.Changes>(reading).changes
        assertEquals(Change(ChangeType.After, taskId = "teams:prep", afterTaskId = "teams:essay"), changes[0])
        assertEquals(Fixtures.at("2026-10-16T23:59"), changes[1].time)
        assertEquals("load:90", changes[2].eventAnswer)
        assertEquals("Drum lesson", changes[2].eventTitle)
        assertEquals(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0), changes[3])
        assertEquals(Change(ChangeType.BusyTime, date = "2026-10-14", startMin = 960, endMin = 1110), changes[4])
    }

    @Test
    fun `one change that can't be made and none are, with why`() {
        val unknown = parse("""{"understood":true,"question":null,"changes":[{"type":"NotATask","taskId":"teams:nope","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":null,"weekday":null,"time":null,"endTime":null}]}""")
        assertEquals("Claude's reading couldn't be used: it names a task that isn't listed", assertIs<InstructionAnswers.Reading.Unclear>(unknown).why)
        val backwards = parse("""{"understood":true,"question":null,"changes":[{"type":"BusyTime","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-14","weekday":null,"time":"18:00","endTime":"16:00"}]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(backwards)
        val farOff = parse("""{"understood":true,"question":null,"changes":[{"type":"DueBy","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2030-01-01","weekday":null,"time":null,"endTime":null}]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(farOff)
        val self = parse("""{"understood":true,"question":null,"changes":[{"type":"After","taskId":"teams:essay","afterTaskId":"teams:essay","eventKey":null,"eventTime":null,"minutes":null,"date":null,"weekday":null,"time":null,"endTime":null}]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(self)
    }

    @Test
    fun `what Claude found unclear is said back`() {
        val reading = parse("""{"understood":false,"question":"Which Saturday do you mean?","changes":[]}""")
        assertEquals("Which Saturday do you mean?", assertIs<InstructionAnswers.Reading.Unclear>(reading).why)
        assertIs<InstructionAnswers.Reading.Unclear>(parse("not json"))
    }

    @Test
    fun `Claude is shown the instruction, what it's about, and what it can name`() {
        val instruction = Instruction("i", "this can't be done until the essay is", About(taskId = prep.id, taskTitle = prep.title), now)
        val text = InstructionPrompts.describe(instruction, listOf(essay, prep), listOf(drums), now, LONDON)
        assertTrue(text.startsWith("Today: Friday 9 October 2026, 21:00 (Europe/London)"))
        assertTrue("Instruction: \"this can't be done until the essay is\"" in text)
        assertTrue("About the task teams:prep:" in text)
        assertTrue("- teams:essay | essay | Teams | Mon 12 Oct 2026 09:00" in text)
        assertTrue("- drum lesson | Drum lesson | Tue 13 Oct 2026 17:00 to Tue 13 Oct 2026 18:00" in text)
        // Every field of a change is required, as the API's strict schemas need.
        @Suppress("UNCHECKED_CAST")
        val change = ((InstructionPrompts.SCHEMA["properties"] as Map<String, Any>)["changes"] as Map<String, Any>)["items"] as Map<String, Any>
        assertEquals((change["properties"] as Map<*, *>).keys.toList(), change["required"])
    }
}
