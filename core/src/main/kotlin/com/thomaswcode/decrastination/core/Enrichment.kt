package com.thomaswcode.decrastination.core

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
     * The prompts it was read by ([com.thomaswcode.decrastination.enrich.ReadingPrompts.VERSION], AC-06):
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
    // Your instructions over both: the deadline you gave; your name for it and your steps,
    // their ticks kept by name. A start you gave isn't laid here: the planner places the work from
    // then on ([TaskItem.mine.from?.value]), so it's seen coming.
    t.copy(
        dueAt = mine.dueAt?.value ?: t.dueAt,
        title = mine.title?.value ?: sourceValues?.title ?: t.title,
        subSteps = mine.steps?.let { yours -> carryMarks(yours.steps.map { it.asSubStep(yours.by) }, t.subSteps) } ?: t.subSteps,
    )
}

/**
 * [steps], each with the marks (done, and ticked by hand) of the one of [before] with its id; one
 * whose id isn't there (a reading made again, a step new to the list) takes those of the one at the
 * same place among its name's, of those no id matched (the second "Apply" the second's): two steps
 * of one name aren't both ticked by one, and a mark stays on the step it was made on. Matched so, one
 * with no id of its own (a reading's steps come without) takes that one's: the same step, read again.
 */
fun carryMarks(steps: List<SubStep>, before: List<SubStep>): List<SubStep> {
    val byId = before.filter { it.id.isNotEmpty() }.associateBy { it.id }
    val matched = steps.mapNotNull { s -> s.id.takeIf { it.isNotEmpty() && it in byId } }.toSet()
    val was = before.filter { it.id !in matched }.groupBy { it.title }
    val seen = HashMap<String, Int>()
    return steps.map { step ->
        val old = byId[step.id]?.takeIf { step.id in matched } ?: run {
            val place = seen.merge(step.title, 1, Int::plus)!! - 1
            was[step.title]?.getOrNull(place)
        }
        val same = if (step.id.isEmpty() && old != null) step.copy(id = old.id) else step
        if (old?.done == true) same.copy(done = true, byHand = old.byHand, timedMin = old.timedMin) else same
    }
}

/** A step you or Felix ([by]) gave, as the task keeps it: where it's done as the planner reads it (PL-15), with its id. */
fun OwnStep.asSubStep(by: By = By.You): SubStep =
    SubStep(title.trim().take(OwnTaskRules.MAX_TITLE), minutes, from = from, dueAt = dueAt, where = Where.of(where)?.name?.lowercase(), id = id, origin = if (by == By.Felix) StepOrigin.Felix else StepOrigin.Yours, anyOrder = anyOrder)

private fun TaskItem.enrichedOnly(): TaskItem {
    // Finished, its reading let go a fortnight on ([trimmed], C-Q3): what the reading laid stays on its
    // record (its estimate, its steps and their ticks), as nothing plans it. Listed again as open, the
    // merge lays it as one seen for the first time.
    if (enrichment == null && !isOpen) return this
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
            // Starred, always something to do: an event's email too, what it asks before it.
            source == Source.Gmail -> Kind.Admin
            else -> base.kind
        },
        dueAt = due,
        // An email the model has read (it gives a kind): its start replaces the rules' guess, as its
        // deadline does. Otherwise the later of the two.
        availableFrom = if (source == Source.Gmail && e.kind != null) from else listOfNotNull(base.availableFrom, from).maxOrNull(),
        // This enrichment's estimate, or none: an older one's doesn't outlive it.
        aiEffortMin = e.effortMin,
        // Laid as the reading's: the rules' where they made it ([StepOrigin.Rules]), else Claude's.
        subSteps = if (subSteps.isEmpty()) e.subSteps.orEmpty().map { if (it.origin == StepOrigin.Rules) it else it.copy(origin = StepOrigin.Reading) } else subSteps,
    )
}

/** This task with its enrichment forgotten, and the steps it gave with it (the source's own stay): to be asked about afresh. */
fun TaskItem.withoutEnrichment(): TaskItem = copy(enrichment = null, subSteps = if (stepsFromEnrichment()) emptyList() else subSteps)

