package com.thomaswcode.decrastination.learn

import java.time.LocalDate
import android.content.Context
import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.ClaudeReviewer
import com.thomaswcode.decrastination.enrich.MemoryTidier
import com.thomaswcode.decrastination.enrich.MemoryTidy
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelHold

/**
 * The weekly review (docs/spec/learning.md, item 7): on Sunday evening, after the check-in (or
 * without it), the calibration is learned afresh from the log, the capacity checked, and, once
 * Claude is on, the week goes to the model for a note and bounded changes, which apply at once,
 * armed or not (your call, 11 Oct). Until then the note is the rules': what changed, in words.
 * Then what's remembered is tidied, once it's past 70 % of its room (AI-21).
 */
object Review {
    // [Learning]'s, till its callers call it there.
    suspend fun run(context: Context, ifDue: Boolean): Unit = AppGraph.get(context).learning.review(ifDue)

    // [Learning]'s, till its callers call it there.
    suspend fun tidyMemory(graph: AppGraph, now: Long, force: Boolean = false): MemoryTidyRecord? = graph.learning.tidyMemory(now, force)

    /**
     * Whether [week] has had its review: one marked with it, or (before reviews were marked) one
     * since [lastCheckIn]. By the week, so moving the check-in later doesn't bring a second.
     */
    fun reviewed(reviews: List<WeeklyReview>, week: String, lastCheckIn: Long): Boolean =
        reviews.any { it.week == week || (it.week == null && it.at >= lastCheckIn) }

    /** The tidy in the review's words. */
    fun tidyLine(tidy: MemoryTidyRecord): String = "Felix tidied what he remembers: ${tidy.before.size} facts into ${tidy.after.size}."

    /**
     * The model's changes in words, as they stand after [after]: in place, or waiting (once armed,
     * one that loosens blocking waits like yours).
     */
    fun changeLines(changes: List<ReviewInput.Change>, after: Settings): List<String> = changes.map { change ->
        val what = "${change.setting} to ${change.value} (${change.why})"
        if (ReviewInput.valueOf(after, change.setting) == change.value) "Changed: $what" else "Waiting ${after.loosenDelayHours} hours, as it loosens blocking: $what"
    }
}
