package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sync.GmailFinishes
import com.thomaswcode.decrastination.tasks.TasksHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * An email finished in Gmail by commands (5.15.0), applied through the journal's owner as any command is: finished, its very
 * first waiting for your OK, taken back, a given-up write sorted. Its writes are `GmailFinishesTest`'s; where they're made, the
 * server's harness.
 */
class GmailActionsTest {
    private val h = TasksHarness()

    @AfterTest
    fun close() = h.close()

    private val email = TaskItem(
        id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "A made-up email", dueAt = null, kind = Kind.Admin,
        firstSeenAt = h.clock.now(), lastSeenAt = h.clock.now(),
    )

    private fun state() = GmailWrites.state(h.stores.gmailWrites.value.writes, email.id)

    @Test
    fun `an email finished by its command waits for the very first OK, goes once given, and is taken back by its own`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(email)) }
        h.stores.settings.update { it.copy(gmailFinishes = true) }
        assertEquals(GmailFinishes.Finish.Awaiting, h.commands.act(FinishGmail(email.id)))
        assertEquals(GmailWrites.State.Awaiting, state())
        h.commands.act(ConfirmGmail())
        assertEquals(GmailWrites.State.Going, state())
        assertTrue(h.gmailWakes.isNotEmpty(), "its writes asked for")
        assertTrue(h.commands.act(UnfinishGmail(email.id)))
        assertEquals(GmailWrites.State.None, state(), "taken back before it went")
    }

    @Test
    fun `the very first finish refused goes nowhere, and one given up is sorted by its command`() = runBlocking {
        h.stores.tasks.update { it.copy(tasks = listOf(email)) }
        h.stores.settings.update { it.copy(gmailFinishes = true) }
        h.commands.act(FinishGmail(email.id))
        h.commands.act(ConfirmGmail(confirmed = false))
        assertEquals(GmailWrites.State.None, state())
        // One given up: sorted, it's said no more.
        h.stores.settings.update { it.copy(hintsSeen = it.hintsSeen + GmailWrites.OK_HINT) }
        h.commands.act(FinishGmail(email.id))
        h.stores.gmailWrites.update { s -> s.copy(writes = s.writes.map { it.copy(gaveUp = true, doneAt = h.clock.now(), lastError = "no connection") }) }
        assertEquals(GmailWrites.State.GaveUp, state())
        h.commands.act(SortGmail(email.id))
        assertTrue(h.stores.gmailWrites.value.writes.none { it.gaveUp })
    }
}
