package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Act
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.Draft
import com.thomaswcode.decrastination.felix.Unsent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat screen's state beside the chat (docs/spec/felix.md §The chat), without Compose. */
class ChatScreenStateTest {
    private fun his(id: String, at: String, text: String = "Up at 17:00.") = ChatMessage(id, Speaker.Felix, text, Fixtures.at(at), kind = MessageKind.SlotStart)

    private fun yours(id: String, at: Long, replyTo: String? = null) = ChatMessage(id, Speaker.You, "on it", at, replyTo = replyTo, answeredAt = at)

    private fun yours(id: String, at: String, replyTo: String? = null) = yours(id, Fixtures.at(at), replyTo)

    /** When the chat's opened in these tests. */
    private val opening = Fixtures.at("2026-10-18T18:00")

    @Test
    fun `opened, the first of his unread is marked for the divider and opened at, and from his notification, its message`() {
        val state = ChatState(listOf(yours("y", "2026-10-18T17:00"), his("h1", "2026-10-18T17:10"), his("h2", "2026-10-18T17:20")), readAt = Fixtures.at("2026-10-18T17:05"))
        val screen = ChatScreenState()
        screen.opened(state, opening)
        assertEquals("h1" to "h1", screen.firstUnread to screen.scrollTo)
        assertNull(screen.flash)
        screen.opened(state, opening, at = "h2")
        assertEquals("h1" to "h2", screen.firstUnread to screen.scrollTo)
        assertEquals("h2", screen.flash)
        screen.scrolled()
        screen.flashed()
        assertNull(screen.scrollTo)
        assertNull(screen.flash)
        // Everything read: no divider, and the foot.
        screen.opened(state.copy(readAt = Fixtures.at("2026-10-18T18:00")), opening)
        assertNull(screen.firstUnread)
        assertNull(screen.scrollTo)
    }

