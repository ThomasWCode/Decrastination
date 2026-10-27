package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstructionRulesTest {
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
                hardBusy = InstructionRules.busy(applied, LONDON, today, today.plusDays(90)),
                dayCaps = InstructionRules.dayCaps(applied, today, today.plusDays(90)),
                onlyDays = InstructionRules.onlyDays(applied, today, today.plusDays(90)),
            ),
        )
    }

    // --- Tasks ---

    @Test
    fun `a due date of yours is laid over the source's, kept through a sync, and taken back`() {
        val essay = task("essay", "2026-10-12T09:00")
        val due = Fixtures.at("2026-10-16T23:59")
        val overrides = InstructionRules.taskOverrides(listOf(applied(Change(ChangeType.DueBy, taskId = essay.id, time = due))))
        val moved = essay.withMine(overrides.getValue(essay.id))
        assertEquals(due, moved.dueAt)
        // A sync reads the source's date again: yours still stands.
        val synced = Merge.apply(listOf(moved), Source.Teams, listOf(Fetched("essay", "essay", Kind.Homework, dueAt = Fixtures.at("2026-10-12T09:00"), sourceEffortMin = 60)), now).tasks.single()
        assertEquals(due, synced.dueAt)
        // Taken back: the source's again.
        assertEquals(Fixtures.at("2026-10-12T09:00"), synced.withMine(Mine.NONE).dueAt)
    }

    @Test
    fun `a total Felix works out at your pace is kept as paced through a sync, and a later one of yours isn't`() {
        val essay = task("essay", "2026-10-12T09:00")
        val paced = applied(Change(ChangeType.Minutes, taskId = essay.id, minutes = 90, pacedFrom = 60, pacedHash = Enrichments.inputHash(essay)))
        val laid = essay.withMine(InstructionRules.taskOverrides(listOf(paced)).getValue(essay.id))
        assertEquals(Triple(90, true, true), Triple(laid.effortMin, laid.pacedMinutes, laid.pacedTeaches))
        assertTrue("“essay”: takes 1 h 30 min in all at your pace" in InstructionRules.describe(paced.changes.single(), mapOf(essay.id to essay), LONDON))
        // A sync keeps it, as it keeps your other changes.
        fun synced(estimate: Int, detail: String = "") =
            Merge.apply(listOf(laid), Source.Teams, listOf(Fetched("essay", "essay", Kind.Homework, detail = detail, dueAt = Fixtures.at("2026-10-12T09:00"), sourceEffortMin = estimate)), now).tasks.single()
        assertTrue(synced(60).pacedTeaches)
        // Estimated anew, its content the same (as a re-read for newer prompts is): still the pace.
        assertEquals(Triple(90, true, true), synced(120).let { Triple(it.effortMin, it.pacedMinutes, it.pacedTeaches) })
        // Its content changed (new instructions): still Felix's total, but the work may have changed, so not the pace.
        assertEquals(Triple(90, true, false), synced(60, detail = "Also questions 9 to 12.").let { Triple(it.effortMin, it.pacedMinutes, it.pacedTeaches) })
        // Your own reckoning after it: yours, not paced; and taken back, neither.
        val yours = applied(Change(ChangeType.Minutes, taskId = essay.id, minutes = 120), at = now + 1)
        val mine = essay.withMine(InstructionRules.taskOverrides(listOf(paced, yours)).getValue(essay.id))
        assertEquals(120 to false, mine.effortMin to mine.pacedMinutes)
        assertFalse(laid.withMine(Mine.NONE).pacedMinutes)
        // Never on a task of your own, whose estimate is yours.
        assertFalse(task("mine", "2026-10-12T09:00", source = Source.Own).withMine(mineOf(minutes = 90, minutesPacedFrom = 60)).pacedMinutes)
    }

    @Test
    fun `a later instruction's change to a task wins over an earlier one's`() {
        val first = applied(Change(ChangeType.StartFrom, taskId = "teams:a", time = 1L), at = now)
        val second = applied(Change(ChangeType.StartFrom, taskId = "teams:a", time = 2L), Change(ChangeType.NotATask, taskId = "teams:a"), at = now + 1)
        val state = InstructionState(listOf(second, first))
        val o = InstructionRules.taskOverrides(state.applied).getValue("teams:a")
        assertEquals(2L, o.from?.value)
        assertTrue(o.notATask != null)
    }

    @Test
    fun `a start of yours places the work from then on, seen coming, never earlier`() {
        // Friday; can't start before Sunday; due Tuesday. Planned Sunday on, and in the plan now.
        val essay = task("essay", "2026-10-13T23:59", effort = 90).withMine(mineOf(from = Fixtures.at("2026-10-11T00:00")))
        assertNull(essay.availableFrom)
        val p = plan(listOf(essay))
        val days = p.buckets.filter { b -> b.chunks.any { it.taskId == essay.id } }.map { it.date }
        assertTrue(days.isNotEmpty())
        assertTrue(days.all { it >= LocalDate.parse("2026-10-11") })
        assertEquals(90, p.buckets.sumOf { b -> b.chunks.filter { it.taskId == essay.id }.sumOf { it.minutes } })
    }

    @Test
    fun `a start later in a day has only that day's time from then`() {
        // Friday 17:00; can't start before 21:30, and Friday's hours end at 22:00: half an hour.
        val from = Fixtures.at("2026-10-09T21:30")
        val later = task("later", "2026-10-12T23:59", effort = 90).withMine(mineOf(from = from))
        val p = plan(listOf(later), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(p.buckets.first { it.date == LocalDate.parse("2026-10-09") }.chunks.none { it.taskId == later.id })
        assertEquals(90, p.buckets.sumOf { b -> b.chunks.filter { it.taskId == later.id }.sumOf { it.minutes } })
        // Due by Friday's end: what can't fit after 21:30 is behind, not counted as fitting.
        val tight = task("tight", "2026-10-10T23:59", effort = 90).withMine(mineOf(from = from))
        val q = plan(listOf(tight), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(q.buckets.flatMap { it.chunks }.filter { it.taskId == tight.id }.any { it.behind })
    }

    @Test
    fun `tasks starting late the same day share that day's time after the start`() {
        // Friday's hours end at 22:00: an hour after 21:00, for two 45-minute tasks that can't start before it.
        val from = Fixtures.at("2026-10-09T21:00")
        val a = task("a", "2026-10-12T23:59", effort = 45).withMine(mineOf(from = from))
        val b = task("b", "2026-10-12T23:59", effort = 45).withMine(mineOf(from = from))
        val friday = plan(listOf(a, b), at = Fixtures.at("2026-10-09T17:00")).buckets.first { it.date == LocalDate.parse("2026-10-09") }
        assertTrue(friday.plannedMin <= 60)
    }

    @Test
    fun `a deck task made for an assignment follows the assignment's instructions`() {
        val homework = task("vocab", "2026-10-13T09:00")
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 1.2", kind = Kind.Homework,
            dueAt = Fixtures.at("2026-10-13T09:00"), sourceEffortMin = 20, derived = true, firstSeenAt = now, lastSeenAt = now,
            extra = mapOf(AnkiRules.EXTRA_FOR to homework.id),
        )
        // Not a task: its deck goes with it.
        val hidden = plan(listOf(homework.withMine(mineOf(notATask = true)), deck)).ordered.map { it.taskId }
        assertTrue(deck.id !in hidden)
        // Can't start before Sunday: nor can its deck.
        val sunday = Fixtures.at("2026-10-11T00:00")
        val started = plan(listOf(homework.withMine(mineOf(from = sunday)), deck))
        val deckDays = started.buckets.filter { b -> b.chunks.any { it.taskId == deck.id } }.map { it.date }
        assertTrue(deckDays.isNotEmpty() && deckDays.all { it >= LocalDate.parse("2026-10-11") })
        // Left as it is, the deck's planned.
        assertTrue(deck.id in plan(listOf(homework, deck)).ordered.map { it.taskId })
    }

    @Test
    fun `earlier-starting work that can't fit before a later start shares its time`() {
        // Friday's hours end at 22:00. 90 minutes from 20:00 and 60 from 21:00 don't both fit.
        val a = task("a", "2026-10-12T23:59", effort = 90).copy(subSteps = listOf(SubStep("A", 90))).withMine(mineOf(from = Fixtures.at("2026-10-09T20:00")))
        val b = task("b", "2026-10-12T23:59", effort = 60).copy(subSteps = listOf(SubStep("B", 60))).withMine(mineOf(from = Fixtures.at("2026-10-09T21:00")))
        val friday = plan(listOf(a, b), at = Fixtures.at("2026-10-09T17:00")).buckets.first { it.date == LocalDate.parse("2026-10-09") }
        assertTrue(friday.plannedMin <= 120)
    }

    @Test
    fun `your busy times aren't overfilled even by overdue work`() {
        // Friday 17:00, the essay overdue; busy all evening, as you said.
        val essay = task("essay", "2026-10-08T09:00", effort = 60)
        val busy = applied(Change(ChangeType.BusyTime, date = "2026-10-09", startMin = 16 * 60, endMin = 22 * 60))
        val p = plan(listOf(essay), listOf(busy), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(p.buckets.first { it.date == LocalDate.parse("2026-10-09") }.chunks.isEmpty())
        assertEquals(60, p.buckets.sumOf { b -> b.chunks.filter { it.taskId == essay.id }.sumOf { it.minutes } })
    }

    @Test
    fun `a task you said isn't one holds nothing back`() {
        val info = task("info", null).withMine(mineOf(notATask = true))
        val essay = task("essay", "2026-10-12T09:00").withMine(mineOf(after = info.id))
        assertFalse(InstructionRules.waiting(essay, listOf(info, essay).associateBy { it.id }))
        assertTrue(essay.id in plan(listOf(info, essay)).ordered.map { it.taskId })
    }

    @Test
    fun `a deck task is due with its assignment's due date as it is now`() {
        val homework = task("vocab", "2026-10-20T09:00").withMine(mineOf(dueAt = Fixtures.at("2026-10-12T09:00")))
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 1.2", kind = Kind.Homework,
            dueAt = Fixtures.at("2026-10-20T09:00"), sourceEffortMin = 20, derived = true, firstSeenAt = now, lastSeenAt = now,
            extra = mapOf(AnkiRules.EXTRA_FOR to homework.id),
        )
        val chunk = plan(listOf(homework, deck)).ordered.first { it.taskId == deck.id }
        assertEquals(Fixtures.at("2026-10-12T09:00"), chunk.dueAt)
    }

    @Test
    fun `a day's limit counts what's been worked today already`() {
        val essay = task("essay", "2026-10-09T23:00", effort = 120)
        val limit = applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 60))
        val at = Fixtures.at("2026-10-09T18:00")
        val today = LocalDate.parse("2026-10-09")
        val input = Planner.Input(listOf(essay), at, LONDON, Settings(), dayCaps = InstructionRules.dayCaps(listOf(limit), today, today.plusDays(30)))
        assertEquals(60, Planner.capacity(today, input))
        // An hour's session done: nothing more today.
        assertEquals(0, Planner.capacity(today, input.copy(workedTodayMin = 60)))
        // Tomorrow's limit is its own.
        assertEquals(Planner.capacity(today.plusDays(1), input), Planner.capacity(today.plusDays(1), input.copy(workedTodayMin = 60)))
    }

    @Test
    fun `work behind on a day you've limited keeps its steps in order`() {
        // Due tomorrow, its last usable day today (a day's margin); today limited to 45 minutes.
        val steps = listOf(SubStep("First", 45), SubStep("Second", 45))
        val essay = task("essay", "2026-10-10T23:59", effort = 90).copy(subSteps = steps)
        val limit = applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 45))
        val p = plan(listOf(essay), listOf(limit), at = Fixtures.at("2026-10-09T17:00"))
        val placed = p.buckets.flatMap { b -> b.chunks.filter { it.taskId == essay.id }.map { b.date to it.step } }
        assertEquals(listOf(LocalDate.parse("2026-10-09") to "First", LocalDate.parse("2026-10-10") to "Second"), placed)
    }

    @Test
    fun `a task you've said isn't one, or that waits for another still open, isn't planned`() {
        val email = task("note", null, source = Source.Gmail).withMine(mineOf(notATask = true))
        val first = task("first", "2026-10-12T09:00")
        val second = task("second", "2026-10-13T09:00").withMine(mineOf(after = first.id))
        val planned = plan(listOf(email, first, second)).ordered.map { it.taskId }.toSet()
        assertEquals(setOf(first.id), planned)
        assertTrue(email.hidden)
        // The first done, the second is planned.
        val after = plan(listOf(first.copy(status = Status.Done), second)).ordered.map { it.taskId }.toSet()
        assertEquals(setOf(second.id), after)
    }

    @Test
    fun `a task said not to be one isn't read by the enrichment`() {
        assertNull(Enrichments.jobFor(task("a", null, source = Source.Gmail).yours(notATask = true), GmailThreads::textRead))
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
    fun `a day you've limited isn't overfilled even by overdue work, which goes to the next day with time`() {
        // Friday evening; the essay was due yesterday. Today: no work, as you said.
        val essay = task("essay", "2026-10-08T09:00", effort = 90)
        val off = applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 0))
        val p = plan(listOf(essay), listOf(off), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(p.buckets.first { it.date == LocalDate.parse("2026-10-09") }.chunks.isEmpty())
        assertEquals(90, p.buckets.first { it.date == LocalDate.parse("2026-10-10") }.chunks.filter { it.taskId == essay.id }.sumOf { it.minutes })
        // Limited to 45 minutes: no more than that today, the rest tomorrow.
        val some = applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 45))
        val q = plan(listOf(essay), listOf(some), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(q.buckets.first { it.date == LocalDate.parse("2026-10-09") }.plannedMin <= 45)
        assertEquals(90, q.buckets.sumOf { b -> b.chunks.filter { it.taskId == essay.id }.sumOf { it.minutes } })
    }

    @Test
    fun `tasks waiting on each other in a circle both get planned, and a circle isn't applied`() {
        val a = task("a", "2026-10-12T09:00").yours(after = "teams:b")
        val b = task("b", "2026-10-13T09:00").yours(after = "teams:a")
        val byId = listOf(a, b).associateBy { it.id }
        assertFalse(InstructionRules.waiting(a, byId))
        assertFalse(InstructionRules.waiting(b, byId))
        assertEquals(setOf(a.id, b.id), plan(listOf(a, b)).ordered.map { it.taskId }.toSet())
        // One waiting on a task stuck in a circle still waits for it.
        val c = task("c", "2026-10-14T09:00").yours(after = "teams:a")
        assertTrue(InstructionRules.waiting(c, byId + (c.id to c)))
        // A new one closing a circle with one applied is caught.
        val applied = listOf(applied(Change(ChangeType.After, taskId = "teams:a", afterTaskId = "teams:b")))
        assertTrue(InstructionRules.makesCircle(listOf(Change(ChangeType.After, taskId = "teams:b", afterTaskId = "teams:a")), applied))
        assertFalse(InstructionRules.makesCircle(listOf(Change(ChangeType.After, taskId = "teams:c", afterTaskId = "teams:a")), applied))
    }

    @Test
    fun `a date's own limit beats its weekday's, and a weekday's holds every week`() {
        val rules = listOf(
            applied(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0), at = now),
            applied(Change(ChangeType.DayLimit, date = "2026-10-17", freeMin = 90), at = now + 1),
        )
        val caps = InstructionRules.dayCaps(rules, LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-24"))
        assertEquals(0, caps[LocalDate.parse("2026-10-10")])
        assertEquals(90, caps[LocalDate.parse("2026-10-17")])
        assertEquals(0, caps[LocalDate.parse("2026-10-24")])
        assertNull(caps[LocalDate.parse("2026-10-11")])
    }

    @Test
    fun `busy times of yours come off a day's time`() {
        // Within a school day's hours (from 16:45).
        val rule = applied(Change(ChangeType.BusyTime, weekday = 2, startMin = 18 * 60, endMin = 20 * 60))
        val busy = InstructionRules.busy(listOf(rule), LONDON, LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-14"))
        assertEquals(listOf(Busy(Fixtures.at("2026-10-13T18:00"), Fixtures.at("2026-10-13T20:00"))), busy)
        val tuesday = LocalDate.parse("2026-10-13")
        val input = Planner.Input(emptyList(), Fixtures.at("2026-10-13T08:00"), LONDON, Settings(), busy = busy)
        val without = Planner.Input(emptyList(), Fixtures.at("2026-10-13T08:00"), LONDON, Settings())
        assertEquals(120, Planner.capacity(tuesday, without) - Planner.capacity(tuesday, input))
    }

    @Test
    fun `an event's answer from an instruction is the calendar's`() {
        val rule = applied(Change(ChangeType.EventTime, eventKey = "drum lesson", eventAnswer = "free"))
        assertEquals(mapOf("drum lesson" to "free"), InstructionRules.eventAnswers(listOf(rule)))
    }

    @Test
    fun `changes read in words`() {
        val essay = task("Essay", "2026-10-12T09:00")
        val tasks = mapOf(essay.id to essay)
        assertEquals("“Essay”: due Fri 16 Oct 23:59 (now Mon 12 Oct 09:00)", InstructionRules.describe(Change(ChangeType.DueBy, taskId = essay.id, time = Fixtures.at("2026-10-16T23:59")), tasks, LONDON))
        // Once it's the date in use, against the source's.
        val moved = essay.withMine(mineOf(dueAt = Fixtures.at("2026-10-16T23:59")))
        assertEquals("“Essay”: due Fri 16 Oct 23:59 (its source says Mon 12 Oct 09:00)", InstructionRules.describe(Change(ChangeType.DueBy, taskId = essay.id, time = Fixtures.at("2026-10-16T23:59")), mapOf(essay.id to moved), LONDON))
        assertEquals("“Essay”: can't be started before Wed 14 Oct", InstructionRules.describe(Change(ChangeType.StartFrom, taskId = essay.id, time = Fixtures.at("2026-10-14T00:00")), tasks, LONDON))
        assertEquals("Sat 10 Oct: no work", InstructionRules.describe(Change(ChangeType.DayLimit, date = "2026-10-10", freeMin = 0), tasks, LONDON))
        assertEquals("Every Friday: at most 1 h 30 min of work", InstructionRules.describe(Change(ChangeType.DayLimit, weekday = 5, freeMin = 90), tasks, LONDON))
        assertEquals("Every Tuesday, 16:00–18:00: busy, no work", InstructionRules.describe(Change(ChangeType.BusyTime, weekday = 2, startMin = 960, endMin = 1080), tasks, LONDON))
        assertEquals("“Train”: takes 2 h of its day", InstructionRules.describe(Change(ChangeType.EventTime, eventKey = "train", eventTitle = "Train", eventAnswer = "load:120"), tasks, LONDON))
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
    fun `a field given empty is none, as the schema has no nullable fields`() {
        // The same reading as nulls give, with an empty string for an unused field and -1 for an unused number.
        fun change(type: String, taskId: String = "", after: String = "", event: String = "", eventTime: String = "", minutes: Int = -1, date: String = "", endDate: String = "", weekday: String = "", time: String = "", endTime: String = "") =
            """{"type":"$type","taskId":"$taskId","afterTaskId":"$after","eventKey":"$event","eventTime":"$eventTime","minutes":$minutes,"date":"$date","endDate":"$endDate","weekday":"$weekday","time":"$time","endTime":"$endTime","title":"","steps":[]}"""
        val reading = parse(
            """{"understood":true,"question":null,"changes":[
              ${change("After", taskId = "teams:prep", after = "teams:essay")},
              ${change("DueBy", taskId = "teams:essay", date = "2026-10-16")},
              ${change("StartFrom", taskId = "teams:prep", date = "2026-10-12")},
              ${change("EventTime", event = "drum lesson", eventTime = "hours", minutes = 90)},
              ${change("DayLimit", minutes = 0, weekday = "Saturday")},
              ${change("NoSchool", date = "2026-10-14")}
            ]}""",
        )
        val changes = assertIs<InstructionAnswers.Reading.Changes>(reading).changes
        assertEquals(Change(ChangeType.After, taskId = "teams:prep", afterTaskId = "teams:essay"), changes[0])
        assertEquals(Fixtures.at("2026-10-16T23:59"), changes[1].time)
        // No time: the start of the day.
        assertEquals(Fixtures.at("2026-10-12T00:00"), changes[2].time)
        assertEquals("load:90", changes[3].eventAnswer)
        // A day's limit of none at all is 0, not none.
        assertEquals(Change(ChangeType.DayLimit, weekday = 6, freeMin = 0), changes[4])
        // No end date: the one day.
        assertEquals(Change(ChangeType.NoSchool, date = "2026-10-14", endDate = "2026-10-14"), changes[5])
    }

    @Test
    fun `days off and half-days are read as dates, and said`() {
        // Half term as one change over its days; a half-day ending at 12:30 (PL-16).
        val reading = parse(
            """{"understood":true,"question":null,"changes":[
              {"type":"NoSchool","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-26","endDate":"2026-10-30","weekday":null,"time":null,"endTime":null},
              {"type":"NoSchool","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-11-13","endDate":null,"weekday":null,"time":null,"endTime":null},
              {"type":"HalfDay","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-23","endDate":null,"weekday":null,"time":null,"endTime":"12:30"}
            ]}""",
        )
        val changes = assertIs<InstructionAnswers.Reading.Changes>(reading).changes
        assertEquals(Change(ChangeType.NoSchool, date = "2026-10-26", endDate = "2026-10-30"), changes[0])
        assertEquals(Change(ChangeType.NoSchool, date = "2026-11-13", endDate = "2026-11-13"), changes[1])
        assertEquals(Change(ChangeType.HalfDay, date = "2026-10-23", endMin = 750), changes[2])
        val tasks = emptyMap<String, TaskItem>()
        assertEquals("No school from Mon 26 Oct to Fri 30 Oct", InstructionRules.describe(changes[0], tasks, LONDON))
        assertEquals("No school on Fri 13 Nov", InstructionRules.describe(changes[1], tasks, LONDON))
        assertEquals("Half-day on Fri 23 Oct, ends 12:30", InstructionRules.describe(changes[2], tasks, LONDON))
        assertEquals(NoSchool("2026-10-26", "2026-10-30"), InstructionRules.schoolEntry(changes[0]))
        assertEquals(HalfDay("2026-10-23", 750), InstructionRules.schoolEntry(changes[2]))
        // An end before the start, or a weekday for a stretch, isn't a reading the app can use.
        fun one(change: String) = parse("""{"understood":true,"question":null,"changes":[$change]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"NoSchool","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-30","endDate":"2026-10-26","weekday":null,"time":null,"endTime":null}"""))
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"NoSchool","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":null,"endDate":null,"weekday":"Monday","time":null,"endTime":null}"""))
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"HalfDay","taskId":null,"afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-23","endDate":null,"weekday":null,"time":null,"endTime":null}"""))
    }

    @Test
    fun `a day for a task and a time for its next piece are read, said, and laid on the task`() {
        // Friday 9 Oct: all of the essay on Sunday; the prep's next piece at 19:30 on Monday (PL-04).
        val reading = parse(
            """{"understood":true,"question":null,"changes":[
              {"type":"OnDay","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null},
              {"type":"AtTime","taskId":"teams:prep","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-12","endDate":null,"weekday":null,"time":"19:30","endTime":null}
            ]}""",
        )
        val changes = assertIs<InstructionAnswers.Reading.Changes>(reading).changes
        assertEquals(Change(ChangeType.OnDay, taskId = "teams:essay", date = "2026-10-11"), changes[0])
        assertEquals(Change(ChangeType.AtTime, taskId = "teams:prep", date = "2026-10-12", startMin = 19 * 60 + 30, time = Fixtures.at("2026-10-12T19:30")), changes[1])
        val tasks = listOf(essay, prep).associateBy { it.id }
        assertEquals("All of “essay” on Sun 11 Oct", InstructionRules.describe(changes[0], tasks, LONDON))
        assertEquals("“prep” at 19:30 on Mon 12 Oct", InstructionRules.describe(changes[1], tasks, LONDON))
        // Laid on the tasks.
        val overrides = InstructionRules.taskOverrides(listOf(applied(*changes.toTypedArray())))
        assertEquals("2026-10-11", overrides.getValue(essay.id).onDay?.value)
        assertEquals(Fixtures.at("2026-10-12T19:30"), prep.withMine(overrides.getValue(prep.id)).mine.atTime?.value)
        assertEquals(overrides.getValue(prep.id), prep.withMine(overrides.getValue(prep.id)).mine)
        // A time on the day all of it is kept to keeps the day; a later day for all of it drops a time on another.
        val both = InstructionRules.taskOverrides(listOf(applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-12"), changes[1])))
        assertEquals(mineOf(onDay = "2026-10-12", atTime = Fixtures.at("2026-10-12T19:30")), both.getValue(prep.id))
        val later = InstructionRules.taskOverrides(listOf(applied(changes[1]), applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-11"), at = now + 1)))
        assertEquals(mineOf(onDay = "2026-10-11"), later.getValue(prep.id))
        // The day given after the time, for the time's own day: both kept, as in the other order.
        val same = InstructionRules.taskOverrides(listOf(applied(changes[1]), applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-12"), at = now + 1)))
        assertEquals(mineOf(onDay = "2026-10-12", atTime = Fixtures.at("2026-10-12T19:30")), same.getValue(prep.id))
        // A weekday isn't a day for a task, nor a time without one.
        fun one(change: String) = parse("""{"understood":true,"question":null,"changes":[$change]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"OnDay","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":null,"endDate":null,"weekday":"Monday","time":null,"endTime":null}"""))
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"AtTime","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null}"""))
    }

    // --- A day for only some tasks (D-115) ---

    private val saturday = LocalDate.parse("2026-10-10")

    private fun Plan.on(day: LocalDate, task: TaskItem) = bucket(day)!!.chunks.filter { it.taskId == task.id }

    /** [tasks] with [applied]'s values laid on them, as the app plans them, then planned. */
    private fun laid(tasks: List<TaskItem>, applied: List<Instruction>, at: Long = now): Plan {
        val overrides = InstructionRules.taskOverrides(applied)
        return plan(tasks.map { t -> overrides[t.id]?.let { t.withMine(it) } ?: t }, applied, at)
    }

    @Test
    fun `a day kept for only some tasks holds their work alone, the rest of its time free`() {
        // Friday 21:00; the essay due Wednesday, maths Monday; Saturday kept for the essay.
        val essay = task("essay", "2026-10-14T09:00", effort = 120)
        val maths = task("maths", "2026-10-12T09:00", effort = 240)
        val only = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"))
        val kept = plan(listOf(essay, maths), listOf(only))
        assertTrue(kept.on(saturday, maths).isEmpty())
        // All of maths still planned, on its other days; without the day kept, Saturday had some of it.
        assertEquals(240, kept.chunksOf(maths.id).sumOf { it.minutes })
        assertTrue(plan(listOf(essay, maths)).on(saturday, maths).isNotEmpty())
        // The day's free time isn't taken by anything else: what's planned there is the essay's alone.
        assertEquals(kept.on(saturday, essay).sumOf { it.minutes }, kept.bucket(saturday)!!.plannedMin)
    }

    @Test
    fun `its tasks are planned as usual, and one given the day as well has all its work there`() {
        val essay = task("essay", "2026-10-14T09:00", effort = 120)
        val only = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"))
        // As usual: where it would go anyway, not pulled onto the day.
        assertEquals(plan(listOf(essay)).chunksOf(essay.id).map { it.key to it.minutes }, plan(listOf(essay), listOf(only)).chunksOf(essay.id).map { it.key to it.minutes })
        assertEquals(plan(listOf(essay)).buckets.map { it.date to it.plannedMin }, plan(listOf(essay), listOf(only)).buckets.map { it.date to it.plannedMin })
        // Given the day too (OnDay): all of it there.
        val day = applied(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-10"), at = now + 1)
        assertEquals(120, laid(listOf(essay), listOf(only, day)).on(saturday, essay).sumOf { it.minutes })
    }

    @Test
    fun `a task moved onto the day after it was kept for others joins them, one moved there before is left out`() {
        val essay = task("essay", "2026-10-14T09:00", effort = 120)
        val maths = task("maths", "2026-10-16T09:00", effort = 60)
        val before = applied(Change(ChangeType.OnDay, taskId = maths.id, date = "2026-10-10"), at = now - 2)
        val only = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"), at = now - 1)
        val after = applied(Change(ChangeType.OnDay, taskId = maths.id, date = "2026-10-10"), at = now)
        val joined = InstructionRules.onlyDays(listOf(only, after), saturday, saturday).getValue(saturday)
        assertEquals(setOf(essay.id, maths.id) to setOf(maths.id), joined.taskIds to joined.joined)
        assertEquals(60, laid(listOf(essay, maths), listOf(only, after)).on(saturday, maths).sumOf { it.minutes })
        // Moved there before: left out, its work on other days.
        assertEquals(setOf(essay.id), InstructionRules.onlyDays(listOf(before, only), saturday, saturday).getValue(saturday).taskIds)
        val left = laid(listOf(essay, maths), listOf(before, only))
        assertTrue(left.on(saturday, maths).isEmpty())
        assertEquals(60, left.chunksOf(maths.id).sumOf { it.minutes })
        // Its day no pin then: its real deadline holds it, so none of its work is behind.
        assertTrue(left.chunksOf(maths.id).none { it.behind }, "${left.chunksOf(maths.id).map { it.behind }}")
        // A DayOnly made later (Felix's answer to his own question, say) keeps one that joined; the latest sets what's let in.
        val anki = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_ANKI), at = now + 1)
        val still = InstructionRules.onlyDays(listOf(only, after, anki), saturday, saturday).getValue(saturday)
        assertEquals(OnlyDay(setOf(essay.id, maths.id), anki = true, deadlines = false, joined = setOf(maths.id)), still)
        // Said as it joins.
        assertEquals("Sat 10 Oct was only for “essay”: “maths” joins it", InstructionRules.joinLine(saturday, still.taskIds - still.joined, maths.id, listOf(essay, maths).associateBy { it.id }))
        // Moved on after it joined (a time on Sunday): the move in force for it is that one, so it's no longer among them.
        val away = applied(Change(ChangeType.AtTime, taskId = maths.id, date = "2026-10-11", startMin = 600, time = Fixtures.at("2026-10-11T10:00")), at = now + 2)
        assertEquals(setOf(essay.id), InstructionRules.onlyDays(listOf(only, after, away), saturday, saturday).getValue(saturday).taskIds)
        // Days outside the range asked for, or none kept: none.
        assertEquals(emptyMap(), InstructionRules.onlyDays(listOf(only), saturday.plusDays(1), saturday.plusDays(5)))
    }

    @Test
    fun `Anki's cards come in only where the day lets them`() {
        // Saturday 08:00, kept for the essay: today's cards, due by 21:30.
        val at = Fixtures.at("2026-10-10T08:00")
        val essay = task("essay", "2026-10-14T09:00", effort = 60)
        val deck = task("deck", "2026-10-10T21:30", effort = 20, source = Source.Anki)
        val shut = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"), at = at)
        assertTrue(plan(listOf(essay, deck), listOf(shut), at = at).on(saturday, deck).isEmpty())
        val anki = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_ANKI), at = at)
        assertEquals(20, plan(listOf(essay, deck), listOf(anki), at = at).on(saturday, deck).sumOf { it.minutes })
    }

    @Test
    fun `with deadlines let in, work with no other day before its deadline takes the day, and work with one doesn't`() {
        // Saturday 08:00, kept for the essay: a form due 20:00 today has no other day; a sheet due Monday has Sunday and Monday.
        val at = Fixtures.at("2026-10-10T08:00")
        val essay = task("essay", "2026-10-14T09:00", effort = 60)
        val form = task("form", "2026-10-10T20:00", effort = 30)
        val sheet = task("sheet", "2026-10-12T21:00", effort = 30)
        val open = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_DEADLINES), at = at)
        val p = plan(listOf(essay, form, sheet), listOf(open), at = at)
        assertEquals(30, p.on(saturday, form).sumOf { it.minutes })
        assertTrue(p.on(saturday, sheet).isEmpty())
        assertTrue(p.wontFit.none { it.taskId == form.id })
        // Not let in: the form isn't on it, and is said not to fit before its deadline (PL-02).
        val shut = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"), at = at)
        val q = plan(listOf(essay, form, sheet), listOf(shut), at = at)
        assertTrue(q.on(saturday, form).isEmpty())
        assertTrue(q.wontFit.any { it.taskId == form.id })
    }

    @Test
    fun `deadlines are let in only where no day before the deadline itself has room, past the safety margin too`() {
        // Saturday 08:00, kept for the essay, deadlines let in: a sheet due Sunday 21:00, whose last usable day by the margin
        // is Saturday, has all of Sunday free, so it doesn't take Saturday.
        val at = Fixtures.at("2026-10-10T08:00")
        val essay = task("essay", "2026-10-14T09:00", effort = 60)
        val sheet = task("sheet", "2026-10-11T21:00", effort = 30)
        val open = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_DEADLINES), at = at)
        val p = plan(listOf(essay, sheet), listOf(open), at = at)
        assertTrue(p.on(saturday, sheet).isEmpty(), "${p.buckets.map { b -> b.date to b.chunks.filter { it.taskId == sheet.id }.size }}")
        assertEquals(30, p.chunksOf(sheet.id).sumOf { it.minutes })
    }

    @Test
    fun `a task's pieces are no longer than the most a day it can go on holds, a day kept for others not among them`() {
        // Friday, Friday and Sunday limited to 20 minutes, Saturday kept for the essay: a 40-minute sheet due Monday morning
        // goes in two pieces on Friday and Sunday, not one with no day to hold it.
        val friday = Fixtures.at("2026-10-09T17:00")
        val essay = task("essay", "2026-10-20T09:00", effort = 60)
        val sheet = task("sheet", "2026-10-12T09:00", effort = 40)
        val rules = listOf(
            applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 20), at = friday),
            applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"), at = friday + 1),
            applied(Change(ChangeType.DayLimit, date = "2026-10-11", freeMin = 20), at = friday + 2),
        )
        // With boxes of an hour, so only the days' room cuts it.
        val today = Planner.date(friday, LONDON)
        val p = Planner.plan(
            Planner.Input(
                listOf(essay, sheet), friday, LONDON, Settings(boxMin = 60),
                dayCaps = InstructionRules.dayCaps(rules, today, today.plusDays(90)), onlyDays = InstructionRules.onlyDays(rules, today, today.plusDays(90)),
            ),
        )
        assertEquals(listOf(20, 20), p.chunksOf(sheet.id).map { it.minutes })
        assertTrue(p.chunksOf(sheet.id).none { it.behind }, "${p.chunksOf(sheet.id).map { it.behind }}")
    }

    @Test
    fun `work carried on from a first day you limited still takes a day kept for others that lets deadlines in, where it would miss its deadline without`() {
        // Saturday 08:00, Saturday and Sunday limited to half an hour, Saturday kept for the essay with deadlines let in: a
        // 90-minute sheet due Monday morning has both days' half hours, so only its last is past them.
        val at = Fixtures.at("2026-10-10T08:00")
        val essay = task("essay", "2026-10-30T09:00", effort = 60)
        val sheet = task("sheet", "2026-10-12T09:00", effort = 90)
        val rules = listOf(
            applied(Change(ChangeType.DayLimit, date = "2026-10-10", freeMin = 30), at = at),
            applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_DEADLINES), at = at + 1),
            applied(Change(ChangeType.DayLimit, date = "2026-10-11", freeMin = 30), at = at + 2),
        )
        val p = plan(listOf(essay, sheet), rules, at = at)
        val days = p.buckets.associate { b -> b.date to b.chunks.filter { it.taskId == sheet.id }.sumOf { it.minutes } }.filterValues { it > 0 }
        assertEquals(30, days[saturday], "$days")
        assertEquals(30, days[LocalDate.parse("2026-10-11")], "$days")
        assertEquals(90, p.chunksOf(sheet.id).sumOf { it.minutes })
    }

    @Test
    fun `a task's pieces are sized by days kept for others that let deadlines in, where none else before its deadline has room`() {
        // Saturday 08:00, Saturday and Sunday limited to 20 minutes and kept for the essay with deadlines let in: a 40-minute
        // sheet due Monday morning goes in two pieces over the weekend, not one no day before its deadline holds.
        val at = Fixtures.at("2026-10-10T08:00")
        val essay = task("essay", "2026-10-30T09:00", effort = 60)
        val sheet = task("sheet", "2026-10-12T09:00", effort = 40)
        val rules = listOf("2026-10-10", "2026-10-11").flatMapIndexed { i, day ->
            listOf(
                applied(Change(ChangeType.DayLimit, date = day, freeMin = 20), at = at + 2 * i),
                applied(Change(ChangeType.DayOnly, taskId = essay.id, date = day, alsoAllow = InstructionRules.ALSO_DEADLINES), at = at + 2 * i + 1),
            )
        }
        val today = Planner.date(at, LONDON)
        val p = Planner.plan(
            Planner.Input(
                listOf(essay, sheet), at, LONDON, Settings(boxMin = 60),
                dayCaps = InstructionRules.dayCaps(rules, today, today.plusDays(90)), onlyDays = InstructionRules.onlyDays(rules, today, today.plusDays(90)),
            ),
        )
        assertEquals(listOf(20, 20), p.chunksOf(sheet.id).map { it.minutes })
        assertEquals(setOf(saturday, LocalDate.parse("2026-10-11")), p.buckets.filter { b -> b.chunks.any { it.taskId == sheet.id } }.map { it.date }.toSet())
    }

    @Test
    fun `work not open yet is said not to fit where the days before its deadline are kept for others, unless they let deadlines in`() {
        // Friday 21:00: a sheet that opens Saturday morning, due Monday morning; the weekend kept for the essay.
        val essay = task("essay", "2026-10-30T09:00", effort = 60)
        val sheet = task("sheet", "2026-10-12T09:00", effort = 60).copy(availableFrom = Fixtures.at("2026-10-10T08:00"))
        fun kept(allow: String?) = listOf("2026-10-10", "2026-10-11").mapIndexed { i, day -> applied(Change(ChangeType.DayOnly, taskId = essay.id, date = day, alsoAllow = allow), at = now + i) }
        assertEquals(listOf(sheet.id), plan(listOf(essay, sheet), kept(null)).wontFit.map { it.taskId })
        assertEquals(emptyList(), plan(listOf(essay, sheet), kept(InstructionRules.ALSO_DEADLINES)).wontFit.map { it.taskId })
    }

    @Test
    fun `work its deadline has gone by on a day isn't let in there as a last resort, as that can't save it`() {
        // Friday 21:00, no work tonight, Saturday kept for the essay with deadlines let in: a half hour due tonight goes to Sunday.
        val essay = task("essay", "2026-10-30T09:00", effort = 60)
        val form = task("form", "2026-10-09T23:30", effort = 30)
        val rules = listOf(
            applied(Change(ChangeType.DayLimit, date = "2026-10-09", freeMin = 0)),
            applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10", alsoAllow = InstructionRules.ALSO_DEADLINES), at = now + 1),
        )
        val p = plan(listOf(essay, form), rules)
        assertTrue(p.on(saturday, form).isEmpty(), "${p.buckets.map { b -> b.date to b.chunks.filter { it.taskId == form.id }.size }}")
        assertEquals(30, p.on(LocalDate.parse("2026-10-11"), form).sumOf { it.minutes })
    }

    @Test
    fun `a day whose tasks are all done stays free of work, and overdue work goes to the next day while today's kept for others`() {
        val essay = task("essay", "2026-10-14T09:00", effort = 60).copy(status = Status.Done)
        val maths = task("maths", "2026-10-12T09:00", effort = 240)
        val only = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-10"))
        val p = plan(listOf(essay, maths), listOf(only))
        assertTrue(p.bucket(saturday)!!.chunks.isEmpty())
        assertEquals(240, p.chunksOf(maths.id).sumOf { it.minutes })
        // Friday 17:00, kept for the essay: the overdue work goes to Saturday, not today.
        val friday = Fixtures.at("2026-10-09T17:00")
        val late = task("late", "2026-10-08T09:00", effort = 60)
        val today = applied(Change(ChangeType.DayOnly, taskId = essay.id, date = "2026-10-09"), at = friday)
        val q = plan(listOf(essay, late), listOf(today), at = friday)
        assertTrue(q.on(LocalDate.parse("2026-10-09"), late).isEmpty())
        assertEquals(60, q.on(saturday, late).sumOf { it.minutes })
    }

    @Test
    fun `a day for only some tasks is read with a date, what else it lets in kept where it's one of the two, and said`() {
        fun one(change: String) = parse("""{"understood":true,"question":null,"changes":[$change]}""")
        val reading = one("""{"type":"DayOnly","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null,"alsoAllow":"anki"}""")
        val change = assertIs<InstructionAnswers.Reading.Changes>(reading).changes.single()
        assertEquals(Change(ChangeType.DayOnly, taskId = "teams:essay", date = "2026-10-11", alsoAllow = InstructionRules.ALSO_ANKI), change)
        val tasks = listOf(essay, prep).associateBy { it.id }
        assertEquals("Sun 11 Oct: only “essay”; Anki too", InstructionRules.describe(change, tasks, LONDON))
        assertEquals("Sun 11 Oct: only “essay”", InstructionRules.describe(change.copy(alsoAllow = null), tasks, LONDON))
        assertEquals("Sun 11 Oct: only “essay”; Anki, and work that would miss its deadline, too", InstructionRules.describe(change.copy(alsoAllow = InstructionRules.ALSO_DEADLINES), tasks, LONDON))
        // Anything else it lets in is nothing; empty is nothing.
        val other = one("""{"type":"DayOnly","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null,"alsoAllow":"everything"}""")
        assertNull(assertIs<InstructionAnswers.Reading.Changes>(other).changes.single().alsoAllow)
        // A weekday alone isn't a day for it: left out, and said.
        val weekday = one("""{"type":"DayOnly","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":null,"endDate":null,"weekday":"Sunday","time":null,"endTime":null,"alsoAllow":""}""")
        assertEquals("Claude's reading couldn't be used: a day only for some tasks takes a date", assertIs<InstructionAnswers.Reading.Unclear>(weekday).why)
        // Nor a task that isn't listed.
        val unknown = one("""{"type":"DayOnly","taskId":"teams:nope","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null,"alsoAllow":""}""")
        assertEquals("Claude's reading couldn't be used: it names a task that isn't listed", assertIs<InstructionAnswers.Reading.Unclear>(unknown).why)
    }

    // --- A day's breaks outside, moved, taken off and added (D-116) ---

    @Test
    fun `a day's breaks outside are as its latest change for each leaves them, and taken back, as the settings have them`() {
        val sat = LocalDate.parse("2026-10-10")
        fun days(vararg changes: Change) = InstructionRules.outsideDays(changes.mapIndexed { i, c -> applied(c, at = now + i) }, sat, sat)[sat]
        val moved = Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 780, startMin = 870)
        val off = Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 780)
        // Moved twice: the latest wins. Moved, then taken off: off; taken off, then moved: moved.
        assertEquals(OutsideDay(moved = mapOf(780 to 870)), days(moved.copy(startMin = 840), moved))
        assertEquals(OutsideDay(off = setOf(780)), days(moved, off))
        assertEquals(OutsideDay(moved = mapOf(780 to 870)), days(off, moved))
        // None at all takes what came before; one added after it stands. An added one taken off by its time.
        val added = Change(ChangeType.OutsideAdded, date = "2026-10-10", startMin = 1020)
        assertEquals(OutsideDay(noneAtAll = true, added = listOf(1020)), days(added.copy(startMin = 600), Change(ChangeType.NoOutside, date = "2026-10-10"), added))
        assertEquals(OutsideDay(off = setOf(1020)), days(added, Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 1020)))
        // Another day's, or none: the settings' alone.
        assertNull(days(moved.copy(date = "2026-10-11")))
        assertNull(days())
    }

    @Test
    fun `a day's breaks are its own at their times where it has them and those added, each with who placed it`() {
        val sat = LocalDate.parse("2026-10-10")
        val tue = LocalDate.parse("2026-10-13")
        val his = applied(Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 780, startMin = 870), Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 960)).copy(by = By.Felix)
        val yours = applied(Change(ChangeType.OutsideAdded, date = "2026-10-13", startMin = 1020), at = now + 1)
        val both = listOf(his, yours)
        assertEquals(DayBreaks(mapOf(570 to 570, 780 to 870), 20, reserved = setOf(570, 780, 960)), InstructionRules.dayBreaks(sat, Settings(), withBreaks = true, both))
        // A school day has only those added.
        assertEquals(DayBreaks(mapOf(1020 to 1020), 20), InstructionRules.dayBreaks(tue, Settings(), withBreaks = false, both))
        assertEquals(mapOf("2026-10-10/outside:780" to By.Felix, "2026-10-13/outside+:1020" to By.You), InstructionRules.outsideBy(both, sat, tue))
        // None at all on Saturday after: none of its own, and no one's there.
        val none = both + applied(Change(ChangeType.NoOutside, date = "2026-10-10"), at = now + 2)
        assertEquals(DayBreaks(emptyMap(), 20, reserved = setOf(570, 780, 960)), InstructionRules.dayBreaks(sat, Settings(), withBreaks = true, none))
        assertEquals(mapOf("2026-10-13/outside+:1020" to By.You), InstructionRules.outsideBy(none, sat, tue))
    }

    @Test
    fun `a change to a day's breaks is read on a date, for a break it has, at a time not gone and over no other, else said why`() {
        // Friday 21:00: the weekend's breaks as the settings have them.
        val weekend: (LocalDate) -> DayBreaks = { day -> InstructionRules.dayBreaks(day, Settings(), day.dayOfWeek.value >= 6, emptyList()) }
        fun one(change: String, at: Long = now, breaksOn: (LocalDate) -> DayBreaks = weekend) =
            InstructionAnswers.parse("""{"understood":true,"question":null,"changes":[$change]}""", listOf(essay, prep), listOf(drums), About(), at, LONDON, breaksOn)
        fun kept(change: String) = assertIs<InstructionAnswers.Reading.Changes>(one(change)).changes.single()
        fun why(change: String, at: Long = now, breaksOn: (LocalDate) -> DayBreaks = weekend) =
            assertIs<InstructionAnswers.Reading.Unclear>(one(change, at, breaksOn)).why.removePrefix("Claude's reading couldn't be used: ")
        val moved = kept("""{"type":"OutsideAt","date":"2026-10-10","time":"14:30","outsideTime":"13:00"}""")
        assertEquals(Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 780, startMin = 870), moved)
        val off = kept("""{"type":"NoOutside","date":"2026-10-10","outsideTime":"16:00"}""")
        assertEquals(Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 960), off)
        val none = kept("""{"type":"NoOutside","date":"2026-10-11","outsideTime":""}""")
        assertEquals(Change(ChangeType.NoOutside, date = "2026-10-11"), none)
        val added = kept("""{"type":"OutsideAdded","date":"2026-10-13","time":"17:00"}""")
        assertEquals(Change(ChangeType.OutsideAdded, date = "2026-10-13", startMin = 1020), added)
        // Each said as Your changes and Felix list it.
        assertEquals(
            listOf("Sat 10 Oct: the 13:00 break outside at 14:30", "Sat 10 Oct: no 16:00 break outside", "Sun 11 Oct: no breaks outside", "Tue 13 Oct: a break outside at 17:00"),
            listOf(moved, off, none, added).map { InstructionRules.describe(it, emptyMap(), LONDON) },
        )
        // Left out, and why: a weekday alone, a break the day hasn't (a school day has none of its own), one over another, a time gone.
        assertEquals("a day's breaks outside are changed on a date, never a weekday", why("""{"type":"NoOutside","weekday":"Saturday","outsideTime":"13:00"}"""))
        assertEquals("there's no 12:00 break outside that day", why("""{"type":"OutsideAt","date":"2026-10-10","time":"14:30","outsideTime":"12:00"}"""))
        assertEquals("there's no 13:00 break outside that day", why("""{"type":"NoOutside","date":"2026-10-13","outsideTime":"13:00"}"""))
        assertEquals("it would be over another break outside", why("""{"type":"OutsideAt","date":"2026-10-10","time":"15:50","outsideTime":"13:00"}"""))
        assertEquals("it would be over another break outside", why("""{"type":"OutsideAdded","date":"2026-10-10","time":"09:40"}"""))
        assertEquals("that time's gone", why("""{"type":"OutsideAdded","date":"2026-10-09","time":"20:00"}"""))
        // One begun today is kept as it is (D-113).
        val begun: (LocalDate) -> DayBreaks = { weekend(it).copy(begun = setOf(780)) }
        assertEquals("that break outside has begun, and is kept as it is", why("""{"type":"OutsideAt","date":"2026-10-10","time":"15:00","outsideTime":"13:00"}""", Fixtures.at("2026-10-10T13:05"), begun))
        // One added at the time of the day's own, moved away: that one's to be moved back.
        val away: (LocalDate) -> DayBreaks = { day -> InstructionRules.dayBreaks(day, Settings(), true, listOf(applied(moved))) }
        assertEquals("a break outside of that day's is named by that time: move or put back that one instead", why("""{"type":"OutsideAdded","date":"2026-10-10","time":"13:00"}""", breaksOn = away))
    }

    @Test
    fun `a break added is moved by its own time, one taken off put back by a move, and none added at a time a day's own is named by`() {
        val sat = LocalDate.parse("2026-10-10")
        val tue = LocalDate.parse("2026-10-13")
        // Added on Tuesday at 17:00, then moved by Felix to 18:00: still the 17:00 break, at 18:00, placed by him.
        val added = applied(Change(ChangeType.OutsideAdded, date = "2026-10-13", startMin = 1020))
        val moved = applied(Change(ChangeType.OutsideAt, date = "2026-10-13", outsideMin = 1020, startMin = 1080), at = now + 1).copy(by = By.Felix)
        assertEquals(DayBreaks(mapOf(1020 to 1080), 20), InstructionRules.dayBreaks(tue, Settings(), withBreaks = false, listOf(added, moved)))
        assertEquals(mapOf("2026-10-13/outside+:1020" to By.Felix), InstructionRules.outsideBy(listOf(added, moved), tue, tue))
        // Saturday's 13:00 taken off: put back by a move to its time, laid again; no break added at 13:00.
        val off = applied(Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 780))
        val weekend: (LocalDate) -> DayBreaks = { day -> InstructionRules.dayBreaks(day, Settings(), day.dayOfWeek.value >= 6, listOf(off)) }
        fun one(change: String) = InstructionAnswers.parse("""{"understood":true,"question":null,"changes":[$change]}""", listOf(essay, prep), listOf(drums), About(), now, LONDON, weekend)
        val back = assertIs<InstructionAnswers.Reading.Changes>(one("""{"type":"OutsideAt","date":"2026-10-10","time":"13:00","outsideTime":"13:00"}""")).changes.single()
        assertEquals(Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 780, startMin = 780), back)
        assertEquals(OutsideDay(moved = mapOf(780 to 780)), InstructionRules.outsideDays(listOf(off, applied(back, at = now + 1)), sat, sat)[sat])
        val why = assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"OutsideAdded","date":"2026-10-10","time":"13:00"}""")).why
        assertEquals("Claude's reading couldn't be used: a break outside of that day's is named by that time: move or put back that one instead", why)
    }

    @Test
    fun `an answer's changes to a day's breaks are checked in turn, so one can use the time another frees, and none runs past midnight`() {
        val weekend: (LocalDate) -> DayBreaks = { day -> InstructionRules.dayBreaks(day, Settings(), day.dayOfWeek.value >= 6, emptyList()) }
        fun read(vararg changes: String) =
            InstructionAnswers.parse("""{"understood":true,"question":null,"changes":[${changes.joinToString(",")}]}""", listOf(essay, prep), listOf(drums), About(), now, LONDON, weekend)
        // Saturday's 13:00 taken off, then its 16:00 moved there: both kept, as the day then has them.
        val both = assertIs<InstructionAnswers.Reading.Changes>(read("""{"type":"NoOutside","date":"2026-10-10","outsideTime":"13:00"}""", """{"type":"OutsideAt","date":"2026-10-10","time":"13:00","outsideTime":"16:00"}"""))
        assertEquals(listOf(Change(ChangeType.NoOutside, date = "2026-10-10", outsideMin = 780), Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 960, startMin = 780)), both.changes)
        // The other way round, the move's over the break still there: not.
        assertIs<InstructionAnswers.Reading.Unclear>(read("""{"type":"OutsideAt","date":"2026-10-10","time":"13:00","outsideTime":"16:00"}""", """{"type":"NoOutside","date":"2026-10-10","outsideTime":"13:00"}"""))
        // A break at 23:50 would run past midnight: refused, said.
        assertEquals("Claude's reading couldn't be used: a break outside is over by midnight, on its day", assertIs<InstructionAnswers.Reading.Unclear>(read("""{"type":"OutsideAdded","date":"2026-10-10","time":"23:50"}""")).why)
        assertEquals(DayBreaks(mapOf(570 to 570), 20).with(Change(ChangeType.OutsideAdded, date = "2026-10-10", startMin = 1020)), DayBreaks(mapOf(570 to 570, 1020 to 1020), 20))
        // A day made off school earlier in the answer has its breaks for the changes after it.
        val school: (LocalDate) -> DayBreaks = { day -> InstructionRules.dayBreaks(day, Settings(), false, emptyList()).copy(offSchool = InstructionRules.dayBreaks(day, Settings(), true, emptyList())) }
        val offSchool = InstructionAnswers.parse(
            """{"understood":true,"question":null,"changes":[{"type":"NoSchool","date":"2026-10-13","endDate":"2026-10-13"},{"type":"OutsideAt","date":"2026-10-13","time":"14:00","outsideTime":"13:00"}]}""",
            listOf(essay, prep), listOf(drums), About(), now, LONDON, school,
        )
        assertEquals(listOf(ChangeType.NoSchool, ChangeType.OutsideAt), assertIs<InstructionAnswers.Reading.Changes>(offSchool).changes.map { it.type })
        // A break begun stays whatever's taken off (D-113), so one added over it in the same answer is still refused.
        val begun: (LocalDate) -> DayBreaks = { day -> weekend(day).copy(begun = setOf(780)) }
        val over = InstructionAnswers.parse(
            """{"understood":true,"question":null,"changes":[{"type":"NoOutside","date":"2026-10-10","outsideTime":""},{"type":"OutsideAdded","date":"2026-10-10","time":"13:10"}]}""",
            listOf(essay, prep), listOf(drums), About(), Fixtures.at("2026-10-10T13:05"), LONDON, begun,
        )
        assertEquals("Claude's reading couldn't be used: it would be over another break outside", assertIs<InstructionAnswers.Reading.Unclear>(over).why)
    }

    @Test
    fun `a time on the day the clocks go back is that day's clock time, as the planner lays it`() {
        // Sunday 25 October 2026, 09:00 in London, the clocks gone back at 02:00: 09:30 is still to come.
        val at = Fixtures.at("2026-10-25T09:00")
        val reading = InstructionAnswers.parse("""{"understood":true,"question":null,"changes":[{"type":"OutsideAdded","date":"2026-10-25","time":"09:30"}]}""", emptyList(), emptyList(), About(), at, LONDON)
        assertEquals(Change(ChangeType.OutsideAdded, date = "2026-10-25", startMin = 570), assertIs<InstructionAnswers.Reading.Changes>(reading).changes.single())
    }

    @Test
    fun `the instruction reader is told the week's breaks outside, each by its own time and where it is once moved`() {
        val moved = listOf(applied(Change(ChangeType.OutsideAt, date = "2026-10-10", outsideMin = 780, startMin = 870)))
        val text = InstructionPrompts.describe(Instruction("i", "no midday break on Saturday", at = now), listOf(essay), emptyList(), now, LONDON, breaksOn = { day -> InstructionRules.dayBreaks(day, Settings(), day.dayOfWeek.value >= 6, moved) })
        assertTrue(text.endsWith("Breaks outside in the next week (each by its own time):\n- Saturday 10 October 2026: 09:30, 13:00 (moved to 14:30), 16:00\n- Sunday 11 October 2026: 09:30, 13:00, 16:00"), text)
        // None in the week: nothing said.
        assertFalse("Breaks outside" in InstructionPrompts.describe(Instruction("i", "x", at = now), emptyList(), emptyList(), now, LONDON))
    }

    @Test
    fun `a day or a time after a task's real deadline is late`() {
        // The essay's due Monday 12 Oct, 09:00.
        assertTrue(InstructionRules.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-13"), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertFalse(InstructionRules.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-12"), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertTrue(InstructionRules.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-12", startMin = 600, time = Fixtures.at("2026-10-12T10:00")), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertFalse(InstructionRules.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-11", startMin = 1170, time = Fixtures.at("2026-10-11T19:30")), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        // Undated, or a plan's date: never late.
        assertFalse(InstructionRules.latePin(Change(ChangeType.OnDay, taskId = "teams:x", date = "2027-01-01"), task("x", null), LONDON, Fixtures.at("2026-10-10T17:00")))
        // Overdue already: today or later is as soon as it can be, not late.
        assertFalse(InstructionRules.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-16"), essay, LONDON, Fixtures.at("2026-10-16T18:30")))
        assertFalse(InstructionRules.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-16", startMin = 1080, time = Fixtures.at("2026-10-16T18:00")), essay, LONDON, Fixtures.at("2026-10-16T17:30")))
    }

    @Test
    fun `the school calendar merges stretches that overlap or run on, and knows its days`() {
        val calendar = SchoolCalendar()
            .with(NoSchool("2026-10-26", "2026-10-28", "Half term"))
            .with(NoSchool("2026-10-28", "2026-10-30"))
            .with(NoSchool("2026-12-21", "2027-01-01", "Christmas"))
            // Runs on from half term: the same stretch.
            .with(NoSchool("2026-10-31", "2026-11-01"))
            .with(HalfDay("2026-10-23", 750))
            .with(HalfDay("2026-10-23", 780))
        assertEquals(listOf(NoSchool("2026-10-26", "2026-11-01", "Half term"), NoSchool("2026-12-21", "2027-01-01", "Christmas")), calendar.noSchool)
        assertEquals(listOf(HalfDay("2026-10-23", 780)), calendar.halfDays)
        assertFalse(calendar.isSchoolDay(LocalDate.parse("2026-10-27")))
        assertTrue(calendar.isSchoolDay(LocalDate.parse("2026-11-02")))
        assertFalse(calendar.isSchoolDay(LocalDate.parse("2026-10-24")))
        assertEquals(780, calendar.halfDay(LocalDate.parse("2026-10-23"))?.endsAtMin)
        // A stretch that joins two others makes them one.
        val joined = SchoolCalendar().with(NoSchool("2026-10-01", "2026-10-02")).with(NoSchool("2026-10-05", "2026-10-06")).with(NoSchool("2026-10-03", "2026-10-04"))
        assertEquals(listOf(NoSchool("2026-10-01", "2026-10-06")), joined.noSchool)
        assertEquals(listOf(NoSchool("2026-12-21", "2027-01-01", "Christmas")), calendar.without(calendar.noSchool.first()).noSchool)
    }

    @Test
    fun `minutes fewer than a task's steps aren't taken from Claude`() {
        val stepped = essay.copy(subSteps = listOf(SubStep("a", 20), SubStep("b", 20), SubStep("c", 20)))
        val reading = InstructionAnswers.parse(
            """{"understood":true,"question":null,"changes":[{"type":"Minutes","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":2,"date":null,"weekday":null,"time":null,"endTime":null}]}""",
            listOf(stepped, prep), listOf(drums), About(), now, LONDON,
        )
        assertIs<InstructionAnswers.Reading.Unclear>(reading)
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
    fun `a reading with tasks waiting on each other in a circle isn't taken`() {
        val circle = parse(
            """{"understood":true,"question":null,"changes":[
              {"type":"After","taskId":"teams:prep","afterTaskId":"teams:essay","eventKey":null,"eventTime":null,"minutes":null,"date":null,"weekday":null,"time":null,"endTime":null},
              {"type":"After","taskId":"teams:essay","afterTaskId":"teams:prep","eventKey":null,"eventTime":null,"minutes":null,"date":null,"weekday":null,"time":null,"endTime":null}
            ]}""",
        )
        assertIs<InstructionAnswers.Reading.Unclear>(circle)
    }

    @Test
    fun `what Claude found unclear is said back`() {
        val reading = parse("""{"understood":false,"question":"Which Saturday do you mean?","changes":[]}""")
        assertEquals("Which Saturday do you mean?", assertIs<InstructionAnswers.Reading.Unclear>(reading).why)
        assertIs<InstructionAnswers.Reading.Unclear>(parse("not json"))
    }

    @Test
    fun `Claude is shown each task's deadline in force, a plan's gone by giving way to the real one`() {
        // Claude read a test on Thursday 8 Oct before Teams' Friday 23 Oct: gone by, Teams' date holds.
        val test = task("test", "2026-10-08T08:30").copy(sourceValues = SourceValues(Kind.Homework, Fixtures.at("2026-10-23T23:59"), null))
        val instruction = Instruction("i", "two days later", About(taskId = test.id, taskTitle = test.title), now)
        val text = InstructionPrompts.describe(instruction, listOf(test), emptyList(), now, LONDON)
        assertTrue("  Due: Fri 23 Oct 2026 23:59\n" in text, text)
        assertTrue("- teams:test | test | Teams | Fri 23 Oct 2026 23:59\n" in text, text)
        // Still to come, it's shown as the plan it is.
        val later = test.copy(dueAt = Fixtures.at("2026-10-14T08:30"))
        assertTrue("  Due: Wed 14 Oct 2026 08:30 (a plan)" in InstructionPrompts.describe(instruction, listOf(later), emptyList(), now, LONDON))
    }

    @Test
    fun `a reply to Felix's question is read shown the question, and Claude says whether it answers it`() {
        val asked = prep.copy(enrichment = Enrichment(inputHash = Enrichments.inputHash(prep), by = "test", at = now, question = "Is it questions 1 to 6, or all of them?"))
        val reply = Instruction("i", "is the essay ticked in teams?", About(taskId = asked.id, taskTitle = asked.title), now, answer = true)
        val text = InstructionPrompts.describe(reply, listOf(essay, asked), emptyList(), now, LONDON)
        assertTrue("It's their reply to the question asked about it: \"Is it questions 1 to 6, or all of them?\"\n" in text, text)
        // The task changed since it was read: still the question its reading asked; given his words as he put it, those.
        val changed = asked.copy(detail = "Questions 1 to 6, then 7 to 12 for extension.")
        assertTrue("about it: \"Is it questions 1 to 6, or all of them?\"\n" in InstructionPrompts.describe(reply, listOf(essay, changed), emptyList(), now, LONDON))
        assertTrue("about it: \"is it 1 to 6 or all of them?\"\n" in InstructionPrompts.describe(reply, listOf(essay, changed), emptyList(), now, LONDON, question = "is it 1 to 6 or all of them?"))
        // One that isn't a reply is told of none.
        assertTrue("their reply" !in InstructionPrompts.describe(reply.copy(answer = false), listOf(essay, asked), emptyList(), now, LONDON))
        // Read back: no answer, with nothing to change; an answer, with a change; one read without the field, an answer.
        assertEquals(InstructionAnswers.Reading.Unclear("Claude couldn't turn it into a change the app can make", answers = false), parse("""{"understood":false,"question":null,"changes":[],"answersQuestion":false}"""))
        val due = """{"type":"DueBy","taskId":"teams:essay","afterTaskId":"","eventKey":"","eventTime":"","minutes":-1,"date":"2026-10-16","endDate":"","weekday":"","time":"","endTime":"","title":"","steps":[]}"""
        assertTrue(assertIs<InstructionAnswers.Reading.Changes>(parse("""{"understood":true,"question":null,"changes":[$due],"answersQuestion":true}""")).answers)
        assertTrue(parse("""{"understood":false,"question":"Which task?","changes":[]}""").answers)
        assertTrue("Set answersQuestion to whether the reply answers that question" in InstructionPrompts.system())
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

    @Test
    fun `a time moves only its task's next piece's slot, a day elsewhere all of today's, a start those before it`() {
        val today = LocalDate.parse("2026-10-12")
        fun chunk(step: Int) = Chunk(
            taskId = "teams:t", source = Source.Teams, kind = Kind.Homework, title = "t", step = "s$step", minutes = 30, dueAt = Fixtures.at("2026-10-20T09:00"),
            deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false, part = step + 1, parts = 3, taskMinutes = 90, why = Why.Spread, stepIndex = step,
        )
        val pieces = (0..2).map(::chunk)
        val slots = pieces.mapIndexed { i, c -> Slot(SlotKind.Work, c, Fixtures.at("2026-10-12T17:00") + i * 35 * 60_000L, Fixtures.at("2026-10-12T17:30") + i * 35 * 60_000L, WindowKind.Evening, pinned = false) }
        val plan = Plan(Fixtures.at("2026-10-12T16:00"), today, listOf(DayBucket(today, 300, pieces, slots)), emptyList())
        assertEquals(listOf(slots[0]), InstructionRules.movedSlots(listOf(Change(ChangeType.AtTime, taskId = "teams:t", date = "2026-10-12", time = Fixtures.at("2026-10-12T20:00"))), plan))
        assertEquals(slots, InstructionRules.movedSlots(listOf(Change(ChangeType.OnDay, taskId = "teams:t", date = "2026-10-14")), plan))
        assertEquals(emptyList(), InstructionRules.movedSlots(listOf(Change(ChangeType.OnDay, taskId = "teams:t", date = "2026-10-12")), plan))
        assertEquals(slots.take(2), InstructionRules.movedSlots(listOf(Change(ChangeType.StartFrom, taskId = "teams:t", time = Fixtures.at("2026-10-12T18:00"))), plan))
    }

    @Test
    fun `steps for a task of your own are read, said and made on it, its ticks kept by name`() {
        val own = OwnTaskRules.create(OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 40))), "e", Fixtures.at("2026-10-12T17:00"))
        val ticked = own.copy(subSteps = own.subSteps.map { if (it.title == "Plan") it.copy(done = true) else it })
        val given = InstructionAnswers.Given(
            "Steps", taskId = own.id,
            steps = listOf(
                InstructionAnswers.GivenStep("Plan", 20),
                InstructionAnswers.GivenStep("Draft", 30, dueDate = "2026-10-14"),
                InstructionAnswers.GivenStep("Polish", 10, where = "home"),
            ),
        )
        val change = InstructionAnswers.change(given, mapOf(own.id to ticked), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON)!!
        assertEquals(listOf("Plan", "Draft", "Polish"), change.steps!!.map { it.title })
        assertEquals("“Essay” in steps: Plan (20 min), Draft (30 min), Polish (10 min)", InstructionRules.describe(change, mapOf(own.id to ticked), Fixtures.LONDON))
        // A step that doesn't need the ones before it says so (D-109).
        val free = change.copy(steps = change.steps!!.mapIndexed { i, s -> if (i == 1) s.copy(anyOrder = true) else s })
        assertEquals("“Essay” in steps: Plan (20 min), Draft (30 min, in any order), Polish (10 min)", InstructionRules.describe(free, mapOf(own.id to ticked), Fixtures.LONDON))
        val draft = OwnTaskRules.draftWith(ticked, change)!!
        val made = OwnTaskRules.edited(ticked, draft)
        assertEquals(listOf("Plan" to true, "Draft" to false, "Polish" to false), made.subSteps.map { it.title to it.done })
        assertEquals(60, made.subSteps.sumOf { it.minutes })
        // A source's task's too, but not one made from Anki's cards.
        val teams = ticked.copy(id = "teams:x", source = Source.Teams)
        assertTrue(InstructionAnswers.change(given.copy(taskId = teams.id), mapOf(teams.id to teams), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON) != null)
        val deck = ticked.copy(id = "anki:deck", source = Source.Anki, derived = true)
        assertNull(InstructionAnswers.change(given.copy(taskId = deck.id), mapOf(deck.id to deck), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertEquals("an Anki task's steps and name come from its cards", InstructionAnswers.problem(given.copy(taskId = deck.id), mapOf(deck.id to deck), emptyMap()))
        // Its steps come to its 60 minutes; to 70 only with a Minutes change saying so.
        val longer = change.copy(steps = change.steps!!.map { if (it.title == "Polish") it.copy(minutes = 20) else it })
        val tasks = mapOf(own.id to ticked)
        assertNull(InstructionAnswers.stepsProblem(listOf(change), tasks))
        assertEquals("the steps for “Essay” add up to 70 minutes, not its 60, and nothing says its total changes", InstructionAnswers.stepsProblem(listOf(longer), tasks))
        assertNull(InstructionAnswers.stepsProblem(listOf(longer, Change(ChangeType.Minutes, taskId = own.id, minutes = 70)), tasks))
        // A step's date years ahead, or after the task's own: not taken.
        val dated = ticked.copy(dueAt = Fixtures.at("2026-10-15T17:00"))
        fun withDue(due: String) = given.copy(steps = given.steps.map { if (it.title == "Draft") it.copy(dueDate = due) else it })
        assertNull(InstructionAnswers.change(withDue("2029-01-01"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertNull(InstructionAnswers.change(withDue("2026-10-16"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertTrue(InstructionAnswers.change(withDue("2026-10-15"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON) != null)
    }

    @Test
    fun `steps for a source's task are laid over its own, kept through a sync, their ticks by name, and taken back`() {
        val at = Fixtures.at("2026-10-12T17:00")
        val reading = Enrichment("h", "claude", at, effortMin = 45, subSteps = listOf(SubStep("Find the clip", 15), SubStep("Upload", 15), SubStep("Link it", 15)))
        val email = task("vid", "2026-10-17T23:59", effort = 45, source = Source.Gmail).let { it.copy(enrichment = reading.copy(inputHash = Enrichments.inputHash(it))).enriched() }
            .let { t -> t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true, byHand = true) else s }) }
        assertEquals(3, email.subSteps.size)
        val one = Change(ChangeType.Steps, taskId = email.id, steps = listOf(OwnStep("Find the clip", 10), OwnStep("Put it on the site", 20, where = "home")))
        val laid = email.withMine(InstructionRules.taskOverrides(listOf(applied(one)))[email.id]!!)
        assertEquals(listOf("Find the clip" to true, "Put it on the site" to false), laid.subSteps.map { it.title to it.done })
        assertEquals("home", laid.subSteps[1].where)
        // A sync's merge, and the enrichment read again, keep them.
        val synced = Merge.apply(listOf(laid), Source.Gmail, listOf(Fetched("vid", "vid", Kind.Admin, dueAt = Fixtures.at("2026-10-17T23:59"))), at + 60_000L).tasks.single()
        assertEquals(listOf("Find the clip", "Put it on the site"), synced.subSteps.map { it.title })
        assertEquals(listOf("Find the clip", "Put it on the site"), synced.withEnrichment(reading.copy(inputHash = Enrichments.inputHash(synced), at = at + 120_000L)).subSteps.map { it.title })
        // Taken back: the enrichment's again, the tick on the first kept by its name.
        val back = synced.withMine(Mine.NONE)
        assertEquals(listOf("Find the clip" to true, "Upload" to false, "Link it" to false), back.subSteps.map { it.title to it.done })
        // Never over a deck's steps, which are its cards.
        val deck = task("deck", null, source = Source.Anki).copy(derived = true, subSteps = listOf(SubStep("1.1", 10)))
        assertEquals(listOf("1.1"), deck.withMine(mineOf(steps = listOf(OwnStep("All", 10)))).subSteps.map { it.title })
    }

    @Test
    fun `a name for a source's task is laid over its own, which a sync keeps beneath it, and isn't new content to read`() {
        val at = Fixtures.at("2026-10-12T17:00")
        val vectors = task("Vectors Practice", "2026-10-16T23:59").let { it.copy(sourceValues = it.sourceValues!!.copy(title = it.title)) }
        val hash = Enrichments.inputHash(vectors)
        val rename = Change(ChangeType.Rename, taskId = vectors.id, title = "Vectors practice")
        assertEquals("“Vectors Practice” renamed “Vectors practice”", InstructionRules.describe(rename, mapOf(vectors.id to vectors), LONDON))
        val renamed = vectors.withMine(InstructionRules.taskOverrides(listOf(applied(rename)))[vectors.id]!!)
        assertEquals("Vectors practice", renamed.title)
        // In use, it's said against its source's name.
        assertEquals("“Vectors Practice” renamed “Vectors practice”", InstructionRules.describe(rename, mapOf(vectors.id to renamed), LONDON))
        assertEquals(hash, Enrichments.inputHash(renamed))
        val synced = Merge.apply(listOf(renamed), Source.Teams, listOf(Fetched(vectors.sourceId, "Vectors Practice", Kind.Homework, dueAt = vectors.dueAt)), at).tasks.single()
        assertEquals("Vectors practice", synced.title)
        assertEquals("Vectors Practice", synced.withMine(Mine.NONE).title)
        // One kept from before names were kept has its own taken as its source's when it's first renamed.
        val old = task("Essay", null).copy(sourceValues = SourceValues(Kind.Homework, null, null))
        assertEquals("Essay", old.withMine(mineOf(title = "Big essay")).withMine(Mine.NONE).title)
        // Read from Claude: a name it gives; none, or for a task not listed, isn't taken.
        val given = InstructionAnswers.Given("Rename", taskId = vectors.id, title = " Vectors practice ")
        assertEquals(rename, InstructionAnswers.change(given, mapOf(vectors.id to vectors), emptyMap(), at, LONDON))
        assertNull(InstructionAnswers.change(given.copy(title = " "), mapOf(vectors.id to vectors), emptyMap(), at, LONDON))
        assertEquals("the new name is missing", InstructionAnswers.problem(given.copy(title = ""), mapOf(vectors.id to vectors), emptyMap()))
    }

    @Test
    fun `a name for a task of your own is made on it, and put back by its card's Undo`() {
        val own = OwnTaskRules.create(OwnTask(title = "Essay", minutes = 60), "e", now)
        val rename = Change(ChangeType.Rename, taskId = own.id, title = "Philosophy essay")
        assertEquals(listOf(rename), OwnTaskRules.madeOn(listOf(own), listOf(rename)))
        val renamed = OwnTaskRules.changed(own, rename)
        assertEquals("Philosophy essay", renamed.title)
        assertEquals("Philosophy essay", renamed.sourceValues?.title)
        val back = OwnTaskRules.restored(OwnTaskRules.draftOf(renamed), OwnTaskRules.draftOf(own), listOf(rename))
        assertEquals("Essay", back.title)
    }

    @Test
    fun `a day off taken back leaves the school calendar as it was, unless it's been deleted since`() {
        val before = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-26", "2026-10-27", "Half term")))
        val after = before.with(NoSchool("2026-10-28", "2026-10-30")).with(HalfDay("2026-10-23", 750))
        assertEquals(before, after.undone(before, after))
        // A day off of yours added since stays; one it added that you've deleted since: not taken back.
        val more = after.with(NoSchool("2026-12-21", "2026-12-21"))
        assertEquals(before.with(NoSchool("2026-12-21", "2026-12-21")), more.undone(before, after))
        assertNull(after.without(after.noSchool.first()).undone(before, after))
    }
}
