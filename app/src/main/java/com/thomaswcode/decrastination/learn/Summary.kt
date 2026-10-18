package com.thomaswcode.decrastination.learn

import android.content.Intent
import androidx.core.net.toUri
import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.SlotOutcome
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The week's summary for home (NF-02): what the model's told of the week, and the email it goes in.
 * [input] is pure, for the tests.
 */
object Summary {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private const val MAX_TITLE = 80
    private const val MAX_LINE = 300

    /**
     * The reviewed [week] (its Monday) as the summary's written from: the numbers (minutes, pieces,
     * work finished and on time, days done, slots kept and missed, what earned free time), the
     * review's note and changes, Felix's own messages in the chat ([messages]) and how they went, the
     * pieces moved, and the changes asked for that week ([instructions]).
     */
    fun input(log: ActivityLog, instructions: List<Instruction>, week: String, zone: ZoneId, messages: List<ChatMessage> = emptyList()): String = buildString {
        val monday = LocalDate.parse(week)
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        fun inWeek(at: Long) = at in start until end
        val rollup = Stats.rollup(log, monday, zone)
        val slots = log.slots.filter { inWeek(it.start) }
        appendLine("The week of ${monday.format(DAY)}:")
        appendLine("- ${rollup.minutes} minutes of work in ${rollup.slotsKept} pieces kept, ${rollup.slotsMissed} missed, ${slots.count { it.outcome == SlotOutcome.Moved }} moved; ${slots.count { it.earned }} earned free time.")
        appendLine("- Finished: ${rollup.finished}" + (if (rollup.dated > 0) ", ${rollup.onTime} of the ${rollup.dated} with a deadline by it" else "") + ".")
        appendLine("- Days whose plan was all done: ${rollup.fullDays} of ${log.days.count { LocalDate.parse(it.date).let { d -> !d.isBefore(monday) && d.isBefore(monday.plusDays(7)) } }}.")
        log.completions.filter { inWeek(it.doneAt) }.takeIf { it.isNotEmpty() }?.let { done ->
            appendLine("Finished work: " + done.joinToString("; ") { it.title.take(MAX_TITLE) })
        }
        slots.filter { it.outcome == SlotOutcome.Missed }.takeIf { it.isNotEmpty() }?.let { missed ->
            appendLine("Missed: " + missed.joinToString("; ") { "${it.label.take(MAX_TITLE)} (${LocalDate.parse(it.date).format(DAY)})" })
        }
        log.reviews.firstOrNull { it.week == week }?.let { review ->
            appendLine("The review's note: " + review.lines.joinToString(" ") { it.take(MAX_LINE) })
        }
        val felix = ChatHistory.his(messages).filter { inWeek(it.at) && it.text.isNotBlank() }
        if (felix.isNotEmpty()) {
            val kept = felix.filter { it.pieceKey != null }.count { f -> log.slots.any { s -> s.key == f.pieceKey && s.outcome.kept && s.at >= f.at } }
            appendLine("Felix's nudges: ${felix.size}, ${felix.count { ChatHistory.replyOf(it, messages) != null }} answered; ${kept} of those about a piece saw it kept after.")
        }
        instructions.filter { inWeek(it.at) }.takeIf { it.isNotEmpty() }?.let { asked ->
            appendLine("Changes asked for: " + asked.joinToString("; ") { "“${it.text.take(MAX_TITLE)}”" })
        }
    }.trimEnd()

    /**
     * The email that sends [text], the [week]'s summary, to [to] (none: the mail app asks): your mail app,
     * to send or change. Handed over as one to send, with a mailto selector so only mail apps take it, as
     * Gmail keeps only the address of a mailto link's and drops its subject and words.
     */
    fun email(to: String, week: String, text: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_EMAIL, listOfNotNull(to.trim().takeIf { it.isNotEmpty() }).toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, "Week of " + LocalDate.parse(week).format(DAY))
            .putExtra(Intent.EXTRA_TEXT, text)
            .apply { selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
