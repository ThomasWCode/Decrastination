package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * A task of your own as you give it (2.0.0, Q28), with the form or by asking Claude: what a source
 * would say of a task. [startFrom]: it can't be started before then, though it's due as ever, and
 * is planned from then on. [steps]: its parts, each with its minutes (and its own dates, where it
 * has them); with steps, its minutes are theirs together.
 */
@Serializable
data class OwnTask(
    val title: String,
    val notes: String = "",
    val kind: Kind = Kind.Homework,
    val className: String? = null,
    val dueAt: Long? = null,
    val startFrom: Long? = null,
    val minutes: Int = Kind.Homework.defaultEffortMin,
    val steps: List<OwnStep> = emptyList(),
)

@Serializable
data class OwnStep(val title: String, val minutes: Int, val from: Long? = null, val dueAt: Long? = null)

/**
 * Your own tasks (2.0.0, Q28). You're their source: what you give is kept as a source's values are
 * ([TaskItem.sourceValues]), so an instruction laid over one can be taken back, and only you change
 * it. Nothing reads them, so no sync touches them; one is done when you say so, which earns no free
 * time (your call, 12 Oct): focus sessions on it earn theirs. Pure.
 */
object OwnTasks {
    const val MAX_TITLE = 200
    const val MAX_NOTES = 4_000
    const val MAX_CLASS = 60
    const val MAX_STEPS = 30

    /** A minute to sixty hours: a term's project, not a year's. */
    val MINUTES = 1..60 * 60

    /**
     * The minutes [task] can be given in all: at least a minute for each of its steps, so yours,
     * shared out over them ([shareOut]), add up to exactly what you gave.
     */
    fun minutesFor(task: TaskItem): IntRange = maxOf(1, task.subSteps.size)..MINUTES.last

    /** The kinds one can be: work of three sorts, or an event (a reminder at its time, not work to plan). */
    val KINDS = listOf(Kind.Homework, Kind.Revision, Kind.Admin, Kind.Event)

    /** What's wrong with [task], in words, or null if it can be kept. */
    fun problem(task: OwnTask): String? = when {
        task.title.isBlank() -> "It needs a name"
        task.kind !in KINDS -> "It can't be of that kind"
        task.kind == Kind.Event && task.dueAt == null -> "An event needs its date and time"
        task.kind != Kind.Event && task.steps.isEmpty() && task.minutes !in MINUTES -> "Its minutes must be from 1 to ${MINUTES.last / 60} hours"
        task.steps.size > MAX_STEPS -> "At most $MAX_STEPS steps"
        task.steps.any { it.title.isBlank() || it.minutes !in MINUTES } -> "Each step needs a name and from 1 minute to ${MINUTES.last / 60} hours"
        task.steps.sumOf { it.minutes } > MINUTES.last -> "Its steps come to more than ${MINUTES.last / 60} hours"
        task.startFrom != null && task.dueAt != null && task.startFrom > task.dueAt -> "It can't start after it's due"
        task.steps.any { s -> s.from != null && s.dueAt != null && s.from > s.dueAt } -> "A step can't start after it's due"
        else -> null
    }

    /** [task] made, under [id], first seen [now]. */
    fun create(task: OwnTask, id: String, now: Long): TaskItem = apply(
        TaskItem(
            id = TaskItem.id(Source.Own, id),
            source = Source.Own,
            sourceId = id,
            title = "",
            kind = task.kind,
            firstSeenAt = now,
            lastSeenAt = now,
        ),
        task,
    ).let { it.copy(peakEffortMin = it.sourceEffortMin, firstProgress = 0.0) }

    /**
     * [old] as you've changed it to [task]. Its steps are made again where you've changed them,
     * keeping those done under the same name; the minutes worked on it stay.
     */
    fun edited(old: TaskItem, task: OwnTask): TaskItem = apply(old, task)

    /** [task] as the form shows it, to change. */
    fun draftOf(task: TaskItem): OwnTask = OwnTask(
        title = task.title,
        notes = task.detail,
        kind = task.sourceValues?.kind ?: task.kind,
        className = task.className,
        dueAt = task.sourceValues?.dueAt ?: task.dueAt,
        startFrom = task.notBefore,
        minutes = task.sourceEffortMin ?: task.kind.defaultEffortMin,
        steps = task.subSteps.map { OwnStep(it.title, it.minutes, it.from, it.dueAt) },
    )

