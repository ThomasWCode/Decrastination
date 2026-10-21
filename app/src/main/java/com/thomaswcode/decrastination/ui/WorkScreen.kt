package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Duplicates
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.longFinished
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.protect.ProtectionActivity
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Work (UI-17, UI-10): what makes the plan less than the whole picture first (a source that
 * can't be read, protection off, Claude's key), then every open task by when it's due (Overdue,
 * Today, Tomorrow, This week, Later, No due date) or by subject, from every source or one; each a
 * tile, a tap opening it to change ([TaskSheet]); *New task* makes one of your own. Below, folded:
 * the work the plan leaves out and why, the tasks you said aren't, the emails of events (DX-02),
 * those finished lately, and how each source's last read went.
 */
@Composable
fun WorkScreen(graph: AppGraph, activity: Activity, opening: String? = null, onOpened: () -> Unit = {}) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val protection by graph.stores.protection.state.collectAsStateWithLifecycle()
    val instructions by graph.instructions.state.collectAsStateWithLifecycle()
    val calendar by graph.sync.calendarState.collectAsStateWithLifecycle()
    // The sections move with the clock as well as the data: deadlines pass, and midnight.
    val now by produceState(graph.clock.now()) {
        while (true) {
            delay(60_000)
            value = graph.clock.now()
        }
    }
    val zone = graph.clock.zone()
    // The task open in its sheet, by id, and the form for a new one: kept through a turn of the phone.
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var making by rememberSaveable { mutableStateOf(false) }
    // A task asked for from elsewhere (its Open on the Plan or the block screen).
    LaunchedEffect(opening) {
        if (opening != null) {
            sheetFor = opening
            onOpened()
        }
    }
    val byId = state.tasks.associateBy { it.id }
    // What the plan leaves out, as the plan has it now.
    val plan = remember(state, settings, now, instructions, calendar) { graph.planning.plan(state, settings, now) }
    val trouble = trouble(state, runtime, protection, now)
    val scope = rememberCoroutineScope()
    var said by rememberSaveable { mutableStateOf<String?>(null) }
    // A photo of homework read into tasks (NF-01): the app's, so a turn of the phone or leaving Work loses nothing.
    val photoReading by graph.photos.photoReading.collectAsStateWithLifecycle()
    val reading = photoReading?.busy == true
    LaunchedEffect(photoReading) { photoReading?.let { p -> said = if (p.busy) "Reading the photo…" else p.why } }
    val homework = remember { File(activity.cacheDir, "photos/homework.jpg").also { it.parentFile?.mkdirs() } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) graph.photos.readPhotoOf(homework) else homework.delete()
    }
    // The screen's camera, for a photo of the work from a task's sheet (UI-09); what it said, about which task.
    var saidAbout by rememberSaveable { mutableStateOf<String?>(null) }
    val photo = rememberPhotoCheck(graph, activity, plan) { taskId, text ->
        said = text
        saidAbout = taskId
    }
    // By due date or by subject, from every source or one (UI-10): kept through a turn of the phone.
    var bySubject by rememberSaveable { mutableStateOf(false) }
    var only by rememberSaveable { mutableStateOf<String?>(null) }
    // The due-date sections open; what's not for now folded, till you say otherwise.
    var folded by rememberSaveable { mutableStateOf(listOf(FINISHED, NOT_TASKS, SOURCES, NOT_PLANNED)) }
    val toggle = { key: String -> folded = if (key in folded) folded - key else folded + key }
    Box(Modifier.fillMaxSize()) {
        // Room at the foot for both buttons (the chat's over New task), so the last row scrolls clear.
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 200.dp)) {
            item(key = "hint") { Hint(graph, "work", "Work is what: every task, by when it's due or by subject. Day is when: today's slots, on the first tab.") }
            // Claude's key stopped working while it's on, and what else makes the plan less than all there is (IMP-P1-007).
            model.aiUsage.keyProblem?.takeIf { settings.aiEnabled && settings.aiKeyActive }?.let { problem ->
                item(key = "key") { KeyProblemCard(problem) { activity.startActivity(Intent(activity, SetupActivity::class.java)) } }
            }
            if (trouble.isNotEmpty()) {
                item(key = "trouble") {
                    TroubleCard(
                        trouble,
                        onSetup = { activity.startActivity(Intent(activity, SetupActivity::class.java)) },
                        onProtection = { activity.startActivity(Intent(activity, ProtectionActivity::class.java)) },
                        onTeams = { scope.launch { said = graph.sync.requestTeamsSync() ?: "The Teams widget is syncing Teams" } },
                    )
                }
            }
            said?.let { text -> item(key = "said") { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) } }
            // By due date or by subject; from every source, or one.
            item(key = "ways") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !bySubject, onClick = { bySubject = false }, label = { Text("By due date") })
                        FilterChip(selected = bySubject, onClick = { bySubject = true }, label = { Text("By subject") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = only == null, onClick = { only = null }, label = { Text("All") })
                        (Source.SYNCED + Source.Own).forEach { source ->
                            FilterChip(selected = only == source.name, onClick = { only = if (only == source.name) null else source.name }, label = { Text(source.label) })
                        }
                    }
                }
            }
            // Emails that are just emails aren't tasks; those you said aren't are below.
            val open = state.tasks.filter { it.isOpen && !it.hidden && (only == null || it.source.name == only) }
            if (open.isEmpty()) {
                item(key = "none") {
                    Text(
                        "Nothing to do. New tasks appear as the sources are read, or add your own.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp),
                    )
                }
            }
            val groups = if (bySubject) Groups.bySubject(open, settings.classMap, now) else Groups.byDue(open, now, zone).map { (g, t) -> g.title to t }
            // Work that looks the same from two sources (DX-15): said on each.
            val pairs = Duplicates.candidates(state.tasks, settings.classMap, runtime.notSame)
            val alike = pairs.flatMap { (a, b) -> listOf(a.id to b, b.id to a) }.groupBy({ it.first }, { it.second })
            for ((title, tasks) in groups) {
                val key = "group-$title"
                val expanded = key !in folded
                item(key = key) { FoldingHeading(title, expanded, { toggle(key) }, summary = "${tasks.size}") }
                if (expanded) itemsIndexed(tasks, key = { _, it -> it.id }) { index, task ->
                    Column {
                        TaskTile(task, index, tasks.size, now, zone, byId) { sheetFor = task.id }
                        alike[task.id]?.firstOrNull()?.let { other -> SameLine(graph, task, other) }
                    }
                }
            }
            // Open work the plan leaves out, each with why (IMP-P1-002): folded, as it isn't for now.
            // Each fold of one source's too, where one is chosen.
            val unplanned = plan.unplanned.filter { gap -> only == null || byId[gap.taskId]?.source?.name == only }
            if (unplanned.isNotEmpty()) {
                val expanded = NOT_PLANNED !in folded
                item(key = NOT_PLANNED) { FoldingHeading("Not planned", expanded, { toggle(NOT_PLANNED) }, summary = "${unplanned.size}", detail = "Open work left out of the plan, and why") }
                if (expanded) {
                    val lastDay = plan.buckets.lastOrNull()?.date ?: plan.today
                    itemsIndexed(unplanned, key = { _, it -> "unplanned-${it.taskId}-${it.reason}" }) { index, gap ->
                        UnplannedTile(gap, index, unplanned.size, lastDay, plan.now, zone) { sheetFor = gap.taskId }
                    }
                }
            }
            // Tasks you've said aren't, or are the same work as another (DX-15): kept, off the plan, and
            // here to check (taken back in Ask Claude).
            val notTasks = state.tasks.filter { it.isOpen && (it.mine.notATask != null || it.mine.sameAs?.value != null) && (only == null || it.source.name == only) }.sortedWith(compareBy(nullsLast()) { it.dueAt })
            if (notTasks.isNotEmpty()) {
                val expanded = NOT_TASKS !in folded
                item(key = NOT_TASKS) { FoldingHeading("Hidden, as you said", expanded, { toggle(NOT_TASKS) }, summary = "${notTasks.size}") }
                if (expanded) itemsIndexed(notTasks, key = { _, it -> "not-" + it.id }) { index, task -> TaskTile(task, index, notTasks.size, now, zone, byId) { sheetFor = task.id } }
            }
            // Lately: a fortnight's, as before your own were kept for good; older ones are records alone (C-Q3).
            val finished = state.tasks.filter { !it.isOpen && !it.hidden && !it.longFinished(now) && (only == null || it.source.name == only) }.sortedByDescending { it.doneAt ?: it.lastSeenAt }
            if (finished.isNotEmpty()) {
                val expanded = FINISHED !in folded
                item(key = FINISHED) { FoldingHeading("Finished or missed lately", expanded, { toggle(FINISHED) }, summary = "${finished.size}") }
                if (expanded) itemsIndexed(finished, key = { _, it -> "done-" + it.id }) { index, task -> TaskTile(task, index, finished.size, now, zone, byId) { sheetFor = task.id } }
            }
            val failing = Source.SYNCED.count { state.status(it).error != null }
            val expanded = SOURCES !in folded
            item(key = SOURCES) {
                FoldingHeading(
                    "Sources",
                    expanded,
                    { toggle(SOURCES) },
                    detail = if (failing > 0) "$failing can't be read" else "How each was last read",
                    detailColor = if (failing > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            if (expanded) {
                itemsIndexed(Source.SYNCED, key = { _, it -> "source-$it" }) { index, source ->
                    SourceTile(source, state.status(source), state.tasks.count { it.source == source && it.isOpen && !it.hidden }, now, index, Source.SYNCED.size)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Or say it to Felix, who makes the card (Q29); here above New task, as Felix is on every screen (UI-16).
            FelixFab(activity)
            // Or photograph it (NF-01), once Claude is on to read it.
            if (settings.aiEnabled && settings.aiKeyActive) {
                SmallFloatingActionButton(onClick = { if (!reading) camera.launch(FileProvider.getUriForFile(activity, "${activity.packageName}.photos", homework)) }) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = "Photograph homework")
                }
            }
            ExtendedFloatingActionButton(
                onClick = { making = true },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("New task") },
            )
        }
    }
    sheetFor?.let { id -> TaskSheetHost(graph, activity, id, plan, photo, said.takeIf { saidAbout == id }) { sheetFor = null } }
    if (making) OwnTaskForm(graph, editing = null) { making = false }
    photoReading?.takeIf { !it.busy }?.let { p -> p.reading?.let { got -> p.jpeg?.let { jpeg -> PhotoTasksSheet(graph, got, jpeg) { graph.photos.photoReading.value = null } } } }
}

