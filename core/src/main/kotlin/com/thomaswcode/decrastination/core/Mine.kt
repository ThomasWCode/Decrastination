package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Who set a value of yours on a task: you (the instructions screen, a sheet's quick change) or Felix
 * (a card of his in the chat). It only labels: the app treats yours and his alike (D-4).
 */
@Serializable
enum class By { You, Felix }

/** One value of yours, with who set it. (Not `Set`, which is Kotlin's own.) */
@Serializable
data class SetBy<T>(val value: T, val by: By)

/** The whole task's minutes as you or Felix gave them, and, where Felix worked them out at your pace, from what ([paced]). */
@Serializable
data class SetMinutes(val minutes: Int, val by: By, val paced: Paced? = null)

/**
 * [SetMinutes] are the app's own estimate at your pace, as Felix worked them out from [from] minutes
 * of the app's, of the task as it was then ([contentHash], [Enrichments.inputHash]): planned as given,
 * but what's finished still teaches the pace while the task is as it was ([TaskItem.pacedTeaches]).
 * No hash where it was paced before the hash was kept (3.1.3): it then teaches nothing.
 */
@Serializable
data class Paced(val from: Int, val contentHash: String? = null)

/** Its steps as you or Felix gave them, in place of the source's and the reading's. */
@Serializable
data class SetSteps(val steps: List<OwnStep>, val by: By)

/**
 * Your instructions' say about a task, each value with who set it (D-4: the app's limits and its pace
 * never apply to these): not a task; not before then; not until another task is done; due by then;
 * its minutes; all of it on one day (YYYY-MM-DD); its next piece at a time (PL-04); the same work as
 * another, which is kept (DX-15); its steps; its name. Laid on the task from the instructions in use
 * ([InstructionRules.taskOverrides], [withMine]) and kept there, so a sync keeps it and every screen
 * sees it.
 */
@Serializable
data class Mine(
    val notATask: SetBy<Boolean>? = null,
    val from: SetBy<Long>? = null,
    val after: SetBy<String>? = null,
    val dueAt: SetBy<Long>? = null,
    val minutes: SetMinutes? = null,
    val onDay: SetBy<String>? = null,
    val atTime: SetBy<Long>? = null,
    val sameAs: SetBy<String>? = null,
    val steps: SetSteps? = null,
    val title: SetBy<String>? = null,
) {
    companion object {
        /** Nothing of yours. */
        val NONE = Mine()
    }
}