    /**
     * [task], done, open again: its minutes worked stay. One with every step ticked has its last open
     * again: something's left, or it would be finished again at once.
     */
    fun reopened(task: TaskItem): TaskItem {
        val steps = if (task.subSteps.isNotEmpty() && task.subSteps.all { it.done }) {
            task.subSteps.mapIndexed { i, s -> if (i == task.subSteps.lastIndex) s.copy(done = false, byHand = false, timedMin = 0) else s }
        } else {
            task.subSteps
        }
        return task.copy(status = Status.Open, doneAt = null, subSteps = steps)
    }

    /**
     * [current] with what [changes] made (a due date, a start, minutes) put back as it was in [was]:
     * only a field that still holds what they set, so a later change to it, or to another field,
     * stands. A step's minutes come back only while the steps are the same ones.
     */
    fun restored(current: OwnTask, was: OwnTask, changes: List<Change>): OwnTask {
        var d = current
        changes.lastOrNull { it.type == ChangeType.DueBy }?.let { c -> if (d.dueAt == c.time) d = d.copy(dueAt = was.dueAt) }
        changes.lastOrNull { it.type == ChangeType.StartFrom }?.let { c -> if (d.startFrom == c.time) d = d.copy(startFrom = was.startFrom) }
        changes.lastOrNull { it.type == ChangeType.Minutes }?.let { c ->
            val total = if (d.steps.isEmpty()) d.minutes else d.steps.sumOf { it.minutes }
            if (total == c.minutes) {
                d = d.copy(minutes = was.minutes)
                if (was.steps.map { it.title } == d.steps.map { it.title }) d = d.copy(steps = d.steps.mapIndexed { i, s -> s.copy(minutes = was.steps[i].minutes) })
            }
        }
        return d
    }

    /**
     * A later card's [laterWas] (what it replaced) once a card whose [undone] changes set those values,
     * and which replaced [undoneWas], is undone: a value of the undone card's becomes what it replaced,
     * so undoing the later card doesn't bring the undone one's back.
     */
    fun relayered(laterWas: OwnTask, undone: List<Change>, undoneWas: OwnTask): OwnTask {
        var d = laterWas
        undone.lastOrNull { it.type == ChangeType.DueBy }?.let { c -> if (d.dueAt == c.time) d = d.copy(dueAt = undoneWas.dueAt) }
        undone.lastOrNull { it.type == ChangeType.StartFrom }?.let { c -> if (d.startFrom == c.time) d = d.copy(startFrom = undoneWas.startFrom) }
        undone.lastOrNull { it.type == ChangeType.Minutes }?.let { c ->
            val total = if (d.steps.isEmpty()) d.minutes else d.steps.sumOf { it.minutes }
            if (total == c.minutes) {
                d = d.copy(minutes = undoneWas.minutes)
                if (undoneWas.steps.map { it.title } == d.steps.map { it.title }) d = d.copy(steps = d.steps.mapIndexed { i, s -> s.copy(minutes = undoneWas.steps[i].minutes) })
            }
        }
        return d
    }

    /** The changes an instruction makes to one of your own that are made on it, not laid over it. */
    val MADE_ON_IT = setOf(ChangeType.DueBy, ChangeType.StartFrom, ChangeType.Minutes)

    /**
     * [change] (a due date, a start, or minutes: [MADE_ON_IT]) made on your own [task] itself, as
     * the form would make it: with no source behind it there's nothing for an override to come back
     * to, and the form can change it again. Minutes for one with steps scale them to match. [task]
     * as it was, if the change would leave it what the form wouldn't take.
     */
    fun changed(task: TaskItem, change: Change): TaskItem = draftWith(task, change)?.takeIf { problem(it) == null }?.let { edited(task, it) } ?: task

    /**
     * [task] as the form would have it with [change] made ([MADE_ON_IT]; a null time clears it), to
     * check ([problem]) and save; null for a change of another kind.
     */
    fun draftWith(task: TaskItem, change: Change): OwnTask? = with(draftOf(task), change)