private const val FINISHED = "finished"
private const val NOT_PLANNED = "not-planned"
private const val NOT_TASKS = "not-tasks"
private const val SOURCES = "sources"

/** How reading [source] last went: when, its data's age, and why it failed, in red. */
@Composable
private fun SourceTile(source: Source, status: SourceStatus, open: Int, now: Long, index: Int, count: Int) {
    val read = status.lastSuccessAt?.let { "Read ${Format.ago(it, now)}" } ?: "Not read yet"
    val asOf = status.dataAsOf?.let { " · its data from ${Format.ago(it, now)}" }.orEmpty()
    ListTile(index, count) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Pill("$open open")
        }
        status.error?.let { Text("Last read failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(read + asOf, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A task as a tile: its name and minutes, when it's due, its source and kind, and what's out of the ordinary as tags. */
@Composable
private fun TaskTile(task: TaskItem, index: Int, count: Int, now: Long, zone: ZoneId, byId: Map<String, TaskItem>, onClick: () -> Unit) {
    // The deadline in force (a plan's gone by gives way to the real one behind it): only a real one gone by is late (PL-14).
    val due = task.deadlineAt(now) ?: task.dueAt
    val hard = task.hardAt(now)
    val overdue = task.isOpen && hard && due?.let { it < now } == true
    val hiddenUntil = task.availableFrom?.takeIf { it > now }
    // Claude's plan for it not used (out of range, or not adding up): said, so it can be checked.
    val dropped = Enrichments.current(task)?.dropped?.takeIf { task.isOpen }
    val waits = task.isOpen && task.mine.after?.value != null && InstructionRules.waiting(task, byId)
    // A start you gave (or one of your own's): planned from then on, not hidden.
    val startsAt = (if (task.source == Source.Own) task.notBefore else task.mine.from?.value)?.takeIf { task.isOpen && it > now }
    val steps = task.subSteps.takeIf { it.isNotEmpty() && task.isOpen }
    // The same work as another, which is planned (DX-15).
    val sameAs = task.mine.sameAs?.value?.let { byId[it] }
    ListTile(index, count, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Pill(Format.minutes(task.effortMin))
        }
        // When it's due, first and coloured when late.
        Text(
            if (task.isOpen) Format.due(due, hard, now, zone) else task.status.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            listOfNotNull(task.source.label, task.kind.label, task.className, task.extra[GmailThreads.EXTRA_FROM]).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val tags = dropped != null || hiddenUntil != null || waits || task.mine.dueAt?.value != null || task.mine.minutes?.minutes != null || startsAt != null || steps != null || task.mine.notATask != null || sameAs != null
        if (task.isOpen && tags) {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.mine.notATask != null) Pill("Not a task")
                sameAs?.let { Pill("Same as “${it.title}”") }
                if (dropped != null) WarningPill("Claude's plan dropped")
                if (hiddenUntil != null) Pill("Hidden till ${Format.at(hiddenUntil, now, zone)}")
                if (waits) Pill("Waits for another task")
                if (task.mine.dueAt?.value != null) Pill("Due date yours")
                if (task.mine.minutes?.minutes != null) Pill(if (task.pacedMinutes) "Minutes at your pace" else "Minutes yours")
                if (startsAt != null) Pill("Starts ${Format.at(startsAt, now, zone)}")
                steps?.let { s -> Pill("${s.count { it.done }}/${s.size} steps") }
            }
        }
    }
}

/**
 * [task] looks like the same work as [other] (DX-15): Same keeps [other] and takes [task] off the
 * plan and the list (a change of yours, taken back in Your changes); Different isn't asked again.
 */
@Composable
private fun SameLine(graph: AppGraph, task: TaskItem, other: TaskItem) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Looks like the same work as “${other.title}” from ${other.source.label}. Same?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            graph.scope.launch {
                graph.instructions.addOwnChange(
                    UUID.randomUUID().toString(), "Same as “${other.title}”",
                    About(taskId = task.id, taskTitle = task.title),
                    Change(ChangeType.SameAs, taskId = task.id, afterTaskId = other.id),
                )
            }
        }) { Text("Same") }
        TextButton(onClick = {
            graph.scope.launch { graph.notSame(task.id, other.id) }
        }) { Text("Different") }
    }
}

/** The most pairs said to be different kept: far more than there are. */
