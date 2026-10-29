package com.thomaswcode.decrastination.block

import android.app.Notification
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.data.HeldNotification
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.SpeechMigration
import com.thomaswcode.decrastination.data.ZoomMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notifier
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReviewUiBlockTest {
    @Test
    fun `Day swipe Undo must reopen the own task its last piece just finished`() = runTest {
        val dir = Files.createTempDirectory("review-day-undo").toFile()
        try {
            val clock = FixedClock(Fixtures.at("2026-10-12T17:00"), Fixtures.LONDON)
            val cipher = object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain
                override fun decrypt(sealed: ByteArray) = sealed
            }
            val stores = Stores(dir, cipher, clock::now)
            val task = OwnTaskRules.create(OwnTask(title = "Review exercise", minutes = 15, dueAt = Fixtures.at("2026-10-12T23:59")), "review", clock.now())
            stores.tasks.update { it.copy(tasks = listOf(task)) }
            val focus = Focus(stores.tasks, stores.settings, stores.session, stores.learning, stores.protection, stores.log, clock,
                onOwnTaskDone = { id -> stores.tasks.update { state -> state.copy(tasks = state.tasks.map { if (it.id == id) it.copy(status = Status.Done, doneAt = clock.now()) else it }) } },
            ) { state, settings, now -> Planner.plan(Planner.Input(state.tasks, now, Fixtures.LONDON, settings)) }
            val piece = focus.plan().ordered.single()
            assertTrue(focus.keepSlot(piece.key, seenAt = clock.now()))
            assertEquals(Status.Done, stores.tasks.value.tasks.single().status)
            assertTrue(focus.unkeepSlot(piece.key, clock.now()), "DayScreen's exact Undo call refuses the task its swipe finished")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `canceling a held key warning must prevent its return at the briefing`() = runTest {
        val dir = Files.createTempDirectory("review-held-cancel").toFile()
        try {
            val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
            val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
            val held = HeldNotification(Channels.MODEL, NoticeId.ModelKey.id, null, 0L, "Key failed", "Old error", emptyList())
            runtime.update { it.copy(heldNotifications = listOf(held)) }
            val posted = mutableListOf<Int>()
            val tray = object : Notifier.Tray {
                override fun allowed() = true
                override fun shown(channel: String) = true
                override fun notify(id: Int, tag: String?, notification: Notification) { posted += id }
                override fun cancel(id: Int, tag: String?) = Unit
                override fun cancelAll() = Unit
                override fun active() = emptyList<Pair<Int, String?>>()
                override fun read(notification: Notification): Notifier.Read? = null
                override fun rebuilt(held: HeldNotification) = Notification()
            }
            val notifier = Notifier(tray, runtime, settings, FixedClock(Fixtures.at("2026-10-12T08:00"), Fixtures.LONDON), this)
            notifier.cancel(NoticeId.ModelKey)
            notifier.postHeld()
            assertTrue(posted.isEmpty(), "The resolved key warning was posted again: $posted")
        } finally {
            dir.deleteRecursively()
        }
    }
}
