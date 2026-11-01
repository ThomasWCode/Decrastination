package com.thomaswcode.decrastination.views

import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.longFinished
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.AddOwnChange
import com.thomaswcode.decrastination.sync.protocol.NotSame
import com.thomaswcode.decrastination.ui.Format
import com.thomaswcode.decrastination.ui.Groups
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/** The Work page as the website draws it (W-14): every open task by when it's due and by subject, in the phone's words. */
@Serializable
data class WorkView(
    /** The sources' labels, for the filter, each a task's [TaskTile.source]. */
    val sources: List<String>,
    val byDue: List<TaskGroup>,
    val bySubject: List<TaskGroup>,
    val notPlanned: List<UnplannedTile>,
    val hidden: List<TaskTile>,
    val finished: List<TaskTile>,
    val read: List<SourceLine>,
    /** When the phone last told the server anything. */
    val asOf: Long? = null,
    /** Whether anything can be changed here: the server applies commands. */
    val canChange: Boolean = false,
    /** What the form for a task of your own takes, while one can be made here. */
    val form: OwnTaskFormView? = null,
)

/** The form for a task of your own: its kinds and limits, so the page holds none of them (D-137). */
@Serializable
data class OwnTaskFormView(val kinds: List<KindChoice>, val maxTitle: Int, val maxClass: Int, val maxNotes: Int, val maxSteps: Int, val defaultMinutes: Int)

@Serializable
data class KindChoice(val name: String, val label: String, val event: Boolean)

@Serializable
data class TaskGroup(val title: String, val tasks: List<TaskTile>)

/** A task as a tile: its name, minutes and when it's due, where it's from, and what's out of the ordinary as tags. */
@Serializable
data class TaskTile(
    val id: String,
    val title: String,
    val minutes: String,
    val due: String,
    val overdue: Boolean,
    val source: String,
    val line: String,
    val tags: List<String> = emptyList(),
    /** Another task it looks like the same work as, "“x” from Teams". */
    val sameAs: String? = null,
    /** What saying it's the same, and that it isn't, send. */
    val same: Action? = null,
    val different: Action? = null,
)

@Serializable
data class UnplannedTile(val id: String, val source: String, val title: String, val why: String, val minutes: String)

/** How a source was last read. */
@Serializable
data class SourceLine(val source: String, val read: String, val open: Int, val error: String? = null, val note: String? = null)

object WorkViews {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    private val FORM = OwnTaskFormView(
        OwnTaskRules.KINDS.map { KindChoice(it.name, it.label, it == Kind.Event) },
        OwnTaskRules.MAX_TITLE, OwnTaskRules.MAX_CLASS, OwnTaskRules.MAX_NOTES, OwnTaskRules.MAX_STEPS, Kind.Homework.defaultEffortMin,
    )

    /** The page at [now], of [state] by [plan], your classes in [settings]. [writable]: the server applies commands, so it can be changed here. */
    fun of(state: TaskState, plan: Plan, settings: Settings, now: Long, zone: ZoneId, asOf: Long?, writable: Boolean = false): WorkView {
        val byId = state.tasks.associateBy { it.id }
        val pairs = Duplicates.candidates(state.tasks, settings.classMap, state.notSame)
        val alike = pairs.flatMap { (a, b) -> listOf(a.id to b, b.id to a) }.groupBy({ it.first }, { it.second })
        fun tile(task: TaskItem) = tile(task, now, zone, byId, alike[task.id]?.firstOrNull(), writable)
        val open = state.tasks.filter { it.isOpen && !it.hidden }
        val hidden = state.tasks.filter { it.isOpen && (it.mine.notATask != null || it.mine.sameAs?.value != null) }.sortedBy { it.title.lowercase(Locale.UK) }
        val finished = state.tasks.filter { !it.isOpen && !it.hidden && !it.longFinished(now) }.sortedByDescending { it.doneAt ?: it.lastSeenAt }
        return WorkView(
            sources = (Source.SYNCED + Source.Own).map { it.label },
            byDue = Groups.byDue(open, now, zone).map { (group, tasks) -> TaskGroup(group.title, tasks.map(::tile)) },
            bySubject = Groups.bySubject(open, settings.classMap, now).map { (title, tasks) -> TaskGroup(title, tasks.map(::tile)) },
            notPlanned = plan.unplanned.map { unplanned(it, plan, now, zone) },
            hidden = hidden.map(::tile),
            finished = finished.map(::tile),
            read = Source.SYNCED.map { source ->
                val status = state.status(source)
                val read = status.lastSuccessAt?.let { "Read ${Format.ago(it, now)}" } ?: "Not read yet"
                SourceLine(source.label, read + status.dataAsOf?.let { " · its data from ${Format.ago(it, now)}" }.orEmpty(), state.tasks.count { it.source == source && it.isOpen && !it.hidden }, status.error, status.note)
            },
            asOf = asOf,
            canChange = writable,
            form = if (writable) FORM else null,
        )
    }

