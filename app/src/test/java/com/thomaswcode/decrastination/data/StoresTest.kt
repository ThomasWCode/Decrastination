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
        val store = JsonStore(file, TaskState.serializer(), ::TaskState)
        assertEquals(TaskState(), store.value)
        assertTrue(File(dir, "tasks.json.unreadable").exists())
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
        assertTrue(SecretStore(file, broken).present.value.isEmpty())
    }
}
