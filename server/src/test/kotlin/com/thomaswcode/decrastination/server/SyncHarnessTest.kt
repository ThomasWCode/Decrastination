package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.Replica
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.protocol.AddNote
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.ClearChat
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.DeleteOwnTask
import com.thomaswcode.decrastination.sync.protocol.UndoKeep
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.tasks.TasksHarness
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        var seeds = 0

        /** What happens on the phone while its pull is on its way, once. */
        var duringPull: (() -> Unit)? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            if (cut) throw IOException("cut")
            if (url.endsWith("/api/stores/seed")) {
                seeds++
                if (graph.mode == ServerGraph.COMMANDS) return HttpResponse(409, "{}")
                return HttpResponse(200, json.encodeToString(SeedAnswer.serializer(), runBlocking { graph.seed(json.decodeFromString(SeedBatch.serializer(), body), device) }))
            }
            check(url.endsWith("/api/commands")) { url }
            headers[Protocol.ZONE_HEADER]?.let(graph.serverClock::setZone)
            if (graph.mode != ServerGraph.COMMANDS) return HttpResponse(409, "{}")
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
            if (url.endsWith("/api/version")) return HttpResponse(200, json.encodeToString(ServerVersion.serializer(), graph.version()))
            duringPull?.also { duringPull = null }?.invoke()
            headers[Protocol.ZONE_HEADER]?.let(graph.serverClock::setZone)
            val since = url.substringAfter("since=").toLong()
            val state = graph.state(since)
            val body = buildJsonObject {
                put("revision", state.revision)
                put("mode", graph.mode)
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
    fun `a server restored to an earlier revision takes nothing from this phone, which holds what it's lost`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        // This phone has been further than the server now has: it took what a restore has since taken back.
        phone.h.stores.sync.update { it.copy(revision = 1_000) }
        phone.h.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(title = "Newer than the restore") }) }
        assertFalse(phone.replica.upload())
        assertEquals(setOf("Newer than the restore"), phone.titles)
        assertEquals(1_000, phone.h.stores.sync.value.revision, "it's not taken at its word")
        assertTrue(assertNotNull(phone.h.stores.sync.value.lastError).startsWith("The server has gone back"))
    }

    /** The server as a restore's steps leave it: back to taking seeds. */
    private fun mirroring() {
        db.setMeta("mode", "mirror")
        graph.modeForATest("mirror")
    }

    @Test
    fun `a server put back to mirroring is sent all this phone holds, with a pull, and what's waiting goes with it`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        phone.h.commands.act(own("Maths"))
        mirroring()
        assertTrue(phone.replica.upload(), "its command's turned away, and it goes back")
        assertEquals(SyncState.MIRROR, phone.h.stores.sync.value.mode)
        assertEquals(0, phone.waiting, "it's in the stores, and goes with them")
        assertEquals(setOf("Essay", "Maths"), phone.titles, "nothing's taken from here")
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), serverTitles)
    }

    @Test
    fun `a phone with nothing waiting hears the server's gone back to mirroring from its pull`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        phone.h.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(title = "Newer than the restore") }) }
        mirroring()
        assertTrue(phone.replica.upload())
        assertEquals(SyncState.MIRROR, phone.h.stores.sync.value.mode)
        assertEquals(setOf("Newer than the restore"), phone.titles)
    }

    @Test
    fun `going over to commands takes one seed, not three`() = runBlocking {
        val phone = phone()
        phone.h.stores.sync.update { it.copy(mode = SyncState.MIRROR) }
        assertTrue(phone.replica.upload())
        assertEquals(1, phone.wire.seeds)
        assertEquals(SyncState.COMMANDS, phone.h.stores.sync.value.mode)
    }

    @Test
    fun `a command that throws here makes the next pull one of everything, as a stopped process's does`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        assertTrue(phone.h.stores.sync.value.revision > 0)
        assertFailsWith<IllegalStateException> { phone.h.commands.act(AddNote("own:s-Essay", "a note")) }
        assertEquals(0, phone.h.stores.sync.value.revision)
    }

    @Test
    fun `the phone's zone reaches the server with each exchange, with commands waiting or none`() = runBlocking {
        val phone = phone()
        fun replica(zone: String) = Replica(phone.h.stores, phone.wire, FixedClock(phone.h.clock.time, ZoneId.of(zone)), "test", { CalendarRead() }, phone.h.commands)
        assertTrue(replica("Asia/Tokyo").upload())
        assertEquals("Asia/Tokyo", graph.clock.zone().id, "with nothing waiting")
        phone.h.commands.act(own("Essay"))
        assertTrue(replica("America/New_York").upload())
        assertEquals("America/New_York", graph.clock.zone().id, "with a command")
    }

    @Test
    fun `what the server dropped stays said till it's put away`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        assertTrue(one.replica.upload())
        assertTrue(two.replica.upload())
        one.h.commands.act(FinishOwnTask("own:s-Essay"))
        one.h.commands.act(DeleteOwnTask("own:s-Essay"))
        assertTrue(one.replica.upload())
        two.h.commands.act(FinishOwnTask("own:s-Essay"))
        assertTrue(two.replica.upload())
        val line = two.h.stores.sync.value.dropped.single()
        assertTrue(line.startsWith("Finish own task, ") && line.endsWith(": That task had gone"), line)
        two.replica.clearDropped()
        assertEquals(emptyList(), two.h.stores.sync.value.dropped)
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
        assertTrue(two.h.stores.sync.value.dropped.single().endsWith(": That task had gone"))
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
    fun `a pull isn't taken over a command waiting, which it couldn't take out, and comes in once that's gone`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        one.replica.upload()
        two.replica.upload()
        one.h.commands.act(own("Maths"))
        one.replica.upload()
        val revision = two.h.stores.sync.value.revision
        // A store the pull doesn't hold, and a change that adds a row each time it's applied.
        val change = AddOwnChange("i", "words", About(taskId = "own:s-Essay"), Change(ChangeType.OnDay, taskId = "own:s-Essay", date = "2026-11-02"))
        two.wire.duringPull = { runBlocking { two.h.commands.act(change) } }
        assertTrue(two.replica.upload())
        assertEquals(setOf("Essay"), two.titles, "not taken yet")
        assertEquals(1, two.h.stores.instructions.value.instructions.size, "made once")
        assertEquals(revision, two.h.stores.sync.value.revision, "so it's asked for again")
        assertTrue(two.replica.upload())
        assertEquals(setOf("Essay", "Maths"), two.titles)
        assertEquals(1, two.h.stores.instructions.value.instructions.size)
    }

    @Test
    fun `a chat cleared on the server goes from a phone, and leaves it no empty month`() = runBlocking {
        val phone = phone()
        val month = "chat-archive-2026-10.json"
        val words = ChatArchive(listOf(ChatMessage("a", Speaker.Felix, "words", 1_792_000_000_000L)))
        graph.backend.keep(month, words.version, JsonStore.json.encodeToString(ChatArchive.serializer(), words))
        assertTrue(phone.replica.upload())
        assertEquals(listOf(month), phone.h.stores.chatArchive.texts().map { it.name })
        graph.applier.apply(CommandBatch(listOf(Command("clear", 1_792_000_000_000L, ClearChat))), "website")
        assertTrue(phone.replica.upload())
        assertEquals(emptyList(), phone.h.stores.chatArchive.texts().map { it.name })
    }

    private fun change(id: String, taskId: String) = AddOwnChange(id, "words", About(taskId = taskId), Change(ChangeType.OnDay, taskId = taskId, date = "2026-11-02"))

    @Test
    fun `a command the server refused isn't said as a change lost, and what it did here goes all the same`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        // What the server's copy hasn't: as a refused one can have gone otherwise here.
        phone.h.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(detail = "Only here") }) }
        phone.h.commands.act(UndoKeep("no such piece", 5L))
        assertTrue(phone.replica.upload())
        assertEquals(emptyList(), phone.h.stores.sync.value.dropped)
        assertEquals(setOf(""), phone.h.stores.tasks.value.tasks.mapTo(HashSet()) { it.detail })
        assertEquals(graph.state(0).revision, phone.h.stores.sync.value.revision)
    }

    @Test
    fun `what a dropped command did here goes, though the server changed nothing a pull since the last would bring`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        one.h.commands.act(own("Maths"))
        one.h.commands.act(change("kept", "own:s-Maths"))
        assertTrue(one.replica.upload())
        assertTrue(two.replica.upload())
        one.h.commands.act(DeleteOwnTask("own:s-Essay"))
        assertTrue(one.replica.upload())
        two.h.commands.act(change("dropped", "own:s-Essay"))
        assertEquals(2, two.h.stores.instructions.value.instructions.size)
        assertTrue(two.replica.upload())
        assertEquals(listOf("kept"), two.h.stores.instructions.value.instructions.map { it.id }, "as the server has it")
        assertEquals(setOf("Maths"), two.titles)
        assertEquals(1, two.h.stores.sync.value.dropped.size)
        assertEquals(graph.state(0).revision, two.h.stores.sync.value.revision)
    }

    @Test
    fun `a command a stopped process half made reaches the server once, and the pull after is of everything`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        phone.h.commands.act(change("kept", "own:s-Essay"))
        assertTrue(phone.replica.upload())
        // Stopped with a command recorded, and a store written that the server won't change.
        phone.h.stores.instructions.update { it.copy(instructions = emptyList()) }
        phone.h.stores.outbox.update { it.copy(commands = listOf(Command("stopped", phone.h.clock.now(), own("Maths")))) }
        val started = Replica(phone.h.stores, phone.wire, phone.h.clock, "test", { CalendarRead() }, phone.h.commands)
        assertTrue(started.upload())
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        assertEquals(setOf("Essay", "Maths"), phone.titles, "made here by the pull")
        assertEquals(0, phone.waiting)
        assertEquals(listOf("kept"), phone.h.stores.instructions.value.instructions.map { it.id }, "put back by it")
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
