package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.net.Http
import java.io.IOException

/**
 * Whether Litestream is still carrying the database off the box (docs/spec/server.md §Deployment), read from its own metrics as
 * the heartbeat beats. The server writes each minute, the heartbeat's own mark among it, so a stream that's going uploads at least
 * that often: none gone up for [STOPPED_MS] is a stream stopped, whether its key's been taken away, it's stuck, or it's down and
 * can't be asked. Litestream 0.5.17, tried on a PC on 3 Nov 2026: with its bucket taken away, its count of uploads stood still and
 * no count of errors came, while the database went on changing.
 */
class StreamWatch(private val http: Http, private val metrics: String?) {
    /** The uploads as last read: a count that starts again from nothing as Litestream restarts. */
    private var uploads: Long? = null

    /** When they last moved, or the watch began. */
    private var movedAt: Long? = null

    /** Whether the stream is going at [now]. With nothing to watch, it's taken as going. */
    fun going(now: Long): Boolean {
        val url = metrics ?: return true
        val began = movedAt ?: now.also { movedAt = it }
        uploaded(url)?.let { read ->
            if (read != uploads) movedAt = now
            uploads = read
        }
        return now - (movedAt ?: began) < STOPPED_MS
    }

    /** The uploads Litestream says it's made, or null where it can't be asked or has made none. */
    private fun uploaded(url: String): Long? = try {
        val answer = http.get(url, emptyMap())
        val counts = if (answer.ok) UPLOADS.findAll(answer.body).map { it.groupValues[1].toDouble().toLong() }.toList() else emptyList()
        counts.takeIf { it.isNotEmpty() }?.sum()
    } catch (failed: IOException) {
        null
    }

    companion object {
        /** How long no upload is taken as the stream stopped: well past a minute's writes and Litestream's own pace. */
        const val STOPPED_MS = 15 * 60_000L

        /** Its count of uploads, as Prometheus' text gives it. */
        private val UPLOADS = Regex("""(?m)^litestream_replica_operation_total\{[^}]*operation="PUT"[^}]*\} ([0-9.eE+]+)\s*$""")
    }
}
