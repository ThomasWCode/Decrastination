package com.thomaswcode.decrastination.sources.anki

import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil

/** One AnkiDroid deck with today's counts (`deck_count` is `[learn, review, new]`). */
data class Deck(val id: Long, val name: String, val learn: Int, val review: Int, val new: Int) {
    val isTopLevel: Boolean get() = SEPARATOR !in name

    companion object {
        const val SEPARATOR = "::"
    }
}

/** Today's quota deck, fixed at the Anki day's first read so that finishing it doesn't move the quota on. */
@Serializable
data class AnkiDay(
    /** The Anki day, ISO date. Anki's day starts at 04:00, not midnight. */
    val day: String,
    val deckId: Long? = null,
    val deckName: String? = null,
)

/**
 * The Anki tasks (docs/data-sources.md §3), from the deck counts, pure:
 *
 * - **The daily quota**: every due review, plus the new cards of the lowest-numbered section deck
 *   of the current textbook that still has some (your answer, 7 Oct; the textbook, Q18). Due at
 *   21:30. Done once nothing is due and that deck has no new cards left today.
 * - **A deck homework names**: "Learn vocabulary column 1.2" in an open German assignment makes
 *   `Textbook 1::1.2` a task with the assignment's deadline, in 20-card steps (the decks' daily
 *   limit). Done once every card in it has been seen (its reviews are the quota's); dropped,
 *   unfinished, if the assignment goes.
 */
object AnkiRules {

    /** Anki rolls its day over at 04:00, so a 01:00 session counts towards the day before. */
    private const val ROLLOVER_HOUR = 4L
    const val REVIEW_SECONDS = 8
    const val NEW_SECONDS = 25

    /** AnkiDroid's default, and what every deck on the phone uses. */
    const val NEW_PER_DAY = 20

    const val QUOTA_PREFIX = "quota:"
    const val DECK_PREFIX = "deck:"

    private val SECTION_DECK = Regex("""^Textbook (\d+)::(\d+)\.(\d+)$""")

    /** A section number in an assignment's text, not part of a longer number like "12.1" or "2026.27". */
    private val SECTION_IN_TEXT = Regex("""(?<![\d.])([1-9])\.([1-9])(?![\d.])""")
    private val VOCABULARY = Regex("""vocab|vokabel|wortschatz|anki""", RegexOption.IGNORE_CASE)

    fun ankiDay(now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).minusHours(ROLLOVER_HOUR).toLocalDate()

    /** Every due card, counting each top-level deck once: a parent's counts include its children's. */
    fun dueReviews(decks: List<Deck>): Int = decks.filter { it.isTopLevel }.sumOf { it.learn + it.review }

    fun effortMin(reviews: Int, new: Int): Int = ceil((reviews * REVIEW_SECONDS + new * NEW_SECONDS) / 60.0).toInt()

    /** `Textbook 1::1.2` → (1, 1, 2). */
    fun section(deck: Deck): Triple<Int, Int, Int>? =
        SECTION_DECK.find(deck.name)?.destructured?.let { (book, major, minor) -> Triple(book.toInt(), major.toInt(), minor.toInt()) }

    /**
     * The deck today's new cards come from: the current textbook's sections in order, then the
     * other textbooks', the first with cards never seen. A deck showing no new cards today may
     * still have some, held back by its daily limit (studied before the day's first read), so
     * [unseen] is asked about those.
     */
    fun quotaDeck(decks: List<Deck>, textbook: Int, homework: Set<Long> = emptySet(), unseen: (Deck) -> Int): Deck? =
        decks.filter { it.id !in homework }
            .mapNotNull { deck -> section(deck)?.let { deck to it } }
            .sortedWith(compareBy({ it.second.first != textbook }, { it.second.first }, { it.second.second }, { it.second.third }))
            .firstOrNull { (deck, _) -> deck.new > 0 || unseen(deck) > 0 }
            ?.first

    /** The sections a piece of vocabulary homework names, in order, without repeats. */
    fun linkedSections(text: String): List<Pair<Int, Int>> {
        if (!VOCABULARY.containsMatchIn(text)) return emptyList()
        return SECTION_IN_TEXT.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.distinct().toList()
    }

    fun deckName(textbook: Int, section: Pair<Int, Int>): String = "Textbook $textbook::${section.first}.${section.second}"

