package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.MemoryTidyRecord
import com.thomaswcode.decrastination.data.WeeklyReview
import java.time.LocalDate
import java.time.ZoneId

/**
 * The weekly review's pure parts (docs/spec/learning.md, item 7; [Learning.review] runs it): whether a
 * week has had its review, and the tidy of what's remembered and the review's changes in words.
 */
object Review {
    /**
     * Whether [week] has had its review: one marked with it made on its Sunday or after, or (before reviews were
     * marked) one since [lastCheckIn]. By the week, so moving the check-in later doesn't bring a second; one made
     * before its Sunday ("Save, and review the week" midweek) is the week so far, so Sunday's still comes.
     */
    fun reviewed(reviews: List<WeeklyReview>, week: String, lastCheckIn: Long, zone: ZoneId): Boolean {
        val sunday = LocalDate.parse(week).plusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        return reviews.any { (it.week == week && it.at >= sunday) || (it.week == null && it.at >= lastCheckIn) }
    }

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
