package com.thomaswcode.decrastination.protect

import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.Window
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotpTest {
    /** RFC 6238 appendix B's SHA-1 secret, "12345678901234567890". */
    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun `the RFC 6238 test vectors`() {
        assertEquals("94287082", Totp.code(secret, Totp.step(59_000L), digits = 8))
        assertEquals("07081804", Totp.code(secret, Totp.step(1_111_111_109_000L), digits = 8))
        assertEquals("14050471", Totp.code(secret, Totp.step(1_111_111_111_000L), digits = 8))
        assertEquals("89005924", Totp.code(secret, Totp.step(1_234_567_890_000L), digits = 8))
        assertEquals("69279037", Totp.code(secret, Totp.step(2_000_000_000_000L), digits = 8))
    }

    @Test
    fun `a code from the step before or after is accepted, not further`() {
        val now = 1_791_500_000_000L
        val step = Totp.step(now)
        assertEquals(step - 1, Totp.matchingStep(secret, Totp.code(secret, step - 1), now))
        assertEquals(step + 1, Totp.matchingStep(secret, Totp.code(secret, step + 1), now))
        assertNull(Totp.matchingStep(secret, Totp.code(secret, step - 2), now))
        assertNull(Totp.matchingStep(secret, "12345", now))
    }

    @Test
    fun `base32 round-trips, and the URI is what authenticators scan`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(secret))
        assertTrue(Totp.fromBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq").contentEquals(secret))
        val uri = Totp.uri(secret)
        assertTrue(uri.startsWith("otpauth://totp/Decrastination:Thomas%27s%20phone?secret=GEZDGNBV"), uri)
        assertTrue("&digits=6&period=30" in uri)
    }
}

class CodeLockTest {
    private val secret = "a fixed secret for the tests".toByteArray()
    private val now = 1_791_500_000_000L

    /** A code that matches none of the steps around [now]. */
    private val wrong: String = (0..999_999).asSequence().map { it.toString().padStart(6, '0') }.first { Totp.matchingStep(secret, it, now) == null }

    @Test
    fun `a right code is accepted once`() {
        val code = Totp.code(secret, Totp.step(now))
        val (first, lock) = CodeLock().attempt(secret, code, now)
        assertIs<CodeLock.Result.Accepted>(first)
        val (again, _) = lock.attempt(secret, code, now + 5_000)
        assertEquals(CodeLock.Result.Reused, again)
    }

    @Test
    fun `three wrong codes lock entry for an hour, and a restart doesn't reset it`() {
        var lock = CodeLock()
        var result: CodeLock.Result = CodeLock.Result.Reused
        repeat(3) {
            val (r, l) = lock.attempt(secret, wrong, now)
            result = r
            lock = l
        }
        assertEquals(CodeLock.Result.Locked(now + CodeLock.LOCK_MS), result)
        // Stored and loaded as the app's state is, a right code still waits for the hour.
        val reloaded = lock.copy()
        val (locked, _) = reloaded.attempt(secret, Totp.code(secret, Totp.step(now)), now + 1_000)
        assertIs<CodeLock.Result.Locked>(locked)
        val (later, _) = reloaded.attempt(secret, Totp.code(secret, Totp.step(now + CodeLock.LOCK_MS)), now + CodeLock.LOCK_MS)
        assertIs<CodeLock.Result.Accepted>(later)
    }

    @Test
    fun `a wrong code says how many tries are left`() {
        assertEquals(CodeLock.Result.Wrong(2), CodeLock().attempt(secret, wrong, now).first)
    }
}

class SettingsChangesTest {
    private val now = 1_791_500_000_000L
    private var ids = 0
    private fun newId() = "c${++ids}"

    @Test
    fun `every setting has a loosening rule`() {
        assertEquals(Settings.serializer().descriptor.elementNames.toSet(), SettingsChanges.fields)
    }

    @Test
    fun `unarmed, every change applies at once`() {
        val current = Settings()
        val proposed = current.copy(blockedApps = current.blockedApps - "com.google.android.youtube")
        val outcome = SettingsChanges.propose(current, proposed, emptyList(), now, ::newId)
        assertEquals(proposed, outcome.settings)
        assertTrue(outcome.pending.isEmpty())
    }

    @Test
    fun `armed, loosening waits 24 hours and tightening applies at once`() {
        val current = Settings(armed = true)
        val proposed = current.copy(
            blockedApps = current.blockedApps - "com.google.android.youtube",
            quietHours = Window(22 * 60, 7 * 60),
            weekdayBlockFromMin = 16 * 60,
        )
        val outcome = SettingsChanges.propose(current, proposed, emptyList(), now, ::newId)
        // Blocking from 16:00 tightens: at once. YouTube off the list and quiet from 22:00 loosen.
        assertEquals(16 * 60, outcome.settings.weekdayBlockFromMin)
        assertTrue("com.google.android.youtube" in outcome.settings.blockedApps)
        assertEquals(setOf("blockedApps", "quietHours"), outcome.pending.map { it.field }.toSet())
        assertTrue(outcome.pending.all { it.applyAt == now + 24 * 3_600_000L })
        assertEquals("Blocked apps: remove com.google.android.youtube", outcome.pending.single { it.field == "blockedApps" }.description)
        assertEquals("Quiet hours: 22:30–07:00 → 22:00–07:00", outcome.pending.single { it.field == "quietHours" }.description)
    }

