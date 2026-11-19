package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.core.SlotState
import kotlinx.serialization.Serializable

/** What the blocker keeps from moment to moment, changing every half minute: the focus's own (Focus). Kept in `session.json`. */
@Serializable
data class SessionState(
    val credit: Credit = Credit(),
    /** Today's slot starts as the last plan laid them, and the slots missed (PL-13). */
    val slots: SlotState = SlotState(),
    /** Photo checks found done whose due (the task's step or minutes, the log, free time) isn't all given yet. */
    val photosDone: List<PhotoDone> = emptyList(),
    /** Slots being kept whose due (the task's step or minutes, the log, free time) isn't all given yet (LR-09). */
    val keeping: List<Keeping> = emptyList(),
    /** Completions whose free time has been given, so each is given once (`Focus.onCompleted`); kept a fortnight. */
    val rewarded: List<Rewarded> = emptyList(),
    /** The timer on a piece (D-112), running or stopped by hand; none once its piece is done. Not backed up: a running timer is this phone's moment. */
    val timer: PieceTimer? = null,
    /** What photo checks of each task saw ([CheckNote], 6.4.0), the newest last: a later check of the task counts it as shown. */
    val notes: List<CheckNote> = emptyList(),
    /**
     * A check's pages kept with their task, its box unticked (6.5.0), by their files' hashes: sent again with a later check of it
     * where its pages leave room, and let go when it's done or gone, or [KeptPage.KEPT_MS] after they were taken. Not backed up, as
     * the files aren't.
     */
    val pages: List<KeptPage> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 5,
)

/** A check's page kept with its task (6.5.0): its file, by its hash ([com.thomaswcode.decrastination.block.Photos.hashOf]), and when it was taken. */
@Serializable
data class KeptPage(val taskId: String, val hash: String, val at: Long) {
    companion object {
        /** How long a kept page lasts at most: a fortnight, as a kept chat photo does. */
        const val KEPT_MS = 14 * 24 * 3_600_000L
    }
}

/**
 * A slot kept already, as a photo check from its sheet names it (6.5.0): its key, its start and when it was kept, so the check
 * earns on that slot and no other, however long after its tick, where a key alone can't tell one kept slot of a task from another.
 */
@Serializable
data class SlotRef(val key: String, val start: Long, val at: Long) {
    /** Whether [record] is the slot this names. */
    fun names(record: SlotRecord): Boolean = record.key == key && record.start == start && record.at == at

    companion object {
        fun of(record: SlotRecord): SlotRef = SlotRef(record.key, record.start, record.at)
    }
}

/**
 * What a photo check saw of a task's work (6.4.0): kept after a check that said done or not yet, as its photos are deleted, so a
 * later check of any of the task's pieces counts it as shown. [CHECK_NOTES_KEPT] a task, the newest; a task's go when it's done or
 * gone ([com.thomaswcode.decrastination.block.Focus.noteCheck]).
 */
@Serializable
data class CheckNote(
    val taskId: String,
    /** The piece it was for ([com.thomaswcode.decrastination.core.Chunk.key]) and its label, as they were. */
    val key: String,
    val label: String,
    /** Exactly which parts of the work its photos showed done, as the check wrote it to be relied on later. */
    val covered: String,
    /**
     * Whether it's counted: its check passed, or a later one that passed was told it and relied on it, so what it showed has earned
     * once already and can't be shown again as new.
     */
    val passed: Boolean,
    val at: Long,
) {
    /**
     * What a pass of its check counts with it: its step, by its place in the task's list, whichever sitting of it it was; or "-1",
     * every part of a task divided with no step named ([com.thomaswcode.decrastination.core.Chunk.keyOf]).
     */
    val scope: String get() = key.removePrefix("$taskId#").substringBefore('#')

    /** The note it is, whatever's been counted since: its task, its piece and when it was kept. */
    val id: Triple<String, String, Long> get() = Triple(taskId, key, at)
}

/** The most notes kept of one task's checks, the newest. */
const val CHECK_NOTES_KEPT = 8

/** A timer on a piece (D-112): the owner's word they're on it, running from [startedAt] for [minutes]; stopped by hand, kept with [stoppedAt] till the next Start, so Felix's slot-over message waits its 30 minutes from the stop. */
@Serializable
data class PieceTimer(
    /** The piece it was started on ([com.thomaswcode.decrastination.core.Chunk.key]). */
    val key: String,
    val taskId: String,
    /** Its step's place in its task's list; null for a task with no steps, or a piece of it in sittings. */
    val stepIndex: Int?,
    /** The step's title as it was, so a step changed under it ends it. */
    val step: String?,
    /** "Task: step", as the notification and the card say it. */
    val label: String,
    val minutes: Int,
    val startedAt: Long,
    /** Its zero has been told (the "time's up" notification posted, the running one retitled). */
    val told: Boolean = false,
    val stoppedAt: Long? = null,
) {
    val endsAt: Long get() = startedAt + minutes * 60_000L
    val running: Boolean get() = stoppedAt == null

    /** Its minutes left at [now], rounded up: none from its zero on. */
    fun leftMin(now: Long): Int = ((endsAt - now + 59_999L) / 60_000L).toInt().coerceAtLeast(0)

    /** The whole minutes it has run past its zero by [now]: none before it. */
    fun overMin(now: Long): Int = ((now - endsAt) / 60_000L).toInt().coerceAtLeast(0)
}

/** A completion that has had its free time: the task, and when it was confirmed done. */
@Serializable
data class Rewarded(val taskId: String, val doneAt: Long)

/** A piece a photo check found done: what it's owed, saved before any of it is given (`Focus.photoChecked`). */
@Serializable
data class PhotoDone(
    val id: String,
    val taskId: String,
    val step: String?,
    val minutes: Int,
    val at: Long,
    /** Its step's place in its task's list; null from before this was kept. */
    val stepIndex: Int? = null,
    /** The piece it was for ([com.thomaswcode.decrastination.core.Chunk.key], LR-09): its slot is kept by it. */
    val key: String? = null,
    /** When the photo was taken (the check began): a slot of its key kept before then is another's. */
    val takenAt: Long? = null,
    /** The kept slot it was taken for, from that slot's sheet (6.5.0): it earns on that slot, or not at all. */
    val record: SlotRef? = null,
)

/**
 * A slot being kept (LR-09), saved before anything changes, so a stop part-way is carried through at
 * start-up (`Focus.finishKeeps`): its [record]; its task's step to tick ([stepIndex], still [step]),
 * none for a box of time or a sitting before its step's last, and the minutes worked on the task
 * before ([workedBefore]), so the task's change
 * is made once; the free time it earns; the miss of it today it [replaces]; and the [photo] that
 * kept it, let go with it. [earnOnly]: a slot kept already, earning now (its photo passed): the
 * record it [replaces] marked, nothing done to the task.
 */
@Serializable
data class Keeping(
    val record: SlotRecord,
    val stepIndex: Int?,
    val step: String?,
    val workedBefore: Int,
    val earningMin: Double,
    val replaces: SlotRecord? = null,
    val photo: PhotoDone? = null,
    val earnOnly: Boolean = false,
)
