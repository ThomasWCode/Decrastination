package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.data.TaskState
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Day's line on keeping in step with the server, once the phone sends commands, and the bar's mark for it ([SetupPage]). */
class SetupPageTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-11-03T18:00:00Z").toEpochMilli()
    private val going = SyncState(mode = SyncState.COMMANDS, lastSyncAt = now)

    @Test
    fun `the Day says nothing while mirroring, unpaired, or in step`() {
        assertNull(SetupPage.syncLine(going.copy(mode = SyncState.MIRROR, lastSyncAt = now - 30 * DAY), now - 5 * HOUR, true, now, zone))
        assertNull(SetupPage.syncLine(going, now - 5 * HOUR, false, now, zone))
        assertNull(SetupPage.syncLine(going, now - SetupPage.HELD_MS + 60_000, true, now, zone))
        assertNull(SetupPage.syncLine(going, null, true, now, zone))
        assertNull(SetupPage.syncLine(going.copy(lastSyncAt = now - 6 * DAY), null, true, now, zone))
    }

    @Test
    fun `a change held up for hours is said first, and a week with no exchange after it`() {
        assertEquals(
            "A change made here today 15:00 hasn't reached the server, and what's after it waits too: Setup says why.",
            SetupPage.syncLine(going, now - SetupPage.HELD_MS, true, now, zone),
        )
        val adrift = going.copy(lastSyncAt = now - 8 * DAY)
        assertEquals("Not in step with the server for 8 days: what's changed on the website isn't here. Setup says why.", SetupPage.syncLine(adrift, null, true, now, zone))
        assertTrue(SetupPage.syncLine(adrift, now - 4 * HOUR, true, now, zone).orEmpty().startsWith("A change made here"))
    }

    @Test
    fun `the bar's mark shows while the Day says it`() {
        val read = TaskState(sources = Source.SYNCED.associateWith { SourceStatus(lastSuccessAt = 1) })
        assertFalse(SetupPage.attention(read, gmailGivenUp = false, keyProblem = false, watchdog = false, unreadable = false))
        assertTrue(SetupPage.attention(read, gmailGivenUp = false, keyProblem = false, watchdog = false, unreadable = false, adrift = true))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
    }
}
