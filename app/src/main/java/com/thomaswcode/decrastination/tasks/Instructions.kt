package com.thomaswcode.decrastination.tasks

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.TaskOverrides
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.overrides
import com.thomaswcode.decrastination.core.withOverrides
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.InstructionAnswers
import com.thomaswcode.decrastination.enrich.InstructionPrompts
import com.thomaswcode.decrastination.enrich.InstructionReader
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.EventJudge
import com.thomaswcode.decrastination.plan.Planning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Your instructions (docs/plan_v4, Stage 6c), moved out of `AppGraph`: written and read by Claude
 * ([addInstruction], [readInstructions]), or made yourself as quick changes ([addOwnChange],
 * [laterToday], [takeBackQuick]); applied at once once read, or refused saying why ([applyInstruction]);
 * deleted or taken back ([deleteInstruction]); and laid over everything they change ([layInstructions],
 * [layTaskOverrides]). The one writer of `instructions.json`: others read it here ([value], [state]).
 * Built like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock, the gateway, the focus
 * and the plan, and small callbacks. Their pure rules are [com.thomaswcode.decrastination.core.Instructions]'s,
 * named in full here, as this class has their name.
 */
class Instructions(
    private val instructions: JsonStore<InstructionState>,
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val runtime: JsonStore<RuntimeState>,
    private val school: JsonStore<SchoolCalendar>,
    /** The chat, whose card that made an instruction is told when it's taken back. */
    private val chat: JsonStore<ChatState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val focus: Focus,
    private val planning: Planning,
    /** The calendar's busy time and events, as last read and judged. */
    private val calendarTime: () -> EventJudge.Time,
    /** Who the student is, as each call sends it (AI-05). */
    private val profile: () -> Profile.Block,
    /** The calendar read again with its answers, and today's record made again with it. */
    private val reread: suspend () -> Unit,
    /** The widget drawn again. */
    private val redraw: suspend () -> Unit,
) {
    /** Your instructions as they are now. */
    val value: InstructionState get() = instructions.value

    /** Your instructions as they change. */
    val state: StateFlow<InstructionState> get() = instructions.state

    /** Held while instructions are read, so each is read once. */
    private val readingInstructions = Mutex()

    /**
     * Writes down a new instruction about [about] and has Claude read it: applied once it's read
     * (your call, 16 Oct), and taken back under Your changes.
     */
    suspend fun addInstruction(text: String, about: About, answer: Boolean = false): String {
        val id = java.util.UUID.randomUUID().toString()
        instructions.update { it.copy(instructions = it.instructions + Instruction(id, text.trim().take(InstructionPrompts.MAX_TEXT), about, clock.now(), answer = answer)) }
        scope.launch { readInstructions(interactive = true) }
        return id
    }

    /**
     * A change you made yourself, with no Claude (IMP-P2-001: a task isn't one, can't start before
     * a day, is due on another): kept as an instruction already read, [text] its words, and applied
     * as you'd apply one, under [id]. Saved and applied together: the screen that asked going
     * meanwhile (turned, closed) can't leave it saved and not applied.
     */
    suspend fun addOwnChange(id: String, text: String, about: About, change: Change): Unit = withContext(NonCancellable) {
        val instruction = Instruction(id, text, about, clock.now(), state = InstructionStatus.Understood, changes = listOf(change), quick = true)
        instructions.update { state ->
            // An earlier quick change of the same kind to the same task is replaced, not piled under
            // this one; the new one is laid in its place as it's applied.
            state.copy(instructions = state.instructions.filterNot { it.id != id && isQuick(it, change.taskId, change.type) } + instruction)
        }
        applyInstruction(id)
    }

    /**
     * [key]'s piece later today (UI-03): to the first time after its slot (or now, or when its task
     * can start) with its minutes free, around today's other slots and busy times, as a time you
     * gave it (a change of yours, taken back in Your changes). Its task's next piece only, as a time
     * pins that one (PL-04), and only a time the plan keeps (before its real deadline). The start, or
     * null when today has none.
     */
    suspend fun laterToday(key: String): Long? {
        val now = clock.now()
        val zone = clock.zone()
        val plan = planning.plan(now = now)
        val chunk = plan.ordered.firstOrNull { it.key == key } ?: return null
        if (plan.chunksOf(chunk.taskId).firstOrNull()?.key != key) return null
        val task = tasks.value.tasks.firstOrNull { it.id == chunk.taskId } ?: return null
        val own = plan.todaySlots.firstOrNull { it.chunk?.key == key }
        val windows = com.thomaswcode.decrastination.core.SchoolDays.windows(plan.today, settings.value, tasks.value.timetable, school.value)
        val busy = calendarTime().busy + com.thomaswcode.decrastination.core.Instructions.busy(instructions.value.applied, zone, plan.today, plan.today)
        val from = listOfNotNull(now, own?.end, task.notBefore).max()
        val start = com.thomaswcode.decrastination.core.Slotter.firstFreeAfter(
            plan.todaySlots.filter { it.chunk?.key != key }, windows, busy, from, chunk.minutes, chunk.where, plan.today, zone, settings.value.slotBreakMin,
        )?.takeIf { Planner.pinProblem(task, it, now, zone) == null } ?: return null
        val at = java.time.Instant.ofEpochMilli(start).atZone(zone)
        addOwnChange(
            java.util.UUID.randomUUID().toString(),
            "Later today",
            About(taskId = chunk.taskId, taskTitle = chunk.title),
            Change(ChangeType.AtTime, taskId = chunk.taskId, date = at.toLocalDate().toString(), startMin = at.hour * 60 + at.minute, time = start),
        )
        return start
    }

    /** Whether [instruction] is a quick change of [type] to [taskId] alone. */
    private fun isQuick(instruction: Instruction, taskId: String?, type: ChangeType): Boolean =
        instruction.quick && instruction.changes.singleOrNull()?.let { it.type == type && it.taskId == taskId } == true

    /**
     * Takes back your quick changes of [type] to [taskId]: what its source says (or an instruction of
     * Claude's reading) stands again.
     */
    suspend fun takeBackQuick(taskId: String, type: ChangeType): Unit = applying.withLock {
        val gone = instructions.value.instructions.filter { isQuick(it, taskId, type) }.map { it.id }.toSet()
        if (gone.isEmpty()) return@withLock
        instructions.update { state -> state.copy(instructions = state.instructions.filterNot { it.id in gone }) }
        layInstructions()
    }

    /**
     * Whether what's in force for [taskId]'s [type] is a quick change of yours: the last applied
     * instruction that changes it. One of Claude's applied since wins, and *Undo* would take back
     * nothing you'd see.
     */
    fun quickInForce(taskId: String, type: ChangeType): Boolean = com.thomaswcode.decrastination.core.Instructions.quickInForce(instructions.value.applied, taskId, type)

    /** Has Claude read [id] again (written again, or after it found it unclear). */
    suspend fun rereadInstruction(id: String, text: String) {
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == id && it.state != InstructionStatus.Applied) it.copy(text = text.trim().take(InstructionPrompts.MAX_TEXT), state = InstructionStatus.Reading, changes = emptyList(), note = null) else it })
        }
        scope.launch { readInstructions(interactive = true) }
    }

    /**
     * Reads the instructions waiting for Claude, one call each, as the enrichment's calls are made:
     * under the cap, and resting after a failure. While Claude can't be asked, each says why.
     */
    suspend fun readInstructions(interactive: Boolean = false) = readingInstructions.withLock {
        while (true) {
            val next = instructions.value.instructions.firstOrNull { it.state == InstructionStatus.Reading } ?: return@withLock
            val waiting = run call@{
                val reader = gateway.client(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Instruction)?.let { InstructionReader(it, clock.zone(), profile = profile()) } ?: return@call gateway.because(ModelHold.Off)
                // Through a rest only for you waiting on it (AC-04): after a sync or an enrichment it waits.
                val result = when (val called = gateway.call(interactive = interactive, record = com.thomaswcode.decrastination.enrich.ModelCalls.OnRecord(com.thomaswcode.decrastination.enrich.ModelEffort.Job.Instruction, next.text) { r: InstructionReader.Result -> r.usage to com.thomaswcode.decrastination.enrich.ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: InstructionReader.Result -> r.costUsd to r.refused }) { at -> reader.read(next, tasks.value.tasks, calendarTime().events, at) }) {
                    is Called.Held -> return@call gateway.because(called.hold)
                    is Called.Failed -> {
                        Log.w(AppGraph.TAG, "Reading an instruction failed", called.error)
                        return@call "Claude's call failed: ${gateway.retryWords()}"
                    }
                    is Called.Answered -> called.value
                }
                val reading = result.reading ?: InstructionAnswers.Reading.Unclear(if (result.refused) "Claude declined to read it" else "Claude's answer couldn't be used")
                instructions.update { state ->
                    state.copy(
                        instructions = state.instructions.mapNotNull { i ->
                            // Only as it was sent: written again meanwhile, it's read again.
                            if (i.id != next.id || i.state != InstructionStatus.Reading || i.text != next.text) {
                                i
                            } else {
                                when (reading) {
                                    is InstructionAnswers.Reading.Changes -> i.copy(state = InstructionStatus.Understood, changes = reading.changes, note = null)
                                    // An answer to his question that changes nothing: it's with its task, and goes.
                                    is InstructionAnswers.Reading.Unclear -> if (i.answer) null else i.copy(state = InstructionStatus.Unclear, changes = emptyList(), note = reading.why)
                                }
                            }
                        },
                    )
                }
                // Made as soon as it's read (your call, 16 Oct: nothing of his waits for Apply), as the
                // chat's are; one that can't be is marked unclear, saying why. Taken back under Your changes.
                if (reading is InstructionAnswers.Reading.Changes) applyInstruction(next.id)
                null
            }
            if (waiting != null) {
                // Not now: each still waiting says why, and they're read when Claude can be asked.
                instructions.update { state -> state.copy(instructions = state.instructions.map { if (it.state == InstructionStatus.Reading) it.copy(note = waiting) else it }) }
                return@withLock
            }
        }
    }

    /** Held from checking an instruction to applying it, so two applied at once can't both pass the check. */
    val applying = Mutex()

    /**
     * Applies [id], read and checked by you, at once, armed or not: what an instruction changes is
     * the work, yours to change (12 Oct), never the blocker.
     */
    suspend fun applyInstruction(id: String): Unit = applying.withLock {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id && it.state == InstructionStatus.Understood } ?: return@withLock
        if (refuseCircle(instruction)) return@withLock
        if (refuseOnOwnTasks(instruction)) return@withLock
        if (refuseLate(instruction)) return@withLock
        setApplied(id)
    }

    /**
     * [instruction] would put a task's work after its real deadline (PL-04): it's marked unclear,
     * saying so, rather than applied.
     */
    private suspend fun refuseLate(instruction: Instruction): Boolean {
        val byId = tasks.value.tasks.associateBy { it.id }
        if (instruction.changes.none { c -> c.taskId?.let(byId::get)?.let { com.thomaswcode.decrastination.core.Instructions.latePin(c, it, clock.zone(), clock.now()) } == true }) return false
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = "It's due before then") else it })
        }
        return true
    }

    /**
     * [instruction] would leave one of your own tasks as the form wouldn't take it (a start after its
     * due date): it's marked unclear, saying why, rather than applied, as nothing of it would be.
     */
    private suspend fun refuseOnOwnTasks(instruction: Instruction): Boolean {
        val problem = com.thomaswcode.decrastination.core.OwnTasks.problemWith(tasks.value.tasks, instruction.changes) ?: return false
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = problem) else it })
        }
        return true
    }

    /**
     * [instruction] would have tasks wait for each other in a circle, with those applied: it's
     * marked unclear, saying so, rather than applied.
     */
    private suspend fun refuseCircle(instruction: Instruction): Boolean {
        if (!com.thomaswcode.decrastination.core.Instructions.makesCircle(instruction.changes, instructions.value.applied)) return false
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.map {
                    if (it.id == instruction.id) it.copy(state = InstructionStatus.Unclear, note = "It would have tasks wait for each other in a circle, so none would ever be planned") else it
                },
            )
        }
        return true
    }

    private suspend fun setApplied(id: String) {
        makeOnSchool(id)
        makeOnOwnTasks(id)
        // A time, a day or a start you gave a task moves it on purpose: its slots today are recorded
        // moved (LR-09), as they were, and its missed slots forgotten (PL-13).
        val moved = instructions.value.instructions.firstOrNull { it.id == id }?.changes.orEmpty()
            .filter { it.type == ChangeType.AtTime || it.type == ChangeType.OnDay || it.type == ChangeType.StartFrom }.mapNotNull { it.taskId }
        if (moved.isNotEmpty()) {
            val changes = instructions.value.instructions.firstOrNull { it.id == id }?.changes.orEmpty()
            com.thomaswcode.decrastination.core.Instructions.movedSlots(changes, focus.plan()).forEach { slot -> focus.moveSlot(slot.key, Focus.Laid(slot.start, slot.end, slot.window)) }
            runtime.update { state -> state.copy(slots = moved.fold(state.slots) { s, taskId -> s.moved(taskId) }) }
        }
        instructions.update { state ->
            state.copy(instructions = state.instructions.map { if (it.id == id && it.state == InstructionStatus.Understood) it.copy(state = InstructionStatus.Applied, appliedAt = clock.now()) else it })
        }
        layInstructions()
    }

    /**
     * [id]'s days off and half-days put in the school calendar (PL-16), and taken out of it: Settings
     * lists them, and deleting one there is how one is taken back. One left with nothing else to do
     * is done with, and goes.
     */
    private suspend fun makeOnSchool(id: String) {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return
        val made = instruction.changes.filter { com.thomaswcode.decrastination.core.Instructions.schoolEntry(it) != null }
        if (made.isEmpty()) return
        school.update { calendar ->
            made.mapNotNull(com.thomaswcode.decrastination.core.Instructions::schoolEntry).fold(calendar) { c, entry ->
                when (entry) {
                    is NoSchool -> c.with(entry)
                    is HalfDay -> c.with(entry)
                    else -> c
                }
            }
        }
        instructions.update { state ->
            state.copy(instructions = state.instructions.mapNotNull { i -> if (i.id != id) i else i.copy(changes = i.changes - made.toSet()).takeIf { it.changes.isNotEmpty() } })
        }
    }

    /**
     * [id]'s due dates, starts and minutes for your own tasks made on them ([com.thomaswcode.decrastination.core.OwnTasks.changed]), and
     * taken out of it: there's no source's value for taking it back to return to, and the form can
     * change them again. One left with nothing else to do is done with, and goes.
     */
    private suspend fun makeOnOwnTasks(id: String) {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return
        // Each task's together, as one draft: only those the form would take are made (a restored
        // instruction's others stay in it, laid over the task as any instruction is).
        val made = com.thomaswcode.decrastination.core.OwnTasks.madeOn(tasks.value.tasks, instruction.changes)
        if (made.isEmpty()) return
        tasks.update { state ->
            state.copy(tasks = state.tasks.map { t -> made.filter { it.taskId == t.id }.takeIf { it.isNotEmpty() }?.let { com.thomaswcode.decrastination.core.OwnTasks.draftWithAll(t, it) }?.let { com.thomaswcode.decrastination.core.OwnTasks.edited(t, it) } ?: t })
        }
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.mapNotNull { i ->
                    if (i.id != id) i else i.copy(changes = i.changes - made.toSet()).takeIf { it.changes.isNotEmpty() }
                },
            )
        }
    }

    /** Deletes [id]: discarded if it wasn't applied, taken back if it was. */
    suspend fun deleteInstruction(id: String): Unit = applying.withLock {
        val instruction = instructions.value.instructions.firstOrNull { it.id == id } ?: return@withLock
        if (instruction.state == InstructionStatus.Applied && refuseTakingBack(instruction)) return@withLock
        remove(id)
    }

    /**
     * Taking [instruction] back would leave tasks waiting for each other in a circle (an older
     * instruction it replaced coming back into force): it stays, saying so.
     */
    private suspend fun refuseTakingBack(instruction: Instruction): Boolean {
        val rest = instructions.value.applied.filterNot { it.id == instruction.id }
        if (!com.thomaswcode.decrastination.core.Instructions.makesCircle(emptyList(), rest)) return false
        instructions.update { state ->
            state.copy(
                instructions = state.instructions.map {
                    if (it.id == instruction.id) it.copy(note = "Taking this back would bring back an earlier instruction that has tasks waiting for each other in a circle: take that one back first") else it
                },
            )
        }
        return true
    }

    private suspend fun remove(id: String) {
        val was = instructions.value.instructions.firstOrNull { it.id == id }
        instructions.update { state -> state.copy(instructions = state.instructions.filterNot { it.id == id }) }
        if (was?.state == InstructionStatus.Applied) layInstructions()
        // A chat card that made it, taken back here (under Your changes): the card says so, and the chat
        // recalls it. What it made on your own tasks, and its days off, stand, still undone from the card.
        if (chat.value.messages.any { m -> m.proposals.any { it.made == id } }) {
            chat.update { s ->
                s.copy(
                    messages = s.messages.map { m ->
                        m.copy(proposals = m.proposals.map { p ->
                            when {
                                p.made != id -> p
                                // Its days off stand too, still undone from the card.
                                p.before.isEmpty() && p.schoolBefore == null -> p.copy(state = ProposalState.Proposed, made = null)
                                else -> p.copy(made = null)
                            }
                        })
                    },
                )
            }
        }
    }

    /**
     * The applied instructions laid over everything they change: each task's overrides (on the
     * task, so syncs keep them), the calendar's answers, and the plan, with today's record made
     * again as an answer about an event does.
     */
    suspend fun layInstructions() {
        layTaskOverrides()
        focus.forgetPlan()
        reread()
        // A day's or time's instruction changes no store the widget watches, and the calendar's read
        // (which redraws it) is skipped without its permission: so it's redrawn here.
        runCatching { redraw() }.onFailure { Log.w(AppGraph.TAG, "Widget update failed", it) }
    }

    /**
     * Each task's overrides made what the applied instructions say: the instructions are what's
     * kept, the tasks follow. Done at start as well, so a stop between writing the one and the other
     * (an instruction applied but not yet laid, or taken back with its override still on the task)
     * is put right.
     */
    suspend fun layTaskOverrides() {
        val overrides = com.thomaswcode.decrastination.core.Instructions.taskOverrides(instructions.value.applied)
        tasks.update { state ->
            state.copy(
                tasks = state.tasks.map { task ->
                    val o = overrides[task.id] ?: TaskOverrides.NONE
                    val now = task.overrides()
                    if (o == now) task else task.withOverrides(o)
                }.let(com.thomaswcode.decrastination.core.Duplicates::linksPresent),
            )
        }
    }
}
