package com.thomaswcode.decrastination.learn

import android.content.Context
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.ActivityLog

/**
 * The quick self-assessment after each verified completion (docs/spec/learning.md, item 5): harder,
 * as expected or easier. It feeds the effort multipliers and the weekly review. Since review v2
 * (LR-05) it's Felix's question, once an evening, about the day's work done outside the app's
 * knowledge; with Claude off, a message of his in the app's words for each completion, its three
 * answers his chips.
 */
object Assessment {
    /** The three answers, as said and as kept. */
    val ANSWERS = listOf("Harder" to Calibrator.HARDER, "As expected" to Calibrator.AS_EXPECTED, "Easier" to Calibrator.EASIER)

    /**
     * Asked about homework and revision only: an archived email or a passed event isn't work to
     * judge. Kept for his evening question while Claude is on; asked at once in the chat with it off.
     */
    suspend fun ask(context: Context, completed: List<TaskItem>) {
        // Asked again after a stop: one answered meanwhile isn't asked again.
        val graph = AppGraph.get(context)
        val answered = graph.log.value.completions.filter { it.assessment != null }
        val asks = completed.filter { it.kind == Kind.Homework || it.kind == Kind.Revision }
            .filterNot { t -> answered.any { it.taskId == t.id && it.doneAt == t.doneAt } }
            .map { AssessLater(it.id, it.title, it.doneAt) }
        graph.conversation.askHowItWent(asks)
    }

    /** [log] with [answer] on the completion it's about: the one done at [doneAt], or (0) the latest of [taskId]'s. */
    fun answered(log: ActivityLog, taskId: String, doneAt: Long, answer: String, note: String?): ActivityLog {
        val i = if (doneAt != 0L) log.completions.indexOfFirst { it.taskId == taskId && it.doneAt == doneAt } else log.completions.indexOfLast { it.taskId == taskId }
        if (i < 0) return log
        return log.copy(completions = log.completions.toMutableList().also { it[i] = it[i].copy(assessment = answer, note = note?.trim()?.takeIf(String::isNotEmpty) ?: it[i].note) })
    }
}
