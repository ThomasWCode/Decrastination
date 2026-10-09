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
    /**
     * Why the model's steps or blocks weren't used (out of range, not adding up), so it's said on
     * the task: its estimate is planned whole instead. Null when they were, or it gave none; the
     * rules never drop a plan.
     */
    val dropped: String? = null,
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
    // None, or one of content that has since changed (new instructions, a new email in the thread):
    // what the source says, until it's enriched again. Steps it gave were of what the task said
    // then, so they go too, and the task's own estimate is planned meanwhile; the source's own stay.
    val e = Enrichments.current(this)
        ?: return copy(
            kind = base.kind,
            dueAt = base.dueAt,
            availableFrom = base.availableFrom,
            aiEffortMin = null,
            subSteps = if (stepsFromEnrichment()) emptyList() else subSteps,
        )
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
        // An email the model has read (it gives a kind): its start replaces the rules' guess, as its
        // deadline does. Otherwise the later of the two.
        availableFrom = if (source == Source.Gmail && e.kind != null) from else listOfNotNull(base.availableFrom, from).maxOrNull(),
        // This enrichment's estimate, or none: an older one's doesn't outlive it.
        aiEffortMin = e.effortMin,
        subSteps = if (subSteps.isEmpty()) e.subSteps.orEmpty() else subSteps,
    )
}

/**
 * The task with a fresh enrichment: steps an earlier enrichment gave are replaced by the new ones,
 * keeping those already done; steps of the source's own are kept.
 */
/** Whether the task's steps are the ones its enrichment gave, not the source's own. */
private fun TaskItem.stepsFromEnrichment(): Boolean = subSteps.isNotEmpty() && enrichment?.subSteps?.map { it.title } == subSteps.map { it.title }

fun TaskItem.withEnrichment(new: Enrichment): TaskItem {
    val fromEnrichment = stepsFromEnrichment()
    // The steps as they were, by name: each new step takes the marks (done, and ticked by hand) of
    // the old one at the same place among its name's (the second "Apply" the second's), so two
    // steps of one name aren't both ticked by one, and a mark stays on the block it was made on.
    val before = subSteps.groupBy { it.title }
    val base = if (fromEnrichment) copy(subSteps = emptyList()) else this
    val next = base.copy(enrichment = new).enriched()
    val seen = HashMap<String, Int>()
    return next.copy(
        subSteps = next.subSteps.map { step ->
            val place = seen.merge(step.title, 1, Int::plus)!! - 1
            val old = before[step.title]?.getOrNull(place)
            if (old?.done == true) step.copy(done = true, byHand = old.byHand, timedMin = old.timedMin) else step
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
        // Its text not read yet (more new emails than one read takes), or not as far as reads now
        // go (cut by an older, lower limit): asked about once it is (GmailThreads.textRead).
        task.source == Source.Gmail && !GmailThreads.textRead(task) -> null
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

    /**
     * Whether [task] needs enriching again: never done, changed since, done by the rules when the
     * model is now on, or done by the model when it's now switched off ([modelOff]: what it read goes
     * with it, and the rules' reading stands instead).
     */
    fun stale(task: TaskItem, modelOn: Boolean, rules: String, modelOff: Boolean = false): Boolean {
        val e = task.enrichment ?: return true
        return e.inputHash != inputHash(task) || (modelOn && e.by == rules) || (modelOff && e.by != rules)
    }
}
