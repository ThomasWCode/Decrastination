package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.AnkiRules
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.ReadContext
import com.thomaswcode.decrastination.sources.SourceRead
import com.thomaswcode.decrastination.sources.TaskSource
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sync.protocol.SourceObserved
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
import kotlinx.serialization.json.Json

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
 * Reads each source and merges what it lists into the stored tasks (docs/spec/sources.md, `SyncWorker`).
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
    readContext: CoroutineContext = Dispatchers.IO,
    /** Seconds a card per Anki deck, as learned (DX-11). */
    private val ankiRates: () -> Map<Long, Double> = { emptyMap() },
    /**
     * Whether what's read here goes to the server as what's seen (W-24), once the two keep in step by commands: Teams and Anki,
     * which only this phone can read, sent as one command a source ([observed]), and what the server reads itself left to it.
     */
    private val observing: () -> Boolean = { false },
    /** What was read, as a command: applied here through [observe], as on the server. */
    private val observed: suspend (List<SourceObserved>) -> SyncReport = { SyncReport() },
) {
    private val sources = sources.sortedBy { it.source == Source.Anki }
    private val readers = CoroutineScope(SupervisorJob() + readContext)
    private val lock = Mutex()
    private val listeners = mutableListOf<suspend (SyncReport) -> Unit>()

    /** Each source's latest read, one given up on included: touched only under [lock]. */
    private val reads = HashMap<Source, Deferred<SourceRead>>()
    private val afterEvery = mutableListOf<suspend () -> Unit>()

    /** Each source's read as last sent, by its hash: one read the same again is sent as its status alone. Under [lock]. */
    private val lastSent = HashMap<Source, String>()

    /** Called after each sync that changed anything, in the order added. */
    fun addListener(listener: suspend (SyncReport) -> Unit) {
        listeners += listener
    }

    /** Called after every sync, changed or not: a task can change in place (a new email in a thread). */
    fun addAfterEverySync(listener: suspend () -> Unit) {
        afterEvery += listener
    }

    suspend fun sync(only: Set<Source>? = null): SyncReport = lock.withLock {
        if (observing()) return@withLock sent(only)
        var report = SyncReport()
        for (source in sources) {
            if (only != null && source.source !in only) continue
            report += when (val read = read(source)) {
                is Read.Done -> merged(source.source, read.read, read.startedAt)
                is Read.Failed -> failed(source.source, read.startedAt, read.why)
            }
        }
        after(report)
        report
    }

    /**
     * What this phone alone can read, read and sent to the server as what's seen, one command a source, each applied here as there
     * ([observe]) before the next is read, as Anki's read is made from the homework Teams' brought; what the server reads itself
     * (Power Planner, Gmail) isn't read here. A read the same as the last sent goes as its status alone, so the server's record
     * doesn't fill with copies of it: as last sent once its command's on record, and not before, so a sync stopped part-way sends
     * it whole the next time; and whole where a note's taken the place of its own since.
     */
    private suspend fun sent(only: Set<Source>?): SyncReport {
        var report = SyncReport()
        for (source in sources) {
            if ((only != null && source.source !in only) || source.source in Source.SERVERS) continue
            val (seen, hash) = when (val read = read(source)) {
                is Read.Failed -> SourceObserved(source.source, read.startedAt, failed = read.why) to null
                is Read.Done -> Protocol.hash(JSON.encodeToString(SourceRead.serializer(), read.read)).let { hash ->
                    // Whole where a note's been put where its status shows since (the Teams widget refusing to sync): a read's own
                    // takes its place, and the status alone would keep it.
                    val same = lastSent[source.source] == hash && tasks.value.status(source.source).note == read.read.note
                    SourceObserved(source.source, read.startedAt, read.read.takeUnless { same }) to hash
                }
            }
            report += observed(listOf(seen))
            if (hash != null) lastSent[source.source] = hash
        }
        return report
    }

    /**
     * What a phone read and sent ([ObserveSources]), merged as a read here is, the same on the server and on the phone that read
     * it; then what follows a sync. A source read the same as before has only its status, as its tasks are as that read left them.
     */
    suspend fun observe(seen: List<SourceObserved>): SyncReport {
        var report = SyncReport()
        for (one in seen) {
            report += when {
                one.failed != null -> failed(one.source, one.startedAt, one.failed)
                one.read != null -> merged(one.source, one.read, one.startedAt)
                else -> readAgain(one.source, one.startedAt)
            }
        }
        after(report)
        return report
    }

    /** [source]'s note, a refusal to sync say, where its status shows it: the next read takes its place. */
    suspend fun note(source: Source, note: String) {
        tasks.update { state -> state.copy(sources = state.sources + (source to state.status(source).copy(note = note))) }
    }

    /** What follows a sync: what's listening, where something changed, then what follows every one. */
    private suspend fun after(report: SyncReport) {
        if (report.completed.isNotEmpty() || report.reopened.isNotEmpty() || report.added.isNotEmpty() || report.missed.isNotEmpty()) {
            listeners.forEach { it(report) }
        }
        afterEvery.forEach { it() }
    }

    /** A source's read, or why there's none. */
    private sealed interface Read {
        data class Done(val read: SourceRead, val startedAt: Long) : Read

        data class Failed(val why: String, val startedAt: Long) : Read
    }

    private suspend fun read(source: TaskSource): Read {
        val startedAt = clock.now()
        val state = tasks.value
        val context = ReadContext(startedAt, clock.zone(), settings.value, state.tasks, state.ankiDay, ankiRates())
        // The last read given up on, still stuck in a call that can't be stopped: not another.
        if (reads[source.source]?.isCompleted == false) return Read.Failed("Still waiting for the last read, which hasn't answered", startedAt)
        val reading = readers.async { source.read(context) }
        reads[source.source] = reading
        return try {
            Read.Done(withTimeout(timeoutMs) { reading.await() }, startedAt)
        } catch (e: TimeoutCancellationException) {
            reading.cancel()
            onFailure(source.source, e)
            Read.Failed("No answer in ${timeoutMs / 1000} s", startedAt)
        } catch (e: CancellationException) {
            // The sync itself was cancelled: so is the read, and that's no failure of the source.
            reading.cancel()
            throw e
        } catch (e: Exception) {
            onFailure(source.source, e)
            Read.Failed(e.message ?: e.javaClass.simpleName, startedAt)
        }
    }

    /** [source] read again as it was last: its tasks as they are, its status as read now. */
    private suspend fun readAgain(source: Source, startedAt: Long): SyncReport {
        tasks.update { state -> state.copy(sources = state.sources + (source to state.status(source).copy(lastSuccessAt = clock.now(), lastAttemptAt = startedAt, error = null))) }
        return SyncReport()
    }

    /** [read], [source]'s, merged into the tasks. */
    private suspend fun merged(source: Source, read: SourceRead, startedAt: Long): SyncReport {
        var report = SyncReport()
        tasks.update { current ->
            val now = clock.now()
            // Merged, and what was finished a fortnight ago kept as its record (C-Q3), whichever source it's from.
            val merged = Merge.apply(current.tasks, source, read.items, now).let { it.copy(tasks = Merge.trimmed(it.tasks, now, GmailThreads.TEXT_EXTRAS)) }
            report = SyncReport(
                completed = merged.completed,
                reopened = merged.reopened,
                added = merged.added,
                missed = merged.missed,
            )
            // A deck topic an assignment names, come or changed (DX-10): its reading is out of date, so
            // it's read again with the decks as they are, and its vocabulary isn't planned twice.
            val topics = read.deckTopics?.takeIf { it != current.deckTopics }
            current.copy(
                tasks = if (topics == null) {
                    merged.tasks
                } else {
                    merged.tasks.map { t ->
                        val named = t.source == Source.Teams && t.isOpen && t.enrichment != null && AnkiRules.describedSections(t, current.deckTopics) != AnkiRules.describedSections(t, topics)
                        if (named) t.copy(enrichment = t.enrichment?.copy(inputHash = "")) else t
                    }
                },
                // Their free time and record are given after; saved here, they can't be lost.
                // An email that's just an email, or a task you've said isn't one, finished isn't work done: nothing to reward.
                unrewarded = current.unrewarded + merged.completed.filterNot { it.hidden },
                sources = current.sources + (source to SourceStatus(lastSuccessAt = now, lastAttemptAt = startedAt, dataAsOf = read.dataAsOf, note = read.note)),
                ankiDay = read.ankiDay ?: current.ankiDay,
                deckTopics = read.deckTopics ?: current.deckTopics,
                timetable = read.timetable ?: current.timetable,
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

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
