package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A day's pieces laid out in time (PL-12). */
class SlotterTest {
    private val settings = Settings()
    private val tuesday = "2026-10-13"
    private val saturday = "2026-10-17"

    private fun w(start: String, end: String, kind: WindowKind = WindowKind.Evening) = DayWindow(minute(start), minute(end), kind)
    private fun minute(text: String) = text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()

    private fun chunk(id: String, minutes: Int = 30, where: Where = Where.Anywhere, part: Int = 1, availableAt: Long? = null) = Chunk(
        taskId = "teams:$id", source = Source.Teams, kind = Kind.Homework, title = id, step = null, minutes = minutes,
        dueAt = Fixtures.at("2026-10-20T09:00"), deadline = Fixtures.at("2026-10-20T09:00"), soft = false, overdue = false, dueToday = false,
        behind = false, part = part, parts = 1, taskMinutes = minutes, availableAt = availableAt, why = Why.Spread, where = where, hard = true,
    )

    /** Step [index] of one task's, the essay's: [name] as its title, so a slot says which. */
    private fun step(name: String, index: Int, minutes: Int) = chunk("essay", minutes, part = index + 1).copy(title = name, step = name, stepIndex = index)

    private fun slot(
        date: String,
        chunks: List<Chunk>,
        windows: List<DayWindow>,
        now: String = "2026-10-12T08:00",
        busy: List<Busy> = emptyList(),
        pins: Map<String, Long> = emptyMap(),
        previous: Map<String, Long> = emptyMap(),
        outside: Boolean = false,
        deadlineMinOf: (Chunk) -> Int? = { null },
    ) = Slotter.slot(LocalDate.parse(date), chunks, windows, busy, pins, previous, Fixtures.at(now), LONDON, settings, outside, deadlineMinOf)

    private fun hm(time: Long) = Instant.ofEpochMilli(time).atZone(LONDON).toLocalTime().toString()

    /** Each slot as "title 17:00–17:30", an outside break as "outside 09:30–09:50". */
    private fun List<Slot>.described() = map { "${it.chunk?.title ?: "outside"} ${hm(it.start)}–${hm(it.end)}" }

    @Test
    fun `pieces have five minutes between them, and a quarter of an hour after an hour and a half`() {
        val (slots, unslotted) = slot(tuesday, listOf(chunk("a"), chunk("b"), chunk("c"), chunk("d")), listOf(w("17:00", "22:00")))
        assertEquals(listOf("a 17:00–17:30", "b 17:35–18:05", "c 18:10–18:40", "d 18:55–19:25"), slots.described())
        assertEquals(emptyList(), unslotted)
    }

    @Test
    fun `work laid before a kept slot gives it a long break after it where the run it ends now needs one`() {
        val kept = chunk("kept", 60)
        val (slots, _) = slot(tuesday, listOf(kept, chunk("first", 50), chunk("last", 25)), listOf(w("09:00", "12:00", WindowKind.Study)), now = "2026-10-13T07:00", previous = mapOf(kept.key to Fixtures.at("2026-10-13T10:00")))
        // An hour and fifty minutes of work by eleven: a quarter of an hour's break before the next.
        assertEquals(listOf("first 09:00–09:50", "kept 10:00–11:00", "last 11:15–11:40"), slots.described())
    }

    @Test
    fun `work isn't laid before kept slots where their run would go past an hour and a half with no long break`() {
        val kept = chunk("kept", 60)
        val next = chunk("next", 25)
        val previous = mapOf(kept.key to Fixtures.at("2026-10-13T10:00"), next.key to Fixtures.at("2026-10-13T11:05"))
        val (slots, _) = slot(tuesday, listOf(kept, next, chunk("first", 50)), listOf(w("09:00", "13:00", WindowKind.Study)), now = "2026-10-13T07:00", previous = previous)
        // Before them, the kept slot's long break would have to come where the next already is: after them instead.
        assertEquals(listOf("kept 10:00–11:00", "next 11:05–11:30", "first 11:35–12:25"), slots.described())
    }

    @Test
    fun `work for home skips a study period, work done anywhere takes it`() {
        val windows = listOf(w("14:35", "15:30", WindowKind.Study), w("16:45", "22:00"))
        val (slots, _) = slot(tuesday, listOf(chunk("essay", where = Where.Home), chunk("reading")), windows)
        assertEquals(listOf("reading 14:35–15:05", "essay 16:45–17:15"), slots.described())
        assertEquals(listOf(WindowKind.Study, WindowKind.Evening), slots.map { it.window })
    }

