package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.data.Settings
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BlocklistTest {

    @Test
    fun `address bars are read as addresses, searches aren't`() {
        assertEquals(Blocklist.Address("m.youtube.com", "/watch"), Blocklist.address("m.youtube.com/watch?v=abc"))
        assertEquals(Blocklist.Address("www.twitch.tv", "/"), Blocklist.address("https://www.twitch.tv"))
        assertEquals(Blocklist.Address("bbc.co.uk", "/iplayer/episode/x"), Blocklist.address("bbc.co.uk/iplayer/episode/x"))
        assertNull(Blocklist.address("how to revise physics"))
        assertNull(Blocklist.address("youtube"))
        assertNull(Blocklist.address(""))
    }

    @Test
    fun `a site matches its subdomains and sub-paths, not look-alikes`() {
        val sites = Blocklist.SITES
        assertEquals("youtube.com", Blocklist.blockedSite(Blocklist.address("m.youtube.com/watch")!!, sites))
        assertEquals("bbc.co.uk/iplayer", Blocklist.blockedSite(Blocklist.address("www.bbc.co.uk/iplayer/live")!!, sites))
        assertNull(Blocklist.blockedSite(Blocklist.address("www.bbc.co.uk/bitesize")!!, sites))
        // A path entry is that path and what's under it, not a longer name sharing its start.
        assertEquals("bbc.co.uk/iplayer", Blocklist.blockedSite(Blocklist.address("bbc.co.uk/iplayer")!!, sites))
        assertNull(Blocklist.blockedSite(Blocklist.address("bbc.co.uk/iplayer-news/today")!!, sites))
        assertNull(Blocklist.blockedSite(Blocklist.address("bbc.co.uk/iplayer2")!!, sites))
        assertNull(Blocklist.blockedSite(Blocklist.address("notyoutube.com")!!, sites))
        assertNull(Blocklist.blockedSite(Blocklist.address("docs.google.com/document")!!, sites))
    }

    @Test
    fun `messaging, school and study apps are never on the list`() {
        val never = listOf("com.whatsapp", "com.microsoft.teams", "com.Slack", "com.ichi2.anki", "com.google.android.gm", "com.teamsassignments.widget")
        never.forEach { assertEquals(false, it in Blocklist.APPS, it) }
    }
}

class BlockPolicyTest {
    private val settings = Settings()

    private fun decide(
        at: String,
        pressure: Boolean = true,
        creditMs: Long = 0,
        session: FocusSession? = null,
        overrideUntil: String? = null,
        forceUntil: String? = null,
    ) = BlockPolicy.decide(
        BlockPolicy.Input(
            now = Fixtures.at(at),
            zone = LONDON,
            settings = settings,
            pressure = pressure,
            creditLeftMs = creditMs,
            session = session,
            overrideUntil = overrideUntil?.let(Fixtures::at),
            forceActiveUntil = forceUntil?.let(Fixtures::at),
        ),
    )

    @Test
    fun `quiet hours and school hours are never blocked`() {
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.Quiet), decide("2026-10-09T23:00"))
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.Quiet), decide("2026-10-10T06:59"))
        // Friday 9 Oct is a school day: free until 16:45.
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.SchoolHours), decide("2026-10-09T12:00"))
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.SchoolHours), decide("2026-10-09T16:44"))
    }

    @Test
    fun `from 16 45 on a school day, and all day at the weekend, work due soon blocks`() {
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.DueSoon), decide("2026-10-09T16:45"))
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.DueSoon), decide("2026-10-10T07:00"))
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.DueSoon), decide("2026-10-10T22:29"))
    }

    @Test
    fun `while work is due soon, free time can't be spent`() {
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.DueSoon), decide("2026-10-10T12:00", creditMs = 600_000))
    }

    @Test
    fun `with nothing due soon, earned time is spent, and without it the app is blocked`() {
        assertEquals(BlockPolicy.Verdict.Spend, decide("2026-10-10T12:00", pressure = false, creditMs = 600_000))
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.NoFreeTime), decide("2026-10-10T12:00", pressure = false))
    }

    @Test
    fun `a focus session blocks whatever the hour`() {
        val session = FocusSession("teams:t", "Statics Prep", null, 25, Fixtures.at("2026-10-09T23:00"))
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.Session), decide("2026-10-09T23:10", pressure = false, session = session))
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.Quiet), decide("2026-10-09T23:30", session = session))
    }

    @Test
    fun `a parent's unblock lets everything through until it ends`() {
        assertEquals(BlockPolicy.Verdict.Allow(BlockPolicy.Reason.Override), decide("2026-10-10T12:00", overrideUntil = "2026-10-10T13:00"))
        assertIs<BlockPolicy.Verdict.Block>(decide("2026-10-10T13:00", overrideUntil = "2026-10-10T13:00"))
    }

    @Test
    fun `testing from a PC can make it act as within blocking hours`() {
        assertEquals(BlockPolicy.Verdict.Block(BlockPolicy.Reason.DueSoon), decide("2026-10-09T23:00", forceUntil = "2026-10-09T23:10"))
    }
}

class CreditTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun `free time is earned, spent, and gone at midnight`() {
        val credit = Credit().earn(today, 15.0).spend(today, 5 * 60_000L)
        assertEquals(10 * 60_000L, credit.leftMs)
        assertEquals(0L, credit.on(today.plusDays(1)).leftMs)
        assertEquals(0L, credit.spend(today, 60 * 60_000L).leftMs)
    }

    @Test
    fun `a minute of free time for three of work, a completion's capped at half an hour`() {
        assertEquals(15.0, Credit.forSession(45, 3))
        assertEquals(10.0, Credit.forCompletion(30, 3))
        assertEquals(30.0, Credit.forCompletion(300, 3))
        assertEquals(0.0, Credit.forCompletion(0, 3))
    }
}

class TeamsAutoSyncTest {
    private val settings = Settings()

    private fun due(at: String, state: TeamsAutoSync.State = TeamsAutoSync.State(), syncedAt: String? = "2026-10-09T07:00", unlocked: Boolean = false) =
        TeamsAutoSync.due(Fixtures.at(at), LONDON, settings, state, syncedAt?.let(Fixtures::at), unlocked)

    @Test
    fun `the first unlock after 16 45 syncs`() {
        assertEquals(TeamsAutoSync.Trigger.FirstUnlock, due("2026-10-09T16:50", syncedAt = "2026-10-09T16:00", unlocked = true))
        assertNull(due("2026-10-09T16:50", syncedAt = "2026-10-09T16:00", unlocked = false))
        assertNull(due("2026-10-09T16:50", TeamsAutoSync.State(firstUnlockDay = "2026-10-09"), syncedAt = "2026-10-09T16:00", unlocked = true))
    }

    @Test
    fun `Teams synced just now needs no sync on unlock`() {
        assertNull(due("2026-10-09T16:50", syncedAt = "2026-10-09T16:40", unlocked = true))
    }

    @Test
    fun `every three hours, within the allowed hours`() {
        assertEquals(TeamsAutoSync.Trigger.Every3Hours, due("2026-10-10T10:00", syncedAt = "2026-10-10T07:00"))
        assertNull(due("2026-10-10T09:59", syncedAt = "2026-10-10T07:00"))
        assertNull(due("2026-10-10T10:00", TeamsAutoSync.State(lastOfferedAt = Fixtures.at("2026-10-10T09:00")), syncedAt = "2026-10-10T07:00"))
    }

    @Test
    fun `never at night, nor in school hours`() {
        assertNull(due("2026-10-09T23:30", syncedAt = "2026-10-09T12:00", unlocked = true))
        assertNull(due("2026-10-09T12:00", syncedAt = "2026-10-09T07:00", unlocked = true))
        assertNull(due("2026-10-10T06:30", syncedAt = "2026-10-09T12:00", unlocked = true))
    }

    @Test
    fun `delay 5 min asks again then, and only then`() {
        val delayed = TeamsAutoSync.delayed(TeamsAutoSync.State(), Fixtures.at("2026-10-09T17:00"))
        assertNull(due("2026-10-09T17:04", delayed, syncedAt = "2026-10-09T12:00"))
        assertEquals(TeamsAutoSync.Trigger.Delayed, due("2026-10-09T17:05", delayed, syncedAt = "2026-10-09T12:00"))
    }

    @Test
    fun `an offer after 16 45 counts as the day's first-unlock sync`() {
        val state = TeamsAutoSync.offered(TeamsAutoSync.State(), Fixtures.at("2026-10-10T17:00"), LONDON, settings)
        assertEquals("2026-10-10", state.firstUnlockDay)
        assertEquals(Fixtures.at("2026-10-10T17:00"), state.lastOfferedAt)
        assertEquals(null, TeamsAutoSync.offered(TeamsAutoSync.State(), Fixtures.at("2026-10-10T10:00"), LONDON, settings).firstUnlockDay)
    }
}
