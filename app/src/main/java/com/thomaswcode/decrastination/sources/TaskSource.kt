package com.thomaswcode.decrastination.sources

import com.thomaswcode.decrastination.core.Fetched
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.sources.anki.AnkiDay
import java.time.ZoneId

/**
 * One of the four places tasks come from (PLAN.md §1). A read returns everything the source has
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
)

data class SourceRead(
    val items: List<Fetched>,
    /** When the source's own data was last refreshed, if that isn't now (the Teams widget's last sync). */
    val dataAsOf: Long? = null,
    val note: String? = null,
    /** Anki only: today's quota deck, as fixed at the day's first read. */
    val ankiDay: AnkiDay? = null,
)

/** A source that can't be read for a reason the setup screen can fix, such as a missing password. */
class SourceUnavailable(message: String) : Exception(message)
