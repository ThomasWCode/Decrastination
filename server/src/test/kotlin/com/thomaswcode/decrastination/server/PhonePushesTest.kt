package com.thomaswcode.decrastination.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.DeviceClaim
import com.thomaswcode.decrastination.sync.PushTried
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.SaveOwnTask
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.sql.DriverManager
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** A phone told at once (W-18): who's told of what, when, what's sent to Firebase, and what's let go. */
class PhonePushesTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    /** Firebase as a test has it: what it took, by id, and which ids it doesn't know or fails on. */
    private class Firebase : PhonePushes.Sender {
        val taken: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
        val gone: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        /** Done as an id's sent to, before it's answered: a phone sending a new one meanwhile, say. */
        var meanwhile: () -> Unit = {}

        override fun send(fid: String, revision: Long): Boolean {
            meanwhile()
            if (fid in failing) throw FcmSender.Refused(503)
            taken += fid to revision
            return fid !in gone
        }
    }

    private val dir: File = Files.createTempDirectory("phones").toFile()
    private val db = Database(File(dir, "test.db").path)
    private val firebase = Firebase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var telling = true
    private val phones = PhonePushes(db, scope, firebase, telling = { telling }, waitMs = WAIT_MS)
    private val devices = Devices(db)

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    /** A phone paired, its id. */
    private fun phone(name: String, on: Devices = devices): String = on.claim(DeviceClaim(on.pair().code, name))!!.id

    private fun fidOf(device: String): String? = db.read { c ->
        c.prepareStatement("SELECT fid FROM devices WHERE id = ?").use { q ->
            q.setString(1, device)
            q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    /** What reached each id, once the wait and its sends are surely over. */
    private suspend fun settled(): List<Pair<String, Long>> {
        delay(WAIT_MS * 6)
        return firebase.taken.toList()
    }

    @Test
    fun `a run of changes is one message to each phone, at the newest revision, and the phone that made them isn't told`() = runBlocking<Unit> {
        val mine = phone("This phone")
        val other = phone("Another")
        assertTrue(phones.keep(mine, "cMineFid00000000000000"))
        assertTrue(phones.keep(other, "cOtherFid0000000000000"))
        // Two commits can be heard out of their order, each after its own transaction.
        phones.changed(6, by = mine)
        phones.changed(5, by = mine)
        assertEquals(listOf("cOtherFid0000000000000" to 6L), settled())
    }

    @Test
    fun `a phone that made a change is told of another's that came with it`() = runBlocking<Unit> {
        val mine = phone("This phone")
        val other = phone("Another")
        phones.keep(mine, "cMineFid00000000000000")
        phones.keep(other, "cOtherFid0000000000000")
        phones.changed(7, by = mine)
        phones.changed(8, by = Client.WEBSITE)
        assertEquals(setOf("cMineFid00000000000000" to 8L, "cOtherFid0000000000000" to 8L), settled().toSet())
        assertEquals(2, firebase.taken.size, "once each")
    }

    @Test
    fun `changes after a message has gone are another message`() = runBlocking<Unit> {
        val mine = phone("This phone")
        phones.keep(mine, "cMineFid00000000000000")
        phones.changed(3, by = PhonePushes.SERVER)
        assertEquals(listOf("cMineFid00000000000000" to 3L), settled())
        phones.changed(4, by = Client.WEBSITE)
        assertEquals(listOf("cMineFid00000000000000" to 3L, "cMineFid00000000000000" to 4L), settled())
        phones.changed(5, by = mine)
        assertEquals(listOf("cMineFid00000000000000" to 3L, "cMineFid00000000000000" to 4L), settled(), "its own alone, with what made the last gone with it")
    }

    @Test
    fun `nothing's told while the server mirrors, nor without the box's service account`() = runBlocking<Unit> {
        val mine = phone("This phone")
        phones.keep(mine, "cMineFid00000000000000")
        telling = false
        phones.changed(5, by = Client.WEBSITE)
        assertEquals(emptyList(), settled())
        val unset = PhonePushes(db, scope, null, telling = { true }, waitMs = WAIT_MS)
        assertFalse(unset.on)
        unset.changed(6, by = Client.WEBSITE)
        assertEquals(emptyList(), settled())
        assertEquals(PushTried(false, PhonePushes.NOT_SET_UP), unset.test(mine, 6))
    }

    @Test
    fun `an id Firebase no longer knows is let go, but not one sent since, and a failure stops no other phone`() = runBlocking<Unit> {
        val gone = phone("Gone")
        val renewed = phone("Renewed")
        val failing = phone("Failing")
        val fine = phone("Fine")
        phones.keep(gone, "cGoneFid00000000000000")
        phones.keep(renewed, "cRenewedOld00000000000")
        phones.keep(failing, "cFailingFid00000000000")
        phones.keep(fine, "cFineFid00000000000000")
        firebase.gone += setOf("cGoneFid00000000000000", "cRenewedOld00000000000")
        firebase.failing += "cFailingFid00000000000"
        // The renewed phone's new id arrives as its old one's being sent to.
        firebase.meanwhile = { if (fidOf(renewed) == "cRenewedOld00000000000") phones.keep(renewed, "cRenewedNew00000000000") }
        phones.changed(9, by = Client.WEBSITE)
        assertTrue(settled().contains("cFineFid00000000000000" to 9L), "the rest still told")
        assertNull(fidOf(gone), "let go")
        assertEquals("cRenewedNew00000000000", fidOf(renewed), "the new one kept")
        assertEquals("cFailingFid00000000000", fidOf(failing), "a failure isn't an id gone")
    }

    @Test
    fun `a phone revoked is told nothing, and what isn't an id's shape isn't kept`() = runBlocking<Unit> {
        val kept = phone("Kept")
        val revoked = phone("Revoked")
        phones.keep(kept, "cKeptFid00000000000000")
        phones.keep(revoked, "cRevokedFid00000000000")
        devices.revoke(revoked)
        assertFalse(phones.keep(kept, "has spaces in it"))
        assertFalse(phones.keep(kept, "a".repeat(257)))
        assertFalse(phones.keep(kept, ""))
        assertEquals("cKeptFid00000000000000", fidOf(kept))
        phones.changed(2, by = Client.WEBSITE)
        assertEquals(listOf("cKeptFid00000000000000" to 2L), settled())
        assertTrue(phones.keep(revoked, "cRevokedNew00000000000"), "a revoked phone's word is taken, and kept nowhere")
        assertEquals("cRevokedFid00000000000", fidOf(revoked))
    }

    @Test
    fun `a phone asking to be told now is, whatever the mode, and hears why if not`() = runBlocking<Unit> {
        telling = false
        val mine = phone("This phone")
        val untold = phone("No id")
        val failing = phone("Failing")
        phones.keep(mine, "cMineFid00000000000000")
        phones.keep(failing, "cFailingFid00000000000")
        firebase.failing += "cFailingFid00000000000"
        assertEquals(PushTried(true), phones.test(mine, 12))
        assertEquals(listOf("cMineFid00000000000000" to 12L), firebase.taken)
        assertEquals(PushTried(false, PhonePushes.NO_ID), phones.test(untold, 12))
        assertEquals(PushTried(false, "Firebase didn't take it: 503"), phones.test(failing, 12))
        firebase.gone += "cMineFid00000000000000"
        assertEquals(PushTried(false, PhonePushes.GONE), phones.test(mine, 13))
        assertNull(fidOf(mine))
    }

    @Test
    fun `a devices table from before takes the column once, its phones kept`() {
        val file = File(dir, "old.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER)")
                s.execute("INSERT INTO devices(id, name, token_hash, created_at) VALUES('old', 'An old phone', 'hash', 1)")
            }
        }
        Database(file.path).close()
        val again = Database(file.path)
        try {
            assertEquals(listOf("An old phone"), Devices(again).list().map { it.name })
            assertTrue(PhonePushes(again, scope, firebase, telling = { true }).keep("old", "cOldFid000000000000000"))
            assertEquals("cOldFid000000000000000", again.read { c -> c.createStatement().use { s -> s.executeQuery("SELECT fid FROM devices").use { it.next(); it.getString(1) } } })
        } finally {
            again.close()
        }
    }

    @Test
    fun `on the server, a phone's own command tells only the others, and the website's and the server's own work tell it too`() = runBlocking<Unit> {
        db.setMeta("mode", ServerGraph.COMMANDS)
        val graph = ServerGraph(db, dir, Plain, "test", phoneSender = firebase)
        val mine = phone("This phone", graph.devices)
        val other = phone("Another", graph.devices)
        graph.phones.keep(mine, "cMineFid00000000000000")
        graph.phones.keep(other, "cOtherFid0000000000000")
        val at = System.currentTimeMillis()
        graph.applier.apply(CommandBatch(listOf(Command("one", at, SaveOwnTask("s-Essay", OwnTask(title = "Essay"))))), mine)
        assertEquals(listOf("cOtherFid0000000000000"), told(), "not the phone that made it")
        graph.applier.apply(CommandBatch(listOf(Command("two", at, SaveOwnTask("s-Maths", OwnTask(title = "Maths"))))), Client.WEBSITE)
        assertEquals(setOf("cMineFid00000000000000", "cOtherFid0000000000000"), told().toSet())
        graph.stores.tasks.update { state -> state.copy(tasks = state.tasks.map { it.copy(detail = "A job's") }) }
        assertEquals(setOf("cMineFid00000000000000", "cOtherFid0000000000000"), told().toSet(), "a job's write is the server's own")
        assertTrue(firebase.taken.all { (_, revision) -> revision <= db.currentRevision() })
    }

    @Test
    fun `on a server that mirrors, no phone's told, whatever's written`() = runBlocking<Unit> {
        val graph = ServerGraph(db, dir, Plain, "test", phoneSender = firebase)
        val mine = phone("This phone", graph.devices)
        graph.phones.keep(mine, "cMineFid00000000000000")
        // A write from outside a seed's transaction, so the backend tells of it as a job's.
        graph.backend.keep("tasks.json", 1, """{"tasks":[]}""")
        assertTrue(db.currentRevision() > 0)
        delay(PhonePushes.WAIT_MS + 500)
        assertEquals(emptyList(), firebase.taken.toList())
    }

    /** The ids told since the last asked, once the server's wait has gone by. */
    private suspend fun told(): List<String> {
        val before = firebase.taken.size
        withTimeout(PhonePushes.WAIT_MS * 3) {
            while (firebase.taken.size == before) delay(20)
        }
        delay(WAIT_MS * 6)
        return firebase.taken.drop(before).map { it.first }
    }

    // What goes to Google, on a stand-in for it.

    /** Google as a test has it: its token's endpoint and Firebase's, each answering as [answers] says. */
    private class Google(private val keys: KeyPair) : AutoCloseable {
        val forms: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        val sends: MutableList<Pair<String?, JsonObject>> = Collections.synchronizedList(mutableListOf())

        /** Each send's answer in turn, the last for every one after: a status and its body. */
        var answers: List<Pair<Int, String>> = listOf(200 to """{"name":"projects/test-project/messages/1"}""")
        var tokens = 0
        private val stand = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${stand.address.port}"

        init {
            stand.createContext("/token") { exchange ->
                val form = exchange.requestBody.readBytes().decodeToString().split("&").associate { pair ->
                    pair.substringBefore("=") to URLDecoder.decode(pair.substringAfter("="), Charsets.UTF_8)
                }
                forms += form
                tokens++
                answer(exchange, 200, """{"access_token":"access-$tokens","expires_in":3599,"token_type":"Bearer"}""")
            }
            stand.createContext("/v1/projects/test-project/messages:send") { exchange ->
                val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                sends += exchange.requestHeaders.getFirst("Authorization") to body
                val (status, text) = answers[minOf(sends.size, answers.size) - 1]
                answer(exchange, status, text)
            }
            stand.start()
        }

        private fun answer(exchange: HttpExchange, status: Int, text: String) {
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        /** Whether [jwt] was signed with the service account's key. */
        fun signed(jwt: String): Boolean {
            val (header, body, signature) = jwt.split(".")
            return Signature.getInstance("SHA256withRSA").run {
                initVerify(keys.public)
                update("$header.$body".toByteArray())
                verify(Base64.getUrlDecoder().decode(signature))
            }
        }

        override fun close() = stand.stop(0)
    }

    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048)
        generateKeyPair()
    }

    /** A key file as Firebase's console gives it, made up, with its token's endpoint at [tokenUri]. */
    private fun keyFile(tokenUri: String): String {
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        return buildJsonObject {
            put("type", "service_account")
            put("project_id", "test-project")
            put("private_key_id", "made-up")
            put("private_key", pem)
            put("client_email", "made-up@test-project.iam.gserviceaccount.com")
            put("token_uri", tokenUri)
        }.toString()
    }

    private var now = 1_800_000_000_000L

    private fun sender(google: Google) = FcmSender(ServiceAccount.read(keyFile("${google.base}/token")), google.base, clock = { now })

    @Test
    fun `what's sent says only the revision, at high priority, in place of the last, for an hour, under a token signed for as RFC 7523 has it`() {
        Google(keys).use { google ->
            assertTrue(sender(google).send("cPhoneFid0000000000000", 12))
            val form = google.forms.single()
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
            val jwt = form.getValue("assertion")
            assertTrue(google.signed(jwt), "signed with the account's key")
            assertEquals("""{"alg":"RS256","typ":"JWT"}""", Base64.getUrlDecoder().decode(jwt.split(".")[0]).decodeToString())
            val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(jwt.split(".")[1]).decodeToString()).jsonObject
            assertEquals("made-up@test-project.iam.gserviceaccount.com", claims.getValue("iss").jsonPrimitive.content)
            assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims.getValue("scope").jsonPrimitive.content)
            assertEquals("${google.base}/token", claims.getValue("aud").jsonPrimitive.content)
            assertEquals(now / 1000, claims.getValue("iat").jsonPrimitive.long)
            assertEquals(3600L, claims.getValue("exp").jsonPrimitive.long - claims.getValue("iat").jsonPrimitive.long)
            val (authorization, body) = google.sends.single()
            assertEquals("Bearer access-1", authorization)
            assertEquals(
                Json.parseToJsonElement("""{"message":{"fid":"cPhoneFid0000000000000","data":{"revision":"12"},"android":{"priority":"HIGH","collapse_key":"state","ttl":"3600s"}}}"""),
                body,
            )
        }
    }

    @Test
    fun `Google's token is kept till near its end, signed for again then, and once at a 401`() {
        Google(keys).use { google ->
            val sender = sender(google)
            sender.send("cPhoneFid0000000000000", 1)
            now += 50 * 60_000L
            sender.send("cPhoneFid0000000000000", 2)
            assertEquals(1, google.tokens, "kept for most of its hour")
            now += 6 * 60_000L
            sender.send("cPhoneFid0000000000000", 3)
            assertEquals(2, google.tokens, "signed for again before it runs out")
            google.answers = listOf(401 to """{"error":{"status":"UNAUTHENTICATED"}}""", 200 to "{}")
            google.sends.clear()
            assertTrue(sender.send("cPhoneFid0000000000000", 4))
            assertEquals(3, google.tokens)
            assertEquals(listOf("Bearer access-2", "Bearer access-3"), google.sends.map { it.first })
        }
    }

    @Test
    fun `an id Firebase doesn't know, or another project's, is gone, and any other no is thrown by its status alone, a 400 among them`() {
        Google(keys).use { google ->
            val sender = sender(google)
            fun answered(status: Int, body: String): Boolean {
                google.answers = listOf(status to body)
                return sender.send("cPhoneFid0000000000000", 1)
            }
            // As Firebase answered a probe on 3 Nov 2026 for a made-up id, of the right shape and of none.
            assertFalse(answered(404, """{"error":{"code":404,"message":"NotRegistered","status":"NOT_FOUND","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}"""))
            assertFalse(answered(403, """{"error":{"status":"PERMISSION_DENIED","details":[{"errorCode":"SENDER_ID_MISMATCH"}]}}"""))
            // A 400 too, even naming its field, as Firebase answered for a token that wasn't one: it's as likely a fault in the message.
            val invalid = """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"message.fid"}]}]}}"""
            for ((status, body) in listOf(403 to """{"error":{"status":"PERMISSION_DENIED"}}""", 400 to invalid, 429 to "{}", 503 to "{}")) {
                val refused = assertFailsWith<FcmSender.Refused> { answered(status, body) }
                assertEquals(status, refused.status)
                assertEquals("Refused with $status", refused.message, "nothing of the body, as it can name the id")
            }
        }
    }

    @Test
    fun `the key file reads as Firebase's console gives it, and the env's base64 of it the same`() {
        val file = keyFile("https://oauth2.googleapis.com/token")
        val read = ServiceAccount.read(file)
        assertEquals("test-project", read.projectId)
        assertEquals(read, ServiceAccount.read(Base64.getMimeDecoder().decode(Base64.getEncoder().encodeToString(file.toByteArray())).decodeToString()))
        assertEquals("https://oauth2.googleapis.com/token", ServiceAccount.read("""{"project_id":"p","client_email":"e","private_key":"k"}""").tokenUri)
    }

    private companion object {
        /** The wait between a change and its message, short for a test. */
        const val WAIT_MS = 50L
    }
}
