package com.thomaswcode.decrastination.protect

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Uptime
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SettingsMigration
import com.thomaswcode.decrastination.data.ProtectionState
import com.thomaswcode.decrastination.data.RuntimeSplit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.learn.ReviewInput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Protection's flows on temporary stores (docs/plan_v4), unarmed and armed: a change at once
 * or waiting its hours, counted on the uptime clock; a parent code used, and what it allows; a waiting
 * change cancelled; the weekly review's changes at once; and the device admin kept in step with arming.
 * The rules themselves are `ProtectTest`'s.
 */
class ProtectionTest {
    private val dir: File = Files.createTempDirectory("protection").toFile()
    private val clock = FixedClock(Fixtures.at("2026-10-19T16:00"), LONDON, up = Uptime(boot = 1, elapsedMs = 1_000_000))
    private val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), ::Settings, migrations = listOf(SettingsMigration.SETTINGS))
    private val runtime = JsonStore(File(dir, "runtime.json"), RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME))
    private val protectionState = JsonStore(File(dir, "protection.json"), ProtectionState.serializer(), ::ProtectionState)
    private val secrets = SecretStore(File(dir, "secrets.bin"), Reverse, clock = { clock.now() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether the device admin is on, as Android would say; and how often it was given up. */
    @Volatile private var admin = false
    private val removed = AtomicInteger()

    private val protection = Protection(settings, protectionState, secrets, clock, scope, adminActive = { admin }) {
        removed.incrementAndGet()
        admin = false
    }

    /** Your dad's authenticator's secret, as a code from it is made. */
    private val secret = "a made-up secret for the tests".toByteArray()

    private fun code(at: Long = clock.now()) = Totp.code(secret, Totp.step(at))

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun arm() = runBlocking { settings.update { it.copy(armed = true) } }

    private fun uptimeOn(ms: Long) {
        clock.up = clock.up!!.copy(elapsedMs = clock.up!!.elapsedMs + ms)
    }

    @Test
    fun `unarmed, a change that loosens blocking applies at once`() = runBlocking {
        protection.changeSettings { it.copy(freeAllowanceMin = 90) }
        assertEquals(90, settings.value.freeAllowanceMin)
        assertEquals(emptyList(), protection.pending)
        assertNull(protectionState.value.uptimeMark)
    }

    @Test
    fun `armed, a loosening waits its hours on the uptime clock, a tightening doesn't, and moving the date on hurries nothing`() = runBlocking {
        arm()
        protection.changeSettings { it.copy(freeAllowanceMin = 90) }
        // Waiting: shown as asked for, not yet in force; its count started now.
        assertEquals(60, settings.value.freeAllowanceMin)
        assertEquals(listOf("freeAllowanceMin"), protection.pending.map { it.field })
        assertEquals(90, protection.asked.freeAllowanceMin)
        assertEquals(clock.up, protectionState.value.uptimeMark)
        // A tightening at once, the waiting change left as it was.
        protection.changeSettings { it.copy(photoFromMin = 10) }
        assertEquals(10, settings.value.photoFromMin)
        assertEquals(listOf("freeAllowanceMin"), protection.pending.map { it.field })
        // The date two days on, the phone an hour on: an hour counted, no more.
        clock.time += 2 * DAY_MS
        uptimeOn(3_600_000L)
        protection.applyDueChanges(force = true)
        assertEquals(60, settings.value.freeAllowanceMin)
        assertEquals(3_600_000L, protection.pending.single().waitedMs)
        // The rest of its 24 hours on: it applies, and nothing's left to count.
        uptimeOn(23 * 3_600_000L)
        protection.applyDueChanges()
        assertEquals(90, settings.value.freeAllowanceMin)
        assertEquals(emptyList(), protection.pending)
        assertNull(protectionState.value.uptimeMark)
    }

    @Test
    fun `a waiting change cancelled goes, and what it would loosen stays`() = runBlocking {
        arm()
        protection.changeSettings { it.copy(workMinPerFreeMin = 2) }
        protection.cancelChange(protection.pending.single().id)
        assertEquals(emptyList(), protection.pending)
        assertNull(protectionState.value.uptimeMark)
        assertEquals(3, settings.value.workMinPerFreeMin)
    }

    @Test
    fun `a parent code is checked and used up, applying a waiting change or unblocking for an hour`() = runBlocking {
        assertEquals("No parent code is set", protection.useParentCode(code()))
        secrets.put(Secret.TotpSecret, Totp.base32(secret))
        arm()
        protection.changeSettings { it.copy(workMinPerFreeMin = 2) }
        val waiting = protection.pending.single()
        // Right: used up, and the change it was for applied now.
        assertNull(protection.useParentCode(code()))
        protection.applyNow(waiting.id)
        assertEquals(2, settings.value.workMinPerFreeMin)
        assertEquals(emptyList(), protection.pending)
        // The same code again, and a wrong one.
        assertEquals("That code has been used: wait for the next one", protection.useParentCode(code()))
        val wrong = (0..999_999).asSequence().map { it.toString().padStart(Totp.DIGITS, '0') }.first { Totp.matchingStep(secret, it, clock.now()) == null }
        assertEquals("Wrong code: 2 tries left", protection.useParentCode(wrong))
        // The next code his app shows, for an hour's unblock.
        clock.time += Totp.STEP_MS
        val until = clock.now() + 3_600_000L
        assertNull(protection.useParentCode(code(), unblockUntil = until))
        assertEquals(until, protectionState.value.overrideUntil)
    }

    @Test
    fun `a new parent code is kept with the code that checked it used up, and none is none`() = runBlocking {
        val step = Totp.step(clock.now())
        protection.newParentCode(secret, step)
        assertEquals(Totp.base32(secret), secrets[Secret.TotpSecret])
        assertEquals(step, protectionState.value.codeLock.lastUsedStep)
        // The code checked can't be used again; the next one his app shows works.
        assertEquals("That code has been used: wait for the next one", protection.useParentCode(code()))
        clock.time += Totp.STEP_MS
        assertNull(protection.useParentCode(code()))
        protection.noParentCode()
        assertNull(secrets[Secret.TotpSecret])
    }

    @Test
    fun `the weekly review's changes apply at once while armed, in place of a wait of yours on the same setting`() = runBlocking {
        arm()
        // Waiting: your looser ratio, and an app taken off the list.
        protection.changeSettings { it.copy(workMinPerFreeMin = 2, blockedApps = it.blockedApps.drop(1)) }
        assertEquals(setOf("workMinPerFreeMin", "blockedApps"), protection.pending.map { it.field }.toSet())
        protection.applyReviewChanges(listOf(ReviewInput.Change("workMinPerFreeMin", 4, ""), ReviewInput.Change("softMinPerDay", 90, "")))
        assertEquals(4, settings.value.workMinPerFreeMin)
        assertEquals(90, settings.value.softMinPerDay)
        // The app keeps its wait, and its count.
        assertEquals(listOf("blockedApps"), protection.pending.map { it.field })
        assertEquals(Settings().blockedApps, settings.value.blockedApps)
        assertEquals(clock.up, protectionState.value.uptimeMark)
    }

    @Test
    fun `the device admin goes once disarmed, and at a start with an arming left part-way`() = runBlocking {
        // The admin given, then the app stopped mid-wizard: given up at the start.
        admin = true
        protection.start()
        until("the part-way arming is undone") { removed.get() == 1 }
        assertTrue(!protectionState.value.adminArmed)
        // Armed: on record; disarmed (its wait over), given up.
        admin = true
        settings.update { it.copy(armed = true) }
        until("armed is on record") { protectionState.value.adminArmed }
        settings.update { it.copy(armed = false) }
        until("the admin is given up") { removed.get() == 2 && !protectionState.value.adminArmed }
    }

    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "never: $what" }
            Thread.sleep(10)
        }
    }

    /** A cipher for the secrets that only reverses the bytes: no keystore on a PC. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
