package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiDay
import com.thomaswcode.decrastination.sources.powerplanner.Timetable
import kotlinx.serialization.Serializable

/** Every task, open or recently finished, and how each source's last read went. Kept in `tasks.json`. */
@Serializable
data class TaskState(
    val tasks: List<TaskItem> = emptyList(),
    val sources: Map<Source, SourceStatus> = emptyMap(),
    /** Which deck today's Anki quota takes its new cards from, fixed at the day's first read. */
    val ankiDay: AnkiDay? = null,
    /** The textbook's vocabulary decks' topics, by section (DX-10): matched against homework, and told to the model. */
    val deckTopics: Map<String, String> = emptyMap(),
    /**
     * Completions syncs confirmed, as they were when confirmed, whose free time and log record
     * aren't given yet. Saved with the tasks' move to done, so a stop before they're given can't
     * lose them (`Focus.rewardCompletions`).
     */
    val unrewarded: List<TaskItem> = emptyList(),
    /**
     * Power Planner's timetable from its last good read (DX-09): the school days' study periods
     * come from it, and a failed read leaves it as it was.
     */
    val timetable: Timetable? = null,
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
