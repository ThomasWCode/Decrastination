package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.core.Enrichment
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sources.anki.AnkiRules

/**
 * Says more about a task than its source does (docs/data-sources.md §5): the rules always, the
 * model when it's switched on (PLAN.md Phase 4).
 */
interface Enricher {
    /** Who this is, as [Enrichment.by] records it. */
    val by: String

    /** What it says about [task] for [job], and what saying it cost. */
    suspend fun enrich(task: TaskItem, job: Enrichments.Job, now: Long): Result

    data class Result(
        /** Null when it had nothing to say or declined. */
        val enrichment: Enrichment?,
        val costUsd: Double = 0.0,
        /** The model declined to answer (its safety classifiers). */
        val refused: Boolean = false,
    )
}

/**
 * What can be said without a model: an assignment whose instructions list its parts ("1. Complete
 * the reading task…", "- Translation…") is cut into those parts, sharing its estimate. A part that's
 * learning numbered vocabulary sections is tagged with them: where Anki deck tasks hold them, the
 * planner leaves it out. An email's kind, estimate and next step already come from the email rules
 * at the source.
 */
class RuleEnricher : Enricher {
    override val by = BY

    override suspend fun enrich(task: TaskItem, job: Enrichments.Job, now: Long): Enricher.Result {
        // The source's estimate (or yours), not one an out-of-date enrichment of the model's left.
        val steps = if (job == Enrichments.Job.Assignment) steps(task.detail, task.copy(aiEffortMin = null).effortMin) else null
        return Enricher.Result(Enrichment(inputHash = Enrichments.inputHash(task), by = BY, at = now, subSteps = steps))
    }

    companion object {
        const val BY = "rules"
        private const val MAX_TITLE = 60

        /** A list item at the start of a line: "1." or "2)" not followed by another digit (so not a section like "1.2"), or a dash or bullet. */
        private val NUMBERED = Regex("""^\s*\d{1,2}[.)](?!\d)\s*(\S.*)$""")
        private val BULLETED = Regex("""^\s*[-•*–]\s+(\S.*)$""")

        /** The parts [instructions] list, two or more, each with its share of [effortMin]; else null. */
        fun steps(instructions: String, effortMin: Int): List<SubStep>? {
            val items = instructions.lines().mapNotNull { line ->
                (NUMBERED.find(line) ?: BULLETED.find(line))?.groupValues?.get(1)?.trim()
            }.filter { it.isNotEmpty() }
            if (items.size < 2) return null
            val each = (effortMin / items.size).coerceAtLeast(5)
            return items.map { item ->
                // Tagged only if learning the words is all it asks: other work in it stays planned.
                val sections = if (AnkiRules.otherWork(item)) emptyList() else AnkiRules.linkedSections(item).map { (major, minor) -> "$major.$minor" }
                SubStep(title(item), each, ankiSections = sections)
            }
        }

        private fun title(item: String): String =
            if (item.length <= MAX_TITLE) item else item.take(MAX_TITLE - 1).trimEnd() + "…"
    }
}
