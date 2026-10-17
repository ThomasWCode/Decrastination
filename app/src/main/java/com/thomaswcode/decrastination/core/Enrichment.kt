package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * What the enrichment (docs/spec/readings.md) says about a task beyond what its source does: an
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
    /**
     * Whether an email's [deadline] is its own words (AI-12): only then is it a deadline that can be
     * missed (PL-14); a day it only suggests, or one the model guessed, is a plan. False for the rules'.
     */
    val deadlineQuoted: Boolean = false,
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
    /** The one question that would settle what the text can't (AI-10): put to you by Felix. Rarely any. */
    val question: String? = null,
    /** A line for each step whose dates couldn't be, kept with none (AI-01): said on the task's sheet. */
    val adjusted: List<String> = emptyList(),
    /** The phrase in the text that gave the reading (AI-09), quoted: under its due date on the sheet. */
    val reason: String? = null,
    /**
     * What a long text asks of the student, in a few lines, from all of it (D-93): what Felix is
     * told in place of a text too long for his room. Null for a short text, which he's told whole.
     */
    val summary: String? = null,
    /** Read by the model with a photo of the work (AI-23): only then is its estimate "From your photo". The rules' standing in for it never is. */
    val fromPhoto: Boolean = false,
    /**
     * The prompts it was read by ([com.thomaswcode.decrastination.enrich.Prompts.VERSION], AC-06):
     * the model's reading is made again once they've changed. 1 for one stored before there was one.
     */
    val promptVersion: Int = 1,
)

/**
 * The task with its enrichment laid over what the source says: the source's deadline wins, though
 * a test before it brings it forward and an email's own deadline counts where the source has none;
 * the later of the two start dates; the enrichment's estimate; an email's kind; and the steps,
 * where the source gives none.
 */
fun TaskItem.enriched(): TaskItem = enrichedOnly().let { t ->
    // Your instructions over both: the deadline you gave; your name for it and your steps (16 Oct),
    // their ticks kept by name. A start you gave isn't laid here: the planner places the work from
    // then on ([TaskItem.userFrom]), so it's seen coming.
    t.copy(
        dueAt = userDueAt ?: t.dueAt,
        title = userTitle ?: sourceValues?.title ?: t.title,
        subSteps = userSteps?.let { mine -> carryMarks(mine.map { it.asSubStep() }, t.subSteps) } ?: t.subSteps,
    )
}

/**
 * [steps], each with the marks (done, and ticked by hand) of the one of [before] at the same place
 * among its name's (the second "Apply" the second's): two steps of one name aren't both ticked by
 * one, and a mark stays on the step it was made on.
 */
internal fun carryMarks(steps: List<SubStep>, before: List<SubStep>): List<SubStep> {
    val was = before.groupBy { it.title }
    val seen = HashMap<String, Int>()
    return steps.map { step ->
        val place = seen.merge(step.title, 1, Int::plus)!! - 1
        val old = was[step.title]?.getOrNull(place)
        if (old?.done == true) step.copy(done = true, byHand = old.byHand, timedMin = old.timedMin) else step
    }
}

/** A step you gave as the task keeps it: where it's done as the planner reads it (PL-15). */
fun OwnStep.asSubStep(): SubStep = SubStep(title.trim().take(OwnTasks.MAX_TITLE), minutes, from = from, dueAt = dueAt, where = Where.of(where)?.name?.lowercase())

