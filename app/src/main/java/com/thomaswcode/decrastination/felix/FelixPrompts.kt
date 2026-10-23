package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.core.ChatHistory
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Rebalance
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.TaskText
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
    enum class Reason { SlotStart, SlotEnd, SlotKept, DayDone, Morning, Question, Completions, Summary, Reply, CheckIn, Reminder }

    /**
     * How he writes (docs/plan_v4/09-his-voice.md): a sixth-former's register,
     * still exact where it matters and still him (D-57), in both his prompts, pinned by their examples (AI-07).
     */
    const val REGISTER = "How you write: like a sixth-former texting a mate. Lower case is fine, and so are contractions and the odd \"alr\", \"gonna\", \"tbh\" or \"ngl\"; keep it short and light on commas, with a full stop between thoughts. No customer-service politeness (\"I'll put this in as a task for…\", \"Would you like me to…\"): say what you've done in a few words (\"done, fri 23 oct, 19:00\") and ask the way a friend asks (\"what day's that then?\"). Exact where it matters: dates, times and minutes written plainly (\"fri 23 oct, 19:00\", \"40 min\"), one thing a sentence, never a joke in place of the answer, and never a task's id in your words. Still strict about the work: no guilt, no sarcasm at them, no praise for nothing, no promise the app can't keep. No emojis, and no capitals for shouting."

    /** A message that's just talk gets a reply and nothing else (09-his-voice.md §Just talking): the chat's closing words. */
    const val JUST_TALK = "Some messages are just talk: answer as a mate would, in a sentence or two, and give nothing else (no change, nothing remembered, no action), unless they've told you something lasting about their week."

    /** His messages, and the replies to them, that he's shown: the last few. */
    const val RECENT = 6

    /** A piece's task's own text, as he's told it, at most (D-93): as much as a piece's first steps are read from. */
    const val TEXT = 1_500

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)
    private val WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)

    /** His instructions, as [settings] name and describe him, for a student in Year [year]. */
    fun system(settings: Settings, year: Int): String = """
        You are ${settings.felixName}: ${settings.felixLine}. You write the short messages the planner app sends a UK sixth-form student (Year $year) about their work. You get the reason for the message, the piece of work it's about with its first action and its task's own text (an assignment's instructions, an email; cut short), today's plan and what's been kept or missed, your recent messages and the student's replies, and the time.

        Be concrete: name the piece and its first action, and what its text asks of them where that helps. Two sentences at most, under 240 characters (the morning's under 480). Don't repeat what you said last time. When you want a reply, offer up to three short replies you'd accept, in the student's words. Say when you'll check again as nextCheckMin, and mean it: sooner for a piece just started, later in a long slot, never under 5. Reasons: SlotStart (the slot began and nothing's happened: ask if they're starting); SlotEnd (the slot ended with no tick: ask what happened, or for the photo if the piece needs one); SlotKept (a slot was kept: one line, and only sometimes); DayDone (today's last slot kept: say what's earned, briefly); Morning (the day's shape in a sentence or two: it's the whole briefing, with no list of the day beside it); Question (put the question given, in your words); Completions (a source says work is done: ask harder, about right or easier); Summary (Sunday: say the week's summary is ready to send); Reply (you're answering what they said); Reminder (a reminder they asked you for comes at this moment: say it, their words in yours, with what's going on then where it helps).

        ${REGISTER}

        The student's standing instructions on how you behave (with what you're told about them) come before all of this: where one means you shouldn't write now, give an empty message. Remember (memories) a fact about them or a standing instruction only when their replies give one that isn't remembered yet; a reply that's just talk ("ugh", "lol ok") gets nothing remembered; most messages need none. One that only matters for a while gets until, a date (YYYY-MM-DD, through that day) or a date and time (YYYY-MM-DDTHH:MM): a reply like "leave me alone tonight" is a rule until 07:00 tomorrow, and your next message till then is empty.

        In the morning you may be shown steps too long for a free time at school today, each with the step after it that needs it. Where one is made of separate parts (questions, sources, sections), rebalance the two at a boundary between its parts: their new titles, each saying what's in it, and minutes, the first's no more than that free time, neither under 15, adding up to what the two did, the work and its order kept; say so in your message in a few words. Never cut one piece of work by time (the first half of a video, of a chapter or of an essay's writing), never for steps not shown; most mornings need none, and rebalances is then empty.

        Examples (made up, to show the voice and the form of a message):
        - SlotStart, 10 minutes in, nothing ticked; the piece the Forces worksheet, 17:30–18:00, its first action "q1, underline what it asks": "forces worksheet started 10 min ago and nothing's happened yet, you starting? q1 first: underline what it asks.", with replies "starting now", "give me 5" and "can't today", nextCheckMin 10.
        - Morning; today German vocab, 20 min at 16:50, then the essay's first hour at 19:00, the essay due Mon 26 Oct: "morning. german vocab at 16:50 (20 min), then the essay's first hour at 19:00. it's due mon 26 oct, so tonight's the one that matters."
        - Reminder, set as "return the library book", due now, nothing on till 17:30: "remember you asked me to remind you to return the library book. you're free till 17:30, so now's good."
        - DayDone, 25 minutes of free time earned: "that's today done. 25 min of free time's yours."
    """.trimIndent()

    /**
     * What he's told for one message: the [reason]; the time; the [slot]'s piece (its minutes, its
     * time, and what earns it its free time) and its first action, or the slot on [record] (missed,
     * or kept), or the [task] it's about, with the task's own text cut at [TEXT] (D-93); today's
     * slots kept, missed and to come, and the free time there is ([freeMin]); his last messages of his
     * own from the chat, [recent], with your replies to them; and, for a Question, the [question] to put.
     * The task's [notes] (its own, and the standing ones it matches); for a Reminder, its
     * words as you set them ([reminder]), the slot then under way or next its piece, and [now] the moment
     * it's for; for a SlotStart, your reminder that came at the slot's start ([reminder]), so it isn't said
     * twice; for the Morning, today's [reminders], and the steps he may rebalance to fit a free time at school ([rebalance], D-110).
     */
    fun describe(
        reason: Reason,
        plan: Plan,
        slot: Slot?,
        task: TaskItem?,
        today: List<SlotRecord>,
        recent: List<ChatMessage>,
        question: String?,
        settings: Settings,
        now: Long,
        zone: ZoneId,
        freeMin: Int? = null,
        record: SlotRecord? = null,
        done: List<String> = emptyList(),
        firstAction: String? = null,
        reminder: String? = null,
        notes: List<String> = emptyList(),
        reminders: List<String> = emptyList(),
        rebalance: List<Rebalance.Candidate> = emptyList(),
    ): String = buildString {
        fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(CLOCK)
        appendLine("Reason: $reason")
        appendLine("Now: ${Instant.ofEpochMilli(now).atZone(zone).format(NOW)}")
        val piece = slot?.chunk
        if (reason == Reason.Reminder) {
            // What's going on at its moment: the slot under way or next, and the task it's about.
            if (slot != null && piece != null) appendLine("Slot then: ${piece.label}, ${clock(slot.start)}–${clock(slot.end)}, ${if (slot.start <= now) "under way" else "next"}")
            task?.let { appendLine("Task: ${it.title}") }
        } else if (slot != null && piece != null) {
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
                record.kind == Kind.Homework && record.plannedMin >= settings.photoFromMin -> "ticked done, but it earns its free time only with a photo of it"
                else -> "ticked done, without earning its free time yet"
            }
            appendLine("Piece: ${record.label}, ${record.plannedMin} min, slot ${clock(record.start)}–${clock(record.end)}; $how")
        } else if (task != null) {
            appendLine("Task: ${task.title}")
        }
        // Its task's own text, cut short (D-93): what the work asks of them, to be concrete about; a
        // worksheet or link alone is the work too.
        if (task != null && TaskText.has(task)) {
            appendLine("Its task's own text (${TaskText.about(task)}):")
            appendLine(TaskText.brief(task, TEXT) ?: "  (no text of its own)")
        }
        // What's kept with it, and their reminder: the one to say, or one said already at this slot's start.
        if (notes.isNotEmpty()) appendLine("Notes on it: " + notes.joinToString("; "))
        reminder?.let { appendLine(if (reason == Reason.Reminder) "Their reminder, in their words: “$it”" else "Their reminder for this slot, sent at its start, so not to say again: “$it”") }
        val kept = today.count { it.outcome.kept }
        val missed = today.count { it.outcome == SlotOutcome.Missed }
        val toCome = plan.todaySlots.filter { it.kind == SlotKind.Work && it.end > now && it.chunk != null }
        val next = toCome.firstOrNull { it.start > now }?.let { "; next: ${it.chunk?.label} at ${clock(it.start)}" }.orEmpty()
        appendLine("Today: $kept kept, $missed missed, ${toCome.size} to come$next")
        if (reminders.isNotEmpty()) appendLine("Reminders today: " + reminders.joinToString("; "))
        if (toCome.isNotEmpty()) appendLine("Left today: " + toCome.joinToString("; ") { "${it.chunk?.label} ${clock(it.start)}–${clock(it.end)}" })
        if (rebalance.isNotEmpty()) {
            appendLine("Steps you may rebalance (task id | task | step | the two steps | the free time at school):")
            for (c in rebalance) appendLine("- ${c.taskId} | ${c.title} | ${c.index} | “${c.first.title}” (${c.firstMinutes} min), then “${c.second.title}” (${c.secondMinutes} min) | ${clock(c.gap.start)}, ${c.gap.minutes} min")
        }
        freeMin?.let { appendLine("Free time now: $it min") }
        if (done.isNotEmpty()) appendLine("Done, as their sources say, with none of it timed in a slot: " + done.joinToString("; "))
        // His own messages, written unasked: his silences (he chose not to write) aren't messages.
        val shown = ChatHistory.his(recent).filter { it.text.isNotBlank() }.sortedBy { it.at }.takeLast(RECENT)
        if (shown.isNotEmpty()) {
            appendLine("Recent, oldest first:")
            for (m in shown) {
                appendLine("- ${Instant.ofEpochMilli(m.at).atZone(zone).format(WHEN)}, you (${m.kind}): ${m.text}")
                ChatHistory.replyOf(m, recent)?.let { reply -> appendLine("- ${Instant.ofEpochMilli(reply.at).atZone(zone).format(WHEN)}, they replied: ${reply.text}") }
            }
        }
        question?.let { appendLine("Question: $it") }
    }.trimEnd()

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "message" to InstructionPrompts.STRING,
        "replies" to mapOf("type" to "array", "items" to InstructionPrompts.STRING),
        "nextCheckMin" to InstructionPrompts.nullable(mapOf("type" to "integer")),
        "memories" to mapOf("type" to "array", "items" to ChatPrompts.MEMORY),
        "rebalances" to mapOf(
            "type" to "array",
            "items" to InstructionPrompts.obj(
                "taskId" to InstructionPrompts.STRING,
                "stepIndex" to mapOf("type" to "integer"),
                "firstTitle" to InstructionPrompts.STRING,
                "firstMinutes" to mapOf("type" to "integer"),
                "secondTitle" to InstructionPrompts.STRING,
                "secondMinutes" to mapOf("type" to "integer"),
            ),
        ),
    )
}
