package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.plan.Planning
import com.thomaswcode.decrastination.sync.protocol.KeepSlot
import kotlin.math.roundToInt

/** What's said of a piece as it's done (LR-09), the phone's and the website's alike. */
class PieceSays(
    private val focus: Focus,
    private val log: JsonStore<ActivityLog>,
    private val settings: JsonStore<Settings>,
    private val learning: JsonStore<LearningState>,
    private val planning: Planning,
    private val clock: WallClock,
) {
    /**
     * [piece] done, seen at [now], by [keep]: its slot kept, earning its free time if it's short (a longer one's photo earns it,
     * or its source saying it's done, BL-08). What to say of it.
     */
    suspend fun kept(piece: Chunk, now: Long, keep: suspend (KeepSlot) -> Boolean): String {
        val by = focus.earnsBy(piece)
        val since = clock.now()
        val kept = keep(KeepSlot(piece.key, now))
        // What the keep put in the day's ledger, from its record (U-07): a piece of another day's earns nothing today, and
        // today's earns no more than the day's share (BL-08), so what's said is what it earned, not what a piece this long could.
        val record = log.value.slots.lastOrNull { it.key == piece.key && it.outcome.kept && it.at >= since }
        val todays = record?.date == focus.today(clock.now()).toString()
        val earned = record?.earnedMin ?: 0.0
        val free = Format.minutes(Credit.forSlot(piece.minutes, settings.value.workMinPerFreeMin).roundToInt().coerceAtLeast(1))
        val none = "Done. It earns no free time: it isn't on today's plan, or today's free time is all earned."
        val said = when {
            !kept -> return "That piece has changed since (done, or planned again)."
            by == EarnsBy.Tick -> if (earned > 0) "Done: ${Format.minutes(earned.roundToInt().coerceAtLeast(1))} free." else none
            by == EarnsBy.Photo -> if (todays) "Done. A photo check of it earns up to $free free." else none
            by == EarnsBy.Source -> if (todays) "Done. Up to $free free comes once ${piece.source.label} says it's done." else none
            else -> "Done. It earns no free time: a piece this long earns with a photo check, and this isn't written work."
        }
        return listOfNotNull(said, stillPlanned(piece.taskId)).joinToString(" ")
    }

    /**
     * What's said once a Done leaves all of [taskId]'s work done but its source's word: its last few minutes stay planned till
     * the source says it's done (planner.md), so the piece shown again isn't taken for a Done that didn't take. Null for any other.
     */
    fun stillPlanned(taskId: String): String? {
        // As the plan takes it: your instructions' values over it, and linked work credited to it.
        val task = planning.asPlanned().firstOrNull { it.id == taskId && it.isOpen } ?: return null
        if (!worked(task, learning.value.calibration.multiplierFor(task))) return null
        val next = planning.plan().ordered.firstOrNull { it.taskId == taskId } ?: return null
        return "It stays planned, ${Format.minutes(next.minutes)}, until ${task.source.label} says it's done."
    }
}
