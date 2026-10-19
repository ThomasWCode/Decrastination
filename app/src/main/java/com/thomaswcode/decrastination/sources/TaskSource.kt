package com.thomaswcode.decrastination.sources

import com.thomaswcode.decrastination.core.AnkiDay
import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import java.time.ZoneId

/**
 * One of the four places tasks come from (docs/spec/sources.md). A read returns everything the source has
 * now, or throws: a failure must never look like an empty list, since [com.thomaswcode.decrastination.core.Merge]
 * takes a task missing from a read as done.
 */
interface TaskSource {
    val source: Source

    suspend fun read(context: ReadContext): SourceRead
}

/** What a read may need besides the source itself. */
data class ReadContext(
    val now: Long,
    val zone: ZoneId,
    val settings: Settings,
    /** Every stored task, all sources: Gmail reuses what it already knows, Anki reads what homework names. */
    val known: List<TaskItem>,
    val ankiDay: AnkiDay?,
    /** Seconds a card, per deck, as learned (DX-11): the Anki tasks' minutes go by them. */
    val ankiSecondsPerCard: Map<Long, Double> = emptyMap(),
)

data class SourceRead(
    val items: List<Fetched>,
    /** When the source's own data was last refreshed, if that isn't now (the Teams widget's last sync). */
    val dataAsOf: Long? = null,
    val note: String? = null,
    /** Anki only: today's quota deck, as fixed at the day's first read. */
    val ankiDay: AnkiDay? = null,
    /** Power Planner only: the timetable the read used, kept for the days' windows (DX-09). */
    val timetable: Timetable? = null,
    /** Anki only: the textbook's decks' topics, by section (DX-10). */
    val deckTopics: Map<String, String>? = null,
)

/** A source that can't be read for a reason the setup screen can fix, such as a missing password. */
class SourceUnavailable(message: String) : Exception(message)
