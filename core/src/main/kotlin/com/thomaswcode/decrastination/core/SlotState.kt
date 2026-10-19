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

    /** [key]'s slot missed at [at]: recorded once, and its start no longer kept, so it moves. */
    fun missed(key: String, taskId: String, at: Long): SlotState =
        copy(previous = previous - key, missed = if (missed.any { it.key == key }) missed else missed + MissedSlot(key, taskId, at))

    /** [key]'s piece kept to its slot after all (phase 4): off both. */
    fun kept(key: String): SlotState = copy(previous = previous - key, missed = missed.filterNot { it.key == key })

    /** [taskId] moved on purpose (a time, a day or a start you gave): its missed slots forgotten. */
    fun moved(taskId: String): SlotState = copy(missed = missed.filterNot { it.taskId == taskId })

    /** As [plan] leaves today: a missed piece in none of today's slots went to another day ([MissedSlot.spilled]). */
    fun reconciled(plan: Plan): SlotState {
        val slotted = plan.todaySlots.mapNotNull { it.chunk?.key }.toSet()
        return copy(missed = missed.map { if (!it.spilled && it.key !in slotted) it.copy(spilled = true) else it })
    }
}

/** A slot whose end and grace passed with its piece undone (PL-13): when, and whether the piece then went to another day. */
@Serializable
data class MissedSlot(val key: String, val taskId: String, val at: Long, val spilled: Boolean = false)
