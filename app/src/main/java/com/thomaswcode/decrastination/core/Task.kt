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
data class SubStep(
    val title: String,
    val minutes: Int,
    val done: Boolean = false,
    /** The vocabulary sections ("1.2") this step learns: left out of the plan where Anki deck tasks hold them. */
    val ankiSections: List<String> = emptyList(),
    /** Not to be started before then: a block whose window opens on a date (an application that opens on 30 Oct). */
    val from: Long? = null,
    /** Its own deadline, before the task's: one of several dated items in one email or planner item. */
    val dueAt: Long? = null,
    /** Ticked off by hand in Tasks, not by a session or a photo check: done, but earning nothing. */
    val byHand: Boolean = false,
    /** Ticked by hand: the task's timed minutes not yet taken up by its other steps that it kept then, worked on it before the tick. */
    val timedMin: Int = 0,
)

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
    /**
     * How far it was when this app first saw it: what was left then is the work its completion
     * earns time for, however its progress moved on the way. Null for a task saved before this was kept.
     */
    val firstProgress: Double? = null,
    /**
     * The most the source's estimate has been: a count that shrinks as it's worked through (an
     * Anki deck's cards) ends near nothing, and its completion is for all of it.
     */
    val peakEffortMin: Int? = null,
    /** Minutes of finished focus sessions spent on it. */
    val workedMin: Int = 0,
    /**
     * Minutes of it a photo check found done: they come off what's left like [workedMin], but
     * weren't timed, so they're kept apart from it and out of what the app learns from timing.
     */
    val photoMin: Int = 0,

    /** The sessions counted in [workedMin], by start time, so each is counted once. */
    val sessionsCounted: List<Long> = emptyList(),
    /** The photo checks counted in [photoMin], by id, with the minutes each was given, so each is counted once. */
    val photosCounted: Map<String, Int> = emptyMap(),
    val subSteps: List<SubStep> = emptyList(),
    /**
     * Of [workedMin] and [photoMin], the minutes the source's own steps already leave out: an Anki
     * deck's cards studied in sessions are gone from the steps its next read gives, so those
     * minutes aren't taken off what's left of them again. Set when the source's steps replace the
     * ones kept here.
     */
    val stepsWorkedMin: Int = 0,
    /** At most this many of its sub-steps can be done in a day: an Anki deck releases 20 new cards a day. */
    val stepsPerDay: Int? = null,
    /**
     * Its work can't start before then, though it's due as ever: an Anki deck whose new cards for
     * today are studied releases more only after Anki's next 04:00.
     */
    val notBefore: Long? = null,
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
    /** What the enrichment said about it (Phase 4), laid over the source's values at every merge. */
    val enrichment: Enrichment? = null,
    /** The source's own kind, deadline and start, before the enrichment's overlay; null before Phase 4. */
    val sourceValues: SourceValues? = null,
    /**
     * Your instructions' say about it ([Instructions]), laid over the source's and the enrichment's
     * at every merge: not a task; not before then; not until another task is done; due by then.
     */
    val userNotATask: Boolean = false,
    val userFrom: Long? = null,
    val userAfter: String? = null,
    val userDueAt: Long? = null,
) {
    val isOpen: Boolean get() = status == Status.Open

    /**
     * An email that only needs reading, or tells of an event (your call, 9 Oct): not a task. It's
     * kept, so archiving it still finishes it and a reply that asks something brings it back as a
     * task, but it's kept off the plan (so the widget, the briefing and blocking), the Tasks list,
     * and the rewards for finishing work. Its kind is the enrichment's reading, or the rules'.
     */
    val justAnEmail: Boolean get() = source == Source.Gmail && (kind == Kind.Info || kind == Kind.Event)

    /** Not work to plan: an email that's just an email, or one you've said isn't a task. */
    val hidden: Boolean get() = justAnEmail || userNotATask

    /** Your estimate, else the enrichment's, else the source's or rules', else the kind's default. */
    /**
     * Minutes of blocks you ticked off by hand in Tasks: done, but neither timed nor checked, so
     * they earn no free time when the task completes and teach the calibration nothing. From the
     * blocks themselves, so a block re-estimated since counts at its minutes now.
     */
    val handMin: Int get() = subSteps.filter { it.done && it.byHand }.sumOf { it.minutes }

    val effortMin: Int get() = userEffortMin ?: aiEffortMin ?: sourceEffortMin ?: kind.defaultEffortMin

    fun isAvailable(now: Long): Boolean = availableFrom == null || availableFrom <= now

    companion object {
        fun id(source: Source, sourceId: String): String = "${source.name.lowercase()}:$sourceId"
    }
}

/** What a source said about a task's kind, deadline and start, kept so an overlay can be redone. */
@Serializable
data class SourceValues(val kind: Kind, val dueAt: Long? = null, val availableFrom: Long? = null)

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
    val stepsPerDay: Int? = null,
    val notBefore: Long? = null,
    val extra: Map<String, String> = emptyMap(),
)