/** Whether the task's steps are the ones its enrichment gave, not the source's own. */
private fun TaskItem.stepsFromEnrichment(): Boolean = subSteps.isNotEmpty() && subSteps.all { it.origin == StepOrigin.Reading || it.origin == StepOrigin.Rules }

/**
 * Whether some of the steps its enrichment gave are done: newer prompts could give its work as other
 * steps (fewer, longer), and a mark goes only to a step of its name ([carryMarks]), so
 * work done would be planned again. Not read again for newer prompts alone, then:
 * only when its text changes or you ask, when the steps done are said to be ([com.thomaswcode.decrastination.enrich.ReadingPrompts.describe]).
 */
private fun TaskItem.partDone(): Boolean = stepsFromEnrichment() && subSteps.any { it.done }

/**
 * The task with a fresh enrichment: steps an earlier enrichment gave are replaced by the new ones,
 * keeping those already done; steps of the source's own are kept.
 */
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

    /**
     * The extras' keys the hash reads beside the task's own fields, as the sources write them: an
     * email's attachments (GmailThreads), an assignment's links (TeamsRows).
     */
    const val EXTRA_ATTACHMENTS = "attachments"
    const val EXTRA_LINKS = "links"

    /**
     * What [task] needs, or null: done, derived (the Anki quota and decks), or an event. [textRead]
     * says whether an email's text has been read as far as reads now go (GmailThreads.textRead).
     */
    fun jobFor(task: TaskItem, textRead: (TaskItem) -> Boolean): Job? = when {
        !task.isOpen || task.derived -> null
        // You've said it isn't a task: nothing to read it for. Nor feedback to read (NF-06): ten minutes, as it is.
        task.mine.notATask != null || task.tickDone -> null
        // Its text not read yet (more new emails than one read takes), or not as far as reads now
        // go (cut by an older, lower limit): asked about once it is.
        task.source == Source.Gmail && !textRead(task) -> null
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
            // The source's name, not yours: renaming a task isn't new content to read.
            task.sourceValues?.title ?: task.title,
            task.detail,
            task.className.orEmpty(),
            // A source with no deadline has none here, whatever the overlay says; only a task kept
            // from before source values were kept has none to read.
            (if (task.sourceValues != null) task.sourceValues.dueAt else task.dueAt)?.toString().orEmpty(),
            task.extra["from"].orEmpty(),
            task.extra["received"].orEmpty(),
            // Your answers to its questions (AI-10): only where there are some, so no other task's changes.
        ) + listOfNotNull(task.extra[EXTRA_ATTACHMENTS], task.extra[EXTRA_LINKS], task.answers.takeIf { it.isNotEmpty() }?.joinToString("\n"), task.photo?.hash)).joinToString("\n")
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
     * read by prompts older than [version] (AC-06: the prompts' now, ReadingPrompts.VERSION), unless some of
     * the steps it gave are done ([partDone]). Resting or refused, the model's older reading stands.
     */
    fun stale(task: TaskItem, modelOn: Boolean, rules: String, version: Int, modelOff: Boolean = false): Boolean {
        val e = task.enrichment ?: return true
        return e.inputHash != inputHash(task) || (modelOn && e.by == rules) || (modelOff && e.by != rules) ||
            (task.source == Source.Gmail && e.kind == Kind.Info) ||
            (modelOn && e.by != rules && task.isOpen && e.promptVersion < version && !task.partDone())
    }

    /**
     * Whether [task]'s reading is the model's, of its text as it is now, stale only as prompts older
     * than [version] made it (AC-06): kept as it is unless the model reads it again, never the rules'
     * in its place.
     */
    fun staleOnlyByVersion(task: TaskItem, rules: String, version: Int): Boolean {
        val e = task.enrichment ?: return false
        return e.by != rules && e.inputHash == inputHash(task) && e.promptVersion < version &&
            !(task.source == Source.Gmail && e.kind == Kind.Info) && !task.partDone()
    }

    /**
     * The order tasks are read in (AC-06): those never read first, then those read by the oldest
     * prompts, each soonest due first, so a run's calls go where they're most out of date.
     */
    val NEED: Comparator<TaskItem> = compareBy<TaskItem>({ it.enrichment?.promptVersion ?: 0 }, { it.dueAt ?: Long.MAX_VALUE })
}
