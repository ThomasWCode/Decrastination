package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.tasks.TasksHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** What only the phone can know, as commands (W-24): the outbox's own rules for them. */
class ObservationsTest {
    private val h = TasksHarness()

    @AfterTest
    fun cleanUp() = h.close()

    /** Sending commands, with today's ten minutes of free time earned to spend. */
    private fun sendingCommands() = runBlocking {
        h.stores.sync.update { it.copy(mode = SyncState.COMMANDS) }
        earned()
    }

    private fun earned() = runBlocking { h.stores.session.update { it.copy(credit = it.credit.earn(today, 10.0)) } }

    private val today get() = h.focus.today(h.clock.now())

    private val spent get() = h.stores.session.value.credit.on(today).spentMs

    @Test
    fun `the half-minute spends of free time are one command till it's sent, each spent here as it comes`() = runBlocking {
        sendingCommands()
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        assertEquals(listOf<Action>(SpendFreeTime(today.toString(), 60_000)), h.stores.outbox.value.commands.map { it.action })
        assertEquals(60_000, spent)
        // On its way, it's sent as it was: the next is one of its own.
        h.commands.sending()
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        assertEquals(listOf<Action>(SpendFreeTime(today.toString(), 60_000), SpendFreeTime(today.toString(), 30_000)), h.stores.outbox.value.commands.map { it.action })
        assertEquals(90_000, spent)
    }

    @Test
    fun `a spend waiting as the process starts may have gone, so it's merged into by none`() = runBlocking {
        sendingCommands()
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        // As a new process finds the outbox: what was waiting may be on the server already.
        val again = Commands(h, h.clock, h.stores.outbox, queuing = { true })
        again.send(SpendFreeTime(today.toString(), 30_000))
        assertEquals(2, h.stores.outbox.value.commands.size)
    }

    @Test
    fun `nor is another day's, or what isn't a spend`() = runBlocking {
        sendingCommands()
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        h.commands.send(SpendFreeTime(today.plusDays(1).toString(), 30_000))
        h.commands.send(NoteSource(Source.Teams, "a note"))
        h.commands.send(SpendFreeTime(today.plusDays(1).toString(), 30_000))
        assertEquals(4, h.stores.outbox.value.commands.size)
    }

    @Test
    fun `a command made while another's applied is made once that closes, as one of its own, with no wait on itself`() = runBlocking {
        sendingCommands()
        // What follows a read sends another, as an event's question found by the calendar read a sync sets off does.
        h.sources.addAfterEverySync { h.commands.send(NoteSource(Source.Teams, "after the read")) }
        withTimeout(5_000) { h.commands.act(ObserveSources(listOf(SourceObserved(Source.Teams, h.clock.now(), SourceRead(emptyList()))))) }
        assertEquals(listOf("ObserveSources", "NoteSource"), h.stores.outbox.value.commands.map { it.action::class.simpleName })
        assertEquals("after the read", h.stores.tasks.value.status(Source.Teams).note)
    }

    @Test
    fun `while mirroring, what's seen is applied here alone, as before`() = runBlocking {
        earned()
        h.commands.send(SpendFreeTime(today.toString(), 30_000))
        assertEquals(emptyList(), h.stores.outbox.value.commands)
        assertEquals(30_000, spent)
    }
}
