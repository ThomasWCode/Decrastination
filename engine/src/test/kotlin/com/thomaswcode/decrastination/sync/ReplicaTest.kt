package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import com.thomaswcode.decrastination.views.Asked
import com.thomaswcode.decrastination.views.OwnTaskEdit
import com.thomaswcode.decrastination.views.PhotoCheckAsk
import com.thomaswcode.decrastination.views.PhotoRead
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class ReplicaTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** The server as a test has it: what it's sent and holds, what it refuses, and whether it's answering at all. */
    private class Server : Http {
        val seeds = mutableListOf<SeedBatch>()
        val headers = mutableListOf<Map<String, String>>()
        val held = mutableMapOf<String, String>()
        var refusing = emptySet<String>()
        var version = "5.0.1"
        var failure: ((String) -> HttpResponse)? = null

        /** A seed alone answered with this, every other route as it would. */
        var seedFailure: Int? = null

        /** What happens on the phone while a send is on its way, once. */
        var meanwhile: (() -> Unit)? = null

        /** What happens on the phone while the id's on its way, once. */
        var duringPush: (() -> Unit)? = null

        /** Where Firebase reaches the phone, each as it was sent, and what's answered to one. */
        val pushIds = mutableListOf<String>()
        var pushAnswer = 204

        /** The thread each request was made on. */
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

        /** The photos of homework it's been sent, and what it answers one. */
        val photos = mutableListOf<ByteArray>()
        var photoAnswer = HttpResponse(200, """{"tasks":[]}""")

        /** The photo checks it's been sent, and what it answers one. */
        val checks = mutableListOf<PhotoCheckAsk>()
        var checkAnswer = HttpResponse(200, """{"said":"Done: every question answered"}""")

        /** Where each Read again was asked, and what it answers. */
        val readAgains = mutableListOf<String>()
        var readAgainAnswer = HttpResponse(200, """{"done":true,"said":"Asked of the server: its new reading comes as it's pulled"}""")

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            this.headers += headers
            threads += Thread.currentThread()
            failure?.let { return it(url) }
            if (url.endsWith("/claim")) return HttpResponse(200, """{"id":"device-1","token":"secret-token"}""")
            if (url.endsWith("/api/backup/import")) return HttpResponse(200, Json.encodeToString(Asked.serializer(), Asked(true, "Restored.")))
            if (url.endsWith("/api/chat/try-again")) return HttpResponse(204, "")
            if (url.endsWith("/api/photos/check")) {
                checks += Json.decodeFromString(PhotoCheckAsk.serializer(), body)
                return checkAnswer
            }
            if (url.endsWith("/read-again")) {
                readAgains += url.substringAfter("/api/")
                return readAgainAnswer
            }
            if (url.endsWith("/api/devices/push")) {
                pushIds += Json.decodeFromString(PushId.serializer(), body).fid
                duringPush?.also { duringPush = null }?.invoke()
                return HttpResponse(pushAnswer, "")
            }
            if (url.endsWith("/api/devices/push/test")) return HttpResponse(200, Json.encodeToString(PushTried.serializer(), PushTried(true)))
            seedFailure?.let { return HttpResponse(it, "") }
            val batch = Json.decodeFromString(SeedBatch.serializer(), body)
            seeds += batch
            meanwhile?.also { meanwhile = null }?.invoke()
            val taken = batch.stores.filter { it.name !in refusing }
            taken.forEach { held[it.name] = it.text }
            val answer = SeedAnswer(seeds.size.toLong(), batch.stores.map { it.name }.filter { it in refusing }, version, held.mapValues { Protocol.hash(it.value) })
            return HttpResponse(200, Json.encodeToString(SeedAnswer.serializer(), answer))
        }

        override fun postBytes(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            this.headers += headers
            threads += Thread.currentThread()
            failure?.let { return it(url) }
            check(url.endsWith("/api/photos/tasks")) { url }
            photos += body
            return photoAnswer
        }
    }

    private val dir = Files.createTempDirectory("replica").toFile()
    private val stores = Stores(dir, Plain)
    private val clock = FixedClock(1_000L)
    private val server = Server()
    private var read = CalendarRead()
    private val replica = Replica(stores, server, clock, "5.0.0", calendar = { read })

    private fun paired() = runBlocking { assertNull(replica.pair("123456", "phone")) }

    @Test
    fun `unpaired, nothing is sent`() = runBlocking {
        assertFalse(replica.upload())
        assertEquals(emptyList(), server.seeds)
    }

    @Test
    fun `a code pairs it, and the token goes to the secrets, not the state`() = runBlocking {
        paired()
        assertTrue(replica.paired)
        assertEquals("secret-token", stores.secrets[Secret.DeviceToken])
        assertEquals("device-1", replica.state.value.deviceId)
        assertFalse("secret-token" in stores.sync.text())
    }

    @Test
    fun `a code the server refuses says so, and pairs nothing`() = runBlocking {
        server.failure = { HttpResponse(403, "{}") }
        assertEquals("That code isn't right, or it's run out", replica.pair("000000", "phone"))
        assertFalse(replica.paired)
    }

    @Test
    fun `no network to pair over says so`() = runBlocking {
        server.failure = { throw IOException("offline") }
        assertEquals("The server can't be reached", replica.pair("000000", "phone"))
        assertFalse(replica.paired)
    }

    @Test
    fun `the first send is every store the server keeps, the token as a bearer, the protocol named, the zone said`() = runBlocking {
        paired()
        assertTrue(replica.upload())
        val sent = server.seeds.single()
        assertEquals(Stores.MIRRORED + CalendarRead.NAME, sent.stores.map { it.name }.toSet())
        assertEquals("Europe/London", sent.zone)
        assertEquals("5.0.0", sent.build)
        assertEquals("Bearer secret-token", server.headers.last()["Authorization"])
        assertEquals(Protocol.VERSION.toString(), server.headers.last()[Protocol.HEADER])
    }

    @Test
    fun `what's the phone's alone is never sent`() = runBlocking {
        paired()
        replica.upload()
        val names = server.seeds.single().stores.map { it.name }
        for (own in listOf("runtime.json", "device.json", "calendar.json", "sync.json", "secrets.bin")) assertFalse(own in names, own)
        // Gmail's journal is the server's to carry on from 5.15.0, its writes still to come among it.
        assertTrue("gmail-writes.json" in names)
    }

    @Test
    fun `after that only what's changed goes, and nothing changed sends an empty batch`() = runBlocking {
        paired()
        replica.upload()
        replica.upload()
        assertEquals(emptyList(), server.seeds[1].stores)
        stores.school.update { it.copy(noSchool = emptyList()) }
        stores.memory.update { it.copy(version = it.version) }
        stores.settings.update { it.copy(boxMin = it.boxMin + 5) }
        replica.upload()
        assertEquals(listOf("settings.json"), server.seeds[2].stores.map { it.name })
        // The revision each answer gave, kept from the last: what a server restored from before it is told by (W-26).
        assertEquals(3L, stores.sync.value.seedRevision)
    }

    @Test
    fun `a failed send keeps what's unsent for next time, and says why`() = runBlocking {
        paired()
        server.failure = { throw IOException("offline") }
        assertFalse(replica.upload())
        assertEquals("The server can't be reached", replica.state.value.lastError)
        server.failure = null
        assertTrue(replica.upload())
        assertNull(replica.state.value.lastError)
        assertEquals(1_000L, replica.state.value.lastSyncAt)
        assertEquals("5.0.1", replica.state.value.serverVersion)
        assertTrue(server.seeds.last().stores.isNotEmpty())
    }

    @Test
    fun `a store the server refuses goes again, and is said`() = runBlocking {
        paired()
        server.refusing = setOf("tasks.json")
        replica.upload()
        assertEquals(listOf("tasks.json"), replica.state.value.refused)
        server.refusing = emptySet()
        replica.upload()
        assertEquals(listOf("tasks.json"), server.seeds.last().stores.map { it.name })
        assertEquals(emptyList(), replica.state.value.refused)
    }

    @Test
    fun `a revoked phone and an old one are told what to do`() = runBlocking {
        paired()
        server.failure = { HttpResponse(401, "{}") }
        replica.upload()
        assertEquals("The server doesn't know this phone: pair it again", replica.state.value.lastError)
        server.failure = { HttpResponse(409, "{}") }
        replica.upload()
        assertEquals("The server needs a newer app: install the latest", replica.state.value.lastError)
    }

    @Test
    fun `the chat's archive goes a month a store, and only once it's changed`() = runBlocking {
        paired()
        stores.replace(stores.chatArchive, ChatArchive(listOf(ChatMessage("a", Speaker.You, "words", 1_792_000_000_000L))))
        replica.upload()
        val month = assertNotNull(server.seeds[0].stores.singleOrNull { it.name.startsWith("chat-archive-") })
        assertTrue("words" in month.text)
        replica.upload()
        assertEquals(emptyList(), server.seeds[1].stores)
    }

    @Test
    fun `a month cleared here is cleared there, once`() = runBlocking {
        paired()
        stores.replace(stores.chatArchive, ChatArchive(listOf(ChatMessage("a", Speaker.You, "words", 1_792_000_000_000L))))
        replica.upload()
        val name = server.held.keys.single { it.startsWith("chat-archive-") }
        stores.chatArchive.clear()
        replica.upload()
        assertFalse("words" in server.held.getValue(name))
        assertEquals(listOf(name), server.seeds.last().stores.map { it.name })
        stores.chatArchive.clear()
        replica.upload()
        assertEquals(emptyList(), server.seeds.last().stores)
    }

    @Test
    fun `a month the server refuses goes again with the archive unchanged, and stays said till it's taken`() = runBlocking {
        paired()
        stores.replace(stores.chatArchive, ChatArchive(listOf(ChatMessage("a", Speaker.You, "words", 1_792_000_000_000L))))
        val month = "chat-archive-2026-10.json"
        server.refusing = setOf(month)
        replica.upload()
        assertEquals(listOf(month), replica.state.value.refused)
        replica.upload()
        assertEquals(listOf(month), server.seeds.last().stores.map { it.name })
        assertEquals(listOf(month), replica.state.value.refused)
        server.refusing = emptySet()
        replica.upload()
        assertEquals(emptyList(), replica.state.value.refused)
        assertTrue(month in server.held)
    }

    @Test
    fun `a server restored to an older copy is sent what it's lost, in the same send`() = runBlocking {
        paired()
        stores.replace(stores.chatArchive, ChatArchive(listOf(ChatMessage("a", Speaker.You, "words", 1_792_000_000_000L))))
        replica.upload()
        val before = server.held.toMap()
        stores.settings.update { it.copy(boxMin = it.boxMin + 5) }
        replica.upload()
        assertTrue(server.held != before)
        // Back to the older copy, the phone's hashes newer than what's there: nothing's changed on the phone.
        server.held.clear()
        server.held += before - "settings.json" - "chat-archive-2026-10.json"
        assertTrue(replica.upload())
        assertEquals(setOf("settings.json", "chat-archive-2026-10.json"), server.seeds.last().stores.map { it.name }.toSet())
        assertTrue(""""boxMin"""" in server.held.getValue("settings.json"))
        replica.upload()
        assertEquals(emptyList(), server.seeds.last().stores)
    }

    @Test
    fun `a store changed while a send's on its way goes in the same upload`() = runBlocking {
        paired()
        replica.upload()
        server.meanwhile = { runBlocking { stores.settings.update { it.copy(boxMin = it.boxMin + 5) } } }
        val before = server.seeds.size
        assertTrue(replica.upload())
        assertEquals(before + 2, server.seeds.size)
        assertEquals(listOf("settings.json"), server.seeds.last().stores.map { it.name })
    }

    @Test
    fun `nothing left to send is one send, and a store refused isn't sent round again`() = runBlocking {
        paired()
        replica.upload()
        val before = server.seeds.size
        replica.upload()
        assertEquals(before + 1, server.seeds.size)
        server.refusing = setOf("settings.json")
        stores.settings.update { it.copy(boxMin = it.boxMin + 5) }
        replica.upload()
        assertEquals(before + 2, server.seeds.size)
    }

    @Test
    fun `the calendar as last read goes with the rest`() = runBlocking {
        paired()
        replica.upload()
        read = CalendarRead(answers = mapOf("an event" to "busy"))
        replica.upload()
        assertEquals(listOf(CalendarRead.NAME), server.seeds[1].stores.map { it.name })
    }

    @Test
    fun `forgetting the server lets the token go, and pairing again sends everything`() = runBlocking {
        paired()
        replica.upload()
        replica.forget()
        assertFalse(replica.paired)
        assertFalse(replica.upload())
        paired()
        replica.upload()
        assertTrue(server.seeds.last().stores.size > 1)
    }

    @Test
    fun `the id Firebase registers goes with the next upload that goes through, once a process, and again when it changes`() = runBlocking {
        paired()
        replica.registered("cMadeUpFid000000000aaa")
        assertTrue(replica.upload())
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa"), server.pushIds, "once")
        assertEquals("Bearer secret-token", server.headers.last()["Authorization"])
        replica.registered("cMadeUpFid000000000bbb")
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa", "cMadeUpFid000000000bbb"), server.pushIds)
        // The next process, on the same files: the server may have lost it since, restored, say.
        Replica(Stores(dir, Plain), server, clock, "5.0.0", calendar = { read }).upload()
        assertEquals(listOf("cMadeUpFid000000000aaa", "cMadeUpFid000000000bbb", "cMadeUpFid000000000bbb"), server.pushIds)
    }

    @Test
    fun `an id the server doesn't take goes again at the next upload, and Setup says nothing of it`() = runBlocking {
        paired()
        replica.registered("cMadeUpFid000000000aaa")
        server.pushAnswer = 404
        assertTrue(replica.upload(), "the stores went all the same")
        assertNull(stores.sync.value.lastError)
        server.pushAnswer = 204
        replica.upload()
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa", "cMadeUpFid000000000aaa"), server.pushIds)
    }

    @Test
    fun `the id waits while unpaired or when a send fails, and outlives forgetting the server`() = runBlocking {
        replica.registered("cMadeUpFid000000000aaa")
        assertFalse(replica.upload())
        paired()
        // The seed alone fails: the route for the id would take it, and isn't asked.
        server.seedFailure = 503
        assertFalse(replica.upload())
        assertEquals(emptyList(), server.pushIds, "not after a send that didn't go")
        server.seedFailure = null
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa"), server.pushIds)
        replica.forget()
        assertEquals("cMadeUpFid000000000aaa", stores.sync.value.pushId, "this phone's, so kept")
        paired()
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa", "cMadeUpFid000000000aaa"), server.pushIds, "sent again to the new pairing")
        paired()
        replica.upload()
        assertEquals(3, server.pushIds.size, "and to one made over the last, unpaired or not")
    }

    @Test
    fun `an id whose answer comes after a new pairing is made is sent again to that pairing`() = runBlocking {
        paired()
        replica.registered("cMadeUpFid000000000aaa")
        // Paired again while the id's on its way, under the old token.
        server.duringPush = { runBlocking { assertNull(replica.pair("654321", "phone")) } }
        replica.upload()
        replica.upload()
        assertEquals(listOf("cMadeUpFid000000000aaa", "cMadeUpFid000000000aaa"), server.pushIds)
    }

    @Test
    fun `asked to be told now, it hears what the server said, and asks nothing while unpaired`() = runBlocking {
        assertNull(replica.testPush())
        paired()
        assertEquals(PushTried(true), replica.testPush())
        server.failure = { HttpResponse(401, "") }
        assertEquals(PushTried(false, "The server doesn't know this phone: pair it again"), replica.testPush())
    }

    @Test
    fun `told while mirroring, it sends as at any other time`() = runBlocking {
        paired()
        replica.upload()
        val before = server.seeds.size
        // A revision it holds, as far as its pulls go: while mirroring it has none, and sends all the same.
        assertTrue(replica.told(0))
        assertEquals(before + 1, server.seeds.size)
    }

    @Test
    fun `an import, a Try again and a photo's reading asked of the server go off the thread that asks, a screen's being the main one`() = runBlocking {
        paired()
        val asking = Thread.currentThread()
        server.threads.clear()
        assertTrue(replica.importOnServer("{}").done)
        assertTrue(replica.askAgainOnServer())
        replica.readPhotoOnServer(byteArrayOf(1))
        assertEquals(3, server.threads.size)
        assertTrue(server.threads.none { it == asking }, "none on the thread that asked")
    }

    @Test
    fun `a photo of homework is read by the server, its reading or its reason as the server's reader gave them, and a refusal in its words`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        assertEquals(null to "The photo couldn't be read (this phone isn't paired).", replica.readPhotoOnServer(jpeg))
        assertEquals(emptyList(), server.photos, "nothing's sent unpaired")
        paired()
        val task = OwnTask("Worksheet", notes = "Questions 1 to 6", minutes = 40)
        server.photoAnswer = HttpResponse(200, Json.encodeToString(PhotoRead.serializer(), PhotoRead(listOf(OwnTaskEdit("s-1", task)), listOf("a diagram"), "Which week?")))
        assertEquals(PhotoTasks.Reading(listOf(task), listOf("a diagram"), "Which week?") to null, replica.readPhotoOnServer(jpeg))
        assertTrue(server.photos.single().contentEquals(jpeg), "the photo as it was taken")
        val headers = server.headers.last()
        assertEquals("Bearer secret-token", headers["Authorization"])
        assertEquals("image/jpeg", headers["Content-Type"])
        assertEquals(Protocol.VERSION.toString(), headers[Protocol.HEADER])
        // Read, and no task in it: the reading, for the sheet to say so, with what was left out.
        server.photoAnswer = HttpResponse(200, """{"tasks":[],"leftOut":["a worksheet"]}""")
        assertEquals(PhotoTasks.Reading(emptyList(), listOf("a worksheet")) to null, replica.readPhotoOnServer(jpeg))
        // Not read, and why, as the server's reader said it.
        server.photoAnswer = HttpResponse(200, """{"tasks":[],"why":"Claude is off: add it with New task."}""")
        assertEquals(null to "Claude is off: add it with New task.", replica.readPhotoOnServer(jpeg))
        // Refused: in the server's words, or by its code where it gave none.
        server.photoAnswer = HttpResponse(409, """{"error":"A photo is being read already"}""")
        assertEquals(null to "The photo couldn't be read (a photo is being read already).", replica.readPhotoOnServer(jpeg))
        server.photoAnswer = HttpResponse(502, "<html>Bad gateway</html>")
        assertEquals(null to "The photo couldn't be read (the server said no, 502).", replica.readPhotoOnServer(jpeg))
        server.failure = { throw IOException("offline") }
        assertEquals(null to "The photo couldn't be read (the server can't be reached).", replica.readPhotoOnServer(jpeg))
    }

    @Test
    fun `a photo check's pages go to the server on the photo's wire, in base64, what came of it said in its words`() = runBlocking {
        val slow = Server()
        val checking = Replica(stores, server, clock, "5.0.0", calendar = { read }, photoHttp = slow)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)
        assertEquals("The check didn't go through (this phone isn't paired).", checking.checkPhotoOnServer("teams:sheet", "teams:sheet#0", "Questions 1 to 6", 5L, listOf(jpeg)))
        paired()
        assertEquals("Done: every question answered", checking.checkPhotoOnServer("teams:sheet", "teams:sheet#0", "Questions 1 to 6", 5L, listOf(jpeg, jpeg)))
        val ask = slow.checks.single()
        assertEquals(Triple("teams:sheet", "teams:sheet#0", 5L), Triple(ask.taskId, ask.key, ask.takenAt))
        assertEquals("Questions 1 to 6", ask.step)
        assertEquals(2, ask.pages.size)
        assertTrue(ask.pages.all { Base64.getDecoder().decode(it).contentEquals(jpeg) })
        assertEquals(emptyList(), server.checks, "not on the usual wire")
        slow.checkAnswer = HttpResponse(409, """{"error":"A photo is being checked already"}""")
        assertEquals("The check didn't go through (a photo is being checked already).", checking.checkPhotoOnServer("teams:sheet", "teams:sheet#0", "Questions 1 to 6", 5L, listOf(jpeg)))
        slow.failure = { throw IOException("offline") }
        assertEquals("The check didn't go through (the server can't be reached).", checking.checkPhotoOnServer("teams:sheet", "teams:sheet#0", "Questions 1 to 6", 5L, listOf(jpeg)))
    }

    @Test
    fun `Read again on a phone sending commands asks the server, its task in the path, saying what came of it`() = runBlocking {
        assertEquals("The server couldn't be asked: this phone isn't paired.", replica.readAgainOnServer("teams:sheet"))
        paired()
        assertEquals("Asked of the server: its new reading comes as it's pulled", replica.readAgainOnServer("teams:a sheet"))
        assertEquals(listOf("tasks/teams%3Aa%20sheet/read-again"), server.readAgains)
        server.readAgainAnswer = HttpResponse(409, """{"error":"The server is mirroring the phone"}""")
        assertEquals("The server couldn't be asked: the server is mirroring the phone.", replica.readAgainOnServer("teams:sheet"))
        server.failure = { throw IOException("offline") }
        assertEquals("The server couldn't be reached: try again later.", replica.readAgainOnServer("teams:sheet"))
    }

    @Test
    fun `a photo's reading goes by a wire of its own, which waits out Claude's call on the server`() = runBlocking {
        val slow = Server()
        val reading = Replica(stores, server, clock, "5.0.0", calendar = { read }, photoHttp = slow)
        paired()
        reading.readPhotoOnServer(byteArrayOf(1))
        assertEquals(1, slow.photos.size)
        assertEquals(emptyList(), server.photos)
        assertTrue(Replica.PHOTO_WAIT_MS > ModelEffort.timeout(ModelEffort.Job.PhotoToTask).toMillis(), "longer than the call may take")
    }
}
