package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord

/** What a missed slot's sheet offers (docs/spec/day.md §A missed slot's sheet), each by [of]. Pure. */
data class MissedActions(
    /** *Done*: kept, late, in the slot's place. */
    val done: Boolean,
    /** *Done* goes on a later miss of its key today: said in its place, till that one's kept. */
    val doneOnLater: Boolean,
    /** *Check it's done*: its source asked. */
    val check: Boolean,
    /** *All of it done*, for one of your own, in *Check it's done*'s place. */
    val allDone: Boolean,
    /** *Ask Felix about it*. */
    val ask: Boolean,
    /** *Open*: the task in its source. */
    val open: Boolean,
    /** Its task is done since, or its piece has left the plan: said, and only [open] is left. */
    val since: Boolean,
    /** *Check with a photo* (6.5.0), where it offers *Done*: written homework, its piece still in the plan, Claude on where it's drawn. */
    val photo: Boolean = false,
) {
    companion object {
        /**
         * What's offered on missed [record], the plan's [piece] with its key and its [task] as they are now, among
         * [records], the log's. [Focus.keep][com.thomaswcode.decrastination.block.Focus] gives way the last miss of a key
         * today, as the log has them: a task in boxes has one key for every box, so a Done on any other would be kept
         * late in that one's place.
         */
        fun of(record: SlotRecord, records: List<SlotRecord>, piece: Chunk?, task: TaskItem?): MissedActions {
            val live = piece != null && task?.isOpen == true
            val last = records.lastOrNull { it.key == record.key && it.date == record.date && it.outcome == SlotOutcome.Missed } == record
            val own = task?.source == Source.Own
            return MissedActions(
                done = live && last,
                doneOnLater = live && !last,
                check = live && !own,
                allDone = live && own,
                ask = live,
                open = task != null,
                since = !live,
                photo = live && last && piece?.photoCheckable == true,
            )
        }
    }
}

/**
 * What a slot ticked *Done* offers on its sheet (6.5.0), by [of]. Pure. [photo]: *Check with a photo*, while it can still earn: kept
 * today without earning, written homework that isn't Anki's, its task still open, or finished by this very tick (6.10.1: one of yours,
 * its last piece, [ChatActs.finishedBy]); Claude on where it's drawn. The check names this slot, so it earns on it and no other
 * (`PhotoDone.record`).
 */
data class KeptActions(val photo: Boolean, val earned: Boolean) {
    companion object {
        fun of(record: SlotRecord, task: TaskItem?, today: String): KeptActions = KeptActions(
            photo = record.outcome.kept && !record.earned && record.date == today && record.kind == Kind.Homework && task != null &&
                (task.isOpen || ChatActs.finishedBy(task, record.at)) && task.source != Source.Anki,
            earned = record.earned,
        )
    }
}
