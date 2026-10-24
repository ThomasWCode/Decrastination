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
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 2,
)

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
