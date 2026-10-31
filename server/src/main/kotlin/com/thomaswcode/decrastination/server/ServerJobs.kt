package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.ports.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Something the server does by itself: when it's due, given when it last ran, and the work. */
class ServerJob(val name: String, val due: (last: Long?, now: Long) -> Boolean, val work: suspend () -> Unit) {
    companion object {
        /** Due [everyMs] after it last ran, and at once if it never has. */
        fun every(name: String, everyMs: Long, work: suspend () -> Unit) = ServerJob(name, { last, now -> last == null || now - last >= everyMs }, work)
    }
}

/**
 * What runs with nobody asking (docs/spec/server.md §Jobs): each minute, whatever's due, one at a time, each's last run kept
 * in `meta` so a restart neither repeats it nor misses it. Only once the server applies commands: in mirror mode the phone
 * does all of this. A job that fails is on the log and tried again at its next turn; it stops neither the others nor the loop.
 */
class ServerJobs(
    private val db: Database,
    private val clock: WallClock,
    private val running: () -> Boolean,
    private val jobs: List<ServerJob>,
) {
    /** One turn of the loop: what's due now. */
    suspend fun tick() {
        if (!running()) return
        for (job in jobs) {
            val key = "job:${job.name}"
            val now = clock.now()
            if (!job.due(db.meta(key)?.toLongOrNull(), now)) continue
            try {
                job.work()
                db.setMeta(key, now.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                Log.w(Log.TAG, "The job ${job.name} failed: ${failed::class.simpleName} at ${failed.stackTrace.firstOrNull { it.className.startsWith("com.thomaswcode") }}")
            }
        }
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            tick()
            delay(TURN_MS)
        }
    }

    private companion object {
        const val TURN_MS = 60_000L
    }
}
