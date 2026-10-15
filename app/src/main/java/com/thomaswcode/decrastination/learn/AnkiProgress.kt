package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiRules
import kotlinx.serialization.Serializable
import java.time.ZoneId
import kotlin.math.roundToInt

/** A deck's counts at a reading (NF-03): in learning, due reviews, new cards, as AnkiDroid shows them; [top] for a deck no other holds. */
@Serializable
data class DeckCount(val learn: Int, val review: Int, val new: Int, val top: Boolean = true)

/** The decks' counts at [at], read as AnkiDroid came to the front ([entering]) or went from it. */
@Serializable
data class AnkiReading(val at: Long, val counts: Map<Long, DeckCount>, val entering: Boolean)

/**
 * What time in AnkiDroid did (NF-03, DX-11), from the decks' counts read as it came to the front and
 * went from it. Pure. A stretch's cards are its counts fallen: a new card seen, a review or a card in
 * learning done (a new card goes into learning, so it isn't counted again there). The daily quota
 * has every deck's reviews and learning and its own deck's new cards; a deck homework names, its new
 * cards. A deck's own counts hold its subdecks', so the decks no other holds are what's added up.
 */
object AnkiProgress {
    /** Readings kept: the last fifty, twenty-five stretches. */
    const val KEEP = 50

    /** A card's most time: a stretch gives no more than a minute a card. */
    private const val MAX_CARD_MIN = 1.0

    /** A deck's speed is from its last ten stretches with five cards or more. */
    const val STRETCHES = 10
    const val MIN_CARDS = 5

    /** The bounds a learned speed is kept within: a second to ten minutes a card. */
    const val MIN_SECONDS = 1.0
    const val MAX_SECONDS = 600.0

    /** A stretch with AnkiDroid in front: its readings coming in and going out, within one Anki day. */
    data class Stretch(val from: AnkiReading, val to: AnkiReading) {
        val minutes: Double get() = (to.at - from.at) / 60_000.0

        /** [deck]'s cards studied: its new cards seen, and reviews and cards in learning done. */
        fun cards(deck: Long, new: Boolean = true, reviews: Boolean = true): Int {
            val a = from.counts[deck] ?: return 0
            val b = to.counts[deck] ?: return 0
            val seen = if (new) (a.new - b.new).coerceAtLeast(0) else 0
            val done = if (reviews) (a.review - b.review).coerceAtLeast(0) + (a.learn - b.learn).coerceAtLeast(0) else 0
            return seen + done
        }

        /** Every card studied: the decks no other holds. */
        val total: Int get() = from.counts.filterValues { it.top }.keys.sumOf { cards(it) }
    }

    /** What a stretch did for a deck task: its cards, its share of the stretch's minutes, and whether today's work of it is all done. */
    data class Done(val taskId: String, val cards: Int, val minutes: Int, val finished: Boolean)

    /** The stretches in [readings]: each reading coming in with the one after it going out, the same Anki day. */
    fun stretches(readings: List<AnkiReading>, zone: ZoneId): List<Stretch> =
        readings.zipWithNext().filter { (a, b) -> a.entering && !b.entering && AnkiRules.ankiDay(a.at, zone) == AnkiRules.ankiDay(b.at, zone) }
            .map { (a, b) -> Stretch(a, b) }

    /**
     * Each open deck task [stretch] studied for, with its cards, its share of the stretch's minutes
     * (by cards, no more than a minute a card), and whether what it is for today is all done by the
     * reading going out: the quota, nothing due in any deck and none of its new cards left
     * ([reviewsOnly]: none planned); a deck homework names, none of today's new cards left.
     */
    fun done(stretch: Stretch, tasks: List<TaskItem>, reviewsOnly: Boolean): List<Done> {
        val anki = tasks.filter { it.source == Source.Anki && it.isOpen }
        val homework = anki.filter { it.sourceId.startsWith(AnkiRules.DECK_PREFIX) }
        val homeworkDecks = homework.mapNotNull { deckOf(it) }.toSet()
        val top = stretch.to.counts.filterValues { it.top }
        val found = buildList {
            anki.firstOrNull { it.sourceId.startsWith(AnkiRules.QUOTA_PREFIX) }?.let { quota ->
                val deck = deckOf(quota)?.takeIf { !reviewsOnly && it !in homeworkDecks }
                val cards = top.keys.sumOf { stretch.cards(it, new = false) } + (deck?.let { stretch.cards(it, reviews = false) } ?: 0)
                val due = top.values.sumOf { it.learn + it.review } + (deck?.let { stretch.to.counts[it]?.new } ?: 0)
                add(quota.id to (cards to (due == 0)))
            }
            for (task in homework) {
                val deck = deckOf(task) ?: continue
                val left = stretch.to.counts[deck]?.new ?: continue
                add(task.id to (stretch.cards(deck, reviews = false) to (left == 0)))
            }
        }.filter { (_, it) -> it.first > 0 }
        val cards = found.sumOf { it.second.first }.coerceAtLeast(1)
        return found.map { (id, it) ->
            val (n, finished) = it
            Done(id, n, minOf(stretch.minutes * n / cards, n * MAX_CARD_MIN).roundToInt(), finished)
        }
    }

    /**
     * Seconds a card (DX-11), per deck: the median over its last [STRETCHES] stretches with
     * [MIN_CARDS] of its cards or more, each the stretch's seconds over all its cards; with how many
     * stretches it rests on.
     */
    fun secondsPerCard(stretches: List<Stretch>): Map<Long, Pair<Double, Int>> {
        val samples = HashMap<Long, MutableList<Double>>()
        for (stretch in stretches) {
            val total = stretch.total
            if (total < MIN_CARDS) continue
            val seconds = stretch.minutes * 60 / total
            for (deck in stretch.from.counts.keys) if (stretch.cards(deck) >= MIN_CARDS) samples.getOrPut(deck) { mutableListOf() } += seconds
        }
        return samples.mapValues { (_, all) ->
            val last = all.takeLast(STRETCHES).sorted()
            val mid = last.size / 2
            val median = if (last.size % 2 == 1) last[mid] else (last[mid - 1] + last[mid]) / 2
            median.coerceIn(MIN_SECONDS, MAX_SECONDS) to last.size
        }
    }

    /** "German 1.3: 9 s a card, from 6 readings", a deck a line, named as its Anki tasks have it. */
    fun lines(calibration: Calibration, tasks: List<TaskItem>): List<String> {
        val names = tasks.filter { it.source == Source.Anki }.mapNotNull { task -> deckOf(task)?.let { it to task.extra[AnkiRules.EXTRA_DECK_NAME] } }
            .toMap()
        return calibration.ankiSecondsPerCard.entries.sortedBy { it.key }.map { (deck, seconds) ->
            val name = names[deck]?.replace("::", " › ") ?: "Deck $deck"
            val n = calibration.ankiStretches[deck] ?: 0
            "$name: ${seconds.roundToInt()} s a card, from $n reading${if (n == 1) "" else "s"}"
        }
    }

    private fun deckOf(task: TaskItem): Long? = task.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull()
}
