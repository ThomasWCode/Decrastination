package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.shareOut
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import android.app.Notification
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notifier
import com.thomaswcode.decrastination.ui.SettingsEffects
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.learn.Daily
import com.thomaswcode.decrastination.learn.ReviewInput
import java.time.LocalDate
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder

class AstraReviewRegressionTest {
    @Test
    fun `a partial current week review does not count last weeks subjects`() {
        val clock = FixedClock.at("2026-10-29T12:00:00Z")
        val doneAt = FixedClock.at("2026-10-23T17:00:00Z").now()
        val old = CompletionRecord(
            taskId = "teams:previous-week", title = "Finished last week", source = Source.Teams,
            kind = Kind.Homework, className = "Previous week only", estimateMin = 30, workedMin = 30,
            firstSeenAt = doneAt - 60_000L, doneAt = doneAt,
        )
        val week = Daily.checkInWeek(clock.now(), clock.zone(), Settings().checkInMin)
        assertEquals("2026-10-26", week)
        val input = ReviewInput.describe(ActivityLog(completions = listOf(old)), Calibration(), Settings(), clock.now(), clock.zone(), week)
        assertTrue("Previous week only" !in input, "A prior-week subject entered this week's review: $input")
    }

    @Test
    fun `the time picker rejects a time already gone today`() {
        val clock = FixedClock.at("2026-10-29T20:00:00Z")
        val task = TaskItem("own:example", Source.Own, "example", "Example work", kind = Kind.Homework,
            firstSeenAt = clock.now(), lastSeenAt = clock.now())
        assertEquals(Planner.PinProblem.DayGone, Planner.pinProblem(task, clock.now() - 3 * 3_600_000L, clock.now(), clock.zone(), 30))
    }

    @Test
    fun `a cancelled held alert cannot reappear in the morning`() = runBlocking {
        val dir = Files.createTempDirectory("astra-notice").toFile()
        try {
            val stores = Stores(dir, object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain
                override fun decrypt(sealed: ByteArray) = sealed
            })
            stores.runtime.update { it.copy(heldNotifications = listOf(HeldNotification(Channels.MODEL, NoticeId.ModelKey.id, at = 1, title = "Claude has stopped working", text = "An old key problem"))) }
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
            val notifier = Notifier(tray, stores.runtime, stores.settings, FixedClock.at("2026-10-29T07:00:00Z"), this)
            notifier.cancel(NoticeId.ModelKey)
            notifier.postHeld()
            assertTrue(posted.isEmpty(), "Cancelled alert was posted again: $posted")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `margin preview agrees with the one day deadline margin`() {
        val line = SettingsEffects.line("marginDays", Settings(marginDays = 1), null, SchoolCalendar(), LocalDate.parse("2026-10-26"))!!
        assertTrue("Wednesday" in line, "Planner subtracts one day from Thursday; preview says: $line")
    }

    @Test
    fun `requested minutes remain the total with uneven steps`() {
        val result = shareOut(10, listOf(100, 1, 1))
        assertEquals(10, result.sum(), "Requested 10, allocated $result")
    }

    @Test
    fun `cancelling a store save cannot lose a committed field on the next save`() = runBlocking {
        val dir = Files.createTempDirectory("astra-cancel").toFile()
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val block = AtomicBoolean(false)
            val delegate = Settings.serializer()
            val serializer = object : KSerializer<Settings> by delegate {
                override fun serialize(encoder: Encoder, value: Settings) {
                    if (block.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    delegate.serialize(encoder, value)
                }
            }
            val file = dir.resolve("settings.json")
            val store = JsonStore(file, serializer, ::Settings, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
            block.set(true)
            val saving = launch(Dispatchers.Default) { store.update { it.copy(felixName = "A saved name") } }
            check(entered.await(10, TimeUnit.SECONDS))
            saving.cancel()
            release.countDown()
            saving.join()
            assertEquals("A saved name", JsonStore.json.decodeFromString(delegate, file.readText()).felixName)
            store.update { it.copy(boxMin = 45) }
            assertEquals("A saved name", JsonStore.json.decodeFromString(delegate, file.readText()).felixName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a retained instruction does not make a later nightly backup unrestorable`() {
        val clock = FixedClock.at("2026-10-29T12:00:00Z")
        val old = clock.now() - 731L * 24 * 3_600_000
        val instruction = Instruction("old-pin", "A time for a task", at = old, state = InstructionStatus.Applied,
            changes = listOf(Change(ChangeType.AtTime, taskId = "own:example", time = old, date = "2024-10-28", startMin = 720)))
        val backup = Backup(exportedAt = clock.now(), settings = Settings(), log = ActivityLog(), instructions = listOf(instruction))
        assertNull(Backups.problem(backup))
    }

    @Test
    fun `chat arriving while reset saves its copy is not silently erased`() = runBlocking {
        val dir = Files.createTempDirectory("astra-reset").toFile()
        try {
            val cipher = object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain
                override fun decrypt(sealed: ByteArray) = sealed
            }
            val stores = Stores(dir, cipher)
            val clock = FixedClock.at("2026-10-29T12:00:00Z")
            var saved = ""
            val arrived = ChatMessage("during-save", Speaker.You, "A message arriving during the reset copy", clock.now())
            val folder = object : BackupService.Folder {
                override val available = true
                override fun write(name: String, text: String): Boolean {
                    saved = text
                    runBlocking { stores.chat.update { it.copy(messages = it.messages + arrived) } }
                    return true
                }
                override fun names() = emptyList<String>()
                override fun delete(name: String) = true
            }
            val service = BackupService(stores, clock, this, folder, dir.resolve("copies"), "review",
                changeSettings = { change -> stores.settings.update(change); Unit }, applying = Mutex(),
                layInstructions = {}, layTaskOverrides = {}, schedule = {}, reread = {}, cancelFelix = {})
            service.reset(Reset.Chat)
            val copy = Backups.decode(saved, stores.shapes)!!
            assertTrue(arrived in stores.chat.value.messages || arrived in copy.chat.messages,
                "Message is absent from the live chat and the reset copy")
        } finally {
            dir.deleteRecursively()
        }
    }
}
