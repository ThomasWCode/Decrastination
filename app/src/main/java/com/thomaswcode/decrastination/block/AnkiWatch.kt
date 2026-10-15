package com.thomaswcode.decrastination.block

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.learn.AnkiProgress
import com.thomaswcode.decrastination.learn.AnkiReading
import com.thomaswcode.decrastination.learn.DeckCount
import com.thomaswcode.decrastination.sources.anki.AnkiProvider
import com.thomaswcode.decrastination.sources.anki.Deck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Time in AnkiDroid as work (NF-03): the decks' counts read as it comes to the front and as it goes,
 * so studying without touching this app ticks its pieces. Going out, a deck task whose work today
 * is all done has its slot kept (LR-09, earning by BL-08), the minutes past its piece's as more it
 * took; the speed a card is learned per deck (DX-11); and Anki is read again at once, so the plan
 * shows what's left (the counts are the progress: a piece part done is less of it).
 */
class AnkiWatch(private val graph: AppGraph) {
    private val lock = Mutex()

    /** [pkg] is in front: AnkiDroid come, or gone. Cheap when it's neither. */
    fun seen(pkg: String) {
        val inside = graph.runtime.value.ankiFrontSince != null
        when {
            pkg == AnkiProvider.PACKAGE && !inside -> graph.scope.launch { runCatching { came() }.onFailure { Log.w(AppGraph.TAG, "Reading AnkiDroid's decks failed", it) } }
            pkg != AnkiProvider.PACKAGE && inside -> graph.scope.launch { runCatching { went() }.onFailure { Log.w(AppGraph.TAG, "Reading AnkiDroid's decks failed", it) } }
        }
    }

    private suspend fun came(now: Long = graph.clock.now()): Unit = lock.withLock {
        if (graph.runtime.value.ankiFrontSince != null) return@withLock
        val reading = read(now, entering = true) ?: return@withLock
        graph.runtime.update { it.copy(ankiFrontSince = now, ankiReadings = (it.ankiReadings + reading).takeLast(AnkiProgress.KEEP)) }
    }

    private suspend fun went(now: Long = graph.clock.now()): Unit = lock.withLock {
        if (graph.runtime.value.ankiFrontSince == null) return@withLock
        val reading = read(now, entering = false)
        graph.runtime.update { state -> state.copy(ankiFrontSince = null, ankiReadings = reading?.let { (state.ankiReadings + it).takeLast(AnkiProgress.KEEP) } ?: state.ankiReadings) }
        reading ?: return@withLock
        val zone = graph.clock.zone()
        val stretches = AnkiProgress.stretches(graph.runtime.value.ankiReadings, zone)
        val stretch = stretches.lastOrNull()?.takeIf { it.to == reading } ?: return@withLock
        val tasks = graph.tasks.value
        val plan = graph.plan(now = now)
        for (done in AnkiProgress.done(stretch, tasks.tasks, reviewsOnly = tasks.ankiDay?.reviewsOnly == true)) {
            Log.i(AppGraph.TAG, "AnkiDroid: ${done.cards} cards for ${done.taskId} in ${done.minutes} min${if (done.finished) ", today's done" else ""}")
            if (!done.finished) continue
            val piece = plan.todayBucket?.chunks?.firstOrNull { it.taskId == done.taskId } ?: continue
            graph.focus.keepSlot(piece.key, extraMin = (done.minutes - piece.minutes).coerceAtLeast(0), now = now)
        }
        // What a card takes, per deck, from the stretches so far (DX-11).
        val rates = AnkiProgress.secondsPerCard(stretches)
        graph.runtime.update { it.copy(calibration = it.calibration.copy(ankiSecondsPerCard = rates.mapValues { r -> r.value.first }, ankiStretches = rates.mapValues { r -> r.value.second })) }
        graph.syncer.sync(setOf(Source.Anki))
    }

    private suspend fun read(now: Long, entering: Boolean): AnkiReading? = withContext(Dispatchers.IO) {
        if (!AnkiProvider.hasPermission(graph.app)) return@withContext null
        val decks: List<Deck> = AnkiProvider.decks(graph.app.contentResolver)
        AnkiReading(now, decks.associate { it.id to DeckCount(it.learn, it.review, it.new, it.isTopLevel) }, entering)
    }
}
