package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A piece's first steps (AI-15): the first action, under two minutes, the next two, and what done
 * looks like; for the piece [key] as its task was ([inputHash]) when it was written, at [at]. An
 * empty [first] is one tried and given none (declined, unread), so it isn't asked for again.
 */
@Serializable
data class MicroPlan(val key: String, val first: String, val next: List<String> = emptyList(), val doneWhen: String = "", val inputHash: String, val at: Long)

/** The micro-plans (AI-15): what's asked, the answer read, and which pieces are due one. Pure. */
object MicroPlans {
    val SYSTEM = """
        Give the student the first action for this piece of work, under two minutes, concrete enough to do without thinking ("open the booklet at p.7 and read Q1 aloud"); then the next two actions; then what done looks like for this piece. Plain UK English, no preamble.
    """.trimIndent()

    val SCHEMA: Map<String, Any> = InstructionPrompts.obj(
        "first" to InstructionPrompts.STRING,
        "next" to mapOf("type" to "array", "items" to InstructionPrompts.STRING),
        "doneWhen" to InstructionPrompts.STRING,
    )

    /** The most of the task's instructions sent. */
    const val MAX_INSTRUCTIONS = 1_500

    /** The most pieces written ahead in one run: today's and tomorrow's are rarely more. */
    const val MAX_A_RUN = 8

    private const val MAX_LINE = 160
    private const val MAX_NEXT = 2

    /** [chunk] as the model reads it: its task's title, subject and instructions, the piece and its minutes, its attachments' names. */
    fun describe(chunk: Chunk, task: TaskItem): String = buildString {
        appendLine("Task: ${task.title}")
        task.className?.let { appendLine("Subject: $it") }
        appendLine("This piece: ${chunk.step ?: if (chunk.parts > 1) "part ${chunk.part} of ${chunk.parts} of the whole task" else "the whole task"}, ${chunk.minutes} min")
        GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files -> appendLine("Attachments: " + files.joinToString(", ") { it.label }) }
        task.detail.trim().takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine("Instructions:")
            append(it.take(MAX_INSTRUCTIONS))
        }
    }.trimEnd()

    /** What a piece's micro-plan is written for: its task as it is, and which piece. */
    fun hashOf(task: TaskItem, chunk: Chunk): String = Enrichments.inputHash(task) + "#" + (chunk.stepIndex ?: -chunk.part)

    @Serializable
    private data class Answer(val first: String = "", val next: List<String> = emptyList(), val doneWhen: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    /** [text] as the micro-plan for [chunk] of [task], at [now]; one with no first action is kept empty, as tried. Null if it can't be read. */
    fun parse(text: String, chunk: Chunk, task: TaskItem, now: Long): MicroPlan? {
        val answer = runCatching { json.decodeFromString(Answer.serializer(), text) }.getOrNull() ?: return null
        fun line(s: String) = s.trim().take(MAX_LINE)
        return MicroPlan(chunk.key, line(answer.first), answer.next.map(::line).filter { it.isNotEmpty() }.take(MAX_NEXT), line(answer.doneWhen), hashOf(task, chunk), now)
    }

    /** One tried and given none (declined, unread), so it isn't asked for again till its task changes. */
    fun none(chunk: Chunk, task: TaskItem, now: Long): MicroPlan = MicroPlan(chunk.key, "", inputHash = hashOf(task, chunk), at = now)

    /** Today's and tomorrow's work slots' pieces, in order. */
    private fun ahead(plan: Plan): List<Chunk> = (plan.todaySlots + plan.tomorrowBucket?.slots.orEmpty()).filter { it.kind == SlotKind.Work && it.end > plan.now }.mapNotNull { it.chunk }.distinctBy { it.key }

    /** The pieces due a micro-plan: today's and tomorrow's slots' whose plan isn't for their task as it is. */
    fun due(plan: Plan, tasks: Map<String, TaskItem>, plans: Map<String, MicroPlan>): List<Pair<Chunk, TaskItem>> =
        ahead(plan).mapNotNull { chunk -> tasks[chunk.taskId]?.takeIf { plans[chunk.key]?.inputHash != hashOf(it, chunk) }?.let { chunk to it } }

    /** [plans] kept for today's and tomorrow's slots only. */
    fun kept(plan: Plan, plans: Map<String, MicroPlan>): Map<String, MicroPlan> {
        val keys = ahead(plan).map { it.key }.toSet()
        return plans.filterKeys { it in keys }
    }

    /** [plan] in full, as a piece's sheet says it: "First: …", "Then: …", "Done when: …". */
    fun lines(plan: MicroPlan): List<String> = listOfNotNull(
        plan.first.takeIf { it.isNotEmpty() }?.let { "First: $it" },
        plan.next.takeIf { it.isNotEmpty() }?.let { "Then: " + it.joinToString("; ") },
        plan.doneWhen.takeIf { it.isNotEmpty() }?.let { "Done when: $it" },
    )
}
