package com.thomaswcode.decrastination.core

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * Where a task comes from. Each source is the only judge of whether its tasks are done; [Own] is
 * you ([OwnTaskRules]), with nothing to read: [synced] says which are read.
 */
@Serializable
enum class Source(val label: String, val synced: Boolean = true) {
    Teams("Teams"),
    PowerPlanner("Power Planner"),
    Anki("Anki"),
    Gmail("Gmail"),

    /** Your own tasks, made in the app (Q28). */
    Own("Yours", synced = false),
    ;

    companion object {
        /** The sources the app reads, each with its own status. */
        val SYNCED: List<Source> = entries.filter { it.synced }
    }
}

/**
 * What sort of work a task is (docs/spec/planner.md). The planner orders a bucket by it, and calibration
 * learns an effort multiplier and a safety margin per kind.
 */
@Serializable
enum class Kind(val label: String, val defaultEffortMin: Int) {
    Homework("Homework", 40),
    Revision("Revision", 30),
    Admin("Admin", 15),

    /** Something that happens at a time (a call, an open day): a reminder, not work to plan. */
    Event("Event", 0),

    /** An email that only needs reading and archiving. */
    Info("Info", 2),
}

@Serializable
enum class Status {
    Open,

    /** The source says it's done: handed in, ticked, archived, or the cards studied. */
    Done,

    /** A task this app derived from a source's counts (the Anki quota) that stopped applying before it was done. */
    Missed,
}

/** One ordered part of a task, from the source or the enrichment, with its own estimate. */
@Serializable
data class SubStep(
    val title: String,
    val minutes: Int,
    val done: Boolean = false,
    /** The vocabulary sections ("1.2") this step learns: left out of the plan where Anki deck tasks hold them. */
    val ankiSections: List<String> = emptyList(),
    /** Not to be started before then: a block whose window opens on a date (an application that opens on 30 Oct). */
    val from: Long? = null,
    /** Its own deadline, before the task's: one of several dated items in one email or planner item. */
    val dueAt: Long? = null,
    /** Ticked off by hand in Tasks, not by keeping its slot or a photo check: done, but earning nothing. */
    val byHand: Boolean = false,
    /** Ticked by hand: the task's timed minutes not yet taken up by its other steps that it kept then, worked on it before the tick. */
    val timedMin: Int = 0,
    /** Where it can be done (PL-15): "home" or "anywhere", as Claude read it; null, as its task's. */
    val where: String? = null,
    /**
     * Its id, made when it's first stored ([withIds]: the tasks' store gives one to each that hasn't)
     * and kept from then on, so a tick follows it through a re-lay or a reading that reorders
     * ([carryMarks]); empty till then. Slots still name it by its place.
     */
    val id: String = "",
    /** Where it came from: what tells the reading's steps from the rest. */
    val origin: StepOrigin = StepOrigin.Source,
    /**
     * It doesn't need the steps before it done first, as the reading or Felix marked it (D-109):
     * separate questions, say, not a draft after its plan. A day's slots may lay it ahead of them.
     */
    val anyOrder: Boolean = false,
)

/**
 * Where a step came from: its task's source (Anki's cards), a reading by Claude or by the rules, your
 * instruction or Felix's card, or a task of your own.
 */
@Serializable
enum class StepOrigin { Source, Reading, Rules, Yours, Felix, OwnTask }

/** A step's new id: eight random hex characters. */
fun newStepId(): String = "%08x".format(Random.nextInt())

/** [this] with each step that has no id, or one a step before it has, given a new one. */
fun List<SubStep>.withIds(): List<SubStep> {
    val seen = HashSet<String>()
    if (all { it.id.isNotEmpty() && seen.add(it.id) }) return this
    seen.clear()
    return map { if (it.id.isEmpty() || !seen.add(it.id)) it.copy(id = newStepId()).also { s -> seen += s.id } else it }
}

/**
 * One obligation from one source, in the shape every other part of the app works with
 * (docs/spec/sources.md). Times are epoch milliseconds.
 *
 * The source owns [title], [detail], [className], [dueAt], [availableFrom], [sourceEffortMin] and
 * [sourceProgress], and a sync overwrites them. Everything else is this app's own bookkeeping
 * and survives syncs: when the task was first and last seen, the minutes worked on it in kept
 * slots, the sub-steps and estimates the enrichment gave it, and your own estimate.
 */