    /**
     * [task] with all of [changes] made at once, in order, as one draft: checked as a whole, so a
     * start and a due date moved later together aren't refused for the start coming first. Null if
     * one is of a kind not made on a task ([MADE_ON_IT]).
     */
    fun draftWithAll(task: TaskItem, changes: List<Change>): OwnTask? =
        changes.fold(draftOf(task) as OwnTask?) { draft, change -> draft?.let { with(it, change) } }

    /**
     * Of [changes], those made on tasks of your own (in [tasks]) that the form would take, each
     * task's together ([draftWithAll]).
     */
    fun madeOn(tasks: List<TaskItem>, changes: List<Change>): List<Change> {
        val own = tasks.filter { it.source == Source.Own }.associateBy { it.id }
        return changes.filter { it.type in MADE_ON_IT && it.taskId in own }.groupBy { it.taskId!! }
            .filter { (id, group) -> problemOf(own.getValue(id), group) == null }
            .values.flatten()
    }

    /** Why [changes] can't be made on a task of your own in [tasks], in words; null if they can (or touch none). */
    fun problemWith(tasks: List<TaskItem>, changes: List<Change>): String? {
        val own = tasks.filter { it.source == Source.Own }.associateBy { it.id }
        for ((id, group) in changes.filter { it.type in MADE_ON_IT && it.taskId in own }.groupBy { it.taskId!! }) {
            val task = own.getValue(id)
            val problem = problemOf(task, group) ?: continue
            return "It would leave “${task.title}” as it can't be: ${problem.replaceFirstChar(Char::lowercase)}"
        }
        return null
    }

    /** What's wrong with [task] after [changes], in words; null if the form would take it. */
    private fun problemOf(task: TaskItem, changes: List<Change>): String? {
        val least = minutesFor(task).first
        changes.firstOrNull { it.type == ChangeType.Minutes && (it.minutes ?: 0) < least }?.let { return "its $least steps need at least a minute each" }
        val draft = draftWithAll(task, changes) ?: return "it's not a change that can be made on it"
        return problem(draft)
    }

    private fun with(draft: OwnTask, change: Change): OwnTask? {
        return when (change.type) {
            ChangeType.DueBy -> draft.copy(dueAt = change.time)
            ChangeType.StartFrom -> draft.copy(startFrom = change.time)
            ChangeType.Minutes -> {
                // Fewer than its steps: they couldn't add up to it ([minutesFor]).
                val minutes = change.minutes?.takeIf { it >= maxOf(1, draft.steps.size) } ?: return null
                if (draft.steps.isNotEmpty()) {
                    // Shared out to add up to exactly yours, as the plan shares them.
                    val shares = shareOut(minutes, draft.steps.map { it.minutes })
                    draft.copy(steps = draft.steps.mapIndexed { i, s -> s.copy(minutes = shares[i]) })
                } else {
                    draft.copy(minutes = minutes)
                }
            }
            else -> null
        }
    }

    private fun apply(old: TaskItem, task: OwnTask): TaskItem {
        val event = task.kind == Kind.Event
        // Each step matched to the one it was by name, a repeated name by its turn (the second
        // "Review" to the second), so a step done stays done and one to do stays to do.
        val seen = HashMap<String, Int>()
        val steps = if (event) emptyList() else task.steps.take(MAX_STEPS).map { s ->
            val turn = seen.merge(s.title.trim(), 1, Int::plus)!! - 1
            val was = old.subSteps.filter { it.title == s.title.trim() }.getOrNull(turn)
            SubStep(s.title.trim().take(MAX_TITLE), s.minutes, done = was?.done == true, byHand = was?.byHand == true, timedMin = was?.timedMin ?: 0, from = s.from, dueAt = s.dueAt)
        }
        val minutes = when {
            event -> 0
            steps.isNotEmpty() -> steps.sumOf { it.minutes }
            else -> task.minutes
        }
        return old.copy(
            title = task.title.trim().take(MAX_TITLE),
            detail = task.notes.trim().take(MAX_NOTES),
            className = task.className?.trim()?.take(MAX_CLASS)?.takeIf { it.isNotEmpty() },
            kind = task.kind,
            dueAt = task.dueAt,
            notBefore = task.startFrom,
            sourceEffortMin = minutes,
            subSteps = steps,
            // As a source's: what an instruction is laid over, and taking one back comes back to.
            sourceValues = SourceValues(task.kind, task.dueAt, null),
        ).enriched()
    }
}
