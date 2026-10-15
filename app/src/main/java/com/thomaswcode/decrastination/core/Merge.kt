package com.thomaswcode.decrastination.core

/**
 * Applies one source's fresh list to the stored tasks. Pure, so the rules are tested directly.
 *
 * - A task the source lists is updated from it, keeping this app's own bookkeeping (first seen,
 *   minutes worked, sub-steps, estimates, the enrichment, laid over the fresh values again). One the
 *   source lists as finished becomes [Status.Done].
 * - An open task the source no longer lists is done: handed in, ticked, or an email unstarred
 *   (PLAN.md §1; DX-03: the star is the state, so an email's blocks or its reading still to come
 *   don't keep it). A [TaskItem.derived] one (the Anki quota) was missed instead: only its counts
 *   can say it's done.
 * - A done task the source lists again is open again: a hand-in undone, an email starred again.
 *   A derived task once done stays done, even if the counts waver (Anki's learning cards come
 *   back within minutes); so does one done by its tick ([TaskItem.tickDone]: feedback you've read,
 *   which Teams goes on listing).
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
                // Finished before it was ever seen: nothing to do, so it isn't added. A derived
                // task is kept, as done: a deck already studied still stands for its sections.
                if (f.done && !f.derived) continue
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
                    sourceValues = SourceValues(f.kind, f.dueAt, f.availableFrom),
                ).let { if (f.done) it.copy(status = Status.Done, doneAt = now) else it }
                if (f.done) {
                    fresh[id] = task
                    continue
                }
                added += task
                fresh[id] = task
                continue
            }
            // The source's steps, unless they're the same as before: then the ones kept here, with
            // what a session ticked off, until the source's counts catch up (Anki's cards).
            val sourceSteps = f.subSteps?.takeIf { new -> new.map { it.title } != old.subSteps.map { it.title } }
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
                subSteps = sourceSteps ?: old.subSteps,
                // Fresh steps from the source's counts already leave out the work done so far.
                stepsWorkedMin = if (sourceSteps != null) old.workedMin + old.photoMin else old.stepsWorkedMin,
                stepsPerDay = f.stepsPerDay,
                notBefore = f.notBefore,
                derived = f.derived,
                lastSeenAt = now,
                extra = f.extra,
                sourceValues = SourceValues(f.kind, f.dueAt, f.availableFrom),
            ).enriched()
            val next = when {
                // Reported as it stood before it finished (see completion()).
                old.status == Status.Open && f.done -> updated.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
                old.status == Status.Open -> updated
                (old.derived || old.tickDone) && old.status == Status.Done -> updated.copy(status = Status.Done)
                f.done -> updated.copy(status = Status.Done)
                // A new round of it: the last round's minutes count towards neither this one's
                // completion record nor what's left of it.
                // Its steps too: none of the new round is done yet, by a session, a photo or by hand.
                else -> updated.copy(
                    status = Status.Open,
                    doneAt = null,
                    workedMin = 0,
                    photoMin = 0,
                    stepsWorkedMin = 0,
                    firstProgress = f.sourceProgress,
                    subSteps = updated.subSteps.map { it.copy(done = false, byHand = false, timedMin = 0) },
                ).also { reopened += it }
            }
            fresh[id] = next
        }

        val result = tasks.mapNotNull { old ->
            fresh[old.id]?.let { return@mapNotNull it }
            if (old.source != source) return@mapNotNull old
            when {
                // Done, kept a fortnight; and the same work as one still open for as long as that is,
                // as its minutes worked count for it (DX-15).
                old.status != Status.Open -> old.takeIf { now - (old.doneAt ?: old.lastSeenAt) < KEEP_FINISHED_MS || old.userSameAs?.let { byId[it]?.isOpen } == true }
                old.derived -> old.copy(status = Status.Missed).also { missed += it }
                else -> old.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
            }
        } + fresh.values.filter { it.id !in byId }

        // A link to the same work let go with the task it was to (DX-15).
        return Result(Duplicates.linksPresent(result), completed, reopened, added, missed)
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
