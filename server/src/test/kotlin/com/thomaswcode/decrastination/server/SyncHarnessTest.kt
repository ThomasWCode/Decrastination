package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.Queued
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sync.Replica
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.DeleteOwnTask
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.tasks.TasksHarness
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A server and two phones in one JVM (docs/plan_web/data-and-sync.md §Testing the sync): the real applier over a real
 * database, each phone's commands, outbox and exchange on stores of its own, and between them a wire a test can cut.
 */
class SyncHarnessTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("harness").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val graph = ServerGraph(db, dir, Plain, "test")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The wire to the server: cut, or losing an answer after the server's acted on what it was sent. */
    private inner class Wire(private val device: String) : Http {
        var cut = false
        var losesAnswers = 0

        /** What happens on the phone while its pull is on its way, once. */
        var duringPull: (() -> Unit)? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            if (cut) throw IOException("cut")
            check(url.endsWith("/api/commands")) { url }
            val batch = json.decodeFromString(CommandBatch.serializer(), body)
            val outcomes = runBlocking { graph.applier.apply(batch, device) }
            if (losesAnswers > 0) {
                losesAnswers--
                throw IOException("lost")
            }
            return HttpResponse(200, json.encodeToString(ListSerializer(Outcome.serializer()), outcomes))
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            if (cut) throw IOException("cut")
            duringPull?.also { duringPull = null }?.invoke()
            val since = url.substringAfter("since=").toLong()
            val state = graph.state(since)
            val body = buildJsonObject {
                put("revision", state.revision)
                put("stores", json.encodeToJsonElement(ListSerializer(JsonElement.serializer()), state.stores.map { row ->
                    buildJsonObject {
                        put("name", row.name)
                        put("version", row.version)
                        put("revision", row.revision)
                        put("json", json.parseToJsonElement(row.text))
                    }
                }))
            }
            return HttpResponse(200, body.toString())
        }
    }

    private inner class Phone(name: String) {
        val wire = Wire(name)
        val h = TasksHarness()
        val replica = Replica(h.stores, wire, h.clock, "test", { CalendarRead() }, h.commands)

        init {
            runBlocking {
                h.stores.secrets.put(Secret.DeviceToken, "token")
                h.stores.sync.update { it.copy(mode = SyncState.COMMANDS) }
            }
        }

        val titles get() = h.stores.tasks.value.tasks.map { it.title }.toSet()
        val waiting get() = h.stores.outbox.value.commands.size
    }

    private val phones = mutableListOf<Phone>()

    private fun phone(name: String = "phone") = Phone(name).also { phones += it }

    private fun own(title: String) = SaveOwnTask("s-$title", OwnTask(title = title))

    private val serverTitles get() = graph.stores.tasks.value.tasks.map { it.title }.toSet()

    @AfterTest
    fun cleanUp() {
        phones.forEach { it.h.close() }
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a task added offline stands on the phone, and reaches the server once it's back`() = runBlocking {
        val phone = phone()
        phone.wire.cut = true
        phone.h.commands.act(own("Essay"))
        assertEquals(setOf("Essay"), phone.titles)
        assertFalse(phone.replica.upload())
        assertEquals(1, phone.waiting)
        assertEquals("The server can't be reached", phone.h.stores.sync.value.lastError)
        phone.wire.cut = false
        assertTrue(phone.replica.upload())
        assertEquals(0, phone.waiting)
        assertEquals(setOf("Essay"), serverTitles)
        assertEquals(setOf("Essay"), phone.titles)
    }

    @Test
    fun `what one phone does reaches the other at its next pull`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        one.replica.upload()
        two.replica.upload()
        assertEquals(setOf("Essay"), two.titles)
    }

    @Test
    fun `a command on a task another deleted is dropped, said, and the task is gone`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        one.replica.upload()
        two.replica.upload()
        two.wire.cut = true
        two.h.commands.act(FinishOwnTask("own:s-Essay"))
        one.h.commands.act(DeleteOwnTask("own:s-Essay"))
        one.replica.upload()
        two.wire.cut = false
        assertTrue(two.replica.upload())
        assertEquals(listOf("That task had gone"), two.h.stores.sync.value.dropped)
        assertEquals(emptySet(), two.titles)
        assertEquals(0, two.waiting)
    }

    @Test
    fun `an answer lost after the server applied it is applied once, the retry answered as the first`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        phone.wire.losesAnswers = 1
        assertFalse(phone.replica.upload())
        assertEquals(1, phone.waiting)
        val revision = db.currentRevision()
        assertTrue(phone.replica.upload())
        assertEquals(0, phone.waiting)
        assertEquals(revision, db.currentRevision())
        assertEquals(1, graph.stores.tasks.value.tasks.size)
    }

    @Test
    fun `a command made while a pull's on its way is still there after it, and goes next time`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        phone.wire.duringPull = { runBlocking { phone.h.commands.act(own("Maths")) } }
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), phone.titles)
        assertEquals(1, phone.waiting)
        assertEquals(setOf("Essay"), serverTitles)
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        assertEquals(0, phone.waiting)
    }

    @Test
    fun `a command recorded and not applied, its process stopped, is applied once at the next start`() = runBlocking {
        val phone = phone()
        val command = Command("stopped", phone.h.clock.now(), own("Essay"))
        phone.h.stores.outbox.update { it.copy(commands = listOf(Queued(command))) }
        phone.h.commands.resume()
        assertEquals(setOf("Essay"), phone.titles)
        assertTrue(phone.h.stores.outbox.value.commands.single().applied)
        phone.h.commands.resume()
        assertEquals(1, phone.h.stores.tasks.value.tasks.size)
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay"), serverTitles)
    }

    @Test
    fun `a store newer than the phone can read stops the pull, whole, and says so`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        phone.replica.upload()
        val newer = phone.h.stores.tasks.version + 1
        db.transaction { c ->
            c.prepareStatement("UPDATE stores SET version = ?, revision = 99 WHERE name = 'tasks.json'").use { q ->
                q.setInt(1, newer)
                q.executeUpdate()
            }
        }
        assertFalse(phone.replica.upload())
        assertEquals("The server is ahead of this app: install the latest", phone.h.stores.sync.value.lastError)
        assertEquals(setOf("Essay"), phone.titles)
    }
}
