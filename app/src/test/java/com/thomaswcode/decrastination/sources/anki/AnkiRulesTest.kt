package com.thomaswcode.decrastination.sources.anki

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnkiRulesTest {
    private val NOW = Fixtures.at("2026-10-08T17:00")

    private fun deck(id: Long, name: String, new: Int = 20, review: Int = 0, learn: Int = 0) = Deck(id, name, learn, review, new)

    private val decks = listOf(
        deck(1, "Extras"),
        deck(2, "Textbook 1", review = 5),
        deck(11, "Textbook 1::1.1", new = 0),
        deck(12, "Textbook 1::1.2", review = 5),
        deck(13, "Textbook 1::1.3"),
        deck(22, "Textbook 1::2.2"),
        deck(3, "Textbook 2", review = 2),
        deck(31, "Textbook 2::1.1", review = 2),
        deck(4, "Verbs", review = 1, learn = 2),
    )

    @Test
    fun `the sections German homework names are found, and only in vocabulary work`() {
        // The texts of 7 Oct (docs/data-sources.md §3).
        assertEquals(listOf(1 to 2), AnkiRules.linkedSections("Learn vocabulary column 1.2 Familie und Ehe - both ways.\nPage 15 - exercises 4 and 5."))
        assertEquals(listOf(2 to 2, 2 to 3), AnkiRules.linkedSections("Hausaufgaben\n1. Learn vocabulary p46-47/ 2.2/2.3 ( vocabulary test ! )"))
        assertEquals(emptyList(), AnkiRules.linkedSections("Learn vocabulary - verschiedene Familienformen"))
        assertEquals(emptyList(), AnkiRules.linkedSections("Chapter 2.3 questions"))
        // A class code or a year isn't a section.
        assertEquals(emptyList(), AnkiRules.linkedSections("12.1 German 2026-27 vocab, 2026.27"))
    }

    @Test
    fun `the quota deck is the current textbook's lowest section with cards left`() {
        val unseen = mapOf(11L to 0)
        assertEquals(12L, AnkiRules.quotaDeck(decks, 1) { unseen[it.id] ?: 99 }?.id)
        assertEquals(31L, AnkiRules.quotaDeck(decks, 2) { 99 }?.id)
    }

    @Test
    fun `a deck showing no new cards today may still have some, held back by its daily limit`() {
        // 1.1's 20 for today were studied before the day's first read: it's still the quota deck.
        assertEquals(11L, AnkiRules.quotaDeck(decks, 1) { if (it.id == 11L) 40 else 0 }?.id)
    }

    @Test
    fun `due reviews count each top-level deck once`() {
        assertEquals(5 + 2 + 3, AnkiRules.dueReviews(decks))
    }

    @Test
    fun `the Anki day starts at four in the morning`() {
        assertEquals(LocalDate.of(2026, 10, 8), AnkiRules.ankiDay(Fixtures.at("2026-10-09T03:59"), LONDON))
        assertEquals(LocalDate.of(2026, 10, 9), AnkiRules.ankiDay(Fixtures.at("2026-10-09T04:00"), LONDON))
    }

    private fun quota(decks: List<Deck>, previous: AnkiDay? = null, at: String = "2026-10-08T17:00") =
        AnkiRules.quota(decks, 1, previous, Fixtures.at(at), LONDON, 21 * 60 + 30) { it.new }

    @Test
    fun `the quota is every review plus the quota deck's new cards, due at 21 30`() {
        val (task, day) = quota(decks)
        task!!
        assertEquals("Anki: 10 reviews + 20 new (1.2)", task.title)
        assertEquals(Fixtures.at("2026-10-08T21:30"), task.dueAt)
        assertEquals(AnkiRules.effortMin(10, 20), task.sourceEffortMin)
        assertEquals(Kind.Revision, task.kind)
        assertTrue(task.derived)
        assertEquals("quota:2026-10-08", task.sourceId)
        assertEquals(AnkiDay("2026-10-08", 12, "Textbook 1::1.2"), day)
    }

    @Test
    fun `the quota deck stays fixed for the day once chosen`() {
        val studied = decks.map { if (it.id == 12L) it.copy(new = 0) else it }
        val (task, day) = quota(studied, previous = AnkiDay("2026-10-08", 12, "Textbook 1::1.2"))
        assertEquals("Anki: 10 reviews", task!!.title)
        assertEquals(12L, day.deckId)
    }

    @Test
    fun `the quota is done when nothing is due and the deck's new cards are studied`() {
        val finished = decks.map { it.copy(review = 0, learn = 0, new = if (it.id == 12L) 0 else it.new) }
        val (task, _) = quota(finished, previous = AnkiDay("2026-10-08", 12, "Textbook 1::1.2"))
        assertTrue(task!!.done)
    }

    @Test
    fun `a new day chooses its deck afresh`() {
        val (task, day) = quota(decks, previous = AnkiDay("2026-10-07", 11, "Textbook 1::1.1"), at = "2026-10-08T07:00")
        assertEquals(12L, day.deckId)
        assertEquals("quota:2026-10-08", task!!.sourceId)
    }

    @Test
    fun `no quota when nothing is due and there are no new cards`() {
        val (task, _) = quota(decks.map { it.copy(new = 0, review = 0, learn = 0) })
        assertNull(task)
    }

    private fun assignment(title: String, detail: String, dueAt: Long?, open: Boolean = true) = TaskItem(
        id = TaskItem.id(Source.Teams, title),
        source = Source.Teams,
        sourceId = title,
        title = title,
        detail = detail,
        className = "12.1 German 2026-27",
        dueAt = dueAt,
        kind = Kind.Homework,
        firstSeenAt = 0,
        lastSeenAt = 0,
        status = if (open) com.thomaswcode.decrastination.core.Status.Open else com.thomaswcode.decrastination.core.Status.Done,
    )

    @Test
    fun `each deck homework names becomes a task due with it, in daily steps`() {
        val homework = listOf(
            assignment("Familie und Ehe", "Learn vocabulary column 1.2 Familie und Ehe", Fixtures.at("2026-10-02T09:00")),
            assignment("Gefahren", "1. Learn vocabulary p46-47/ 1.2/2.2", Fixtures.at("2026-10-01T08:30")),
            assignment("Done already", "Learn vocabulary 1.3", Fixtures.at("2026-10-01T08:30"), open = false),
        )
        val tasks = AnkiRules.homeworkDecks(decks, 1, homework, unseen = { if (it.id == 12L) 45 else 10 }, now = NOW, zone = LONDON)
        assertEquals(listOf("deck:12", "deck:22"), tasks.map { it.sourceId })
        val deck12 = tasks.first()
        assertEquals("Learn Anki deck 1.2", deck12.title)
        assertEquals(1, deck12.stepsPerDay)
        assertEquals(Fixtures.at("2026-10-01T08:30"), deck12.dueAt)
        assertEquals(listOf("20 new cards", "20 new cards", "5 new cards"), deck12.subSteps!!.map { it.title })
        assertEquals("45 cards never studied. For: Familie und Ehe; Gefahren", deck12.detail)
        assertTrue(deck12.derived)
    }

    @Test
    fun `a homework deck is done when every card has been seen, reviews or not`() {
        val homework = listOf(assignment("Familie und Ehe", "Learn vocabulary column 1.3", null))
        val studied = decks.map { if (it.id == 13L) it.copy(new = 0, review = 4) else it }
        assertTrue(AnkiRules.homeworkDecks(studied, 1, homework, unseen = { 0 }, now = NOW, zone = LONDON).single().done)
    }

    @Test
    fun `a deck whose assignment closes gets a last look, done if finished and otherwise left to be missed`() {
        val tracked = { deckId: Long ->
            TaskItem(
                id = TaskItem.id(Source.Anki, "deck:$deckId"),
                source = Source.Anki,
                sourceId = "deck:$deckId",
                title = "Learn Anki deck",
                kind = Kind.Homework,
                derived = true,
                firstSeenAt = 0,
                lastSeenAt = 0,
                extra = mapOf(AnkiRules.EXTRA_DECK_ID to deckId.toString()),
            )
        }
        val handedIn = assignment("Familie und Ehe", "Learn vocabulary column 1.2 and 1.3", null, open = false)
        val finished = decks.map { if (it.id == 12L) it.copy(new = 0, review = 0) else it }
        val tasks = AnkiRules.homeworkDecks(finished, 1, listOf(handedIn, tracked(12), tracked(13)), unseen = { if (it.id == 12L) 0 else 30 }, now = NOW, zone = LONDON)
        assertEquals(listOf("deck:12"), tasks.map { it.sourceId })
        assertTrue(tasks.single().done)
    }

    @Test
    fun `the quota's new cards come from a deck homework doesn't name`() {
        // 1.1 is finished; 1.2 is the lowest deck with cards left, but homework names it: it's a task of its own.
        val (task, day) = AnkiRules.quota(decks, 1, null, NOW, LONDON, 21 * 60 + 30, homework = setOf(12L)) { if (it.id == 11L) 0 else 30 }
        assertEquals(13L, day.deckId)
        assertEquals("Anki: 10 reviews + 20 new (1.3)", task!!.title)
        // Delegated to homework with nothing due: no quota at all, so it can come back if the
        // homework goes before the cards are studied.
        val (none, _) = AnkiRules.quota(decks.map { it.copy(review = 0, learn = 0) }, 1, AnkiDay("2026-10-08", 12, "Textbook 1::1.2"), NOW, LONDON, 21 * 60 + 30, homework = setOf(12L)) { 30 }
        assertNull(none)
        // Chosen this morning, named by homework since: counted there only.
        val (later, _) = AnkiRules.quota(decks, 1, AnkiDay("2026-10-08", 12, "Textbook 1::1.2"), NOW, LONDON, 21 * 60 + 30, homework = setOf(12L)) { 30 }
        assertEquals("Anki: 10 reviews", later!!.title)
    }

    @Test
    fun `a homework deck whose new cards for today are studied waits for Anki's next day`() {
        val homework = listOf(assignment("Familie und Ehe", "Learn vocabulary column 1.2", Fixtures.at("2026-10-02T09:00")))
        // Reviews still due in it (deck 1.2 has 5) don't release more new cards today.
        val studied = decks.map { if (it.id == 12L) it.copy(new = 0) else it }
        val deck = AnkiRules.homeworkDecks(studied, 1, homework, unseen = { 25 }, now = NOW, zone = LONDON).single()
        assertEquals(Fixtures.at("2026-10-09T04:00"), deck.notBefore)
        val fresh = AnkiRules.homeworkDecks(decks, 1, homework, unseen = { 25 }, now = NOW, zone = LONDON).single()
        assertEquals(null, fresh.notBefore)
    }

    @Test
    fun `a homework deck part-way through today's new cards steps from what's left today`() {
        val homework = listOf(assignment("Familie und Ehe", "Learn vocabulary column 1.2", Fixtures.at("2026-10-02T09:00")))
        val partway = decks.map { if (it.id == 12L) it.copy(new = 5) else it }
        val deck = AnkiRules.homeworkDecks(partway, 1, homework, unseen = { 45 }, now = NOW, zone = LONDON).single()
        assertEquals(listOf("5 new cards", "20 new cards", "20 new cards"), deck.subSteps!!.map { it.title })
        // None left today: 20 a day from Anki's next day.
        val studied = decks.map { if (it.id == 12L) it.copy(new = 0) else it }
        val waiting = AnkiRules.homeworkDecks(studied, 1, homework, unseen = { 45 }, now = NOW, zone = LONDON).single()
        assertEquals(listOf("20 new cards", "20 new cards", "5 new cards"), waiting.subSteps!!.map { it.title })
    }

    @Test
    fun `sections the enrichment read link decks when the pattern finds none, and never beat it`() {
        // "Revise the family topic" names no section the pattern can see; the enrichment read 1.2.
        val unread = assignment("Familie", "Revise the family topic for Monday's test", Fixtures.at("2026-10-12T08:30"))
        val enriched = unread.copy(enrichment = com.thomaswcode.decrastination.core.Enrichment("h", "claude-opus-5-5", 0, ankiSections = listOf("1.2")))
        assertEquals(emptyList(), AnkiRules.homeworkDecks(decks, 1, listOf(unread), unseen = { 25 }, now = NOW, zone = LONDON).map { it.sourceId })
        assertEquals(listOf("deck:12"), AnkiRules.homeworkDecks(decks, 1, listOf(enriched), unseen = { 25 }, now = NOW, zone = LONDON).map { it.sourceId })
        // Where the pattern finds a section, it wins over the enrichment's.
        val named = assignment("Familie", "Learn vocabulary 1.3", Fixtures.at("2026-10-12T08:30")).copy(enrichment = enriched.enrichment)
        assertEquals(listOf("deck:13"), AnkiRules.homeworkDecks(decks, 1, listOf(named), unseen = { 25 }, now = NOW, zone = LONDON).map { it.sourceId })
    }

    @Test
    fun `deck counts parse`() {
        assertEquals(listOf(0, 3, 20), AnkiProvider.parseCounts("[0, 3, 20]")?.toList())
        assertNull(AnkiProvider.parseCounts("[0, 3]"))
        assertNull(AnkiProvider.parseCounts("nonsense"))
    }
}
