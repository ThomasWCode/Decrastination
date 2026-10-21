package com.thomaswcode.decrastination.core

import kotlin.math.roundToInt

/**
 * When finishing a starred email's work here unstars and archives it in Gmail (docs/plan_v4/10-emails-done.md,
 * D-7 as amended): a slot kept or a step ticked finishes it only once its work is all done ([due]); Felix's
 * `done` and the sheet's *Finish* need only an open email ([finishable]), as you've said it's finished. Pure.
 */
object GmailFinish {
    /** Whether [task] can be finished here at all: an open Gmail task. */
    fun finishable(task: TaskItem): Boolean = task.source == Source.Gmail && task.isOpen

    /**
     * Whether [task]'s work is all done, so that the keep or the tick that made it so finishes it: an open
     * Gmail task, not hidden, with every step ticked, or, with none, nothing left of its minutes at the pace
     * learned ([multiplier]) as the plan rounds them ([Planner.remaining]), so its last box kept is the end of it.
     */
    fun due(task: TaskItem, multiplier: Double): Boolean = finishable(task) && !task.hidden && workDone(task, multiplier)

    /** Whether [task]'s work is all done, open or not: every step ticked, or, with none, nothing left of its minutes at the pace learned. */
    fun workDone(task: TaskItem, multiplier: Double): Boolean =
        if (task.subSteps.isNotEmpty()) task.subSteps.all { it.done } else Planner.remaining(task, multiplier).roundToInt() <= 0
}
