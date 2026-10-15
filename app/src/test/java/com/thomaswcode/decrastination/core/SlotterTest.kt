package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
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
        // 840 minutes; twenty 35-minute pieces with their breaks need 800: room for two breaks, not three.
        val pieces = (1..20).map { chunk("p$it", 35) }
        val two = slot(saturday, pieces, listOf(w("08:30", "22:30")), outside = true).first.filter { it.kind == SlotKind.Outside }
        assertEquals(listOf("outside 09:30–09:50", "outside 16:00–16:20"), two.described())
        // Twenty-four half hours need all 840.
        val full = slot(saturday, (1..24).map { chunk("p$it") }, listOf(w("08:30", "22:30")), outside = true).first
        assertTrue(full.none { it.kind == SlotKind.Outside })
    }

    @Test
    fun `on a free day under way, a break outside whose time has gone isn't laid after now`() {
        val (slots, _) = slot(saturday, emptyList(), listOf(w("08:30", "22:30")), now = "2026-10-17T14:10", outside = true)
        assertEquals(listOf("outside 14:10–14:30", "outside 16:00–16:20"), slots.described())
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
}
