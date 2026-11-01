package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Window
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.ReviewInput
import com.thomaswcode.decrastination.learn.Stats
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Stats page and the reviews say (docs/spec/learning.md), the phone's and the website's alike. */
class StatsPageTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today = StatsPage.Today(earnedMin = 30, spentMin = 10, leftMin = 20, spentUsd = 1.5, calls = 2, capUsd = 5, claudeOff = false)

    private fun summary(log: ActivityLog = ActivityLog()) = Stats.summary(log, Fixtures.at("2026-10-14T12:00"), zone)

    @Test
    fun `a period's days are its own, and this term's as the calendar has them`() {
        assertEquals(14, StatsPage.days(Stats.DAYS, LocalDate.of(2026, 10, 14)) { true })
        assertEquals(42, StatsPage.days(StatsPage.SIX_WEEKS, LocalDate.of(2026, 10, 14)) { true })
        assertTrue(StatsPage.days(StatsPage.TERM, LocalDate.of(2026, 10, 14)) { it.isAfter(LocalDate.of(2026, 10, 1)) } in 1..14)
    }

    @Test
    fun `the tiles say what's what, in the phone's words`() {
        val tiles = StatsPage.tiles(summary(), today)
        assertEquals(listOf("Finished", "Slots kept", "Blocked", "Protection", "Free time left today", "Claude this month"), tiles.map { it.label })
        assertEquals("$1.50", tiles[5].value)
        assertEquals("2 calls, cap $5", tiles[5].note)
        assertEquals("none with a deadline", tiles[0].note)
        assertEquals("nothing", tiles[2].note)
        assertEquals("Fine", tiles[3].value)
        assertFalse(tiles[3].warn)
    }

    @Test
    fun `Claude off says so, and one call is one`() {
        assertEquals("Off", StatsPage.tiles(summary(), today.copy(claudeOff = true))[5].value)
        assertEquals("nothing sent", StatsPage.tiles(summary(), today.copy(claudeOff = true))[5].note)
        assertEquals("1 call", StatsPage.tiles(summary(), today.copy(calls = 1, capUsd = null))[5].note)
    }

    @Test
    fun `a subject's line has only what applies`() {
        assertEquals("Maths: 1 h in 1 piece", StatsPage.subject(Stats.SubjectRow("Maths", 60, 1, 0, 0, 0, null)))
        assertEquals("Maths: 1 h in 2 pieces, 1 of 2 on time, 1.5× the estimates (from 3)", StatsPage.subject(Stats.SubjectRow("Maths", 60, 2, 2, 1, 3, 1.5)))
    }

    @Test
    fun `a day's line has only what applies, and an empty day fades`() {
        val empty = Stats.Day(LocalDate.of(2026, 10, 14), 0, 0, 0, null)
        assertEquals("0 done", StatsPage.day(empty))
        assertTrue(StatsPage.quiet(empty))
        val full = Stats.Day(LocalDate.of(2026, 10, 14), 2, 90, 1, true)
        assertEquals("2 done · 1 h 30 min in slots · 1 block · plan done", StatsPage.day(full))
        assertFalse(StatsPage.quiet(full))
        assertEquals("0 done · plan not done", StatsPage.day(empty.copy(planDone = false)))
    }

    @Test
    fun `in full, a term says so, and Claude off says nothing was sent`() {
        val lines = StatsPage.inFull(summary(), today, 30, term = true)
        assertTrue("Shown: the last 30 days, this term." in lines)
        assertTrue("Claude this month: $1.50 of $5 in 2 calls." in lines)
        assertTrue("Claude this month: off: nothing sent." in StatsPage.inFull(summary(), today.copy(claudeOff = true), 14, term = false))
        assertTrue("Shown: the last 14 days." in StatsPage.inFull(summary(), today, 14, term = false))
    }

    @Test
    fun `the offer of hours says what's suggested and what's now`() {
        assertEquals("From 16:00 to 21:00, where most of your kept slots were on weekdays. Now 17:00 to 22:00.", StatsPage.hoursDetail(Window(16 * 60, 21 * 60), Window(17 * 60, 22 * 60)))
    }

    @Test
    fun `a review's line says who wrote it, and why the rules did`() {
        val review = WeeklyReview(1L, emptyList(), by = "rules", why = "Claude was off")
        assertEquals("By the rules: Claude was off", ReviewPage.byLine(review))
        assertEquals("By the rules", ReviewPage.byLine(review.copy(why = null)))
        assertEquals("By Claude", ReviewPage.byLine(review.copy(by = "claude")))
    }

    private val change = ReviewInput.Change("softMinPerDay", 90, "made up")

    @Test
    fun `a review's change can be undone only while it's what the review left, and what it was is known`() {
        val review = WeeklyReview(1L, emptyList(), by = "rules", week = "2026-10-12", changes = listOf(change), settingsBefore = mapOf("softMinPerDay" to 60))
        fun card(settings: Settings, known: WeeklyReview = review) = ReviewPage.cards(ActivityLog(reviews = listOf(known)), settings, MemoryState(), zone).single().changes.single()
        assertTrue(card(Settings(softMinPerDay = 90)).undoable)
        assertFalse(card(Settings(softMinPerDay = 80)).undoable)
        assertFalse(card(Settings(softMinPerDay = 90), review.copy(settingsBefore = emptyMap())).undoable)
    }

    @Test
    fun `a tidy can be undone only while what's remembered is as it left it`() {
        val after = listOf(Fact("school", "Takes the bus", 1L))
        val review = WeeklyReview(1L, emptyList(), by = "rules", memoryTidy = MemoryTidyRecord(after + after, after))
        fun card(memory: MemoryState) = ReviewPage.cards(ActivityLog(reviews = listOf(review)), Settings(), memory, zone).single()
        assertEquals("What's remembered tidied: 2 facts into 1", card(MemoryState(facts = after)).tidy)
        assertTrue(card(MemoryState(facts = after)).tidyUndoable)
        assertFalse(card(MemoryState()).tidyUndoable)
    }

    @Test
    fun `reviews come newest first, each with its week's check-in, and a week that can't be read goes by its moment`() {
        val at = Fixtures.at("2026-10-14T12:00")
        val older = WeeklyReview(at - 7 * 86_400_000L, emptyList(), by = "rules", week = "2026-10-05")
        val newer = WeeklyReview(at, emptyList(), by = "rules", week = "not a week")
        val checkIn = CheckIn("2026-10-05", at, 4, "", "people", "", "")
        val cards = ReviewPage.cards(ActivityLog(reviews = listOf(older, newer), checkIns = listOf(checkIn)), Settings(), MemoryState(), zone)
        assertEquals(listOf("Week of Wed 14 Oct", "Week of Mon 5 Oct"), cards.map { it.week })
        assertNull(cards[0].checkIn)
        assertEquals("The week felt 4/5. Avoided: -. In the way: people. Would change: -. Most energy: -.", cards[1].checkIn)
    }
}
