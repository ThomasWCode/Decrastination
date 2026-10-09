package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.TaskSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

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
 * closes its socket when cancelled), and its answer, if it ever comes, is ignored.
 */
class Syncer(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    sources: List<TaskSource>,
    private val clock: WallClock,
    private val timeoutMs: Long = 90_000,
    /** Hears each source's failure in full, for the log; the store keeps only its message. */
    private val onFailure: (Source, Throwable) -> Unit = { _, _ -> },
    readContext: CoroutineContext = Dispatchers.IO,
) {
    private val sources = sources.sortedBy { it.source == Source.Anki }
    private val readers = CoroutineScope(SupervisorJob() + readContext)
    private val lock = Mutex()
    private val listeners = mutableListOf<suspend (SyncReport) -> Unit>()
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
        val reading = readers.async { source.read(context) }
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
            val merged = Merge.apply(current.tasks, source.source, read.items, now)
            report = SyncReport(
                completed = merged.completed,
                reopened = merged.reopened,
                added = merged.added,
                missed = merged.missed,
            )
            current.copy(
                tasks = merged.tasks,
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
