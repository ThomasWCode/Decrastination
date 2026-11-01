package com.thomaswcode.decrastination.server

import com.interaso.webpush.VapidKeys
import com.sun.net.httpserver.HttpServer
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/** A browser told with its tab closed (W-9): who's told of what, and that what's sent is what a browser can open. */
class PushesTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** A push service: what it took, and which addresses are gone or fail. */
    private class Service : Pushes.Sender {
        val taken: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val gone: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        override fun send(subscription: PushSubscription, payload: String): Boolean {
            check(subscription.endpoint !in failing) { "made up" }
            taken += subscription.endpoint to payload
            return subscription.endpoint !in gone
        }
    }

    private val dir: File = Files.createTempDirectory("pushes").toFile()
    private val db = Database(File(dir, "test.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
    private val service = Service()
    private val graph = ServerGraph(db, dir, Plain, "test", pushSender = service, pushKey = "made-up-key")

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private val laptop = "https://push.example.com/laptop"
    private val other = "https://push.example.com/other"

    private fun asks(endpoint: String, vararg channels: String) = assertTrue(graph.pushes.keep(PushSubscription(endpoint, "made-up", "made-up", channels.toSet())))

    private fun message(payload: String) = Json.decodeFromString<PushMessage>(payload)

    private suspend fun waitFor(count: Int) = withTimeout(5_000) {
        while (service.taken.size < count) delay(10)
    }

    @Test
    fun `an address has to be a push service's, as the server posts to it`() {
        assertTrue(Pushes.fit("https://fcm.googleapis.com/fcm/send/made-up"))
        assertTrue(Pushes.fit("https://updates.push.services.mozilla.com/wpush/v2/made-up"))
        for (address in listOf("http://push.example.com/a", "https://localhost/a", "https://127.0.0.1/a", "https://10.0.0.1/a", "https://[::1]/a", "https://server:8080/a", "https://metadata.internal/a", "https://box.local/a", "not an address", "https://push.example.com/" + "a".repeat(1100))) {
            assertFalse(Pushes.fit(address), address)
        }
        assertFalse(graph.pushes.keep(PushSubscription("http://push.example.com/a", "made-up", "made-up", setOf(Pushes.FELIX))))
        assertEquals(emptySet(), graph.pushes.channels("http://push.example.com/a"))
    }

    @Test
    fun `a browser's told only of what it asked for, and only what's known is kept`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX, "made up")
        asks(other, Pushes.TIMER)
        assertEquals(setOf(Pushes.FELIX), graph.pushes.channels(laptop))
        graph.pushes.push(Pushes.FELIX, PushMessage("A title", "Words", "/", "felix"))
        waitFor(1)
        delay(100)
        assertEquals(listOf(laptop), service.taken.map { it.first })
        assertEquals(PushMessage("A title", "Words", "/", "felix"), message(service.taken.single().second))
    }

    @Test
    fun `asking again changes what it's told of, and it can stop`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        asks(laptop, Pushes.TIMER, Pushes.SLOTS)
        assertEquals(setOf(Pushes.TIMER, Pushes.SLOTS), graph.pushes.channels(laptop))
        graph.pushes.forget(laptop)
        assertEquals(emptySet(), graph.pushes.channels(laptop))
    }

    @Test
    fun `a browser that's gone is let go of, and one that fails is kept, and neither stops the rest`() = runBlocking<Unit> {
        asks("https://push.example.com/failing", Pushes.FELIX)
        asks(laptop, Pushes.FELIX)
        asks(other, Pushes.FELIX)
        service.failing += "https://push.example.com/failing"
        service.gone += laptop
        graph.pushes.push(Pushes.FELIX, PushMessage("A title", "Words", "/", "felix"))
        waitFor(2)
        withTimeout(5_000) { while (graph.pushes.channels(laptop).isNotEmpty()) delay(10) }
        assertEquals(setOf(Pushes.FELIX), graph.pushes.channels(other))
        assertEquals(setOf(Pushes.FELIX), graph.pushes.channels("https://push.example.com/failing"))
    }

    @Test
    fun `his new message goes to a browser, by his name, in his newest words`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        graph.owners.conversation.reviewed("A made-up word on the week")
        waitFor(1)
        val told = message(service.taken.single().second)
        assertEquals(graph.stores.settings.value.felixName, told.title)
        assertEquals("A made-up word on the week", told.body)
        assertEquals("/", told.url)
        assertEquals(Pushes.FELIX, told.tag)
    }

    private fun timer(now: Long, overMs: Long) = PieceTimer("own:s#-1#1", "own:s", null, null, "Made-up essay", 30, now - 30 * 60_000L - overMs)

    @Test
    fun `a timer's zero is told once, and left unmarked for the phone's own`() = runBlocking<Unit> {
        asks(laptop, Pushes.TIMER)
        val now = System.currentTimeMillis()
        val running = timer(now, 30_000L)
        graph.stores.session.update { it.copy(timer = running) }
        db.setMeta("pushed", (now - 60_000L).toString())
        graph.pushDue()
        waitFor(1)
        assertEquals(PushMessage("Time's up: Made-up essay", "30 min on it. Tick it when it's done, or stop the timer.", "/day", Pushes.TIMER), message(service.taken.single().second))
        graph.pushDue()
        delay(100)
        assertEquals(1, service.taken.size)
        assertFalse(assertNotNull(graph.stores.session.value.timer).told)
    }

    @Test
    fun `a timer stopped is told nothing, nor one whose zero's long gone`() = runBlocking<Unit> {
        asks(laptop, Pushes.TIMER)
        val now = System.currentTimeMillis()
        graph.stores.session.update { it.copy(timer = timer(now, 30_000L).copy(stoppedAt = now - 1L)) }
        db.setMeta("pushed", (now - 60_000L).toString())
        graph.pushDue()
        graph.stores.session.update { it.copy(timer = timer(now, 10 * 60_000L)) }
        db.setMeta("pushed", (now - 3_600_000L).toString())
        graph.pushDue()
        delay(100)
        assertTrue(service.taken.isEmpty())
    }

    @Test
    fun `the first look tells nothing of what came before it`() = runBlocking<Unit> {
        asks(laptop, Pushes.TIMER)
        val now = System.currentTimeMillis()
        graph.stores.session.update { it.copy(timer = timer(now, 30_000L)) }
        graph.pushDue()
        delay(100)
        assertTrue(service.taken.isEmpty())
        assertNotNull(db.meta("pushed"))
    }

    @Test
    fun `a slot that's started is told, and one that isn't, or a break, isn't`() = runBlocking<Unit> {
        graph.applier.apply(CommandBatch(listOf(Command("own", System.currentTimeMillis(), SaveOwnTask("s", OwnTask(title = "Made-up essay", minutes = 30))))), Client.WEBSITE)
        val plan = graph.planning.plan()
        val slot = plan.buckets.flatMap { it.slots }.first { it.kind == SlotKind.Work }
        val told = graph.due(plan, null, slot.start - 1, slot.start)
        assertEquals(listOf(Pushes.SLOTS), told.map { it.first })
        assertEquals(PushMessage("Now: Made-up essay", "30 min", "/day", Pushes.SLOTS), told.single().second)
        assertTrue(graph.due(plan, null, slot.start, slot.start).isEmpty(), "told at the last look")
        assertTrue(graph.due(plan, null, slot.start - 2, slot.start - 1).isEmpty(), "still to come")
    }

    @Test
    fun `the page's offered it only with the box's keys and commands`() {
        assertEquals("made-up-key", assertNotNull(graph.settings().push).key)
        val mirror = Database(File(dir, "mirror.db").path)
        assertNull(ServerGraph(mirror, File(dir, "mirror").apply { mkdirs() }, Plain, "test", pushSender = service, pushKey = "made-up-key").settings().push)
        mirror.close()
        val keyless = Database(File(dir, "keyless.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
        assertNull(ServerGraph(keyless, File(dir, "keyless").apply { mkdirs() }, Plain, "test").settings().push)
        keyless.close()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF's expand, for what RFC 8291 takes, one block at most. */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int) = hmac(prk, info + 1).copyOf(length)

    private fun point(key: ECPublicKey): ByteArray {
        fun fixed(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    /** A push's body opened as a browser opens it (RFC 8291, RFC 8188), with its [keys] and [auth]. */
    private fun open(body: ByteArray, keys: java.security.KeyPair, auth: ByteArray): String {
        val read = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also(read::get)
        read.int
        val theirs = ByteArray(read.get().toInt()).also(read::get)
        val curve = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val sender = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, theirs.copyOfRange(1, 33)), BigInteger(1, theirs.copyOfRange(33, 65))), curve))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keys.private)
            doPhase(sender, true)
            generateSecret()
        }
        val info = "WebPush: info".toByteArray() + 0 + point(keys.public as ECPublicKey) + theirs
        val ikm = expand(hmac(auth, shared), info, 32)
        val prk = hmac(salt, ikm)
        val key = expand(prk, "Content-Encoding: aes128gcm".toByteArray() + 0, 16)
        val nonce = expand(prk, "Content-Encoding: nonce".toByteArray() + 0, 12)
        val sealed = ByteArray(read.remaining()).also(read::get)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(sealed)
        }
        return plain.copyOf(plain.indexOfLast { it.toInt() != 0 }).decodeToString()
    }

    @Test
    fun `what's sent is what a browser can open, and a service saying it's gone is heard`() {
        val browser = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val auth = ByteArray(16) { it.toByte() }
        fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val bodies = Collections.synchronizedList(mutableListOf<ByteArray>())
        val headers = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val stand = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stand.createContext("/here") { exchange ->
            bodies += exchange.requestBody.readBytes()
            headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        stand.createContext("/gone") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(410, -1)
            exchange.close()
        }
        stand.start()
        try {
            val sender = WebPushSender("https://example.com", VapidKeys.generate())
            val base = "http://127.0.0.1:${stand.address.port}"
            assertTrue(sender.send(PushSubscription("$base/here", encoded(point(browser.public as ECPublicKey)), encoded(auth), emptySet()), "A made-up word"))
            assertEquals("A made-up word", open(bodies.single(), browser, auth))
            val sent = headers.single()
            assertEquals("aes128gcm", sent["content-encoding"])
            assertEquals("86400", sent["ttl"])
            assertTrue(assertNotNull(sent["authorization"]).startsWith("vapid "), sent["authorization"])
            assertFalse(sender.send(PushSubscription("$base/gone", encoded(point(browser.public as ECPublicKey)), encoded(auth), emptySet()), "A made-up word"))
        } finally {
            stand.stop(0)
        }
    }
}
