package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Secret
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.sync.DeviceToken
import com.thomaswcode.decrastination.sync.PairingCode
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.SeedAnswer
import com.thomaswcode.decrastination.sync.SeedBatch
import com.thomaswcode.decrastination.sync.ServerVersion
import com.thomaswcode.decrastination.sync.StoreText
import com.thomaswcode.decrastination.views.DayView
import com.thomaswcode.decrastination.views.SettingsView
import com.thomaswcode.decrastination.views.TaskView
import com.thomaswcode.decrastination.views.WorkView
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.encodeURLPathPart
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
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
    fun `the event stream says the revision it's at at once, then each new one`() = server {
        val session = signIn()
        val phone = paired()
        val events = createClient { install(ClientSSE) }
        withTimeout(10_000) {
            events.sse("/api/events", request = { header(HttpHeaders.Cookie, session) }) {
                val heard = mutableListOf<String>()
                incoming.filter { it.event == "revision" }.take(2).collect { event ->
                    heard += event.data.orEmpty()
                    if (heard.size == 1) seed(phone.token, listOf(StoreText("school.json", 1, """{"version":1}""")))
                }
                assertEquals(listOf("0", "1"), heard.toList())
            }
        }
    }

    @Test
    fun `a store of an older shape stays as the phone sent it, read up to date, and costs one write`() = server {
        val phone = paired()
        val old = Fixtures.file("stores").resolve("2026-10-30-4.10.0/protection.json").readText()
        val store = StoreText("protection.json", versionOf(old), old)
        assertTrue(store.version < graph.stores.protection.value.version, "this fixture should be of an older shape")
        val answer = json.decodeFromString<SeedAnswer>(seed(phone.token, listOf(store)).bodyAsText())
        assertEquals(Protocol.hash(old), answer.holds["protection.json"], "the hash is of what was sent, or the phone sends it again")
        assertEquals(1L, answer.revision)
        val again = json.decodeFromString<SeedAnswer>(seed(phone.token, listOf(store)).bodyAsText())
        assertEquals(1L, again.revision)
        assertEquals(1, db.transactionless { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM store_history WHERE name = 'protection.json'").use { it.next(); it.getInt(1) } } })
    }

    @Test
    fun `a phone can't list or revoke devices`() = server {
        val phone = paired()
        fun HttpRequestBuilder.asPhone() {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, plain().get("/api/devices") { asPhone() }.status)
        assertEquals(HttpStatusCode.Forbidden, plain().delete("/api/devices/${phone.id}") { asPhone() }.status)
    }

    @Test
    fun `a seed's answer says what the server holds, by hash`() = server {
        val phone = paired()
        val text = """{"version":1}"""
        val answer = json.decodeFromString<SeedAnswer>(seed(phone.token, listOf(StoreText("school.json", 1, text))).bodyAsText())
        assertEquals(Protocol.hash(text), answer.holds["school.json"])
    }

    @Test
    fun `the state's revision is never past a store it doesn't return`() = server {
        val phone = paired()
        seed(phone.token, listOf(StoreText("school.json", 1, """{"version":1}""")))
        val first = graph.state(0)
        assertEquals(1L, first.revision)
        assertEquals(listOf("school.json"), first.stores.map { it.name })
        seed(phone.token, listOf(StoreText("school.json", 1, """{"version":1,"noSchool":[]}""")))
        val next = graph.state(first.revision)
        assertEquals(2L, next.revision)
        assertEquals(listOf("school.json"), next.stores.map { it.name })
        assertEquals(emptyList(), graph.state(next.revision).stores)
    }

    @Test
    fun `the history is let go of by its rule`() = server {
        val phone = paired()
        repeat(SqliteBackend.HISTORY_KEPT + 5) { seed(phone.token, listOf(StoreText("school.json", 1, """{"version":1,"at":$it}"""))) }
        fun rows() = db.transactionless { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM store_history").use { it.next(); it.getInt(1) } } }
        assertEquals(SqliteBackend.HISTORY_KEPT + 5, rows())
        graph.tidy()
        assertEquals(SqliteBackend.HISTORY_KEPT + 5, rows(), "none is old enough yet")
        db.transactionless { c -> c.createStatement().use { it.execute("UPDATE store_history SET at = 0") } }
        graph.tidy()
        assertEquals(SqliteBackend.HISTORY_KEPT, rows())
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
    fun `a phone's zone is heard with its pull and with its commands, and a website's isn't`() = server {
        val phone = paired()
        suspend fun pull(zone: String) = plain().get("/api/state") {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
            header(Protocol.ZONE_HEADER, zone)
        }
        assertEquals(HttpStatusCode.OK, pull("Asia/Tokyo").status)
        assertEquals("Asia/Tokyo", graph.clock.zone().id)
        plain().post("/api/commands") {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
            header(Protocol.ZONE_HEADER, "America/New_York")
            contentType(ContentType.Application.Json)
            setBody("""{"commands":[]}""")
        }
        assertEquals("America/New_York", graph.clock.zone().id, "before it's turned away in mirror mode, as it's the same either way")
        plain().get("/api/state") {
            header(HttpHeaders.Cookie, signIn())
            header(Protocol.ZONE_HEADER, "Asia/Tokyo")
        }
        assertEquals("America/New_York", graph.clock.zone().id)
    }

    @Test
    fun `a photo's for the website, of a size, a JPEG, and not while mirroring`() = server {
        val cookie = signIn()
        val phone = paired()
        suspend fun upload(bytes: ByteArray, signed: HttpRequestBuilder.() -> Unit) = plain().post("/api/photos/tasks") {
            signed()
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        val website: HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Cookie, cookie)
            header("X-Requested-With", "test")
        }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        assertEquals(HttpStatusCode.Conflict, upload(jpeg, website).status)
        graph.commandsForATest()
        assertEquals(
            HttpStatusCode.Forbidden,
            upload(jpeg) {
                bearerAuth(phone.token)
                header(Protocol.HEADER, Protocol.VERSION.toString())
            }.status,
        )
        assertEquals(HttpStatusCode.UnsupportedMediaType, upload("not a photo".toByteArray(), website).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(4 * 1024 * 1024 + 1), website).status)
        assertEquals(HttpStatusCode.OK, upload(jpeg, website).status, "it's read, with no key saying so")
    }

    @Test
    fun `Try again asks again, at your word alone, and not while mirroring`() = server {
        val cookie = signIn()
        suspend fun again() = plain().post("/api/chat/try-again") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Requested-With", "test")
        }
        assertEquals(HttpStatusCode.Conflict, again().status)
        graph.commandsForATest()
        // Answered with nothing usable, it waits for your word: with no key that's as far as the call gets, and it says so.
        graph.stores.chat.update { it.copy(messages = listOf(ChatMessage("y", Speaker.You, "Well?", 1L, note = "Unreadable", stuck = true))) }
        assertEquals(HttpStatusCode.NoContent, again().status)
        withTimeout(5_000) { graph.stores.chat.state.first { chat -> !chat.messages.single().stuck } }
    }

    @Test
    fun `the chat's views are a client's`() = server {
        assertEquals(HttpStatusCode.Unauthorized, plain().get("/api/views/chat").status)
        assertEquals(HttpStatusCode.Unauthorized, plain().get("/api/views/notices").status)
        val cookie = signIn()
        val chat = plain().get("/api/views/chat?earlier=not-a-number&reach=x") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, chat.status, "a page's asking is taken as far as it makes sense")
        assertEquals(HttpStatusCode.OK, plain().get("/api/views/notices") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `the parent code's secret comes from the phone alone, and it's taken away the same way`() = server {
        val phone = paired()
        suspend fun fromPhone(body: String) = plain().post("/api/parent-code") {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val website = plain().post("/api/parent-code") {
            header(HttpHeaders.Cookie, signIn())
            header("X-Requested-With", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"secret":"MADEUP"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, website.status)
        assertFalse(graph.stores.secrets.has(Secret.TotpSecret), "a code set from the website would prove nothing")
        assertEquals(HttpStatusCode.NoContent, fromPhone("""{"secret":"MADEUP"}""").status)
        assertTrue(graph.stores.secrets.has(Secret.TotpSecret))
        assertEquals(HttpStatusCode.NoContent, fromPhone("""{"secret":null}""").status)
        assertFalse(graph.stores.secrets.has(Secret.TotpSecret))
    }

    @Test
    fun `a task's sheet and Settings are there to read in mirror mode, and what's asked of a task is turned away`() = server {
        val phone = paired()
        seed(phone.token, shape().map { it.copy(version = versionOf(it.text)) })
        val cookie = signIn()
        val id = graph.stores.tasks.value.tasks.first().id
        val sheet = plain().get("/api/views/task/" + id.encodeURLPathPart()) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, sheet.status)
        assertTrue(json.decodeFromString<TaskView>(sheet.bodyAsText()).actions.isEmpty())
        assertEquals(HttpStatusCode.NotFound, plain().get("/api/views/task/nothing") { header(HttpHeaders.Cookie, cookie) }.status)
        val settings = plain().get("/api/views/settings") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, settings.status)
        assertFalse(json.decodeFromString<SettingsView>(settings.bodyAsText()).canChange)
        val asked = plain().post("/api/tasks/" + id.encodeURLPathPart() + "/ask") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Requested-With", "test")
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"done"}""")
        }
        assertEquals(HttpStatusCode.Conflict, asked.status)
        val fromPhone = plain().post("/api/tasks/" + id.encodeURLPathPart() + "/ask") {
            bearerAuth(phone.token)
            header(Protocol.HEADER, Protocol.VERSION.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"done"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, fromPhone.status, "a phone sends commands of its own")
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
