package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.saveable.SaverScope
import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.Blocklist
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.protect.Protection
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The Settings screen's state and actions without Compose (docs/plan_v4, Stage 6b): your edits over
 * what it showed, a change made elsewhere laid under them, the save through the real Protection on
 * temporary stores, the fields that don't parse, the lists' edits, and the state kept through a turn
 * of the phone.
 */
class SettingsScreenStateTest {
    private val dir: File = Files.createTempDirectory("settings-screen").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON, up = Uptime(boot = 1, elapsedMs = 1_000_000))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings)
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val protectionState = JsonStore(File(dir, "protection.json"), ProtectionState.serializer(), ::ProtectionState)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val protection = Protection(settings, protectionState, SecretStore(File(dir, "secrets.bin"), Reverse, clock = { clock.now() }), clock, scope, adminActive = { false }) {}

    /** How often the reminders' alarms were set again after a save. */
    private var scheduled = 0

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a change made elsewhere shows at once, your edits laid over it`() = runBlocking {
        val screen = SettingsScreenState(protection.asked)
        screen.draft = screen.draft.copy(boxMin = 40)
        assertTrue(screen.canSave)
        // Setup puts a key to use meanwhile.
        settings.update { it.copy(aiKeyActive = true) }
        screen.shown(protection.asked)
        assertTrue(screen.draft.aiKeyActive)
        assertEquals(40, screen.draft.boxMin)
        assertEquals(protection.asked, screen.base)
        // Shown again as it is, nothing moves.
        screen.shown(protection.asked)
        assertEquals(40, screen.draft.boxMin)
    }

    @Test
    fun `saved unarmed, your change applies and the screen begins again from what's asked for`() = runBlocking {
        val screen = SettingsScreenState(protection.asked)
        screen.typed("boxMin", "40")
        screen.draft = screen.draft.copy(boxMin = 40)
        screen.save(protection) { scheduled++ }
        assertEquals(40, settings.value.boxMin)
        assertEquals("Saved.", screen.message)
        assertEquals(1, scheduled)
        assertEquals(protection.asked, screen.base)
        assertEquals(screen.base, screen.draft)
        assertEquals(emptyMap(), screen.raw)
        assertFalse(screen.canSave)
    }

    @Test
    fun `saved armed, only what you changed goes, a loosening waits, and the message says how long`() = runBlocking {
        settings.update { it.copy(armed = true) }
        val screen = SettingsScreenState(protection.asked)
        screen.draft = screen.draft.copy(freeAllowanceMin = 90)
        // A change made elsewhere after the screen began, and not yet shown on it: kept, not saved over.
        settings.update { it.copy(slotBreakMin = 10) }
        screen.save(protection) { scheduled++ }
        assertEquals(10, settings.value.slotBreakMin)
        assertEquals(60, settings.value.freeAllowanceMin)
        assertEquals("Saved. 1 change loosening blocking waits 24 hours, shown below.", screen.message)
        // Shown as asked for, its wait under the list.
        assertEquals(90, screen.draft.freeAllowanceMin)
        assertEquals(10, screen.draft.slotBreakMin)
        assertFalse(screen.canSave)
    }

    @Test
    fun `a field that doesn't parse keeps its words and holds the save`() {
        val screen = SettingsScreenState(Settings())
        screen.draft = screen.draft.copy(marginDays = 2)
        screen.typed("boxMin", "4o")
        screen.valid("boxMin", false)
        assertEquals("4o", screen.raw["boxMin"])
        assertFalse(screen.canSave)
        screen.valid("boxMin", true)
        assertTrue(screen.canSave)
    }

    @Test
    fun `the lines, the sites and the subjects as typed`() {
        val screen = SettingsScreenState(Settings())
        screen.profileTyped("  Works best before dinner \n\n  Plays the cello  ")
        assertEquals(listOf("Works best before dinner", "Plays the cello"), screen.draft.profileLines)
        assertEquals("  Works best before dinner \n\n  Plays the cello  ", screen.raw[SettingsScreenState.PROFILE])
        screen.sitesTyped("Example.com\nexample.com\n\n news.example.org/sport ")
        assertEquals(listOf("example.com", "news.example.org/sport"), screen.draft.blockedSites)
        screen.subject("12Ph", "Physics")
        val tasks = listOf(task("a", className = "12Ma"), task("b", className = " "), task("c", className = null))
        assertEquals(listOf("12Ma", "12Ph"), screen.classCodes(tasks))
        assertEquals(mapOf("12Ph" to "Physics"), screen.draft.classMap)
        // Blank leaves it the code.
        screen.subject("12Ph", " ")
        assertEquals(emptyMap(), screen.draft.classMap)
    }

    @Test
    fun `a browser blocked outright isn't watched, and let off is watched again where it can be`() {
        val screen = SettingsScreenState(Settings())
        val chrome = Blocklist.CHECKED_BROWSERS.first()
        screen.browserBlocked(chrome, true)
        assertTrue(chrome in screen.draft.blockedBrowsers && chrome !in screen.draft.checkedBrowsers)
        screen.browserBlocked(chrome, false)
        assertTrue(chrome !in screen.draft.blockedBrowsers && chrome in screen.draft.checkedBrowsers)
        // One whose address bar can't be read is only let off.
        val firefox = Blocklist.BLOCKED_BROWSERS.first()
        screen.browserBlocked(firefox, false)
        assertTrue(firefox !in screen.draft.blockedBrowsers && firefox !in screen.draft.checkedBrowsers)
    }

    @Test
    fun `the apps' rows, blocked first as the screen began, then by name, the search finding one`() {
        val zed = SettingsScreenState.App("Zed", "com.example.zed")
        val alpha = SettingsScreenState.App("Alpha", "com.example.alpha")
        val own = SettingsScreenState.App("Decrastination", "com.thomaswcode.decrastination")
        val chrome = SettingsScreenState.App("Chrome", Blocklist.CHECKED_BROWSERS.first())
        val asked = Settings(blockedApps = listOf(zed.packageName, Blocklist.APPS.first()))
        val screen = SettingsScreenState(asked)
        val installed = listOf(alpha, own, chrome, zed)
        // The app itself and the browsers aren't rows; a known one not installed keeps one.
        val missing = SettingsScreenState.App("${Blocklist.NAMES.getValue(Blocklist.APPS.first())} (not installed)", Blocklist.APPS.first())
        val rows = screen.appRows(installed, asked, own.packageName)
        assertEquals(listOf(missing, zed), rows.take(2))
        assertTrue(alpha in rows && own !in rows && chrome !in rows)
        // Switched off, a row doesn't jump: the order is the screen's as it began.
        screen.appBlocked(zed.packageName, false)
        assertFalse(zed.packageName in screen.draft.blockedApps)
        assertEquals(rows, screen.appRows(installed, asked, own.packageName))
        screen.appBlocked(alpha.packageName, true)
        assertTrue(alpha.packageName in screen.draft.blockedApps)
        screen.find = " ALP "
        assertEquals(listOf(alpha), screen.appRows(installed, asked, own.packageName))
        screen.find = "example.zed"
        assertEquals(listOf(zed), screen.appRows(installed, asked, own.packageName))
    }

    @Test
    fun `kept through a turn of the phone, but for what the last save said`() = runBlocking {
        val screen = SettingsScreenState(protection.asked)
        screen.draft = screen.draft.copy(boxMin = 40)
        screen.save(protection) { scheduled++ }
        assertEquals("Saved.", screen.message)
        screen.draft = screen.draft.copy(boxMin = 50)
        screen.typed("marginDays", "x")
        screen.valid("marginDays", false)
        screen.section = "planning"
        screen.find = "tube"
        val saved = with(SettingsScreenState.SAVER) { SaverScope { true }.save(screen) }!!
        val again = SettingsScreenState.SAVER.restore(saved)!!
        assertEquals(screen.base, again.base)
        assertEquals(screen.draft, again.draft)
        assertEquals(mapOf("marginDays" to "x"), again.raw)
        assertEquals(setOf("marginDays"), again.invalid)
        assertEquals("planning", again.section)
        assertEquals("tube", again.find)
        assertNull(again.message)
        // The list of pages, kept as such.
        screen.section = null
        assertNull(SettingsScreenState.SAVER.restore(with(SettingsScreenState.SAVER) { SaverScope { true }.save(screen) }!!)!!.section)
    }

    private fun task(id: String, className: String?) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = null, kind = Kind.Homework,
        className = className, firstSeenAt = clock.time, lastSeenAt = clock.time,
    )

    /** A cipher for the secrets that only reverses the bytes: no keystore on a PC. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
