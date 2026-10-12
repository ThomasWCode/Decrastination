package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A task opened from Tasks or the Plan, with what goes with it: the form for one of your own, and
 * the box for an instruction about it. [onClose] when it's shut.
 */
@Composable
fun TaskSheetHost(graph: AppGraph, activity: Activity, taskId: String, onClose: () -> Unit) {
    // What's open over the sheet, kept through a turn of the phone: the form, or an instruction.
    var editing by rememberSaveable { mutableStateOf(false) }
    var writing by rememberSaveable { mutableStateOf(false) }
    val task = graph.tasks.value.tasks.firstOrNull { it.id == taskId }
    when {
        editing && task != null -> OwnTaskForm(graph, task) { editing = false }
        writing && task != null -> InstructionDialog("About “${task.title}”", onDismiss = { writing = false }) { text ->
            writing = false
            onClose()
            sendInstruction(activity, graph, text, About(taskId = task.id, taskTitle = task.title))
        }
        else -> TaskSheet(graph, activity, taskId, onClose, onInstruct = { writing = true }, onEdit = { editing = true })
    }
}

/**
 * A task (2.0.0, Q30): what it is, and what you can change about it, each at once, armed or not:
 * when it's due, how long it takes, when it can start, and whether it's a task at all. A source's
 * task keeps each change as a quick change (an instruction with no Claude) over what the source
 * says, which *Undo* takes back; one of your own is changed itself, and can be edited whole,
 * finished (earning no free time) or deleted. A deck's task, which Anki makes afresh, can't be changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(graph: AppGraph, activity: Activity, taskId: String, onClose: () -> Unit, onInstruct: () -> Unit, onEdit: () -> Unit) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    // Read so a change taken back redraws what's yours.
    val instructions by graph.instructions.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.id == taskId }
    if (task == null) {
        // Gone (deleted, or aged out of the list): nothing to show.
        LaunchedEffect(taskId) { onClose() }
        return
    }
    val now = graph.clock.now()
    val zone = graph.clock.zone()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf<OwnChange?>(null) }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val own = task.source == Source.Own
    val changeable = task.isOpen && !task.derived
    val byId = state.tasks.associateBy { it.id }
    // Undo only where a quick change of yours is what's in force: one of Claude's since wins.
    fun quick(type: ChangeType) = Instructions.quickInForce(instructions.applied, task.id, type)

    /** Your own task changed as [change] says, or a source's given it as a quick change. */
    fun make(change: Change, text: String) {
        scope.launch {
            if (own) {
                val draft = OwnTasks.draftWith(task, change) ?: return@launch
                message = OwnTasks.problem(draft)
                if (message == null) graph.saveOwnTask(task.sourceId, draft)
            } else {
                graph.addOwnChange(UUID.randomUUID().toString(), text, About(taskId = task.id, taskTitle = task.title), change)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(task.source.label, task.kind.label, task.className, task.extra[GmailThreads.EXTRA_FROM]).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!task.isOpen) {
                Text(
                    if (task.status == Status.Done) "Done ${task.doneAt?.let { Format.at(it, now, zone) }.orEmpty()}" else "Missed",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (task.kind == Kind.Event) {
                Field("When", task.dueAt?.let { Format.at(it, now, zone) } ?: "No time", onChange = if (own && changeable) ({ picking = OwnChange.DueOn }) else null)
            } else {
                val sourceDue = task.sourceValues?.dueAt
                Field(
                    "Due",
                    when (val due = task.dueAt) {
                        null -> "No due date"
                        else -> (if (due < now) "Overdue: " else "") + Format.at(due, now, zone).replaceFirstChar(Char::uppercase)
                    },
                    note = when {
                        own -> null
                        task.userDueAt != null -> "Yours: ${task.source.label} says ${sourceDue?.let { Format.at(it, now, zone) } ?: "no date"}"
                        else -> null
                    },
                    urgent = task.isOpen && task.dueAt?.let { it < now } == true,
                    onChange = if (changeable) ({ picking = OwnChange.DueOn }) else null,
                    undo = when {
                        own && changeable && task.dueAt != null -> "Clear" to { make(Change(ChangeType.DueBy, taskId = task.id, time = null), "") }
                        !own && changeable && quick(ChangeType.DueBy) -> "Undo" to { scope.launch { graph.takeBackQuick(task.id, ChangeType.DueBy) } }
                        else -> null
                    },
                )
                Field(
                    "Takes",
                    "${Format.minutes(task.effortMin)} in all" + if (task.workedMin > 0) ", ${Format.minutes(task.workedMin)} worked" else "",
                    note = listOfNotNull(
                        if (!own && task.userEffortMin != null) "Yours: the app reckoned ${Format.minutes(task.appEffortMin)}" else null,
                        if (task.subSteps.isNotEmpty() && task.userEffortMin != null) "Its steps are scaled to match" else null,
                        // The app's estimate as the plan takes it: by how long such work has taken you.
                        runtime.calibration.multiplierFor(task).takeIf { kotlin.math.abs(it - 1.0) >= 0.05 }?.let { m ->
                            "Planned as ${Format.minutes((task.effortMin * m).roundToInt())}: such work has taken you ${if (m > 1) "longer" else "less time"}"
                        },
                    ).joinToString(". ").takeIf { it.isNotEmpty() },
                    onChange = if (changeable) ({ picking = OwnChange.Minutes }) else null,
                    undo = if (!own && changeable && quick(ChangeType.Minutes)) "Undo" to { scope.launch { graph.takeBackQuick(task.id, ChangeType.Minutes) } } else null,
                )
                val start = if (own) task.notBefore else task.userFrom
                val hiddenUntil = task.availableFrom?.takeIf { it > now && !own }
                Field(
                    "Can't start before",
                    start?.takeIf { it > now }?.let { Format.at(it, now, zone) } ?: "Any time",
                    note = hiddenUntil?.let { "Hidden from the plan until ${Format.at(it, now, zone)}, ${task.source.label} says" },
                    onChange = if (changeable) ({ picking = OwnChange.StartFrom }) else null,
                    undo = when {
                        own && changeable && task.notBefore != null -> "Clear" to { make(Change(ChangeType.StartFrom, taskId = task.id, time = null), "") }
                        !own && changeable && quick(ChangeType.StartFrom) -> "Undo" to { scope.launch { graph.takeBackQuick(task.id, ChangeType.StartFrom) } }
                        else -> null
                    },
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Notes(task, now, zone, byId)
            Steps(graph, task, now, zone)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    own && task.isOpen -> {
                        Button(onClick = { scope.launch { graph.finishOwnTask(task.id) } }) { Text("Done") }
                        OutlinedButton(onClick = onEdit) { Text("Edit") }
                        OutlinedButton(enabled = runtime.session?.taskId != task.id, onClick = { confirming = "delete" }) { Text("Delete") }
                    }
                    own -> {
                        Button(onClick = { scope.launch { graph.reopenOwnTask(task.id) } }) { Text("Not done") }
                        OutlinedButton(onClick = { confirming = "delete" }) { Text("Delete") }
                    }
                    else -> Button(onClick = { scope.launch { TaskOpener.open(activity, task)?.let { message = it } } }) { Text("Open in ${task.source.label}") }
                }
                if (changeable && !task.userNotATask) OutlinedButton(onClick = { confirming = "not-a-task" }) { Text("Not a task") }
                if (task.isOpen) OutlinedButton(onClick = onInstruct) { Text("Add an instruction") }
            }
            if (own && task.isOpen) {
                Text(
                    "Done earns no free time: nothing but you says it's done. Its focus sessions earn theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (picking) {
        OwnChange.DueOn -> DateTimeDialog(
            title = if (task.kind == Kind.Event) "When" else "Due",
            initialDay = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { it >= today } ?: today,
            initialTime = dueTimeOf(task, zone),
            earliest = today,
            onClose = { picking = null },
        ) { day, time ->
            picking = null
            val (change, text) = dueChange(task, day.atTime(time!!), zone)
            make(change, text)
        }
        OwnChange.StartFrom -> DateTimeDialog(
            title = "Can't be started before",
            initialDay = today.plusDays(1),
            initialTime = null,
            earliest = today,
            onClose = { picking = null },
        ) { day, _ ->
            picking = null
            val (change, text) = ownChange(task, OwnChange.StartFrom, day, zone)
            make(change, text)
        }
        OwnChange.Minutes -> MinutesDialog(
            title = "How long it takes in all",
            initial = task.effortMin,
            note = "The whole task, worked or not" + if (task.subSteps.isNotEmpty()) ": its steps are scaled to match, a minute each at least." else ".",
            least = OwnTasks.minutesFor(task).first,
            onClose = { picking = null },
        ) { minutes ->
            picking = null
            val (change, text) = minutesChange(task, minutes)
            make(change, text)
        }
        else -> Unit
    }

    when (confirming) {
        "delete" -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete “${task.title}”?") },
            text = { Text("It goes from the plan and the list. Its finished focus sessions stay in the log.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch { if (!graph.deleteOwnTask(task.id)) message = "Stop its focus session first" }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
        "not-a-task" -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Not a task?") },
            text = { Text("“${task.title}” comes off the plan and the list, as an email that's only to read does. It's listed under Not tasks, and taken back in Instructions.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val (change, text) = ownChange(task, OwnChange.NotATask, today, zone)
                    scope.launch { graph.addOwnChange(UUID.randomUUID().toString(), text, About(taskId = task.id, taskTitle = task.title), change) }
                }) { Text("Not a task") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

/** One thing about a task: [label], its [value] (red if [urgent]), a [note] under it, and what can be done about it. */
@Composable
private fun Field(label: String, value: String, note: String? = null, urgent: Boolean = false, onChange: (() -> Unit)? = null, undo: Pair<String, () -> Unit>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (urgent) MaterialTheme.colorScheme.error else Color.Unspecified)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        undo?.let { (text, action) -> TextButton(onClick = action) { Text(text) } }
        onChange?.let { TextButton(onClick = it) { Text("Change") } }
    }
}

/** What's out of the ordinary about [task], and what it says. */
@Composable
private fun Notes(task: TaskItem, now: Long, zone: java.time.ZoneId, byId: Map<String, TaskItem>) {
    val lines = buildList {
        task.userAfter?.takeIf { task.isOpen && Instructions.waiting(task, byId) }?.let { add("Waits, as you said, until “${byId[it]?.title ?: it}” is done" to false) }
        if (task.userNotATask) add("You said it isn't a task: take that back in Instructions" to false)
        if (task.isOpen && task.extra[Merge.EXTRA_FOLLOW_UP] == "true") add("Archived in Gmail: kept for its blocks till they're done" to false)
        Enrichments.current(task)?.dropped?.takeIf { task.isOpen }?.let { add("Claude's plan was dropped ($it): planned as one piece" to true) }
        (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { add("Next: $it" to false) }
        task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { add("Steps and estimate by Claude" to false) }
    }
    lines.forEach { (text, bad) ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    if (task.detail.isNotBlank()) Text(task.detail, style = MaterialTheme.typography.bodySmall)
}

/** [task]'s steps, each with its minutes and dates; an email's or one of your own's ticked off here. */
@Composable
private fun Steps(graph: AppGraph, task: TaskItem, now: Long, zone: java.time.ZoneId) {
    if (task.subSteps.isEmpty()) return
    val scope = rememberCoroutineScope()
    // As the plan shares them: your minutes over its steps add up to exactly yours.
    val planned = task.plannedStepMinutes(1.0)
    Text("Steps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    task.subSteps.forEachIndexed { index, step ->
        val dates = listOfNotNull(step.from?.let { "from " + Format.at(it, now, zone) }, step.dueAt?.let { Format.due(it, now, zone) }).joinToString(", ")
        val minutes = Format.minutes(planned[index])
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "• ${step.title} ($minutes${if (dates.isEmpty()) "" else "; $dates"})${if (step.done) " ✓" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (Focus.tickable(task) && task.isOpen && !step.done) TextButton(onClick = { scope.launch { graph.focus.tickBlock(task.id, index, step.title) } }) { Text("Done") }
        }
    }
}
