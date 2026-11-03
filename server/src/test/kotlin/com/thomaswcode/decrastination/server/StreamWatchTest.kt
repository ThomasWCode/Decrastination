package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.data.SecretCipher
import com.thomaswcode.decrastination.net.Http
import com.thomaswcode.decrastination.net.HttpResponse
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Whether Litestream's still carrying the database off the box, read from its metrics as the heartbeat beats. */
class StreamWatchTest {
    private object Plain : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain

        override fun decrypt(sealed: ByteArray) = sealed
    }

    private val dir: File = Files.createTempDirectory("stream").toFile()

    @AfterTest
    fun cleanUp() = dir.deleteRecursively().let { }

    /** Litestream's metrics, as 0.5.17 gave them on a PC, its uploads at [puts]. */
    private fun metrics(puts: Int) = """
        # HELP litestream_replica_operation_total The number of replica operations performed
        # TYPE litestream_replica_operation_total counter
        litestream_replica_operation_bytes{operation="PUT",replica_type="s3"} 3140
        litestream_replica_operation_total{operation="PUT",replica_type="s3"} $puts
        litestream_sync_count{db="/data/decrastination.db"} 35
        litestream_txid{db="/data/decrastination.db"} 7
    """.trimIndent()

    private var puts = 10
    private var down = false
    private val http = Http { _, _, _ -> if (down) throw IOException("refused") else HttpResponse(200, metrics(puts)) }
    private val minute = 60_000L

    @Test
    fun `uploads that go on are a stream going, and none for a quarter of an hour a stream stopped`() {
        val watch = StreamWatch(http, "http://litestream:9090/metrics")
        assertTrue(watch.going(0))
        puts++
        assertTrue(watch.going(5 * minute))
        assertTrue(watch.going(19 * minute), "fourteen minutes with none")
        assertFalse(watch.going(20 * minute), "fifteen")
        puts++
        assertTrue(watch.going(21 * minute), "and going again as they go up again")
    }

    @Test
    fun `a Litestream that can't be asked is a stream stopped once that's lasted, and one started again counts from nothing`() {
        val watch = StreamWatch(http, "http://litestream:9090/metrics")
        assertTrue(watch.going(0))
        down = true
        assertTrue(watch.going(10 * minute))
        assertFalse(watch.going(16 * minute))
        down = false
        puts = 1
        assertTrue(watch.going(17 * minute), "fewer than before: it's been restarted, and it's uploading")
    }

    @Test
    fun `metrics with no uploads in them are no uploads`() {
        val watch = StreamWatch(Http { _, _, _ -> HttpResponse(200, "litestream_sync_count{db=\"/data/decrastination.db\"} 35") }, "http://litestream:9090/metrics")
        assertTrue(watch.going(0))
        assertFalse(watch.going(15 * minute))
    }

    @Test
    fun `with nothing to watch the stream's taken as going`() {
        assertTrue(StreamWatch(http, null).going(Long.MAX_VALUE))
    }

    @Test
    fun `the heartbeat says it failed while the stream's stopped, so the check's owner hears of it, and beats again as it goes`() {
        val db = Database(File(dir, "test.db").path)
        val beats = mutableListOf<String>()
        val graph = ServerGraph(db, dir, Plain, "test", heartbeatUrl = "https://beat.example/made-up", http = Http { url, _, _ ->
            if (url.startsWith("https://beat.example")) beats += url
            HttpResponse(200, metrics(puts))
        }, streamMetrics = "http://litestream:9090/metrics")
        graph.heartbeat(0)
        graph.heartbeat(16 * minute)
        puts++
        graph.heartbeat(17 * minute)
        assertEquals(listOf("https://beat.example/made-up", "https://beat.example/made-up/fail", "https://beat.example/made-up"), beats)
        runBlocking { graph.stopForATest() }
        db.close()
    }
}
