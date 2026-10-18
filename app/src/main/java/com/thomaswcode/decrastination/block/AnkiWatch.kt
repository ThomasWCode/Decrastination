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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Time in AnkiDroid as work (NF-03): the decks' counts read as it comes to the front and as it goes,
 * so studying without touching this app ticks its pieces. Going out, a deck task whose work today
 * is all done has its slot kept (LR-09, earning by BL-08), the minutes past its piece's as more it
 * took; the speed a card is learned per deck (DX-11); and Anki is read again at once, so the plan
 * shows what's left (the counts are the progress: a piece part done is less of it). Each coming
 * and going is decided as it's seen and handled after the one before, so leaving before the read
 * coming in is done isn't lost behind it.
 */
class AnkiWatch(private val graph: AppGraph) {
    private sealed interface Event {
        data class Came(val at: Long) : Event
        data class Went(val at: Long) : Event
        data object Reset : Event
    }

    /** AnkiDroid's comings and goings, handled one at a time in the order they were seen. */
    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Whether AnkiDroid was in front as last seen; till the first, as the last run left it. */
    @Volatile
    private var inFront: Boolean? = null

    init {
        graph.scope.launch {
            for (event in events) {
                runCatching {
                    when (event) {
                        is Event.Came -> came(event.at)
                        is Event.Went -> went(event.at)
                        Event.Reset -> if (graph.runtime.value.ankiFrontSince != null) graph.runtime.update { it.copy(ankiFrontSince = null) }
                    }
                }.onFailure { Log.w(AppGraph.TAG, "Reading AnkiDroid's decks failed", it) }
            }
        }
    }

    /**
     * The focus service starting again: a stretch begun before it stopped is let go, as when it ended
     * isn't known (its cards would be spread over all the time the service was down).
     */
    fun reset() {
        inFront = false
        events.trySend(Event.Reset)
    }

    /** [pkg] is in front: AnkiDroid come, or gone. Cheap when it's neither. */
    fun seen(pkg: String) {
        val anki = pkg == AnkiProvider.PACKAGE
        val was = inFront ?: (graph.runtime.value.ankiFrontSince != null)
        inFront = anki
        if (anki != was) events.trySend(if (anki) Event.Came(graph.clock.now()) else Event.Went(graph.clock.now()))
    }

    private suspend fun came(now: Long) {
        if (graph.runtime.value.ankiFrontSince != null) return
        val reading = read(now, entering = true) ?: return
        graph.runtime.update { it.copy(ankiFrontSince = now, ankiReadings = (it.ankiReadings + reading).takeLast(AnkiProgress.KEEP)) }
        // In AnkiDroid in a deck task's slot: work on it, as Felix sees it (LR-03). Not on every deck
        // task: it may be the deck list, or another deck; going out, the decks that moved say which.
        graph.focus.plan(now).slotAt(now)?.chunk?.takeIf { it.source == Source.Anki }?.let { graph.felix.noteActivity(listOf(it.taskId), now) }
    }

    private suspend fun went(now: Long) {
        if (graph.runtime.value.ankiFrontSince == null) return
        val reading = read(now, entering = false)
        graph.runtime.update { state -> state.copy(ankiFrontSince = null, ankiReadings = reading?.let { (state.ankiReadings + it).takeLast(AnkiProgress.KEEP) } ?: state.ankiReadings) }
        reading ?: return
        val zone = graph.clock.zone()
        val stretches = AnkiProgress.stretches(graph.runtime.value.ankiReadings, zone)
        // A stretch across 04:00 (or one cut short) counts for nothing, but the decks are read again all the same.
        val stretch = stretches.lastOrNull()?.takeIf { it.to == reading }
        if (stretch != null) {
            val tasks = graph.tasks.value
            val plan = graph.planning.plan(now = now)
            // Visits that didn't finish a deck's cards: their minutes kept towards the one that does.
            var worked = graph.runtime.value.ankiWorked.on(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())
            val studied = AnkiProgress.done(stretch, tasks.tasks, reviewsOnly = tasks.ankiDay?.reviewsOnly == true)
            // The decks studied: work on their tasks, as Felix sees it (LR-03).
            graph.felix.noteActivity(studied.filter { it.cards > 0 }.map { it.taskId }, now)
            for (done in studied) {
                Log.i(AppGraph.TAG, "AnkiDroid: ${done.cards} cards for ${done.taskId} in ${done.minutes} min${if (done.finished) ", today's done" else ""}")
                if (!done.finished) {
                    worked = worked.plus(done.taskId, done.minutes)
                    continue
                }
                // Its next piece, today's or a later day's: studied ahead is kept all the same, with all
                // of today's time on it. The cards counted are its evidence, as a photo is written work's (BL-08).
                val piece = plan.ordered.firstOrNull { it.taskId == done.taskId } ?: continue
                val minutes = (worked.minutes[done.taskId] ?: 0) + done.minutes
                if (graph.focus.keepSlot(piece.key, extraMin = (minutes - piece.minutes).coerceAtLeast(0), verified = true, now = now)) worked = worked.without(done.taskId)
            }
            graph.runtime.update { it.copy(ankiWorked = worked) }
            // What a card takes, per deck (DX-11): a deck with new evidence takes it; the rest keep theirs (a backup's, say).
            val rates = AnkiProgress.secondsPerCard(stretches)
            graph.runtime.update {
                it.copy(
                    calibration = it.calibration.copy(
                        ankiSecondsPerCard = it.calibration.ankiSecondsPerCard + rates.mapValues { r -> r.value.first },
                        ankiStretches = it.calibration.ankiStretches + rates.mapValues { r -> r.value.second },
                    ),
                )
            }
        }
        graph.syncer.sync(setOf(Source.Anki))
    }

    private suspend fun read(now: Long, entering: Boolean): AnkiReading? = withContext(Dispatchers.IO) {
        if (!AnkiProvider.hasPermission(graph.app)) return@withContext null
        val decks: List<Deck> = AnkiProvider.decks(graph.app.contentResolver)
        AnkiReading(now, decks.associate { it.id to DeckCount(it.learn, it.review, it.new, it.isTopLevel) }, entering)
    }
}
