package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.DeviceToken
import com.thomaswcode.decrastination.sync.PairingCode
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.StoreText
import com.thomaswcode.decrastination.views.DayView
import com.thomaswcode.decrastination.views.WorkView
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ApiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** GitHub as a test has it: whoever signs in is [who]. */
    private class FakeGitHub(var who: Long?) : GitHub {
        var verifier: String? = null

        override fun authorizeUrl(state: String, challenge: String) = "https://github.example/login?state=$state&challenge=$challenge"

        override fun userId(code: String, verifier: String): Long? = who.also { this.verifier = verifier }
    }

    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val db = Database(":memory:")
    private val graph = ServerGraph(db, Files.createTempDirectory("api").toFile(), Plain, "test")
    private val github = FakeGitHub(ALLOWED)
    private val web = Web(graph, github, Sessions(db, ALLOWED), secure = false)

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(web) }
        block()
    }

    private fun ApplicationTestBuilder.plain(): HttpClient = createClient { followRedirects = false }

    /** Through the whole sign-in: its session cookie, ready to send. */
    private suspend fun ApplicationTestBuilder.signIn(): String {
        val client = plain()
        val start = client.get("/api/auth/github")
        assertEquals(HttpStatusCode.Found, start.status)
        val cookie = start.setCookie().single { it.name == "oauth" }
        val state = start.headers[HttpHeaders.Location]!!.substringAfter("state=").substringBefore("&")
        val back = client.get("/api/auth/callback?code=c&state=$state") { header(HttpHeaders.Cookie, "oauth=${cookie.value}") }
        assertEquals(HttpStatusCode.Found, back.status, back.bodyAsText())
        return "session=" + back.setCookie().single { it.name == "session" }.value
    }

    private suspend fun ApplicationTestBuilder.paired(): DeviceToken {
        val code = json.decodeFromString<PairingCode>(plain().post("/api/devices/pair") { header(HttpHeaders.Cookie, signIn()); header("X-Requested-With", "test") }.bodyAsText())
        val claim = plain().post("/api/devices/claim") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"${code.code}","name":"the phone"}""")
        }
        assertEquals(HttpStatusCode.OK, claim.status, claim.bodyAsText())
        return json.decodeFromString(claim.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.seed(token: String, stores: List<StoreText>): HttpResponse = plain().post("/api/stores/seed") {
        bearerAuth(token)
        header(Protocol.HEADER, Protocol.VERSION.toString())
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(SeedBatch("Europe/London", "5.0.0", stores)))
    }

    /** Every store of the shape now, as a phone would send them. */
    private fun shape(): List<StoreText> {
        val dir = Fixtures.file("stores").listFiles { f -> f.isDirectory }!!.maxByOrNull { it.name }!!
        return dir.listFiles()!!.filter { it.name in setOf("tasks.json", "settings.json", "session.json", "log.json", "instructions.json", "school.json", "model.json", "learning.json") }
            .map { file -> StoreText(file.name, JsonStore.json.parseToJsonElement(file.readText()).let { 1 }, file.readText()) }
    }

    @Test
    fun `health and the version are open, and everything else isn't`() = server {
        assertEquals(HttpStatusCode.OK, plain().get("/api/health").status)
        val version = json.decodeFromString<ServerVersion>(plain().get("/api/version").bodyAsText())
        assertEquals(Protocol.VERSION, version.protocol)
        assertEquals("mirror", version.mode)
        assertTrue("tasks.json" in version.stores)
        for (path in listOf("/api/views/day", "/api/views/work", "/api/state", "/api/devices")) {
            assertEquals(HttpStatusCode.Unauthorized, plain().get(path).status, path)
        }
    }

    @Test
    fun `sign-in lets the one account in`() = server {
        val cookie = signIn()
        val me = plain().get("/api/me") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
        assertTrue("true" in me, me)
        assertNotNull(github.verifier)
        assertEquals(HttpStatusCode.OK, plain().get("/api/views/work") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `another account is refused, and gets no session`() = server {
        github.who = 99
        val client = plain()
        val start = client.get("/api/auth/github")
        val cookie = start.setCookie().single { it.name == "oauth" }
        val state = start.headers[HttpHeaders.Location]!!.substringAfter("state=").substringBefore("&")
        val back = client.get("/api/auth/callback?code=c&state=$state") { header(HttpHeaders.Cookie, "oauth=${cookie.value}") }
        assertEquals(HttpStatusCode.Forbidden, back.status)
        assertTrue(back.setCookie().none { it.name == "session" && it.value.isNotEmpty() })
    }

    @Test
    fun `a callback that didn't start here is refused`() = server {
        val back = plain().get("/api/auth/callback?code=c&state=made-up") { header(HttpHeaders.Cookie, "oauth=real.verifier") }
        assertEquals(HttpStatusCode.BadRequest, back.status)
        assertNull(github.verifier)
    }

    @Test
    fun `what changes something needs the header a cross-site form can't send`() = server {
        val cookie = signIn()
        assertEquals(HttpStatusCode.Forbidden, plain().post("/api/devices/pair") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `signing out ends the session`() = server {
        val cookie = signIn()
        plain().post("/api/auth/logout") { header(HttpHeaders.Cookie, cookie); header("X-Requested-With", "test") }
        assertEquals(HttpStatusCode.Unauthorized, plain().get("/api/views/work") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `a pairing code works once, and a wrong one not at all`() = server {
        val cookie = signIn()
        val code = json.decodeFromString<PairingCode>(plain().post("/api/devices/pair") { header(HttpHeaders.Cookie, cookie); header("X-Requested-With", "test") }.bodyAsText())
        val wrong = if (code.code == "000000") "000001" else "000000"
        suspend fun claim(with: String) = plain().post("/api/devices/claim") { contentType(ContentType.Application.Json); setBody("""{"code":"$with","name":"p"}""") }
        assertEquals(HttpStatusCode.Forbidden, claim(wrong).status)
        assertEquals(HttpStatusCode.OK, claim(code.code).status)
        assertEquals(HttpStatusCode.Forbidden, claim(code.code).status)
    }

    @Test
    fun `a token is held by its hash, and revoked it's refused`() = server {
        val phone = paired()
        val held = db.transactionless { c -> c.prepareStatement("SELECT token_hash FROM devices").use { q -> q.executeQuery().use { it.next(); it.getString(1) } } }
        assertFalse(held == phone.token)
        assertEquals(Tokens.hash(phone.token), held)
        assertEquals(HttpStatusCode.OK, seed(phone.token, emptyList()).status)
        plain().delete("/api/devices/${phone.id}") { header(HttpHeaders.Cookie, signIn()); header("X-Requested-With", "test") }
        assertEquals(HttpStatusCode.Unauthorized, seed(phone.token, emptyList()).status)
    }

    @Test
    fun `only a phone seeds`() = server {
        val cookie = signIn()
        val answer = plain().post("/api/stores/seed") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Requested-With", "test")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SeedBatch("Europe/London", "x", emptyList())))
        }
        assertEquals(HttpStatusCode.Forbidden, answer.status)
    }

    @Test
    fun `a phone a protocol behind is still heard, one further behind or ahead is told so`() = server {
        val phone = paired()
        suspend fun at(version: Int?) = plain().post("/api/stores/seed") {
            bearerAuth(phone.token)
            version?.let { header(Protocol.HEADER, it.toString()) }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SeedBatch("Europe/London", "x", emptyList())))
        }.status
        assertEquals(HttpStatusCode.OK, at(Protocol.VERSION))
        assertEquals(HttpStatusCode.OK, at(Protocol.VERSION - 1))
        assertEquals(HttpStatusCode.Conflict, at(Protocol.VERSION - 2))
        assertEquals(HttpStatusCode.Conflict, at(Protocol.VERSION + 1))
        assertEquals(HttpStatusCode.Conflict, at(null))
    }

    @Test
    fun `a store a phone sends is kept whole, and read back above its revision`() = server {
        val phone = paired()
        val answer = json.decodeFromString<SeedAnswer>(seed(phone.token, listOf(StoreText("school.json", 1, """{"noSchool":[],"halfDays":[]}"""))).bodyAsText())
        assertEquals(1L, answer.revision)
        assertEquals(emptyList(), answer.refused)
        val all = plain().get("/api/state") { header(HttpHeaders.Cookie, signIn()) }.bodyAsText()
        assertTrue("school.json" in all, all)
        val none = plain().get("/api/state?since=1") { header(HttpHeaders.Cookie, signIn()) }.bodyAsText()
        assertFalse("school.json" in none, none)
    }

    @Test
    fun `the same text again takes no revision`() = server {
        val phone = paired()
        val store = StoreText("school.json", 1, """{"noSchool":[],"halfDays":[]}""")
        seed(phone.token, listOf(store))
        assertEquals(1L, json.decodeFromString<SeedAnswer>(seed(phone.token, listOf(store)).bodyAsText()).revision)
    }

    @Test
    fun `a store newer than this build, or one it doesn't keep, is refused and said`() = server {
        val phone = paired()
        val newest = graph.stores.tasks.version
        val answer = json.decodeFromString<SeedAnswer>(
            seed(phone.token, listOf(StoreText("tasks.json", newest + 1, "{}"), StoreText("runtime.json", 1, "{}"), StoreText("secrets.bin", 1, "x"))).bodyAsText(),
        )
        assertEquals(listOf("tasks.json", "runtime.json", "secrets.bin"), answer.refused)
        assertNull(graph.backend.read("tasks.json"))
        assertNull(graph.backend.read("secrets.bin"))
    }

    @Test
    fun `the zone a phone says is the server's`() = server {
        val phone = paired()
        plain().post("/api/stores/seed") {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SeedBatch("America/New_York", "x", emptyList())))
        }
        assertEquals("America/New_York", graph.clock.zone().id)
    }

    @Test
    fun `the Day and Work show what the phone sent, in the phone's words`() = server {
        val phone = paired()
        val answer = json.decodeFromString<SeedAnswer>(seed(phone.token, shape().map { it.copy(version = versionOf(it.text)) }).bodyAsText())
        assertEquals(emptyList(), answer.refused)
        val cookie = signIn()
        val work = json.decodeFromString<WorkView>(plain().get("/api/views/work") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
        assertTrue(work.byDue.flatMap { it.tasks }.isNotEmpty() || work.finished.isNotEmpty())
        assertNotNull(work.asOf)
        val day = json.decodeFromString<DayView>(plain().get("/api/views/day?date=2026-10-20") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
        assertEquals("2026-10-20", day.date)
        assertEquals(7, day.week.size)
    }

    private fun versionOf(text: String): Int = (JsonStore.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["version"]?.toString()?.toIntOrNull() ?: 1

    private companion object {
        const val ALLOWED = 42L
    }
}
