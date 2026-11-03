package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.PushTried
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Telling a phone at once (W-18): a change committed here is said to each paired phone that didn't make it, through Firebase
 * Cloud Messaging, so it pulls in seconds rather than at its next 15-minute sync. Only the revision is said: the change comes by
 * the phone's own pull, signed in, and nothing of the state goes through Google. A run of changes is one message ([waitMs]). Only
 * once the server applies commands: while it mirrors, the phone is the one writing, and has nothing to hear. A phone is reached by
 * its Firebase Installation ID, kept with its device ([keep]), so a phone revoked is told nothing; Firebase calls the registration
 * token it used before deprecated. Nothing's sent without a [sender], the box's service account.
 */
class PhonePushes(
    private val db: Database,
    private val scope: CoroutineScope,
    private val sender: Sender?,
    /** Whether a change is to be told: once the server applies commands. */
    private val telling: () -> Boolean,
    private val waitMs: Long = WAIT_MS,
) {
    /** Hands a message to Firebase. */
    fun interface Sender {
        /** [revision] to the phone whose Firebase Installation ID is [fid]: false if Firebase no longer knows it. A failure of any other kind is thrown. */
        fun send(fid: String, revision: Long): Boolean
    }

    val on: Boolean get() = sender != null

    private val lock = Any()

    /** Who's made a change since the last message went: a phone's id, the website's, the server's own. */
    private val makers = mutableSetOf<String>()
    private var newest = 0L
    private var waiting = false

    /** The run of changes on its way to being told, if there is one: what a test waits on, rather than the clock. */
    @Volatile
    internal var sending: Job? = null

    /** [fid] kept as where Firebase reaches [device]: false, and nothing kept, if it isn't the shape of one. */
    fun keep(device: String, fid: String): Boolean {
        if (!fit(fid)) return false
        db.transaction { c ->
            c.prepareStatement("UPDATE devices SET fid = ? WHERE id = ? AND revoked_at IS NULL").use { q ->
                q.setString(1, fid)
                q.setString(2, device)
                q.executeUpdate()
            }
        }
        return true
    }

    /** [fid] let go of, as Firebase has: only while it's still the one held, as the phone may have sent a new one meanwhile. */
    private fun forget(device: String, fid: String) {
        db.transaction { c ->
            c.prepareStatement("UPDATE devices SET fid = NULL WHERE id = ? AND fid = ?").use { q ->
                q.setString(1, device)
                q.setString(2, fid)
                q.executeUpdate()
            }
        }
    }

    /** Each paired phone Firebase can reach, by its id. */
    private fun fids(): Map<String, String> = db.read { c ->
        c.prepareStatement("SELECT id, fid FROM devices WHERE revoked_at IS NULL AND fid IS NOT NULL").use { q ->
            q.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1), r.getString(2)) } }
        }
    }

    /**
     * A change committed at [revision], made by [by]: each phone told but the one that made it, once [waitMs] has gone by, with
     * whatever else came meanwhile. A phone that makes a change pulls in the same exchange, so it's told only of others'.
     */
    fun changed(revision: Long, by: String) {
        if (sender == null || !telling()) return
        synchronized(lock) {
            makers += by
            newest = maxOf(newest, revision)
            if (waiting) return
            waiting = true
        }
        sending = scope.launch(Dispatchers.IO) {
            delay(waitMs)
            val (made, at) = synchronized(lock) {
                waiting = false
                (makers.toSet() to newest).also { makers.clear() }
            }
            val reached = try {
                fids()
            } catch (failed: Exception) {
                // Nothing's lost: the phones hear at their own sync.
                Log.w(Log.TAG, "Reading which phones to tell failed: ${failed::class.simpleName}")
                emptyMap()
            }
            reached.filter { (device, _) -> made.any { it != device } }.forEach { (device, fid) -> tell(device, fid, at) }
        }
    }

    /** [device] told now, whatever the mode: how a phone finds out it can be told at once. What came of it. */
    suspend fun test(device: String, revision: Long): PushTried = withContext(Dispatchers.IO) {
        if (sender == null) return@withContext PushTried(false, NOT_SET_UP)
        val fid = fids()[device] ?: return@withContext PushTried(false, NO_ID)
        tell(device, fid, revision)
    }

    /**
     * [revision] to [device], at [fid]. One Firebase no longer knows is let go; a failure's only logged, by kind, as an id is as good
     * as an address. Letting go is outside what's said of Firebase: it answered, and a fault of the database's is the database's.
     */
    private fun tell(device: String, fid: String, revision: Long): PushTried {
        val sender = sender ?: return PushTried(false, NOT_SET_UP)
        val known = try {
            sender.send(fid, revision)
        } catch (failed: Exception) {
            val status = (failed as? FcmSender.Refused)?.status
            Log.w(Log.TAG, "Telling a phone failed: ${failed::class.simpleName}${status?.let { " ($it)" }.orEmpty()}")
            return PushTried(false, "Firebase didn't take it: ${status ?: failed::class.simpleName}")
        }
        if (known) return PushTried(true)
        try {
            forget(device, fid)
        } catch (failed: Exception) {
            // Kept, then: it's told again next time, and let go of then.
            Log.w(Log.TAG, "Letting go of an id Firebase no longer knows failed: ${failed::class.simpleName}")
        }
        return PushTried(false, GONE)
    }

    companion object {
        /** A few seconds: a command's own changes, and what follows from them, are one message. */
        const val WAIT_MS = 3_000L

        /** What the server's own work is called, as the maker of a change. */
        const val SERVER = "server"

        const val NOT_SET_UP = "The server isn't set up to tell a phone"
        const val NO_ID = "The server has no Firebase ID for this phone"
        const val GONE = "Firebase no longer knows this phone"

        /** What an id runs to at most: Firebase's are 22 characters, and this leaves room for a longer kind. */
        private const val MAX_ID = 256

        private val ID = Regex("[A-Za-z0-9_:.\\-]+")

        /** Whether [fid] is the shape of Firebase's: a phone's word for it, kept and sent in a message, never followed. */
        fun fit(fid: String) = fid.length <= MAX_ID && ID.matches(fid)
    }
}

