package com.thomaswcode.decrastination.tasks

import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.WeeklyRules
import com.thomaswcode.decrastination.core.longFinished
import com.thomaswcode.decrastination.core.trimmed
import com.thomaswcode.decrastination.core.withMine
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own tasks (Q28; docs/plan_v4/architecture.md §The rules): kept ([saveOwnTask]),
 * finished as you say or once their steps are done ([finishOwnTask], [start]), opened again
 * ([reopenOwnTask]), deleted ([deleteOwnTask]), and trimmed to their record a fortnight after
 * they're done ([pruneOwn]). Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and a
 * callback for what follows a finish. Their pure rules are [OwnTaskRules]'s,
 * named in full here, as this class has their name.
 */
class OwnTasks(
    private val tasks: JsonStore<TaskState>,
    /** Your instructions, read for the values they lay on one of your own as it's kept. */
    private val instructions: JsonStore<InstructionState>,
    /** The chat, whose cards that added a task say so no more once it's gone. */
    private val chat: JsonStore<ChatState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    /** The completions a finish left: free time and the log, then "How was it?" (`AppGraph.settleCompletions`). */
    private val settle: suspend () -> Unit,
) {
    /**
     * Keeps [task] as one of your own (Q28) under [sourceId]: made, or changed if it's there
     * already (changed in the form, or a second tap). False if it can't be kept ([OwnTaskRules.problem]).
     * Saved whole, whatever the screen that asked does meanwhile.
     */
    suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = withContext(NonCancellable) {
        if (OwnTaskRules.problem(task) != null) return@withContext false
        val id = TaskItem.id(Source.Own, sourceId)
        val overrides = InstructionRules.taskOverrides(instructions.value.applied)[id] ?: Mine.NONE
        val now = clock.now()
        tasks.update { state ->
            val old = state.tasks.firstOrNull { it.id == id }
            val made = (if (old == null) OwnTaskRules.create(task, sourceId, now) else OwnTaskRules.edited(old, task))
                .let { if (it.mine == overrides) it else it.withMine(overrides) }
            state.copy(tasks = if (old == null) state.tasks + made else state.tasks.map { if (it.id == id) made else it })
        }
        true
    }

    /**
     * Your own task [taskId] done, as you say, or one done by its tick (NF-06): finished
     * through the queue a source's confirmation goes through, so it's logged and asked about as any
     * is; but it earns no free time ([com.thomaswcode.decrastination.block.Focus.onCompleted]), as nothing but you says it's done.
     */
    suspend fun finishOwnTask(taskId: String): Unit = withContext(NonCancellable) {
        val now = clock.now()
        var finished = false
        tasks.update { state ->
            val task = state.tasks.firstOrNull { it.id == taskId && (it.source == Source.Own || it.tickDone) && it.isOpen } ?: return@update state
            val done = task.copy(status = Status.Done, doneAt = now, lastSeenAt = now)
            finished = true
            state.copy(tasks = state.tasks.map { if (it.id == taskId) done else it }, unrewarded = state.unrewarded + done)
        }
        if (finished) settle()
    }

    /**
     * Your own task [taskId], done, open again (done by mistake): its minutes worked stay, and so does
     * its record in the log, as a source's reopening leaves it. One with every step ticked has its
     * last step open again: something's left, or it would be finished again at once.
     */
    suspend fun reopenOwnTask(taskId: String) {
        tasks.update { state ->
            // Or one done by its tick (NF-06), which only you say is done: a sync leaves it open again.
            state.copy(tasks = state.tasks.map { t -> if (t.id == taskId && (t.source == Source.Own || t.tickDone) && t.status == Status.Done) OwnTaskRules.reopened(t) else t })
        }
    }

    /** Your own task [taskId] gone, as if it never was. */
    suspend fun deleteOwnTask(taskId: String) {
        // A task linked to it as the same work (DX-15) is back: the work it stood for has gone.
        tasks.update { state ->
            // A week of weekly work stays gone (D-124), whatever becomes of its rule.
            val week = state.tasks.firstOrNull { it.id == taskId && it.source == Source.Own }?.weekly
            state.copy(tasks = Duplicates.linksPresent(state.tasks.filterNot { it.id == taskId && it.source == Source.Own }), weeksDeleted = state.weeksDeleted + listOfNotNull(week))
        }
        forgetCards(setOf(taskId))
    }

    /** Chat cards that added the tasks [gone] say so no more: they can be added again. */
    private suspend fun forgetCards(gone: Set<String>) {
        if (chat.value.messages.none { m -> m.proposals.any { it.made in gone } }) return
        chat.update { s ->
            s.copy(messages = s.messages.map { m -> m.copy(proposals = m.proposals.map { p -> if (p.made in gone) p.copy(state = ProposalState.Proposed, made = null) else p }) })
        }
    }

    /**
     * Your own tasks done more than a fortnight ago are kept as their record, as a source's are
     * ([trimmed], C-Q3): only a delete removes one. A sync trims them too; this does after one that
     * merged nothing.
     */
    suspend fun pruneOwn() {
        val now = clock.now()
        if (tasks.value.tasks.none { it.source == Source.Own && it.longFinished(now) && it != it.trimmed(emptySet()) }) return
        tasks.update { state -> state.copy(tasks = state.tasks.map { if (it.source == Source.Own && it.longFinished(now)) it.trimmed(emptySet()) else it }) }
    }

    /**
     * Weekly work's tasks made what the rules in force say at this moment (D-124): this week's and next week's made, a week
     * gone by closed, a rule ended let go. Each time is safe, as each week is one task; so it's done at a start, whenever a
     * rule changes, after every sync and before each morning's briefing.
     */
    suspend fun rollWeeks(): Unit = withContext(NonCancellable) {
        val rules = WeeklyRules.inForce(instructions.value.applied)
        val now = clock.now()
        tasks.update { state ->
            val rolled = WeeklyRules.roll(state.tasks, state.weeksDeleted, rules, now, clock.zone())
            state.copy(tasks = Duplicates.linksPresent(rolled.tasks), weeksDeleted = rolled.weeksDeleted)
        }
    }

    /** One of your own whose steps are all done finished, and weekly work kept to its rules, from the app's start. */
    fun start() {
        // One of your own whose steps are all done (ticked, or kept in their slots) is finished; and one
        // done by its tick (NF-06) given steps of yours by the chat.
        scope.launch {
            tasks.state.map { s -> s.tasks.filter { (it.source == Source.Own || it.tickDone) && it.isOpen && it.subSteps.isNotEmpty() && it.subSteps.all { st -> st.done } }.map { it.id } }
                .distinctUntilChanged().collect { ids -> ids.forEach { finishOwnTask(it) } }
        }
        scope.launch {
            instructions.state.map { WeeklyRules.inForce(it.applied) }.distinctUntilChanged().collect { rollWeeks() }
        }
    }
}
