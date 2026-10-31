package com.thomaswcode.decrastination.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class FileBackendTest {
    private val dir = Files.createTempDirectory("backend").toFile()
    private val backend = FileBackend(dir)

    @Serializable
    private data class Note(val text: String = "", val version: Int = 1)

    @Test
    fun `a store with no file has no text, and one written reads back whole with no temporary file left`() {
        assertNull(backend.read("a.json"))
        backend.write("a.json", 1, "first")
        backend.write("a.json", 1, "second")
        assertEquals("second", backend.read("a.json"))
        assertEquals(listOf("a.json"), dir.list()!!.toList())
    }

    @Test
    fun `a store that can't be read is a failure, not none`() {
        File(dir, "a.json").mkdir()
        assertFailsWith<java.io.IOException> { backend.read("a.json") }
    }

    @Test
    fun `it lists the stores and none of the copies beside them`() {
        backend.write("a.json", 1, "{}")
        backend.write("b.json", 1, "{}")
        backend.keepShape("a.json", 1, 1_000L)
        backend.setAside("b.json", 1_000L)
        assertEquals(setOf("a.json", "b.json"), backend.names().toSet())
    }

    @Test
    fun `an older shape is kept once, dated as it's kept`() {
        backend.write("a.json", 2, "old")
        backend.keepShape("a.json", 1, 5_000L)
        backend.write("a.json", 2, "newer")
        backend.keepShape("a.json", 1, 6_000L)
        val kept = File(dir, "a.json.v1")
        assertEquals("old", kept.readText())
        assertEquals(5_000L, kept.lastModified())
    }

    @Test
    fun `a copy set aside is found again while it holds what the store holds`() {
        backend.write("a.json", 1, "broken")
        val copy = assertNotNull(backend.setAside("a.json", 1_000L))
        assertEquals(copy, backend.alreadyAside("a.json"))
        backend.write("a.json", 1, "something else")
        assertNull(backend.alreadyAside("a.json"))
    }

    @Test
    fun `the copies are let go and the store stays`() {
        backend.write("a.json", 1, "{}")
        backend.keepShape("a.json", 1, 1_000L)
        backend.setAside("a.json", 1_000L)
        backend.letGoOfCopies { it == "a.json" }
        assertEquals(listOf("a.json"), dir.list()!!.toList())
    }

    @Test
    fun `only the copies of the stores picked are let go`() {
        backend.write("a.json", 1, "{}")
        backend.write("b.json", 1, "{}")
        backend.keepShape("a.json", 1, 1_000L)
        backend.keepShape("b.json", 1, 1_000L)
        backend.letGoOfCopies { it == "a.json" }
        assertEquals(setOf("a.json", "b.json", "b.json.v1"), dir.list()!!.toSet())
    }

    @Test
    fun `an older shape goes once it's old enough, and a copy that couldn't be read doesn't go with it`() {
        backend.write("a.json", 1, "{}")
        backend.keepShape("a.json", 1, 1_000L)
        val aside = assertNotNull(backend.setAside("a.json", 1_000L))
        backend.letGoOfOldShapes(999L) { true }
        assertTrue(File(dir, "a.json.v1").exists())
        backend.letGoOfOldShapes(1_000L) { it == "a.json" }
        assertFalse(File(dir, "a.json.v1").exists())
        assertTrue(File(dir, aside).exists())
    }

    @Test
    fun `a store is held till it's deleted, and one that can't be deleted is emptied`() {
        backend.write("a.json", 1, "{}")
        assertTrue(backend.holds("a.json"))
        backend.delete("a.json", "empty")
        assertFalse(backend.holds("a.json"))
        assertFalse(backend.holds("never.json"))
    }

    @Test
    fun `a store read again takes what its backend holds now`() = runBlocking {
        val store = JsonStore(backend, "note.json", Note.serializer(), ::Note)
        backend.write("note.json", 1, """{"text":"written elsewhere"}""")
        assertEquals("", store.value.text)
        assertEquals("written elsewhere", store.reload().text)
        assertEquals("written elsewhere", store.value.text)
    }

    @Test
    fun `a store over a backend reads and writes as one over its file`() = runBlocking {
        val store = JsonStore(backend, "note.json", Note.serializer(), ::Note)
        assertEquals("", store.value.text)
        store.update { it.copy(text = "kept") }
        assertTrue("kept" in backend.read("note.json")!!)
        assertEquals("kept", JsonStore(File(dir, "note.json"), Note.serializer(), ::Note).value.text)
        assertFalse(File(dir, "note.json.tmp").exists())
    }
}
