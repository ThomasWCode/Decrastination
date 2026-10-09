package com.thomaswcode.decrastination.learn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.Notify
import kotlinx.coroutines.sync.withLock

/**
 * The weekly review (docs/scheduler.md §5, item 7): on Sunday evening, after the check-in (or
 * without it), the calibration is learned afresh from the log, the capacity checked, and, once
 * Claude is on, the week goes to the model for a note and bounded changes, which go through the
 * same pending-change path as yours. Until then the note is the rules': what changed, in words.
 */
object Review {
    private const val ID = 4003
    private const val WEEK_MS = 7 * 24 * 3_600_000L

    suspend fun run(context: Context, ifDue: Boolean) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        // Once a week: the check-in runs it at once, and the evening's alarm finds it done since
        // Sunday's check-in time (a review at any other time doesn't count). The latest Sunday at or
        // before now, so an alarm after midnight still finds Sunday's.
        if (ifDue && graph.log.value.reviews.any { it.at >= Daily.lastCheckIn(now, graph.clock.zone(), graph.settings.value.checkInMin) }) return
        val learned = Calibrator.learn(graph.log.value, graph.runtime.value.calibration, graph.settings.value.boxMin, week = now / WEEK_MS)
        graph.runtime.update { it.copy(calibration = learned.calibration) }
        val findings = learned.changes + listOfNotNull(Days.capacityAdvice(graph.log.value.days, graph.focus.today(now)))
        val model = runCatching { modelReview(graph, now) }
            .onFailure { Log.w(AppGraph.TAG, "The model's weekly review failed; the rules' stands", it) }
            .getOrNull()
        // The rules' findings always stand: the model's note comes first, and they follow.
        val review = model?.let { it.copy(lines = it.lines + findings) }
            ?: WeeklyReview(now, findings.ifEmpty { listOf("Nothing to change this week: the estimates held.") }, by = "rules")
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
        val input = ReviewInput.describe(graph.log.value, graph.runtime.value.calibration, graph.settings.value, now, zone)
        // The cap checked and the cost recorded in one turn with every other model call.
        val result = graph.modelCalls.withLock {
            // Checked again here: switched off while another call held the lock, nothing is sent.
            val current = graph.modelReviewer() ?: return@withLock null
            val settings = graph.settings.value
            if (!graph.runtime.value.aiUsage.forMonth(month).allows(settings.aiMonthlyCapGbp, settings.usdToGbp)) return@withLock null
            current.review(input).also { r -> graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, now)) } }
        } ?: return null
        val answer = result.answer ?: return null
        val changes = answer.changes.filter(ReviewInput::allowed)
        // Laid over what's been asked for, so a change waiting elsewhere keeps its wait.
        if (changes.isNotEmpty()) graph.changeSettings { ReviewInput.apply(it, changes) }
        val note = answer.note.ifEmpty { listOf("No note this week.") }
        return WeeklyReview(now, note + changeLines(changes, graph.settings.value), by = reviewer.model)
    }

    /**
     * The model's changes in words, as they stand after [after]: in place, or waiting (once armed,
     * one that loosens blocking waits like yours).
     */
    fun changeLines(changes: List<ReviewInput.Change>, after: Settings): List<String> = changes.map { change ->
        val what = "${change.setting} to ${change.value} (${change.why})"
        if (ReviewInput.valueOf(after, change.setting) == change.value) "Changed: $what" else "Waiting ${after.loosenDelayHours} hours, as it loosens blocking: $what"
    }
}
