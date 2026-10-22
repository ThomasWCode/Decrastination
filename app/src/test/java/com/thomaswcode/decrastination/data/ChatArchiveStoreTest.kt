package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Speaker
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The chat's archive a month a file (D-96): each read only when it's wanted, as one in a backup. */
class ChatArchiveStoreTest {
    private val dir: File = Files.createTempDirectory("archive").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun his(id: String, at: String) = ChatMessage(id, Speaker.Felix, "message $id", Fixtures.at(at))

    private val august = listOf(his("a1", "2026-08-03T10:00"), his("a2", "2026-08-30T10:00"))
    private val september = listOf(his("s1", "2026-09-01T09:00"), his("s2", "2026-09-20T10:00"))

    private fun files() = dir.list()!!.filter { it.startsWith("chat-archive") && it.endsWith(".json") }.sorted()

    @Test
    fun `messages moved go to their months' files, each once, and read back as one`() = runBlocking {
        val store = ChatArchiveStore(dir)
        assertFalse(store.holdsAny)
        store.add(august + september)
        assertEquals(listOf("chat-archive-2026-08.json", "chat-archive-2026-09.json"), files())
        // A move cut short and made again: nothing twice.
        store.add(september.takeLast(1))
        assertEquals(august + september, store.value.messages)
        assertEquals(august + september, ChatArchiveStore(dir).value.messages)
        assertTrue(ChatArchiveStore(dir).holdsAny)
    }

    @Test
    fun `scrolling back reads only the newest months it takes, and a reply's message the months back to it`() = runBlocking {
        ChatArchiveStore(dir).add(august + september)
        // August's file broken: read only when it's wanted.
        File(dir, "chat-archive-2026-08.json").writeText("{not json")
        val store = ChatArchiveStore(dir)
        val found = mutableListOf<JsonStore.Unreadable>()
        store.onProblem = { found += it }
        assertTrue(store.holdsAny)
        assertEquals(september, store.recent(2))
        assertEquals(listOf(september.last()), store.recent(1))
        assertEquals(september.first(), store.find("s1"))
        assertEquals(2, store.back("s1"))
        assertTrue(found.isEmpty() && store.problems.isEmpty())
        // Further back: August read, found unreadable, said so, and set aside.
        assertNull(store.find("a1"))
        assertEquals("chat-archive-2026-08.json", found.single().file)
        assertEquals(found, store.problems)
    }

    @Test
    fun `a move made again after a stop takes the window's copy, answered since, over the archive's`() = runBlocking {
        val store = ChatArchiveStore(dir)
        store.add(august)
        // The window's copy answered there before the move was made again.
        store.add(listOf(august.first().copy(answeredAt = 7L)))
        assertEquals(7L, ChatArchiveStore(dir).find("a1")?.answeredAt)
        assertEquals(2, ChatArchiveStore(dir).value.messages.size)
    }

    @Test
    fun `4_0_3's one file that can't be read is set aside and said, as any store's, and none of it left unread`() {
        File(dir, "chat-archive.json").writeText("{not json")
        val stores = Stores(dir, Reverse)
        val problem = stores.problems.single()
        assertEquals("chat-archive.json", problem.file)
        assertEquals("{not json", File(dir, problem.copy!!).readText())
        assertFalse(File(dir, "chat-archive.json").exists())
        assertFalse(stores.chatArchive.holdsAny)
    }