    @Test
    fun `the list has each day's date over its first message and the divider over the first unread, his silences left out`() {
        val window = listOf(
            yours("y1", "2026-10-17T20:00"),
            his("s", "2026-10-17T20:05", text = ""),
            his("h1", "2026-10-18T07:00"),
            his("h2", "2026-10-18T17:00"),
        )
        val screen = ChatScreenState()
        screen.opened(ChatState(window, readAt = Fixtures.at("2026-10-18T08:00")), opening)
        val rows = screen.items(window, emptyList(), LONDON)
        assertEquals(listOf("day-y1", "y1", "day-h1", "h1", "unread", "h2"), rows.map { it.key })
        assertEquals(listOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)), rows.filterIsInstance<ChatScreenState.Item.Day>().map { it.date })
        // A message out of time order brings a date back: its row keyed by the message, so no key repeats.
        val back = window + yours("late", "2026-10-17T23:59")
        val keys = screen.items(back, emptyList(), LONDON).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a question you've answered from his notification isn't where the chat opens, and the archive's rows are marked`() {
        val answered = his("q", "2026-10-18T17:10").copy(kind = MessageKind.CheckIn, answeredAt = Fixtures.at("2026-10-18T17:11"))
        val state = ChatState(listOf(answered, his("h2", "2026-10-18T17:20")), readAt = Fixtures.at("2026-10-18T17:00"))
        val screen = ChatScreenState()
        screen.opened(state, opening)
        assertEquals("h2", screen.firstUnread)
        val rows = ChatScreenState().apply { loadEarlier(1) }.items(state.messages, listOf(his("old", "2026-08-01T10:00")), LONDON)
        assertEquals(mapOf("old" to true, "q" to false, "h2" to false), rows.filterIsInstance<ChatScreenState.Item.Message>().associate { it.message.id to it.archived })
    }

    @Test
    fun `a message you've replied to from his notification isn't new, and one it left out still is`() {
        // His older message, left out of the notification's room; the one you replied to from it; read before both.
        val older = his("old", "2026-10-18T17:05")
        val replied = his("r", "2026-10-18T17:10")
        val reply = yours("y", "2026-10-18T17:11", replyTo = "r").copy(answeredAt = null)
        val screen = ChatScreenState()
        screen.opened(ChatState(listOf(older, replied, reply), readAt = Fixtures.at("2026-10-18T17:00")), opening)
        assertEquals("old", screen.firstUnread)
        // Nothing of his but the one replied to: nothing new.
        val alone = ChatScreenState().apply { opened(ChatState(listOf(replied, reply), readAt = Fixtures.at("2026-10-18T17:00")), opening) }
        assertNull(alone.firstUnread)
    }

    @Test
    fun `a newer nudge of his just above the box lets go the question offered before, with no strip of its own`() {
        val screen = ChatScreenState()
        screen.offer(ChatMessage("q", Speaker.Felix, "What did you avoid?", 2, kind = MessageKind.CheckIn), checkInUnderWay = true)
        assertEquals("q" to true, screen.replyingTo?.id to screen.offered)
        screen.offer(ChatMessage("n", Speaker.Felix, "Starting now?", 3, kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting")), checkInUnderWay = true)
        assertEquals(null to false, screen.replyingTo to screen.offered)
        // One you chose stays chosen.
        val chosen = his("h", "2026-10-18T17:00")
        screen.replyTo(chosen)
        screen.offer(ChatMessage("n2", Speaker.Felix, "Ten minutes left.", 4, kind = MessageKind.SlotEnd, suggestedReplies = listOf("Done")), checkInUnderWay = true)
        assertEquals("h", screen.replyingTo?.id)
        // A nudge further up than the message just above the box is offered as a question is, with its strip.
        val fresh = ChatScreenState()
        fresh.offer(ChatMessage("n3", Speaker.Felix, "Starting now?", 5, kind = MessageKind.SlotStart, suggestedReplies = listOf("Starting")), checkInUnderWay = false, nudge = true)
        assertEquals("n3" to true, fresh.replyingTo?.id to fresh.offered)
    }

    @Test
    fun `made again, the screen has the archive's pages and the message it was replying to back`() {
        val screen = ChatScreenState()
        val target = his("h", "2026-10-18T17:00")
        screen.restored(earlier = 200, replyingTo = target, offered = false)
        assertEquals(200 to "h", screen.earlier to screen.replyingTo?.id)
        // Restored as chosen: his question offered after it doesn't take its place.
        screen.offer(ChatMessage("q", Speaker.Felix, "What did you avoid?", 2, kind = MessageKind.CheckIn), checkInUnderWay = true)
        assertEquals("h", screen.replyingTo?.id)
        // Restored as the chat's offer: the next question takes its place once it's answered.
        val offered = ChatScreenState()
        val first = ChatMessage("q1", Speaker.Felix, "How was the week, 1 to 5?", 1, kind = MessageKind.CheckIn)
        offered.restored(earlier = 0, replyingTo = first, offered = true)
        offered.offer(ChatMessage("q2", Speaker.Felix, "What did you avoid?", 2, kind = MessageKind.CheckIn), checkInUnderWay = true)
        assertEquals("q2", offered.replyingTo?.id)
        // Cleared with the chat: nothing to reply to, no pages.
        offered.cleared()
        assertEquals(null to 0, offered.replyingTo to offered.earlier)
    }

    @Test
    fun `a reply restored from the archive comes back only if nothing changed the reply meanwhile`() {
        val old = his("old", "2026-08-01T10:00")
        val other = his("other", "2026-10-18T17:00")
        val screen = ChatScreenState()
        screen.restored(earlier = 0, replyingTo = null, offered = false)
        screen.restoringReply("old")
        screen.restoredReply(old)
        assertEquals(old, screen.replyingTo)
        // Another chosen while it was read: that one stays.
        val again = ChatScreenState()
        again.restoringReply("old")
        again.replyTo(other)
        again.restoredReply(old)
        assertEquals(other, again.replyingTo)
        // His question waiting, offered at the first drawing while it's read: the restored one still wins.
        val asked = ChatScreenState()
        val question = his("q", "2026-10-18T17:30").copy(kind = MessageKind.Question, about = About(taskId = "teams:essay"))
        // (With nothing being restored, it's offered.)
        assertEquals(question, ChatScreenState().apply { offer(question, checkInUnderWay = false) }.replyingTo)
        asked.restoringReply("old")
        asked.offer(question, checkInUnderWay = false)
        asked.restoredReply(old)
        assertEquals(old to false, asked.replyingTo to asked.offered)
        // Let go meanwhile (sent, or the strip's ✕): none.
        val none = ChatScreenState()
        none.restoringReply("old")
        none.replyingTo = null
        none.restoredReply(old)
        assertNull(none.replyingTo)
    }

    @Test
    fun `an archive not read yet has its row, asks for one page till it's read, and a quote further back is shown from what's read of it`() {
        val old = his("old", "2026-08-01T10:00")
        val window = listOf(yours("w1", Fixtures.at("2026-10-18T17:00")).copy(replyTo = "old"))
        val screen = ChatScreenState()
        assertEquals(ChatScreenState.Item.Earlier, screen.items(window, emptyList(), LONDON, unread = true).first())
        assertTrue(screen.loadEarlier(null))
        assertEquals(ChatScreenState.PAGE, screen.earlier)
        assertFalse(screen.loadEarlier(null))
        // The quote, read on its own from the archive.
        val reply = screen.items(window, emptyList(), LONDON, quotes = listOf(old)).filterIsInstance<ChatScreenState.Item.Message>().single()
        assertEquals(old, reply.quote)
        // Tapped, further back than the pages read: as far back as it is, and scrolled to.
        screen.revealBack("old", 340)
        assertEquals(340 to "old", screen.earlier to screen.scrollTo)
    }

    @Test
    fun `the archive is read back a page at a time, newest first, and a quote's message loads as far back as it is`() {
        val start = Fixtures.at("2026-08-01T10:00")
        val archive = (1..250).map { yours("a$it", start + it * 60_000L) }
        val window = listOf(his("w1", "2026-10-18T17:00"))
        val screen = ChatScreenState()
        assertEquals(ChatScreenState.Item.Earlier, screen.items(window, archive, LONDON).first())
        assertTrue(screen.loadEarlier(archive.size))
        assertEquals((151..250).map { "a$it" } + "w1", screen.items(window, archive, LONDON).filterIsInstance<ChatScreenState.Item.Message>().map { it.message.id })
        assertTrue(screen.loadEarlier(archive.size))
        assertTrue(screen.loadEarlier(archive.size))
        assertEquals(250, screen.earlier)
        assertFalse(screen.loadEarlier(archive.size))
        // None left: no row to load more.
        assertTrue(screen.items(window, archive, LONDON).none { it == ChatScreenState.Item.Earlier })
        // A quote's message far back: shown, and scrolled to; one in neither, not.
        val fresh = ChatScreenState()
        assertTrue(fresh.reveal("a10", window, archive))
        assertEquals(241 to "a10", fresh.earlier to fresh.scrollTo)
        assertTrue(fresh.reveal("w1", window, archive))
        assertEquals(241 to "w1", fresh.earlier to fresh.scrollTo)
        assertFalse(fresh.reveal("gone", window, archive))
    }

    @Test
    fun `a reply shows what it replies to where that isn't the message above it, from the archive too`() {
        val archived = listOf(his("old", "2026-08-01T10:00"))
        val window = listOf(
            his("h1", "2026-10-18T17:00"),
            yours("y1", "2026-10-18T17:01", replyTo = "h1"),
            his("h2", "2026-10-18T17:05"),
            yours("y2", "2026-10-18T17:06", replyTo = "h1"),
            yours("y3", "2026-10-18T17:07", replyTo = "old"),
        )
        val rows = ChatScreenState().items(window, archived, LONDON).filterIsInstance<ChatScreenState.Item.Message>()
        assertEquals(listOf(null, null, null, "h1", "old"), rows.map { it.quote?.id })
    }

    @Test
    fun `what's under a message is opened and closed, and every one closed again as the screen pauses`() {
        val screen = ChatScreenState()
        screen.toggle("h1")
        screen.unfold("h2")
        screen.unfold("h2")
        assertEquals(setOf("h1", "h2"), screen.open)
        screen.toggle("h1")
        assertEquals(setOf("h2"), screen.open)
        screen.paused()
        assertTrue(screen.open.isEmpty())
    }

    @Test
    fun `a label slides out once for his message with something to say that arrived on screen or was unread when the chat opened`() {
        fun said(id: String, at: String) = his(id, at).copy(acts = listOf(Act("Ticked “Plan” on “Essay”.", ChatAction(ChatAction.Type.TickStep, "own:essay", stepIndex = 0))))
        val read = said("read", "2026-10-18T16:00")
        val unread = said("unread", "2026-10-18T17:30")
        val talk = his("talk", "2026-10-18T17:40")
        val screen = ChatScreenState()
        screen.opened(ChatState(listOf(read, unread, talk), readAt = Fixtures.at("2026-10-18T17:00")), opening)
        // Read before it opened, or just talk: none.
        assertFalse(screen.labels(read))
        assertFalse(screen.labels(talk))
        assertTrue(screen.labels(unread))
        // Arriving while it's on screen.
        val arrived = said("arrived", "2026-10-18T18:05")
        assertTrue(screen.labels(arrived))
        assertFalse(screen.labels(arrived.copy(from = Speaker.You)))
        // An answer whose changes are kept after its words: none while it has nothing to say, one once it has.
        val words = his("words", "2026-10-18T18:06")
        assertFalse(screen.labels(words))
        assertTrue(screen.labels(said("words", "2026-10-18T18:06")))
        // Once a visit.
        screen.labelled("unread")
        assertFalse(screen.labels(unread))
        // Paused: nothing arrives on a screen that isn't there; opened again, what's unread then gets it.
        screen.paused()
        assertFalse(screen.labels(said("later", "2026-10-18T18:10")))
        val before = screen.visit
        screen.opened(ChatState(listOf(read, unread, arrived), readAt = Fixtures.at("2026-10-18T18:01")), Fixtures.at("2026-10-18T18:20"))
        assertEquals(before + 1, screen.visit)
        assertFalse(screen.labels(unread))
        assertTrue(screen.labels(arrived))
    }

    @Test
    fun `opened again before what it showed is written as read, what it showed isn't unread`() {
        val seen = his("seen", "2026-10-18T18:00")
        val screen = ChatScreenState()
        screen.paused(read = Fixtures.at("2026-10-18T18:05"))
        // The chat's read position not moved yet: its write still under way.
        screen.opened(ChatState(listOf(seen), readAt = Fixtures.at("2026-10-18T17:00")), Fixtures.at("2026-10-18T18:06"))
        assertNull(screen.firstUnread)
        assertFalse(screen.labels(seen.copy(acts = listOf(Act("Ticked “Plan” on “Essay”.", ChatAction(ChatAction.Type.TickStep, "own:essay", stepIndex = 0))))))
        // Paused with nothing read (on Your changes), what came after is still unread.
        val later = his("later", "2026-10-18T18:10")
        screen.paused()
        screen.opened(ChatState(listOf(seen, later), readAt = Fixtures.at("2026-10-18T17:00")), Fixtures.at("2026-10-18T18:20"))
        assertEquals("later", screen.firstUnread)
    }

    @Test
    fun `a visit is seen after 3 seconds on screen, or at once when marked or a message of yours is sent, and a new one starts unseen`() {
        val opened = Fixtures.at("2026-10-18T18:00")
        val screen = ChatScreenState()
        assertFalse(screen.seen(opened))
        screen.opened(ChatState(emptyList()), opened)
        assertFalse(screen.seen(opened + ChatScreenState.READ_AFTER_MS - 1))
        assertTrue(screen.seen(opened + ChatScreenState.READ_AFTER_MS))
        // Opened again: unseen till its own 3 seconds are up.
        val again = opened + 60_000L
        screen.paused(read = again)
        screen.opened(ChatState(emptyList()), again)
        assertFalse(screen.seen(again + 1_000L))
        screen.markSeen()
        assertTrue(screen.seen(again + 1_000L))
        // A message sent counts as seen at once.
        val third = again + 60_000L
        screen.paused(read = third)
        screen.opened(ChatState(emptyList()), third)
        assertFalse(screen.seen(third))
        screen.sent()
        assertTrue(screen.seen(third))
        // Paused, nothing's on screen to be seen till it's opened again.
        screen.paused()
        screen.opened(ChatState(emptyList()), third + 60_000L)
        assertFalse(screen.seen(third + 61_000L))
    }

    @Test
    fun `a visit left unseen keeps the next visit's New messages line on the same messages`() {
        val unread = his("unread", "2026-10-18T18:00")
        val state = ChatState(listOf(unread), readAt = Fixtures.at("2026-10-18T17:00"))
        val screen = ChatScreenState()
        screen.opened(state, Fixtures.at("2026-10-18T18:05"))
        assertEquals("unread", screen.firstUnread)
        // Passed through in a second: nothing read.
        val left = Fixtures.at("2026-10-18T18:05") + 1_000L
        screen.paused(read = left.takeIf { screen.seen(left) })
        screen.opened(state, Fixtures.at("2026-10-18T18:06"))
        assertEquals("unread", screen.firstUnread)
    }

    @Test
    fun `his question still waiting, his last message, is offered to the box, unless you chose another or turned it down`() {
        val screen = ChatScreenState()
        val question = ChatMessage("q", Speaker.Felix, "What did you avoid, and why?", 1, kind = MessageKind.CheckIn, byApp = true)
        // Offered while the check-in's under way; its thanks after it isn't a question.
        screen.offer(question, checkInUnderWay = true)
        assertEquals("q", screen.replyingTo?.id)
        screen.offer(question.copy(id = "thanks", text = "Thanks: the week's review comes next."), checkInUnderWay = false)
        assertNull(screen.replyingTo)
        // A message you chose stays chosen.
        screen.replyTo(his("n", "2026-10-18T17:00"))
        screen.offer(question, checkInUnderWay = true)
        assertEquals("n", screen.replyingTo?.id)
        // Turned down with its ✕: not offered again, but the next question is.
        screen.sent()
        screen.offer(question, checkInUnderWay = true)
        screen.notReplying()
        screen.offer(question, checkInUnderWay = true)
        assertNull(screen.replyingTo)
        screen.offer(question.copy(id = "q2"), checkInUnderWay = true)
        assertEquals("q2", screen.replyingTo?.id)
        // Answered: let go.
        screen.offer(question.copy(id = "q2", answeredAt = 5), checkInUnderWay = true)
        assertNull(screen.replyingTo)
    }

    @Test
    fun `a day's line says today, yesterday, or its date, with its year where it isn't this one`() {
        val today = LocalDate.of(2026, 10, 18)
        assertEquals("Today", ChatScreenState.dayLine(today, today))
        assertEquals("Yesterday", ChatScreenState.dayLine(today.minusDays(1), today))
        assertEquals("Tuesday 13 October", ChatScreenState.dayLine(LocalDate.of(2026, 10, 13), today))
        assertEquals("Wednesday 31 December 2025", ChatScreenState.dayLine(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun `words handed in to an empty box are the box's, and to words typed earlier they go first, a blank line between`() {
        assertEquals("Split this into steps", ChatScreenState.withHanded("", "Split this into steps"))
        assertEquals("Split this into steps", ChatScreenState.withHanded("  ", "Split this into steps"))
        assertEquals("Split this into steps\n\ncan I do it on Saturday", ChatScreenState.withHanded("can I do it on Saturday", "Split this into steps"))
    }

    @Test
    fun `the same words handed in again leave the box as it is, and so do blank ones`() {
        val box = ChatScreenState.withHanded("can I do it on Saturday", "Split this into steps")
        assertEquals(box, ChatScreenState.withHanded(box, "Split this into steps"))
        assertEquals("Split this into steps", ChatScreenState.withHanded("Split this into steps", "Split this into steps"))
        assertEquals("can I do it on Saturday", ChatScreenState.withHanded("can I do it on Saturday", " "))
    }

    @Test
    fun `a saved draft newer than the holder's comes back, whatever the holder holds`() {
        assertEquals(Draft("mine", 20), ChatScreenState.restoredDraft(Draft("older", 10), Draft("mine", 20)))
        assertEquals(Draft("mine", 20), ChatScreenState.restoredDraft(Draft("", 10), Draft("mine", 20)))
    }

    @Test
    fun `an older saved draft doesn't, a send's empty box among what it can't overwrite`() {
        assertEquals(Draft("newer", 30), ChatScreenState.restoredDraft(Draft("newer", 30), Draft("mine", 20)))
        // Two chats in the task, a stop, a send from the top one and Back to the lower one: the send's empty box stands.
        assertEquals(Draft("", 30), ChatScreenState.restoredDraft(Draft("", 30), Draft("mine", 20)))
        // The same draft, a turn of the phone: nothing changes.
        assertEquals(Draft("mine", 20), ChatScreenState.restoredDraft(Draft("mine", 20), Draft("mine", 20)))
    }

    @Test
    fun `saved words come back over a holder at time 0, and nothing saved leaves it as it is`() {
        assertEquals(Draft("mine", 20), ChatScreenState.restoredDraft(Draft("", 0), Draft("mine", 20)))
        assertEquals(Draft("", 0), ChatScreenState.restoredDraft(Draft("", 0), Draft("", 0)))
    }

    @Test
    fun `a message that couldn't be kept comes back into an empty box with its subject and its reply`() {
        val unsent = Unsent("the essay's done", About(taskId = "t", taskTitle = "Essay"), his("h", "2026-10-18T17:10"))
        assertEquals("the essay's done" to true, ChatScreenState.withUnsent("", unsent))
    }

    @Test
    fun `into a box holding words it goes first, a blank line between, the box's subject and reply kept`() {
        val unsent = Unsent("the essay's done", About(taskId = "t", taskTitle = "Essay"), his("h", "2026-10-18T17:10"))
        assertEquals("the essay's done\n\nand the prep" to false, ChatScreenState.withUnsent("and the prep", unsent))
    }
}
