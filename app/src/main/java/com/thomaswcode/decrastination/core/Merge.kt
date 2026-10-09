package com.thomaswcode.decrastination.core

/**
 * Applies one source's fresh list to the stored tasks. Pure, so the rules are tested directly.
 *
 * - A task the source lists is updated from it, keeping this app's own bookkeeping (first seen,
 *   minutes worked, sub-steps, estimates). One the source lists as finished becomes [Status.Done].
 * - An open task the source no longer lists is done: handed in, ticked, archived, or snoozed out
 *   of the inbox (PLAN.md §1). A [TaskItem.derived] one (the Anki quota) was missed instead: only
 *   its counts can say it's done.
 * - A done task the source lists again is open again: a hand-in undone, a snoozed email back.
 *   A derived task once done stays done, even if the counts waver (Anki's learning cards come
 *   back within minutes).
 * - Something the source already lists as finished, never seen open, is not added: it was done
 *   outside the plan and earns nothing.
 *
 * Sources throw rather than return a partial list, so "not listed" always means gone.
 */
object Merge {

    data class Result(
        val tasks: List<TaskItem>,
        /** Open before this merge, done after it. */
        val completed: List<TaskItem>,
        val reopened: List<TaskItem>,
        val added: List<TaskItem>,
        val missed: List<TaskItem>,
    )

    /** How long a finished task stays in the list after it was last seen or finished. */
    const val KEEP_FINISHED_MS = 14 * 24 * 3_600_000L

    fun apply(tasks: List<TaskItem>, source: Source, fetched: List<Fetched>, now: Long): Result {
        val byId = tasks.associateBy { it.id }
        val listed = HashSet<String>()
        val completed = mutableListOf<TaskItem>()
        val reopened = mutableListOf<TaskItem>()
        val added = mutableListOf<TaskItem>()
        val missed = mutableListOf<TaskItem>()
        val fresh = LinkedHashMap<String, TaskItem>()

        for (f in fetched) {
            val id = TaskItem.id(source, f.sourceId)
            if (!listed.add(id)) continue
            val old = byId[id]
            if (old == null) {
                if (f.done) continue
                val task = TaskItem(
                    id = id,
                    source = source,
                    sourceId = f.sourceId,
                    title = f.title,
                    detail = f.detail,
                    className = f.className,
                    dueAt = f.dueAt,
                    availableFrom = f.availableFrom,
                    kind = f.kind,
                    sourceEffortMin = f.sourceEffortMin,
                    sourceProgress = f.sourceProgress,
                    firstProgress = f.sourceProgress,
                    peakEffortMin = f.sourceEffortMin,
                    subSteps = f.subSteps.orEmpty(),
                    stepsPerDay = f.stepsPerDay,
                    notBefore = f.notBefore,
                    derived = f.derived,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    extra = f.extra,
                )
                added += task
                fresh[id] = task
                continue
            }
            val updated = old.copy(
                title = f.title,
                detail = f.detail,
                className = f.className,
                dueAt = f.dueAt,
                availableFrom = f.availableFrom,
                kind = f.kind,
                sourceEffortMin = f.sourceEffortMin,
                sourceProgress = f.sourceProgress,
                firstProgress = old.firstProgress ?: old.sourceProgress,
                peakEffortMin = listOfNotNull(old.peakEffortMin, old.sourceEffortMin, f.sourceEffortMin).maxOrNull(),
                subSteps = f.subSteps ?: old.subSteps,
                stepsPerDay = f.stepsPerDay,
                notBefore = f.notBefore,
                derived = f.derived,
                lastSeenAt = now,
                extra = f.extra,
            )
            val next = when {
                // Reported as it stood before it finished (see completion()).
                old.status == Status.Open && f.done -> updated.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
                old.status == Status.Open -> updated
                old.derived && old.status == Status.Done -> updated.copy(status = Status.Done)
                f.done -> updated.copy(status = Status.Done)
                else -> updated.copy(status = Status.Open, doneAt = null).also { reopened += it }
            }
            fresh[id] = next
        }

        val result = tasks.mapNotNull { old ->
            fresh[old.id]?.let { return@mapNotNull it }
            if (old.source != source) return@mapNotNull old
            when {
                old.status != Status.Open -> old.takeIf { now - (old.doneAt ?: old.lastSeenAt) < KEEP_FINISHED_MS }
                old.derived -> old.copy(status = Status.Missed).also { missed += it }
                else -> old.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
            }
        } + fresh.values.filter { it.id !in byId }

        return Result(result, completed, reopened, added, missed)
    }

    /**
     * [done] as its completion is rewarded: with the progress it had when first seen (finished
     * through its own progress, Power Planner at 50 % then 100 %, the time is for all the work
     * since), and its estimate before it finished. A derived count (an Anki deck's cards) shrinks
     * as it's worked through, so it's rewarded for the most it was.
     */
    private fun completion(old: TaskItem, done: TaskItem): TaskItem = done.copy(
        // The lower of first-seen and last progress: corrected downward, the work back since counts.
        sourceProgress = minOf(old.firstProgress ?: old.sourceProgress, old.sourceProgress),
        sourceEffortMin = if (old.derived) old.peakEffortMin ?: old.sourceEffortMin else old.sourceEffortMin,
    )
}
