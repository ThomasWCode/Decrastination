package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.ports.Log
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS's Signature Version 4, as S3 and Backblaze's S3 endpoint take it: a page of HMACs, and no SDK on the box for one PUT a
 * night. Pure: the time and the headers are given.
 */
object Sigv4 {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun stamp(at: Long): String = STAMP.format(Instant.ofEpochMilli(at))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(text.toByteArray())
    }

    /** [path], each of its segments encoded as S3 signs it. */
    fun encoded(path: String): String = path.split("/").joinToString("/") { segment ->
        buildString {
            segment.toByteArray().forEach { byte ->
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c) else append("%%%02X".format(byte))
            }
        }
    }

    /** The `Authorization` header for [method] on [path] of a host, with [headers] (lower case, `x-amz-date` among them) all signed. */
    fun authorization(method: String, path: String, headers: Map<String, String>, payloadHash: String, region: String, keyId: String, secret: String): String {
        val when_ = headers.getValue("x-amz-date")
        val day = when_.take(8)
        val sorted = headers.toSortedMap()
        val signed = sorted.keys.joinToString(";")
        val canonical = listOf(method, encoded(path), "", sorted.entries.joinToString("") { (name, value) -> "$name:${value.trim()}\n" }, signed, payloadHash).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", when_, scope, sha256(canonical.toByteArray())).joinToString("\n")
        val key = listOf(day, region, "s3", "aws4_request").fold("AWS4$secret".toByteArray(), ::hmac)
        val signature = hmac(key, toSign).joinToString("") { "%02x".format(it) }
        return "AWS4-HMAC-SHA256 Credential=$keyId/$scope,SignedHeaders=$signed,Signature=$signature"
    }
}

/**
 * The nightly Backup-format file's place (W-20): a Backblaze B2 bucket, under a key that can write and not delete, so nothing
 * that gets this key can take the copies. The bucket's own lifecycle rule lets the old ones go, so this lists none and
 * deletes none. Its [endpoint] is the bucket's S3 one, `s3.<region>.backblazeb2.com`.
 */
class B2Folder(
    private val endpoint: String,
    private val keyId: String,
    private val secret: String,
    private val bucket: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupService.Folder {
    private val client = HttpClient.newHttpClient()
    private val region = endpoint.removePrefix("s3.").substringBefore(".")

    override val available: Boolean = true

    override fun write(name: String, text: String): Boolean {
        val body = text.toByteArray()
        val hash = Sigv4.sha256(body)
        val path = "/$bucket/$name"
        val headers = mapOf("host" to endpoint, "x-amz-content-sha256" to hash, "x-amz-date" to Sigv4.stamp(clock()))
        val request = HttpRequest.newBuilder(URI("https://$endpoint${Sigv4.encoded(path)}"))
            .header("x-amz-content-sha256", hash)
            .header("x-amz-date", headers.getValue("x-amz-date"))
            .header("Authorization", Sigv4.authorization("PUT", path, headers, hash, region, keyId, secret))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return try {
            val answer = client.send(request, HttpResponse.BodyHandlers.discarding())
            (answer.statusCode() in 200..299).also { if (!it) Log.w(Log.TAG, "The nightly file wasn't taken: ${answer.statusCode()}") }
        } catch (failed: IOException) {
            Log.w(Log.TAG, "The nightly file couldn't be sent: ${failed::class.simpleName}")
            false
        }
    }

    override fun names(): List<String> = emptyList()

    override fun delete(name: String): Boolean = false
}
