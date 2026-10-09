package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * What the enrichment (docs/data-sources.md §5) says about a task beyond what its source does: an
 * email's real kind and deadline, an assignment's steps, an estimate. Kept on the task
 * ([TaskItem.enrichment]) and laid over the source's values at every merge ([enriched]), so a sync
 * never undoes it; made again when the task's content changes ([Enrichments.inputHash]).
 */
@Serializable
data class Enrichment(
    /** What it was made from: a different hash means the task has changed since. */
    val inputHash: String,
    /** Who made it: the rules, or the model's id. */
    val by: String,
    val at: Long,
    /** An email's kind: something to do, an event, or only to read. */
    val kind: Kind? = null,
    /** Nothing can be done before then (a form that opens next week). */
    val actionableFrom: Long? = null,
    /** A deadline the text gives that the source doesn't know. */
    val deadline: Long? = null,
    val effortMin: Int? = null,
    /** The next thing to do, in a few words. */
    val nextStep: String? = null,
    /** The work cut into the steps it will be done in. */
    val subSteps: List<SubStep>? = null,
    /** Vocabulary sections it names ("1.2"), for its Anki decks when the deck pattern finds none. */
    val ankiSections: List<String> = emptyList(),
    /** The day of a test the work prepares for: it's due by then. */
    val testDate: Long? = null,
)

/**
 * The task with its enrichment laid over what the source says: the source's deadline wins, though
 * a test before it brings it forward and an email's own deadline counts where the source has none;
 * the later of the two start dates; the enrichment's estimate; an email's kind; and the steps,
 * where the source gives none.
 */
fun TaskItem.enriched(): TaskItem {
    // Always from what the source said, so a changed enrichment doesn't leave the last one's dates.
    val base = sourceValues ?: SourceValues(kind, dueAt, availableFrom)
    // None, or one of content that has since changed (a new email in the thread): what the source
    // says, until it's enriched again. The steps it gave stay, with their ticks, till then.
    val e = Enrichments.current(this)
        ?: return copy(kind = base.kind, dueAt = base.dueAt, availableFrom = base.availableFrom, aiEffortMin = null)
    val due = when {
        // An email has no deadline of its own: what the rules found is a guess from its words
        // ("appointment"), and the enrichment's reading of it wins where it gives one.
        source == Source.Gmail -> e.deadline ?: base.dueAt
        e.testDate != null && (base.dueAt == null || e.testDate < base.dueAt) -> e.testDate
        else -> base.dueAt ?: e.deadline
    }
    // A start after the deadline would hide the work until it's overdue: it's dropped.
    val from = e.actionableFrom?.takeIf { due == null || it <= due }
    return copy(
        kind = if (source == Source.Gmail) e.kind ?: base.kind else base.kind,
        dueAt = due,
        availableFrom = listOfNotNull(base.availableFrom, from).maxOrNull(),
        // This enrichment's estimate, or none: an older one's doesn't outlive it.
        aiEffortMin = e.effortMin,
        subSteps = if (subSteps.isEmpty()) e.subSteps.orEmpty() else subSteps,
    )
}

/**
 * The task with a fresh enrichment: steps an earlier enrichment gave are replaced by the new ones,
 * keeping those already done; steps of the source's own are kept.
 */
fun TaskItem.withEnrichment(new: Enrichment): TaskItem {
    val fromEnrichment = enrichment?.subSteps?.map { it.title } == subSteps.map { it.title } && subSteps.isNotEmpty()
    // How many by each title were done: that many of the new steps by it are, in order, so two
    // steps of the same name aren't both ticked by one.
    val done = subSteps.filter { it.done }.groupingBy { it.title }.eachCount().toMutableMap()
    val base = if (fromEnrichment) copy(subSteps = emptyList()) else this
    val next = base.copy(enrichment = new).enriched()
    return next.copy(
        subSteps = next.subSteps.map { step ->
            val left = done[step.title] ?: 0
            if (left == 0) return@map step
            done[step.title] = left - 1
            step.copy(done = true)
        },
    )
}

/** What there is to enrich, and how to tell when it has changed. */
object Enrichments {

    /** The three jobs of docs/data-sources.md §5 that run on tasks. */
    enum class Job { Email, Assignment, Effort }

    /** What [task] needs, or null: done, derived (the Anki quota and decks), or an event. */
    fun jobFor(task: TaskItem): Job? = when {
        !task.isOpen || task.derived -> null
        // Its text not read yet (more new emails than one read takes): asked about once it is.
        task.source == Source.Gmail && GmailThreads.EXTRA_TEXT_PENDING in task.extra -> null
        task.source == Source.Gmail -> Job.Email
        task.source == Source.Teams -> Job.Assignment
        task.source == Source.PowerPlanner && task.kind != Kind.Event -> Job.Effort
        else -> null
    }

    /**
     * A hash of everything the enrichment reads, so a change in any of it makes it out of date. The
     * source's deadline, not the one the enrichment lays over it, which would make it out of date itself.
     */
    fun inputHash(task: TaskItem): String {
        val text = listOf(
            task.source.name,
            task.title,
            task.detail,
            task.className.orEmpty(),
            // A source with no deadline has none here, whatever the overlay says; only a task kept
            // from before Phase 4 has no source values to read.
            (if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt)?.toString().orEmpty(),
            task.extra["from"].orEmpty(),
            task.extra["received"].orEmpty(),
        ).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** [task]'s enrichment if it's of the task as it is now; one of content since changed, null. */
    fun current(task: TaskItem): Enrichment? = task.enrichment?.takeIf { it.inputHash == inputHash(task) }

    /** Whether [task] needs enriching again: never done, changed since, or done by the rules when the model is now on. */
    fun stale(task: TaskItem, modelOn: Boolean, rules: String): Boolean {
        val e = task.enrichment ?: return true
        return e.inputHash != inputHash(task) || (modelOn && e.by == rules)
    }
}
