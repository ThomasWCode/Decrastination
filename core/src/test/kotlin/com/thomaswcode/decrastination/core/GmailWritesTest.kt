package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The journal of writes to Gmail: the grace, the first finish's OK, Undo, tries and receipts (docs/plan_v4/10-emails-done.md §Tests). */
class GmailWritesTest {
    private val now = Fixtures.at("2026-10-21T17:00")
    private val min = 60_000L

    private fun finish(id: String = "w1", taskId: String = "gmail:1", at: Long = now) =
        GmailWrite(id, taskId, "Trip consent form", "1", at)

    @Test
    fun `a finish goes after the grace once one's been OK'd, and the very first waits for your OK`() {
        val first = GmailWrites.finish(emptyList(), finish(), confirmed = false)!!
        assertNull(first.single().notBefore)
        assertEquals(GmailWrites.State.Awaiting, GmailWrites.state(first, "gmail:1"))
        assertNull(GmailWrites.next(first, now + 10 * min))
        assertNull(GmailWrites.wake(first))
        // Carry on: from then, the grace.
        val going = GmailWrites.confirm(first, now + min)
        assertEquals(now + min + GmailWrites.GRACE_MS, going.single().notBefore)
        assertNull(GmailWrites.next(going, now + min + GmailWrites.GRACE_MS - 1))
        assertEquals("w1", GmailWrites.next(going, now + min + GmailWrites.GRACE_MS)?.id)
        assertEquals(now + min + GmailWrites.GRACE_MS, GmailWrites.wake(going))
        // Not this time: dropped, the task left open; asked again at the next finish.
        assertTrue(GmailWrites.refuse(first).isEmpty())
        val later = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        assertEquals(now + GmailWrites.GRACE_MS, later.single().notBefore)
        assertEquals(GmailWrites.State.Going, GmailWrites.state(later, "gmail:1"))
    }