private fun TaskItem.enrichedOnly(): TaskItem {
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
    // An invite's time is exact (DX-02): the email is that event, unless Claude read work to do by a
    // date the email gives (a reply by Friday), when the invite stays on its sheet.
    val invited = invite?.takeIf { !(e.kind == Kind.Admin && e.deadlineQuoted && e.deadline != null) }
    val due = when {
        invited != null -> invited.start
        // An email has no deadline of its own: what the rules found is a guess from its words
        // ("appointment"), and the enrichment's reading of it wins where it gives one.
        source == Source.Gmail -> e.deadline ?: base.dueAt
        e.testDate != null && (base.dueAt == null || e.testDate < base.dueAt) -> e.testDate
        else -> base.dueAt ?: e.deadline
    }
    // A start after the deadline would hide the work until it's overdue: it's dropped.
    val from = e.actionableFrom?.takeIf { due == null || it <= due }
    return copy(
        // A reading from before starred-only reading may say an email is only to read; starred, it
        // never is (AI-02): the rules' kind stands until it's read again ([Enrichments.stale]).
        kind = when {
            // Starred, always something to do (16 Oct): an event's email too, what it asks before it.
            source == Source.Gmail -> Kind.Admin
            else -> base.kind
        },
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
/** This task with its enrichment forgotten, and the steps it gave with it (the source's own stay): to be asked about afresh. */
fun TaskItem.withoutEnrichment(): TaskItem = copy(enrichment = null, subSteps = if (stepsFromEnrichment()) emptyList() else subSteps)

private fun TaskItem.stepsFromEnrichment(): Boolean = subSteps.isNotEmpty() && enrichment?.subSteps?.map { it.title } == subSteps.map { it.title }

/**
 * Whether some of the steps its enrichment gave are done: newer prompts could give its work as other
 * steps (fewer, longer, since 3.1.1), and a mark goes only to a step of its name ([carryMarks]), so
 * work done would be planned again (#47's review). Not read again for newer prompts alone, then:
 * only when its text changes or you ask, when the steps done are said to be ([com.thomaswcode.decrastination.enrich.Prompts.describe]).
 */
private fun TaskItem.partDone(): Boolean = stepsFromEnrichment() && subSteps.any { it.done }

fun TaskItem.withEnrichment(new: Enrichment): TaskItem {
    val fromEnrichment = stepsFromEnrichment()
    // The steps as they were, by name: each new step takes the marks of the old one ([carryMarks]).
    val base = if (fromEnrichment) copy(subSteps = emptyList()) else this
    val next = base.copy(enrichment = new).enriched()
    return next.copy(subSteps = carryMarks(next.subSteps, subSteps))
}

/** What there is to enrich, and how to tell when it has changed. */
object Enrichments {

    /** The three jobs of docs/spec/readings.md that run on tasks. */
    enum class Job { Email, Assignment, Effort }

    /** What [task] needs, or null: done, derived (the Anki quota and decks), or an event. */
    fun jobFor(task: TaskItem): Job? = when {
        !task.isOpen || task.derived -> null
        // You've said it isn't a task: nothing to read it for. Nor feedback to read (NF-06): ten minutes, as it is.
        task.userNotATask || task.tickDone -> null
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
     * An email's attachments only where it has some (DX-01), so a task without any keeps the hash it had.
     */
    fun inputHash(task: TaskItem): String {
        val text = (listOf(
            task.source.name,
            // The source's name, not yours (16 Oct): renaming a task isn't new content to read.
            task.sourceValues?.title ?: task.title,
            task.detail,
            task.className.orEmpty(),
            // A source with no deadline has none here, whatever the overlay says; only a task kept
            // from before Phase 4 has no source values to read.
            (if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt)?.toString().orEmpty(),
            task.extra["from"].orEmpty(),
            task.extra["received"].orEmpty(),
            // Your answers to its questions (AI-10): only where there are some, so no other task's changes.
        ) + listOfNotNull(task.extra[GmailThreads.EXTRA_ATTACHMENTS], task.extra[com.thomaswcode.decrastination.sources.teams.TeamsRows.EXTRA_LINKS], task.answers.takeIf { it.isNotEmpty() }?.joinToString("\n"), task.photo?.hash)).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** [task]'s enrichment if it's of the task as it is now; one of content since changed, null. */
    fun current(task: TaskItem): Enrichment? = task.enrichment?.takeIf { it.inputHash == inputHash(task) }

    /**
     * Whether [task] needs enriching again: never done, changed since, done by the rules when the
     * model is now on, or done by the model when it's now switched off ([modelOff]: what it read goes
     * with it, and the rules' reading stands instead); or an email read, before starred-only reading,
     * as only to read (AI-02), which a starred one never is; or, the model on, an open task the model
     * read by older prompts (AC-06), unless some of the steps it gave are done ([partDone]). Resting
     * or refused, the model's older reading stands.
     */
    fun stale(task: TaskItem, modelOn: Boolean, rules: String, modelOff: Boolean = false): Boolean {
        val e = task.enrichment ?: return true
        return e.inputHash != inputHash(task) || (modelOn && e.by == rules) || (modelOff && e.by != rules) ||
            (task.source == Source.Gmail && e.kind == Kind.Info) ||
            (modelOn && e.by != rules && task.isOpen && e.promptVersion < com.thomaswcode.decrastination.enrich.Prompts.VERSION && !task.partDone())
    }

    /**
     * Whether [task]'s reading is the model's, of its text as it is now, stale only as older prompts
     * made it (AC-06): kept as it is unless the model reads it again, never the rules' in its place.
     */
    fun staleOnlyByVersion(task: TaskItem, rules: String): Boolean {
        val e = task.enrichment ?: return false
        return e.by != rules && e.inputHash == inputHash(task) && e.promptVersion < com.thomaswcode.decrastination.enrich.Prompts.VERSION &&
            !(task.source == Source.Gmail && e.kind == Kind.Info) && !task.partDone()
    }

    /**
     * The order tasks are read in (AC-06): those never read first, then those read by the oldest
     * prompts, each soonest due first, so a run's calls go where they're most out of date.
     */
    val NEED: Comparator<TaskItem> = compareBy<TaskItem>({ it.enrichment?.promptVersion ?: 0 }, { it.dueAt ?: Long.MAX_VALUE })
}
