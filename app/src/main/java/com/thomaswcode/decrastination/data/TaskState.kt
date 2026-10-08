package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiDay
import kotlinx.serialization.Serializable

/** Every task, open or recently finished, and how each source's last read went. Kept in `tasks.json`. */
@Serializable
data class TaskState(
    val tasks: List<TaskItem> = emptyList(),
    val sources: Map<Source, SourceStatus> = emptyMap(),
    /** Which deck today's Anki quota takes its new cards from, fixed at the day's first read. */
    val ankiDay: AnkiDay? = null,
) {
    val open: List<TaskItem> get() = tasks.filter { it.isOpen }

    fun status(source: Source): SourceStatus = sources[source] ?: SourceStatus()
}

/** How reading one source last went. */
@Serializable
data class SourceStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    /** Why the last read failed; null when it worked. The tasks from the last good read are kept. */
    val error: String? = null,
    /**
     * When the source's own data was last refreshed, where that differs from when it was read:
     * the Teams widget's last sync of Teams.
     */
    val dataAsOf: Long? = null,
    /** A line about the source's own state, such as the Teams widget's sync failing. */
    val note: String? = null,
)
