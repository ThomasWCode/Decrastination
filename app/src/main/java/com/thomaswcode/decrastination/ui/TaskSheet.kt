package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import com.thomaswcode.decrastination.core.OwnTasks
import com.thomaswcode.decrastination.core.Plan
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
 * A task opened from Day or Work, with what goes with it: the form for one of your own, the chat
 * about it, and its next piece moved (UI-04). [plan]: the screen's; [photo], its camera, for a
 * photo of the work; [note], its last word on the task (how a photo check went). [onClose] when
 * it's shut.
 */
@Composable
fun TaskSheetHost(graph: AppGraph, activity: Activity, taskId: String, plan: Plan, photo: PhotoCheck? = null, note: String? = null, onClose: () -> Unit) {
    // The form or the move open over the sheet, kept through a turn of the phone; and what a move said.
    var editing by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var moved by rememberSaveable { mutableStateOf<String?>(null) }
    val task = graph.tasks.value.tasks.firstOrNull { it.id == taskId }
    val piece = moving?.let { key -> plan.ordered.firstOrNull { it.key == key } }
    // Planned again meanwhile, the piece gone: back to the sheet.
    if (moving != null && piece == null) LaunchedEffect(moving) { moving = null }
    when {
        editing && task != null -> OwnTaskForm(graph, task) { editing = false }
        piece != null -> MoveSheet(graph, piece, plan, graph.clock.zone(), onDone = { said ->
            moving = null
            moved = "$said: undone in the chat's Your changes"
        }) { moving = null }
        else -> TaskSheet(graph, activity, taskId, plan, photo, moved ?: note, onClose, onInstruct = {
            task?.let { activity.startActivity(AskActivity.intent(activity, About(taskId = it.id, taskTitle = it.title))) }
        }, onEdit = { editing = true }, onMove = { moving = it })
    }
}

