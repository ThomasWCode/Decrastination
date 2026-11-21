package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.EmptyTimetable
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sync.protocol.AnswerTimetable
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

    private val held = EmptyTimetable(Timetable(null, emptyList()), now - 3 * HOUR)

    @Test
    fun `an empty timetable held back is asked about in its own row, with both answers, till you say`() {
        assertNull(SetupPage.timetable(null, now, zone))
        assertEquals(
            SetupPage.Line(
                "Power Planner's timetable",
                false,
                "Power Planner's has had no lessons since today 15:00, which may be a read gone wrong, so the last one is kept. Keep it, or use the empty one?",
                listOf(SetupPage.Answer("Keep the last one", AnswerTimetable(keep = true)), SetupPage.Answer("Use the empty one", AnswerTimetable(keep = false))),
            ),
            SetupPage.timetable(held, now, zone),
        )
        assertEquals("Power Planner's timetable came back with no lessons: the last one is kept till you say", SetupPage.timetableTrouble(held))
    }

    @Test
    fun `once you've kept the last one it's said plainly, with the way to take the empty one after all`() {
        assertEquals(
            SetupPage.Line(
                "Power Planner's timetable",
                null,
                "Kept as it was before today 15:00, as you said: Power Planner's has no lessons.",
                listOf(SetupPage.Answer("Use the empty one", AnswerTimetable(keep = false))),
            ),
            SetupPage.timetable(held.copy(kept = true), now, zone),
        )
        assertNull(SetupPage.timetableTrouble(held.copy(kept = true)))
        assertNull(SetupPage.timetableTrouble(null))
    }

    @Test
    fun `the bar's mark shows while an empty timetable's asked about, not once it's kept`() {
        val read = TaskState(sources = Source.SYNCED.associateWith { SourceStatus(lastSuccessAt = 1) })
        assertTrue(SetupPage.attention(read.copy(emptyTimetable = held), gmailGivenUp = false, keyProblem = false, watchdog = false, unreadable = false))
        assertFalse(SetupPage.attention(read.copy(emptyTimetable = held.copy(kept = true)), gmailGivenUp = false, keyProblem = false, watchdog = false, unreadable = false))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
    }
}