    @Test
    fun `pending changes apply when their time comes, each only its own field`() {
        val current = Settings(armed = true)
        val loose = SettingsChanges.propose(current, current.copy(blockedApps = emptyList()), emptyList(), now, ::newId)
        val other = SettingsChanges.propose(loose.settings, loose.settings.copy(boxMin = 60), loose.pending, now + 1000, ::newId)
        assertEquals(2, other.pending.size)
        val early = SettingsChanges.applyDue(other.settings, other.pending, now + 23 * 3_600_000L)
        assertEquals(current.blockedApps, early.settings.blockedApps)
        val due = SettingsChanges.applyDue(other.settings, other.pending, now + 24 * 3_600_000L)
        assertEquals(emptyList(), due.settings.blockedApps)
        assertEquals(45, due.settings.boxMin)
        assertEquals(listOf("boxMin"), due.pending.map { it.field })
    }

    @Test
    fun `disarming waits, arming doesn't`() {
        val armed = SettingsChanges.propose(Settings(), Settings(armed = true), emptyList(), now, ::newId)
        assertTrue(armed.settings.armed)
        val disarm = SettingsChanges.propose(armed.settings, armed.settings.copy(armed = false), emptyList(), now, ::newId)
        assertTrue(disarm.settings.armed)
        assertEquals("Disarm protection", disarm.pending.single().description)
    }

    @Test
    fun `a newer change to a field replaces the pending one`() {
        val current = Settings(armed = true)
        val first = SettingsChanges.propose(current, current.copy(loosenDelayHours = 1), emptyList(), now, ::newId)
        val second = SettingsChanges.propose(first.settings, first.settings.copy(loosenDelayHours = 12), first.pending, now, ::newId)
        assertEquals(1, second.pending.size)
        assertEquals("12", second.pending.single().value.toString())
    }
}

class GuardRulesTest {
    private val labels = GuardRules.Labels("Decrastination", "Decrastination focus", "Decrastination watches which app is in front")

    private fun decide(packageName: String, vararg texts: String) = GuardRules.decide(packageName, texts.toList(), labels)

    @Test
    fun `the service's page, App info and the uninstall prompt are left`() {
        assertIs<GuardRules.Verdict.Back>(decide(GuardRules.SETTINGS, "Decrastination focus", "On", "Decrastination watches which app is in front. While…"))
        assertIs<GuardRules.Verdict.Back>(decide(GuardRules.SETTINGS, "App info", "Decrastination", "Open", "Uninstall", "Force stop"))
        assertIs<GuardRules.Verdict.Back>(decide("com.google.android.packageinstaller", "Uninstall this app?", "Decrastination", "Cancel", "Uninstall"))
    }

    @Test
    fun `its storage, device admin and Device Care pages are left`() {
        assertEquals(GuardRules.Verdict.Back("this app's storage"), decide(GuardRules.SETTINGS, "Storage", "Decrastination", "Clear data", "Clear cache"))
        assertEquals(
            GuardRules.Verdict.Back("this app's device admin page"),
            decide(GuardRules.SETTINGS, "Decrastination", "Deactivate this device admin app", "Activating this device admin app will allow…"),
        )
        assertEquals(GuardRules.Verdict.Back("this app in Device Care"), decide(GuardRules.DEVICE_CARE, "Decrastination", "Force stop"))
    }

    @Test
    fun `a reset's confirmation is left, the list of resets isn't`() {
        assertIs<GuardRules.Verdict.Back>(decide(GuardRules.SETTINGS, "Reset all settings", "Your settings will be reset…", "Reset settings"))
        assertEquals(
            GuardRules.Verdict.Leave,
            decide(GuardRules.SETTINGS, "Reset", "Reset all settings", "Reset network settings", "Reset accessibility settings", "Factory data reset"),
        )
    }

    @Test
    fun `other apps' pages and the rest of Settings stay usable`() {
        assertEquals(GuardRules.Verdict.Leave, decide(GuardRules.SETTINGS, "App info", "YouTube", "Open", "Uninstall", "Force stop"))
        assertEquals(GuardRules.Verdict.Leave, decide(GuardRules.SETTINGS, "Apps", "Decrastination", "Discord", "YouTube"))
        assertEquals(GuardRules.Verdict.Leave, decide("com.google.android.packageinstaller", "Do you want to update this app?", "Decrastination", "Update"))
        assertEquals(GuardRules.Verdict.Leave, decide("com.whatsapp", "Decrastination", "Uninstall"))
    }
}

class ProtectionCheckTest {
    private val pkg = "com.thomaswcode.decrastination"
    private val cls = "com.thomaswcode.decrastination.block.FocusService"

