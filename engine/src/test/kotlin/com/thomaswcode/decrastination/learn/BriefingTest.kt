package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.felix.FelixHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The morning's briefing ([Briefing]) on temporary stores: once the server says what Felix says (D-142), his morning is its. */
class BriefingTest {
    private val felix = FelixHarness()
    private val stores = felix.stores

    /** Whether the server says the morning in his name, the phone keeping in step by commands. */
    @Volatile private var byServer = false
    private val briefing = Briefing(felix.planning, stores.log, stores.runtime, stores.school, felix.clock, felix.notices, {}, felix.looks, felix.conversation, byServer = { byServer })

    @AfterTest
    fun close() = felix.close()

    @Test
    fun `once the server says what he says, the morning in his name is its, and the day's record is still made here`() = runBlocking {
        stores.settings.update { it.copy(aiEnabled = false) }
        byServer = true
        felix.byServer = true
        briefing.run()
        assertTrue(stores.chat.value.messages.isEmpty())
        assertEquals(1, stores.log.value.days.size, "today's record")
        // Mirroring, his morning's said here, the list in his name with Claude off.
        byServer = false
        felix.byServer = false
        briefing.run()
        assertEquals(1, stores.chat.value.messages.count { it.from == Speaker.Felix })
    }
}
