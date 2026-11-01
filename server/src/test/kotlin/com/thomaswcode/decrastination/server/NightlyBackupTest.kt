package com.thomaswcode.decrastination.server

import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The nightly file's signature, against the worked example AWS publishes for Signature Version 4. */
class NightlyBackupTest {
    @Test
    fun `a PUT signs as AWS's worked example does`() {
        val payload = "Welcome to Amazon S3."
        val hash = Sigv4.sha256(payload.toByteArray())
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val headers = mapOf(
            "date" to "Fri, 24 May 2013 00:00:00 GMT",
            "host" to "examplebucket.s3.amazonaws.com",
            "x-amz-content-sha256" to hash,
            "x-amz-date" to "20130524T000000Z",
            "x-amz-storage-class" to "REDUCED_REDUNDANCY",
        )
        val authorization = Sigv4.authorization("PUT", "/test\$file.text", headers, hash, "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class,Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            authorization,
        )
    }

    @Test
    fun `a path is encoded as S3 signs it, its slashes left`() {
        assertEquals("/test%24file.text", Sigv4.encoded("/test\$file.text"))
        assertEquals("/bucket/decrastination-2026-10-31.json", Sigv4.encoded("/bucket/decrastination-2026-10-31.json"))
        assertEquals("/a%20b", Sigv4.encoded("/a b"))
    }

    @Test
    fun `a time stamps as the header wants it`() {
        assertEquals("20130524T000000Z", Sigv4.stamp(1_369_353_600_000L))
    }

    @Test(timeout = 15_000)
    fun `an endpoint that takes the connection and says nothing is a failure, not a wait for ever`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val folder = B2Folder("127.0.0.1:${silent.localPort}", "key", "secret", "bucket", timeout = Duration.ofMillis(300))
            assertFalse(folder.write("a-file", "text"))
        }
    }

    @Test
    fun `the bucket lets the old ones go by its own rule, so nothing's listed or deleted here`() {
        val folder = B2Folder("s3.eu-central-003.backblazeb2.com", "key", "secret", "bucket")
        assertTrue(folder.available)
        assertEquals(emptyList(), folder.names())
        assertEquals(false, folder.delete("anything"))
    }
}
