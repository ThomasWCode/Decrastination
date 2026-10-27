package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Today's slots as the app keeps them between plans (PL-13): each piece's start in the last plan
 * ([previous], by [Chunk.key]), kept where it's still free so a replan doesn't shuffle the day; and
 * the slots whose time passed with their piece undone ([missed]). A new day starts empty. Pure.
 */
@Serializable
data class SlotState(val date: String = "", val previous: Map<String, Long> = emptyMap(), val missed: List<MissedSlot> = emptyList()) {
    /** As it stands on [date]: a new day starts empty. */
    fun on(date: LocalDate): SlotState = if (this.date == date.toString()) this else SlotState(date.toString())

    /**
     * As [plan] lays today: its slots' starts, breaks outside among them, with a begun break's end as laid ([Slot.endKey]),
     * so a length changed after it began doesn't move its end; and each break outside begun that it no longer lays,
     * completed, with its end (or [Plan.now], by which it was over, where no plan laid it under way), so the next plan knows
     * it's done and never lays it again that day (D-113).
     */
    fun laid(plan: Plan): SlotState {
        val slots = plan.todaySlots
        val starts = slots.associate { it.key to it.start } +
            slots.filter { it.kind == SlotKind.Outside && it.start <= plan.now }.associate { Slot.endKey(it.key) to it.end }
        val completed = previous.filter { (key, start) -> Slot.isOutside(key) && start <= plan.now && key !in starts }
        val ends = completed.keys.associate { Slot.endKey(it) to (previous[Slot.endKey(it)] ?: plan.now) }
        return copy(previous = starts + completed + ends)
    }

    /**
     * Today's pieces whose start is more than [graceMs] before [now], not missed: those a sweep looks at (PL-13). A break
     * outside's start and end, kept beside them (D-113), aside: a break has nothing to miss.
     */
    fun started(now: Long, graceMs: Long): Map<String, Long> {
        val gone = missed.map { it.key }.toSet()
        return previous.filter { (key, start) -> key !in gone && !Slot.isOutside(key) && !key.endsWith(Slot.endKey("")) && start + graceMs < now }
    }

    /** [key]'s slot missed at [at]: recorded once, and its start no longer kept, so it moves. */
    fun missed(key: String, taskId: String, at: Long): SlotState =
        copy(previous = previous - key, missed = if (missed.any { it.key == key }) missed else missed + MissedSlot(key, taskId, at))

    /** [key]'s piece kept to its slot after all: off both. */
    fun kept(key: String): SlotState = copy(previous = previous - key, missed = missed.filterNot { it.key == key })

    /** [taskId] moved on purpose (a time, a day or a start you gave): its missed slots forgotten. */
    fun moved(taskId: String): SlotState = copy(missed = missed.filterNot { it.taskId == taskId })

    /**
     * [taskId]'s starts kept from the last plan let go of where they're still to come at [now]: its new steps laid afresh
     * (D-110), one begun left where it is, and the steps at the places in [keep], as they were ([unchanged]), kept too.
     */
    fun released(taskId: String, now: Long, keep: Set<Int> = emptySet()): SlotState = copy(previous = previous.filter { (key, start) ->
        start <= now || !key.startsWith("$taskId#") || key.removePrefix("$taskId#").substringBefore('#').toIntOrNull() in keep
    })

    companion object {
        /**
         * The places where a Steps change's [steps] leave [task]'s as they were: the same title, the same minutes as planned
         * before the change and after it ([before], [after]: a total you gave shares them out), and the same step, by its id
         * or with one given afresh as it's stored (a change Felix or a reading of your words writes comes without its steps'
         * ids), not another step's: a start kept for it still holds it (D-110).
         */
        fun unchanged(task: TaskItem, steps: List<OwnStep>, before: List<Int>, after: List<Int>): Set<Int> {
            val ids = task.subSteps.map { it.id }.filter { it.isNotEmpty() }.toSet()
            return steps.indices.filter { i ->
                val was = task.subSteps.getOrNull(i)
                was != null && was.title == steps[i].title && before.getOrNull(i) != null && before.getOrNull(i) == after.getOrNull(i) &&
                    (was.id == steps[i].id || steps[i].id !in ids)
            }.toSet()
        }
    }

    /**
     * As [plan] leaves today: a missed piece in none of today's slots, its task still in the plan, went to
     * another day ([MissedSlot.spilled]). One whose task the plan no longer holds (finished since, or
     * deleted) went to no day, so it isn't taken as gone to one, nor kept so once its task has finished:
     * the work it was for is done (BL-11).
     */
    fun reconciled(plan: Plan): SlotState {
        val slotted = plan.todaySlots.mapNotNull { it.chunk?.key }.toSet()
        val held = plan.ordered.map { it.taskId }.toSet() + plan.unplanned.map { it.taskId } + plan.wontFit.map { it.taskId }
        return copy(
            missed = missed.map {
                when {
                    it.taskId !in held -> if (it.spilled) it.copy(spilled = false) else it
                    !it.spilled && it.key !in slotted -> it.copy(spilled = true)
                    else -> it
                }
            },
        )
    }
}

/** A slot whose end and grace passed with its piece undone (PL-13): when, and whether the piece then went to another day. */
@Serializable
data class MissedSlot(val key: String, val taskId: String, val at: Long, val spilled: Boolean = false)
