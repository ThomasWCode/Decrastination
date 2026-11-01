package com.thomaswcode.decrastination.ui

import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.WeeklyReview
import com.thomaswcode.decrastination.learn.CheckIn
import com.thomaswcode.decrastination.learn.ReviewInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The check-in's form and the reviews (LR-01, LR-02), the same on the phone and on the website: their words and what can be undone. A screen draws them. */
object ReviewPage {
    const val CHECK_IN_DONE = "This week's check-in is done."
    const val FEEL = "How did the week feel?"
    const val FEEL_NOTE = "1 is badly, 5 is well."
    const val AVOIDED = "What did you avoid, and why?"
    const val IN_THE_WAY = "What got in the way?"
    const val CHANGE = "What would you change about the plan?"
    const val ENERGY = "When in the day did you have most energy?"
    const val SAVE = "Save, and review the week"
    const val NONE_YET = "No review yet: the first comes on Sunday evening, after the week's check-in."

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** A setting a review changed: what's said of it, and whether it can be put back: it's what the review left, and what it was is known. */
    data class Change(val setting: String, val text: String, val undoable: Boolean)

    /** A review as a card. */
    data class Card(
        val review: WeeklyReview,
        val week: String,
        val by: String,
        val lines: List<String>,
        val changes: List<Change>,
        val tidy: String?,
        val tidyUndoable: Boolean,
        val summary: String?,
        val checkIn: String?,
    )

    /** Who wrote [review]: the rules, with why where that was kept (older reviews didn't keep it), or Claude. */
    fun byLine(review: WeeklyReview): String = if (review.by == "rules") "By the rules" + (review.why?.let { ": $it" } ?: "") else "By Claude"

    /** The reviews in [log], newest first, as they stand by [settings] and [memory]. */
    fun cards(log: ActivityLog, settings: Settings, memory: MemoryState, zone: ZoneId): List<Card> =
        log.reviews.sortedByDescending { it.at }.map { review ->
            val monday = review.week?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: Instant.ofEpochMilli(review.at).atZone(zone).toLocalDate()
            Card(
                review,
                "Week of ${monday.format(DAY)}",
                byLine(review),
                review.lines,
                review.changes.mapIndexed { index, change ->
                    // Only a setting's last change stands, so only that one can be put back.
                    val stands = index == review.changes.indexOfLast { it.setting == change.setting }
                    Change(change.setting, ReviewInput.changeText(change, review.settingsBefore[change.setting]), stands && review.settingsBefore[change.setting] != null && ReviewInput.valueOf(settings, change.setting) == change.value)
                },
                review.memoryTidy?.let { "What's remembered tidied: ${it.before.size} facts into ${it.after.size}" },
                review.memoryTidy?.let { memory.facts == it.after } == true,
                review.summary,
                log.checkIns.filter { it.weekOf == review.week }.maxByOrNull { it.at }?.let(::checkInLine),
            )
        }

    /** A week's [checkIn] in a sentence. */
    fun checkInLine(checkIn: CheckIn): String {
        fun said(text: String) = text.ifBlank { "-" }
        return "The week felt ${checkIn.feel}/5. Avoided: ${said(checkIn.avoided)}. In the way: ${said(checkIn.inTheWay)}. Would change: ${said(checkIn.change)}. Most energy: ${said(checkIn.energy)}."
    }
}