    /** A cipher for the secrets that only reverses the bytes. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    @Test
    fun `a month's file holds messages for the chat, however short, and clearing lets every month go unread`() = runBlocking {
        // Cut short to less than an empty archive written: still a month to show, and to say can't be read.
        File(dir, "chat-archive-2026-08.json").writeText("{")
        val store = ChatArchiveStore(dir)
        assertTrue(store.holdsAny)
        store.add(september)
        store.clear()
        assertTrue(files().isEmpty())
        assertFalse(store.holdsAny)
        assertTrue(store.problems.isEmpty())
    }

    @Test
    fun `clearing takes the copies kept of it, older shapes and files that couldn't be read, a month gone already among them`() = runBlocking {
        val store = ChatArchiveStore(dir)
        store.add(august + september)
        // Each kept as a month was brought from an older shape (JsonStore); July's month itself gone since.
        val shapes = listOf("chat-archive-2026-07.json.v3", "chat-archive-2026-08.json.v4", "chat-archive-2026-09.json.v4", "chat-archive.json.v2")
        // And set aside as they couldn't be read (D-94).
        val unreadable = listOf("chat-archive-2026-08.json.unreadable-20261021-010000", "chat-archive.json.unreadable-20261020-000000")
        val copies = (shapes + unreadable).map { File(dir, it).apply { writeText("{\"messages\":[]}") } }
        val others = listOf("chat.json.v5", "chat.json.unreadable-20261021-010000").map { File(dir, it).apply { writeText("{}") } }
        store.clear()
        assertTrue(files().isEmpty())
        assertTrue(copies.none { it.exists() })
        // Not the archive's: the window's own copies are its clear's (Outbox).
        assertTrue(others.all { it.exists() })
    }

    @Test
    fun `a month that can't be read and keeps nothing is let go once it's said, and a far year's month is still listed`() = runBlocking {
        File(dir, "chat-archive-2026-08.json").writeText("{")
        val store = ChatArchiveStore(dir)
        assertTrue(store.value.messages.isEmpty())
        assertEquals("chat-archive-2026-08.json", store.problems.single().file)
        store.keepRecovered()
        assertFalse(File(dir, "chat-archive-2026-08.json").exists())
        assertFalse(store.holdsAny)
        // A message dated past 9999 (a restore's), in a file named with the year's sign: read back, and cleared.
        val far = ChatMessage("far", Speaker.Felix, "far", 300_000_000_000_000L)
        store.add(listOf(far) + september)
        // After every four-digit year's month: the newest.
        assertEquals(september + far, ChatArchiveStore(dir).value.messages)
        assertEquals(listOf(far), ChatArchiveStore(dir).recent(1))
        store.clear()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `a clear waits for a month's first read, so the month it writes again in this shape doesn't come back`() {
        // August in an older shape: its first read keeps it as .v4 and writes it again in this one (JsonStore).
        File(dir, "chat-archive-2026-08.json").writeText(JsonStore.json.encodeToString(ChatArchive.serializer(), ChatArchive(august, version = 4)))
        // The month's clock is asked between its old shape kept and the new one written: the read held there, once.
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asked = AtomicInteger()
        val store = ChatArchiveStore(dir) {
            if (asked.getAndIncrement() == 0) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            Fixtures.at("2026-10-21T20:00")
        }
        val read = thread { store.find("a1") }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val clear = thread { runBlocking { store.clear() } }
        // Long enough for a clear that doesn't wait to be done before the read writes August again.
        clear.join(500)
        release.countDown()
        read.join(5_000)
        clear.join(5_000)
        assertTrue(dir.list()!!.none { it.startsWith("chat-archive") }, "left after the clear: ${dir.list()!!.toList()}")
        assertFalse(ChatArchiveStore(dir).holdsAny)
        // Nor kept by the store that read it: a message moved into August now is all it holds.
        val later = his("a3", "2026-08-15T10:00")
        runBlocking { store.add(listOf(later)) }
        assertEquals(listOf(later), ChatArchiveStore(dir).value.messages)
    }

    @Test
    fun `the recovery lets go only of a month it wrote back with nothing kept, not one that read whole`() = runBlocking {
        // Read whole, empty: nothing set aside, nothing written back (as where a read or a copy failed): it stays.
        File(dir, "chat-archive-2026-08.json").writeText(JsonStore.json.encodeToString(ChatArchive.serializer(), ChatArchive()))
        val store = ChatArchiveStore(dir)
        assertTrue(store.value.messages.isEmpty() && store.problems.isEmpty())
        store.keepRecovered()
        assertTrue(File(dir, "chat-archive-2026-08.json").exists())
    }

    @Test
    fun `a question of his answered from the archive is marked in its month`() = runBlocking {
        val store = ChatArchiveStore(dir)
        store.add(august + september)
        store.answered("a1", 5L)
        assertEquals(5L, ChatArchiveStore(dir).find("a1")?.answeredAt)
        assertNull(ChatArchiveStore(dir).find("s1")?.answeredAt)
    }

    @Test
    fun `all of it replaced, a restore or a clear, its months written again and those left empty gone`() = runBlocking {
        val store = ChatArchiveStore(dir)
        store.add(august + september)
        val changes = store.changes.value
        store.update { ChatArchive(september) }
        assertEquals(listOf("chat-archive-2026-09.json"), files())
        assertEquals(september, ChatArchiveStore(dir).value.messages)
        store.update { ChatArchive() }
        assertTrue(files().isEmpty())
        assertFalse(store.holdsAny)
        assertTrue(store.changes.value > changes)
    }

    @Test
    fun `the one file 4_0_3 kept it in is split into its months, then let go, and one that can't be read is left`() = runBlocking {
        val whole = File(dir, "chat-archive.json")
        whole.writeText(JsonStore.json.encodeToString(ChatArchive.serializer(), ChatArchive(august + september)))
        ChatArchiveStore.SPLIT.run(dir)
        assertFalse(whole.exists())
        assertEquals(august + september, ChatArchiveStore(dir).value.messages)
        // Cut short before it was let go: split again, each message once.
        whole.writeText(JsonStore.json.encodeToString(ChatArchive.serializer(), ChatArchive(august)))
        ChatArchiveStore.SPLIT.run(dir)
        assertEquals(august + september, ChatArchiveStore(dir).value.messages)
        // Unreadable: left as it is, none of it lost.
        whole.writeText("{not json")
        ChatArchiveStore.SPLIT.run(dir)
        assertEquals("{not json", whole.readText())
    }

    @Test
    fun `a store read lazily reads its file only when it's first wanted`() = runBlocking {
        val file = File(dir, "chat-archive-2026-08.json").apply { writeText("{not json, but longer than an empty archive written is}") }
        val found = mutableListOf<JsonStore.Unreadable>()
        val store = JsonStore(file, ChatArchive.serializer(), ::ChatArchive, lazily = true, migrations = ChatArchiveStore.MIGRATIONS).also { s -> s.onProblem = { found += it } }
        assertFalse(store.isLoaded)
        assertNull(store.problem)
        assertEquals(ChatArchive(), store.value)
        assertTrue(store.isLoaded)
        assertEquals(store.problem, found.single())
    }
}
