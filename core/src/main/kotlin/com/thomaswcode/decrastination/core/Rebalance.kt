package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Two of Felix's steps rebalanced to fit a free time at school (D-110): where a task's next step is too
 * long for one today and the step after it needs it first, he may move the boundary between them in the
 * morning's call, so the first fits that time, the work and its order kept. Still two steps, with their
 * ids, so a slot's key and a tick follow them. Pure.
 */
object Rebalance {
    /** The shortest step a rebalance makes: the shortest free time at school there is (`minSchoolSlotMin`'s). */
    const val MIN_STEP_MIN = 15

    /** The most he's asked about in a morning. */
    const val MAX_ASKED = 3

    /** Task [taskId]'s step [index] ([first]) and the one after it ([second]), the first too long for [gap]. */
    data class Candidate(val taskId: String, val title: String, val index: Int, val first: SubStep, val second: SubStep, val gap: Gap)

    /** His answer for one: the two steps' new titles and minutes, in their order. */
    @Serializable
    data class Given(
        val taskId: String = "",
        val stepIndex: Int = -1,
        val firstTitle: String = "",
        val firstMinutes: Int = 0,
        val secondTitle: String = "",
        val secondMinutes: Int = 0,
    )

    /**
     * Today's in [plan], from [tasks]: a task's next step, today's work, not begun and one that can be done
     * at school, too long for a free time there before its slot today (or with none), and the step after it
     * needing it, alike in its dates and place, so the work moved between them keeps them; both Felix's, as
     * D-4 keeps yours and the reading's as they were given. None of a task whose rebalance you took back
     * ([declined]). The earliest such time each, at most [MAX_ASKED].
     */
    fun candidates(plan: Plan, tasks: List<TaskItem>, declined: Set<String>, now: Long): List<Candidate> {
        val today = plan.todayBucket ?: return emptyList()
        val gaps = today.gaps.filter { it.start >= now && it.minutes >= MIN_STEP_MIN }
        if (gaps.isEmpty()) return emptyList()
        return tasks.asSequence().filter { it.isOpen && it.source != Source.Anki && it.id !in declined }.mapNotNull { task ->
            val index = task.subSteps.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: return@mapNotNull null
            val first = task.subSteps[index]
            val second = task.subSteps.getOrNull(index + 1) ?: return@mapNotNull null
            if (first.origin != StepOrigin.Felix || second.origin != StepOrigin.Felix || second.done || second.anyOrder) return@mapNotNull null
            // Today's work: a task for another day isn't rewritten for today's free time.
            if (today.chunks.none { it.taskId == task.id && it.stepIndex == index }) return@mapNotNull null
            if (worked(task) > 0) return@mapNotNull null
            // Work for home only never goes in a free time at school (PL-15), however it's cut.
            val where = Where.of(first.where) ?: Planner.whereOf(task)
            if (where == Where.Home) return@mapNotNull null
            // What moves to the second keeps its dates and place: no later deadline for it, nowhere it couldn't be done.
            if (first.from != second.from || first.dueAt != second.dueAt || (Where.of(second.where) ?: Planner.whereOf(task)) != where) return@mapNotNull null
            val slot = today.slots.firstOrNull { it.kind == SlotKind.Work && it.chunk?.taskId == task.id && it.chunk?.stepIndex == index }
            val gap = gaps.firstOrNull { it.minutes < first.minutes && (slot == null || it.start < slot.start) } ?: return@mapNotNull null
            Candidate(task.id, task.title, index, first, second, gap)
        }.take(MAX_ASKED).toList()
    }

    /**
     * [task]'s steps with [given] made on [candidate], as the steps he gives in a Steps change, each with
     * its id so a tick follows it; null where [given] isn't one: another task or step, a title missing, a
     * step under [MIN_STEP_MIN], the first longer than the free time, the two not adding up to what they
     * did, or the task's steps no longer as they were asked about.
     */
    fun steps(task: TaskItem, candidate: Candidate, given: Given): List<OwnStep>? {
        if (given.taskId != candidate.taskId || given.stepIndex != candidate.index) return null
        val first = given.firstTitle.trim().take(OwnTaskRules.MAX_TITLE)
        val second = given.secondTitle.trim().take(OwnTaskRules.MAX_TITLE)
        if (first.isEmpty() || second.isEmpty()) return null
        if (given.firstMinutes !in MIN_STEP_MIN..candidate.gap.minutes || given.secondMinutes < MIN_STEP_MIN) return null
        if (given.firstMinutes + given.secondMinutes != candidate.first.minutes + candidate.second.minutes) return null
        if (task.subSteps.getOrNull(candidate.index) != candidate.first || task.subSteps.getOrNull(candidate.index + 1) != candidate.second) return null
        return task.subSteps.mapIndexed { i, s ->
            val own = OwnStep(s.title, s.minutes, s.from, s.dueAt, s.where, id = s.id, anyOrder = s.anyOrder)
            when (i) {
                candidate.index -> own.copy(title = first, minutes = given.firstMinutes)
                candidate.index + 1 -> own.copy(title = second, minutes = given.secondMinutes)
                else -> own
            }
        }
    }

    /** What a rebalance made, as its card under his message says it. */
    fun line(candidate: Candidate, given: Given): String =
        "“${candidate.title}”: “${given.firstTitle.trim()}” (${given.firstMinutes} min), then “${given.secondTitle.trim()}” (${given.secondMinutes} min)"

    /** The tasks whose rebalance in a morning's message isn't in use, taken back or never made: not asked about again. */
    fun declined(messages: List<ChatMessage>): Set<String> = messages.asSequence()
        .filter { it.kind == MessageKind.Morning }
        .flatMap { it.proposals }
        .filter { it.state != ProposalState.Applied }
        .flatMap { it.changes }
        .filter { it.type == ChangeType.Steps }
        .mapNotNull { it.taskId }
        .toSet()

    /** Minutes worked on [task] past its ticked steps, which the plan takes off its next: that step begun. */
    private fun worked(task: TaskItem): Int =
        task.workedMin + task.photoMin - task.stepsWorkedMin - task.subSteps.filter { it.done }.sumOf { if (it.byHand) it.timedMin else it.minutes }
}
