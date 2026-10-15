package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Sunday check-in as Felix asks it (LR-02): the form's five questions, one a message, each reply
 * its answer; the first's replies 1 to 5, the second's what the week shows was put off. Pure.
 */
object CheckInFlow {
    val QUESTIONS = listOf(
        "How was the week, 1 to 5?",
        "What did you avoid, and why?",
        "What got in the way?",
        "What would you change?",
        "When did you have most energy?",
    )

    /** The week's answers so far, and the question asked last ([step], from 0). */
    @Serializable
    data class Draft(val week: String, val step: Int = 0, val feel: Int = 0, val avoided: String = "", val inTheWay: String = "", val change: String = "", val energy: String = "")

    /** The most of the week's put-off work offered as replies to the second question. */
    private const val MAX_CHIPS = 3

    /** [draft]'s question to ask now. */
    fun question(draft: Draft): String = QUESTIONS[draft.step]

    /**
     * The replies offered to [draft]'s question: 1 to 5 for the first; for the second, what the week
     * shows was put off ([avoided]); none for the rest, said in words.
     */
    fun replies(draft: Draft, log: ActivityLog, zone: ZoneId): List<String> = when (draft.step) {
        0 -> (1..5).map(Int::toString)
        1 -> avoided(log, LocalDate.parse(draft.week), zone)
        else -> emptyList()
    }

    /** What the week from [monday] shows was put off: its slots missed, its pieces moved, its days not done, a few. */
    fun avoided(log: ActivityLog, monday: LocalDate, zone: ZoneId): List<String> {
        val days = (0L..6L).map { monday.plusDays(it).toString() }.toSet()
        val slots = log.slots.filter { it.date in days }
        fun day(date: String) = LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        val missed = slots.filter { it.outcome == SlotOutcome.Missed }.groupBy { it.taskId }.map { (_, s) -> "${s.first().label.substringBefore(':').take(40)}, missed" }
        val moved = slots.filter { it.outcome == SlotOutcome.Moved }.groupBy { it.taskId }.map { (_, s) -> "${s.first().label.substringBefore(':').take(40)}, moved" + if (s.size > 1) " ${s.size} times" else "" }
        val notDone = log.days.filter { it.date in days && it.full == false }.map { "${day(it.date)}'s plan" }
        return (missed + moved + notDone).distinct().take(MAX_CHIPS)
    }

    /**
     * [draft] with [text] as its question's answer, the next one to ask: null where it can't be (the
     * first wants a number from 1 to 5).
     */
    fun answered(draft: Draft, text: String): Draft? {
        val words = text.trim()
        return when (draft.step) {
            0 -> Regex("""[1-5]""").find(words)?.value?.toInt()?.let { draft.copy(step = 1, feel = it) }
            1 -> draft.copy(step = 2, avoided = words)
            2 -> draft.copy(step = 3, inTheWay = words)
            3 -> draft.copy(step = 4, change = words)
            4 -> draft.copy(step = 5, energy = words)
            else -> null
        }
    }

    /** Whether all five have their answers. */
    fun done(draft: Draft): Boolean = draft.step >= QUESTIONS.size

    /** [draft], all answered, as the week's check-in made at [at]. */
    fun checkIn(draft: Draft, at: Long): CheckIn = CheckIn(draft.week, at, draft.feel, draft.avoided, draft.inTheWay, draft.change, draft.energy)
}