    @Test
    fun `a second finish of a task with one pending writes nothing more, and one after it's made does`() {
        val one = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        assertNull(GmailWrites.finish(one, finish("w2"), confirmed = true))
        val made = GmailWrites.with(one, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101", "102"), unstarred = true, archived = true, doneAt = now + 2 * min) }
        assertEquals(GmailWrites.State.Made, GmailWrites.state(made, "gmail:1"))
        // Starred again since (a Gmail read after it shows the task open again), finished again: written again.
        assertEquals(2, GmailWrites.finish(made, finish("w2", at = now + 3 * min), confirmed = true, readAt = now + 3 * min)!!.size)
    }

    @Test
    fun `taken back before it read the conversation it's dropped, after it an Undo puts back what it took`() {
        val one = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        // In its grace, or waiting for a connection: nothing's changed, so nothing's written.
        assertEquals(emptyList(), GmailWrites.undo(one, "gmail:1", now + 30_000L, "u1"))
        val awaiting = GmailWrites.finish(emptyList(), finish(), confirmed = false)!!
        assertEquals(emptyList(), GmailWrites.undo(awaiting, "gmail:1", now, "u1"))
        // Read, and partly made: an Undo to go at once, after it's done.
        val read = GmailWrites.with(one, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101", "102"), unstarred = true) }
        val undone = GmailWrites.undo(read, "gmail:1", now + 2 * min, "u1")!!
        val undo = undone.last()
        assertEquals("w1" to now + 2 * min, undo.reverseOf to undo.notBefore)
        assertEquals(GmailWrites.State.TakenBack, GmailWrites.state(undone, "gmail:1"))
        assertEquals("w1", GmailWrites.next(undone, now + 2 * min)?.id)
        // The finish given up with its star taken off: the Undo goes next, putting back the star alone.
        val gaveUp = GmailWrites.with(undone, "w1") { it.copy(doneAt = now + 3 * min, gaveUp = true) }
        assertEquals("u1", GmailWrites.next(gaveUp, now + 3 * min)?.id)
        assertEquals(listOf("101") to emptyList<String>(), GmailWrites.toRestore(gaveUp, undo))
        // Made whole: both put back.
        val made = GmailWrites.with(undone, "w1") { it.copy(archived = true, doneAt = now + 3 * min) }
        assertEquals(listOf("101") to listOf("101", "102"), GmailWrites.toRestore(made, undo))
        // Taken back already: nothing more to take back.
        assertNull(GmailWrites.undo(undone, "gmail:1", now + 4 * min, "u2"))
        assertNull(GmailWrites.undo(emptyList(), "gmail:1", now, "u1"))
    }

    @Test
    fun `a try that fails is tried again, the fifth given up with what succeeded said`() {
        var writes = GmailWrites.with(GmailWrites.finish(emptyList(), finish(), confirmed = true)!!, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101"), unstarred = true) }
        repeat(GmailWrites.MAX_TRIES - 1) { writes = GmailWrites.failed(writes, "w1", "no connection", now + it * min) }
        assertEquals(GmailWrites.State.Going, GmailWrites.state(writes, "gmail:1"))
        writes = GmailWrites.failed(writes, "w1", "no connection", now + 10 * min)
        val gaveUp = writes.single()
        assertTrue(gaveUp.gaveUp && gaveUp.doneAt == now + 10 * min)
        assertEquals(GmailWrites.State.GaveUp, GmailWrites.state(writes, "gmail:1"))
        assertEquals("Unstarred “Trip consent form” in Gmail but couldn't archive it (no connection): it's still in the inbox.", GmailWrites.failure(gaveUp))
        assertEquals("Couldn't unstar “Trip consent form” in Gmail (no connection): it's still starred.", GmailWrites.failure(gaveUp.copy(unstarred = false)))
        assertEquals(listOf(gaveUp), GmailWrites.givenUp(writes))
        // Kept till it's sorted, however old; then gone.
        assertEquals(writes, GmailWrites.prune(writes, now + 2 * GmailWrites.RECEIPT_MS))
        assertTrue(GmailWrites.sorted(writes, "gmail:1").isEmpty())
    }

    @Test
    fun `a finish made stands till a Gmail read after it shows the email starred again`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now + 2 * min)
        assertNull(GmailWrites.finish(listOf(made), finish("w2", at = now + 3 * min), confirmed = true, readAt = null))
        assertNull(GmailWrites.finish(listOf(made), finish("w2", at = now + 3 * min), confirmed = true, readAt = now + 2 * min))
        assertEquals(2, GmailWrites.finish(listOf(made), finish("w2", at = now + 9 * min), confirmed = true, readAt = now + 5 * min)!!.size)
        // One given up stands no more: a finish again goes on from it.
        val again = GmailWrites.finish(listOf(made.copy(gaveUp = true)), finish("w2"), confirmed = true)!!.single()
        assertTrue(again.id == "w1" && GmailWrites.pending(again))
    }

    @Test
    fun `an Undo takes back only its own finish, and a keep taken back never one by your word`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now)
        assertNull(GmailWrites.undo(listOf(made), "gmail:1", now + min, "u1", writeId = "w0"))
        assertEquals(2, GmailWrites.undo(listOf(made), "gmail:1", now + min, "u1", writeId = "w1")!!.size)
        assertNull(GmailWrites.undo(listOf(made.copy(explicit = true)), "gmail:1", now + min, "u1", byWork = true))
        assertEquals(2, GmailWrites.undo(listOf(made), "gmail:1", now + min, "u1", byWork = true)!!.size)
        assertEquals(setOf("w1"), GmailWrites.standing(listOf(made)))
    }

    @Test
    fun `an Undo given up is said as given up, with what it put back and what not`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now)
        val undo = made.copy(id = "u1", reverseOf = "w1", flagged = null, inbox = null, unstarred = true, archived = false, tries = 5, lastError = "no connection", doneAt = now + min, gaveUp = true)
        assertEquals(GmailWrites.State.GaveUp, GmailWrites.state(listOf(made, undo), "gmail:1"))
        assertEquals("Starred “Trip consent form” again in Gmail but couldn't put it back in the inbox (no connection): it's still archived.", GmailWrites.failure(undo))
        assertEquals("Couldn't star “Trip consent form” again in Gmail (no connection): star it there if you want it starred.", GmailWrites.failure(undo.copy(unstarred = false, archived = true)))
        assertEquals("Couldn't star “Trip consent form” again or put it back in the inbox in Gmail (no connection): it's as the finish left it.", GmailWrites.failure(undo.copy(unstarred = false)))
        // Sorted, the finish stands again, to be taken back once more.
        assertEquals(GmailWrites.State.Made, GmailWrites.state(GmailWrites.sorted(listOf(made, undo), "gmail:1"), "gmail:1"))
    }

    @Test
    fun `a receipt an Undo still to make needs is kept past its month`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now)
        val undo = made.copy(id = "u1", reverseOf = "w1", at = now + GmailWrites.RECEIPT_MS - min, notBefore = now + GmailWrites.RECEIPT_MS - min, flagged = null, inbox = null, doneAt = null)
        assertEquals(listOf(made, undo), GmailWrites.prune(listOf(made, undo), now + GmailWrites.RECEIPT_MS + min))
    }

    @Test
    fun `receipts are what a backup keeps, and go once their Undo can't stand`() {
        val made = finish().copy(notBefore = now, flagged = emptyList(), inbox = emptyList(), unstarred = true, archived = true, doneAt = now)
        val pending = finish("w2", "gmail:2").copy(notBefore = now + GmailWrites.GRACE_MS)
        val writes = listOf(made, pending)
        assertEquals(listOf(made), GmailWrites.receipts(writes))
        // One given up is this phone's to sort: not kept in a backup.
        assertEquals(listOf(made), GmailWrites.receipts(writes + finish("w3", "gmail:3").copy(notBefore = now, tries = 5, doneAt = now, gaveUp = true)))
        assertEquals(writes, GmailWrites.prune(writes, now + GmailWrites.RECEIPT_MS - 1))
        assertEquals(listOf(pending), GmailWrites.prune(writes, now + GmailWrites.RECEIPT_MS))
    }

    @Test
    fun `a failed try waits its backoff in the journal, and each task's writes go in the order they were journaled`() {
        var writes = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        writes = GmailWrites.failed(writes, "w1", "no connection", now + 2 * min)
        assertEquals(now + 4 * min, writes.single().notBefore)
        assertNull(GmailWrites.next(writes, now + 3 * min))
        assertEquals("w1", GmailWrites.next(writes, now + 4 * min)?.id)
        writes = GmailWrites.failed(writes, "w1", "no connection", now + 10 * min)
        assertEquals(now + 40 * min, writes.single().notBefore)
        // Two minutes the first time; then half an hour, doubled each time, to five hours.
        assertEquals(listOf(2L, 30L, 60L, 120L, 240L, 300L).map { it * min }, listOf(1, 2, 3, 4, 5, 9).map(GmailWrites::backoff))
        // A finish after an Undo that failed waits for it, so the two go in the order you asked.
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now)
        val undo = GmailWrites.failed(GmailWrites.undo(listOf(made), "gmail:1", now + min, "u1")!!, "u1", "no connection", now + min)
        val again = GmailWrites.finish(undo, finish("w3", at = now + min), confirmed = true)!!
        assertNull(GmailWrites.next(again, now + min + GmailWrites.GRACE_MS))
        assertEquals("u1", GmailWrites.next(again, now + 3 * min)?.id)
    }

    @Test
    fun `a finish again after one given up goes on from it, its lists kept for its Undo`() {
        val gaveUp = finish().copy(notBefore = now, tries = 5, lastError = "no connection", doneAt = now, gaveUp = true)
        // Given up after it took the star off: it goes on where it stopped, by your word now too.
        val partly = gaveUp.copy(flagged = listOf("101"), inbox = listOf("101", "102"), unstarred = true)
        val again = GmailWrites.finish(listOf(partly), finish("w2", at = now + min).copy(explicit = true), confirmed = true)!!.single()
        assertEquals("w1" to now + min + GmailWrites.GRACE_MS, again.id to again.notBefore)
        assertTrue(again.unstarred && !again.archived && again.tries == 0 && !again.gaveUp && again.explicit)
        assertEquals(listOf("101") to listOf("101", "102"), again.flagged to again.inbox)
        assertTrue(GmailWrites.givenUp(listOf(again)).isEmpty())
        // An Undo of a finish given up, its finish again: the finish goes again, both commands, from its own lists.
        val made = partly.copy(archived = true, tries = 0, lastError = null, gaveUp = false)
        val undoGaveUp = GmailWrites.undo(listOf(made), "gmail:1", now + min, "u1")!!.map { if (it.id == "u1") it.copy(unstarred = true, tries = 5, doneAt = now + 2 * min, gaveUp = true) else it }
        val redone = GmailWrites.finish(undoGaveUp, finish("w3", at = now + 3 * min), confirmed = true)!!.single()
        assertTrue(redone.id == "w1" && !redone.unstarred && !redone.archived && GmailWrites.pending(redone))
        assertEquals(listOf("101") to listOf("101", "102"), redone.flagged to redone.inbox)
        // Given up with an Undo of it still to make: kept, as it says what to put back, and a finish again comes after.
        val undone = GmailWrites.undo(listOf(partly), "gmail:1", now + min, "u1")!!
        assertEquals(listOf("w1", "u1", "w2"), GmailWrites.finish(undone, finish("w2", at = now + 2 * min), confirmed = true)!!.map { it.id })
    }

    @Test
    fun `settled, a task's receipts go once a read that began after them finds it open again, and once old`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("101"), unstarred = true, archived = true, doneAt = now)
        val writes = listOf(made)
        // Closed by the read after it: kept, for its Undo.
        assertEquals(writes, GmailWrites.settle(writes, { false }, now + min, now + min))
        // Open, but the read began before it was made: kept.
        assertEquals(writes, GmailWrites.settle(writes, { true }, now - min, now + min))
        // Open after a read that began after it: starred again, a new round.
        assertTrue(GmailWrites.settle(writes, { true }, now + min, now + min).isEmpty())
        // Given up: kept till it's sorted. With a write still to make: left as it is.
        val gaveUp = made.copy(gaveUp = true)
        assertEquals(listOf(gaveUp), GmailWrites.settle(listOf(gaveUp), { true }, now + min, now + min))
        val undoing = GmailWrites.undo(writes, "gmail:1", now + min, "u1")!!
        assertEquals(undoing, GmailWrites.settle(undoing, { true }, now + 2 * min, now + 2 * min))
        // Old: gone, whatever the read.
        assertTrue(GmailWrites.settle(writes, { false }, null, now + GmailWrites.RECEIPT_MS).isEmpty())
    }

    @Test
    fun `an Undo of a finish waiting a retry stops it as it is, and puts back only what it made`() {
        val read = GmailWrites.with(GmailWrites.finish(emptyList(), finish(), confirmed = true)!!, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101", "102"), unstarred = true) }
        val waiting = GmailWrites.failed(read, "w1", "no connection", now + min)
        val undone = GmailWrites.undo(waiting, "gmail:1", now + 2 * min, "u1")!!
        val stopped = undone.first { it.id == "w1" }
        assertTrue(stopped.doneAt == now + 2 * min && stopped.unstarred && !stopped.archived)
        assertEquals("u1", GmailWrites.next(undone, now + 2 * min)?.id)
        assertEquals(listOf("101") to emptyList<String>(), GmailWrites.toRestore(undone, undone.last()))
        // One due now (being made) isn't stopped: its Undo waits behind it.
        assertTrue(GmailWrites.pending(GmailWrites.undo(waiting, "gmail:1", now + 31 * min, "u1")!!.first { it.id == "w1" }))
    }

    @Test
    fun `the worker's wake is each email's first write to go, not one waiting behind it`() {
        val read = GmailWrites.with(GmailWrites.finish(emptyList(), finish(), confirmed = true)!!, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101")) }
        // Taken back as it was being made (due), then its try failed: the Undo waits behind its wait.
        val undone = GmailWrites.failed(GmailWrites.undo(read, "gmail:1", now + 2 * min, "u1")!!, "w1", "no connection", now + 2 * min)
        assertEquals(now + 4 * min, GmailWrites.wake(undone))
        assertNull(GmailWrites.next(undone, now + 3 * min))
        // A failure taken back by an Undo is said no more: the Undo's its task's newest.
        val gaveUp = GmailWrites.with(read, "w1") { it.copy(unstarred = true, tries = 5, doneAt = now + min, gaveUp = true) }
        assertEquals(listOf("w1"), GmailWrites.givenUp(gaveUp).map { it.id })
        assertTrue(GmailWrites.givenUp(GmailWrites.undo(gaveUp, "gmail:1", now + 2 * min, "u1")!!).isEmpty())
    }

    @Test
    fun `a write is well formed by Gmail's own ids, so a crafted backup's can't go into an IMAP command`() {
        val made = finish().copy(notBefore = now, flagged = listOf("101"), inbox = listOf("18446744073709551615"), unstarred = true, archived = true, doneAt = now)
        assertTrue(GmailWrites.wellFormed(made))
        assertFalse(GmailWrites.wellFormed(made.copy(threadId = "1\r\nA9 EXPUNGE")))
        assertFalse(GmailWrites.wellFormed(made.copy(threadId = "")))
        assertFalse(GmailWrites.wellFormed(made.copy(inbox = listOf("18446744073709551616"))))
        assertFalse(GmailWrites.wellFormed(made.copy(flagged = listOf("+101"))))
        assertFalse(GmailWrites.wellFormed(made.copy(tries = GmailWrites.MAX_TRIES + 1)))
    }

    @Test
    fun `switched off, a finish not yet started is dropped, and one started and an Undo go on`() {
        assertTrue(GmailWrites.switchedOff(GmailWrites.finish(emptyList(), finish(), confirmed = false)!!).isEmpty())
        val inGrace = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        assertTrue(GmailWrites.switchedOff(inGrace).isEmpty())
        val started = GmailWrites.with(inGrace, "w1") { it.copy(flagged = listOf("101"), inbox = listOf("101"), unstarred = true) }
        assertEquals(started, GmailWrites.switchedOff(started))
        val undone = GmailWrites.undo(started.map { it.copy(archived = true, doneAt = now) }, "gmail:1", now + min, "u1")!!
        assertEquals(undone, GmailWrites.switchedOff(undone))
    }
}