    /**
     * Today's quota: fixes the day's deck on the day's first read ([AnkiDay]), then measures what
     * is left against it. Null when there's nothing to do and nothing was, such as a day with no
     * reviews and no new cards anywhere.
     */
    fun quota(
        decks: List<Deck>,
        textbook: Int,
        previous: AnkiDay?,
        now: Long,
        zone: ZoneId,
        deadlineMin: Int,
        /** Decks homework names: their new cards are their own tasks, so the quota's come from another. */
        homework: Set<Long> = emptySet(),
        unseen: (Deck) -> Int,
    ): Pair<Fetched?, AnkiDay> {
        val day = ankiDay(now, zone)
        val today = previous?.takeIf { it.day == day.toString() }
            ?: quotaDeck(decks, textbook, homework, unseen).let { AnkiDay(day.toString(), it?.id, it?.name) }
        val reviews = dueReviews(decks)
        // A deck chosen this morning that homework has named since is counted there, not twice.
        val newLeft = today.deckId?.takeIf { it !in homework }?.let { id -> decks.firstOrNull { it.id == id }?.new } ?: 0
        if (today.deckId == null && reviews == 0) return null to today
        // Its deck is homework's for now and nothing is due: no quota today, rather than one done.
        // A quota marked done would stay done if the homework went before its cards were studied.
        if (today.deckId != null && today.deckId in homework && reviews == 0) return null to today
        val shortName = today.deckName?.substringAfterLast(Deck.SEPARATOR)
        val parts = buildList {
            if (reviews > 0) add("$reviews review${if (reviews == 1) "" else "s"}")
            if (newLeft > 0) add("$newLeft new${shortName?.let { " ($it)" } ?: ""}")
        }
        val dueAt = day.atTime(LocalTime.of(deadlineMin / 60, deadlineMin % 60)).atZone(zone).toInstant().toEpochMilli()
        val fetched = Fetched(
            sourceId = QUOTA_PREFIX + day,
            title = if (parts.isEmpty()) "Anki: today's cards" else "Anki: ${parts.joinToString(" + ")}",
            kind = Kind.Revision,
            detail = buildString {
                append("Every due review")
                if (today.deckName != null) append(", and today's new cards from ${today.deckName}")
                append(".")
            },
            className = "German",
            dueAt = dueAt,
            sourceEffortMin = effortMin(reviews, newLeft).coerceAtLeast(1),
            done = reviews == 0 && newLeft == 0,
            derived = true,
            extra = buildMap {
                today.deckId?.let { put(EXTRA_DECK_ID, it.toString()) }
                today.deckName?.let { put(EXTRA_DECK_NAME, it) }
            },
        )
        return fetched to today
    }

    /**
     * A task for each deck an open assignment names, due with the earliest such assignment.
     * [unseen] counts the deck's cards not yet seen (all of them, not just today's 20).
     */
    fun homeworkDecks(
        decks: List<Deck>,
        textbook: Int,
        assignments: List<TaskItem>,
        unseen: (Deck) -> Int,
        now: Long,
        zone: ZoneId,
    ): List<Fetched> {
        val byName = decks.associateBy { it.name }
        val wanted = LinkedHashMap<Deck, MutableList<TaskItem>>()
        for (task in assignments) {
            if (!task.isOpen || task.source == Source.Anki) continue
            for (section in linkedSections(task.title + "\n" + task.detail)) {
                val deck = byName[deckName(textbook, section)] ?: continue
                wanted.getOrPut(deck) { mutableListOf() } += task
            }
        }
        // A deck task whose assignment has just closed gets one last look: if the deck is
        // finished it's done, as a finished deck is; otherwise it's dropped, and missed.
        val finishing = assignments
            .filter { it.isOpen && it.source == Source.Anki && it.sourceId.startsWith(DECK_PREFIX) }
            .mapNotNull { task ->
                val deck = decks.firstOrNull { it.id.toString() == task.extra[EXTRA_DECK_ID] } ?: return@mapNotNull null
                if (deck in wanted || unseen(deck) > 0) return@mapNotNull null
                Fetched(
                    sourceId = task.sourceId,
                    title = task.title,
                    kind = task.kind,
                    detail = task.detail,
                    className = task.className,
                    dueAt = task.dueAt,
                    sourceEffortMin = task.sourceEffortMin,
                    done = true,
                    derived = true,
                    subSteps = emptyList(),
                    extra = task.extra,
                )
            }
        return finishing + wanted.map { (deck, linked) ->
            val first = linked.minWith(compareBy(nullsLast()) { it.dueAt })
            val left = unseen(deck)
            // Today's new cards for this deck are studied: the rest wait for Anki's next day,
            // whatever reviews it has (those are the daily quota's).
            val waits = deck.new == 0 && left > 0
            val section = deck.name.substringAfterLast(Deck.SEPARATOR)
            Fetched(
                sourceId = DECK_PREFIX + deck.id,
                title = "Learn Anki deck $section",
                kind = Kind.Homework,
                detail = "$left cards never studied. For: " + linked.joinToString("; ") { it.title },
                className = first.className,
                dueAt = first.dueAt,
                sourceEffortMin = effortMin(0, left).coerceAtLeast(1),
                done = left == 0,
                derived = true,
                subSteps = newCardSteps(left),
                stepsPerDay = 1,
                notBefore = if (waits) nextRollover(now, zone) else null,
                extra = mapOf(EXTRA_DECK_ID to deck.id.toString(), EXTRA_DECK_NAME to deck.name, EXTRA_FOR to linked.joinToString(",") { it.id }),
            )
        }
    }

    /** When Anki's next day starts: the next 04:00. */
    fun nextRollover(now: Long, zone: ZoneId): Long =
        ankiDay(now, zone).plusDays(1).atTime(ROLLOVER_HOUR.toInt(), 0).atZone(zone).toInstant().toEpochMilli()

    /** The deck's daily limit makes a day's step: 45 unseen cards are 20, 20 and 5. */
    private fun newCardSteps(unseen: Int): List<SubStep> =
        (0 until unseen step NEW_PER_DAY).map { start ->
            val cards = minOf(NEW_PER_DAY, unseen - start)
            SubStep("$cards new cards", effortMin(0, cards).coerceAtLeast(1))
        }

    const val EXTRA_DECK_ID = "deckId"
    const val EXTRA_DECK_NAME = "deckName"
    const val EXTRA_FOR = "for"
}
