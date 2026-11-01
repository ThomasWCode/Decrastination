package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.ClearChat
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The chat as the server draws it for the website (docs/spec/website.md): the window, what's read of the archive, and what's unread. */
class ChatRouteTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("chat").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun his(id: String, daysAgo: Long) = ChatMessage(id, Speaker.Felix, "Words of $id", now - daysAgo * day)

    /** [count] of his in the archive, the first the oldest, a month's worth of days back. */
    private suspend fun archived(count: Int) = graph.stores.chatArchive.add((1..count).map { his("old$it", 300L - it) })

    @Test
    fun `the archive's offered before it's read, and what's asked for of it is shown over the window`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        assertFalse(graph.chat(0, null).more, "with nothing in it")
        archived(3)
        val closed = graph.chat(0, null)
        assertTrue(closed.more)
        assertEquals(listOf("new"), closed.rows.map { it.id })
        val two = graph.chat(2, null)
        assertEquals(listOf("old2", "old3", "new"), two.rows.map { it.id })
        assertTrue(two.more)
        assertFalse(graph.chat(3, null).more)
    }

    @Test
    fun `a cleared chat offers nothing of its archive, though the months' rows stay`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        archived(3)
        graph.applier.apply(CommandBatch(listOf(Command("gone", now, ClearChat))), Client.WEBSITE)
        assertFalse(graph.chat(0, null).more)
    }

    @Test
    fun `a quote reaches back as far as its message, and no further than asked for one in the window`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        archived(3)
        val reached = graph.chat(0, "old2")
        assertEquals(2, reached.earlier)
        assertEquals(listOf("old2", "old3", "new"), reached.rows.map { it.id })
        assertEquals(0, graph.chat(0, "new").earlier)
        assertEquals(0, graph.chat(0, "no such message").earlier)
        assertEquals(1, graph.chat(1, "old3").earlier, "what's asked for stands where that's further")
    }

    @Test
    fun `a reply says what it answers from past the pages read`() = runBlocking<Unit> {
        val reply = ChatMessage("reply", Speaker.You, "An answer", now, replyTo = "old1", answeredAt = now)
        graph.stores.chat.update { it.copy(messages = listOf(reply)) }
        archived(3)
        assertEquals("Words of old1", graph.chat(0, null).rows.single().quote?.line)
    }

    @Test
    fun `a page asked for past the first comes out whole, with nothing below zero taken for it`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        assertEquals(0, graph.chat(-5, null).earlier)
    }

    @Test
    fun `however far a page asks to go back, it's what the archive holds`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        archived(3)
        assertEquals(listOf("old1", "old2", "old3", "new"), graph.chat(Int.MAX_VALUE, null).rows.map { it.id })
    }

    @Test
    fun `what's unread is counted as the phone counts it`() = runBlocking<Unit> {
        assertEquals(0, graph.notices().unread)
        graph.stores.chat.update { it.copy(messages = listOf(his("read", 2), his("one", 1), his("two", 0).copy(answeredAt = now), his("three", 0)), readAt = now - 36 * 3_600_000L) }
        assertEquals(2, graph.notices().unread)
        assertEquals(2, graph.chat(0, null).unread)
        assertEquals("one", graph.chat(0, null).firstUnread)
    }

    @Test
    fun `Claude's on by this box's key once it's asked here, and by the phone's while it's asked there`() = runBlocking<Unit> {
        val keyed = ServerGraph(db, dir, Plain, "test", claudeKey = "a made-up key")
        keyed.stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = false) }
        assertNull(keyed.chat(0, null).claudeOff, "the phone's key has nothing to do with it")
        graph.stores.settings.update { it.copy(aiEnabled = true, aiKeyActive = true) }
        assertNotNull(graph.chat(0, null).claudeOff, "no key on the box")
        graph.modeForATest("mirror")
        assertNull(graph.chat(0, null).claudeOff, "the phone's has it")
        graph.stores.settings.update { it.copy(aiEnabled = false) }
        assertNotNull(graph.chat(0, null).claudeOff, "and switched off, it's off")
    }

    @Test
    fun `mirroring, there's nothing to press`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(his("new", 0))) }
        assertTrue(graph.chat(0, null).canChange)
        graph.modeForATest("mirror")
        val view = graph.chat(0, null)
        assertFalse(view.canChange)
        assertNull(view.clear)
    }
}
