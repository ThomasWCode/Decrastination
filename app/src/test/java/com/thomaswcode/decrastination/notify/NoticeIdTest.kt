package com.thomaswcode.decrastination.notify

import com.thomaswcode.decrastination.felix.FelixNotifications
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every notification named once (docs/plan_v4/05-the-chat.md §3): no two ids, nor two ranges of request codes, alike. */
class NoticeIdTest {
    @Test
    fun `no two notifications share an id`() {
        val ids = NoticeId.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "an id twice: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `no two ranges of request codes overlap`() {
        val ranged = NoticeId.entries.filterNot { it.codes.isEmpty() }
        for ((i, a) in ranged.withIndex()) for (b in ranged.drop(i + 1)) {
            assertTrue(a.codes.last < b.codes.first || b.codes.last < a.codes.first, "${a.name}'s codes ${a.codes} overlap ${b.name}'s ${b.codes}")
        }
    }

    @Test
    fun `a code is its own range's, or refused`() {
        assertEquals(4200, NoticeId.FelixMessage.code())
        assertEquals(4202, NoticeId.FelixMessage.code(2))
        assertFailsWith<IllegalArgumentException> { NoticeId.FelixMessage.code(3) }
        assertFailsWith<IllegalArgumentException> { NoticeId.FelixMessage.code(-1) }
        // Work under way has no tap of its own.
        assertFailsWith<IllegalArgumentException> { NoticeId.Sync.code() }
    }

    @Test
    fun `Felix's message has a request code for its tap and each card's button`() {
        // The tap is code 0, each card's button 1 + its place: more cards than codes would throw as it posts.
        assertTrue(1 + FelixNotifications.MAX_CARDS <= NoticeId.FelixMessage.codes.count())
    }

    @Test
    fun `those that stay keep the ids a notification may be showing under, and the calendar's questions leave Felix's`() {
        assertEquals(
            mapOf(
                "Sync" to 1001, "Readings" to 1002, "ReviewWork" to 1003, "ReplyWork" to 1004, "FelixWork" to 1005, "FreeTime" to 2003, "FreeTimeEnding" to 2004,
                "Protection" to 3001, "Briefing" to 4001, "CheckIn" to 4002, "Review" to 4003, "Summary" to 4004, "Assessment" to 4100, "FelixMessage" to 4200,
                "CalendarMove" to 4300, "CalendarQuestion" to 4400, "GmailWrite" to 4500, "ModelDropped" to 5001, "ModelKey" to 5002, "Felix" to 6000,
            ),
            NoticeId.entries.associate { it.name to it.id },
        )
    }
}
