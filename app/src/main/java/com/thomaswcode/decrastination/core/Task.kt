package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/** Where a task comes from. Each source is the only judge of whether its tasks are done. */
@Serializable
enum class Source(val label: String) {
    Teams("Teams"),
    PowerPlanner("Power Planner"),
    Anki("Anki"),
    Gmail("Gmail"),
}

/**
 * What sort of work a task is (PLAN.md §4). The planner orders a bucket by it, and calibration
 * learns an effort multiplier and a safety margin per kind.
 */
@Serializable
enum class Kind(val label: String, val defaultEffortMin: Int) {
    Homework("Homework", 40),
    Revision("Revision", 30),
    Admin("Admin", 15),

    /** Something that happens at a time (a call, an open day): a reminder, not work to plan. */
    Event("Event", 0),

    /** An email that only needs reading and archiving. */
    Info("Info", 2),
}

@Serializable
enum class Status {
    Open,

    /** The source says it's done: handed in, ticked, archived, or the cards studied. */
    Done,

    /** A task this app derived from a source's counts (the Anki quota) that stopped applying before it was done. */
    Missed,
}

/** One ordered part of a task, from the source or the enrichment, with its own estimate. */
@Serializable
data class SubStep(val title: String, val minutes: Int, val done: Boolean = false)

/**
 * One obligation from one source, in the shape every other part of the app works with
 * (PLAN.md §4). Times are epoch milliseconds.
 *
 * The source owns [title], [detail], [className], [dueAt], [availableFrom], [sourceEffortMin] and
 * [sourceProgress], and a sync overwrites them. Everything else is this app's own bookkeeping
 * and survives syncs: when the task was first and last seen, the minutes worked on it in focus
 * sessions, the sub-steps and estimates the enrichment gave it, and your own estimate.
 */
@Serializable
data class TaskItem(
    /** `<source>:<sourceId>`, stable across syncs. */
    val id: String,
    val source: Source,
    /** The source's own id: the Teams GUID, the Power Planner identifier, the Gmail thread id, an Anki deck. */
    val sourceId: String,
    val title: String,
    val detail: String = "",
    val className: String? = null,
    val dueAt: Long? = null,
    /** Hidden from the plan until then (a "wait until" email, an event's day-before reminder). */
    val availableFrom: Long? = null,
    val kind: Kind,
    /** An estimate the source itself implies (Anki's card counts), or the rules' guess. */
    val sourceEffortMin: Int? = null,
    /** The enrichment's estimate (Phase 4). */
    val aiEffortMin: Int? = null,
    /** Your own estimate, which beats every other. */
    val userEffortMin: Int? = null,
    /** How far the source says it is (Power Planner's percent complete), 0 to 1. */
    val sourceProgress: Double = 0.0,
    /** Minutes of finished focus sessions spent on it. */
    val workedMin: Int = 0,
    val subSteps: List<SubStep> = emptyList(),
    val status: Status = Status.Open,
    /**
     * Derived by this app from a source's counts (the daily Anki quota, a deck homework names)
     * rather than listed by the source: done only when the counts say so, and missed, not done,
     * if it stops applying first.
     */
    val derived: Boolean = false,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val doneAt: Long? = null,
    /** Source-specific values: Gmail's sender and labels, an Anki deck id, a Teams tab. */
    val extra: Map<String, String> = emptyMap(),
) {
    val isOpen: Boolean get() = status == Status.Open

    /** Your estimate, else the enrichment's, else the source's or rules', else the kind's default. */
    val effortMin: Int get() = userEffortMin ?: aiEffortMin ?: sourceEffortMin ?: kind.defaultEffortMin

    fun isAvailable(now: Long): Boolean = availableFrom == null || availableFrom <= now

    companion object {
        fun id(source: Source, sourceId: String): String = "${source.name.lowercase()}:$sourceId"
    }
}

/**
 * A source's view of one task, as read by a sync. [Merge] turns these into [TaskItem]s, keeping
 * the app's own bookkeeping.
 */
data class Fetched(
    val sourceId: String,
    val title: String,
    val kind: Kind,
    val detail: String = "",
    val className: String? = null,
    val dueAt: Long? = null,
    val availableFrom: Long? = null,
    val sourceEffortMin: Int? = null,
    val sourceProgress: Double = 0.0,
    /** The source lists it but says it's finished (Power Planner at 100 %, an Anki deck studied). */
    val done: Boolean = false,
    val derived: Boolean = false,
    val subSteps: List<SubStep>? = null,
    val extra: Map<String, String> = emptyMap(),
)
