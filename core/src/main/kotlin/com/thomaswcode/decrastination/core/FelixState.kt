package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Felix between looks (LR-03): when he said he'd check again, when he last wrote, a message he wrote
 * ahead (AC-10), and when something was last seen done on each task (opened, studied in AnkiDroid).
 * The conversation's ([com.thomaswcode.decrastination.core.ChatState.felix]), in `chat.json`.
 */
@Serializable
data class FelixState(
    val nextCheckAt: Long? = null,
    val lastSentAt: Long? = null,
    val draft: FelixDraft? = null,
    val activity: Map<String, Long> = emptyMap(),
    /** The model's questions about tasks waiting to be put (AI-10). */
    val questions: List<FelixQuestion> = emptyList(),
    /** A message whose call didn't come back, to try again (LR-03). */
    val retry: FelixRetry? = null,
    /** Completions waiting to be asked how they went, in his evening question (LR-05). */
    val assessLater: List<AssessLater> = emptyList(),
    /** The week's check-in as he asks it (LR-02), its answers so far: null when none is being asked. */
    val checkInDraft: CheckInDraft? = null,
) {
    /** [taskIds] seen done on [at]: the last day's kept. */
    fun withActivity(taskIds: Collection<String>, at: Long): FelixState =
        copy(activity = (activity + taskIds.associateWith { at }).filterValues { it > at - 24 * 3_600_000L })

    /** [question] waiting about [taskId] (AI-10) in place of the one there was, where it stood (as it was, if the same); null, none. */
    fun withQuestion(taskId: String, question: String?, at: Long): FelixState {
        val was = questions.firstOrNull { it.taskId == taskId }
        val next = question?.let { q -> was?.takeIf { it.question == q } ?: FelixQuestion(taskId, q, at) }
        return copy(questions = if (was == null) questions + listOfNotNull(next) else questions.flatMap { if (it.taskId == taskId) listOfNotNull(next) else listOf(it) }.distinct())
    }
}

/** A message whose call didn't come back (held, failed): its reason and piece, tried again from [notBefore] (LR-03). */
@Serializable
data class FelixRetry(val reason: String, val key: String?, val notBefore: Long)

/** A question of the model's about [taskId]'s reading (AI-10), from [at]. */
@Serializable
data class FelixQuestion(val taskId: String, val question: String, val at: Long)

/**
 * A message of Felix's written ahead (AC-10): sent if it still fits its reason and piece when the time comes. [mood]: the
 * level he was told as it was written (D-119), kept on the message as sent (Q103); null in a draft from before it was kept.
 */
@Serializable
data class FelixDraft(val reason: String, val key: String?, val answer: FelixAnswer, val at: Long, val mood: String? = null)

/** A finished task's "how was it?" (LR-05): what he asks about, and when it was finished. */
@Serializable
data class AssessLater(val taskId: String, val title: String, val doneAt: Long? = null)

/** The week's answers so far, and the question asked last ([step], from 0). */
@Serializable
data class CheckInDraft(val week: String, val step: Int = 0, val feel: Int = 0, val avoided: String = "", val inTheWay: String = "", val change: String = "", val energy: String = "")
