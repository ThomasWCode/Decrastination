package com.thomaswcode.decrastination.notify

import com.thomaswcode.decrastination.core.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The routine notifications' ceiling and quiet hours (LR-07). */
class NotifyRulesTest {
    @Test
    fun `the routine go four a day, never in quiet hours, and Felix and protection always`() {
        for (channel in NotifyRules.CEILINGED) {
            assertTrue(NotifyRules.goesNow(channel, 3, quiet = false))
            assertFalse(NotifyRules.goesNow(channel, 4, quiet = false))
            assertFalse(NotifyRules.goesNow(channel, 0, quiet = true))
        }
        for (channel in listOf(Channels.FELIX, Channels.PROTECTION, Channels.FREE_TIME)) assertTrue(NotifyRules.goesNow(channel, 9, quiet = true))
    }

    @Test
    fun `of his messages, the review's, the summary's and a calendar question wait for the morning in quiet hours, and the rest don't`() {
        for (kind in MessageKind.entries) {
            assertFalse(NotifyRules.waitsForMorning(kind, quiet = false))
            assertEquals(kind in setOf(MessageKind.Review, MessageKind.Summary, MessageKind.EventQuestion), NotifyRules.waitsForMorning(kind, quiet = true), "$kind")
        }
    }

    @Test
    fun `the count is today's alone`() {
        assertEquals(mapOf("2026-10-15" to 1), NotifyRules.counted(mapOf("2026-10-14" to 4), "2026-10-15"))
        assertEquals(mapOf("2026-10-15" to 3), NotifyRules.counted(mapOf("2026-10-15" to 2), "2026-10-15"))
    }
}
