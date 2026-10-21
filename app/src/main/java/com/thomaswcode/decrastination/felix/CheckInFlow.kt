package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.CheckInDraft
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.learn.CheckIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Sunday check-in as Felix asks it (LR-02): the form's five questions, one a message, each reply
 * its answer; the first's replies 1 to 5, the second's what the week shows was put off. Pure.
 */
object CheckInFlow {
    /** The most of the week's put-off work offered as replies to the second question. */
    private const val MAX_CHIPS = 3

    /** [draft]'s question to ask now. */
    fun question(draft: CheckInDraft): String = FelixWords.CHECK_IN[draft.step]

    /**
     * The replies offered to [draft]'s question: 1 to 5 for the first; for the second, what the week
     * shows was put off ([avoided]); none for the rest, said in words.
     */
    fun replies(draft: CheckInDraft, log: ActivityLog, zone: ZoneId): List<String> = when (draft.step) {
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
     * first wants a number from 1 to 5, and nothing else).
     */
    fun answered(draft: CheckInDraft, text: String): CheckInDraft? {
        val words = text.trim()
        return when (draft.step) {
            // "4", "4/5", "4 out of 5": the number's what's kept.
            0 -> FEEL.matchEntire(words.lowercase())?.groupValues?.get(1)?.toInt()?.let { draft.copy(step = 1, feel = it) }
            1 -> draft.copy(step = 2, avoided = words)
            2 -> draft.copy(step = 3, inTheWay = words)
            3 -> draft.copy(step = 4, change = words)
            4 -> draft.copy(step = 5, energy = words)
            else -> null
        }
    }

    /** The first question's answer: a number from 1 to 5, alone or out of 5 ("4", "4/5", "4 out of 5"). */
    private val FEEL = Regex("""([1-5])\s*(?:/\s*5|out of 5)?""")

    /** Whether all five have their answers. */
    fun done(draft: CheckInDraft): Boolean = draft.step >= FelixWords.CHECK_IN.size

    /** [draft], all answered, as the week's check-in made at [at]. */
    fun checkIn(draft: CheckInDraft, at: Long): CheckIn = CheckIn(draft.week, at, draft.feel, draft.avoided, draft.inTheWay, draft.change, draft.energy)
}
