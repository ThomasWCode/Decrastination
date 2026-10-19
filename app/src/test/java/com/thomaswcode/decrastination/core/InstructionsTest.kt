package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
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
                hardBusy = Instructions.busy(applied, LONDON, today, today.plusDays(90)),
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
    fun `a total Felix works out at your pace is kept as paced through a sync, and a later one of yours isn't`() {
        val essay = task("essay", "2026-10-12T09:00")
        val paced = applied(Change(ChangeType.Minutes, taskId = essay.id, minutes = 90, pacedFrom = 60, pacedHash = Enrichments.inputHash(essay)))
        val laid = essay.withOverrides(Instructions.taskOverrides(listOf(paced)).getValue(essay.id))
        assertEquals(Triple(90, true, true), Triple(laid.effortMin, laid.pacedMinutes, laid.pacedTeaches))
        assertTrue("“essay”: takes 1 h 30 min in all at your pace" in Instructions.describe(paced.changes.single(), mapOf(essay.id to essay), LONDON))
        // A sync keeps it, as it keeps your other changes.
        fun synced(estimate: Int, detail: String = "") =
            Merge.apply(listOf(laid), Source.Teams, listOf(Fetched("essay", "essay", Kind.Homework, detail = detail, dueAt = Fixtures.at("2026-10-12T09:00"), sourceEffortMin = estimate)), now).tasks.single()
        assertTrue(synced(60).pacedTeaches)
        // Estimated anew, its content the same (#50's review: as a re-read for newer prompts is): still the pace.
        assertEquals(Triple(90, true, true), synced(120).let { Triple(it.effortMin, it.pacedMinutes, it.pacedTeaches) })
        // Its content changed (new instructions): still Felix's total, but the work may have changed, so not the pace.
        assertEquals(Triple(90, true, false), synced(60, detail = "Also questions 9 to 12.").let { Triple(it.effortMin, it.pacedMinutes, it.pacedTeaches) })
        // Your own reckoning after it: yours, not paced; and taken back, neither.
        val yours = applied(Change(ChangeType.Minutes, taskId = essay.id, minutes = 120), at = now + 1)
        val mine = essay.withOverrides(Instructions.taskOverrides(listOf(paced, yours)).getValue(essay.id))
        assertEquals(120 to false, mine.effortMin to mine.pacedMinutes)
        assertFalse(laid.withOverrides(TaskOverrides.NONE).pacedMinutes)
        // Never on a task of your own, whose estimate is yours.
        assertFalse(task("mine", "2026-10-12T09:00", source = Source.Own).withOverrides(TaskOverrides(minutes = 90, minutesPacedFrom = 60)).pacedMinutes)
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
    fun `a start of yours places the work from then on, seen coming, never earlier`() {
        // Friday; can't start before Sunday; due Tuesday. Planned Sunday on, and in the plan now.
        val essay = task("essay", "2026-10-13T23:59", effort = 90).withOverrides(TaskOverrides(from = Fixtures.at("2026-10-11T00:00")))
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
        val later = task("later", "2026-10-12T23:59", effort = 90).withOverrides(TaskOverrides(from = from))
        val p = plan(listOf(later), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(p.buckets.first { it.date == LocalDate.parse("2026-10-09") }.chunks.none { it.taskId == later.id })
        assertEquals(90, p.buckets.sumOf { b -> b.chunks.filter { it.taskId == later.id }.sumOf { it.minutes } })
        // Due by Friday's end: what can't fit after 21:30 is behind, not counted as fitting.
        val tight = task("tight", "2026-10-10T23:59", effort = 90).withOverrides(TaskOverrides(from = from))
        val q = plan(listOf(tight), at = Fixtures.at("2026-10-09T17:00"))
        assertTrue(q.buckets.flatMap { it.chunks }.filter { it.taskId == tight.id }.any { it.behind })
    }

    @Test
    fun `tasks starting late the same day share that day's time after the start`() {
        // Friday's hours end at 22:00: an hour after 21:00, for two 45-minute tasks that can't start before it.
        val from = Fixtures.at("2026-10-09T21:00")
        val a = task("a", "2026-10-12T23:59", effort = 45).withOverrides(TaskOverrides(from = from))
        val b = task("b", "2026-10-12T23:59", effort = 45).withOverrides(TaskOverrides(from = from))
        val friday = plan(listOf(a, b), at = Fixtures.at("2026-10-09T17:00")).buckets.first { it.date == LocalDate.parse("2026-10-09") }
        assertTrue(friday.plannedMin <= 60)
    }

    @Test
    fun `a deck task made for an assignment follows the assignment's instructions`() {
        val homework = task("vocab", "2026-10-13T09:00")
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 1.2", kind = Kind.Homework,
            dueAt = Fixtures.at("2026-10-13T09:00"), sourceEffortMin = 20, derived = true, firstSeenAt = now, lastSeenAt = now,
            extra = mapOf(com.thomaswcode.decrastination.sources.anki.AnkiRules.EXTRA_FOR to homework.id),
        )
        // Not a task: its deck goes with it.
        val hidden = plan(listOf(homework.withOverrides(TaskOverrides(notATask = true)), deck)).ordered.map { it.taskId }
        assertTrue(deck.id !in hidden)
        // Can't start before Sunday: nor can its deck.
        val sunday = Fixtures.at("2026-10-11T00:00")
        val started = plan(listOf(homework.withOverrides(TaskOverrides(from = sunday)), deck))
        val deckDays = started.buckets.filter { b -> b.chunks.any { it.taskId == deck.id } }.map { it.date }
        assertTrue(deckDays.isNotEmpty() && deckDays.all { it >= LocalDate.parse("2026-10-11") })
        // Left as it is, the deck's planned.
        assertTrue(deck.id in plan(listOf(homework, deck)).ordered.map { it.taskId })
    }

    @Test
    fun `earlier-starting work that can't fit before a later start shares its time`() {
        // Friday's hours end at 22:00. 90 minutes from 20:00 and 60 from 21:00 don't both fit.
        val a = task("a", "2026-10-12T23:59", effort = 90).copy(subSteps = listOf(SubStep("A", 90))).withOverrides(TaskOverrides(from = Fixtures.at("2026-10-09T20:00")))
        val b = task("b", "2026-10-12T23:59", effort = 60).copy(subSteps = listOf(SubStep("B", 60))).withOverrides(TaskOverrides(from = Fixtures.at("2026-10-09T21:00")))
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
        val info = task("info", null).withOverrides(TaskOverrides(notATask = true))
        val essay = task("essay", "2026-10-12T09:00").withOverrides(TaskOverrides(after = info.id))
        assertFalse(Instructions.waiting(essay, listOf(info, essay).associateBy { it.id }))
        assertTrue(essay.id in plan(listOf(info, essay)).ordered.map { it.taskId })
    }

    @Test
    fun `a deck task is due with its assignment's due date as it is now`() {
        val homework = task("vocab", "2026-10-20T09:00").withOverrides(TaskOverrides(dueAt = Fixtures.at("2026-10-12T09:00")))
        val deck = TaskItem(
            id = "anki:deck:1", source = Source.Anki, sourceId = "deck:1", title = "Learn Anki deck 1.2", kind = Kind.Homework,
            dueAt = Fixtures.at("2026-10-20T09:00"), sourceEffortMin = 20, derived = true, firstSeenAt = now, lastSeenAt = now,
            extra = mapOf(com.thomaswcode.decrastination.sources.anki.AnkiRules.EXTRA_FOR to homework.id),
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
        val input = Planner.Input(listOf(essay), at, LONDON, Settings(), dayCaps = Instructions.dayCaps(listOf(limit), today, today.plusDays(30)))
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
        val a = task("a", "2026-10-12T09:00").copy(userAfter = "teams:b")
        val b = task("b", "2026-10-13T09:00").copy(userAfter = "teams:a")
        val byId = listOf(a, b).associateBy { it.id }
        assertFalse(Instructions.waiting(a, byId))
        assertFalse(Instructions.waiting(b, byId))
        assertEquals(setOf(a.id, b.id), plan(listOf(a, b)).ordered.map { it.taskId }.toSet())
        // One waiting on a task stuck in a circle still waits for it.
        val c = task("c", "2026-10-14T09:00").copy(userAfter = "teams:a")
        assertTrue(Instructions.waiting(c, byId + (c.id to c)))
        // A new one closing a circle with one applied is caught.
        val applied = listOf(applied(Change(ChangeType.After, taskId = "teams:a", afterTaskId = "teams:b")))
        assertTrue(Instructions.makesCircle(listOf(Change(ChangeType.After, taskId = "teams:b", afterTaskId = "teams:a")), applied))
        assertFalse(Instructions.makesCircle(listOf(Change(ChangeType.After, taskId = "teams:c", afterTaskId = "teams:a")), applied))
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
    fun `changes read in words`() {
        val essay = task("Essay", "2026-10-12T09:00")
        val tasks = mapOf(essay.id to essay)
        assertEquals("“Essay”: due Fri 16 Oct 23:59 (now Mon 12 Oct 09:00)", Instructions.describe(Change(ChangeType.DueBy, taskId = essay.id, time = Fixtures.at("2026-10-16T23:59")), tasks, LONDON))
        // Once it's the date in use, against the source's.
        val moved = essay.withOverrides(TaskOverrides(dueAt = Fixtures.at("2026-10-16T23:59")))
        assertEquals("“Essay”: due Fri 16 Oct 23:59 (its source says Mon 12 Oct 09:00)", Instructions.describe(Change(ChangeType.DueBy, taskId = essay.id, time = Fixtures.at("2026-10-16T23:59")), mapOf(essay.id to moved), LONDON))
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
        assertEquals("No school from Mon 26 Oct to Fri 30 Oct", Instructions.describe(changes[0], tasks, LONDON))
        assertEquals("No school on Fri 13 Nov", Instructions.describe(changes[1], tasks, LONDON))
        assertEquals("Half-day on Fri 23 Oct, ends 12:30", Instructions.describe(changes[2], tasks, LONDON))
        assertEquals(NoSchool("2026-10-26", "2026-10-30"), Instructions.schoolEntry(changes[0]))
        assertEquals(HalfDay("2026-10-23", 750), Instructions.schoolEntry(changes[2]))
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
        assertEquals("All of “essay” on Sun 11 Oct", Instructions.describe(changes[0], tasks, LONDON))
        assertEquals("“prep” at 19:30 on Mon 12 Oct", Instructions.describe(changes[1], tasks, LONDON))
        // Laid on the tasks.
        val overrides = Instructions.taskOverrides(listOf(applied(*changes.toTypedArray())))
        assertEquals("2026-10-11", overrides.getValue(essay.id).onDay)
        assertEquals(Fixtures.at("2026-10-12T19:30"), prep.withOverrides(overrides.getValue(prep.id)).userAtTime)
        assertEquals(overrides.getValue(prep.id), prep.withOverrides(overrides.getValue(prep.id)).overrides())
        // A time on the day all of it is kept to keeps the day; a later day for all of it drops a time on another.
        val both = Instructions.taskOverrides(listOf(applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-12"), changes[1])))
        assertEquals(TaskOverrides(onDay = "2026-10-12", atTime = Fixtures.at("2026-10-12T19:30")), both.getValue(prep.id))
        val later = Instructions.taskOverrides(listOf(applied(changes[1]), applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-11"), at = now + 1)))
        assertEquals(TaskOverrides(onDay = "2026-10-11"), later.getValue(prep.id))
        // The day given after the time, for the time's own day: both kept, as in the other order.
        val same = Instructions.taskOverrides(listOf(applied(changes[1]), applied(Change(ChangeType.OnDay, taskId = prep.id, date = "2026-10-12"), at = now + 1)))
        assertEquals(TaskOverrides(onDay = "2026-10-12", atTime = Fixtures.at("2026-10-12T19:30")), same.getValue(prep.id))
        // A weekday isn't a day for a task, nor a time without one.
        fun one(change: String) = parse("""{"understood":true,"question":null,"changes":[$change]}""")
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"OnDay","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":null,"endDate":null,"weekday":"Monday","time":null,"endTime":null}"""))
        assertIs<InstructionAnswers.Reading.Unclear>(one("""{"type":"AtTime","taskId":"teams:essay","afterTaskId":null,"eventKey":null,"eventTime":null,"minutes":null,"date":"2026-10-11","endDate":null,"weekday":null,"time":null,"endTime":null}"""))
    }

    @Test
    fun `a day or a time after a task's real deadline is late`() {
        // The essay's due Monday 12 Oct, 09:00.
        assertTrue(Instructions.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-13"), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertFalse(Instructions.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-12"), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertTrue(Instructions.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-12", startMin = 600, time = Fixtures.at("2026-10-12T10:00")), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        assertFalse(Instructions.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-11", startMin = 1170, time = Fixtures.at("2026-10-11T19:30")), essay, LONDON, Fixtures.at("2026-10-10T17:00")))
        // Undated, or a plan's date: never late.
        assertFalse(Instructions.latePin(Change(ChangeType.OnDay, taskId = "teams:x", date = "2027-01-01"), task("x", null), LONDON, Fixtures.at("2026-10-10T17:00")))
        // Overdue already: today or later is as soon as it can be, not late.
        assertFalse(Instructions.latePin(Change(ChangeType.OnDay, taskId = essay.id, date = "2026-10-16"), essay, LONDON, Fixtures.at("2026-10-16T18:30")))
        assertFalse(Instructions.latePin(Change(ChangeType.AtTime, taskId = essay.id, date = "2026-10-16", startMin = 1080, time = Fixtures.at("2026-10-16T18:00")), essay, LONDON, Fixtures.at("2026-10-16T17:30")))
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
        assertFalse(calendar.isSchoolDay(java.time.LocalDate.parse("2026-10-27")))
        assertTrue(calendar.isSchoolDay(java.time.LocalDate.parse("2026-11-02")))
        assertFalse(calendar.isSchoolDay(java.time.LocalDate.parse("2026-10-24")))
        assertEquals(780, calendar.halfDay(java.time.LocalDate.parse("2026-10-23"))?.endsAtMin)
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
        val today = java.time.LocalDate.parse("2026-10-12")
        fun chunk(step: Int) = Chunk(
            taskId = "teams:t", source = Source.Teams, kind = Kind.Homework, title = "t", step = "s$step", minutes = 30, dueAt = Fixtures.at("2026-10-20T09:00"),
            deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false, behind = false, part = step + 1, parts = 3, taskMinutes = 90, why = Why.Spread, stepIndex = step,
        )
        val pieces = (0..2).map(::chunk)
        val slots = pieces.mapIndexed { i, c -> Slot(SlotKind.Work, c, Fixtures.at("2026-10-12T17:00") + i * 35 * 60_000L, Fixtures.at("2026-10-12T17:30") + i * 35 * 60_000L, WindowKind.Evening, pinned = false) }
        val plan = Plan(Fixtures.at("2026-10-12T16:00"), today, listOf(DayBucket(today, 300, pieces, slots)), emptyList())
        assertEquals(listOf(slots[0]), Instructions.movedSlots(listOf(Change(ChangeType.AtTime, taskId = "teams:t", date = "2026-10-12", time = Fixtures.at("2026-10-12T20:00"))), plan))
        assertEquals(slots, Instructions.movedSlots(listOf(Change(ChangeType.OnDay, taskId = "teams:t", date = "2026-10-14")), plan))
        assertEquals(emptyList(), Instructions.movedSlots(listOf(Change(ChangeType.OnDay, taskId = "teams:t", date = "2026-10-12")), plan))
        assertEquals(slots.take(2), Instructions.movedSlots(listOf(Change(ChangeType.StartFrom, taskId = "teams:t", time = Fixtures.at("2026-10-12T18:00"))), plan))
    }

    @Test
    fun `steps for a task of your own are read, said and made on it, its ticks kept by name`() {
        val own = OwnTasks.create(OwnTask(title = "Essay", steps = listOf(OwnStep("Plan", 20), OwnStep("Write", 40))), "e", Fixtures.at("2026-10-12T17:00"))
        val ticked = own.copy(subSteps = own.subSteps.map { if (it.title == "Plan") it.copy(done = true) else it })
        val given = com.thomaswcode.decrastination.enrich.InstructionAnswers.Given(
            "Steps", taskId = own.id,
            steps = listOf(
                com.thomaswcode.decrastination.enrich.InstructionAnswers.GivenStep("Plan", 20),
                com.thomaswcode.decrastination.enrich.InstructionAnswers.GivenStep("Draft", 30, dueDate = "2026-10-14"),
                com.thomaswcode.decrastination.enrich.InstructionAnswers.GivenStep("Polish", 10, where = "home"),
            ),
        )
        val change = com.thomaswcode.decrastination.enrich.InstructionAnswers.change(given, mapOf(own.id to ticked), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON)!!
        assertEquals(listOf("Plan", "Draft", "Polish"), change.steps!!.map { it.title })
        assertEquals("“Essay” in steps: Plan (20 min), Draft (30 min), Polish (10 min)", Instructions.describe(change, mapOf(own.id to ticked), Fixtures.LONDON))
        val draft = OwnTasks.draftWith(ticked, change)!!
        val made = OwnTasks.edited(ticked, draft)
        assertEquals(listOf("Plan" to true, "Draft" to false, "Polish" to false), made.subSteps.map { it.title to it.done })
        assertEquals(60, made.subSteps.sumOf { it.minutes })
        // A source's task's too (16 Oct), but not one made from Anki's cards.
        val teams = ticked.copy(id = "teams:x", source = Source.Teams)
        assertTrue(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(given.copy(taskId = teams.id), mapOf(teams.id to teams), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON) != null)
        val deck = ticked.copy(id = "anki:deck", source = Source.Anki, derived = true)
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(given.copy(taskId = deck.id), mapOf(deck.id to deck), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertEquals("an Anki task's steps and name come from its cards", com.thomaswcode.decrastination.enrich.InstructionAnswers.problem(given.copy(taskId = deck.id), mapOf(deck.id to deck), emptyMap()))
        // Its steps come to its 60 minutes; to 70 only with a Minutes change saying so.
        val longer = change.copy(steps = change.steps!!.map { if (it.title == "Polish") it.copy(minutes = 20) else it })
        val tasks = mapOf(own.id to ticked)
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.stepsProblem(listOf(change), tasks))
        assertEquals("the steps for “Essay” add up to 70 minutes, not its 60, and nothing says its total changes", com.thomaswcode.decrastination.enrich.InstructionAnswers.stepsProblem(listOf(longer), tasks))
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.stepsProblem(listOf(longer, Change(ChangeType.Minutes, taskId = own.id, minutes = 70)), tasks))
        // A step's date years ahead, or after the task's own: not taken.
        val dated = ticked.copy(dueAt = Fixtures.at("2026-10-15T17:00"))
        fun withDue(due: String) = given.copy(steps = given.steps.map { if (it.title == "Draft") it.copy(dueDate = due) else it })
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(withDue("2029-01-01"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(withDue("2026-10-16"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON))
        assertTrue(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(withDue("2026-10-15"), mapOf(own.id to dated), emptyMap(), Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON) != null)
    }

    @Test
    fun `steps for a source's task are laid over its own, kept through a sync, their ticks by name, and taken back`() {
        val at = Fixtures.at("2026-10-12T17:00")
        val reading = Enrichment("h", "claude", at, effortMin = 45, subSteps = listOf(SubStep("Find the clip", 15), SubStep("Upload", 15), SubStep("Link it", 15)))
        val email = task("vid", "2026-10-17T23:59", effort = 45, source = Source.Gmail).let { it.copy(enrichment = reading.copy(inputHash = Enrichments.inputHash(it))).enriched() }
            .let { t -> t.copy(subSteps = t.subSteps.mapIndexed { i, s -> if (i == 0) s.copy(done = true, byHand = true) else s }) }
        assertEquals(3, email.subSteps.size)
        val one = Change(ChangeType.Steps, taskId = email.id, steps = listOf(OwnStep("Find the clip", 10), OwnStep("Put it on the site", 20, where = "home")))
        val laid = email.withOverrides(Instructions.taskOverrides(listOf(applied(one)))[email.id]!!)
        assertEquals(listOf("Find the clip" to true, "Put it on the site" to false), laid.subSteps.map { it.title to it.done })
        assertEquals("home", laid.subSteps[1].where)
        // A sync's merge, and the enrichment read again, keep them.
        val synced = Merge.apply(listOf(laid), Source.Gmail, listOf(Fetched("vid", "vid", Kind.Admin, dueAt = Fixtures.at("2026-10-17T23:59"))), at + 60_000L).tasks.single()
        assertEquals(listOf("Find the clip", "Put it on the site"), synced.subSteps.map { it.title })
        assertEquals(listOf("Find the clip", "Put it on the site"), synced.withEnrichment(reading.copy(inputHash = Enrichments.inputHash(synced), at = at + 120_000L)).subSteps.map { it.title })
        // Taken back: the enrichment's again, the tick on the first kept by its name.
        val back = synced.withOverrides(TaskOverrides.NONE)
        assertEquals(listOf("Find the clip" to true, "Upload" to false, "Link it" to false), back.subSteps.map { it.title to it.done })
        // Never over a deck's steps, which are its cards.
        val deck = task("deck", null, source = Source.Anki).copy(derived = true, subSteps = listOf(SubStep("1.1", 10)))
        assertEquals(listOf("1.1"), deck.withOverrides(TaskOverrides(steps = listOf(OwnStep("All", 10)))).subSteps.map { it.title })
    }

    @Test
    fun `a name for a source's task is laid over its own, which a sync keeps beneath it, and isn't new content to read`() {
        val at = Fixtures.at("2026-10-12T17:00")
        val vectors = task("Vectors Practice", "2026-10-16T23:59").let { it.copy(sourceValues = it.sourceValues!!.copy(title = it.title)) }
        val hash = Enrichments.inputHash(vectors)
        val rename = Change(ChangeType.Rename, taskId = vectors.id, title = "Vectors practice")
        assertEquals("“Vectors Practice” renamed “Vectors practice”", Instructions.describe(rename, mapOf(vectors.id to vectors), LONDON))
        val renamed = vectors.withOverrides(Instructions.taskOverrides(listOf(applied(rename)))[vectors.id]!!)
        assertEquals("Vectors practice", renamed.title)
        // In use, it's said against its source's name.
        assertEquals("“Vectors Practice” renamed “Vectors practice”", Instructions.describe(rename, mapOf(vectors.id to renamed), LONDON))
        assertEquals(hash, Enrichments.inputHash(renamed))
        val synced = Merge.apply(listOf(renamed), Source.Teams, listOf(Fetched(vectors.sourceId, "Vectors Practice", Kind.Homework, dueAt = vectors.dueAt)), at).tasks.single()
        assertEquals("Vectors practice", synced.title)
        assertEquals("Vectors Practice", synced.withOverrides(TaskOverrides.NONE).title)
        // One kept from before names were kept has its own taken as its source's when it's first renamed.
        val old = task("Essay", null).copy(sourceValues = SourceValues(Kind.Homework, null, null))
        assertEquals("Essay", old.withOverrides(TaskOverrides(title = "Big essay")).withOverrides(TaskOverrides.NONE).title)
        // Read from Claude: a name it gives; none, or for a task not listed, isn't taken.
        val given = com.thomaswcode.decrastination.enrich.InstructionAnswers.Given("Rename", taskId = vectors.id, title = " Vectors practice ")
        assertEquals(rename, com.thomaswcode.decrastination.enrich.InstructionAnswers.change(given, mapOf(vectors.id to vectors), emptyMap(), at, LONDON))
        assertNull(com.thomaswcode.decrastination.enrich.InstructionAnswers.change(given.copy(title = " "), mapOf(vectors.id to vectors), emptyMap(), at, LONDON))
        assertEquals("the new name is missing", com.thomaswcode.decrastination.enrich.InstructionAnswers.problem(given.copy(title = ""), mapOf(vectors.id to vectors), emptyMap()))
    }

    @Test
    fun `a name for a task of your own is made on it, and put back by its card's Undo`() {
        val own = OwnTasks.create(OwnTask(title = "Essay", minutes = 60), "e", now)
        val rename = Change(ChangeType.Rename, taskId = own.id, title = "Philosophy essay")
        assertEquals(listOf(rename), OwnTasks.madeOn(listOf(own), listOf(rename)))
        val renamed = OwnTasks.changed(own, rename)
        assertEquals("Philosophy essay", renamed.title)
        assertEquals("Philosophy essay", renamed.sourceValues?.title)
        val back = OwnTasks.restored(OwnTasks.draftOf(renamed), OwnTasks.draftOf(own), listOf(rename))
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