/**
 * A task (2.0.0, Q30), in three parts (UI-09). **Do**: its next piece done (its slot kept, LR-09),
 * its steps where they're ticked here, the task where it lives, a photo of the work, and one of
 * your own all done; a source's task has no Done of its own, as its source says. **About**: when
 * it's due, how long it takes and when it can start, each changed at once, armed or not; what it
 * says, its steps, when its pieces are planned and why, and the instructions on it, folded.
 * **Change**: its next piece later today, tomorrow or to a day you pick, not a task, Felix asked
 * about it, and one of your own edited, deleted, or kept for home. A source's task keeps each
 * change as a quick change (an instruction with no Claude) over what the source says, which *Undo*
 * takes back; one of your own is changed itself. A deck's task, which Anki makes afresh, can't be
 * changed, but its pieces move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(
    graph: AppGraph,
    activity: Activity,
    taskId: String,
    plan: Plan,
    photo: PhotoCheck?,
    note: String?,
    onClose: () -> Unit,
    onInstruct: () -> Unit,
    onEdit: () -> Unit,
    onMove: (String) -> Unit,
) {
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
    // What an action here said (a piece done, moved later), over the screen's last word.
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val own = task.source == Source.Own
    val changeable = task.isOpen && !task.derived
    val byId = state.tasks.associateBy { it.id }
    // Its next piece in the plan: to tick, photograph or move.
    val next = TaskSheetModel.next(plan, task.id).takeIf { task.isOpen }
    // An email's steps and your own are ticked here; the rest are what their source says.
    val ticksHere = Focus.tickable(task) && task.subSteps.isNotEmpty()
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
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            (said ?: note)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }

            // Do: its next piece, its steps, where it lives, a photo of the work.
            SectionHeading("Do", sheet = true)
            next?.let { piece ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Next: ${piece.step ?: if (piece.parts > 1) "part ${piece.part} of ${piece.parts}" else piece.title}", style = MaterialTheme.typography.bodyLarge)
                        Text(Format.minutes(piece.minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { scope.launch { said = keepPiece(graph, piece, now) } }) { Text("Done") }
                }
            }
            if (ticksHere) Steps(graph, task, now, zone)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!own) Button(onClick = { scope.launch { TaskOpener.open(activity, task)?.let { message = it } } }) { Text("Open in ${task.source.label}") }
                // Written work: a photo shows it done, once Claude is on (Q16).
                if (next != null && photo != null && photo.available && next.photoCheckable) {
                    OutlinedButton(enabled = !photo.checking, onClick = { photo.take(next) }) { Text("Check with a photo") }
                }
                when {
                    own && task.isOpen -> Button(onClick = { scope.launch { graph.finishOwnTask(task.id) } }) { Text("All of it done") }
                    own -> Button(onClick = { scope.launch { graph.reopenOwnTask(task.id) } }) { Text("Not done") }
                }
            }
            when {
                own && task.isOpen -> Text(
                    "All of it done earns no free time: nothing but you says it's done. Its pieces earn theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !own && task.isOpen -> Text(
                    "It's done when ${task.source.label} says so, read at each sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // About: when it's due, how long it takes, what it says, when it's planned and why.
            SectionHeading("About", sheet = true)
            if (task.kind == Kind.Event) {
                Field("When", task.dueAt?.let { Format.at(it, now, zone) } ?: "No time", onChange = if (own && changeable) ({ picking = OwnChange.DueOn }) else null)
            } else {
                val sourceDue = task.sourceValues?.dueAt
                Field(
                    "Due",
                    // The deadline in force: a plan's gone by gives way to the real one behind it (PL-14).
                    when (val due = task.deadlineAt(now) ?: task.dueAt) {
                        null -> "No due date"
                        // Only a real deadline is overdue (PL-14); a date the app or Claude set moves on.
                        else -> when {
                            due >= now || task.hardAt(now) -> (if (due < now) "Overdue: " else "") + Format.at(due, now, zone).replaceFirstChar(Char::uppercase)
                            else -> "Planned again: its date was " + Format.at(due, now, zone)
                        }
                    },
                    note = listOfNotNull(
                        if (!own && task.userDueAt != null) "Yours: ${task.source.label} says ${sourceDue?.let { Format.at(it, now, zone) } ?: "no date"}" else null,
                        // Where Claude's reading came from (AI-09).
                        Enrichments.current(task)?.reason?.let { "Claude: “$it” in ${if (task.source == com.thomaswcode.decrastination.core.Source.Gmail) "the email" else "the instructions"}" },
                    ).joinToString(". ").takeIf { it.isNotEmpty() },
                    urgent = task.isOpen && task.hardAt(now) && task.deadlineAt(now)?.let { it < now } == true,
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
            Notes(task, now, zone, byId, onOpen = { scope.launch { TaskOpener.open(activity, task)?.let { message = it } } })
            if (!ticksHere) Steps(graph, task, now, zone)
            // Its next piece's first steps (AI-15), as written ahead.
            next?.let { runtime.microPlans[it.key] }?.let(com.thomaswcode.decrastination.enrich.MicroPlans::lines)?.takeIf { it.isNotEmpty() }?.let { lines ->
                Text("Its next piece", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            // When its pieces are planned, and why (PL-07).
            TaskSheetModel.planned(plan, task.id, zone).takeIf { it.isNotEmpty() && task.isOpen }?.let { planned ->
                Text("Planned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                planned.take(MAX_PLANNED).forEach { Text("• " + TaskSheetModel.line(it, today, zone), style = MaterialTheme.typography.bodyMedium) }
                if (planned.size > MAX_PLANNED) Text("and ${planned.size - MAX_PLANNED} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // What you've said about it, folded.
            TaskSheetModel.instructions(instructions.applied, task.id).takeIf { it.isNotEmpty() }?.let { words ->
                Row(Modifier.fillMaxWidth().clickable { showInstructions = !showInstructions }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Instructions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if (showInstructions) "Hide" else "Show ${words.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (showInstructions) words.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            }

            // Change: its next piece moved, what it is, Felix asked, yours edited.
            SectionHeading("Change", sheet = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                next?.let { piece ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            said = graph.laterToday(piece.key)?.let { "Later today, at ${Format.at(it, now, zone)}: undone in the chat's Your changes" } ?: "No time left today for it"
                        }
                    }) { Text("Later today") }
                    val tomorrow = today.plusDays(1)
                    if (TaskSheetModel.canGo(task, tomorrow, zone)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                graph.addOwnChange(UUID.randomUUID().toString(), "Tomorrow", About(taskId = task.id, taskTitle = task.title), Change(ChangeType.OnDay, taskId = task.id, date = tomorrow.toString()))
                                said = "Tomorrow: undone in the chat's Your changes"
                            }
                        }) { Text("Tomorrow") }
                    }
                    OutlinedButton(onClick = { onMove(piece.key) }) { Text("Move…") }
                }
                if (changeable && !task.userNotATask) OutlinedButton(onClick = { confirming = "not-a-task" }) { Text("Not a task") }
                if (task.isOpen) OutlinedButton(onClick = onInstruct) { Text("Ask ${graph.settings.value.felixName} about it") }
                // Its steps made by Felix (AI-19): the chat, with the ask written for you to send.
                if (own && changeable) {
                    OutlinedButton(onClick = {
                        activity.startActivity(AskActivity.intent(activity, About(taskId = task.id, taskTitle = task.title), "Split this into steps", send = false))
                    }) { Text("Split with ${graph.settings.value.felixName}") }
                }
                if (own && task.isOpen) OutlinedButton(onClick = onEdit) { Text("Edit") }
                if (own) OutlinedButton(onClick = { confirming = "delete" }) { Text("Delete") }
            }
            // Where yours can be done (PL-15): anywhere, a free period at school too, or at home.
            if (own && changeable) {
                val home = OwnTasks.draftOf(task).where == "home"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = home,
                        onClick = { scope.launch { graph.saveOwnTask(task.sourceId, OwnTasks.draftOf(task).copy(where = if (home) "anywhere" else "home")) } },
                        label = { Text("Home only") },
                    )
                    Text(
                        if (home) "Planned at home, not in a free period at school" else "Planned anywhere, a free period at school too",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    scope.launch { graph.deleteOwnTask(task.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
        "not-a-task" -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Not a task?") },
            text = { Text("“${task.title}” comes off the plan and the list, as an email that's only to read does. It's listed under Not tasks, and taken back in Ask Claude, under Your changes.") },
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
private fun Notes(task: TaskItem, now: Long, zone: java.time.ZoneId, byId: Map<String, TaskItem>, onOpen: () -> Unit) {
    val lines = buildList {
        task.userAfter?.takeIf { task.isOpen && Instructions.waiting(task, byId) }?.let { add("Waits, as you said, until “${byId[it]?.title ?: it}” is done" to false) }
        if (task.userNotATask) add("You said it isn't a task: take that back in Felix's chat, under Your changes" to false)
        Enrichments.current(task)?.dropped?.takeIf { task.isOpen }?.let { add("Claude's plan was dropped ($it): planned as one piece" to true) }
        // Steps kept with their dates left out (AI-01).
        Enrichments.current(task)?.adjusted?.takeIf { it.isNotEmpty() && task.isOpen }?.let { lines ->
            add("Claude's dates for ${if (lines.size == 1) "one step were" else "${lines.size} steps were"} adjusted: ${lines.joinToString("; ")}" to false)
        }
        (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { add("Next: $it" to false) }
        task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { add("Steps and estimate by Claude" to false) }
    }
    lines.forEach { (text, bad) ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    // What's attached, which the app can't open: a tap opens Gmail, saying what to look for (DX-01).
    GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files ->
        Text(
            "Attachments: " + files.joinToString(", ") { it.label },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
    if (task.detail.isNotBlank()) Text(task.detail, style = MaterialTheme.typography.bodySmall)
}

/** The most of a task's pieces its sheet lists as planned: the rest are counted. */
private const val MAX_PLANNED = 6

/** [task]'s steps, each with its minutes and dates; an email's or one of your own's ticked off here. */
@Composable
private fun Steps(graph: AppGraph, task: TaskItem, now: Long, zone: java.time.ZoneId) {
    if (task.subSteps.isEmpty()) return
    val scope = rememberCoroutineScope()
    // As the plan shares them: your minutes over its steps add up to exactly yours.
    val planned = task.plannedStepMinutes(1.0)
    Text("Steps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    task.subSteps.forEachIndexed { index, step ->
        // A step's own date is a plan, never overdue (PL-14).
        val dates = listOfNotNull(step.from?.let { "from " + Format.at(it, now, zone) }, step.dueAt?.let { Format.due(it, hard = false, now, zone) }).joinToString(", ")
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
