package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * His looks (LR-03) on temporary stores (docs/plan_v4, Stages 4 and 5a), his message read by the real
 * caller from a stand-in for Claude's API ([FelixHarness]): written into the chat, his one notification
 * made from it, his next look set.
 */
class LooksTest {
    private val felix = FelixHarness()
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    /** Due tomorrow morning: tonight's work, whatever the time; its slot's start kept and the clock past his grace into it. */
    private suspend fun underWay(): String {
        stores.tasks.update {
            it.copy(
                tasks = listOf(
                    TaskItem(
                        id = "teams:sheet", source = Source.Teams, sourceId = "sheet", title = "Forces worksheet", dueAt = Fixtures.at("2026-10-20T08:30"),
                        kind = Kind.Homework, sourceEffortMin = 30, firstSeenAt = now, lastSeenAt = now,
                    ),
                ),
            )
        }
        val slot = felix.planning.plan().todaySlots.first { it.kind == SlotKind.Work }
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        stores.runtime.update { it.copy(slots = SlotState(today, previous = mapOf(slot.chunk!!.key to slot.start))) }
        felix.clock.time = slot.start + (stores.settings.value.felixGraceMin + 1) * 60_000L
        return slot.chunk!!.key
    }

    @Test
    fun `a look at a slot under way with nothing done writes in the chat, makes his notification from it and sets his next`() = runBlocking {
        val key = underWay()
        felix.answers("""{"message":"Ten minutes in: started the worksheet?","replies":["Yes","Not yet"],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        val message = stores.chat.value.messages.single()
        assertEquals(Speaker.Claude to MessageKind.SlotStart, message.from to message.kind)
        assertEquals("teams:sheet" to key, message.about.taskId to message.pieceKey)
        assertEquals("Forces worksheet", message.about.taskTitle)
        assertEquals("Ten minutes in: started the worksheet?" to listOf("Yes", "Not yet"), message.text to message.suggestedReplies)
        assertEquals(felix.clock.time + 15 * 60_000L, message.nextCheckAt)
        val notice = felix.ports.showing!!
        assertEquals(listOf(message.id to message.text), notice.lines.map { it.id to it.text })
        assertEquals(message.id to listOf("Yes", "Not yet"), notice.replyTo to notice.replies)
        assertTrue(notice.alert)
        val state = stores.chat.value.felix
        assertEquals(felix.clock.time + 15 * 60_000L, state.nextCheckAt)
        assertEquals(felix.clock.time, state.lastSentAt)
        assertTrue(felix.ports.looks.isNotEmpty())
        // Looked at again before the check he set: nothing more to say.
        felix.looks.check()
        assertEquals(1, stores.chat.value.messages.size)
        assertEquals(1, felix.server.requestCount)
    }

    @Test
    fun `his notifications off silence only them, and he still writes in the chat`() = runBlocking {
        underWay()
        felix.shown = false
        felix.answers("""{"message":"Ten minutes in: started the worksheet?","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        assertEquals("Ten minutes in: started the worksheet?", stores.chat.value.messages.single().text)
        assertNull(felix.ports.showing)
    }

    @Test
    fun `his silence is kept in the chat, shown nowhere, and counted as said`() = runBlocking {
        underWay()
        felix.answers("""{"message":"","replies":[],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        val silent = stores.chat.value.messages.single()
        assertEquals("" to MessageKind.SlotStart, silent.text to silent.kind)
        assertNull(felix.ports.showing)
        assertEquals(1, FelixRules.sent(stores.chat.value.messages, 0).size)
    }
}