/** A Google service account's key, as Firebase's console gives it: what's needed to sign for a token. */
@Serializable
data class ServiceAccount(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The key file's [text]. */
        fun read(text: String): ServiceAccount = json.decodeFromString(serializer(), text)
    }
}

/**
 * The real thing: Firebase Cloud Messaging's HTTP v1 API, under the box's service account ([account]). Its token is signed for
 * here, and kept till near its hour's end, as Google's own library would bring a great deal with it for two requests. A data
 * message at high priority, so a phone asleep still pulls; one waiting for a phone that's away takes the place of the last
 * ([COLLAPSE]), and none is kept past an hour ([TTL_SECONDS]), as by then the phone's own sync has brought what it said.
 */
class FcmSender(
    private val account: ServiceAccount,
    private val endpoint: String = ENDPOINT,
    private val timeout: Duration = Duration.ofSeconds(15),
    private val clock: () -> Long = System::currentTimeMillis,
) : PhonePushes.Sender {
    /** An answer from Google that's neither a yes nor an id gone: its status alone, as its body can name the id. */
    class Refused(val status: Int) : Exception("Refused with $status")

    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val key: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(account.privateKey.replace(PEM_LINE, ""))))

    /** Google's token, and the moment it's to be signed for again. */
    private var access: Pair<String, Long>? = null

    override fun send(fid: String, revision: Long): Boolean {
        val first = post(fid, revision, bearer())
        // A token Google's let go of before its time: a new one, once.
        val answer = if (first.statusCode() == 401) post(fid, revision, bearer(again = true)) else first
        return when {
            answer.statusCode() == 200 -> true
            gone(answer) -> false
            else -> throw Refused(answer.statusCode())
        }
    }

    private fun post(fid: String, revision: Long, bearer: String): HttpResponse<String> {
        val message = buildJsonObject {
            putJsonObject("message") {
                put("fid", fid)
                putJsonObject("data") { put("revision", revision.toString()) }
                putJsonObject("android") {
                    put("priority", "HIGH")
                    put("collapse_key", COLLAPSE)
                    put("ttl", "${TTL_SECONDS}s")
                }
            }
        }
        val request = HttpRequest.newBuilder(URI("$endpoint/v1/projects/${account.projectId}/messages:send")).timeout(timeout)
            .header("Authorization", "Bearer $bearer").header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(message.toString())).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Whether [answer] says the id's no good, and won't be: unknown to Firebase (as an id that isn't one is too), or another
     * project's. Not a 400, which Firebase can give for a dead one, as it's as likely to be a fault in the message, and an id let go
     * for that would never be told again.
     */
    private fun gone(answer: HttpResponse<String>): Boolean = when (answer.statusCode()) {
        404 -> true
        403 -> "SENDER_ID_MISMATCH" in answer.body()
        else -> false
    }

    /** Google's token for messaging: the one held, or signed for afresh once it's near its end, or [again]. */
    @Synchronized
    private fun bearer(again: Boolean = false): String {
        val now = clock()
        if (!again) access?.takeIf { it.second > now }?.let { return it.first }
        val form = "grant_type=${URLEncoder.encode(GRANT, Charsets.UTF_8)}&assertion=${assertion(now)}"
        val answer = client.send(
            HttpRequest.newBuilder(URI(account.tokenUri)).timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (answer.statusCode() != 200) throw Refused(answer.statusCode())
        val given = json.parseToJsonElement(answer.body()).jsonObject
        val token = given["access_token"]?.jsonPrimitive?.content ?: throw Refused(answer.statusCode())
        val life = given["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: LIFE_S
        access = token to now + (life - MARGIN_S) * 1000
        return token
    }

    /** The service account's word for itself, as RFC 7523 has it: a JWT signed with its key, good for an hour. */
    internal fun assertion(now: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = buildJsonObject {
            put("iss", account.clientEmail)
            put("scope", SCOPE)
            put("aud", account.tokenUri)
            put("iat", now / 1000)
            put("exp", now / 1000 + LIFE_S)
        }
        val body = encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update("$header.$body".toByteArray())
        }.sign()
        return "$header.$body.${encoder.encodeToString(signature)}"
    }

    companion object {
        const val ENDPOINT = "https://fcm.googleapis.com"
        private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private const val GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"

        /** An hour: what Google gives, and the longest it allows. */
        private const val LIFE_S = 3600L

        /** Signed for again this long before its end, so none goes out as it runs out. */
        private const val MARGIN_S = 300L

        /** One waiting for a phone that's away takes the place of the last: each says only to pull. */
        const val COLLAPSE = "state"

        /** An hour: by then the phone's own sync has brought it. */
        const val TTL_SECONDS = 3600

        /** A PEM file's first and last lines. */
        private val PEM_LINE = Regex("-----[A-Z ]+-----")
    }
}
