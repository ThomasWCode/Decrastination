package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import kotlin.test.Test
import kotlin.test.assertEquals

/** Time in AnkiDroid as work (NF-03), and what a card takes (DX-11). */
class AnkiProgressTest {
    // Textbook 1 holds 1.1 (the quota's new cards) and 1.2 (a deck homework names).
    private val book = 1L
    private val quotaDeck = 11L
    private val homeworkDeck = 12L

    private fun deckTask(id: String, sourceId: String, deck: Long, name: String) = TaskItem(
        id = id, source = Source.Anki, sourceId = sourceId, title = id, kind = Kind.Revision,
        extra = mapOf(AnkiRules.EXTRA_DECK_ID to deck.toString(), AnkiRules.EXTRA_DECK_NAME to name),
        firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    private val quota = deckTask("anki:quota:2026-10-12", AnkiRules.QUOTA_PREFIX + "2026-10-12", quotaDeck, "Textbook 1::1.1")
    private val homework = deckTask("anki:deck:12", AnkiRules.DECK_PREFIX + homeworkDeck, homeworkDeck, "Textbook 1::1.2")

    /** Counts as [learn], [review], [new] for the book, 1.1 and 1.2 (the book's include its decks'). */
    private fun reading(at: String, entering: Boolean, one: Triple<Int, Int, Int>, two: Triple<Int, Int, Int>) = AnkiReading(
        Fixtures.at(at),
        mapOf(
            book to DeckCount(one.first + two.first, one.second + two.second, one.third + two.third, top = true),
            quotaDeck to DeckCount(one.first, one.second, one.third, top = false),
            homeworkDeck to DeckCount(two.first, two.second, two.third, top = false),
        ),
        entering,
    )

    @Test
    fun `a stretch with the counts fallen credits each deck task its cards and its share of the minutes`() {
        // Twelve minutes: every review done, five of 1.1's new cards seen (into learning), seven of 1.2's.
        val stretch = AnkiProgress.Stretch(
            reading("2026-10-12T17:00", true, Triple(1, 6, 20), Triple(1, 4, 10)),
            reading("2026-10-12T17:12", false, Triple(6, 0, 15), Triple(8, 0, 3)),
        )
        val done = AnkiProgress.done(stretch, listOf(quota, homework), reviewsOnly = false)
        // The quota: the ten reviews and five new; 1.2: its seven new. Twelve minutes split 15 to 7.
        assertEquals(listOf(AnkiProgress.Done(quota.id, 15, 8, false), AnkiProgress.Done(homework.id, 7, 4, false)), done)
    }

    @Test
    fun `today's work done by the reading going out is finished, and a stretch with nothing studied credits nothing`() {
        val start = reading("2026-10-12T17:00", true, Triple(0, 6, 5), Triple(0, 0, 4))
        val finished = AnkiProgress.done(AnkiProgress.Stretch(start, reading("2026-10-12T17:10", false, Triple(0, 0, 0), Triple(0, 0, 0))), listOf(quota, homework), reviewsOnly = false)
        assertEquals(listOf(true, true), finished.map { it.finished })
        // Ten minutes split by cards, 11 to 4.
        assertEquals(listOf(11, 4), finished.map { it.cards })
        assertEquals(listOf(7, 3), finished.map { it.minutes })
        // Opened and left with nothing studied: nothing.
        assertEquals(emptyList(), AnkiProgress.done(AnkiProgress.Stretch(start, start.copy(at = start.at + 600_000L, entering = false)), listOf(quota, homework), reviewsOnly = false))
        // Reviews only today: the quota is done with nothing due, its new cards not its own; its six
        // cards in ten minutes are six, no more than a minute a card.
        val reviews = AnkiProgress.done(AnkiProgress.Stretch(start, reading("2026-10-12T17:10", false, Triple(0, 0, 5), Triple(0, 0, 4))), listOf(quota), reviewsOnly = true)
        assertEquals(listOf(AnkiProgress.Done(quota.id, 6, 6, true)), reviews)
    }

    @Test
    fun `a stretch is a reading coming in and the next going out, within one Anki day`() {
        val counts = Triple(0, 10, 0) to Triple(0, 0, 0)
        val readings = listOf(
            reading("2026-10-12T17:00", true, counts.first, counts.second),
            reading("2026-10-12T17:10", false, counts.first, counts.second),
            // Across 04:00, when Anki's day turns over and its counts with it: no stretch.
            reading("2026-10-13T03:50", true, counts.first, counts.second),
            reading("2026-10-13T04:10", false, counts.first, counts.second),
            // Two comings in a row (one going out not read): the second's with its going out.
            reading("2026-10-13T17:00", true, counts.first, counts.second),
            reading("2026-10-13T18:00", true, counts.first, counts.second),
            reading("2026-10-13T18:05", false, counts.first, counts.second),
        )
        assertEquals(listOf(Fixtures.at("2026-10-12T17:00"), Fixtures.at("2026-10-13T18:00")), AnkiProgress.stretches(readings, LONDON).map { it.from.at })
    }

    @Test
    fun `seconds a card is the median of a deck's last stretches with five cards or more`() {
        fun stretch(start: String, minutes: Long, reviews: Int) = AnkiProgress.Stretch(
            reading(start, true, Triple(0, reviews, 0), Triple(0, 0, 0)),
            reading(start, false, Triple(0, 0, 0), Triple(0, 0, 0)).let { it.copy(at = it.at + minutes * 60_000L) },
        )
        // 30, 30 and 50 seconds a card in 1.1; a stretch of three cards is too few to say.
        val rates = AnkiProgress.secondsPerCard(listOf(stretch("2026-10-12T17:00", 10, 20), stretch("2026-10-13T17:00", 5, 10), stretch("2026-10-14T17:00", 10, 12), stretch("2026-10-15T17:00", 10, 3)))
        assertEquals(30.0 to 3, rates[quotaDeck])
        // As Settings says it, by the deck's name.
        val calibration = Calibration(ankiSecondsPerCard = mapOf(quotaDeck to 30.0), ankiStretches = mapOf(quotaDeck to 3))
        assertEquals(listOf("Textbook 1 › 1.1: 30 s a card, from 3 readings"), AnkiProgress.lines(calibration, listOf(quota)))
    }

    @Test
    fun `a deck's learned seconds a card set its minutes, Anki's usual where none is learned`() {
        // 40 reviews and 20 new: 8 and 25 seconds, 14 minutes; at a learned 6 seconds a card, 6.
        assertEquals(14, AnkiRules.effortMin(40, 20))
        assertEquals(6, AnkiRules.effortMin(40, 20, 6.0))
    }

    @Test
    fun `a stretch longer than three hours is no stretch, its going out not seen in time`() {
        val counts = Triple(0, 10, 0) to Triple(0, 0, 0)
        val readings = listOf(reading("2026-10-12T12:00", true, counts.first, counts.second), reading("2026-10-12T16:00", false, counts.first, counts.second))
        assertEquals(emptyList(), AnkiProgress.stretches(readings, LONDON))
    }

    @Test
    fun `the quota's reviews go by each deck's own speed, its new cards by their deck's`() {
        val decks = listOf(
            com.thomaswcode.decrastination.sources.anki.Deck(1, "Textbook 1", 0, 30, 20),
            com.thomaswcode.decrastination.sources.anki.Deck(11, "Textbook 1::1.1", 0, 0, 20),
            com.thomaswcode.decrastination.sources.anki.Deck(2, "GCSE Vocab", 0, 30, 0),
        )
        // 30 reviews at 4 s and 30 at Anki's 8, and 20 new at 1.1's 12 s: 2, 4 and 4 minutes, 10.
        assertEquals(10, AnkiRules.quotaMin(decks, 20, 11, mapOf(1L to 4.0, 11L to 12.0)))
        // None learned: Anki's usual, 8 s a review and 25 a new card.
        assertEquals(17, AnkiRules.quotaMin(decks, 20, 11, emptyMap()))
    }
}
