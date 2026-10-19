package com.thomaswcode.decrastination.tasks

import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.TaskOverrides
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.overrides
import com.thomaswcode.decrastination.core.withOverrides
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own tasks (2.0.0, Q28; docs/plan_v4, Stage 6c), moved out of `AppGraph`: kept ([saveOwnTask]),
 * finished as you say or once their steps are done ([finishOwnTask], [start]), opened again
 * ([reopenOwnTask]), deleted ([deleteOwnTask]), and let go a fortnight after they're done
 * ([pruneOwn]). Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and a
 * callback for what follows a finish. Their pure rules are [com.thomaswcode.decrastination.core.OwnTasks]'s,
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
     * Keeps [task] as one of your own (2.0.0, Q28) under [sourceId]: made, or changed if it's there
     * already (changed in the form, or a second tap). False if it can't be kept ([com.thomaswcode.decrastination.core.OwnTasks.problem]).
     * Saved whole, whatever the screen that asked does meanwhile.
     */
    suspend fun saveOwnTask(sourceId: String, task: OwnTask): Boolean = withContext(NonCancellable) {
        if (com.thomaswcode.decrastination.core.OwnTasks.problem(task) != null) return@withContext false
        val id = TaskItem.id(Source.Own, sourceId)
        val overrides = com.thomaswcode.decrastination.core.Instructions.taskOverrides(instructions.value.applied)[id] ?: TaskOverrides.NONE
        val now = clock.now()
        tasks.update { state ->
            val old = state.tasks.firstOrNull { it.id == id }
            val made = (if (old == null) com.thomaswcode.decrastination.core.OwnTasks.create(task, sourceId, now) else com.thomaswcode.decrastination.core.OwnTasks.edited(old, task))
                .let { if (it.overrides() == overrides) it else it.withOverrides(overrides) }
            state.copy(tasks = if (old == null) state.tasks + made else state.tasks.map { if (it.id == id) made else it })
        }
        true
    }

    /**
     * Your own task [taskId] done, as you say (2.0.0), or one done by its tick (NF-06): finished
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
            state.copy(tasks = state.tasks.map { t -> if (t.id == taskId && (t.source == Source.Own || t.tickDone) && !t.isOpen) com.thomaswcode.decrastination.core.OwnTasks.reopened(t) else t })
        }
    }

    /** Your own task [taskId] gone, as if it never was. */
    suspend fun deleteOwnTask(taskId: String) {
        // A task linked to it as the same work (DX-15) is back: the work it stood for has gone.
        tasks.update { state -> state.copy(tasks = com.thomaswcode.decrastination.core.Duplicates.linksPresent(state.tasks.filterNot { it.id == taskId && it.source == Source.Own })) }
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
     * Your own tasks done more than a fortnight ago go, as a source's do once it no longer lists them
     * ([com.thomaswcode.decrastination.core.Merge.KEEP_FINISHED_MS]); nothing reads them to do it.
     */
    suspend fun pruneOwn() {
        val now = clock.now()
        fun old(t: TaskItem) = t.source == Source.Own && !t.isOpen && now - (t.doneAt ?: t.lastSeenAt) >= com.thomaswcode.decrastination.core.Merge.KEEP_FINISHED_MS
        val gone = tasks.value.tasks.filter(::old).map { it.id }.toSet()
        if (gone.isEmpty()) return
        tasks.update { state -> state.copy(tasks = state.tasks.filterNot(::old)) }
        forgetCards(gone)
    }

    /** One of your own whose steps are all done finished, from the app's start. */
    fun start() {
        // One of your own whose steps are all done (ticked, or kept in their slots) is finished; and one
        // done by its tick (NF-06) given steps of yours by the chat (16 Oct).
        scope.launch {
            tasks.state.map { s -> s.tasks.filter { (it.source == Source.Own || it.tickDone) && it.isOpen && it.subSteps.isNotEmpty() && it.subSteps.all { st -> st.done } }.map { it.id } }
                .distinctUntilChanged().collect { ids -> ids.forEach { finishOwnTask(it) } }
        }
    }
}
