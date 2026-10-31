package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.sync.DeviceClaim
import com.thomaswcode.decrastination.sync.DeviceLine
import com.thomaswcode.decrastination.sync.DeviceToken
import com.thomaswcode.decrastination.sync.PairingCode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Random secrets, and what's kept of them: a session's and a device's only ever as their hash. */
object Tokens {
    private val random = SecureRandom()

    /** [bytes] of randomness, for a URL or a cookie. */
    fun random(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** PKCE's challenge for [verifier]. */
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** A six-digit code. */
    fun code(): String = "%06d".format(random.nextInt(1_000_000))

    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** What sign-in asks of GitHub (docs/plan_web/architecture.md §Sign-in): an interface, so the tests need no GitHub. */
interface GitHub {
    /** Where to send you to sign in. */
    fun authorizeUrl(state: String, challenge: String): String

    /** The numeric id of who signed in with [code], or null where GitHub says no. */
    fun userId(code: String, verifier: String): Long?
}

/** GitHub itself, through a GitHub App's OAuth flow with PKCE, as edit.thomaswhite.me's Worker signs in. */
class RealGitHub(private val clientId: String, private val clientSecret: String, private val callback: String) : GitHub {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    override fun authorizeUrl(state: String, challenge: String): String =
        "https://github.com/login/oauth/authorize?client_id=${encode(clientId)}&redirect_uri=${encode(callback)}&state=${encode(state)}&code_challenge=${encode(challenge)}&code_challenge_method=S256"

    override fun userId(code: String, verifier: String): Long? {
        val form = "client_id=${encode(clientId)}&client_secret=${encode(clientSecret)}&code=${encode(code)}&redirect_uri=${encode(callback)}&code_verifier=${encode(verifier)}"
        val token = http.send(
            HttpRequest.newBuilder(URI("https://github.com/login/oauth/access_token")).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val access = runCatching { json.parseToJsonElement(token.body()).jsonObject["access_token"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        val user = http.send(
            HttpRequest.newBuilder(URI("https://api.github.com/user")).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json").header("Authorization", "Bearer $access").header("User-Agent", "decrastination").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (user.statusCode() != 200) return null
        return runCatching { json.parseToJsonElement(user.body()).jsonObject["id"]?.jsonPrimitive?.long }.getOrNull()
    }
}

/** Who may sign in, and who's signed in (W-3): one GitHub account, a session a random id in a cookie, held by its hash. */
class Sessions(private val db: Database, private val allowed: Long, private val clock: () -> Long = System::currentTimeMillis) {
    fun allows(userId: Long?): Boolean = userId != null && userId == allowed

    /** A new session: its id, for the cookie, and nothing else keeps it. */
    fun start(): String {
        val id = Tokens.random()
        val now = clock()
        db.transaction { c ->
            c.prepareStatement("DELETE FROM sessions WHERE expires_at < ?").use {
                it.setLong(1, now)
                it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO sessions(id, created_at, expires_at, last_seen_at) VALUES(?, ?, ?, ?)").use {
                it.setString(1, Tokens.hash(id))
                it.setLong(2, now)
                it.setLong(3, now + LIFE_MS)
                it.setLong(4, now)
                it.executeUpdate()
            }
        }
        return id
    }

    /** Whether [id] is a session still good, which its use keeps good a while longer. */
    fun check(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        val now = clock()
        val hash = Tokens.hash(id)
        return db.transaction { c ->
            val live = c.prepareStatement("SELECT expires_at FROM sessions WHERE id = ?").use { q ->
                q.setString(1, hash)
                q.executeQuery().use { it.next() && it.getLong(1) > now }
            }
            if (live) {
                c.prepareStatement("UPDATE sessions SET last_seen_at = ?, expires_at = ? WHERE id = ?").use {
                    it.setLong(1, now)
                    it.setLong(2, now + LIFE_MS)
                    it.setString(3, hash)
                    it.executeUpdate()
                }
            }
            live
        }
    }

    fun end(id: String?) {
        if (id.isNullOrEmpty()) return
        db.transaction { c ->
            c.prepareStatement("DELETE FROM sessions WHERE id = ?").use {
                it.setString(1, Tokens.hash(id))
                it.executeUpdate()
            }
        }
    }

    companion object {
        /** A session lasts this long from its last use. */
        const val LIFE_MS = 30 * 24 * 3_600_000L
    }
}

/** The phone's pairing and its token (docs/plan_web/architecture.md §Sign-in): a one-time code, then 256 random bits held by their hash. */
class Devices(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** A code for the website to show: the only one live. */
    fun pair(): PairingCode {
        val code = Tokens.code()
        val expires = clock() + CODE_MS
        db.transaction { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM pairing_codes") }
            c.prepareStatement("INSERT INTO pairing_codes(code_hash, expires_at) VALUES(?, ?)").use {
                it.setString(1, Tokens.hash(code))
                it.setLong(2, expires)
                it.executeUpdate()
            }
        }
        return PairingCode(code, expires)
    }

    /** [claim]'s code, if it's the one live, used up: the device made, its token given this once. Null for any other. */
    fun claim(claim: DeviceClaim): DeviceToken? {
        val now = clock()
        return db.transaction { c ->
            val live = c.prepareStatement("SELECT expires_at FROM pairing_codes WHERE code_hash = ?").use { q ->
                q.setString(1, Tokens.hash(claim.code.trim()))
                q.executeQuery().use { it.next() && it.getLong(1) > now }
            }
            if (!live) return@transaction null
            c.createStatement().use { it.executeUpdate("DELETE FROM pairing_codes") }
            val id = UUID.randomUUID().toString()
            val token = Tokens.random()
            c.prepareStatement("INSERT INTO devices(id, name, token_hash, created_at) VALUES(?, ?, ?, ?)").use {
                it.setString(1, id)
                it.setString(2, claim.name.take(NAME_MAX).ifBlank { "Phone" })
                it.setString(3, Tokens.hash(token))
                it.setLong(4, now)
                it.executeUpdate()
            }
            DeviceToken(id, token)
        }
    }

    /** The device [token] is, if it's one not revoked. */
    fun check(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val hash = Tokens.hash(token)
        return db.transaction { c ->
            val id = c.prepareStatement("SELECT id FROM devices WHERE token_hash = ? AND revoked_at IS NULL").use { q ->
                q.setString(1, hash)
                q.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
            if (id != null) {
                c.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use {
                    it.setLong(1, clock())
                    it.setString(2, id)
                    it.executeUpdate()
                }
            }
            id
        }
    }

    fun list(): List<DeviceLine> = db.transactionless { c ->
        c.prepareStatement("SELECT id, name, created_at, last_seen_at FROM devices WHERE revoked_at IS NULL ORDER BY created_at").use { q ->
            q.executeQuery().use { r ->
                buildList { while (r.next()) add(DeviceLine(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4).takeIf { !r.wasNull() })) }
            }
        }
    }

    fun revoke(id: String) {
        db.transaction { c ->
            c.prepareStatement("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").use {
                it.setLong(1, clock())
                it.setString(2, id)
                it.executeUpdate()
            }
        }
    }

    /** The most recent time a device was seen: how fresh the mirror is. */
    fun lastSeen(): Long? = list().mapNotNull { it.lastSeenAt }.maxOrNull()

    companion object {
        /** A pairing code's life. */
        const val CODE_MS = 5 * 60_000L
        private const val NAME_MAX = 60
    }
}