@Serializable
data class TaskItem(
    /** `<source>:<sourceId>`, stable across syncs. */
    val id: String,
    val source: Source,
    /** The source's own id: the Teams GUID, the Power Planner identifier, the Gmail thread id, an Anki deck. */
    val sourceId: String,
    val title: String,
    val detail: String = "",
    val className: String? = null,
    val dueAt: Long? = null,
    /** Hidden from the plan until then (a "wait until" email, an event's day-before reminder). */
    val availableFrom: Long? = null,
    val kind: Kind,
    /** An estimate the source itself implies (Anki's card counts), or the rules' guess. */
    val sourceEffortMin: Int? = null,
    /** The enrichment's estimate. */
    val aiEffortMin: Int? = null,
    /** How far the source says it is (Power Planner's percent complete), 0 to 1. */
    val sourceProgress: Double = 0.0,
    /**
     * How far it was when this app first saw it: what was left then is the work its completion
     * earns time for, however its progress moved on the way. Null for a task saved before this was kept.
     */
    val firstProgress: Double? = null,
    /**
     * The most the source's estimate has been: a count that shrinks as it's worked through (an
     * Anki deck's cards) ends near nothing, and its completion is for all of it.
     */
    val peakEffortMin: Int? = null,
    /** Minutes worked on it: its kept slots' (LR-09). */
    val workedMin: Int = 0,
    /**
     * Minutes of it a photo check found done: they come off what's left like [workedMin], but
     * weren't timed, so they're kept apart from it and out of what the app learns from timing.
     */
    val photoMin: Int = 0,

    /** The photo checks counted in [photoMin], by id, with the minutes each was given, so each is counted once. */
    val photosCounted: Map<String, Int> = emptyMap(),
    /** Your answers to the model's questions about it (AI-10), oldest first: read with it from then on. */
    val answers: List<String> = emptyList(),
    /** A photo of its work (AI-23), read with it from then on: kept here, not in [extra], so a sync keeps it. */
    val photo: TaskPhoto? = null,
    val subSteps: List<SubStep> = emptyList(),
    /**
     * Of [workedMin] and [photoMin], the minutes the source's own steps already leave out: an Anki
     * deck's cards studied in its kept slots are gone from the steps its next read gives, so those
     * minutes aren't taken off what's left of them again. Set when the source's steps replace the
     * ones kept here.
     */
    val stepsWorkedMin: Int = 0,
    /** At most this many of its sub-steps can be done in a day: an Anki deck releases 20 new cards a day. */
    val stepsPerDay: Int? = null,
    /**
     * Its work can't start before then, though it's due as ever: an Anki deck whose new cards for
     * today are studied releases more only after Anki's next 04:00.
     */
    val notBefore: Long? = null,
    val status: Status = Status.Open,
    /**
     * Derived by this app from a source's counts (the daily Anki quota, a deck homework names)
     * rather than listed by the source: done only when the counts say so, and missed, not done,
     * if it stops applying first.
     */
    val derived: Boolean = false,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val doneAt: Long? = null,
    /** Source-specific values: Gmail's sender and labels, an Anki deck id, a Teams tab. */
    val extra: Map<String, String> = emptyMap(),
    /** What the enrichment said about it, laid over the source's values at every merge. */
    val enrichment: Enrichment? = null,
    /** The source's own kind, deadline and start, before the enrichment's overlay; null in one kept from before source values were kept. */
    val sourceValues: SourceValues? = null,
    /**
     * Your instructions' say about it, each value with who set it ([Mine], [InstructionRules]), laid
     * over the source's and the enrichment's at every merge: its minutes beat every other estimate;
     * its steps (any task's but Anki's) the source's and the reading's ([enriched]), their
     * ticks kept by name; its name its source's, which is kept ([SourceValues.title]).
     */
    val mine: Mine = Mine(),
) {
    val isOpen: Boolean get() = status == Status.Open

    /**
     * Done once you tick its piece off ([EXTRA_TICK_DONE]), as one of your own is, though a source
     * lists it: a returned assignment's feedback to read (NF-06), which no source can say is read.
     */
    val tickDone: Boolean get() = extra[EXTRA_TICK_DONE] == "true"

    /**
     * Not work to plan: one you've said isn't a task, or is the same as another. Every starred email
     * is a task (D-10), what to do about an event among them; where that isn't clear, Claude's
     * reading asks (AI-10).
     */
    val hidden: Boolean get() = mine.notATask != null || mine.sameAs != null

    /**
     * Its real deadline, where it has one (PL-14): yours; an email's own words as Claude quoted them
     * (AI-12); else its source's own. A date the app or Claude set (a guess from an email's words, a
     * test date Claude read before its source's deadline) isn't one.
     */
    val realDeadline: Long?
        get() = mine.dueAt?.value ?: when (source) {
            // The rules' date is a guess from its words, and Claude's is only a quote where it says so:
            // a reading of the email as it is now, not one its new content has made out of date.
            Source.Gmail -> Enrichments.current(this)?.takeIf { it.deadlineQuoted && it.deadline == dueAt }?.deadline
                // An invite's start is the sender's own time (DX-02).
                ?: invite?.start?.takeIf { it == dueAt }
            // A deck for homework has its assignments' deadline, not one of its own: their earliest real
            // one, as read with them (the planner reads them through it), as its date may be a test
            // date Claude read, a plan.
            Source.Anki if extra[AnkiRules.EXTRA_FOR] != null -> extra[AnkiRules.EXTRA_REAL]?.toLongOrNull()
            // Your own task's date is yours; a source's, its own (one kept from before source values were kept has none).
            else -> if (sourceValues != null) sourceValues.dueAt else dueAt
        }

    /**
     * The deadline in force is a real one ([realDeadline]). Otherwise it's a plan: it orders the work,
     * it's never overdue, and once it has passed the real deadline behind it holds, or with none the
     * work is planned again ([Planner]).
     */
    val hardDeadline: Boolean get() = dueAt != null && dueAt == realDeadline

    /**
     * The deadline in force at [now] (PL-14), as the plan holds it: [dueAt] where it's the real one or
     * a plan still to come; a plan gone by gives way to the real deadline behind it; with none, null,
     * and the work is undated again.
     */
    fun deadlineAt(now: Long): Long? = dueAt?.takeIf { it == realDeadline || it >= now } ?: realDeadline

    /** Whether [deadlineAt] is a real deadline, which can be missed. */
    fun hardAt(now: Long): Boolean = deadlineAt(now)?.let { it == realDeadline } == true

    /**
     * Minutes of blocks you ticked off by hand in Tasks: done, but neither timed nor checked, so
     * they earn no free time when the task completes and teach the calibration nothing. From the
     * blocks themselves, so a block re-estimated since counts at its minutes now.
     */
    val handMin: Int get() = subSteps.filter { it.done && it.byHand }.sumOf { it.minutes }

    /** Your estimate, else the enrichment's, else the source's or rules', else the kind's default. */
    val effortMin: Int get() = mine.minutes?.minutes ?: aiEffortMin ?: sourceEffortMin ?: kind.defaultEffortMin

    /** The estimate as the app has it, without yours: what free time for finishing it goes by, at most. */
    val appEffortMin: Int get() = aiEffortMin ?: sourceEffortMin ?: kind.defaultEffortMin

    /**
     * Its minutes are yours (an instruction's, or a task of your own): planned as you gave them,
     * not scaled by what the app has learned of its own estimates.
     */
    val yourMinutes: Boolean get() = mine.minutes != null || source == Source.Own

    /**
     * Your minutes are the app's own estimate at your pace, as Felix worked them out to size its
     * steps (D-63), not your own reckoning: planned as given, like any of yours. Only
     * where the app made the estimate: never a task of your own.
     */
    val pacedMinutes: Boolean get() = mine.minutes?.paced != null && source != Source.Own

    /**
     * What's finished teaches the pace, as the app's own tasks do, measured against the estimate
     * Felix worked from ([Paced.from]): your minutes [pacedMinutes], of the task as it was then.
     * Read again for newer prompts alone (or *Read again*), its content's the same
     * and the estimate he worked from stands, whatever the new one; once its content has changed
     * (new instructions, a new email), the work may have too, and it's as your own.
     */
    val pacedTeaches: Boolean get() = pacedMinutes && mine.minutes?.paced?.contentHash.let { it != null && it == Enrichments.inputHash(this) }

    /**
     * Its steps are yours: typed into a task of your own, or given any task by you or Felix (an
     * instruction): planned as you gave them, at their own minutes (not stretched by the pace learned
     * of the app's estimates) and however long, never cut into sittings (D-34).
     */
    val yourSteps: Boolean get() = mine.steps != null || source == Source.Own

    /**
     * What each of its steps' minutes counts as: where you gave the whole task's minutes, its steps
     * are scaled to add up to them.
     */
    val stepScale: Double
        get() {
            val yours = mine.minutes?.minutes ?: return 1.0
            val steps = subSteps.sumOf { it.minutes }
            return if (steps > 0) yours.toDouble() / steps else 1.0
        }

    /**
     * Each step's minutes as the plan takes them, by [multiplier] (the calibration's, for the app's
     * estimate). Where you gave the whole task's minutes, they're shared out in whole minutes that
     * add up to exactly yours, the odd minutes going to the steps with most left over (each at
     * least one, so fewer minutes than steps comes to a minute each).
     */
    fun plannedStepMinutes(multiplier: Double): List<Int> {
        val steps = subSteps.map { it.minutes }
        val yours = mine.minutes?.minutes
        if (yours == null || steps.sum() <= 0) return steps.map { (it * multiplier).roundToInt().coerceAtLeast(1) }
        return shareOut(yours, steps)
    }

    fun isAvailable(now: Long): Boolean = availableFrom == null || availableFrom <= now

    companion object {
        fun id(source: Source, sourceId: String): String = "${source.name.lowercase()}:$sourceId"

        /** The source task [id] is from, by its prefix ([id]); null for one that names none. */
        fun sourceOf(id: String): Source? = Source.entries.firstOrNull { id.startsWith(it.name.lowercase() + ":") }

        /**
         * Whether task [id], not in the list, is gone, for what was kept on it since [since]: one of your own
         * at once (deleted); a source's once that source has been read since ([readAt], its last good read), so one
         * not listed only because its source hasn't been read yet (a backup restored before the first sync) is waited for.
         */
        fun goneSince(id: String, since: Long, readAt: (Source) -> Long?): Boolean = when (val source = sourceOf(id)) {
            null, Source.Own -> true
            else -> readAt(source)?.let { it > since } == true
        }

        /** Marks a task done by its tick ([tickDone]). */
        const val EXTRA_TICK_DONE = "tickDone"
    }
}

