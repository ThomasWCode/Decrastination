package com.thomaswcode.decrastination.core

/**
 * Applies one source's fresh list to the stored tasks. Pure, so the rules are tested directly.
 *
 * - A task the source lists is updated from it, keeping this app's own bookkeeping (first seen,
 *   minutes worked, sub-steps, estimates, the enrichment, laid over the fresh values again). One the
 *   source lists as finished becomes [Status.Done]. One finished a fortnight ago and finished still is
 *   left as it was kept ([trimmed]), only seen again.
 * - An open task the source no longer lists is done: handed in, ticked, or an email unstarred
 *   (docs/spec/sources.md; DX-03: the star is the state, so an email's blocks or its reading still to come
 *   don't keep it). A [TaskItem.derived] one (the Anki quota) was missed instead: only its counts
 *   can say it's done.
 * - A done task the source lists again is open again: a hand-in undone, an email starred again.
 *   One whose reading was let go ([trimmed]) is laid as one seen for the first time, and read again.
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

    /** How long a finished task stays in the list after it was last seen or finished; and keeps its text and reading ([trimmed], C-Q3). */
    const val KEEP_FINISHED_MS = 14 * 24 * 3_600_000L

    /** [tasks], each finished more than [KEEP_FINISHED_MS] ago as it's kept ([TaskItem.trimmed]), the rest as they are. */
    fun trimmed(tasks: List<TaskItem>, now: Long, bulky: Set<String>): List<TaskItem> =
        tasks.map { if (it.longFinished(now)) it.trimmed(bulky) else it }

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
                    sourceValues = SourceValues(f.kind, f.dueAt, f.availableFrom, f.title),
                ).let { if (f.done) it.copy(status = Status.Done, doneAt = now) else it }
                if (f.done) {
                    fresh[id] = task
                    continue
                }
                added += task
                fresh[id] = task
                continue
            }
            // Finished a fortnight ago and finished still: kept as its record ([trimmed], C-Q3), what its
            // reading laid as it was laid, only seen again.
            if (old.longFinished(now) && (f.done || ((old.derived || old.tickDone) && old.status == Status.Done))) {
                fresh[id] = old.copy(status = Status.Done, lastSeenAt = now)
                continue
            }
            // The source's steps, unless they're the same as before: then the ones kept here, with
            // what's ticked off here, until the source's counts catch up (Anki's cards), each with what
            // the source now says of it (its minutes by a pace learned since, DX-11) and what's of this
            // phone kept: its id, tick and timed minutes.
            val sourceSteps = f.subSteps?.takeIf { new -> new.map { it.title } != old.subSteps.map { it.title } }
            val keptSteps = f.subSteps?.takeIf { sourceSteps == null && it.size == old.subSteps.size }
                ?.let { new -> old.subSteps.mapIndexed { i, s -> s.copy(minutes = new[i].minutes, ankiSections = new[i].ankiSections, from = new[i].from, dueAt = new[i].dueAt, where = new[i].where) } }
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
                subSteps = sourceSteps ?: keptSteps ?: old.subSteps,
                // Fresh steps from the source's counts already leave out the work done so far.
                stepsWorkedMin = if (sourceSteps != null) old.workedMin + old.photoMin else old.stepsWorkedMin,
                stepsPerDay = f.stepsPerDay,
                notBefore = f.notBefore,
                derived = f.derived,
                lastSeenAt = now,
                extra = f.extra,
                sourceValues = SourceValues(f.kind, f.dueAt, f.availableFrom, f.title),
            ).enriched()
            val next = when {
                // Reported as it stood before it finished (see completion()).
                old.status == Status.Open && f.done -> updated.copy(status = Status.Done, doneAt = now).also { completed += completion(old, it) }
                old.status == Status.Open -> updated
                (old.derived || old.tickDone) && old.status == Status.Done -> updated.copy(status = Status.Done)
                f.done -> updated.copy(status = Status.Done)
                // A new round of it: the last round's minutes count towards neither this one's
                // completion record nor what's left of it.
                // Its steps too: none of the new round is done yet, by a slot kept, a photo or by hand.
                // Laid again as open: one whose reading was let go ([trimmed]) as one seen for the first time.
                else -> updated.copy(
                    status = Status.Open,
                    doneAt = null,
                    workedMin = 0,
                    photoMin = 0,
                    stepsWorkedMin = 0,
                    firstProgress = f.sourceProgress,
                    subSteps = updated.subSteps.map { it.copy(done = false, byHand = false, timedMin = 0) },
                ).enriched().also { reopened += it }
            }
            fresh[id] = next
        }

        val result = tasks.mapNotNull { old ->
            fresh[old.id]?.let { return@mapNotNull it }
            if (old.source != source) return@mapNotNull old
            when {
                // Done, kept a fortnight; and the same work as one still open for as long as that is,
                // as its minutes worked count for it (DX-15).
                old.status != Status.Open -> old.takeIf { now - (old.doneAt ?: old.lastSeenAt) < KEEP_FINISHED_MS || old.mine.sameAs?.value?.let { byId[it]?.isOpen } == true }
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