    internal fun tile(task: TaskItem, now: Long, zone: ZoneId, byId: Map<String, TaskItem>, alike: TaskItem?, writable: Boolean = false): TaskTile {
        val due = task.deadlineAt(now) ?: task.dueAt
        val hard = task.hardAt(now)
        val hiddenUntil = task.availableFrom?.takeIf { it > now }
        val startsAt = (if (task.source == Source.Own) task.notBefore else task.mine.from?.value)?.takeIf { task.isOpen && it > now }
        val steps = task.subSteps.takeIf { it.isNotEmpty() && task.isOpen }
        val tags = buildList {
            if (!task.isOpen) return@buildList
            if (task.mine.notATask != null) add("Not a task")
            task.mine.sameAs?.value?.let { byId[it] }?.let { add("Same as “${it.title}”") }
            if (Enrichments.current(task)?.dropped != null) add("Claude's plan dropped")
            hiddenUntil?.let { add("Hidden till ${Format.at(it, now, zone)}") }
            if (task.mine.after?.value != null && InstructionRules.waiting(task, byId)) add("Waits for another task")
            if (task.mine.dueAt?.value != null) add("Due date yours")
            if (task.mine.minutes?.minutes != null) add(if (task.pacedMinutes) "Minutes at your pace" else "Minutes yours")
            startsAt?.let { add("Starts ${Format.at(it, now, zone)}") }
            steps?.let { s -> add("${s.count { it.done }}/${s.size} steps") }
        }
        return TaskTile(
            id = task.id,
            title = task.title,
            minutes = Format.minutes(task.effortMin),
            due = if (task.isOpen) Format.due(due, hard, now, zone) else task.status.name,
            overdue = task.isOpen && hard && due?.let { it < now } == true,
            source = task.source.label,
            line = listOfNotNull(task.source.label, task.kind.label, task.className, task.extra[GmailThreads.EXTRA_FROM]).joinToString(" · "),
            tags = tags,
            sameAs = alike?.let { "“${it.title}” from ${it.source.label}" },
            same = alike?.takeIf { writable }?.let { AddOwnChange(Ids.next(), "Same as “${it.title}”", About(taskId = task.id, taskTitle = task.title), Change(ChangeType.SameAs, taskId = task.id, afterTaskId = it.id)) },
            different = alike?.takeIf { writable }?.let { NotSame(task.id, it.id) },
        )
    }

    private fun unplanned(gap: Unplanned, plan: Plan, now: Long, zone: ZoneId): UnplannedTile {
        val lastDay = plan.buckets.lastOrNull()?.date ?: plan.today
        val why = when (gap.reason) {
            Unplanned.Reason.NotOpen -> "Can't be started before ${gap.at?.let { Format.at(it, now, zone) } ?: "later"}, its source says"
            Unplanned.Reason.Waits -> "Waits until “${gap.after ?: "another task"}” is done, as you said"
            Unplanned.Reason.OpensLater -> "Opens ${gap.at?.let { Format.at(it, now, zone) } ?: "later"}, after the plan's last day: planned once it's within reach"
            Unplanned.Reason.NoRoom -> "No room for it by ${lastDay.format(DAY)}, the plan's last day, within each day's limits"
        }
        return UnplannedTile(gap.taskId, gap.source.label, gap.title, "$why · ${gap.source.label}", (if (gap.pieces > 1) "${gap.pieces} pieces, " else "") + Format.minutes(gap.minutes))
    }
}
