package com.thomaswcode.decrastination.plan

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.DayBreaks
import com.thomaswcode.decrastination.core.DayKind
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.SchoolDays
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.core.withMine
import com.thomaswcode.decrastination.data.ActivityLog
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.LearningState
import com.thomaswcode.decrastination.data.ModelState
import com.thomaswcode.decrastination.data.SessionState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.Profile
import com.thomaswcode.decrastination.learn.Days
import com.thomaswcode.decrastination.learn.EventJudge
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The plan (docs/plan_v4/architecture.md §The rules): made from the stores as they are
 * ([plan]; cheap, dozens of tasks), made again as what it depends on changes ([watcher]), today's
 * slot starts kept for the next ([keepSlots]), and the work done ahead after each ([Ahead]). Built
 * like [com.thomaswcode.decrastination.block.Focus]: its stores, the clock and small callbacks in
 * its constructor, the calendar as the sync last read it among them.
 */
class Planning(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    private val session: JsonStore<SessionState>,
    private val learning: JsonStore<LearningState>,
    private val model: JsonStore<ModelState>,
    private val log: JsonStore<ActivityLog>,
    private val instructions: JsonStore<InstructionState>,
    private val school: JsonStore<SchoolCalendar>,
    private val clock: WallClock,
    scope: CoroutineScope,
    gateway: ModelGateway,
    /** The calendar's busy time and per-day loads, as last read and judged. */
    private val calendarTime: () -> EventJudge.Time,
    /** [calendarTime] as it changes. */
    calendarChanges: Flow<*>,
    /** Today's slots whose grace has gone by, missed ([com.thomaswcode.decrastination.block.Slots.sweep]). */
    private val sweep: suspend () -> Unit,
    /** What's done with each new plan, in order, once its slots are kept: the blocker's plan let go, the slot alarms, your reminders and their alarm, the calendar, the widget. */
    hooks: List<suspend (Plan) -> Unit>,
    /** Who the student is, as each call sends it (AI-05). */
    profile: (Long) -> Profile.Block,
    /** Felix's next message written ahead ([com.thomaswcode.decrastination.felix.Looks.draftAhead]). */
    draftAhead: suspend (Plan, Long) -> Unit,
    onFailure: (Throwable) -> Unit,
    /** The notes kept with a task, which its pieces' first steps are written with. */
    notes: (TaskItem) -> List<String> = { emptyList() },
) {
    /**
     * [state]'s tasks as the plan takes them: each as its instructions say, whatever the task file (a stop
     * between writing the two, not yet put right: the instructions are what's kept), and work on a task
     * linked as the same credited to it (DX-15).
     */
    fun asPlanned(state: TaskState = tasks.value, applied: List<Instruction> = instructions.value.applied): List<TaskItem> {
        val overrides = InstructionRules.taskOverrides(applied)
        return state.tasks.map { task ->
            val o = overrides[task.id] ?: Mine.NONE
            if (o == task.mine) task else task.withMine(o)
        }.let(Duplicates::linksPresent).let(Duplicates::credited)
    }

    /**
     * [day]'s breaks outside as [applied] leaves them (D-116), which a change to one is checked against: the settings' own
     * times on a day off school, as moved or taken off, and those added; today's begun by the starts kept (D-113).
     */
    fun breaksOn(day: LocalDate, applied: List<Instruction> = instructions.value.applied): DayBreaks {
        val now = clock.now()
        val zone = clock.zone()
        // A school day's, as a day off too, for a change after one making it so in the same answer.
        val withBreaks = SchoolDays.kindOf(day, school.value) == DayKind.Weekend
        val breaks = InstructionRules.dayBreaks(day, settings.value, withBreaks, applied)
            .let { if (withBreaks) it else it.copy(offSchool = InstructionRules.dayBreaks(day, settings.value, true, applied)) }
        if (day != Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) return breaks
        // Today's begun (D-113), by the starts kept: one under way at the start and end it was laid at, whatever the changes
        // now say of it, so a change over it is refused as the planner would keep it.
        val kept = session.value.slots.on(day).previous
        fun minute(at: Long) = ChronoUnit.MINUTES.between(day.atStartOfDay(), Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()).toInt()
        val begun = kept.mapNotNull { (key, start) ->
            val name = (Slot.outsideAt(key) ?: Slot.addedAt(key))?.takeIf { start <= now } ?: return@mapNotNull null
            Triple(name, start, kept[Slot.endKey(key)] ?: (start + settings.value.outsideBreakMin * 60_000L))
        }
        val underWay = begun.filter { (_, _, end) -> end > now }
        fun kept(day: DayBreaks) = day.copy(
            at = day.at + underWay.associate { (name, start, _) -> name to minute(start) },
            ends = underWay.associate { (name, _, end) -> name to minute(end) },
            begun = begun.mapTo(HashSet()) { it.first },
        )
        return kept(breaks).copy(offSchool = breaks.offSchool?.let(::kept))
    }

    /** What's been learned of the app's estimates, the plans made by it: a task's steps' minutes as planned ([TaskItem.plannedStepMinutes]). */
    fun calibration(): Calibration = learning.value.calibration

    /** The places where [change]'s steps are its task's in [before] as they were, as [after] plans them ([SlotState.unchanged]). */
    private fun unchangedBy(change: Change, before: List<TaskItem>, after: List<TaskItem>): Set<Int> {
        val was = before.firstOrNull { it.id == change.taskId } ?: return emptySet()
        val will = after.firstOrNull { it.id == change.taskId } ?: return emptySet()
        val calibration = calibration()
        return SlotState.unchanged(was, change.steps.orEmpty(), was.plannedStepMinutes(calibration.multiplierFor(was)), will.plannedStepMinutes(calibration.multiplierFor(will)))
    }

    /**
     * The plan at [now], of [state] with [applied] instructions and the calendar's [time]: as they are, or a
     * snapshot of them taken once ([chatContext]). With [trying], as if those were applied too, a task they
     * give new steps letting go of its starts kept today, as applying them does: a rebalance tried (D-110).
     */
    fun plan(
        state: TaskState = tasks.value,
        settings: Settings = this.settings.value,
        now: Long = clock.now(),
        applied: List<Instruction> = instructions.value.applied,
        time: EventJudge.Time = calendarTime(),
        trying: List<Instruction> = emptyList(),
    ): Plan {
        val zone = clock.zone()
        // Your instructions' days and times, over as far as the plan can reach.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val reach = today.plusDays(PLAN_REACH_DAYS)
        val tasks = asPlanned(state, applied + trying)
        // The tasks as they are, where [trying] gives some new steps: those it leaves as they were keep their starts.
        val before = if (trying.isEmpty()) emptyList() else asPlanned(state, applied)
        // Each day's windows, from the timetable's study periods and the school calendar (PL-15).
        val days = generateSequence(today) { it.plusDays(1) }.takeWhile { it <= reach }.toList()
        val calendar = school.value
        val windows = days.associateWith { SchoolDays.windows(it, settings, state.timetable, calendar) }
        val dayKinds = days.associateWith { SchoolDays.kindOf(it, calendar) }
        return Planner.plan(
            Planner.Input(
                tasks = tasks,
                now = now,
                zone = zone,
                settings = settings,
                calibration = learning.value.calibration,
                busy = time.busy,
                hardBusy = InstructionRules.busy(applied, zone, today, reach),
                dayLoads = time.dayLoads,
                dayCaps = InstructionRules.dayCaps(applied, today, reach),
                onlyDays = InstructionRules.onlyDays(applied, today, reach),
                // The tasks given a time and the breaks outside moved or added (D-116), the latest given first (D-114): the Day's
                // moves, the move sheet, Felix's and the calendar's hand moves are all changes of yours or his.
                pinOrder = applied.flatMap { it.changes }.flatMap { c ->
                    when (c.type) {
                        ChangeType.AtTime -> listOfNotNull(c.taskId)
                        // A move names a settings break or one added by its own time: whichever the day has.
                        ChangeType.OutsideAt -> c.date?.let { listOf("${Slot.OUTSIDE}$it:${c.outsideMin}", "${Slot.ADDED}$it:${c.outsideMin}") }.orEmpty()
                        ChangeType.OutsideAdded -> listOfNotNull(c.date?.let { day -> c.startMin?.let { "${Slot.ADDED}$day:$it" } })
                        else -> emptyList()
                    }
                }.reversed().distinct(),
                outsideDays = InstructionRules.outsideDays(applied, today, reach),
                workedTodayMin = workedToday(now, zone),
                windows = windows,
                dayKinds = dayKinds,
                // Today's pieces where the last plan laid them, where that's still free, and those whose
                // slots went by undone (PL-13).
                previousSlots = trying.flatMap { it.changes }.filter { it.type == ChangeType.Steps }
                    .fold(session.value.slots.on(today)) { s, c -> c.taskId?.let { s.released(it, now, unchangedBy(c, before, tasks)) } ?: s }.previous,
                missedSlots = session.value.slots.on(today).missed.map { it.key }.toSet(),
            ),
        )
    }

    /** Minutes worked in today's kept slots, as the day's record counts them (LR-09). */
    private fun workedToday(now: Long, zone: ZoneId): Int =
        Days.workedOn(log.value, Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()).values.sum()

    /** The pieces' first steps and Felix's next message, written after each plan (AC-10, AI-15). */
    private val ahead: Ahead = Ahead(tasks, settings, model, gateway, profile, draftAhead, notes) { watcher.poke() }

    /**
     * The plan made again as what it depends on changes (PL-13): today's slot starts kept, then the
     * slot alarms set to it and the widget redrawn.
     */
    val watcher: PlanWatcher = PlanWatcher(
        scope = scope,
        changes = merge(
            tasks.state,
            settings.state,
            instructions.state,
            calendarChanges,
            school.state,
            session.state.combine(learning.state) { s, l -> s.slots.previous to l.calibration }.distinctUntilChanged(),
        ),
        plan = { plan() },
        keep = ::keepSlots,
        hooks = hooks + listOf<suspend (Plan) -> Unit>(
            // Felix's next message written ahead (AC-10): by itself, as a model call takes a while.
            { made -> scope.launch { runCatching { ahead.run(made, clock.now()) }.onFailure { Log.w(AppGraph.TAG, "Writing ahead failed", it) } }; Unit },
        ),
        onFailure = onFailure,
    )

    /**
     * [made]'s today kept for the next plan (PL-13): its slots' starts, breaks outside begun or completed
     * among them (D-113), and which missed pieces went to another day. Written only where that changes
     * something, so keeping them doesn't make the plan again for nothing.
     */
    private suspend fun keepSlots(made: Plan) {
        // A slot whose grace went by before its alarm or a sweep could see it is missed first: once its
        // start is replaced by where this plan moved it, nothing would know it was.
        runCatching { sweep() }.onFailure { Log.w(AppGraph.TAG, "Looking at missed slots failed", it) }
        session.update { state ->
            val kept = state.slots.on(made.today).laid(made).reconciled(made)
            if (kept == state.slots) state else state.copy(slots = kept)
        }
    }

    companion object {
        /** How far ahead your instructions' days and times are laid out for the plan: as far as it reaches. */
        private const val PLAN_REACH_DAYS = 90L
    }
}
