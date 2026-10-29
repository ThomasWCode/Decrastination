package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnStep
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.OwnTaskRules
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The form for a task of your own (Q28): a new one (from [initial], Claude's, if given), or
 * [editing] one. Saved under an id chosen as it opens ([com.thomaswcode.decrastination.tasks.OwnTasks.saveOwnTask]), so a second tap, or
 * the form made again after a turn of the phone, changes the one task; or, given [onSave], handed
 * to that (a card of Claude's, added as you've changed it). Every field is kept through a turn too.
 */
@Composable
fun OwnTaskForm(graph: AppGraph, editing: TaskItem?, initial: OwnTask? = null, onSave: (suspend (OwnTask) -> Boolean)? = null, onClose: () -> Unit) {
    val zone = graph.clock.zone()
    val now = graph.clock.now()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val start = editing?.let(OwnTaskRules::draftOf) ?: initial ?: OwnTask(title = "")
    val id = rememberSaveable { editing?.sourceId ?: UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf(start.title) }
    var notes by rememberSaveable { mutableStateOf(start.notes) }
    var kind by rememberSaveable { mutableStateOf(start.kind.name) }
    var subject by rememberSaveable { mutableStateOf(start.className.orEmpty()) }
    var due by rememberSaveable { mutableStateOf(start.dueAt) }
    var from by rememberSaveable { mutableStateOf(start.startFrom) }
    var hours by rememberSaveable { mutableStateOf((start.minutes / 60).toString()) }
    var minutes by rememberSaveable { mutableStateOf((start.minutes % 60).toString()) }
    // Its steps as you change them (UI-15), kept through a turn of the phone as their words.
    var stepsText by rememberSaveable { mutableStateOf(STEPS.encodeToString(ListSerializer(StepRow.serializer()), rowsOf(start, editing != null))) }
    val rows = STEPS.decodeFromString(ListSerializer(StepRow.serializer()), stepsText)
    fun setRows(next: List<StepRow>) { stepsText = STEPS.encodeToString(ListSerializer(StepRow.serializer()), next) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    // A save under way, this form's or the one before a turn of the phone: closed once it's kept, Save back if it isn't.
    LaunchedEffect(saving) {
        if (saving) {
            if (FormSaves.result(id) == true) onClose() else saving = false
        }
    }
    val chosen = Kind.valueOf(kind)
    val event = chosen == Kind.Event

    fun draft() = start.copy(
        title = title,
        notes = notes,
        kind = chosen,
        className = subject,
        dueAt = due,
        startFrom = from.takeUnless { event },
        // With steps, its minutes are theirs together.
        minutes = if (rows.isEmpty()) (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0) else rows.sumOf { it.min },
        steps = stepsOf(rows, start),
        fromForm = true,
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
                    Text(
                        if (editing == null) "New task" else "Edit task",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !saving, onClick = {
                        val made = draft()
                        problem = OwnTaskRules.problem(made)
                        if (problem == null) {
                            saving = true
                            // In the app's scope, past this form's: a turn of the phone mid-save doesn't stop it (a photo's
                            // attaching with it), and the form made again waits for how it went (U-02).
                            FormSaves.start(graph.scope, id) { onSave?.invoke(made) ?: graph.ownTasks.saveOwnTask(id, made) }
                        }
                    }) { Text("Save", fontWeight = FontWeight.Bold) }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        title,
                        { title = it.take(OwnTaskRules.MAX_TITLE) },
                        label = { Text("What it is") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OwnTaskRules.KINDS.forEach { k -> FilterChip(selected = chosen == k, onClick = { kind = k.name }, label = { Text(k.label) }) }
                    }
                    OutlinedTextField(
                        subject,
                        { subject = it.take(OwnTaskRules.MAX_CLASS) },
                        label = { Text("Subject (if any)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Choice(
                        if (event) "When" else "Due",
                        due?.let { Format.at(it, now, zone) } ?: if (event) "Pick a time" else "No due date",
                        onPick = { picking = "due" },
                        onClear = if (due != null && !event) ({ due = null }) else null,
                    )
                    if (!event) {
                        // Its steps, each changed here (UI-15): in order, with the total.
                        Text("Steps", style = MaterialTheme.typography.titleSmall)
                        rows.forEachIndexed { i, row ->
                            StepEditor(
                                row,
                                first = i == 0,
                                last = i == rows.lastIndex,
                                onChange = { changed -> setRows(changedRow(rows, i, changed)) },
                                onMove = { by -> setRows(movedRow(rows, i, by)) },
                                onDelete = { setRows(rows.filterIndexed { j, _ -> j != i }) },
                            )
                        }
                        if (rows.size < OwnTaskRules.MAX_STEPS) TextButton(onClick = { setRows(rows + StepRow("", "30")) }) { Text("Add a step") }
                        if (rows.isEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    hours,
                                    { hours = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Hours") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    minutes,
                                    { minutes = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Minutes") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text("How long it takes in all.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                "${rows.size} step${if (rows.size == 1) "" else "s"}, ${Format.minutes(rows.sumOf { it.min })} in all: how long it takes is theirs together.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Choice(
                            "Can't start before",
                            from?.let { Format.at(it, now, zone) } ?: "Any time",
                            onPick = { picking = "from" },
                            onClear = if (from != null) ({ from = null }) else null,
                        )
                    }
                    OutlinedTextField(
                        notes,
                        { notes = it.take(OwnTaskRules.MAX_NOTES) },
                        label = { Text("Notes") },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "It's planned and blocks like any task. Done, on its page, finishes it but earns no free time; its slots kept earn theirs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    when (picking) {
        "due" -> DateTimeDialog(
            title = if (event) "When" else "Due",
            initialDay = due?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { it >= today } ?: today,
            initialTime = due?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: if (event) LocalTime.of(16, 0) else LocalTime.of(23, 59),
            earliest = today,
            onClose = { picking = null },
        ) { day, time ->
            picking = null
            due = day.atTime(time!!).atZone(zone).toInstant().toEpochMilli()
        }
        "from" -> DateTimeDialog(
            title = "Can't be started before",
            initialDay = from?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today.plusDays(1),
            initialTime = null,
            earliest = today,
            onClose = { picking = null },
        ) { day, _ ->
            picking = null
            from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}

/**
 * A step as the form has it (UI-15): its words as typed, where it can be done, and the step it was; and its
 * own dates and whether it needs the steps before it (D-109), kept with the row, a task Felix proposed's too.
 */
@Serializable
internal data class StepRow(
    val title: String,
    val minutes: String,
    val where: String? = null,
    val wasIndex: Int? = null,
    val anyOrder: Boolean = false,
    val from: Long? = null,
    val dueAt: Long? = null,
) {
    val min: Int get() = minutes.toIntOrNull() ?: 0
}

/** [start]'s steps as the form's rows, each knowing the step it was where you're [editing] a task of yours. */
internal fun rowsOf(start: OwnTask, editing: Boolean): List<StepRow> =
    start.steps.mapIndexed { i, st -> StepRow(st.title, st.minutes.toString(), st.where, wasIndex = i.takeIf { editing }, anyOrder = st.anyOrder, from = st.from, dueAt = st.dueAt) }

/** [rows] with row [i] as [changed]: its mark (D-109) as its chip has it, through a new title too. */
internal fun changedRow(rows: List<StepRow>, i: Int, changed: StepRow): List<StepRow> = rows.mapIndexed { j, r -> if (j == i) changed else r }

/** [rows] with row [i] moved [by] one place, its mark (D-109) going with it: its chip shows it there. */
internal fun movedRow(rows: List<StepRow>, i: Int, by: Int): List<StepRow> = rows.toMutableList().also { it.add(i + by, it.removeAt(i)) }

/**
 * The form's [rows] as the task's steps, as listed: each with its own dates and mark as its row had them, and
 * its id where it was one of [start]'s. The first step's mark is false, having none before it (D-109): a row
 * moved to the top, or left there, keeps none its chip, not shown there, couldn't clear.
 */
internal fun stepsOf(rows: List<StepRow>, start: OwnTask): List<OwnStep> = rows.mapIndexed { i, r ->
    val was = r.wasIndex?.let { start.steps.getOrNull(it) }
    OwnStep(r.title.trim(), r.min, r.from, r.dueAt, r.where, id = was?.id.orEmpty(), wasIndex = r.wasIndex, anyOrder = r.anyOrder && i > 0)
}

private val STEPS = Json { ignoreUnknownKeys = true }

/** One step: its name, its minutes, whether it needs home, and its moves and delete. */
@Composable
private fun StepEditor(row: StepRow, first: Boolean, last: Boolean, onChange: (StepRow) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                row.title,
                { onChange(row.copy(title = it.take(OwnTaskRules.MAX_TITLE))) },
                label = { Text("Step") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    row.minutes,
                    { onChange(row.copy(minutes = it.filter(Char::isDigit).take(4))) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = !first, onClick = { onMove(-1) }) { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Up", modifier = Modifier.rotate(180f)) }
                IconButton(enabled = !last, onClick = { onMove(1) }) { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Down") }
                IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Delete the step") }
            }
            // Where it can be done, and after the first whether it needs the steps before it (D-109): a line of
            // their own, as a second chip beside the minutes would leave them no room.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = row.where == "home", onClick = { onChange(row.copy(where = if (row.where == "home") "anywhere" else "home")) }, label = { Text("Home only") })
                if (!first) FilterChip(selected = row.anyOrder, onClick = { onChange(row.copy(anyOrder = !row.anyOrder)) }, label = { Text("Any order") })
            }
        }
    }
}

/**
 * The task form's saves, by the task's id, run in the app's scope past the form (U-02): what each came to, true kept,
 * false not, till the form made again after a turn of the phone has read it. None for an id means none is under way
 * (the app was started afresh since), so the form's Save comes back.
 */
internal object FormSaves {
    private val results = MutableStateFlow<Map<String, Boolean?>>(emptyMap())

    /** [save] for the task [id] begun in [scope]; under way till it's done. */
    fun start(scope: CoroutineScope, id: String, save: suspend () -> Boolean) {
        results.update { it + (id to null) }
        scope.launch {
            val kept = runCatching { save() }.getOrDefault(false)
            results.update { it + (id to kept) }
        }
    }

    /** How [id]'s save went, once it's done (null: none was under way), let go of once read. */
    suspend fun result(id: String): Boolean? {
        val done = results.first { id !in it || it[id] != null }[id]
        results.update { it - id }
        return done
    }
}

/** A value picked in a dialog: [label], what it is, and the buttons to pick it and to clear it. */
@Composable
private fun Choice(label: String, value: String, onPick: () -> Unit, onClear: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        onClear?.let { TextButton(onClick = it) { Text("Clear") } }
        TextButton(onClick = onPick) { Text("Change") }
    }
}
