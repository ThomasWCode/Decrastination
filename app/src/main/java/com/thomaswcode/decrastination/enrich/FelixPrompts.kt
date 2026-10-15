package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.core.Given
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Felix (AI-22): the voice of the messages the app sends about the work, a strict friend whose name
 * and line are the student's to change in Settings. What he's told for each reason a message is
 * written, and the shape of his answer. Pure.
 */
object FelixPrompts {
    /** Why a message is written. */
    enum class Reason { SlotStart, SlotEnd, SlotKept, DayDone, Morning, Question, Completions, Summary, Reply }

    /** His messages, and the replies to them, that he's shown: the last few. */
    const val RECENT = 6

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)

    /** His instructions, as [settings] name and describe him, for a student in Year [year]. */
    fun system(settings: Settings, year: Int): String = """
        You are ${settings.felixName}: ${settings.felixLine}. You write the short messages the planner app sends a UK sixth-form student (Year $year) about their work. You get the reason for the message, the piece of work it's about with its first action, today's plan and what's been kept or missed, your recent messages and the student's replies, and the time.

        Be concrete: name the piece and its first action. Two sentences at most. No guilt, no sarcasm, no emojis, no praise for nothing, no promise the app can't keep. Don't repeat what you said last time. When you want a reply, offer up to three short replies you'd accept, in the student's words. Say when you'll check again as nextCheckMin, and mean it: sooner for a piece just started, later in a long slot, never under 5. Reasons: SlotStart (the slot began and nothing's happened: ask if they're starting); SlotEnd (the slot ended with no tick: ask what happened, or for the photo if the piece needs one); SlotKept (a slot was kept: one line, and only sometimes); DayDone (today's last slot kept: say what's earned, briefly); Morning (the day's shape in a sentence or two); Question (put the question given, in your words); Completions (a source says work is done: ask harder, as expected or easier); Summary (Sunday: say the week's summary is ready to send); Reply (you're answering what they said).

        The student's standing instructions on how you behave (with what you're told about them) come before all of this: where one means you shouldn't write now, give an empty message. Remember (memories) a fact about them or a standing instruction only when their replies give one that isn't remembered yet; most messages need none.
    """.trimIndent()

    /**
     * What he's told for one message: the [reason]; the time; the [slot]'s piece (its minutes, its
     * time, and what earns it its free time) and its first action, or the slot on [record] (missed,
     * or kept), or the [task] it's about; today's
     * slots kept, missed and to come, and the free time there is ([freeMin]); his [recent] messages
     * and the replies to them; and, for a Question, the [question] to put.
     */
    fun describe(
        reason: Reason,
        plan: Plan,
        slot: Slot?,
        task: TaskItem?,
        today: List<SlotRecord>,
        recent: List<FelixRecord>,
        question: String?,
        settings: Settings,
        now: Long,
        zone: ZoneId,
        freeMin: Int? = null,
        record: SlotRecord? = null,
        done: List<String> = emptyList(),
        firstAction: String? = null,
    ): String = buildString {
        fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)
        appendLine("Reason: $reason")
        appendLine("Now: ${Instant.ofEpochMilli(now).atZone(zone).format(NOW)}")
        val piece = slot?.chunk
        if (slot != null && piece != null) {
            val earns = when (piece.earnsBy(settings.photoFromMin)) {
                EarnsBy.Tick -> "on its tick"
                EarnsBy.Photo -> "with a photo of it, which it needs to earn"
                EarnsBy.Source -> "once ${piece.source.label} says it's done"
                EarnsBy.Nothing -> "nothing: nothing can check it"
            }
            appendLine("Piece: ${piece.label}, ${piece.minutes} min, slot ${clock(slot.start)}–${clock(slot.end)}; it earns its free time $earns")
            // Its first step, written ahead (AI-15).
            appendLine("First action: ${firstAction ?: "none yet"}")
        } else if (record != null) {
            val how = when {
                record.outcome == SlotOutcome.Missed -> "it went by undone, and has been moved to later"
                record.earned -> "kept"
                record.kind == com.thomaswcode.decrastination.core.Kind.Homework && record.plannedMin >= settings.photoFromMin -> "ticked done, but it earns its free time only with a photo of it"
                else -> "ticked done, without earning its free time yet"
            }
            appendLine("Piece: ${record.label}, ${record.plannedMin} min, slot ${clock(record.start)}–${clock(record.end)}; $how")
        } else if (task != null) {
            appendLine("Task: ${task.title}")
        }
        val kept = today.count { it.outcome.kept }
        val missed = today.count { it.outcome == SlotOutcome.Missed }
        val toCome = plan.todaySlots.filter { it.kind == SlotKind.Work && it.end > now && it.chunk != null }
        val next = toCome.firstOrNull { it.start > now }?.let { "; next: ${it.chunk?.label} at ${clock(it.start)}" }.orEmpty()
        appendLine("Today: $kept kept, $missed missed, ${toCome.size} to come$next")
        if (toCome.isNotEmpty()) appendLine("Left today: " + toCome.joinToString("; ") { "${it.chunk?.label} ${clock(it.start)}–${clock(it.end)}" })
        freeMin?.let { appendLine("Free time now: $it min") }
        if (done.isNotEmpty()) appendLine("Done, as their sources say, with none of it timed in a slot: " + done.joinToString("; "))
        // His silences (he chose not to write, or the model couldn't be asked) aren't messages.
        val shown = recent.filter { it.text.isNotBlank() }.sortedBy { it.at }.takeLast(RECENT)
        if (shown.isNotEmpty()) {
            appendLine("Recent, oldest first:")
            for (r in shown) {
                appendLine("- ${Instant.ofEpochMilli(r.at).atZone(zone).format(WHEN)}, you (${r.reason}): ${r.text}")
                r.replyText?.let { reply -> appendLine("- ${Instant.ofEpochMilli(r.repliedAt ?: r.at).atZone(zone).format(WHEN)}, they replied: $reply") }
            }
        }
        question?.let { appendLine("Question: $it") }
    }.trimEnd()

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "message" to InstructionPrompts.STRING,
        "replies" to mapOf("type" to "array", "items" to InstructionPrompts.STRING),
        "nextCheckMin" to InstructionPrompts.nullable(mapOf("type" to "integer")),
        "memories" to mapOf("type" to "array", "items" to ChatPrompts.MEMORY),
    )
}

