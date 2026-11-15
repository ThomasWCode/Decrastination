package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SendMessage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A calendar question of Felix's answered on the website: the calendar's answers are the phone's alone (`calendar.json`), so
 * the server keeps the answer as your quick change about the event, which reaches the phone as an instruction; one answered on
 * the phone is kept there by its own run of the command, and the server keeps nothing of it.
 */
class WebsiteEventAnswerTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("event-answer").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val now = System.currentTimeMillis()

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun question(id: String, key: String, title: String) = ChatMessage(
        id, Speaker.Felix, "$title: how much of your day does that take?", now, About(eventKey = key, eventTitle = title, eventStart = now + 86_400_000L),
        kind = MessageKind.EventQuestion, byApp = true, suggestedReplies = listOf("all of it", "a few hours", "none"),
    )

    private suspend fun reply(id: String, words: String, to: String, from: String) =
        graph.applier.apply(CommandBatch(listOf(Command(id, now, SendMessage(words, id = "$id-message", replyTo = to)))), from).single()

    /** The quick changes about the events named [key], as they stand. */
    private fun quick(key: String) = graph.stores.instructions.value.applied.filter { i -> i.quick && i.changes.any { it.type == ChangeType.EventTime && it.eventKey == key } }

    @Test
    fun `an event question answered on the website is kept as your quick change about the event, a later one in its place`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(question("q1", "revision club", "Revision club"))) }
        reply("c1", "a few hours", "q1", Client.WEBSITE)
        val kept = quick("revision club").single().changes.single()
        assertEquals("load:180" to "Revision club", kept.eventAnswer to kept.eventTitle)
        assertNotNull(graph.stores.chat.value.messages.single { it.id == "q1" }.answeredAt)
        // Asked again and answered again: the new answer in its place, not two of them.
        graph.stores.chat.update { it.copy(messages = it.messages + question("q2", "revision club", "Revision club")) }
        reply("c2", "none", "q2", Client.WEBSITE)
        assertEquals(listOf("free"), quick("revision club").map { it.changes.single().eventAnswer })
        // Another event's answer is its own, and leaves this one's standing.
        graph.stores.chat.update { it.copy(messages = it.messages + question("q3", "drum lesson", "Drum lesson")) }
        reply("c3", "all of it", "q3", Client.WEBSITE)
        assertEquals(listOf("busy"), quick("drum lesson").map { it.changes.single().eventAnswer })
        assertEquals(listOf("free"), quick("revision club").map { it.changes.single().eventAnswer })
    }

    @Test
    fun `an event question answered on the phone keeps nothing on the server, as the phone keeps it`() = runBlocking<Unit> {
        graph.stores.chat.update { it.copy(messages = listOf(question("q1", "drum lesson", "Drum lesson"))) }
        reply("c1", "all of it", "q1", "phone-1")
        assertTrue(quick("drum lesson").isEmpty())
        assertNotNull(graph.stores.chat.value.messages.single { it.id == "q1" }.answeredAt)
    }
}