/** Whether it was finished more than [Merge.KEEP_FINISHED_MS] ago: kept then as its record alone ([trimmed]). */
fun TaskItem.longFinished(now: Long): Boolean = !isOpen && now - (doneAt ?: lastSeenAt) >= Merge.KEEP_FINISHED_MS

/**
 * [this], long finished, as it's kept (C-Q3): its record (who it is, its title, class, kind, dates and
 * status, its estimates and minutes worked, its steps with their ticks, your values and its source's)
 * without its text, its reading, your answers, its photo or its source's [bulky] extras (an email's
 * text, messages and ids). What the reading laid stays laid ([enriched]). A source listing it again
 * brings its text back, and it's read again; a task of your own is kept so too, and only a delete
 * removes one.
 */
fun TaskItem.trimmed(bulky: Set<String>): TaskItem = copy(detail = "", enrichment = null, answers = emptyList(), photo = null, extra = extra - bulky)

/**
 * [total] minutes shared over [parts] in proportion, in whole minutes that add up to exactly
 * [total]: the odd minutes go to the parts with most left over. Each gets at least one, so a total
 * smaller than the parts' number would come to more than it: it's refused where it's given
 * ([OwnTaskRules.minutesFor]). Your minutes over a task's steps.
 */
fun shareOut(total: Int, parts: List<Int>): List<Int> {
    val sum = parts.sum()
    if (sum <= 0) return parts.map { 1 }
    val exact = parts.map { it.toDouble() * total / sum }
    val whole = exact.map { floor(it).toInt() }.toMutableList()
    var odd = total - whole.sum()
    for (i in exact.indices.sortedByDescending { exact[it] - whole[it] }) {
        if (odd <= 0) break
        whole[i]++
        odd--
    }
    return whole.map { it.coerceAtLeast(1) }
}

