package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.FelixPrompts.Reason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How Felix feels about today's work (D-119): let down by slots missed and nudges gone unanswered, pleased and proud by
 * work kept. Today's records alone, so each day starts neutral; the level a message of his was written at is kept on it
 * (Q103). A ceiling, not an order: his prompts bring him down from it where their words give a reason, an illness or a
 * hard time. Pure.
 */
object Mood {
    /** How he feels, the warmest first. */
    enum class Level { Proud, Pleased, Neutral, Firm, Strong }

    /** A [level], and what it rests on in words ([because]), for the line he's told. */
    data class Feeling(val level: Level, val because: List<String> = emptyList())

    val NEUTRAL = Feeling(Level.Neutral)

    /** A real deadline this close, with nothing done on its task, raises a let-down a step. */
    const val CLOSE_MS = 24 * 3_600_000L

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /**
     * How he feels writing for [reason] at [now] (null: the chat's turn), from [today]'s slots on record, the one
     * the message is about ([record]: a kept slot's, the day's last), his nudges already sent on the slot under way
     * ([nudges]), the [task] it's about and the chat's [messages]. A slot begun or gone by undone leans let down; a
     * slot kept or the day done, pleased; the chat leans the way today's newest record does; the rest (the morning,
     * what he only passes on) are neutral, so a day starts clean and nothing he passes on is coloured.
     */
    fun of(
        reason: Reason?,
        today: List<SlotRecord>,
        record: SlotRecord?,
        nudges: Int,
        task: TaskItem?,
        messages: List<ChatMessage>,
        now: Long,
        zone: ZoneId,
    ): Feeling = when (reason) {
        Reason.SlotStart -> down(today, nudges, task, now, zone)
        Reason.SlotEnd -> down(today, 0, task, now, zone)
        Reason.SlotKept -> up(today, record, dayDone = false, messages, zone)
        Reason.DayDone -> up(today, record, dayDone = true, messages, zone)
        // The chat: as today's newest record has it, and with none, neutral.
        null -> newest(today).let { newest ->
            when {
                newest == null -> NEUTRAL
                newest.outcome == SlotOutcome.Missed -> down(today, 0, task?.takeIf { it.id == newest.taskId }, now, zone)
                else -> up(today, newest, dayDone = false, messages, zone)
            }
        }
        else -> NEUTRAL
    }

    /** Today's newest slot on record, a move passed over: the one the chat's turn leans by, its task the one [of] is given. */
    fun newest(today: List<SlotRecord>): SlotRecord? = today.filter { it.outcome != SlotOutcome.Moved }.maxByOrNull { it.at }

    /**
     * Let down: strongly with three or more slots missed today; firmly with two, or on a piece he's nudged already
     * ([nudges] one or more); else neutral. A real deadline within [CLOSE_MS] with nothing done on its task raises it
     * a step, only where the day has a miss or the piece a nudge: the planner lays most work the evening before it's
     * due, so a first nudge with nothing missed stays neutral. A slot kept late after its miss isn't one.
     */
    private fun down(today: List<SlotRecord>, nudges: Int, task: TaskItem?, now: Long, zone: ZoneId): Feeling {
        val missed = today.count { it.outcome == SlotOutcome.Missed }
        val because = mutableListOf<String>()
        if (missed > 0) because += if (missed == 1) "1 slot missed today" else "$missed slots missed today"
        if (nudges > 0) because += "you've nudged them on this piece already, and nothing's started"
        var level = when {
            missed >= 3 -> Level.Strong
            missed == 2 || nudges > 0 -> Level.Firm
            else -> Level.Neutral
        }
        val due = task?.let { closeAndUntouched(it, now) }
        if (due != null && (missed > 0 || nudges > 0) && level != Level.Strong) {
            level = if (level == Level.Firm) Level.Strong else Level.Firm
            because += "its task's due ${dueWords(due, now, zone)}, a real deadline, and nothing's done on it yet"
        }
        return if (level == Level.Neutral) NEUTRAL else Feeling(level, because)
    }

    /** [task]'s real deadline where it's within [CLOSE_MS] of [now] and nothing's done on it: no minutes worked or seen in a photo, no step ticked, by hand or not. */
    private fun closeAndUntouched(task: TaskItem, now: Long): Long? {
        if (!task.hardAt(now)) return null
        val due = task.deadlineAt(now) ?: return null
        if (due <= now || due - now > CLOSE_MS) return null
        return due.takeIf { task.workedMin == 0 && task.photoMin == 0 && task.subSteps.none { it.done } }
    }

