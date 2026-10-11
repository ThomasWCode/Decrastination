package com.thomaswcode.decrastination.data

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    private val dir: File = Files.createTempDirectory("stores").toFile()

    @Test
    fun `a store saves and loads its state`() = runTest {
        val file = File(dir, "settings.json")
        JsonStore(file, Settings.serializer(), ::Settings).update { it.copy(ankiTextbook = 2) }
        assertEquals(2, JsonStore(file, Settings.serializer(), ::Settings).value.ankiTextbook)
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `an unreadable file is set aside and the state starts afresh`() {
        val file = File(dir, "tasks.json").apply { writeText("{not json") }
        val store = JsonStore(file, TaskState.serializer(), ::TaskState, clock = { AT })
        assertEquals(TaskState(), store.value)
        val problem = store.problem!!
        assertEquals("tasks.json.unreadable-20261011-083000", problem.copy)
        assertEquals("{not json", File(dir, problem.copy!!).readText())
        assertFalse(problem.kept)
        // What it started from is the file now: the next start reads it, with nothing to say.
        assertNull(JsonStore(file, TaskState.serializer(), ::TaskState, clock = { AT + 1000 }).problem)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("tasks.json.unreadable-") }!!.size)
        // A file that reads is no problem.
        assertNull(JsonStore(File(dir, "other.json").apply { writeText("{}") }, TaskState.serializer(), ::TaskState).problem)
    }

    @Test
    fun `of a file that doesn't read whole, each field that reads on its own is kept`() {
        // An app update that can no longer read one field (here, a list that isn't one) loses only it:
        // protection stays armed, the textbook stays 3.
        val file = File(dir, "settings.json").apply { writeText("""{"armed": true, "ankiTextbook": 3, "blockedApps": "not a list"}""") }
        val store = JsonStore(file, Settings.serializer(), ::Settings, clock = { AT })
        assertTrue(store.value.armed)
        assertEquals(3, store.value.ankiTextbook)
        assertEquals(Settings().blockedApps, store.value.blockedApps)
        assertTrue(store.problem!!.kept)
        // Kept, and written back: still armed at the next start, and nothing more to say.
        val again = JsonStore(file, Settings.serializer(), ::Settings, clock = { AT + 1000 })
        assertTrue(again.value.armed)
        assertNull(again.problem)
    }

    @Test
    fun `a file that can't be read at all starts from what's safer, where that's given`() {
        val file = File(dir, "settings.json").apply { writeText("garbage") }
        val store = JsonStore(file, Settings.serializer(), ::Settings, unreadable = { Settings(armed = true) }, clock = { AT })
        assertTrue(store.value.armed)
        // A file that's simply not there yet is the default, as ever.
        assertFalse(JsonStore(File(dir, "new.json"), Settings.serializer(), ::Settings, unreadable = { Settings(armed = true) }).value.armed)
    }

    @Test
    fun `a second unreadable file doesn't overwrite the first, and only the newest few are kept`() {
        val file = File(dir, "log.json")
        for (i in 0 until 5) {
            file.writeText("bad $i")
            JsonStore(file, ActivityLog.serializer(), ::ActivityLog, clock = { AT + i * 1000L })
        }
        val copies = dir.listFiles { f -> f.name.startsWith("log.json.unreadable-") }.orEmpty().map { it.name }.sorted()
        assertEquals(JsonStore.COPIES_KEPT, copies.size)
        assertEquals("bad 4", File(dir, copies.last()).readText())
    }

    @Test
    fun `unknown keys from another version are ignored`() {
        val file = File(dir, "settings.json").apply { writeText("""{"ankiTextbook": 3, "fromTheFuture": true}""") }
        assertEquals(3, JsonStore(file, Settings.serializer(), ::Settings).value.ankiTextbook)
    }

    /** Stands in for the Keystore: reverses the bytes, so the file is at least not the plain JSON. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    @Test
    fun `secrets are kept encrypted, and blank removes one`() = runTest {
        val file = File(dir, "secrets.bin")
        val store = SecretStore(file, Reverse)
        store.put(mapOf(Secret.GmailAddress to "me@example.com", Secret.GmailAppPassword to "abcd efgh"))
        assertFalse("abcd efgh" in file.readText())
        val again = SecretStore(file, Reverse)
        assertEquals("abcd efgh", again[Secret.GmailAppPassword])
        assertEquals(setOf(Secret.GmailAddress, Secret.GmailAppPassword), again.present.value)
        again.put(Secret.GmailAppPassword, " ")
        assertNull(SecretStore(file, Reverse)[Secret.GmailAppPassword])
    }

    @Test
    fun `secrets that can't be decrypted read as none`() {
        val file = File(dir, "secrets.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
        }
        val store = SecretStore(file, broken, { AT }, retryWaitMs = 0)
        assertTrue(store.present.value.isEmpty())
        // Told apart from none saved, and a copy set aside before a save can overwrite it.
        assertEquals("secrets.bin.unreadable-20261011-083000", store.problem!!.copy)
        assertTrue(File(dir, "secrets.bin.unreadable-20261011-083000").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        // Left where it is, for a keystore that comes back; said once, not again at the next start.
        assertTrue(file.exists())
        assertNull(SecretStore(file, broken, { AT + 1000 }, retryWaitMs = 0).problem)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("secrets.bin.unreadable-") }!!.size)
        assertNull(SecretStore(File(dir, "none.bin"), broken).problem)
    }

    @Test
    fun `a keystore that fails for a moment loses nothing`() = runTest {
        val file = File(dir, "secrets.bin")
        SecretStore(file, Reverse).put(Secret.GmailAppPassword, "abcd efgh")
        // Fails once, then works: read on the second try, nothing set aside, nothing said.
        var calls = 0
        val flaky = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = Reverse.encrypt(plain)
            override fun decrypt(sealed: ByteArray): ByteArray = if (calls++ == 0) throw java.security.KeyStoreException("busy") else Reverse.decrypt(sealed)
        }
        val store = SecretStore(file, flaky, retryWaitMs = 0)
        assertEquals("abcd efgh", store[Secret.GmailAppPassword])
        assertNull(store.problem)
        // Failing at every try that start, then back: the file was left, so the next start reads it all.
        val down = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = Reverse.encrypt(plain)
            override fun decrypt(sealed: ByteArray): ByteArray = throw java.security.KeyStoreException("down")
        }
        assertTrue(SecretStore(file, down, retryWaitMs = 0).present.value.isEmpty())
        assertEquals("abcd efgh", SecretStore(file, Reverse)[Secret.GmailAppPassword])
    }

    @Test
    fun `a file that can't be read at all, rather than decoded, isn't written over`() {
        // A directory where the file should be: reading it fails, as an I/O error would.
        val file = File(dir, "runtime.json").apply { mkdirs() }
        val store = JsonStore(file, Settings.serializer(), ::Settings, clock = { AT })
        assertEquals(Settings(), store.value)
        assertTrue(file.isDirectory)
    }

    private companion object {
        /** 11 Oct 2026, 08:30 UTC. */
        val AT = java.time.Instant.parse("2026-10-11T08:30:00Z").toEpochMilli()
    }
}