    @Test
    fun `a piece longer than every free time is unslotted, in order`() {
        val windows = listOf(w("10:45", "11:15", WindowKind.Break), w("17:00", "18:00"))
        val (slots, unslotted) = slot(tuesday, listOf(chunk("big", 75), chunk("small"), chunk("huge", 90)), windows)
        assertEquals(listOf("small 10:45–11:15"), slots.described())
        assertEquals(listOf("big", "huge"), unslotted.map { it.title })
    }

    @Test
    fun `today's first slot is no earlier than now, on five minutes`() {
        val (slots, _) = slot(tuesday, listOf(chunk("a")), listOf(w("16:45", "22:00")), now = "2026-10-13T17:12")
        assertEquals(listOf("a 17:15–17:45"), slots.described())
    }

    @Test
    fun `a pin lands at its time, or at the next free start when something is in it`() {
        val pin = mapOf(chunk("b").key to Fixtures.at("2026-10-13T19:30"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), pins = pin)
        assertEquals(listOf("a 17:00–17:30", "b 19:30–20:00"), slots.described())
        assertEquals(listOf(false, true), slots.map { it.pinned })
        // Drums from 19:00 to 20:00: at 20:00, still pinned.
        val drums = Busy(Fixtures.at("2026-10-13T19:00"), Fixtures.at("2026-10-13T20:00"))
        val (moved, _) = slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), busy = listOf(drums), pins = pin)
        assertEquals(listOf("a 17:00–17:30", "b 20:00–20:30"), moved.described())
        assertTrue(moved.last().pinned)
        // A pin for another day doesn't touch this one.
        val thursday = mapOf(chunk("b").key to Fixtures.at("2026-10-15T19:30"))
        assertEquals(listOf("a 17:00–17:30", "b 17:35–18:05"), slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), pins = thursday).first.described())
    }

    @Test
    fun `a pin off the five minutes lands at its own minute, and one before its piece opens at the opening`() {
        // "Give me 15" at 19:17: 19:32, which its piece opens at (the time it was given), not 19:35.
        val b = chunk("b", availableAt = Fixtures.at("2026-10-13T19:32"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), b), listOf(w("17:00", "22:00")), now = "2026-10-13T17:00", pins = mapOf(b.key to Fixtures.at("2026-10-13T19:32")))
        assertEquals(listOf("a 17:00–17:30", "b 19:32–20:02"), slots.described())
        assertTrue(slots.last().pinned)
        // Its piece opens at 19:45, after the pin: at the opening, in the free time that ran from before
        // the pin, still pinned.
        val late = chunk("b", availableAt = Fixtures.at("2026-10-13T19:45"))
        val (moved, _) = slot(tuesday, listOf(chunk("a"), late), listOf(w("17:00", "22:00")), now = "2026-10-13T17:00", pins = mapOf(late.key to Fixtures.at("2026-10-13T19:30")))
        assertEquals(listOf("a 17:00–17:30", "b 19:45–20:15"), moved.described())
        assertTrue(moved.last().pinned)
    }

    @Test
    fun `a pin gone by is laid as any piece, where the last plan put it, rather than moving on`() {
        // Pinned at 19:00 and missed: at 19:52 it's at the first free time, no longer pinned, and kept
        // there at 19:58, where it's under way, rather than going on to the next five minutes at each
        // plan and never beginning (PL-13).
        val b = chunk("b")
        val pin = mapOf(b.key to Fixtures.at("2026-10-13T19:00"))
        val (first, _) = slot(tuesday, listOf(b), listOf(w("17:00", "22:00")), now = "2026-10-13T19:52", pins = pin)
        assertEquals(listOf("b 19:55–20:25"), first.described())
        assertTrue(!first.single().pinned)
        val (later, _) = slot(tuesday, listOf(b), listOf(w("17:00", "22:00")), now = "2026-10-13T19:58", pins = pin, previous = mapOf(b.key to first.single().start))
        assertEquals(listOf("b 19:55–20:25"), later.described())
    }

    @Test
    fun `a pin where its piece can't be done is laid where it can`() {
        // Work for home pinned in a study period: the evening's first free time, still pinned.
        val essay = chunk("essay", where = Where.Home)
        val home = slot(tuesday, listOf(essay), listOf(w("14:35", "15:30", WindowKind.Study), w("16:45", "22:00")), pins = mapOf(essay.key to Fixtures.at("2026-10-13T14:40"))).first
        assertEquals(listOf("essay 16:45–17:15"), home.described())
        assertTrue(home.single().pinned)
        // Pinned at 17:50, due at 18:00: it would end after its deadline, so it goes where it's done by then.
        val form = chunk("form")
        val due = slot(tuesday, listOf(form), listOf(w("16:45", "22:00")), pins = mapOf(form.key to Fixtures.at("2026-10-13T17:50"))) { 18 * 60 }.first
        assertEquals(listOf("form 16:45–17:15"), due.described())
    }

    @Test
    fun `a pin moved never takes the time of one that fits`() {
        // a's 13:00 is the midday break outside's, so it moves; b's 13:30 is free and holds it.
        val pins = mapOf(chunk("a").key to Fixtures.at("2026-10-17T13:00"), chunk("b").key to Fixtures.at("2026-10-17T13:30"))
        val (slots, _) = slot(saturday, listOf(chunk("a"), chunk("b")), listOf(w("08:30", "22:30")), pins = pins, outside = true)
        assertEquals(listOf("b 13:30–14:00", "a 14:05–14:35"), slots.filter { it.kind == SlotKind.Work }.described())
    }

    @Test
    fun `a piece just before a pin leaves its break before it`() {
        val pin = mapOf(chunk("b").key to Fixtures.at("2026-10-13T17:30"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), pins = pin)
        assertEquals(listOf("b 17:30–18:00", "a 18:05–18:35"), slots.described())
    }

    @Test
    fun `a start from the last plan is kept while its time is free`() {
        val previous = mapOf(chunk("b").key to Fixtures.at("2026-10-13T18:00"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), previous = previous)
        assertEquals(listOf("a 17:00–17:30", "b 18:00–18:30"), slots.described())
        assertEquals(listOf(false, false), slots.map { it.pinned })
        // A call put in its time: laid afresh, after a.
        val call = Busy(Fixtures.at("2026-10-13T17:50"), Fixtures.at("2026-10-13T18:20"))
        assertEquals(listOf("a 17:00–17:30", "b 18:20–18:50"), slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), busy = listOf(call), previous = previous).first.described())
    }

    @Test
    fun `a slot under way stays where it started`() {
        val previous = mapOf(chunk("a").key to Fixtures.at("2026-10-13T17:00"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), now = "2026-10-13T17:12", previous = previous)
        assertEquals(listOf("a 17:00–17:30", "b 17:35–18:05"), slots.described())
        // Just over, it stays where it was for its grace, its piece still to tick (PL-13)...
        assertEquals(listOf("a 17:00–17:30", "b 17:35–18:05"), slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), now = "2026-10-13T17:31", previous = previous).first.described())
        // ...and once that's over, it's laid afresh from now.
        assertEquals(listOf("a 17:45–18:15", "b 18:20–18:50"), slot(tuesday, listOf(chunk("a"), chunk("b")), listOf(w("17:00", "22:00")), now = "2026-10-13T17:41", previous = previous).first.described())
    }

    @Test
    fun `a piece waits for its task to open`() {
        val (slots, _) = slot(tuesday, listOf(chunk("late", availableAt = Fixtures.at("2026-10-13T19:02")), chunk("a")), listOf(w("17:00", "22:00")))
        assertEquals(listOf("a 17:00–17:30", "late 19:05–19:35"), slots.described())
    }

    @Test
    fun `a free Saturday has three breaks outside, morning, midday and afternoon`() {
        val (slots, _) = slot(saturday, listOf(chunk("a"), chunk("b")), listOf(w("08:30", "22:30")), outside = true)
        assertEquals(listOf("a 08:30–09:00", "outside 09:30–09:50", "b 09:50–10:20", "outside 13:00–13:20", "outside 16:00–16:20"), slots.described())
        assertEquals(SlotKind.Outside, slots[1].kind)
        // Not on a school day.
        assertTrue(slot(tuesday, listOf(chunk("a")), listOf(w("08:30", "22:30"))).first.none { it.kind == SlotKind.Outside })
    }

    @Test
    fun `the midday break outside goes first when the work needs its time, and none when the day is full`() {
        // 840 minutes; seventeen 35-minute pieces with their rests, a longer one after each hour and a
        // half's work, need 730: room for three breaks by the sum, but laid around three, cutting the
        // day up, one's left out. Around two, none is.
        val two = slot(saturday, (1..17).map { chunk("p$it", 35) }, listOf(w("08:30", "22:30")), outside = true)
        assertEquals(listOf("outside 09:30–09:50", "outside 16:00–16:20"), two.first.filter { it.kind == SlotKind.Outside }.described())
        assertEquals(emptyList(), two.second)
        // Twenty need 860 with their long rests, more than the day: no break, and the last left out still.
        val twenty = slot(saturday, (1..20).map { chunk("p$it", 35) }, listOf(w("08:30", "22:30")), outside = true)
        assertTrue(twenty.first.none { it.kind == SlotKind.Outside })
        assertEquals(listOf("p20"), twenty.second.map { it.title })
        // Twenty-four half hours need more than all 840.
        val full = slot(saturday, (1..24).map { chunk("p$it") }, listOf(w("08:30", "22:30")), outside = true).first
        assertTrue(full.none { it.kind == SlotKind.Outside })
    }

    @Test
    fun `on a free day under way, a break outside whose time has gone isn't laid after now`() {
        val (slots, _) = slot(saturday, emptyList(), listOf(w("08:30", "22:30")), now = "2026-10-17T14:10", outside = true)
        assertEquals(listOf("outside 14:10–14:30", "outside 16:00–16:20"), slots.described())
    }

    @Test
    fun `a break outside starts by six in the evening at the latest, or isn't laid`() {
        val day = listOf(w("08:30", "22:30"))
        // Busy through the afternoon till 17:55: the 16:00 break goes at 17:55, the latest it may.
        val late = slot(saturday, emptyList(), day, busy = listOf(Busy(Fixtures.at("2026-10-17T15:00"), Fixtures.at("2026-10-17T17:55"))), outside = true)
        assertEquals(listOf("outside 09:30–09:50", "outside 13:00–13:20", "outside 17:55–18:15"), late.first.described())
        // Till 18:05: none in the evening, so two.
        val evening = slot(saturday, emptyList(), day, busy = listOf(Busy(Fixtures.at("2026-10-17T15:00"), Fixtures.at("2026-10-17T18:05"))), outside = true)
        assertEquals(listOf("outside 09:30–09:50", "outside 13:00–13:20"), evening.first.described())
        // A free day planned after 18:00: none is left to lay.
        assertTrue(slot(saturday, emptyList(), day, now = "2026-10-17T18:10", outside = true).first.isEmpty())
    }

    @Test
    fun `a piece due today at a set time goes only in a slot over by then`() {
        val evening = listOf(w("16:45", "22:00"))
        // Due at 17:00: 16:45 to 17:15 would be late, so it has no slot.
        assertEquals(listOf("form"), slot(tuesday, listOf(chunk("form")), evening) { 17 * 60 }.second.map { it.title })
        // Due at 17:30: 16:45 holds it.
        assertEquals(listOf("form 16:45–17:15"), slot(tuesday, listOf(chunk("form")), evening) { 17 * 60 + 30 }.first.described())
    }

    @Test
    fun `the first free time after a moment leaves breaks around the slots laid`() {
        val windows = listOf(w("14:35", "15:30", WindowKind.Study), w("17:00", "22:00"))
        val (slots, _) = slot(tuesday, listOf(chunk("a"), chunk("b", where = Where.Home)), windows)
        val date = LocalDate.parse(tuesday)
        // a in the study period, b at 17:00: the next half hour for home is 17:35.
        assertEquals(Fixtures.at("2026-10-13T17:35"), Slotter.firstFreeAfter(slots, windows, emptyList(), Fixtures.at("2026-10-13T14:00"), 30, Where.Home, date, LONDON, breakMin = 5))
        // Done anywhere, the study period's rest from 15:10 holds 20 minutes.
        assertEquals(Fixtures.at("2026-10-13T15:10"), Slotter.firstFreeAfter(slots, windows, emptyList(), Fixtures.at("2026-10-13T14:00"), 20, Where.Anywhere, date, LONDON, breakMin = 5))
    }

    @Test
    fun `a task's steps keep their order in the day, a short later one not taking an earlier gap`() {
        // A quarter of an hour at break, 25 minutes at lunch, the evening: revising (25 minutes) fits lunch, but
        // follows the research; lunch goes to another task's piece.
        val windows = listOf(w("10:45", "11:00", WindowKind.Break), w("13:15", "13:40", WindowKind.Break), w("17:00", "22:00"))
        val steps = listOf(step("pick", 0, 15), step("research", 1, 60), step("plan", 2, 30), step("revise", 3, 25))
        val (slots, unslotted) = slot(tuesday, steps + chunk("vocab", 20), windows)
        assertEquals(listOf("pick 10:45–11:00", "vocab 13:15–13:35", "research 17:00–18:00", "plan 18:05–18:35", "revise 18:50–19:15"), slots.described())
        assertEquals(emptyList(), unslotted)
    }

    @Test
    fun `a step waits unslotted while one before it has no time, though it would fit`() {
        val windows = listOf(w("10:45", "11:15", WindowKind.Break), w("17:00", "18:00"))
        val (slots, unslotted) = slot(tuesday, listOf(step("research", 0, 90), step("plan", 1, 30)), windows)
        assertEquals(emptyList(), slots.described())
        assertEquals(listOf("research", "plan"), unslotted.map { it.title })
    }

    @Test
    fun `a pinned step stays where it's pinned, and the steps after it follow it`() {
        val steps = listOf(step("pick", 0, 15), step("plan", 1, 30), step("draft", 2, 55))
        val evening = listOf(w("17:00", "22:00"))
        val plan = mapOf(steps[1].key to Fixtures.at("2026-10-13T19:30"))
        assertEquals(listOf("pick 17:00–17:15", "plan 19:30–20:00", "draft 20:05–21:00"), slot(tuesday, steps, evening, pins = plan).first.described())
        // Pinned before the steps it follows, as you put it (D-4): they go round it, in their order.
        val draft = mapOf(steps[2].key to Fixtures.at("2026-10-13T17:00"))
        assertEquals(listOf("draft 17:00–17:55", "pick 18:00–18:15", "plan 18:20–18:50"), slot(tuesday, steps, evening, pins = draft).first.described())
    }

    @Test
    fun `steps keep their times from the last plan in their order, and are laid afresh where those were out of it`() {
        val windows = listOf(w("13:15", "13:45", WindowKind.Break), w("17:00", "22:00"))
        val steps = listOf(step("plan", 0, 30), step("revise", 1, 25))
        val kept = mapOf(steps[0].key to Fixtures.at("2026-10-13T17:30"), steps[1].key to Fixtures.at("2026-10-13T18:30"))
        assertEquals(listOf("plan 17:30–18:00", "revise 18:30–18:55"), slot(tuesday, steps, windows, previous = kept).first.described())
        // Revising before the plan, as a plan before D-108 could lay them: both laid afresh, in order.
        val shuffled = mapOf(steps[0].key to Fixtures.at("2026-10-13T17:00"), steps[1].key to Fixtures.at("2026-10-13T13:15"))
        assertEquals(listOf("plan 13:15–13:45", "revise 17:00–17:25"), slot(tuesday, steps, windows, previous = shuffled).first.described())
    }

    @Test
    fun `a step after one that moved keeps its start from the last plan where it still follows`() {
        // Planning was missed, so its start was let go of (PL-13); laid again from now, it ends before the draft's start.
        val steps = listOf(step("plan", 0, 30), step("draft", 1, 55))
        val previous = mapOf(steps[1].key to Fixtures.at("2026-10-13T19:00"))
        val (slots, _) = slot(tuesday, steps, listOf(w("17:00", "22:00")), now = "2026-10-13T17:55", previous = previous)
        assertEquals(listOf("plan 17:55–18:25", "draft 19:00–19:55"), slots.described())
    }

    @Test
    fun `a step pinned or begun ahead of the steps before it leaves the others their starts from the last plan`() {
        val steps = listOf(step("pick", 0, 15), step("plan", 1, 30), step("draft", 2, 55))
        val evening = listOf(w("17:00", "22:00"))
        val draft = mapOf(steps[2].key to Fixtures.at("2026-10-13T17:00"))
        // Drums from 18:00 to 18:30 put picking after them; with the drums gone, the next plan keeps the starts it had.
        val drums = Busy(Fixtures.at("2026-10-13T18:00"), Fixtures.at("2026-10-13T18:30"))
        val first = slot(tuesday, steps, evening, busy = listOf(drums), pins = draft).first
        assertEquals(listOf("draft 17:00–17:55", "pick 18:30–18:45", "plan 18:50–19:20"), first.described())
        val kept = first.associate { it.chunk!!.key to it.start }
        assertEquals(first.described(), slot(tuesday, steps, evening, pins = draft, previous = kept).first.described())
        // Drafting begun at 14:00 before the plan, which a missed slot moved to 17:00: revising keeps 18:30.
        val study = listOf(w("14:00", "15:00", WindowKind.Study), w("17:00", "22:00"))
        val begun = listOf(step("plan", 0, 30), step("draft", 1, 55), step("revise", 2, 25))
        val previous = mapOf(begun[0].key to Fixtures.at("2026-10-13T17:00"), begun[1].key to Fixtures.at("2026-10-13T14:00"), begun[2].key to Fixtures.at("2026-10-13T18:30"))
        val (laid, _) = slot(tuesday, begun, study, now = "2026-10-13T14:10", previous = previous)
        assertEquals(listOf("draft 14:00–14:55", "plan 17:00–17:30", "revise 18:30–18:55"), laid.described())
    }

    @Test
    fun `the room for breaks outside counts a task's steps in their order, as they're laid`() {
        // Listed 60, 30, 60 and 30 minutes they'd want two long rests; laid in their order, 60, 60, 30 and 30, one.
        // So laid, they leave room for the break outside at 13:00, which counting them as listed gave up.
        val midday = settings.copy(outsideBreaks = 1, outsideTimes = listOf(13 * 60))
        val steps = listOf(step("a", 0, 60), step("c", 2, 30), step("b", 1, 60), step("d", 3, 30))
        val (slots, unslotted) = Slotter.slot(LocalDate.parse(saturday), steps, listOf(w("10:00", "13:50")), emptyList(), emptyMap(), emptyMap(), Fixtures.at("2026-10-12T08:00"), LONDON, midday, true) { null }
        assertEquals(listOf("a 10:00–11:00", "b 11:05–12:05", "c 12:20–12:50", "outside 13:00–13:20", "d 13:20–13:50"), slots.described())
        assertEquals(emptyList(), unslotted)
    }

    @Test
    fun `a step under way stays where it is, though a step before it is laid after it`() {
        val windows = listOf(w("13:15", "13:45", WindowKind.Break), w("17:00", "22:00"))
        val steps = listOf(step("plan", 0, 30), step("revise", 1, 25))
        val previous = mapOf(steps[0].key to Fixtures.at("2026-10-13T17:00"), steps[1].key to Fixtures.at("2026-10-13T13:15"))
        val (slots, _) = slot(tuesday, steps, windows, now = "2026-10-13T13:20", previous = previous)
        assertEquals(listOf("revise 13:15–13:40", "plan 17:00–17:30"), slots.described())
    }

    @Test
    fun `a step that doesn't need the ones before it takes an earlier gap, and the step after it follows both`() {
        // Learning the vocabulary needn't wait for the questions (D-109): lunch holds it. The essay needs both.
        val windows = listOf(w("10:45", "11:00", WindowKind.Break), w("13:15", "13:40", WindowKind.Break), w("17:00", "22:00"))
        val steps = listOf(step("questions", 0, 60), step("vocab", 1, 25).copy(anyOrder = true), step("essay", 2, 30))
        val (slots, unslotted) = slot(tuesday, steps, windows)
        assertEquals(listOf("vocab 13:15–13:40", "questions 17:00–18:00", "essay 18:05–18:35"), slots.described())
        assertEquals(emptyList(), unslotted)
    }

    @Test
    fun `a step that doesn't need the ones before it but is in sittings follows them, its sittings counted on the steps in order`() {
        // Reading in two sittings, free of the questions before it: a sitting kept is counted on the first
        // step still to do, so it waits for the questions; lunch's 25 minutes stay free.
        val windows = listOf(w("13:15", "13:40", WindowKind.Break), w("17:00", "22:00"))
        val reading = step("reading", 1, 25).copy(anyOrder = true)
        val steps = listOf(step("questions", 0, 60), reading.copy(title = "reading 1", sitting = Sitting(1, "reading")), reading.copy(title = "reading 2", part = 3, sitting = Sitting(0, "reading")))
        val (slots, _) = slot(tuesday, steps, windows)
        assertEquals(listOf("questions 17:00–18:00", "reading 1 18:05–18:30", "reading 2 18:35–19:00"), slots.described())
    }

    @Test
    fun `today's free times at school are what its slots and their breaks leave of its school windows, from now`() {
        val windows = listOf(w("10:45", "11:15", WindowKind.Break), w("13:15", "14:05", WindowKind.Lunch), w("17:00", "22:00"))
        val lunch = Slot(SlotKind.Work, chunk("a", 25), Fixtures.at("2026-10-13T13:15"), Fixtures.at("2026-10-13T13:40"), WindowKind.Lunch, pinned = false)
        fun gaps(now: String) = Slotter.schoolGaps(LocalDate.parse(tuesday), windows, emptyList(), listOf(lunch), Fixtures.at(now), LONDON, settings).map { hm(it.start) to it.minutes }
        // The evening isn't school; lunch keeps five minutes after its slot.
        assertEquals(listOf("10:45" to 30, "13:45" to 20), gaps("2026-10-13T07:00"))
        assertEquals(listOf("10:50" to 25, "13:45" to 20), gaps("2026-10-13T10:50"))
        // A run long enough for a long break keeps it after its slot, as the slots are laid.
        val study = listOf(w("09:00", "10:30", WindowKind.Study))
        val first = Slot(SlotKind.Work, chunk("b", 30), Fixtures.at("2026-10-13T09:00"), Fixtures.at("2026-10-13T09:30"), WindowKind.Study, pinned = false)
        val long = settings.copy(slotLongBreakAfterMin = 30)
        assertEquals(listOf("09:45" to 45), Slotter.schoolGaps(LocalDate.parse(tuesday), study, emptyList(), listOf(first), Fixtures.at("2026-10-13T07:00"), LONDON, long).map { hm(it.start) to it.minutes })
        assertEquals(listOf("09:35" to 55), Slotter.schoolGaps(LocalDate.parse(tuesday), study, emptyList(), listOf(first), Fixtures.at("2026-10-13T07:00"), LONDON, settings).map { hm(it.start) to it.minutes })
        // A slot that ends where break begins keeps none of break: past its window's edge there's nothing to keep apart.
        val edge = listOf(w("10:15", "10:45", WindowKind.Study), w("10:45", "11:15", WindowKind.Break))
        val study1 = Slot(SlotKind.Work, chunk("d", 25), Fixtures.at("2026-10-13T10:20"), Fixtures.at("2026-10-13T10:45"), WindowKind.Study, pinned = false)
        assertEquals(listOf("10:45" to 30), Slotter.schoolGaps(LocalDate.parse(tuesday), edge, emptyList(), listOf(study1), Fixtures.at("2026-10-13T07:00"), LONDON, settings).map { hm(it.start) to it.minutes })
        // Two study periods five minutes apart are two runs, as they're laid: the second's slot keeps a short break.
        val two = listOf(w("09:00", "09:30", WindowKind.Study), w("09:35", "10:30", WindowKind.Study))
        val second = Slot(SlotKind.Work, chunk("c", 20), Fixtures.at("2026-10-13T09:35"), Fixtures.at("2026-10-13T09:55"), WindowKind.Study, pinned = false)
        val longer = settings.copy(slotLongBreakAfterMin = 45)
        assertEquals(listOf("10:00" to 30), Slotter.schoolGaps(LocalDate.parse(tuesday), two, emptyList(), listOf(first, second), Fixtures.at("2026-10-13T07:00"), LONDON, longer).map { hm(it.start) to it.minutes })
    }

    @Test
    fun `steps the day lists out of their order are laid in it`() {
        val (slots, _) = slot(tuesday, listOf(step("revise", 1, 25), step("plan", 0, 30)), listOf(w("17:00", "22:00")))
        assertEquals(listOf("plan 17:00–17:30", "revise 17:35–18:00"), slots.described())
    }
}
