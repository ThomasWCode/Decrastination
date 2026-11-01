package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.ports.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Something the server does by itself: when it's due, given when it last ran, how long after a failure it's tried again, and the work. */
class ServerJob(val name: String, val due: (last: Long?, now: Long) -> Boolean, val retryMs: Long = RETRY_MS, val work: suspend () -> Unit) {
    companion object {
        /** A failed job's wait: one that reaches Claude or a source isn't to go round every minute. */
        const val RETRY_MS = 15 * 60_000L

        /** Due [everyMs] after it last ran, or failed, and at once if it never has. */
        fun every(name: String, everyMs: Long, work: suspend () -> Unit) = ServerJob(name, { last, now -> last == null || now - last >= everyMs }, everyMs, work)
    }
}

/**
 * What runs with nobody asking (docs/spec/server.md §Jobs): each minute, whatever's due, one at a time, each's last run kept
 * in `meta` so a restart neither repeats it nor misses it. Only once the server applies commands: in mirror mode the phone
 * does all of this. A job that fails is on the log and tried again after its wait; it stops neither the others nor the loop.
 */
class ServerJobs(
    private val db: Database,
    private val clock: WallClock,
    private val running: () -> Boolean,
    private val jobs: List<ServerJob>,
) {
    /** When each last failed: in memory, as a restart may as well try again. */
    private val failedAt = HashMap<String, Long>()

    /** One turn of the loop: what's due now. */
    suspend fun tick() {
        if (!running()) return
        for (job in jobs) {
            val key = "job:${job.name}"
            val now = clock.now()
            if (failedAt[job.name]?.let { now - it < job.retryMs } == true) continue
            if (!job.due(db.meta(key)?.toLongOrNull(), now)) continue
            try {
                job.work()
                db.setMeta(key, now.toString())
                failedAt.remove(job.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failedAt[job.name] = now
                Log.w(Log.TAG, "The job ${job.name} failed: ${failed::class.simpleName} at ${failed.stackTrace.firstOrNull { it.className.startsWith("com.thomaswcode") }}")
            }
        }
    }

    /** The loop, on threads that can wait: a job's writes wait for an open command, and the default pool is as small as the box. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            tick()
            delay(TURN_MS)
        }
    }

    private companion object {
        const val TURN_MS = 60_000L
    }
}
