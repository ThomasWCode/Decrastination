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
import com.thomaswcode.decrastination.sync.ParentCode
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.PushId
import com.thomaswcode.decrastination.sync.Replica
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.SwitchSeed
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.AddNote
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.ClearChat
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.DeleteOwnTask
import com.thomaswcode.decrastination.sync.protocol.UndoKeep
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.ResetData
import com.thomaswcode.decrastination.data.Reset
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailWriter
import com.thomaswcode.decrastination.sync.GmailFinishes
import com.thomaswcode.decrastination.sync.protocol.FinishGmail
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.learn.AnkiReading
import com.thomaswcode.decrastination.learn.DeckCount
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.sync.protocol.AnkiCame
import com.thomaswcode.decrastination.sync.protocol.AnkiWent
import com.thomaswcode.decrastination.sync.protocol.AskAboutEvent
import com.thomaswcode.decrastination.sync.protocol.AttachPhoto
import com.thomaswcode.decrastination.sync.protocol.NoteSource
import com.thomaswcode.decrastination.sync.protocol.ObserveCalendar
import com.thomaswcode.decrastination.sync.protocol.ObserveSources
import com.thomaswcode.decrastination.sync.protocol.RecordBlock
import com.thomaswcode.decrastination.sync.protocol.RecordProtection
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.ParentCodeSet
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import com.thomaswcode.decrastination.sync.protocol.UseParentCode
import com.thomaswcode.decrastination.protect.CodeLock
import com.thomaswcode.decrastination.protect.Totp
import com.thomaswcode.decrastination.tasks.TasksHarness
import com.thomaswcode.decrastination.views.Asked
import com.thomaswcode.decrastination.views.PhotoCheckAsk
import com.thomaswcode.decrastination.views.PhotoChecked
import java.net.URLDecoder
import java.util.Base64
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.SQLException
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    /** What the server's Gmail was asked to change, in order: one message, starred, in the inbox. */
    private val gmailStored = mutableListOf<GmailWriter.Change>()
    private val gmail = object : GmailWriter {
        override suspend fun <T> open(block: suspend (GmailWriter.Session) -> T): T = block(object : GmailWriter.Session {
            override fun thread(threadId: String): List<Long> = listOf(11L)
            override fun messages(uids: List<Long>): List<GmailWriter.Message> = listOf(GmailWriter.Message(11, "101", starred = true, inbox = true))
            override fun store(uids: List<Long>, change: GmailWriter.Change): List<Long> {
                gmailStored += change
                return emptyList()
            }
        })
    }
    private val graph = ServerGraph(db, dir, Plain, "test", gmailWriter = { gmail })
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The wire to the server: cut, or losing an answer after the server's acted on what it was sent. */
    private inner class Wire(private val device: String) : Http {
        var cut = false
        var losesAnswers = 0
        var seeds = 0

        /** The pulls it's carried. */
        var pulls = 0

        /** Where Firebase reaches the phone, each as it was sent. */
        val pushIds = mutableListOf<String>()

        /** The parent code's secrets, each as it was taken; null for none. */
        val parentCodes = mutableListOf<String?>()

        /** The parent code's route answering with a failure. */
        var parentFails = false

        /** The times the server was asked to try your words again. */
        var triedAgain = 0

        /** Pulls answered with a failure, every other route as it would. */
        var pullFails = false

        /** The tasks' photos' and the commands' requests, in the order they came. */
        val routes = mutableListOf<String>()

        /** The tasks' photos' route answering with a failure. */
        var photosFail = false

        override fun postBytes(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            if (cut) throw IOException("cut")
            check(url.contains("/api/task-photos/")) { url }
            routes += "task-photos"
            if (photosFail) return HttpResponse(500, "{}")
            return if (graph.keepTaskPhoto(url.substringAfterLast('/'), body)) HttpResponse(204, "") else HttpResponse(400, "{}")
        }

        /** What the server's routes answer a request that throws. */
        private fun failing(request: () -> HttpResponse): HttpResponse = try {
            request()
        } catch (failed: SQLException) {
            HttpResponse(500, "{}")
        }

        /** What happens on the phone while its pull is on its way, once. */
        var duringPull: (() -> Unit)? = null

        /** Where each pull began, by the revision it asked for what's changed since. */
        val sinces = mutableListOf<Long>()

        /** The switch's seeds it's carried, answers to lose after the server's taken one, and a refusal to give in its place. */
        var switches = 0
        var losesSwitchAnswers = 0
        var refusesSwitch: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            if (cut) throw IOException("cut")
            if (url.endsWith("/api/stores/switch")) {
                switches++
                refusesSwitch?.let { return HttpResponse(409, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf("error" to it))) }
                val switched = runBlocking { graph.switchOver(json.decodeFromString(SwitchSeed.serializer(), body), device) }
                if (losesSwitchAnswers > 0) {
                    losesSwitchAnswers--
                    throw IOException("lost")
                }
                return when (switched) {
                    is ServerGraph.Switched.Over -> HttpResponse(200, json.encodeToString(SeedAnswer.serializer(), switched.answer))
                    is ServerGraph.Switched.Refused -> HttpResponse(409, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf("error" to switched.why)))
                }
            }
            if (url.endsWith("/api/stores/seed")) {
                seeds++
                if (graph.mode == ServerGraph.COMMANDS) return HttpResponse(409, "{}")
                val answer = runBlocking { graph.seed(json.decodeFromString(SeedBatch.serializer(), body), device) } ?: return HttpResponse(409, "{}")
                return HttpResponse(200, json.encodeToString(SeedAnswer.serializer(), answer))
            }
            if (url.endsWith("/api/devices/push")) {
                pushIds += json.decodeFromString(PushId.serializer(), body).fid
                return HttpResponse(204, "")
            }
            if (url.endsWith("/api/parent-code")) {
                if (parentFails) return HttpResponse(500, "{}")
                val secret = json.decodeFromString(ParentCode.serializer(), body).secret
                parentCodes += secret
                runBlocking { graph.setParentCode(secret) }
                return HttpResponse(204, "")
            }
            if (url.endsWith("/read-again")) {
                routes += "read-again"
                val id = URLDecoder.decode(url.substringAfter("/api/tasks/").substringBefore("/read-again"), Charsets.UTF_8)
                return HttpResponse(200, json.encodeToString(Asked.serializer(), graph.readAgain(id)))
            }
            if (url.endsWith("/api/photos/check")) {
                routes += "photos/check"
                val ask = json.decodeFromString(PhotoCheckAsk.serializer(), body)
                val checked = runBlocking { graph.checkPhoto(ask.taskId, ask.key, ask.step, ask.takenAt, ask.pages.map { Base64.getDecoder().decode(it) }) }
                return HttpResponse(200, json.encodeToString(PhotoChecked.serializer(), checked!!))
            }
            if (url.endsWith("/api/chat/try-again")) {
                if (graph.mode != ServerGraph.COMMANDS) return HttpResponse(409, "{}")
                triedAgain++
                graph.tryAgain()
                return HttpResponse(204, "")
            }
            if (url.endsWith("/api/backup/import")) {
                if (graph.mode != ServerGraph.COMMANDS) return HttpResponse(409, "{}")
                return HttpResponse(200, json.encodeToString(Asked.serializer(), Asked(true, runBlocking { graph.importBackup(body, device) })))
            }
            check(url.endsWith("/api/commands")) { url }
            routes += "commands"
            headers[Protocol.ZONE_HEADER]?.let(graph.serverClock::setZone)
            if (graph.mode != ServerGraph.COMMANDS) return HttpResponse(409, "{}")
            val batch = json.decodeFromString(CommandBatch.serializer(), body)
            return failing {
                val outcomes = runBlocking { graph.applier.apply(batch, device) }
                if (losesAnswers > 0) {
                    losesAnswers--
                    throw IOException("lost")
                }
                HttpResponse(200, json.encodeToString(ListSerializer(Outcome.serializer()), outcomes))
            }
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            if (cut) throw IOException("cut")
            if (url.endsWith("/api/version")) return HttpResponse(200, json.encodeToString(ServerVersion.serializer(), graph.version()))
            pulls++
            if (pullFails) return HttpResponse(500, "{}")
            duringPull?.also { duringPull = null }?.invoke()
            headers[Protocol.ZONE_HEADER]?.let(graph.serverClock::setZone)
            val since = url.substringAfter("since=").toLong()
            sinces += since
            val state = graph.state(since)
            val body = buildJsonObject {
                put("revision", state.revision)
                put("mode", graph.mode)
                put("letGo", json.encodeToJsonElement(MapSerializer(String.serializer(), Long.serializer()), state.letGo))
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

        /** The chat's messages each pull brought, new to it. */
        val heard = mutableListOf<List<ChatMessage>>()
        val replica = Replica(h.stores, wire, h.clock, "test", { CalendarRead() }, h.commands, seedFile = File(dir, "$name-switch-seed.json")) { heard += it }

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
        phone.h.stores.sync.update { it.copy(letGoSeen = 40) }
        mirroring()
        assertTrue(phone.replica.upload(), "its command's turned away, and it goes back")
        assertEquals(SyncState.MIRROR, phone.h.stores.sync.value.mode)
        assertEquals(0, phone.waiting, "it's in the stores, and goes with them")
        assertEquals(0, phone.h.stores.sync.value.letGoSeen, "a restored server's revisions start again below the let-gos acted on")
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
    fun `a fault on the server loses nothing, the phone's change waiting, none said to be dropped, till it lands once that's past`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        phone.h.commands.act(own("Maths"))
        // A store can't be written, and the command log can: given up on, it'd go on record, and the phone would let it go.
        db.transaction { c -> c.createStatement().use { it.execute("ALTER TABLE store_history RENAME TO store_history_gone") } }
        assertFalse(phone.replica.upload())
        db.transaction { c -> c.createStatement().use { it.execute("ALTER TABLE store_history_gone RENAME TO store_history") } }
        assertEquals(1, phone.waiting)
        assertEquals(emptyList(), phone.h.stores.sync.value.dropped)
        assertEquals("The server said no (500)", phone.h.stores.sync.value.lastError)
        assertEquals(setOf("Essay", "Maths"), phone.titles, "and what it did here stands")
        assertTrue(phone.replica.upload())
        assertEquals(0, phone.waiting)
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        assertEquals(setOf("Essay", "Maths"), phone.titles)
        assertEquals(null, phone.h.stores.sync.value.lastError)
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
    fun `told of a revision it holds, with nothing waiting, a phone asks nothing, and told of a newer one, or with a change waiting, it exchanges`() = runBlocking {
        val one = phone("one")
        val two = phone("two")
        one.h.commands.act(own("Essay"))
        one.replica.upload()
        val held = one.h.stores.sync.value.revision
        val pulls = one.wire.pulls
        assertTrue(one.replica.told(held))
        assertEquals(pulls, one.wire.pulls, "what it said is here")
        two.h.commands.act(own("Maths"))
        two.replica.upload()
        assertTrue(one.replica.told(db.currentRevision()))
        assertEquals(setOf("Essay", "Maths"), one.titles)
        one.h.commands.act(own("Physics"))
        assertTrue(one.replica.told(held))
        assertEquals(setOf("Essay", "Maths", "Physics"), serverTitles, "what was waiting went")
    }

    @Test
    fun `a phone going by commands sends where Firebase reaches it once an exchange goes through, and once only`() = runBlocking {
        val phone = phone()
        phone.replica.registered("cMadeUpFid000000000aaa")
        phone.wire.cut = true
        assertFalse(phone.replica.upload())
        // The pull alone fails: the route for the id would take it, and isn't asked.
        phone.wire.cut = false
        phone.wire.pullFails = true
        assertFalse(phone.replica.upload())
        assertEquals(emptyList(), phone.wire.pushIds)
        phone.wire.pullFails = false
        assertTrue(phone.replica.upload())
        phone.replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa"), phone.wire.pushIds)
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

    /** The phone's own read of Teams, as a phone sending commands sends it: what it lists is [listed]. */
    private fun Phone.teams(vararg listed: String): Syncer {
        val teams = object : TaskSource {
            override val source = Source.Teams

            override suspend fun read(context: ReadContext) = SourceRead(listed.map { Fetched(it, it, Kind.Homework) })
        }
        return Syncer(h.stores.tasks, h.stores.settings, listOf(teams), h.clock, observing = { true }, observed = { seen -> h.commands.act(ObserveSources(seen)) })
    }

    @Test
    fun `a Teams read on a phone sending commands is merged on the server as here, and no pull takes it back`() = runBlocking {
        val phone = phone()
        phone.teams("An essay").sync()
        assertEquals(setOf("An essay"), phone.titles, "here at once, as before")
        assertTrue(phone.replica.upload())
        assertEquals(setOf("An essay"), serverTitles)
        // A pull of everything: what the phone holds is the server's copy, the read in it.
        phone.replica.pullEverything()
        assertTrue(phone.replica.upload())
        assertEquals(setOf("An essay"), phone.titles)
    }

    @Test
    fun `a task gone from Teams' list is done on the server too, and a read the same again changes nothing there`() = runBlocking {
        val phone = phone()
        phone.teams("An essay", "A poster").sync()
        phone.teams("An essay").sync()
        assertTrue(phone.replica.upload())
        val poster = graph.stores.tasks.value.tasks.single { it.title == "A poster" }
        assertFalse(poster.isOpen, "a task missing from a read is done, as the phone's merge has it")
        val before = graph.stores.tasks.value.tasks
        val again = phone.teams("An essay")
        again.sync()
        again.sync()
        assertTrue(phone.replica.upload())
        assertEquals(before, graph.stores.tasks.value.tasks)
    }

    @Test
    fun `AnkiDroid come and gone on a phone sending commands is kept on the server, from the decks as the phone read them`() = runBlocking {
        val phone = phone()
        val at = phone.h.clock.now()
        phone.h.commands.send(AnkiCame(at, AnkiReading(at, mapOf(1L to DeckCount(0, 10, 0)), entering = true)))
        assertTrue(phone.replica.upload())
        assertEquals(at, graph.stores.anki.value.ankiFrontSince)
        phone.h.commands.send(AnkiWent(at + 60_000, AnkiReading(at + 60_000, mapOf(1L to DeckCount(0, 5, 0)), entering = false)))
        assertTrue(phone.replica.upload())
        assertEquals(null, graph.stores.anki.value.ankiFrontSince)
        assertEquals(2, graph.stores.anki.value.ankiReadings.size)
    }

    @Test
    fun `the calendar as a phone read it is what the server plans by`() = runBlocking {
        val phone = phone()
        val read = CalendarRead(answers = mapOf("an event" to "busy"), from = 1L, to = 2L)
        phone.h.commands.send(ObserveCalendar(read))
        assertTrue(phone.replica.upload())
        assertEquals(read, graph.calendarRead.value)
        assertEquals(listOf(read), phone.h.calendars, "kept here by nothing but the phone's own calendar")
    }

    @Test
    fun `what the phone's blocker, watchdog and Teams widget saw is on the server's record too`() = runBlocking {
        val phone = phone()
        phone.h.commands.send(RecordBlock("an app", "a reason"))
        phone.h.commands.send(RecordProtection(listOf("a problem"), repaired = false))
        phone.h.commands.send(NoteSource(Source.Teams, "The Teams widget's sync service is off"))
        phone.h.commands.act(own("Essay"))
        phone.h.commands.send(AttachPhoto("own:s-Essay", TaskPhoto("photos/own_s-Essay.jpg", "abcd", phone.h.clock.now())))
        assertTrue(phone.replica.upload())
        assertEquals(listOf("an app"), graph.stores.log.value.blocks.map { it.target })
        assertEquals(listOf(listOf("a problem")), graph.stores.log.value.protection.map { it.problems })
        assertEquals("The Teams widget's sync service is off", graph.stores.tasks.value.status(Source.Teams).note)
        assertEquals("abcd", graph.stores.tasks.value.tasks.single { it.title == "Essay" }.photo?.hash)
    }

    @Test
    fun `Read again and a photo check go after what's waiting, so the server has the task as this phone shows it`() = runBlocking {
        val phone = phone()
        // Made here and not yet sent: without what's waiting going first, the server hasn't the task.
        phone.h.commands.act(own("Essay"))
        phone.wire.routes.clear()
        assertEquals("Claude can't be asked now: try again later", phone.replica.readAgainOnServer("own:s-Essay"))
        assertEquals(listOf("commands", "read-again"), phone.wire.routes)
        // A photo check of a task just made, likewise: its piece found there, and with no key there, said so.
        phone.h.commands.act(own("Maths"))
        val piece = phone.h.planning.plan().chunksOf("own:s-Maths").first()
        phone.wire.routes.clear()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
        assertEquals("Claude is off: the timer or the source will have to do.", phone.replica.checkPhotoOnServer(piece.taskId, piece.key, piece.step, 1L, listOf(jpeg)))
        assertEquals(listOf("commands", "photos/check"), phone.wire.routes)
    }

    @Test
    fun `a task's photo reaches the box ahead of the command that keeps it, by its hash, and one that can't go holds the commands back`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)
        val file = File(dir, "a-phone-photo.jpg").apply { writeBytes(jpeg) }
        val photo = TaskPhoto(file.path, Photos.hashOf(jpeg), phone.h.clock.now())
        phone.h.commands.send(AttachPhoto("own:s-Essay", photo))
        phone.wire.routes.clear()
        assertTrue(phone.replica.upload())
        assertEquals(listOf("task-photos", "commands"), phone.wire.routes)
        assertTrue(File(dir, "photos/${photo.hash}.jpg").readBytes().contentEquals(jpeg), "kept on the box by its hash")
        assertEquals(photo, graph.stores.tasks.value.tasks.single { it.id == "own:s-Essay" }.photo)
        // One that can't go holds the commands back, said; the next try sends both.
        phone.wire.photosFail = true
        phone.h.commands.send(AttachPhoto("own:s-Essay", photo))
        assertFalse(phone.replica.upload())
        assertEquals(1, phone.waiting)
        assertEquals("A task's photo couldn't be sent", phone.h.stores.sync.value.lastError)
        // Another photo in its file here since: that one's no more, so it isn't sent, and the command goes.
        phone.wire.photosFail = false
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 8))
        phone.wire.routes.clear()
        assertTrue(phone.replica.upload())
        assertEquals(listOf("commands"), phone.wire.routes)
        assertEquals(0, phone.waiting)
    }

    @Test
    fun `an event the phone's calendar asks about is asked in the server's chat`() = runBlocking {
        val outcome = graph.applier.apply(CommandBatch(listOf(Command("q-1", System.currentTimeMillis(), AskAboutEvent("an event", "An event", "Monday", System.currentTimeMillis(), listOf("Busy", "Free"))))), "phone").single()
        assertEquals(Outcome.APPLIED, outcome.state)
        assertTrue(graph.stores.chat.value.messages.any { "An event" in it.text })
    }

    @Test
    fun `a reset made on the server takes this phone's copies of what it cleared at its next pull, and leaves the rest`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        // Copies a migration kept here, beside the stores (D-94).
        val chatCopy = File(phone.h.dir, "chat.json.v5").apply { writeText("{}") }
        val tasksCopy = File(phone.h.dir, "tasks.json.v9").apply { writeText("{}") }
        val before = db.currentRevision()
        assertEquals(Outcome.APPLIED, graph.applier.apply(CommandBatch(listOf(Command("reset-1", phone.h.clock.now(), ResetData(Reset.Chat)))), Client.WEBSITE).single().state)
        assertTrue("chat.json" in graph.state(before).letGo, "said to a pull from before it")
        assertEquals(emptyMap(), graph.state(db.currentRevision()).letGo, "and not to one from after")
        assertTrue(phone.replica.upload())
        assertFalse(chatCopy.exists(), "what the reset cleared isn't kept in a copy here either")
        assertTrue(tasksCopy.exists(), "what it didn't clear keeps its copy")
        // A copy made here since, by a migration say, then a pull of everything, which says every let-go there's been: kept.
        val newer = File(phone.h.dir, "chat.json.v6").apply { writeText("{}") }
        phone.replica.pullEverything()
        assertTrue(phone.replica.upload())
        assertTrue(newer.exists(), "a let-go acted on already isn't acted on again")
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

    /** [action] applied on the server as the website's: what the phone didn't make. */
    private suspend fun onTheWebsite(id: String, action: Action) =
        assertEquals(Outcome.APPLIED, graph.applier.apply(CommandBatch(listOf(Command(id, System.currentTimeMillis(), action))), Client.WEBSITE).single().state)

    @Test
    fun `what Felix says on the server is heard on the phone as it's pulled, only what's new to it`() = runBlocking {
        val phone = phone()
        assertTrue(phone.replica.upload())
        onTheWebsite("q-1", AskAboutEvent("first", "A first event", "Monday", System.currentTimeMillis(), listOf("Busy", "Free")))
        assertTrue(phone.replica.upload())
        assertEquals(listOf("A first event"), phone.heard.last().map { it.about.eventTitle })
        onTheWebsite("q-2", AskAboutEvent("second", "A second event", "Tuesday", System.currentTimeMillis(), listOf("Busy", "Free")))
        assertTrue(phone.replica.upload())
        assertEquals(listOf("A second event"), phone.heard.last().map { it.about.eventTitle }, "the first isn't new to it now")
        val heard = phone.heard.size
        assertTrue(phone.replica.upload())
        assertEquals(heard, phone.heard.size, "a pull that brings no chat hears nothing")
    }

    @Test
    fun `a change held up is let go only at your word, said, and the pull after is of everything, taking out what it did here`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        phone.wire.cut = true
        phone.h.commands.act(own("Maths"))
        assertFalse(phone.replica.upload())
        val held = phone.h.stores.outbox.value.commands.single()
        assertFalse(phone.replica.letGo("not-one-of-them"))
        assertTrue(phone.replica.letGo(held.id))
        assertEquals(0, phone.waiting)
        assertEquals(0, phone.h.stores.sync.value.revision, "the next pull's of everything")
        val line = phone.h.stores.sync.value.dropped.single()
        assertTrue(line.startsWith("Save own task, ") && line.endsWith(": Let go at your word"), line)
        phone.wire.cut = false
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay"), serverTitles)
        assertEquals(setOf("Essay"), phone.titles, "what it did here went with the pull")
    }

    @Test
    fun `a backup imported on the phone is taken in on the server, after what was waiting, and comes back with the pull of everything`() = runBlocking {
        val phone = phone()
        phone.h.commands.act(own("Essay"))
        assertTrue(phone.replica.upload())
        val backup = assertNotNull(graph.backup())
        phone.h.commands.act(DeleteOwnTask("own:s-Essay"))
        assertTrue(phone.replica.upload())
        // A change still waiting goes first.
        phone.h.commands.act(own("Maths"))
        val asked = phone.replica.importOnServer(backup)
        assertTrue(assertNotNull(asked.said).startsWith("Restored"), asked.said)
        assertEquals(0, phone.waiting)
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        assertEquals(0, phone.h.stores.sync.value.revision)
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), phone.titles)
        // Mirroring, it's refused, and nothing's said to be restored.
        mirroring()
        assertFalse(phone.replica.importOnServer(backup).done)
    }

    @Test
    fun `the chat's Try again on a phone sending commands is the server's to make`() = runBlocking {
        val phone = phone()
        assertTrue(phone.replica.askAgainOnServer())
        assertEquals(1, phone.wire.triedAgain)
        phone.wire.cut = true
        assertFalse(phone.replica.askAgainOnServer())
    }

    @Test
    fun `the parent code reaches the server once the phone sends commands, again only as it changes, and none takes it away`() = runBlocking {
        val phone = phone()
        // A server gone back to mirroring, which the exchange finds: nothing's sent after it, nor at a seed.
        mirroring()
        phone.h.stores.secrets.put(Secret.TotpSecret, "ABCDEFGHIJKLMNOP")
        assertTrue(phone.replica.upload())
        assertEquals(SyncState.MIRROR, phone.h.stores.sync.value.mode)
        phone.replica.upload()
        assertEquals(emptyList(), phone.wire.parentCodes)
        // Going by commands, it goes ahead of the exchange, once.
        db.setMeta("mode", ServerGraph.COMMANDS)
        graph.modeForATest(ServerGraph.COMMANDS)
        phone.h.stores.sync.update { it.copy(mode = SyncState.COMMANDS) }
        assertTrue(phone.replica.upload())
        assertTrue(phone.replica.upload())
        assertEquals(listOf<String?>("ABCDEFGHIJKLMNOP"), phone.wire.parentCodes)
        assertEquals("ABCDEFGHIJKLMNOP", graph.stores.secrets[Secret.TotpSecret])
        // One that doesn't go is tried again after the next.
        phone.h.stores.secrets.put(Secret.TotpSecret, "QRSTUVWXYZ234567")
        phone.wire.parentFails = true
        assertTrue(phone.replica.upload())
        phone.wire.parentFails = false
        assertTrue(phone.replica.upload())
        assertEquals("QRSTUVWXYZ234567", graph.stores.secrets[Secret.TotpSecret])
        // None: taken away there too.
        phone.h.stores.secrets.put(Secret.TotpSecret, null)
        assertTrue(phone.replica.upload())
        assertEquals(null, graph.stores.secrets[Secret.TotpSecret])
        assertEquals(SyncState.NO_CODE, phone.h.stores.sync.value.parentCodeSent)
        assertEquals(listOf("ABCDEFGHIJKLMNOP", "QRSTUVWXYZ234567", null), phone.wire.parentCodes)
    }

    @Test
    fun `an email finished on a phone sending commands is journaled the same on both, and written to Gmail by the server alone`() = runBlocking {
        val phone = phone()
        val email = TaskItem(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "A made-up email", dueAt = null, kind = Kind.Admin, firstSeenAt = 1, lastSeenAt = 1)
        for (stores in listOf(graph.stores, phone.h.stores)) {
            stores.tasks.update { it.copy(tasks = listOf(email)) }
            stores.settings.update { it.copy(gmailFinishes = true, hintsSeen = it.hintsSeen + GmailWrites.OK_HINT) }
        }
        assertEquals(GmailFinishes.Finish.Going, phone.h.commands.act(FinishGmail(email.id)))
        assertTrue(phone.replica.upload())
        assertEquals(phone.h.stores.gmailWrites.value.writes.map { it.id }, graph.stores.gmailWrites.value.writes.map { it.id }, "the one write, by its command's id")
        // Made the day the phone made it, so past its grace: the server writes it, and the phone has it made at its next pull.
        graph.gmailDue()
        assertEquals(listOf(GmailWriter.Change.Unstar, GmailWriter.Change.Archive), gmailStored)
        assertTrue(phone.replica.upload())
        assertEquals(GmailWrites.State.Made, GmailWrites.state(phone.h.stores.gmailWrites.value.writes, email.id))
    }

    @Test
    fun `a finish the server can't make, its email gone there meanwhile, is refused, and the phone takes the journal as it stands`() = runBlocking {
        val phone = phone()
        val email = TaskItem(id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "A made-up email", dueAt = null, kind = Kind.Admin, firstSeenAt = 1, lastSeenAt = 1)
        // An earlier finish's receipt on both, as the journal's kept there from the phone's seeds.
        val receipt = GmailWrite("w-0", "gmail:t0", "An older made-up email", "t0", phone.h.clock.now() - 60_000, doneAt = phone.h.clock.now() - 30_000)
        for (stores in listOf(graph.stores, phone.h.stores)) {
            stores.tasks.update { it.copy(tasks = listOf(email)) }
            stores.settings.update { it.copy(gmailFinishes = true, hintsSeen = it.hintsSeen + GmailWrites.OK_HINT) }
            stores.gmailWrites.update { it.copy(writes = listOf(receipt)) }
        }
        assertTrue(phone.replica.upload())
        // Offline, it's finished here; meanwhile the server's own read finds the email gone (unstarred by hand, say).
        phone.wire.cut = true
        assertEquals(GmailFinishes.Finish.Going, phone.h.commands.act(FinishGmail(email.id)))
        graph.stores.tasks.update { it.copy(tasks = emptyList()) }
        phone.wire.cut = false
        assertTrue(phone.replica.upload())
        assertEquals(listOf(receipt), graph.stores.gmailWrites.value.writes, "nothing journaled there")
        // The journal hasn't changed there, so only a pull of everything puts the phone's right: the refusal asks for one.
        assertEquals(listOf(receipt), phone.h.stores.gmailWrites.value.writes)
    }

    @Test
    fun `a new parent code reaches the server ahead of the commands made with it, its lock begun afresh there`() = runBlocking {
        val phone = phone()
        phone.h.stores.secrets.put(Secret.TotpSecret, "ABCDEFGHIJKLMNOP")
        assertTrue(phone.replica.upload())
        // The old code locked there by wrong tries.
        graph.stores.protection.update { it.copy(codeLock = CodeLock(lastUsedStep = 7, lockedUntil = Long.MAX_VALUE)) }
        // A new one set here and used at once: its lock begun and a code from it, both commands, waiting together.
        val now = phone.h.clock.now()
        val step = Totp.step(now)
        phone.h.stores.secrets.put(Secret.TotpSecret, "QRSTUVWXYZ234567")
        val code = Totp.code(Totp.fromBase32("QRSTUVWXYZ234567"), step)
        phone.h.stores.outbox.update { it.copy(commands = listOf(Command("c-set", now, ParentCodeSet(step - 1)), Command("c-use", now, UseParentCode(code)))) }
        assertTrue(phone.replica.upload())
        assertEquals("QRSTUVWXYZ234567", graph.stores.secrets[Secret.TotpSecret])
        assertEquals(CodeLock(lastUsedStep = step), graph.stores.protection.value.codeLock, "the new code taken, the old one's lock gone")
    }

    /** A phone mirroring a mirroring server, as before the switch, with [title] seeded there. */
    private suspend fun mirroringWith(title: String): Phone {
        val phone = phone()
        mirroring()
        phone.h.stores.sync.update { it.copy(mode = SyncState.MIRROR) }
        phone.h.commands.act(own(title))
        assertTrue(phone.replica.upload())
        return phone
    }

    @Test
    fun `the switch saves a backup first, sends every store whole, and the phone goes over at its answer's revision, what's made after it a command`() = runBlocking {
        val phone = mirroringWith("Essay")
        var backups = 0
        assertNull(phone.replica.switchOver(backup = { backups++; true }, ran = mapOf("morning" to 5L)))
        assertEquals(1 to ServerGraph.COMMANDS, backups to graph.mode)
        val sync = phone.h.stores.sync.value
        assertEquals(SyncState.COMMANDS to null, sync.mode to sync.switching)
        assertTrue(sync.revision > 0 && sync.revision == sync.seedRevision, "at the answer's revision")
        assertEquals(sync.seedRevision, phone.wire.sinces.last(), "the pull after it of what's changed since, not of everything")
        assertEquals(setOf("Essay"), serverTitles)
        // What's made after it is a command, sent.
        phone.h.commands.act(own("Maths"))
        assertEquals(1, phone.waiting)
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), serverTitles)
        // Not again: this phone keeps in step by commands already.
        assertEquals("This phone keeps in step by commands already", phone.replica.switchOver({ true }, emptyMap()))
    }

    @Test
    fun `a switch whose answer's lost goes again the same, its answer said again, and what's made meanwhile waits for it`() = runBlocking {
        val phone = mirroringWith("Essay")
        phone.wire.losesSwitchAnswers = 1
        assertEquals("Under way: the server can't be reached, so it goes at the next sync", phone.replica.switchOver({ true }, emptyMap()))
        assertEquals(ServerGraph.COMMANDS, graph.mode, "taken there")
        assertNotNull(phone.h.stores.sync.value.switching)
        phone.h.commands.act(own("Maths"))
        assertTrue(phone.replica.upload())
        assertEquals(2, phone.wire.switches)
        assertNull(phone.h.stores.sync.value.switching)
        assertEquals(setOf("Essay", "Maths"), serverTitles)
    }

    @Test
    fun `a backup that can't be saved, or a server that can't take the switch, leaves the phone mirroring, nothing lost`() = runBlocking {
        val phone = mirroringWith("Essay")
        phone.wire.cut = true
        assertEquals("Not in step with the server yet: the server can't be reached", phone.replica.switchOver({ true }, emptyMap()))
        phone.wire.cut = false
        assertEquals("Nothing switched: a backup couldn't be saved first", phone.replica.switchOver({ false }, emptyMap()))
        assertEquals(SyncState.MIRROR to 0, phone.h.stores.sync.value.mode to phone.wire.switches)
        phone.wire.refusesSwitch = "The server can't read this app's tasks.json: deploy it first"
        assertEquals("Not switched: the server can't read this app's tasks.json: deploy it first", phone.replica.switchOver({ true }, emptyMap()))
        assertEquals(SyncState.MIRROR to null, phone.h.stores.sync.value.mode to phone.h.stores.sync.value.switching)
        // Made after the refusal, mirroring: in the stores, and sent with the next seed.
        phone.h.commands.act(own("Maths"))
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), serverTitles)
    }

    @Test
    fun `a first pull that would lose a change the server never took is refused, nothing taken, till the server's copy is taken at your word`() = runBlocking {
        val phone = mirroringWith("Essay")
        // The server goes over without this phone's switch (a restore, say), and a change made here meanwhile never reaches it.
        graph.commandsForATest()
        phone.h.commands.act(own("Maths"))
        assertTrue(phone.replica.upload())
        assertTrue(phone.h.stores.sync.value.firstPull)
        assertFalse(phone.replica.upload())
        assertEquals(setOf("Essay", "Maths"), phone.titles, "nothing taken")
        val why = phone.h.stores.sync.value.lastError.orEmpty()
        assertTrue(why.startsWith("A first pull would lose what's changed here in") && "tasks.json" in why, why)
        phone.replica.takeServersCopy()
        assertTrue(phone.replica.upload())
        assertEquals(setOf("Essay"), phone.titles)
        assertFalse(phone.h.stores.sync.value.firstPull)
    }

    @Test
    fun `a first pull with nothing changed here is taken, the server's own changes with it, and one from a server behind this phone's last seed is refused`() = runBlocking {
        val phone = mirroringWith("Essay")
        graph.commandsForATest()
        // The server's moved on since (its own work): what's here is as last sent, so that's no change of this phone's.
        graph.stores.tasks.update { s -> s.copy(tasks = s.tasks.map { it.copy(title = "Essay, moved on") }) }
        // Behind: its revisions below what this phone's last seed was answered at.
        phone.h.stores.sync.update { it.copy(seedRevision = 1_000_000) }
        assertTrue(phone.replica.upload())
        assertFalse(phone.replica.upload())
        assertTrue(phone.h.stores.sync.value.lastError.orEmpty().startsWith("The server is behind what this phone last sent"))
        // As it was, nothing changed here: taken, and the server's change with it. A store whose seed's answer was lost (its hash
        // here not the one on record) is no change lost either, where the server has it as it is here.
        phone.h.stores.sync.update { it.copy(seedRevision = 0, sent = it.sent + ("settings.json" to "a hash from before")) }
        assertTrue(phone.replica.upload())
        assertFalse(phone.h.stores.sync.value.firstPull)
        assertEquals(setOf("Essay, moved on"), phone.titles)
    }
}
