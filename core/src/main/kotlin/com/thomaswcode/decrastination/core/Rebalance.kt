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

    private const val MINUTE = 60_000L

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
     * needing it, alike in place, its dates planning the work moved to it no later than the first's did; both
     * Felix's, as D-4 keeps yours and the reading's as they were given. None of a task whose rebalance you took back
     * ([declined]). Each its own free time, the earliest its piece can be done in, taken in the day's order
     * of their pieces (the more urgent first), at most [MAX_ASKED].
     */
    fun candidates(plan: Plan, tasks: List<TaskItem>, declined: Set<String>, now: Long): List<Candidate> {
        val today = plan.todayBucket ?: return emptyList()
        val gaps = today.gaps.filter { it.start >= now && it.minutes >= MIN_STEP_MIN }
        if (gaps.isEmpty()) return emptyList()
        // Each free time for one rebalance: two made for the same one, only one would fit it.
        val taken = HashSet<Long>()
        val out = mutableListOf<Candidate>()
        val byId = tasks.associateBy { it.id }
        for (task in today.chunks.map { it.taskId }.distinct().mapNotNull { byId[it] }) {
            if (out.size == MAX_ASKED) break
            if (!task.isOpen || task.source == Source.Anki || task.id in declined) continue
            val index = task.subSteps.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: continue
            val first = task.subSteps[index]
            val second = task.subSteps.getOrNull(index + 1) ?: continue
            if (first.origin != StepOrigin.Felix || second.origin != StepOrigin.Felix || second.done || second.anyOrder) continue
            // Today's work: a task for another day isn't rewritten for today's free time.
            val piece = today.chunks.firstOrNull { it.taskId == task.id && it.stepIndex == index } ?: continue
            if (worked(task) > 0) continue
            // Its piece laid at the step's own minutes: not shared out to a total you gave, nor shortened by work
            // or its source's progress, so the minutes he gives are the ones laid.
            if (piece.minutes != first.minutes) continue
            // Work for home only never goes in a free time at school (PL-15), however it's cut.
            val where = Where.of(first.where) ?: Planner.whereOf(task)
            if (where == Where.Home) continue
            // What moves to the second is planned no later, nowhere it couldn't be done: a date of the first's own
            // still to come holds the second's to it (one gone by is dropped, PL-14, and a step is done by the
            // dates after it already, as the steps go in order), and the second is open by now or with the first.
            val due = first.dueAt?.takeIf { it > now }
            if (due != null && (second.dueAt?.takeIf { it > now } ?: Long.MAX_VALUE) > due) continue
            if ((second.from ?: Long.MIN_VALUE) > maxOf(first.from ?: Long.MIN_VALUE, now)) continue
            if ((Where.of(second.where) ?: Planner.whereOf(task)) != where) continue
            val slot = today.slots.firstOrNull { it.kind == SlotKind.Work && it.chunk?.taskId == task.id && it.chunk?.stepIndex == index }
            // Held at a time you gave (PL-04): the pin keeps it there, however it's cut.
            if (slot?.pinned == true || (slot == null && piece.why == Why.Pinned)) continue
            // One its piece can be done in: before its slot, once its task opens, over before a deadline that day.
            val gap = gaps.firstOrNull { g ->
                g.start !in taken && g.minutes < first.minutes && (slot == null || g.start < slot.start) &&
                    (piece.availableAt ?: Long.MIN_VALUE) <= g.start && !(piece.hard && piece.deadline < g.start + g.minutes * MINUTE)
            } ?: continue
            taken += gap.start
            out += Candidate(task.id, task.title, index, first, second, gap)
        }
        return out
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

    /** [candidate]'s rebalance into [steps] as his Steps change, at [at]: to try a plan with ([laidAsOffered]) before it's made. */
    fun instruction(candidate: Candidate, steps: List<OwnStep>, at: Long): Instruction = Instruction(
        "rebalance:${candidate.taskId}", "", at = at, state = InstructionStatus.Applied,
        changes = listOf(Change(ChangeType.Steps, taskId = candidate.taskId, steps = steps)), appliedAt = at, by = By.Felix,
    )

    /**
     * Whether [plan], tried with [candidate]'s rebalance, lays its first step in the free time it was offered,
     * with the rest the slots keep after it before the next work (a long one once its run reaches
     * `slotLongBreakAfterMin`, counted over any window, the cautious way): else his message, which names
     * that time, wouldn't be true, or a rest would be cut short by a slot that can't move.
     */
    fun laidAsOffered(plan: Plan, candidate: Candidate, settings: Settings): Boolean {
        val work = plan.todaySlots.filter { it.kind == SlotKind.Work }.sortedBy { it.start }
        val slot = work.firstOrNull { it.chunk?.taskId == candidate.taskId && it.chunk?.stepIndex == candidate.index } ?: return false
        if (slot.start < candidate.gap.start || slot.end > candidate.gap.start + candidate.gap.minutes * MINUTE) return false
        var run = ((slot.end - slot.start) / MINUTE).toInt()
        var from = slot.start
        for (before in work.filter { it.end <= slot.start }.asReversed()) {
            if (from - before.end >= settings.slotLongBreakMin * MINUTE) break
            run += ((before.end - before.start) / MINUTE).toInt()
            from = before.start
        }
        val rest = if (run >= settings.slotLongBreakAfterMin) settings.slotLongBreakMin else settings.slotBreakMin
        val next = work.firstOrNull { it.start >= slot.end && it != slot } ?: return true
        return next.start - slot.end >= rest * MINUTE
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
