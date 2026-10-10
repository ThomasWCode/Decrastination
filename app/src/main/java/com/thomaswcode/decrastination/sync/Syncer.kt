package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** What one sync found, for the log, the credit and the self-assessment prompts. */
data class SyncReport(
    val failures: Map<Source, String> = emptyMap(),
    val completed: List<TaskItem> = emptyList(),
    val reopened: List<TaskItem> = emptyList(),
    val added: List<TaskItem> = emptyList(),
    val missed: List<TaskItem> = emptyList(),
) {
    operator fun plus(other: SyncReport) = SyncReport(
        failures + other.failures,
        completed + other.completed,
        reopened + other.reopened,
        added + other.added,
        missed + other.missed,
    )
}

/**
 * Reads each source and merges what it lists into the stored tasks (PLAN.md §4, `SyncWorker`).
 * Sources are isolated: one that fails keeps its tasks as they were and records why, and the rest
 * carry on. Anki is read last, since the decks it tracks are the ones the other sources' homework
 * names. One sync runs at a time.
 *
 * Sources read with blocking calls (a socket, an HTTP connection, another app's provider), which
 * a coroutine timeout can't interrupt. So each read runs on its own, outside the sync, which waits
 * for it no longer than [timeoutMs]: a read that overruns is cancelled and left to wind down (Gmail
 * closes its socket when cancelled), and its answer, if it ever comes, is ignored. Until it has
 * wound down, no other read of that source starts beside it: a provider stuck for good holds one
 * read, not one a sync (BUG-P2-020).
 */
class Syncer(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    sources: List<TaskSource>,
    private val clock: WallClock,
    private val timeoutMs: Long = 90_000,
    /** Hears each source's failure in full, for the log; the store keeps only its message. */
    private val onFailure: (Source, Throwable) -> Unit = { _, _ -> },
    /** Whether a task's reading is still to come ([Merge.apply]'s `unread`). */
    private val unread: (TaskItem) -> Boolean = { Enrichments.current(it) == null },
    readContext: CoroutineContext = Dispatchers.IO,
) {
    private val sources = sources.sortedBy { it.source == Source.Anki }
    private val readers = CoroutineScope(SupervisorJob() + readContext)
    private val lock = Mutex()
    private val listeners = mutableListOf<suspend (SyncReport) -> Unit>()

    /** Each source's latest read, one given up on included: touched only under [lock]. */
    private val reads = HashMap<Source, Deferred<SourceRead>>()
    private val afterEvery = mutableListOf<suspend () -> Unit>()

    /** Called after each sync that changed anything, in the order added. */
    fun addListener(listener: suspend (SyncReport) -> Unit) {
        listeners += listener
    }

    /** Called after every sync, changed or not: a task can change in place (a new email in a thread). */
    fun addAfterEverySync(listener: suspend () -> Unit) {
        afterEvery += listener
    }

    suspend fun sync(only: Set<Source>? = null): SyncReport = lock.withLock {
        var report = SyncReport()
        for (source in sources) {
            if (only != null && source.source !in only) continue
            report += readOne(source)
        }
        if (report.completed.isNotEmpty() || report.reopened.isNotEmpty() || report.added.isNotEmpty() || report.missed.isNotEmpty()) {
            listeners.forEach { it(report) }
        }
        afterEvery.forEach { it() }
        report
    }

    private suspend fun readOne(source: TaskSource): SyncReport {
        val startedAt = clock.now()
        val state = tasks.value
        val context = ReadContext(startedAt, clock.zone(), settings.value, state.tasks, state.ankiDay)
        // The last read given up on, still stuck in a call that can't be stopped: not another.
        if (reads[source.source]?.isCompleted == false) return failed(source.source, startedAt, "Still waiting for the last read, which hasn't answered")
        val reading = readers.async { source.read(context) }
        reads[source.source] = reading
        val read = try {
            withTimeout(timeoutMs) { reading.await() }
        } catch (e: TimeoutCancellationException) {
            reading.cancel()
            onFailure(source.source, e)
            return failed(source.source, startedAt, "No answer in ${timeoutMs / 1000} s")
        } catch (e: CancellationException) {
            // The sync itself was cancelled: so is the read, and that's no failure of the source.
            reading.cancel()
            throw e
        } catch (e: Exception) {
            onFailure(source.source, e)
            return failed(source.source, startedAt, e.message ?: e.javaClass.simpleName)
        }
        var report = SyncReport()
        tasks.update { current ->
            val now = clock.now()
            val merged = Merge.apply(current.tasks, source.source, read.items, now, unread)
            report = SyncReport(
                completed = merged.completed,
                reopened = merged.reopened,
                added = merged.added,
                missed = merged.missed,
            )
            current.copy(
                tasks = merged.tasks,
                // Their free time and record are given after; saved here, they can't be lost.
                // An email that's just an email, or a task you've said isn't one, finished isn't work done: nothing to reward.
                unrewarded = current.unrewarded + merged.completed.filterNot { it.hidden },
                sources = current.sources + (source.source to SourceStatus(lastSuccessAt = now, lastAttemptAt = startedAt, dataAsOf = read.dataAsOf, note = read.note)),
                ankiDay = read.ankiDay ?: current.ankiDay,
            )
        }
        return report
    }

    private suspend fun failed(source: Source, at: Long, why: String): SyncReport {
        tasks.update { current ->
            val previous = current.status(source)
            current.copy(sources = current.sources + (source to previous.copy(lastAttemptAt = at, error = why)))
        }
        return SyncReport(failures = mapOf(source to why))
    }
}
