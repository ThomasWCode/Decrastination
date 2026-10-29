package com.thomaswcode.decrastination.notify

import android.app.Notification
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.HeldNotification
import com.thomaswcode.decrastination.data.HeldTap
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import java.io.File
import java.nio.file.Files
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

/** The routine notifications' ceiling, quiet hours and held list (LR-07), through the notifier, on a tray that keeps a list. */
class NotifierTest {
    private val dir: File = Files.createTempDirectory("notifier").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-14T18:00"), Fixtures.LONDON)
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    private val job = SupervisorJob()
    private val tray = FakeTray()
    private val notifier = Notifier(tray, runtime, settings, clock, CoroutineScope(job + Dispatchers.Default))

    /** A notification on [channel], saying [title] and opening the app's screen [screen]. */
    private fun notice(channel: String, title: String = "A title", screen: String = "intent:#Intent;end"): Notification =
        Notification().also { tray.reads[it] = Notifier.Read(channel, title, "Its words", listOf(HeldTap(null, false, 4003, screen))) }

    /** The count and the held list are written after the decision: waited for. */
    private fun settle() = runBlocking { job.children.toList().joinAll() }

    @Test
    fun `a routine one in quiet hours is held with its words and tap, not posted, while Felix's goes`() {
        clock.time = Fixtures.at("2026-10-14T23:30")
        notifier.post(NoticeId.Review, notice(Channels.WEEKLY, "This week's review"))
        notifier.post(NoticeId.FelixMessage, notice(Channels.FELIX))
        settle()
        assertEquals(listOf(4200), tray.posted.map { it.first })
        val held = runtime.value.heldNotifications.single()
        assertEquals(HeldNotification(Channels.WEEKLY, 4003, null, clock.time, "This week's review", "Its words", listOf(HeldTap(null, false, 4003, "intent:#Intent;end"))), held)
    }

    @Test
    fun `past four routine ones a day the rest are held, and each counted is on record`() {
        repeat(5) { notifier.post(NoticeId.CalendarQuestion, notice(Channels.CALENDAR, "Question $it"), tag = "event:$it") }
        settle()
        assertEquals(listOf("event:0", "event:1", "event:2", "event:3"), tray.posted.map { it.second })
        assertEquals(mapOf("2026-10-14" to 4), runtime.value.notifiedToday)
        assertEquals(listOf("event:4"), runtime.value.heldNotifications.map { it.tag })
    }

    @Test
    fun `the briefing isn't held by quiet hours, as its time is yours`() {
        clock.time = Fixtures.at("2026-10-14T06:45")
        notifier.post(NoticeId.Briefing, notice(Channels.BRIEFING))
        settle()
        assertEquals(listOf(4001), tray.posted.map { it.first })
    }

    @Test
    fun `what was held goes at the briefing, made again, and is let go, but not while notifications aren't allowed`() {
        clock.time = Fixtures.at("2026-10-14T23:30")
        notifier.post(NoticeId.Review, notice(Channels.WEEKLY))
        settle()
        tray.allowed = false
        runBlocking { notifier.postHeld() }
        assertEquals(1, runtime.value.heldNotifications.size, "kept for the next briefing, not lost")
        tray.allowed = true
        runBlocking { notifier.postHeld() }
        assertEquals(listOf(4003), tray.posted.map { it.first })
        assertEquals(listOf(4003), tray.rebuilt.map { it.id })
        assertTrue(runtime.value.heldNotifications.isEmpty())
    }

    @Test
    fun `nothing's posted or counted while notifications aren't allowed, and a channel switched off is neither held nor counted`() {
        tray.allowed = false
        notifier.post(NoticeId.Review, notice(Channels.WEEKLY))
        settle()
        assertTrue(tray.posted.isEmpty() && runtime.value.notifiedToday.isEmpty())
        tray.allowed = true
        tray.off += Channels.WEEKLY
        notifier.post(NoticeId.Review, notice(Channels.WEEKLY))
        settle()
        // Handed to Android, which shows nothing on a channel switched off.
        assertEquals(listOf(4003), tray.posted.map { it.first })
        assertTrue(runtime.value.notifiedToday.isEmpty() && runtime.value.heldNotifications.isEmpty())
    }

    @Test
    fun `a cancel goes by id and tag, and what's showing is told by its id`() {
        notifier.post(NoticeId.ModelDropped, notice(Channels.MODEL), tag = "teams:a")
        notifier.post(NoticeId.ModelDropped, notice(Channels.MODEL), tag = "teams:b")
        settle()
        notifier.cancel(NoticeId.ModelDropped, "teams:a")
        assertEquals(listOf<String?>("teams:b"), notifier.showing(NoticeId.ModelDropped))
    }

    @Test
    fun `one held back and then cancelled doesn't come back at the briefing, whether its hold was written yet or not, and one held again after does`() {
        clock.time = Fixtures.at("2026-10-14T23:30")
        notifier.post(NoticeId.ModelKey, notice(Channels.MODEL, "Claude has stopped working"))
        settle()
        assertEquals(listOf<String?>(null), notifier.heldTags(NoticeId.ModelKey))
        notifier.cancel(NoticeId.ModelKey)
        settle()
        assertTrue(runtime.value.heldNotifications.isEmpty())
        // Cancelled before its hold is written: the write, coming after, keeps nothing.
        notifier.post(NoticeId.ModelDropped, notice(Channels.MODEL), tag = "teams:a")
        notifier.cancel(NoticeId.ModelDropped, "teams:a")
        settle()
        assertTrue(runtime.value.heldNotifications.isEmpty())
        // Held again since its cancel: kept for the morning.
        notifier.post(NoticeId.ModelDropped, notice(Channels.MODEL), tag = "teams:a")
        settle()
        clock.time = Fixtures.at("2026-10-15T07:00")
        runBlocking { notifier.postHeld() }
        assertEquals(listOf<Pair<Int, String?>>(NoticeId.ModelDropped.id to "teams:a"), tray.posted)
    }

    /** A tray keeping a list: what's posted and showing, by id and tag. */
    private class FakeTray : Notifier.Tray {
        var allowed = true
        val off = mutableSetOf<String>()
        val reads = IdentityHashMap<Notification, Notifier.Read>()
        val posted = mutableListOf<Pair<Int, String?>>()
        val showing = mutableListOf<Pair<Int, String?>>()
        val rebuilt = mutableListOf<HeldNotification>()

        override fun allowed() = allowed
        override fun shown(channel: String) = allowed && channel !in off
        override fun notify(id: Int, tag: String?, notification: Notification) {
            posted += id to tag
            showing.removeAll { it == id to tag }
            showing += id to tag
        }
        override fun cancel(id: Int, tag: String?) {
            showing.removeAll { it == id to tag }
        }
        override fun cancelAll() = showing.clear()
        override fun active(): List<Pair<Int, String?>> = showing.toList()
        override fun read(notification: Notification): Notifier.Read? = reads[notification]
        override fun rebuilt(held: HeldNotification): Notification = Notification().also { rebuilt += held }
    }
}
