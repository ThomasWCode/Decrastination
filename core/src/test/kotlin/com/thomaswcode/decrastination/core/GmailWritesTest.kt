package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The journal of writes to Gmail: the grace, the first finish's OK, Undo, tries and receipts (docs/plan_v4/10-emails-done.md §Tests). */
class GmailWritesTest {
    private val now = Fixtures.at("2026-10-21T17:00")
    private val min = 60_000L

    private fun finish(id: String = "w1", taskId: String = "gmail:1", at: Long = now) =
        GmailWrite(id, taskId, "Trip consent form", "1", listOf(11L, 12L), 7L, at)

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
        val made = GmailWrites.with(one, "w1") { it.copy(flagged = listOf(101L), inbox = listOf(101L, 102L), unstarred = true, archived = true, doneAt = now + 2 * min) }
        assertEquals(GmailWrites.State.Made, GmailWrites.state(made, "gmail:1"))
        // Starred again since (the task open again), finished again: written again.
        assertEquals(2, GmailWrites.finish(made, finish("w2", at = now + 3 * min), confirmed = true)!!.size)
    }

    @Test
    fun `taken back before it read the conversation it's dropped, after it an Undo puts back what it took`() {
        val one = GmailWrites.finish(emptyList(), finish(), confirmed = true)!!
        // In its grace, or waiting for a connection: nothing's changed, so nothing's written.
        assertEquals(emptyList(), GmailWrites.undo(one, "gmail:1", now + 30_000L, "u1"))
        val awaiting = GmailWrites.finish(emptyList(), finish(), confirmed = false)!!
        assertEquals(emptyList(), GmailWrites.undo(awaiting, "gmail:1", now, "u1"))
        // Read, and partly made: an Undo to go at once, after it's done.
        val read = GmailWrites.with(one, "w1") { it.copy(flagged = listOf(101L), inbox = listOf(101L, 102L), unstarred = true) }
        val undone = GmailWrites.undo(read, "gmail:1", now + 2 * min, "u1")!!
        val undo = undone.last()
        assertEquals("w1" to now + 2 * min, undo.reverseOf to undo.notBefore)
        assertEquals(GmailWrites.State.TakenBack, GmailWrites.state(undone, "gmail:1"))
        assertEquals("w1", GmailWrites.next(undone, now + 2 * min)?.id)
        // The finish given up with its star taken off: the Undo goes next, putting back the star alone.
        val gaveUp = GmailWrites.with(undone, "w1") { it.copy(doneAt = now + 3 * min, gaveUp = true) }
        assertEquals("u1", GmailWrites.next(gaveUp, now + 3 * min)?.id)
        assertEquals(listOf(101L) to emptyList<Long>(), GmailWrites.toRestore(gaveUp, undo))
        // Made whole: both put back.
        val made = GmailWrites.with(undone, "w1") { it.copy(archived = true, doneAt = now + 3 * min) }
        assertEquals(listOf(101L) to listOf(101L, 102L), GmailWrites.toRestore(made, undo))
        // Taken back already: nothing more to take back.
        assertNull(GmailWrites.undo(undone, "gmail:1", now + 4 * min, "u2"))
        assertNull(GmailWrites.undo(emptyList(), "gmail:1", now, "u1"))
    }

    @Test
    fun `a try that fails is tried again, the fifth given up with what succeeded said`() {
        var writes = GmailWrites.with(GmailWrites.finish(emptyList(), finish(), confirmed = true)!!, "w1") { it.copy(flagged = listOf(101L), inbox = listOf(101L), unstarred = true) }
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
    fun `receipts are what a backup keeps, and go once their Undo can't stand`() {
        val made = finish().copy(notBefore = now, flagged = emptyList(), inbox = emptyList(), unstarred = true, archived = true, doneAt = now)
        val pending = finish("w2", "gmail:2").copy(notBefore = now + GmailWrites.GRACE_MS)
        val writes = listOf(made, pending)
        assertEquals(listOf(made), GmailWrites.receipts(writes))
        assertEquals(writes, GmailWrites.prune(writes, now + GmailWrites.RECEIPT_MS - 1))
        assertEquals(listOf(pending), GmailWrites.prune(writes, now + GmailWrites.RECEIPT_MS))
    }
}
