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
    fun `past its whole span the history keeps each store's last row of each hour, and the newest whatever their hour`() {
        var now = 0L
        val timed = SqliteBackend(db) { now }
        fun at(minute: Long, name: String, text: String) {
            now = minute * 60_000L
            timed.write(name, 1, text)
        }
        at(0, "a.json", "a0")
        at(5, "x.json", "x0")
        at(10, "a.json", "a1")
        at(15, "x.json", "x1")
        at(20, "a.json", "a2")
        at(60, "a.json", "b0")
        at(70, "a.json", "b1")
        at(240, "a.json", "c0")
        at(250, "a.json", "c1")
        at(260, "x.json", "x2")
        timed.trimHistory(before = 0L, keep = 1, thinBefore = 180 * 60_000L)
        assertEquals(listOf("a2", "b1", "c0", "c1"), historyOf("a.json").map { it.second })
        // Another store's rows in the same hour are its own.
        assertEquals(listOf("x1", "x2"), historyOf("x.json").map { it.second })
    }

    @Test
    fun `a copy set aside as unreadable isn't thinned, only let go of with its time`() {
        var now = 0L
        val timed = SqliteBackend(db) { now }
        timed.write("a.json", 1, "a0")
        assertNotNull(timed.setAside("a.json", 5 * 60_000L))
        now = 10 * 60_000L
        timed.write("a.json", 1, "a1")
        // A second set aside in the same hour is kept too.
        assertNotNull(timed.setAside("a.json", 15 * 60_000L))
        now = 300 * 60_000L
        timed.write("a.json", 1, "a2")
        timed.trimHistory(before = 0L, keep = 1, thinBefore = 180 * 60_000L)
        assertEquals(listOf("a0", "a1", "a1", "a2"), historyOf("a.json").map { it.second }, "both set aside stay beside the hour's last")
        timed.trimHistory(before = 180 * 60_000L, keep = 1)
        assertEquals(listOf("a2"), historyOf("a.json").map { it.second })
    }

    private fun tasks(tried: Long, read: Long, seen: Long, done: Long? = null, title: String = "An essay") =
        """{"tasks":[{"id":"t1","title":"$title","firstSeenAt":1,"lastSeenAt":$seen,"doneAt":${done ?: "null"}}],"sources":{"Teams":{"lastSuccessAt":$read,"lastAttemptAt":$tried}},"version":9}"""

    @Test
    fun `a write that moves a sync's times alone adds no history, and still changes the store's row`() {
        backend.write("tasks.json", 9, tasks(tried = 1, read = 1, seen = 1))
        backend.write("tasks.json", 9, tasks(tried = 2, read = 2, seen = 2))
        backend.write("tasks.json", 9, tasks(tried = 3, read = 2, seen = 3))
        assertEquals(tasks(tried = 3, read = 2, seen = 3), backend.read("tasks.json"))
        assertEquals(listOf(tasks(tried = 1, read = 1, seen = 1)), historyOf("tasks.json").map { it.second })
        assertEquals(3L, db.currentRevision(), "each write still a revision, for clients to hear of")
        backend.write("calendar-read.json", 1, """{"events":[],"from":1,"to":9}""")
        backend.write("calendar-read.json", 1, """{"events":[],"from":2,"to":9}""")
        backend.write("chat.json", 13, """{"messages":[],"readAt":1}""")
        backend.write("chat.json", 13, """{"messages":[],"readAt":2}""")
        assertEquals(1, historyOf("calendar-read.json").size)
        assertEquals(1, historyOf("chat.json").size)
    }

    @Test
    fun `one that changes anything else adds its row, a time that isn't a sync's among them`() {
        backend.write("tasks.json", 9, tasks(tried = 1, read = 1, seen = 1))
        backend.write("tasks.json", 9, tasks(tried = 2, read = 2, seen = 2, title = "An essay, longer"))
        backend.write("tasks.json", 9, tasks(tried = 2, read = 2, seen = 2, title = "An essay, longer", done = 5))
        backend.write("tasks.json", 10, tasks(tried = 2, read = 2, seen = 2, title = "An essay, longer", done = 5))
        backend.write("calendar-read.json", 1, """{"events":[],"from":1,"to":9}""")
        backend.write("calendar-read.json", 1, """{"events":[],"from":1,"to":10}""")
        assertEquals(4, historyOf("tasks.json").size, "the words, a task's done time, a new shape")
        assertEquals(2, historyOf("calendar-read.json").size, "how far it reaches is more than when it began")
        // Another store's times are its own: only the named ones are a sync's.
        backend.write("log.json", 1, """{"at":1}""")
        backend.write("log.json", 1, """{"at":2}""")
        assertEquals(2, historyOf("log.json").size)
    }

    @Test
    fun `a store set aside as unreadable is kept as now, and the write after it too`() {
        backend.write("tasks.json", 9, "not json")
        assertNotNull(backend.setAside("tasks.json", 5L))
        backend.write("tasks.json", 9, "not json")
        backend.write("tasks.json", 9, "still not json")
        assertEquals(listOf("not json", "not json", "not json", "still not json"), historyOf("tasks.json").map { it.second })
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

    @Test
    fun `a store emptied already takes no new revision for being emptied again`() {
        val db = Database(":memory:")
        val backend = SqliteBackend(db)
        backend.write("chat-archive-2026-10.json", 1, """{"messages":["words"]}""")
        backend.delete("chat-archive-2026-10.json", "{}")
        val revision = db.currentRevision()
        backend.delete("chat-archive-2026-10.json", "{}")
        assertEquals(revision, db.currentRevision())
        db.close()
    }
}
