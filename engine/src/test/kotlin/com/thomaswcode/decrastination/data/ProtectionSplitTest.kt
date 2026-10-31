package com.thomaswcode.decrastination.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/** protection.json split, and the pairs that aren't the same work to the tasks (4.13.0): each run at a start, before a store is read. */
class ProtectionSplitTest {
    private val dir = Files.createTempDirectory("split").toFile()

    private fun file(name: String) = File(dir, name)

    private fun stores() = Stores(dir, Keyless)

    /** protection.json as 4.12 left it: one waiting change's worth of its own, and the phone's three fields. */
    private val before = """{"pending":[],"overrideUntil":5000,"adminArmed":true,"codeLock":{"lastUsedStep":9},
        |"protection":{"problems":["The focus service is off"],"offSince":100,"checkedAt":200,"restartTries":2},
        |"forceActiveUntil":7000,"uptimeMark":{"boot":3,"elapsedMs":40}}""".trimMargin()

    @Test
    fun `the phone's own fields go to device_json and protection keeps the rest`() {
        file("protection.json").writeText(before)
        val stores = stores()
        val device = stores.device.value
        assertTrue(device.adminArmed)
        assertEquals(listOf("The focus service is off"), device.watchdog.problems)
        assertEquals(100L, device.watchdog.offSince)
        assertEquals(200L, device.watchdog.checkedAt)
        assertEquals(2, device.watchdog.restartTries)
        assertEquals(7000L, device.forceActiveUntil)
        val protection = stores.protection.value
        assertEquals(5000L, protection.overrideUntil)
        assertEquals(9L, protection.codeLock.lastUsedStep)
        assertEquals(3, protection.uptimeMark?.boot)
        assertEquals(2, protection.version)
        assertEquals(emptyList(), stores.problems)
    }

    @Test
    fun `what was there is kept beside it, and what's written has none of the phone's fields`() {
        file("protection.json").writeText(before)
        stores()
        val now = JsonStore.json.parseToJsonElement(file("protection.json").readText()) as JsonObject
        ProtectionSplit.FIELDS.keys.forEach { assertFalse(it in now, it) }
        assertEquals(before, file("protection.json.v1").readText())
    }

    @Test
    fun `run twice it gives what it gives once`() {
        file("protection.json").writeText(before)
        ProtectionSplit.MOVE.run(dir)
        val once = file("device.json").readText()
        ProtectionSplit.MOVE.run(dir)
        assertEquals(once, file("device.json").readText())
    }

    @Test
    fun `a stop before protection is trimmed leaves a device_json that's newer alone`() {
        file("protection.json").writeText(before)
        ProtectionSplit.MOVE.run(dir)
        // The app ran on after the move and the watchdog changed its finding, the trim never written.
        file("device.json").writeText("""{"watchdog":{"problems":[]},"version":1}""")
        val stores = stores()
        assertEquals(emptyList(), stores.device.value.watchdog.problems)
        assertFalse("adminArmed" in file("protection.json").readText())
    }

    @Test
    fun `a device_json that can't be read is set aside and said, the fields put in a fresh one`() {
        file("protection.json").writeText(before)
        file("device.json").writeText("not json")
        val stores = stores()
        assertTrue(stores.device.value.adminArmed)
        assertEquals(listOf("device.json"), stores.problems.map { it.file })
        assertTrue(dir.list()!!.any { it.startsWith("device.json.unreadable-") })
    }

    @Test
    fun `a phone with no protection_json has a device_json of defaults`() {
        val stores = stores()
        assertFalse(stores.device.value.adminArmed)
        assertNull(stores.device.value.forceActiveUntil)
        assertFalse(file("device.json").exists())
    }

    @Test
    fun `the pairs go to the tasks, whatever version the tasks are at, and leave the runtime`() {
        file("runtime.json").writeText("""{"notSame":["a|b","c|d"],"lastBackupAt":77,"version":3}""")
        file("tasks.json").writeText("""{"tasks":[],"version":4}""")
        val stores = stores()
        assertEquals(listOf("a|b", "c|d"), stores.tasks.value.notSame)
        assertEquals(5, stores.tasks.value.version)
        assertEquals(77L, stores.runtime.value.lastBackupAt)
        assertFalse("notSame" in file("runtime.json").readText())
        assertTrue("notSame" in file("runtime.json.v3").readText())
    }

    @Test
    fun `a run after a stop puts nothing older over the tasks' own pairs`() {
        file("runtime.json").writeText("""{"notSame":["a|b"],"version":3}""")
        file("tasks.json").writeText("""{"tasks":[],"notSame":["a|b","e|f"],"version":4}""")
        assertEquals(emptyList(), NotSameMigration.MOVE.run(dir))
        assertEquals(listOf("a|b", "e|f"), stores().tasks.value.notSame)
    }

    @Test
    fun `tasks that can't be read aren't written over, and the pairs stay in the runtime's copy`() {
        file("runtime.json").writeText("""{"notSame":["a|b"],"version":3}""")
        file("tasks.json").writeText("not json")
        val stores = stores()
        assertEquals(emptyList(), stores.tasks.value.notSame)
        assertTrue("a|b" in file("runtime.json.v3").readText())
        assertTrue(dir.list()!!.any { it.startsWith("tasks.json.unreadable-") })
    }

    @Test
    fun `pairs with no tasks file yet go into a new one, which every migration then brings on`() {
        file("runtime.json").writeText("""{"notSame":["a|b"],"version":3}""")
        val stores = stores()
        assertEquals(listOf("a|b"), stores.tasks.value.notSame)
        assertEquals(5, stores.tasks.value.version)
    }

    @Test
    fun `no pairs leave the tasks as they are`() {
        file("runtime.json").writeText("""{"notSame":[],"version":3}""")
        file("tasks.json").writeText("""{"tasks":[],"version":4}""")
        NotSameMigration.MOVE.run(dir)
        assertEquals("""{"tasks":[],"version":4}""", file("tasks.json").readText())
    }

    @Test
    fun `a task file from before the pairs' field moves up through every migration in turn`() {
        // A file of tasks version 1 with no runtime pairs at all: the plain path, from the oldest shape.
        file("tasks.json").writeText("""{"tasks":[]}""")
        assertEquals(5, stores().tasks.value.version)
    }

    private object Keyless : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }
}
