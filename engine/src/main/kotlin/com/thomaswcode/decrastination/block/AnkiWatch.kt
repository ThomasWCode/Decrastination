package com.thomaswcode.decrastination.block

import com.thomaswcode.decrastination.core.Deck
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.AnkiState
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.learn.AnkiProgress
import com.thomaswcode.decrastination.learn.AnkiReading
import com.thomaswcode.decrastination.learn.DeckCount
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Sync
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
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
class AnkiWatch(
    private val anki: JsonStore<AnkiState>,
    private val learning: JsonStore<LearningState>,
    private val tasks: JsonStore<TaskState>,
    private val clock: WallClock,
    scope: CoroutineScope,
    private val focus: Focus,
    private val planning: Planning,
    private val sync: Sync,
    /** AnkiDroid's package, by which it's known in front. */
    private val ankiPackage: String,
    /** Its decks as they are now; null where they can't be read. */
    private val decks: () -> List<Deck>?,
    /** Work on [taskIds] as Felix sees it (LR-03). */
    private val noteActivity: suspend (taskIds: List<String>, now: Long) -> Unit,
) {
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
        scope.launch {
            for (event in events) {
                runCatching {
                    when (event) {
                        is Event.Came -> came(event.at)
                        is Event.Went -> went(event.at)
                        Event.Reset -> if (anki.value.ankiFrontSince != null) anki.update { it.copy(ankiFrontSince = null) }
                    }
                }.onFailure { Log.w(Log.TAG, "Reading AnkiDroid's decks failed", it) }
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
        val isAnki = pkg == ankiPackage
        val was = inFront ?: (anki.value.ankiFrontSince != null)
        inFront = isAnki
        if (isAnki != was) events.trySend(if (isAnki) Event.Came(clock.now()) else Event.Went(clock.now()))
    }

    private suspend fun came(now: Long) {
        if (anki.value.ankiFrontSince != null) return
        val reading = read(now, entering = true) ?: return
        anki.update { it.copy(ankiFrontSince = now, ankiReadings = (it.ankiReadings + reading).takeLast(AnkiProgress.KEEP)) }
        // In AnkiDroid in a deck task's slot: work on it, as Felix sees it (LR-03). Not on every deck
        // task: it may be the deck list, or another deck; going out, the decks that moved say which.
        focus.plan(now).slotAt(now)?.chunk?.takeIf { it.source == Source.Anki }?.let { noteActivity(listOf(it.taskId), now) }
    }

    private suspend fun went(now: Long) {
        if (anki.value.ankiFrontSince == null) return
        val reading = read(now, entering = false)
        anki.update { state -> state.copy(ankiFrontSince = null, ankiReadings = reading?.let { (state.ankiReadings + it).takeLast(AnkiProgress.KEEP) } ?: state.ankiReadings) }
        reading ?: return
        val zone = clock.zone()
        val stretches = AnkiProgress.stretches(anki.value.ankiReadings, zone)
        // A stretch across 04:00 (or one cut short) counts for nothing, but the decks are read again all the same.
        val stretch = stretches.lastOrNull()?.takeIf { it.to == reading }
        if (stretch != null) {
            val state = tasks.value
            val plan = planning.plan(now = now)
            // Visits that didn't finish a deck's cards: their minutes kept towards the one that does.
            var worked = anki.value.ankiWorked.on(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())
            val studied = AnkiProgress.done(stretch, state.tasks, reviewsOnly = state.ankiDay?.reviewsOnly == true)
            // The decks studied: work on their tasks, as Felix sees it (LR-03).
            noteActivity(studied.filter { it.cards > 0 }.map { it.taskId }, now)
            for (done in studied) {
                Log.i(Log.TAG, "AnkiDroid: ${done.cards} cards for ${done.taskId} in ${done.minutes} min${if (done.finished) ", today's done" else ""}")
                if (!done.finished) {
                    worked = worked.plus(done.taskId, done.minutes)
                    continue
                }
                // Its next piece, today's or a later day's: studied ahead is kept all the same, with all
                // of today's time on it. The cards counted are its evidence, as a photo is written work's (BL-08).
                val piece = plan.ordered.firstOrNull { it.taskId == done.taskId } ?: continue
                val minutes = (worked.minutes[done.taskId] ?: 0) + done.minutes
                if (focus.keepSlot(piece.key, extraMin = (minutes - piece.minutes).coerceAtLeast(0), verified = true, now = now)) worked = worked.without(done.taskId)
            }
            anki.update { it.copy(ankiWorked = worked) }
            // What a card takes, per deck (DX-11): a deck with new evidence takes it; the rest keep theirs (a backup's, say).
            val rates = AnkiProgress.secondsPerCard(stretches)
            learning.update {
                it.copy(
                    calibration = it.calibration.copy(
                        ankiSecondsPerCard = it.calibration.ankiSecondsPerCard + rates.mapValues { r -> r.value.first },
                        ankiStretches = it.calibration.ankiStretches + rates.mapValues { r -> r.value.second },
                    ),
                )
            }
        }
        sync.sync(setOf(Source.Anki))
    }

    private suspend fun read(now: Long, entering: Boolean): AnkiReading? = withContext(Dispatchers.IO) {
        val read = decks() ?: return@withContext null
        AnkiReading(now, read.associate { it.id to DeckCount(it.learn, it.review, it.new, it.isTopLevel) }, entering)
    }
}
