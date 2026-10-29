package com.thomaswcode.decrastination.sources.gmail

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Deck
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.SetBy
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.enriched
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AstraIntegrationsReviewTest {
    @Test
    fun `an unchanged Gmail read preserves the source title under a rename`() {
        val now = Fixtures.at("2026-10-08T23:00")
        val conversation = GmailThreads.conversation(
            listOf(MailMessage(1, "message-1", "123", now, "Original subject", "Sender", "sender@example.com", false)),
            11,
        )
        val bodies = mapOf(conversation.version to GmailThreads.Body("Please return the form."))
        val original = Merge.apply(emptyList(), Source.Gmail, GmailThreads.fetched(listOf(conversation), bodies, now, LONDON), now).tasks.single()
        val renamed = original.copy(mine = Mine(title = SetBy("My short title", By.You))).enriched()
        val cached = GmailThreads.stored(renamed, conversation.uids, conversation.uidValidity)!!
        val polled = Merge.apply(listOf(renamed), Source.Gmail, GmailThreads.fetched(listOf(cached), bodies, now, LONDON), now).tasks.single()
        val undone = polled.copy(mine = Mine()).enriched()

        assertEquals("Original subject", undone.title)
    }

    @Test
    fun `steps use a deadline extended by the same instruction`() {
        val now = Fixtures.at("2026-10-09T17:00")
        val task = TaskItem(
            id = "teams:prep", source = Source.Teams, sourceId = "prep", title = "Prep", kind = Kind.Homework,
            sourceEffortMin = 20, dueAt = Fixtures.at("2026-10-14T08:30"), firstSeenAt = now, lastSeenAt = now,
        )
        val answer = """{"understood":true,"changes":[{"type":"DueBy","taskId":"teams:prep","date":"2026-10-20"},{"type":"Steps","taskId":"teams:prep","steps":[{"title":"Complete prep","minutes":20,"dueDate":"2026-10-18"}]}]}"""
        val read = InstructionAnswers.parse(answer, listOf(task), emptyList(), About(), now, LONDON)

        assertIs<InstructionAnswers.Reading.Changes>(read)
    }

    @Test
    fun `an assignment marked not a task does not suppress the daily new cards`() {
        val now = Fixtures.at("2026-10-09T17:00")
        val task = TaskItem(
            id = "teams:vocab", source = Source.Teams, sourceId = "vocab", title = "Learn vocabulary 1.2", kind = Kind.Homework,
            dueAt = Fixtures.at("2026-10-10T08:30"), firstSeenAt = now, lastSeenAt = now,
            mine = Mine(notATask = SetBy(true, By.You)),
        )
        val decks = listOf(Deck(1, "Textbook 1", learn = 0, review = 5, new = 20), Deck(12, "Textbook 1::1.2", learn = 0, review = 5, new = 20))
        val homework = AnkiRules.homeworkDecks(decks, 1, listOf(task), unseen = { 40 }, now = now, zone = LONDON)
        val homeworkDecks = homework.filter { !it.done }.mapNotNull { it.extra[AnkiRules.EXTRA_DECK_ID]?.toLongOrNull() }.toSet()
        val reviewsOnly = AnkiRules.homeworkDueSoon(homework, now, LONDON)
        val (quota, _) = AnkiRules.quota(decks, 1, null, now, LONDON, 21 * 60 + 30, homeworkDecks, reviewsOnly) { 40 }

        assertEquals("Anki: 5 reviews + 20 new (1.2)", quota?.title)
    }
}
