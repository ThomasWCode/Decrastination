package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.core.Enrichments
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * How hard the model thinks, by job (AC-02): high for the readings the plan is made from (an email,
 * an assignment, an estimate, an instruction, the week's review, a photo of homework read as
 * tasks); medium for the answers someone waits for (the chat, a reply, Felix, a piece's first
 * steps, a photo check, a summary, a tidy, a web search), which come sooner and cost less.
 */
object ModelEffort {
    enum class Job { Email, Assignment, Effort, Instruction, Review, PhotoToTask, Chat, Reply, Felix, MicroPlan, PhotoCheck, Summary, Tidy, Research }

    /**
     * How long a call waits for its answer, by job: the week's review and the tidy, which think longest,
     * five minutes; Felix's messages, a piece's first steps and the summary two; the rest three. The three
     * readings are one reader's ([ClaudeEnricher]), so theirs are alike.
     */
    private val TIMEOUT_MIN = mapOf(
        Job.Email to 3L, Job.Assignment to 3L, Job.Effort to 3L, Job.Instruction to 3L, Job.Review to 5L, Job.PhotoToTask to 3L,
        Job.Chat to 3L, Job.Reply to 3L, Job.Felix to 2L, Job.MicroPlan to 2L, Job.PhotoCheck to 3L, Job.Summary to 2L, Job.Tidy to 5L, Job.Research to 3L,
    )

    fun timeout(job: Job): Duration = Duration.ofMinutes(TIMEOUT_MIN.getValue(job))

    /** Set from a PC for testing (`ai-effort`), till the app restarts: never kept. */
    val overrides: MutableMap<Job, OutputConfig.Effort> = ConcurrentHashMap()

    private val HIGH = setOf(Job.Email, Job.Assignment, Job.Effort, Job.Instruction, Job.Review, Job.PhotoToTask)

    fun of(job: Job): OutputConfig.Effort = overrides[job] ?: if (job in HIGH) OutputConfig.Effort.HIGH else OutputConfig.Effort.MEDIUM

    private val SAYS = mapOf(
        Job.Email to "emails", Job.Assignment to "assignments", Job.Effort to "estimates", Job.Instruction to "instructions",
        Job.Review to "the week's review", Job.PhotoToTask to "photos made tasks",
        Job.Chat to "the chat", Job.Reply to "replies", Job.Felix to "Felix", Job.MicroPlan to "first steps",
        Job.PhotoCheck to "photo checks", Job.Summary to "the week's summary", Job.Tidy to "the tidy of what's remembered", Job.Research to "Felix's web searches",
    )

    /** The jobs by how hard it thinks on each, in words (Settings → Claude, UI-18): "High" and those, then "Medium". */
    fun table(): List<Pair<String, String>> = Job.entries.groupBy { of(it) }.map { (effort, jobs) ->
        val name = when (effort) {
            OutputConfig.Effort.HIGH -> "High"
            OutputConfig.Effort.MEDIUM -> "Medium"
            OutputConfig.Effort.LOW -> "Low"
            else -> effort.toString()
        }
        name to jobs.joinToString(", ") { SAYS[it] ?: it.name }
    }

    /** An enrichment's job as one of these. */
    fun jobOf(job: Enrichments.Job): Job = when (job) {
        Enrichments.Job.Email -> Job.Email
        Enrichments.Job.Assignment -> Job.Assignment
        Enrichments.Job.Effort -> Job.Effort
    }

    /** An enrichment's job's effort. */
    fun of(job: Enrichments.Job): OutputConfig.Effort = of(jobOf(job))
}
