package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.SecretStore
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.data.TaskState
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

class SqliteBackendTest {
    private val db = Database(":memory:")
    private val backend = SqliteBackend(db) { 1_000L }

    @Serializable
    private data class Note(val text: String = "", val version: Int = 1)

    private fun historyOf(name: String): List<Pair<Long, String>> = db.transactionless { c ->
        c.prepareStatement("SELECT revision, json FROM store_history WHERE name = ? ORDER BY rowid").use { q ->
            q.setString(1, name)
            q.executeQuery().use { r -> buildList { while (r.next()) add(r.getLong(1) to r.getString(2)) } }
        }
    }

    @Test
    fun `a store with no row has no text, and one written reads back, its history beside it`() {
        assertNull(backend.read("a.json"))
        backend.write("a.json", 1, "first")
        backend.write("a.json", 1, "second")
        assertEquals("second", backend.read("a.json"))
        assertEquals(listOf(1L to "first", 2L to "second"), historyOf("a.json"))
        assertEquals(listOf("a.json"), backend.names())
    }

    @Test
    fun `every store a transaction changes takes one revision, and the next the next`() {
        db.transaction {
            backend.write("a.json", 1, "a")
            backend.write("b.json", 1, "b")
        }
        assertEquals(1L, db.currentRevision())
        backend.write("a.json", 1, "a again")
        assertEquals(2L, db.currentRevision())
    }

    @Test
    fun `a transaction that fails leaves nothing of it, the revision neither`() {
        backend.write("a.json", 1, "kept")
        assertFailsWith<IllegalStateException> {
            db.transaction {
                backend.write("a.json", 1, "lost")
                backend.write("b.json", 1, "lost too")
                error("the command failed")
            }
        }
        assertEquals("kept", backend.read("a.json"))
        assertNull(backend.read("b.json"))
        assertEquals(1L, db.currentRevision())
        assertEquals(1, historyOf("a.json").size)
    }

    @Test
    fun `a copy set aside is found again while it holds what the store holds`() {
        backend.write("a.json", 1, "broken")
        val copy = assertNotNull(backend.setAside("a.json", 5L))
        assertEquals(copy, backend.alreadyAside("a.json"))
        backend.write("a.json", 1, "something else")
        assertNull(backend.alreadyAside("a.json"))
    }

    @Test
    fun `the copies let go are the history of the stores picked, and the stores stay`() {
        backend.write("a.json", 1, "a")
        backend.write("b.json", 1, "b")
        backend.letGoOfCopies { it == "a.json" }
        assertEquals(emptyList(), historyOf("a.json"))
        assertEquals(1, historyOf("b.json").size)
        assertEquals("a", backend.read("a.json"))
    }

    @Test
    fun `a store deleted is left empty, at a revision of its own, for clients to hear of`() {
        backend.write("a.json", 1, "a")
        val before = db.currentRevision()
        backend.delete("a.json", "empty")
        assertEquals("empty", backend.read("a.json"))
        assertEquals(before + 1, db.currentRevision())
    }

    @Test
    fun `deleting writes nothing where nothing's held, or in mirror mode`() {
        backend.delete("a.json", "empty")
        assertFalse(backend.holds("a.json"))
        backend.write("b.json", 1, "b")
        backend.writable = false
        backend.delete("b.json", "empty")
        assertEquals("b", backend.read("b.json"))
    }

    @Test
    fun `the history older than its time goes, but a store's newest rows`() {
        repeat(30) { backend.write("a.json", 1, "text $it") }
        backend.trimHistory(before = 2_000L, keep = 20)
        val left = historyOf("a.json")
        assertEquals(20, left.size)
        assertEquals("text 29", left.last().second)
        // None older than the cut goes where it's newer than the time.
        backend.trimHistory(before = 500L, keep = 1)
        assertEquals(20, historyOf("a.json").size)
    }

    @Test
    fun `a store over rows reads and writes as one over its file, and a broken one is set aside`() = runBlocking {
        val store = JsonStore(backend, "note.json", Note.serializer(), ::Note)
        assertEquals("", store.value.text)
        store.update { it.copy(text = "kept") }
        assertTrue("kept" in backend.read("note.json")!!)
        assertEquals("kept", JsonStore(backend, "note.json", Note.serializer(), ::Note).value.text)
        backend.write("note.json", 1, "not json")
        val broken = JsonStore(backend, "note.json", Note.serializer(), ::Note)
        assertEquals("", broken.value.text)
        assertEquals("note.json", broken.problem?.file)
    }

    @Test
    fun `an older shape read from rows comes up through the stores' migrations`() {
        // Each store of the oldest shape written as rows, as a client on an old build would send it.
        val oldest = Fixtures.file("stores").listFiles { f -> f.isDirectory }!!.minByOrNull { it.name }!!
        oldest.listFiles()!!.filter { it.name == "tasks.json" }.forEach { backend.write(it.name, 1, it.readText()) }
        val dir = Files.createTempDirectory("server").toFile()
        val stores = Stores(backend, SecretStore(File(dir, "secrets.bin"), Plain))
        assertEquals(TaskState().version, stores.tasks.version)
        assertTrue(stores.tasks.value.tasks.isNotEmpty())
        assertNull(stores.tasks.problem)
    }

    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }
}
