package com.thomaswcode.decrastination.server

import com.interaso.webpush.VapidKeys
import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.ports.Log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.json.JsonPrimitive

/** AES-256-GCM under a key from the environment (`MASTER_KEY`, base64, 32 bytes), for what the server holds encrypted. */
class MasterKeyCipher(masterKey: String) : SecretCipher {
    private val key = SecretKeySpec(Base64.getDecoder().decode(masterKey).also { require(it.size == 32) { "MASTER_KEY is 32 bytes, in base64" } }, "AES")
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** The log as a line of JSON on stdout: the level, the message, the kind of a failure. No line ever holds a store's content or a header. */
private object StdoutLog : Log.Sink {
    override fun write(level: Log.Level, tag: String, message: String, error: Throwable?) {
        fun quoted(text: String) = JsonPrimitive(text).toString()
        val failure = error?.let { ",\"error\":${quoted(it::class.simpleName.orEmpty())}" }.orEmpty()
        println("{\"at\":\"${Instant.now()}\",\"level\":\"${level.name}\",\"message\":${quoted(message)}$failure}")
    }
}

/** The nightly file's place, if all of its settings are there. */
private fun nightlyFolder(): B2Folder? {
    val values = listOf("B2_ENDPOINT", "B2_KEY_ID", "B2_APPLICATION_KEY", "B2_BUCKET").map { System.getenv(it)?.takeIf(String::isNotBlank) ?: return null }
    return B2Folder(values[0], values[1], values[2], values[3])
}

/** The box's keys for telling a browser, if they're set: `vapid-keys` makes them. */
private fun pushKeys(): VapidKeys? {
    val public = System.getenv("VAPID_PUBLIC_KEY")?.takeIf(String::isNotBlank) ?: return null
    val private = System.getenv("VAPID_PRIVATE_KEY")?.takeIf(String::isNotBlank) ?: return null
    return VapidKeys.create(public, private)
}

private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: run {
    System.err.println("Set $name")
    exitProcess(1)
}

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    // Once, by hand, to fill in `.env`: it runs no server, and nothing of it reaches a log.
    if (args.firstOrNull() == "vapid-keys") {
        val keys = VapidKeys.generate()
        println("VAPID_PUBLIC_KEY=${keys.x509PublicKey}")
        println("VAPID_PRIVATE_KEY=${keys.pkcs8PrivateKey}")
        return
    }
    Log.sink = StdoutLog
    val testing = System.getenv("DECRASTINATION_TEST") == "1"
    val domain = System.getenv("DOMAIN").orEmpty()
    if (testing && domain.isNotEmpty()) {
        System.err.println("A test server isn't run on a domain")
        exitProcess(1)
    }
    val dataDir = File(System.getenv("DATA_DIR") ?: "/data").apply { mkdirs() }
    val db = Database(File(dataDir, "decrastination.db").path)
    val version = System.getenv("APP_VERSION") ?: "dev"
    // Only a test's server has a stand-in for Claude: one on a box would send the owner's tasks to it.
    val standIn = System.getenv("CLAUDE_STAND_IN")?.takeIf { testing }
    // A test's made here, so none is in the repository, and none's a box's.
    val pusher = (if (testing) VapidKeys.generate() else pushKeys())?.let { WebPushSender("https://${domain.ifEmpty { "localhost" }}", it) }
    val graph = ServerGraph(db, dataDir, MasterKeyCipher(if (testing) Base64.getEncoder().encodeToString(ByteArray(32)) else env("MASTER_KEY")), version, claudeKey = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }, claudeEndpoint = standIn, heartbeatUrl = System.getenv("HEARTBEAT_URL")?.takeIf { it.isNotBlank() }, nightly = nightlyFolder(), pushSender = pusher, pushKey = pusher?.applicationKey)
    val github: GitHub = if (testing) {
        object : GitHub {
            override fun authorizeUrl(state: String, challenge: String) = "/"

            override fun userId(code: String, verifier: String): Long? = null
        }
    } else {
        RealGitHub(env("GITHUB_CLIENT_ID"), env("GITHUB_CLIENT_SECRET"), "https://$domain/api/auth/callback")
    }
    val sessions = Sessions(db, if (testing) 0 else env("ALLOWED_GITHUB_USER_ID").toLong())
    val web = Web(graph, github, sessions, secure = !testing, testing = testing)
    Log.i(Log.TAG, "Starting $version in ${graph.mode} mode")
    // A test's server does what it's asked and no more: nothing of its own in between a test's steps.
    if (!testing) graph.start(GlobalScope)
    embeddedServer(Netty, port = (System.getenv("PORT") ?: "8080").toInt(), host = "0.0.0.0") { api(web) }.start(wait = true)
}
