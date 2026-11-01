package com.thomaswcode.decrastination.server

import com.interaso.webpush.VapidKeys
import com.sun.net.httpserver.HttpServer
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.felix.Outbox
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
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
import java.time.Duration
import java.util.Base64
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** As a browser gives them: 65 bytes and 16, as URL-safe base64. */
    private val P256DH = "A".repeat(87)
    private val AUTH = "A".repeat(22)
    private val laptop = "https://fcm.googleapis.com/fcm/send/laptop"
    private val other = "https://updates.push.services.mozilla.com/wpush/v2/other"

    private fun asks(endpoint: String, vararg channels: String) = assertTrue(graph.pushes.keep(PushSubscription(endpoint, P256DH, AUTH, channels.toSet())))

    private fun message(payload: String) = Json.decodeFromString<PushMessage>(payload)

    private suspend fun waitFor(count: Int) = withTimeout(5_000) {
        while (service.taken.size < count) delay(10)
    }

    @Test
    fun `an address has to be a known push service's, as the server posts to it, and any other name can point anywhere`() {
        for (address in listOf("https://fcm.googleapis.com/fcm/send/a", "https://updates.push.services.mozilla.com/wpush/v2/a", "https://web.push.apple.com/a", "https://wns2-par02p.notify.windows.com/w/?token=a")) {
            assertTrue(Pushes.fit(address), address)
        }
        for (address in listOf(
            "http://fcm.googleapis.com/a", "https://fcm.googleapis.com:8443/a", "https://user" + "@fcm.googleapis.com/a", "https://fcm.googleapis.com.example.com/a",
            "https://notfcm.googleapis.com/a", "https://push.example.com/a", "https://localhost/a", "https://127.0.0.1/a", "https://[::1]/a", "not an address",
            "https://fcm.googleapis.com/" + "a".repeat(1100),
        )) {
            assertFalse(Pushes.fit(address), address)
        }
        assertFalse(graph.pushes.keep(PushSubscription("https://push.example.com/a", P256DH, AUTH, setOf(Pushes.FELIX))))
        assertEquals(emptySet(), graph.pushes.channels("https://push.example.com/a"))
    }

    @Test
    fun `keys that couldn't be sealed for aren't kept, as every send would fail and it'd never be let go of`() {
        for ((p256dh, auth) in listOf("" to AUTH, P256DH to "", "A".repeat(80) to AUTH, P256DH to "A".repeat(20), "not base64!" to AUTH, P256DH to "not base64!")) {
            assertFalse(graph.pushes.keep(PushSubscription(laptop, p256dh, auth, setOf(Pushes.FELIX))), "$p256dh $auth")
        }
        assertEquals(emptySet(), graph.pushes.channels(laptop))
        assertTrue(graph.pushes.keep(PushSubscription(laptop, P256DH, AUTH, setOf(Pushes.FELIX))))
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
        asks("https://web.push.apple.com/failing", Pushes.FELIX)
        asks(laptop, Pushes.FELIX)
        asks(other, Pushes.FELIX)
        service.failing += "https://web.push.apple.com/failing"
        service.gone += laptop
        graph.pushes.push(Pushes.FELIX, PushMessage("A title", "Words", "/", "felix"))
        waitFor(2)
        withTimeout(5_000) { while (graph.pushes.channels(laptop).isNotEmpty()) delay(10) }
        assertEquals(setOf(Pushes.FELIX), graph.pushes.channels(other))
        assertEquals(setOf(Pushes.FELIX), graph.pushes.channels("https://web.push.apple.com/failing"))
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

    @Test
    fun `what's told is cut at a whole sentence, or a whole word`() {
        assertEquals("Short.", Pushes.words("Short."))
        val sentences = Pushes.words("A made-up sentence. ".repeat(30).trim())
        assertTrue(sentences.length <= 300, sentences)
        assertTrue(sentences.endsWith("made-up sentence."), sentences)
        val onAndOn = Pushes.words("word ".repeat(100).trim())
        assertTrue(onAndOn.length <= 301, onAndOn)
        assertTrue(onAndOn.endsWith(" word…"), onAndOn)
    }

    @Test
    fun `his long words reach a browser cut`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        said("m", his("m", "A made-up sentence. ".repeat(30).trim()))
        waitFor(1)
        assertEquals(Pushes.words("A made-up sentence. ".repeat(30).trim()), message(service.taken.single().second).body)
    }

    @Test
    fun `keys as they're kept in the box's env read back the same`() {
        val keys = VapidKeys.generate()
        assertContentEquals(keys.applicationServerKey, VapidKeys.create(keys.x509PublicKey, keys.pkcs8PrivateKey).applicationServerKey)
    }

    private fun his(id: String, text: String, kind: MessageKind = MessageKind.Chat) = ChatMessage(id, Speaker.Felix, text, System.currentTimeMillis(), kind = kind)

    /** [messages] in the chat, and his notification made from it as the phone's is, cut to its room, sounding for [alertFor]. */
    private suspend fun said(alertFor: String?, vararg messages: ChatMessage) {
        graph.stores.chat.update { it.copy(messages = it.messages + messages) }
        graph.owners.ports.show(assertNotNull(Outbox.notice(graph.stores.chat.value, "Felix", quiet = false, alertFor = alertFor)))
    }

    @Test
    fun `his notification made again says nothing new, and goes nowhere`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        said(null, his("m", "A made-up word"))
        delay(100)
        assertTrue(service.taken.isEmpty())
    }

    @Test
    fun `a message he splits goes whole, and only that one`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        said("m", his("older", "An older word"), his("m", "His first text\nHis last", MessageKind.Morning))
        waitFor(1)
        assertEquals("His first text\nHis last", message(service.taken.single().second).body)
    }

    @Test
    fun `a long message goes from its opening, though his notification only has room for its end`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        val end = "A made-up sentence. ".repeat(20).trim()
        said("m", his("m", "A made-up opening.\n$end", MessageKind.Morning))
        assertTrue(assertNotNull(Outbox.notice(graph.stores.chat.value, "Felix", quiet = false)).lines.none { it.text.startsWith("A made-up opening") })
        waitFor(1)
        val body = message(service.taken.single().second).body
        assertTrue(body.startsWith("A made-up opening.\nA made-up sentence."), body)
    }

    @Test
    fun `what a message made goes with it, as on the phone`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        said("m", his("m", "That's in.").copy(proposals = listOf(Proposal("p", OwnTask(title = "Made-up essay"), state = ProposalState.Applied))))
        waitFor(1)
        assertEquals("That's in.\nAdded “Made-up essay”", message(service.taken.single().second).body)
    }

    @Test
    fun `what a command says is told only once it stands`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        var before = -1
        db.command {
            graph.owners.conversation.reviewed("A made-up word on the week")
            delay(200)
            before = service.taken.size
        }
        assertEquals(0, before)
        waitFor(1)
        assertEquals("A made-up word on the week", message(service.taken.single().second).body)
    }

    @Test
    fun `what a command that fails said is told to nobody, as it's put right or by the next`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        val answer = db.commandOr({ graph.stores.chat.reload() }, { "put right" }) {
            graph.owners.conversation.reviewed("A made-up word on the week")
            error("made up")
        }
        assertEquals("put right", answer)
        db.command { db.setMeta("probe", "the next") }
        delay(200)
        assertTrue(service.taken.isEmpty())
    }

    @Test
    fun `what's told is his newest, not a reply of yours under it`() = runBlocking<Unit> {
        asks(laptop, Pushes.FELIX)
        said("m", his("m", "His word"), ChatMessage("y", Speaker.You, "Your reply", System.currentTimeMillis(), replyTo = "m"))
        waitFor(1)
        assertEquals("His word", message(service.taken.single().second).body)
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
    fun `the jobs look each minute`() = runBlocking<Unit> {
        asks(laptop, Pushes.TIMER)
        val now = System.currentTimeMillis()
        graph.stores.session.update { it.copy(timer = timer(now, 30_000L)) }
        db.setMeta("pushed", (now - 60_000L).toString())
        graph.jobs.tick()
        waitFor(1)
    }

    @Test
    fun `with no keys there's no looking`() {
        val keyless = Database(File(dir, "keyless.db").path).apply { setMeta("mode", ServerGraph.COMMANDS) }
        ServerGraph(keyless, File(dir, "keyless").apply { mkdirs() }, Plain, "test").pushDue()
        assertNull(keyless.meta("pushed"))
        keyless.close()
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

    // A limit of its own: without the sender's, this would wait for ever.
    @Test(timeout = 10_000)
    fun `a service that never answers is given up on`() {
        val browser = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val stand = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stand.createContext("/never") { exchange -> exchange.requestBody.readBytes() }
        stand.start()
        try {
            val sender = WebPushSender("https://example.com", VapidKeys.generate(), Duration.ofMillis(300))
            val subscription = PushSubscription("http://127.0.0.1:${stand.address.port}/never", encoder.encodeToString(point(browser.public as ECPublicKey)), encoder.encodeToString(ByteArray(16)), emptySet())
            assertFailsWith<HttpTimeoutException> { sender.send(subscription, "A made-up word") }
        } finally {
            stand.stop(0)
        }
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