/**
 * Felix's answer (AI-22): his [message] (empty: he'd rather not write now), up to three [replies]
 * he'd accept, when he'd check again, and what to remember (AI-21).
 */
@Serializable
data class FelixAnswer(val message: String = "", val replies: List<String> = emptyList(), val nextCheckMin: Int? = null, val memories: List<Given> = emptyList())

/** His answer read and kept within its bounds. Pure. */
object FelixAnswers {
    const val MAX_MESSAGE = 240
    const val MAX_REPLIES = 3
    const val MAX_REPLY = 40
    const val MIN_CHECK_MIN = 5
    private const val MAX_CHECK_MIN = 24 * 60
    private const val MAX_MEMORIES = 5

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] as his answer, within bounds; null when it isn't one. */
    fun parse(text: String): FelixAnswer? {
        val answer = runCatching { json.decodeFromString(FelixAnswer.serializer(), text) }.getOrNull() ?: return null
        return FelixAnswer(
            message = cut(answer.message.trim(), MAX_MESSAGE),
            replies = answer.replies.map { cut(it.trim(), MAX_REPLY) }.filter { it.isNotEmpty() }.distinct().take(MAX_REPLIES),
            nextCheckMin = answer.nextCheckMin?.coerceIn(MIN_CHECK_MIN, MAX_CHECK_MIN),
            memories = answer.memories.take(MAX_MEMORIES),
        )
    }

    /** [text] no longer than [max]: cut at a word where it can be, with an ellipsis. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val room = text.take(max - 1)
        return room.substringBeforeLast(' ', room).trimEnd(' ', ',', ';', ':') + "…"
    }
}
