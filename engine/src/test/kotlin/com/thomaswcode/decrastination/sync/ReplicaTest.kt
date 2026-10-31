package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.FixedClock
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.Stores
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import java.io.IOException
import java.nio.file.Files
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

        /** What happens on the phone while a send is on its way, once. */
        var meanwhile: (() -> Unit)? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            this.headers += headers
            failure?.let { return it(url) }
            if (url.endsWith("/claim")) return HttpResponse(200, """{"id":"device-1","token":"secret-token"}""")
            val batch = Json.decodeFromString(SeedBatch.serializer(), body)
            seeds += batch
            meanwhile?.also { meanwhile = null }?.invoke()
            val taken = batch.stores.filter { it.name !in refusing }
            taken.forEach { held[it.name] = it.text }
            val answer = SeedAnswer(seeds.size.toLong(), batch.stores.map { it.name }.filter { it in refusing }, version, held.mapValues { Protocol.hash(it.value) })
            return HttpResponse(200, Json.encodeToString(SeedAnswer.serializer(), answer))
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
        for (own in listOf("runtime.json", "device.json", "calendar.json", "sync.json", "gmail-writes.json", "secrets.bin")) assertFalse(own in names, own)
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
}
