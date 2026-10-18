package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * His looks (LR-03) on temporary stores (docs/plan_v4, Stage 4), his message read by the real caller
 * from a stand-in for Claude's API ([FelixHarness]): written, kept on record and posted, his next
 * look set.
 */
class LooksTest {
    private val felix = FelixHarness()
    private val stores = felix.stores
    private val now = felix.clock.now()

    @AfterTest
    fun close() = felix.close()

    @Test
    fun `a look at a slot under way with nothing done writes, keeps it on record, posts it and sets his next`() = runBlocking {
        // Due tomorrow morning: tonight's work, whatever the time.
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
        // Its start kept, as the plan's watcher keeps it, and the clock past his grace into it.
        val today = Instant.ofEpochMilli(now).atZone(Fixtures.LONDON).toLocalDate().toString()
        stores.runtime.update { it.copy(slots = SlotState(today, previous = mapOf(slot.chunk!!.key to slot.start))) }
        felix.clock.time = slot.start + (stores.settings.value.felixGraceMin + 1) * 60_000L
        felix.answers("""{"message":"Ten minutes in: started the worksheet?","replies":["Yes","Not yet"],"nextCheckMin":15,"memories":[]}""")
        felix.looks.check()
        val record = stores.log.value.felix.single()
        assertEquals(FelixPrompts.Reason.SlotStart.name, record.reason)
        assertEquals("teams:sheet" to slot.chunk!!.key, record.taskId to record.key)
        assertEquals("Ten minutes in: started the worksheet?" to listOf("Yes", "Not yet"), record.text to record.replies)
        assertEquals(record.id to record.text, felix.ports.posted.single().let { it.id to it.text })
        val state = stores.runtime.value.felix
        assertEquals(felix.clock.time + 15 * 60_000L, state.nextCheckAt)
        assertEquals(felix.clock.time, state.lastSentAt)
        assertTrue(felix.ports.looks.isNotEmpty())
        // Looked at again before the check he set: nothing more to say.
        felix.looks.check()
        assertEquals(1, stores.log.value.felix.size)
        assertEquals(1, felix.server.requestCount)
    }
}