    @Test
    fun `the service is found in either form, among the others`() {
        val enabled = "com.thomaswcode.dictationapp/com.thomaswcode.dictationapp.X:$pkg/.block.FocusService"
        assertTrue(ProtectionCheck.isEnabled(enabled, pkg, cls))
        assertTrue(ProtectionCheck.isEnabled("$pkg/$cls", pkg, cls))
        assertEquals(false, ProtectionCheck.isEnabled("com.other/.X", pkg, cls))
        assertEquals(false, ProtectionCheck.isEnabled(null, pkg, cls))
    }

    @Test
    fun `repairs add the service once and take it off shortcuts, leaving the rest`() {
        val others = "com.thomaswcode.dictationapp/com.thomaswcode.dictationapp.X:com.anydesk.adcontrol.ad1/com.anydesk.adcontrol.AccService"
        assertEquals("$others:$pkg/$cls", ProtectionCheck.withService(others, pkg, cls))
        assertEquals("$others:$pkg/$cls", ProtectionCheck.withService("$others:$pkg/$cls", pkg, cls))
        assertEquals("com.android.server.accessibility/ReduceBrightColors", ProtectionCheck.withoutService("com.android.server.accessibility/ReduceBrightColors:$pkg/.block.FocusService", pkg, cls))
        assertEquals("", ProtectionCheck.withoutService("null", pkg, cls))
    }

    @Test
    fun `a service switched on but not running has stopped, armed or not`() {
        val crashed = ProtectionCheck.Report(serviceEnabled = true, accessibilityOn = true, onShortcuts = emptyList(), adminActive = true, canRepair = true, serviceRunning = false)
        assertEquals(listOf("The focus service has stopped: nothing is blocked"), ProtectionCheck.problems(crashed, armed = false))
        assertEquals(listOf("The focus service has stopped: nothing is blocked"), ProtectionCheck.problems(crashed, armed = true))
        // Switched off says off, whether or not it's running.
        assertEquals(listOf("The focus service is off: nothing is blocked"), ProtectionCheck.problems(crashed.copy(serviceEnabled = false), armed = false))
    }

    @Test
    fun `a stopped service is restarted after a minute's grace, at most once in ten minutes`() {
        val crashed = ProtectionCheck.Report(serviceEnabled = true, accessibilityOn = true, onShortcuts = emptyList(), adminActive = false, canRepair = true, serviceRunning = false)
        val t = 1_000_000_000L
        assertEquals(false, ProtectionCheck.shouldRestart(crashed, stoppedSince = null, restartedAt = null, now = t))
        assertEquals(false, ProtectionCheck.shouldRestart(crashed, stoppedSince = t, restartedAt = null, now = t + 30_000L))
        assertEquals(true, ProtectionCheck.shouldRestart(crashed, stoppedSince = t, restartedAt = null, now = t + 60_000L))
        assertEquals(false, ProtectionCheck.shouldRestart(crashed, stoppedSince = t, restartedAt = t + 60_000L, now = t + 5 * 60_000L))
        assertEquals(true, ProtectionCheck.shouldRestart(crashed, stoppedSince = t, restartedAt = t + 60_000L, now = t + 11 * 60_000L))
        assertEquals(t + 11 * 60_000L, ProtectionCheck.restartAt(crashed, stoppedSince = t, restartedAt = t + 60_000L))
        // A first restart found stopped again within a minute is tried once more at once; a
        // second isn't, and waits its ten minutes.
        val restarted = t + 60_000L
        assertEquals(restarted + 20L, ProtectionCheck.restartAt(crashed, stoppedSince = restarted + 20L, restartedAt = restarted, tries = 1))
        assertEquals(restarted + 10 * 60_000L, ProtectionCheck.restartAt(crashed, stoppedSince = restarted + 20L, restartedAt = restarted, tries = 2))
        assertEquals(restarted + 10 * 60_000L, ProtectionCheck.restartAt(crashed, stoppedSince = restarted + 2 * 60_000L, restartedAt = restarted, tries = 1))
        // Not without the permission, not when it's running, and not when it's switched off: that's
        // someone's choice, put right only once armed.
        assertEquals(false, ProtectionCheck.shouldRestart(crashed.copy(canRepair = false), t, null, t + 60_000L))
        assertEquals(false, ProtectionCheck.shouldRestart(crashed.copy(serviceRunning = true), t, null, t + 60_000L))
        assertEquals(false, ProtectionCheck.shouldRestart(crashed.copy(serviceEnabled = false), t, null, t + 60_000L))
    }

    @Test
    fun `unarmed, only the service being off is a problem`() {
        val report = ProtectionCheck.Report(serviceEnabled = true, accessibilityOn = true, onShortcuts = listOf("accessibility_button_targets"), adminActive = false, canRepair = true)
        assertEquals(emptyList(), ProtectionCheck.problems(report, armed = false))
        assertEquals(2, ProtectionCheck.problems(report, armed = true).size)
        assertEquals(1, ProtectionCheck.problems(report.copy(serviceEnabled = false), armed = false).size)
    }
}
