package com.thomaswcode.decrastination.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which timetable is kept as reads come and you answer (D-165). */
class TimetablesTest {
    private val slot = PpSchedule("2026-09-05T09:50:00", "2026-09-05T10:45:00", dayOfWeek = 3, scheduleWeek = 1)
    private val german = Timetable("2026-08-24T00:00:00", listOf(PpClass("german", "German", listOf(slot))))
    private val art = Timetable("2026-08-24T00:00:00", listOf(PpClass("art", "Art", listOf(slot.copy(dayOfWeek = 4)))))

    /** No classes at all, and classes left with no slots: neither has a lesson. */
    private val none = Timetable(null, emptyList())
    private val bare = Timetable("2026-08-24T00:00:00", listOf(PpClass("german", "German", emptyList()), PpClass("art", "Art", null)))

    @Test
    fun `a timetable has lessons where any class has a slot`() {
        assertTrue(german.hasLessons)
        assertTrue(Timetable(null, listOf(PpClass("art", "Art", emptyList()), PpClass("german", "German", listOf(slot)))).hasLessons)
        assertFalse(none.hasLessons)
        assertFalse(bare.hasLessons)
    }

    @Test
    fun `a read with lessons is the timetable, whatever was held back`() {
        assertEquals(Timetables.Kept(art, null), Timetables.after(german, null, art, NOW))
        assertEquals(Timetables.Kept(art, null), Timetables.after(german, EmptyTimetable(none, NOW - 5, kept = true), art, NOW))
        assertEquals(Timetables.Kept(german, null), Timetables.after(null, null, german, NOW))
    }

    @Test
    fun `a read with no lessons isn't taken over one that has them, and is held back from the first such read`() {
        listOf(none, bare).forEach { empty ->
            assertEquals(Timetables.Kept(german, EmptyTimetable(empty, NOW)), Timetables.after(german, null, empty, NOW))
        }
        // The next empty read: held back as from the first, your answer to it standing, the read the newest.
        val asked = EmptyTimetable(none, NOW)
        assertEquals(Timetables.Kept(german, EmptyTimetable(bare, NOW)), Timetables.after(german, asked, bare, NOW + 900_000))
        assertEquals(Timetables.Kept(german, EmptyTimetable(bare, NOW, kept = true)), Timetables.after(german, asked.copy(kept = true), bare, NOW + 900_000))
    }

    @Test
    fun `with no lessons stored either, an empty read is taken and nothing asked`() {
        assertEquals(Timetables.Kept(none, null), Timetables.after(null, null, none, NOW))
        assertEquals(Timetables.Kept(bare, null), Timetables.after(none, null, bare, NOW))
    }

    @Test
    fun `keeping the last one keeps it and asks no more, and taking the empty one takes it`() {
        val asked = EmptyTimetable(bare, NOW)
        assertEquals(Timetables.Kept(german, asked.copy(kept = true)), Timetables.answered(german, asked, keep = true))
        assertEquals(Timetables.Kept(bare, null), Timetables.answered(german, asked, keep = false))
        // Kept, then taken after all.
        assertEquals(Timetables.Kept(bare, null), Timetables.answered(german, asked.copy(kept = true), keep = false))
        // The empty one once taken, the next empty read has nothing to be held back from.
        assertEquals(Timetables.Kept(none, null), Timetables.after(bare, null, none, NOW + 900_000))
    }

    @Test
    fun `an answer with nothing held back changes nothing`() {
        assertEquals(Timetables.Kept(german, null), Timetables.answered(german, null, keep = false))
        assertEquals(Timetables.Kept(german, null), Timetables.answered(german, null, keep = true))
    }

    private companion object {
        const val NOW = 1_795_000_000_000L
    }
}