    /**
     * Pleased with a slot kept ([record]); proud with the day done ([dayDone]) and every slot of it kept, two or more,
     * none of them made up late, or with this slot a missed piece [madeUp] and no other miss or make-up today. Never
     * "every slot" before the day's done: the records aren't the plan, and work may be still to come.
     */
    private fun up(today: List<SlotRecord>, record: SlotRecord?, dayDone: Boolean, messages: List<ChatMessage>, zone: ZoneId): Feeling {
        if (record == null || !record.outcome.kept) return NEUTRAL
        val counted = today.filter { it.outcome != SlotOutcome.Moved }
        val missed = counted.count { it.outcome == SlotOutcome.Missed }
        val madeUp = counted.filter { madeUp(it, messages, zone) }
        val kept = counted.count { it.outcome.kept }
        if (dayDone && counted.size >= 2 && kept == counted.size && madeUp.isEmpty()) return Feeling(Level.Proud, listOf("every slot today kept, $kept of $kept"))
        val itsOwn = madeUp.any { it.key == record.key }
        val at = Instant.ofEpochMilli(record.at).atZone(zone).format(CLOCK)
        if (itsOwn && missed == 0 && madeUp.size == 1) return Feeling(Level.Proud, listOf("the missed piece of ${record.label} made up at $at, and no other miss today"))
        val because = mutableListOf(if (itsOwn) "the missed piece of ${record.label} made up at $at" else if (dayDone) "today's last slot kept" else "this slot kept")
        if (missed > 0) because += if (missed == 1) "1 still missed today" else "$missed still missed today"
        if (madeUp.any { it.key != record.key }) because += if (madeUp.count { it.key != record.key } == 1) "another made up late" else "${madeUp.count { it.key != record.key }} others made up late"
        return Feeling(Level.Pleased, because)
    }

    /**
     * Whether [record] is a missed piece made up: kept late ([SlotOutcome.KeptLate]) after its slot's end, with a
     * SlotEnd of his about it that day. Keeping a missed piece replaces its miss on record ([com.thomaswcode.decrastination.block.Focus]),
     * so this is how a make-up is known; one kept late within its slot's time, with no SlotEnd, is only late.
     */
    fun madeUp(record: SlotRecord, messages: List<ChatMessage>, zone: ZoneId): Boolean =
        record.outcome == SlotOutcome.KeptLate && record.at > record.end &&
            messages.any { it.from == Speaker.Felix && it.kind == MessageKind.SlotEnd && it.pieceKey == record.key && dayOf(it.at, zone).toString() == record.date }

    /** His nudges already sent on the piece [key] today, by [now]: his SlotStart messages about it with words (a silence was never seen). */
    fun nudges(messages: List<ChatMessage>, key: String?, now: Long, zone: ZoneId): Int {
        if (key == null) return 0
        val day = dayOf(now, zone)
        return messages.count { it.from == Speaker.Felix && it.kind == MessageKind.SlotStart && it.pieceKey == key && it.text.isNotBlank() && it.at <= now && dayOf(it.at, zone) == day }
    }

    /**
     * Whether he was let down (firmly or strongly) about [taskId] in a message of his today before [keptAt], by the level
     * kept on it (Q103): a slot of that task kept after it is then always answered (Q102), so the let-down is never his
     * last word on it; one kept before it, the let-down about what came after, isn't a recovery. Read from what was kept,
     * not worked out again: keeping the piece has replaced its miss and added to its minutes.
     */
    fun letDown(taskId: String, messages: List<ChatMessage>, now: Long, zone: ZoneId, keptAt: Long = now): Boolean {
        val day = dayOf(now, zone)
        return messages.any {
            it.from == Speaker.Felix && it.about.taskId == taskId && it.text.isNotBlank() && dayOf(it.at, zone) == day && it.at < keptAt &&
                (it.mood == Level.Firm.name || it.mood == Level.Strong.name)
        }
    }

    /** What's kept on his message for [feeling] ([ChatMessage.mood]): its level's name, empty for neutral or a silence. */
    fun kept(feeling: Feeling, text: String): String = if (feeling.level == Level.Neutral || text.isBlank()) "" else feeling.level.name

    /**
     * The line he's told for [feeling], none at neutral: "How you feel, at most: let down, strongly (3 slots missed
     * today; …)", "How you feel, at most: proud (every slot today kept, 4 of 4)".
     */
    fun line(feeling: Feeling): String? {
        val how = when (feeling.level) {
            Level.Proud -> "proud"
            Level.Pleased -> "pleased"
            Level.Neutral -> return null
            Level.Firm -> "let down, firmly"
            Level.Strong -> "let down, strongly"
        }
        return "How you feel, at most: $how" + feeling.because.takeIf { it.isNotEmpty() }?.joinToString("; ", " (", ")").orEmpty()
    }

    private fun dayOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** A deadline as he's told it: "today 23:59", "tomorrow 09:00", else "Thu 6 Nov 09:00". */
    private fun dueWords(due: Long, now: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(due).atZone(zone)
        val today = dayOf(now, zone)
        val day = when (at.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> at.format(DAY)
        }
        return "$day ${at.format(CLOCK)}"
    }
}
