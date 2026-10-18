package com.thomaswcode.decrastination.learn

import java.time.LocalDate
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.enrich.MemoryTidier
import com.thomaswcode.decrastination.enrich.MemoryTidy
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.notify.Channels
import com.thomaswcode.decrastination.notify.NoticeId
import com.thomaswcode.decrastination.notify.Notify

/**
 * The weekly review (docs/spec/learning.md, item 7): on Sunday evening, after the check-in (or
 * without it), the calibration is learned afresh from the log, the capacity checked, and, once
 * Claude is on, the week goes to the model for a note and bounded changes, which apply at once,
 * armed or not (your call, 11 Oct). Until then the note is the rules': what changed, in words.
 * Then what's remembered is tidied, once it's past 70 % of its room (AI-21).
 */
object Review {
    suspend fun run(context: Context, ifDue: Boolean) {
        val graph = AppGraph.get(context)
        val now = graph.clock.now()
        // Once a week: the check-in runs it at once, and the evening's alarm finds it done since
        // Sunday's check-in time (a review at any other time doesn't count). The latest Sunday at or
        // before now, so an alarm after midnight still finds Sunday's.
        val zone = graph.clock.zone()
        val week = Daily.checkInWeek(now, zone, graph.settings.value.checkInMin)
        if (ifDue && reviewed(graph.log.value.reviews, week, Daily.lastCheckIn(now, zone, graph.settings.value.checkInMin))) return
        // A reset of the record while this is out (LR-08) lets what it makes go.
        val generation = graph.resets.get()
        val learned = Calibrator.learn(
            graph.log.value,
            graph.runtime.value.calibration,
            today = graph.focus.today(now),
            zone = zone,
            defaultMargin = graph.settings.value.marginDays,
        )
        graph.runtime.update { it.copy(calibration = learned.calibration) }
        val findings = learned.changes + listOfNotNull(Days.capacityAdvice(graph.log.value.days, graph.focus.today(now)))
        val model = runCatching { modelReview(graph, now, week, generation) }
            .onFailure { Log.w(AppGraph.TAG, "The model's weekly review failed; the rules' stands", it) }
            .getOrElse { Outcome(why = "Claude's call failed") }
        val tidy = runCatching { tidyMemory(graph, now) }
            .onFailure { Log.w(AppGraph.TAG, "Tidying what's remembered failed; it's left as it was", it) }
            .getOrNull()
        // The rules' findings always stand: the model's note comes first, and they follow; then the tidy's line.
        val review = (
            model.review?.let { it.copy(lines = it.lines + findings) }
                ?: WeeklyReview(now, findings.ifEmpty { listOf("Nothing to change this week: the estimates held.") }, by = "rules", why = model.why)
            ).let { it.copy(week = week, lines = it.lines + listOfNotNull(tidy?.let(::tidyLine)), memoryTidy = tidy) }
        // The week's totals (PL-10), kept past its days; those of the weeks before it the log still
        // holds whole made again with them, so what came in after their reviews is in them.
        val rollup = Stats.rollup(graph.log.value, LocalDate.parse(week), zone)
        var kept = false
        graph.log.update { l ->
            if (graph.resets.get() != generation) l
            else l.copy(reviews = l.reviews + review, weeks = Stats.refreshedWeeks(l, now, zone).filterNot { w -> w.week == week } + rollup).trimmed(now).also { kept = true }
        }
        if (!kept) {
            Log.i(AppGraph.TAG, "The record was reset while the week's review was out: it isn't kept")
            return
        }
        // The week's summary for home (NF-02), after its review: its own call, its failure the review's no matter.
        runCatching { graph.writeSummary(week) }.onFailure { Log.w(AppGraph.TAG, "The week's summary failed", it) }
        graph.notifier.post(
            NoticeId.Review,
            Notify.builder(context, Channels.WEEKLY)
                .setSmallIcon(R.drawable.ic_focus)
                .setContentTitle("This week's review")
                .setContentText(review.lines.first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(review.lines.joinToString("\n")))
                .let { Notify.opening(it, context, NoticeId.Review.code(), Intent(context, CheckInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Whether [week] has had its review: one marked with it, or (before reviews were marked) one
     * since [lastCheckIn]. By the week, so moving the check-in later doesn't bring a second.
     */
    fun reviewed(reviews: List<WeeklyReview>, week: String, lastCheckIn: Long): Boolean =
        reviews.any { it.week == week || (it.week == null && it.at >= lastCheckIn) }

    /** The model's review, or why there's none (the rules' stands then, saying so). */
    private class Outcome(val review: WeeklyReview? = null, val why: String? = null)

    /**
     * The model's review, while Claude is on and under its cap: a note and bounded changes, the
     * changes applied through [AppGraph.applyReviewChanges]. Otherwise why it couldn't be had.
     */
    private suspend fun modelReview(graph: AppGraph, now: Long, week: String, generation: Int): Outcome {
        val reviewer = graph.modelReviewer() ?: return Outcome(why = because(ModelHold.Off))
        val zone = graph.clock.zone()
        val input = ReviewInput.describe(graph.log.value, graph.runtime.value.calibration, graph.settings.value, now, zone, week)
        // Its room taken with every other model call's, then sent (AC-05). A failure is recorded
        // (Setup shows it; the model rests), then the rules' review stands.
        val current = graph.modelReviewer() ?: return Outcome(why = because(ModelHold.Off))
        val result = when (val called = graph.callModel(interactive = false, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Review, "the week") { r: ClaudeReviewer.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: ClaudeReviewer.Result -> r.costUsd to r.refused }) { current.review(input) }) {
            is Called.Held -> return Outcome(why = because(called.hold))
            is Called.Failed -> throw called.error
            is Called.Answered -> called.value
        }
        val answer = result.answer ?: return Outcome(why = if (result.refused) "Claude declined to review it" else "Claude's answer couldn't be used")
        val settings = graph.settings.value
        val changes = answer.changes.filter { ReviewInput.allowed(it, settings) }
        // What each was, kept with the review (AI-18, LR-01): next week's reads it, and its page undoes it.
        val before = changes.mapNotNull { c -> ReviewInput.valueOf(settings, c.setting)?.let { c.setting to it } }.toMap()
        // Not after a reset of the record that came while Claude was asked (LR-08): the review's let go.
        if (graph.resets.get() != generation) return Outcome(why = "the record was reset while Claude was asked")
        // At once, armed or not (your call, 11 Oct); laid over what's been asked for, so a change
        // waiting for another setting keeps its wait.
        if (changes.isNotEmpty()) graph.applyReviewChanges(changes)
        val note = answer.note.ifEmpty { listOf("No note this week.") }
        return Outcome(WeeklyReview(now, note + changeLines(changes, graph.settings.value), by = result.model ?: reviewer.model, changes = changes, settingsBefore = before))
    }

    /**
     * What's remembered, tidied by the model once its facts are past [MemoryTidy.DUE_SHARE] of their
     * room (AI-21), or when [force]d (a PC's `memory-tidy`): its own call, after the review's. The
     * before and after, or null: not due, the model held or failed (recorded as any call is), an
     * answer that isn't a tidy, or the facts changed while it was out.
     */
    suspend fun tidyMemory(graph: AppGraph, now: Long, force: Boolean = false): MemoryTidyRecord? {
        val before = graph.memory.value.facts
        if (before.isEmpty() || !(force || MemoryTidy.due(before))) return null
        val tidier = graph.memoryTidier() ?: return null
        val called = graph.callModel(interactive = false, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Tidy, "what's remembered") { r: MemoryTidier.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.answer == null) }, cost = { r: MemoryTidier.Result -> r.costUsd to r.refused }) { at -> tidier.tidy(before, at, graph.clock.zone()) }
        val answer = (called as? Called.Answered)?.value?.answer ?: return null
        val after = MemoryTidy.after(answer, before, now) ?: return null
        var applied = false
        graph.memory.update { state -> MemoryTidy.applied(state, before, after)?.also { applied = true } ?: state }
        return if (applied) MemoryTidyRecord(before, after) else null
    }

    /** The tidy in the review's words. */
    fun tidyLine(tidy: MemoryTidyRecord): String = "Felix tidied what he remembers: ${tidy.before.size} facts into ${tidy.after.size}."

    /** Why the model didn't review the week, in words. */
    private fun because(hold: ModelHold): String = when (hold) {
        ModelHold.Off -> "Claude was off"
        ModelHold.Resting -> "Claude was resting after a failed call"
        ModelHold.Capped -> "Claude's monthly cap was reached"
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
