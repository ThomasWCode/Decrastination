package com.thomaswcode.decrastination.learn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify

/**
 * The weekly review (docs/scheduler.md §5, item 7): on Sunday evening, after the check-in (or
 * without it), the calibration is learned afresh from the log, the capacity checked, and, once
 * Claude is on, the week goes to the model for a note and bounded changes, which go through the
 * same pending-change path as yours. Until then the note is the rules': what changed, in words.
 */
object Review {
    private const val ID = 4003
    private const val DAY_MS = 24 * 3_600_000L
    private const val WEEK_MS = 7 * DAY_MS

    suspend fun run(context: Context, ifDue: Boolean) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        val last = graph.log.value.reviews.maxOfOrNull { it.at }
        // Once a week: the check-in runs it at once, and the evening's alarm finds it done.
        if (ifDue && last != null && now - last < 3 * DAY_MS) return
        val learned = Calibrator.learn(graph.log.value, graph.runtime.value.calibration, graph.settings.value.boxMin, week = now / WEEK_MS)
        graph.runtime.update { it.copy(calibration = learned.calibration) }
        val rules = (learned.changes + listOfNotNull(Days.capacityAdvice(graph.log.value.days)))
            .ifEmpty { listOf("Nothing to change this week: the estimates held.") }
        val model = runCatching { modelReview(graph, now) }
            .onFailure { Log.w(AppGraph.TAG, "The model's weekly review failed; the rules' stands", it) }
            .getOrNull()
        val review = model ?: WeeklyReview(now, rules, by = "rules")
        graph.log.update { it.copy(reviews = it.reviews + review).trimmed(now) }
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notify.post(
            context,
            ID,
            NotificationCompat.Builder(context, Channels.DAILY)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("This week's review")
                .setContentText(review.lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(review.lines.joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * The model's review, while Claude is on and under its cap: a note and bounded changes, the
     * changes applied through [AppGraph.changeSettings]. Null otherwise.
     */
    private suspend fun modelReview(graph: AppGraph, now: Long): WeeklyReview? {
        val reviewer = graph.modelReviewer() ?: return null
        val zone = graph.clock.zone()
        val month = AiUsage.monthOf(now, zone)
        val settings = graph.settings.value
        if (!graph.runtime.value.aiUsage.forMonth(month).allows(settings.aiMonthlyCapGbp, settings.usdToGbp)) return null
        val input = ReviewInput.describe(graph.log.value, graph.runtime.value.calibration, settings, now, zone)
        val result = reviewer.review(input)
        graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(result.costUsd, result.refused, now)) }
        val answer = result.answer ?: return null
        val proposed = ReviewInput.apply(settings, answer.changes)
        if (proposed != settings) graph.changeSettings(proposed)
        return WeeklyReview(now, answer.note.ifEmpty { listOf("No note this week.") } + answer.changes.map { "Changed: ${it.setting} to ${it.value} (${it.why})" }, by = reviewer.model)
    }
}