/**
 * What a source said about a task's kind, deadline, start and name, kept so an overlay can be redone.
 * [title] is null for one kept from before names could be given: its [TaskItem.title] is it.
 */
@Serializable
data class SourceValues(val kind: Kind, val dueAt: Long? = null, val availableFrom: Long? = null, val title: String? = null)

/**
 * A source's view of one task, as read by a sync. [Merge] turns these into [TaskItem]s, keeping
 * the app's own bookkeeping.
 */
data class Fetched(
    val sourceId: String,
    val title: String,
    val kind: Kind,
    val detail: String = "",
    val className: String? = null,
    val dueAt: Long? = null,
    val availableFrom: Long? = null,
    val sourceEffortMin: Int? = null,
    val sourceProgress: Double = 0.0,
    /** The source lists it but says it's finished (Power Planner at 100 %, an Anki deck studied). */
    val done: Boolean = false,
    val derived: Boolean = false,
    val subSteps: List<SubStep>? = null,
    val stepsPerDay: Int? = null,
    val notBefore: Long? = null,
    val extra: Map<String, String> = emptyMap(),
)

/** A photo of a task's work (AI-23): its file, the app's own ([path]), [hash] its SHA-256's first bytes, for the task's reading, and when. */
@Serializable
data class TaskPhoto(val path: String, val hash: String, val at: Long)
