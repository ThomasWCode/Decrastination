package com.thomaswcode.decrastination.enrich

import com.anthropic.models.messages.OutputConfig
import com.thomaswcode.decrastination.core.Enrichments
import java.util.concurrent.ConcurrentHashMap

/**
 * How hard the model thinks, by job (AC-02): high for the readings the plan is made from (an email,
 * an assignment, an estimate, an instruction, the week's review, a photo read as a task or for its
 * context); medium for the answers someone waits for (the chat, a reply, Felix, a piece's first
 * steps, a photo check, a summary, a tidy), which come sooner and cost less.
 */
object ModelEffort {
    enum class Job { Email, Assignment, Effort, Instruction, Review, PhotoToTask, PhotoContext, Chat, Reply, Felix, MicroPlan, PhotoCheck, Summary, Tidy }

    /** Set from a PC for testing (`ai-effort`), till the app restarts: never kept. */
    val overrides: MutableMap<Job, OutputConfig.Effort> = ConcurrentHashMap()

    private val HIGH = setOf(Job.Email, Job.Assignment, Job.Effort, Job.Instruction, Job.Review, Job.PhotoToTask, Job.PhotoContext)

    fun of(job: Job): OutputConfig.Effort = overrides[job] ?: if (job in HIGH) OutputConfig.Effort.HIGH else OutputConfig.Effort.MEDIUM

    /** An enrichment's job as one of these. */
    fun of(job: Enrichments.Job): OutputConfig.Effort = of(
        when (job) {
            Enrichments.Job.Email -> Job.Email
            Enrichments.Job.Assignment -> Job.Assignment
            Enrichments.Job.Effort -> Job.Effort
        },
    )
}
