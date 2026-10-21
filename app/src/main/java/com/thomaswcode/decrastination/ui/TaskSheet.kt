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
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Invite
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.invite
import com.thomaswcode.decrastination.enrich.Readings
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import com.thomaswcode.decrastination.core.GmailWrites
import java.time.Instant
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
    val learning by graph.stores.learning.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    // Its notes, read as they change (8a).
    val felix by graph.stores.felix.state.collectAsStateWithLifecycle()
    // A starred email's finish in Gmail (Stage 11), read as it goes.
    val gmail by graph.gmailFinishes.state.collectAsStateWithLifecycle()
    // His messages unread, worn by Ask Felix about it as by his button elsewhere (UI-16).
    val unread by graph.conversation.unread.collectAsStateWithLifecycle(0)
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
    // The sheet's state and its changes ([TaskSheetState]), kept through a turn of the phone.
    val sheet = rememberSaveable(saver = TaskSheetState.saver(graph.instructions, graph.ownTasks, graph.conversation::addNote, graph.conversation::deleteNote)) {
        TaskSheetState(graph.instructions, graph.ownTasks, graph.conversation::addNote, graph.conversation::deleteNote)
    }
    var picking by sheet::picking
    var confirming by sheet::confirming
    var message by sheet::message
    // What an action here said (a piece done, moved later), over the screen's last word.
    var said by sheet::said
    var showInstructions by sheet::showInstructions
    val own = task.source == Source.Own
    val changeable = task.isOpen && !task.derived
    val byId = state.tasks.associateBy { it.id }
    // Its next piece in the plan: to tick, photograph or move.
    val next = TaskSheetModel.next(plan, task.id).takeIf { task.isOpen }
    // An email's steps and your own are ticked here; the rest are what their source says.
    val ticksHere = Focus.tickable(task) && task.subSteps.isNotEmpty()
    // A photo of the work (AI-23), for a task Claude reads: kept with it, and read with it.
    val shot = androidx.compose.runtime.remember { java.io.File(activity.cacheDir, "photos/work.jpg").also { it.parentFile?.mkdirs() } }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { taken ->
        if (!taken) {
            shot.delete()
            return@rememberLauncherForActivityResult
        }
        graph.scope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.thomaswcode.decrastination.block.PhotoChecks.shrink(shot).also { shot.delete() } }
            if (jpeg == null) {
                message = "Couldn't read the photo."
            } else {
                graph.photos.attachPhoto(task.id, jpeg)
                said = if (graph.gateway.claudeKey() != null) "Photo kept: Claude reads the task again with it." else "Photo kept: it's read with the task once Claude is on."
            }
        }
    }
    // Undo only where a quick change of yours is what's in force: one of Claude's since wins.
    fun quick(type: ChangeType) = InstructionRules.quickInForce(instructions.applied, task.id, type)

    /** Your own task changed as [change] says, or a source's given it as a quick change ([TaskSheetState.make]). */
    fun make(change: Change, text: String) {
        scope.launch { sheet.make(task, change, text) }
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
                        Text("Next: ${piece.shown ?: if (piece.parts > 1) "part ${piece.part} of ${piece.parts}" else piece.title}", style = MaterialTheme.typography.bodyLarge)
                        Text(Format.minutes(piece.minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { scope.launch { said = keepPiece(graph, piece, plan.now) } }) { Text("Done") }
                }
            }
            if (ticksHere) Steps(graph, task, now, zone)
            // An invite the email carries (DX-02): its time, and a tap putting it in your calendar.
            val invite = task.invite
            invite?.let { Text("Invite: " + Format.invite(it, zone), style = MaterialTheme.typography.bodyLarge) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!own) Button(onClick = { scope.launch { TaskOpener.open(activity, task)?.let { message = it } } }) { Text("Open in ${task.source.label}") }
                invite?.let { i -> OutlinedButton(onClick = { message = addToCalendar(activity, i, task.title, zone) }) { Text("Add to calendar") } }
                // A starred email finished by your word (Stage 11): unstarred and archived in Gmail, after the grace.
                if (task.source == Source.Gmail && task.isOpen && graph.settings.value.gmailFinishes && GmailWrites.state(gmail.writes, task.id) !in FINISHING) {
                    OutlinedButton(onClick = { scope.launch { said = finishSaid(graph.gmailFinishes.finish(task.id, explicit = true)) } }) { Text("Finish: unstar and archive") }
                }
                // Written work: a photo shows it done, once Claude is on (Q16).
                if (next != null && photo != null && photo.available && next.photoCheckable) {
                    OutlinedButton(enabled = !photo.checking, onClick = { photo.take(next) }) { Text("Check with a photo") }
                }
                // The work itself photographed, so Claude reads its size from it (AI-23).
                if (task.isOpen && Enrichments.jobFor(task, GmailThreads::textRead) != null) {
                    OutlinedButton(onClick = { camera.launch(androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.photos", shot)) }) {
                        Text(if (task.photo == null) "Add a photo of the work" else "Photograph the work again")
                    }
                }
                when {
                    own && task.isOpen -> Button(onClick = { scope.launch { sheet.finish(task) } }) { Text("All of it done") }
                    own -> Button(onClick = { scope.launch { sheet.reopen(task) } }) { Text("Not done") }
                }
            }
            when {
                own && task.isOpen -> Text(
                    "All of it done earns no free time: nothing but you says it's done. Its pieces earn theirs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !own && task.isOpen -> Text(
                    if (task.tickDone) "It's done once you tick it off, or when ${task.source.label} no longer lists it." else "It's done when ${task.source.label} says so, read at each sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Its finish in Gmail, where there's one (Stage 11): what it is, with its Undo or It's sorted.
            if (task.source == Source.Gmail) GmailFinishLine(graph, task)

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
                        if (!own && task.mine.dueAt?.value != null) "Yours: ${task.source.label} says ${sourceDue?.let { Format.at(it, now, zone) } ?: "no date"}" else null,
                        // Where Claude's reading came from (AI-09); one from a photo says what it saw, under Takes.
                        Enrichments.current(task)?.reason?.takeUnless { fromPhoto(task) }?.let { "Claude: “$it” in ${if (task.source == com.thomaswcode.decrastination.core.Source.Gmail) "the email" else "the instructions"}" },
                    ).joinToString(". ").takeIf { it.isNotEmpty() },
                    urgent = task.isOpen && task.hardAt(now) && task.deadlineAt(now)?.let { it < now } == true,
                    onChange = if (changeable) ({ picking = OwnChange.DueOn }) else null,
                    undo = when {
                        own && changeable && task.dueAt != null -> "Clear" to { make(Change(ChangeType.DueBy, taskId = task.id, time = null), "") }
                        !own && changeable && quick(ChangeType.DueBy) -> "Undo" to { scope.launch { sheet.undo(task, ChangeType.DueBy) } }
                        else -> null
                    },
                )
                Field(
                    "Takes",
                    "${Format.minutes(task.effortMin)} in all" + if (task.workedMin > 0) ", ${Format.minutes(task.workedMin)} worked" else "",
                    note = listOfNotNull(
                        if (!own && task.mine.minutes?.minutes != null) {
                            // A total Felix worked out at your pace (17 Oct), or your own.
                            if (task.pacedMinutes) {
                                "The app's ${Format.minutes(task.mine.minutes?.paced?.from ?: task.appEffortMin)} at your pace" +
                                    if (task.mine.minutes?.paced?.from == null || task.mine.minutes?.paced?.from == task.appEffortMin) "" else ", which it now reckons ${Format.minutes(task.appEffortMin)}"
                            } else {
                                "Yours: the app reckoned ${Format.minutes(task.appEffortMin)}"
                            }
                        } else {
                            null
                        },
                        if (task.subSteps.isNotEmpty() && task.mine.minutes?.minutes != null) "Its steps are scaled to match" else null,
                        // Read from its photo (AI-23), with what Claude saw in it.
                        if (fromPhoto(task)) "From your photo" + (Enrichments.current(task)?.reason?.takeIf { it.isNotBlank() }?.let { ": “$it”" } ?: "") else null,
                        // The app's estimate as the plan takes it: by how long such work has taken you.
                        learning.calibration.multiplierFor(task).takeIf { kotlin.math.abs(it - 1.0) >= 0.05 }?.let { m ->
                            "Planned as ${Format.minutes((task.effortMin * m).roundToInt())}: such work has taken you ${if (m > 1) "longer" else "less time"}"
                        },
                    ).joinToString(". ").takeIf { it.isNotEmpty() },
                    onChange = if (changeable) ({ picking = OwnChange.Minutes }) else null,
                    undo = if (!own && changeable && quick(ChangeType.Minutes)) "Undo" to { scope.launch { sheet.undo(task, ChangeType.Minutes) } } else null,
                )
                val start = if (own) task.notBefore else task.mine.from?.value
                val hiddenUntil = task.availableFrom?.takeIf { it > now && !own }
                Field(
                    "Can't start before",
                    start?.takeIf { it > now }?.let { Format.at(it, now, zone) } ?: "Any time",
                    note = hiddenUntil?.let { "Hidden from the plan until ${Format.at(it, now, zone)}, ${task.source.label} says" },
                    onChange = if (changeable) ({ picking = OwnChange.StartFrom }) else null,
                    undo = when {
                        own && changeable && task.notBefore != null -> "Clear" to { make(Change(ChangeType.StartFrom, taskId = task.id, time = null), "") }
                        !own && changeable && quick(ChangeType.StartFrom) -> "Undo" to { scope.launch { sheet.undo(task, ChangeType.StartFrom) } }
                        else -> null
                    },
                )
            }
            Notes(task, now, zone, byId, onOpen = { scope.launch { TaskOpener.open(activity, task)?.let { message = it } } }, onLink = { message = openLink(activity, it) })
            // What's kept with it (8a): Felix's notes and yours, the standing ones it matches, each with its delete; yours added here (Q14: A).
            TaskSheetModel.notes(task, felix.notes, graph.settings.value.classMap, graph.settings.value.felixName, now, zone).let { notes ->
                if (notes.isNotEmpty()) Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                notes.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            // A standing one goes from every task it's for: asked first.
                            if (n.every != null) {
                                confirming = "unnote:${n.id}"
                            } else {
                                scope.launch { sheet.unnote(n.id) }
                            }
                        }) { Text("Delete") }
                    }
                }
                TextButton(onClick = { sheet.noting = true }) { Text("Add a note") }
            }
            if (!ticksHere) Steps(graph, task, now, zone)
            // Its next piece's first steps (AI-15), as written ahead.
            next?.let { model.microPlans[it.key] }?.let(com.thomaswcode.decrastination.enrich.MicroPlans::lines)?.takeIf { it.isNotEmpty() }?.let { lines ->
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
                    OutlinedButton(onClick = { scope.launch { sheet.later(piece, now, zone) } }) { Text("Later today") }
                    val tomorrow = today.plusDays(1)
                    if (TaskSheetModel.canGo(task, tomorrow, now, zone)) {
                        OutlinedButton(onClick = { scope.launch { sheet.tomorrow(task, tomorrow) } }) { Text("Tomorrow") }
                    }
                    OutlinedButton(onClick = { onMove(piece.key) }) { Text("Move…") }
                }
                if (changeable && task.mine.notATask == null) OutlinedButton(onClick = { confirming = "not-a-task" }) { Text("Not a task") }
                if (task.isOpen) {
                    OutlinedButton(onClick = onInstruct) {
                        Text("Ask ${graph.settings.value.felixName} about it")
                        if (unread > 0) Badge(Modifier.padding(start = 8.dp)) { Text("$unread") }
                    }
                }
                // Claude's reading made again, as the task is now (AC-06).
                if (task.isOpen && Enrichments.jobFor(task, GmailThreads::textRead) != null && graph.gateway.claudeKey() != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                said = when (graph.readings.readAgain(task.id)) {
                                    Readings.ReadAgain.Read -> "Read again by Claude"
                                    Readings.ReadAgain.NotNow -> "Claude can't be asked now: try again later"
                                    Readings.ReadAgain.NoReading -> "No new reading came back: the one there stands"
                                }
                            }
                        },
                    ) { Text("Read again with Claude") }
                }
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
                val home = OwnTaskRules.draftOf(task).where == "home"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = home,
                        onClick = { scope.launch { sheet.homeOnly(task, !home) } },
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
            least = OwnTaskRules.minutesFor(task).first,
            onClose = { picking = null },
        ) { minutes ->
            picking = null
            val (change, text) = minutesChange(task, minutes)
            make(change, text)
        }
        else -> Unit
    }

    // Your note on it (8a): kept with it, told to Felix whenever it comes up.
    if (sheet.noting) {
        var words by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sheet.noting = false },
            title = { Text("A note on “${task.title}”") },
            text = {
                OutlinedTextField(
                    words, { words = it.take(TaskSheetModel.MAX_NOTE) },
                    label = { Text("Kept with it, and told to ${graph.settings.value.felixName} whenever it comes up") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(enabled = words.isNotBlank(), onClick = { scope.launch { sheet.note(task, words) } }) { Text("Keep") } },
            dismissButton = { TextButton(onClick = { sheet.noting = false }) { Text("Cancel") } },
        )
    }

    when (confirming) {
        "delete" -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete “${task.title}”?") },
            text = { Text("It goes from the plan and the list. Its finished focus sessions stay in the log.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    scope.launch { sheet.delete(task) }
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
                    scope.launch { sheet.notATask(task, today, zone) }
                }) { Text("Not a task") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
        // A standing note's delete (8a): it goes from every task it's for, not only this one.
        else -> TaskSheetModel.notes(task, felix.notes, graph.settings.value.classMap, graph.settings.value.felixName, now, zone).firstOrNull { it.every != null && confirming == "unnote:${it.id}" }?.let { n ->
            AlertDialog(
                onDismissRequest = { confirming = null },
                title = { Text("Delete the note for ${n.every}?") },
                text = { Text("It goes from every task it's for, not only this one.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirming = null
                        scope.launch { sheet.unnote(n.id) }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
            )
        }
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
private fun Notes(task: TaskItem, now: Long, zone: java.time.ZoneId, byId: Map<String, TaskItem>, onOpen: () -> Unit, onLink: (String) -> Unit) {
    val lines = buildList {
        task.mine.after?.value?.takeIf { task.isOpen && InstructionRules.waiting(task, byId) }?.let { add("Waits, as you said, until “${byId[it]?.title ?: it}” is done" to false) }
        if (task.mine.notATask != null) add("You said it isn't a task: take that back in Felix's chat, under Your changes" to false)
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
    // What's attached, which the app can't open: a tap opens it where it is, the email in Gmail
    // (DX-01) or the assignment in Teams (DX-08).
    GmailThreads.attachmentsOf(task).takeIf { it.isNotEmpty() }?.let { files ->
        Text(
            "Attachments: " + files.joinToString(", ") { it.label },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
    // An assignment's links (DX-08), each opened in the browser.
    com.thomaswcode.decrastination.sources.teams.TeamsRows.linksOf(task).forEach { link ->
        Text(
            link,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onLink(link) },
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

/**
 * [invite] as a new event in your calendar app, filled in for you to save (DX-02): its name (else
 * the email's [title]), its times and its place. What went wrong where no app takes it.
 */
private fun addToCalendar(activity: Activity, invite: Invite, title: String, zone: java.time.ZoneId): String? {
    val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.Events.TITLE, invite.summary.ifBlank { title })
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, invite.calendarTimes(zone).first)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, invite.calendarTimes(zone).second)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, invite.allDay(zone))
    invite.location?.let { intent.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
    return try {
        activity.startActivity(intent)
        null
    } catch (e: android.content.ActivityNotFoundException) {
        "No calendar app to add it to"
    }
}

/** [url] in the browser (DX-08); what went wrong where nothing on the phone opens it. */
private fun openLink(activity: Activity, url: String): String? = try {
    activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    null
} catch (e: android.content.ActivityNotFoundException) {
    "Nothing on the phone opens $url"
}

/** Whether [task]'s reading in force was made from its photo (AI-23): its reason then says what Claude saw, not a quote. */
private fun fromPhoto(task: TaskItem): Boolean = task.photo != null && Enrichments.current(task)?.fromPhoto == true

/** An email's finish going, or waiting for your OK: its sheet doesn't offer to finish it again meanwhile. */
private val FINISHING = setOf(GmailWrites.State.Awaiting, GmailWrites.State.Going)
