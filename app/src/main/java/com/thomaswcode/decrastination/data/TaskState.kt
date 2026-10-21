package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.AnkiDay
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Timetable
import com.thomaswcode.decrastination.core.withIds
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
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 2,
) {
    val open: List<TaskItem> get() = tasks.filter { it.isOpen }

    /** Every step of every task, and of every completion waiting, with an id ([withIds]): given as it's first stored. */
    fun withStepIds(): TaskState {
        fun TaskItem.ided(): TaskItem = subSteps.withIds().let { if (it === subSteps) this else copy(subSteps = it) }
        val all = tasks.map { it.ided() }
        val waiting = unrewarded.map { it.ided() }
        return if (all.indices.all { all[it] === tasks[it] } && waiting.indices.all { waiting[it] === unrewarded[it] }) this else copy(tasks = all, unrewarded = waiting)
    }

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
) {
    /**
     * When the last read that worked began, all it saw being from then or after (a success's [lastAttemptAt]):
     * what changed in the source after that read began isn't taken as seen by it, as Gmail's finishes need. Null
     * where the last read failed (when the one that worked began isn't kept) or none has worked.
     */
    val lastSuccessStartedAt: Long? get() = if (error == null && lastSuccessAt != null) lastAttemptAt else null
}
