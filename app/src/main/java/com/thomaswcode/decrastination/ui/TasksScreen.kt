package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlinx.coroutines.launch

/**
 * Every task each source lists, with its raw detail (PLAN.md's Phase 1 debug view): a folding
 * group per source, each task a tile whose detail opens on a tap.
 */
@Composable
fun TasksScreen(graph: AppGraph, activity: Activity) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val now = graph.clock.now()
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    val tick = { task: TaskItem, index: Int, title: String -> scope.launch { graph.focus.tickBlock(task.id, index, title) }; Unit }
    // The task an instruction is being written about, by id: kept, with the words, through the
    // screen being made again (BUG-P2-013).
    var writingAbout by rememberSaveable { mutableStateOf<String?>(null) }
    val titles = state.tasks.associate { it.id to it.title }
    val actions = TileActions(tick, { writingAbout = it.id }, titles, state.tasks.associateBy { it.id })
    // Sources open, the finished folded, till you say otherwise.
    var folded by rememberSaveable { mutableStateOf(listOf(FINISHED, NOT_TASKS)) }
    val toggle = { key: String -> folded = if (key in folded) folded - key else folded + key }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        for (source in Source.entries) {
            // Emails that are just emails aren't tasks: not listed (your call, 9 Oct).
            // You've said some aren't tasks: they're under their own heading below.
            val open = state.tasks.filter { it.source == source && it.isOpen && !it.hidden }.sortedWith(compareBy(nullsLast()) { it.dueAt })
            val expanded = source.name !in folded
            item(key = "header-$source") { SourceHeader(source, state.status(source), open.size, now, expanded) { toggle(source.name) } }
            if (expanded) itemsIndexed(open, key = { _, it -> it.id }) { index, task -> TaskTile(task, index, open.size, now, zone, actions) }
        }
        // Tasks you've said aren't: kept, off the plan, and here to check (taken back in Instructions).
        val notTasks = state.tasks.filter { it.isOpen && it.userNotATask }.sortedWith(compareBy(nullsLast()) { it.dueAt })
        if (notTasks.isNotEmpty()) {
            val expanded = NOT_TASKS !in folded
            item(key = NOT_TASKS) { FoldingHeading("Not tasks, as you said", expanded, { toggle(NOT_TASKS) }, summary = "${notTasks.size}") }
            if (expanded) itemsIndexed(notTasks, key = { _, it -> "not-" + it.id }) { index, task -> TaskTile(task, index, notTasks.size, now, zone, actions) }
        }
        val finished = state.tasks.filter { !it.isOpen && !it.hidden }.sortedByDescending { it.doneAt ?: it.lastSeenAt }
        if (finished.isNotEmpty()) {
            val expanded = FINISHED !in folded
            item(key = "finished") { FoldingHeading("Finished or missed lately", expanded, { toggle(FINISHED) }, summary = "${finished.size}") }
            if (expanded) itemsIndexed(finished, key = { _, it -> "done-" + it.id }) { index, task -> TaskTile(task, index, finished.size, now, zone, actions) }
        }
    }
    writingAbout?.let { id -> state.tasks.firstOrNull { it.id == id } }?.let { task ->
        InstructionDialog("About “${task.title}”", onDismiss = { writingAbout = null }) { text ->
            writingAbout = null
            sendInstruction(activity, graph, text, About(taskId = task.id, taskTitle = task.title))
        }
    }
}

/** What a task's tile can do, and what it needs to name other tasks. */
private class TileActions(
    val tick: (TaskItem, Int, String) -> Unit,
    val instruct: (TaskItem) -> Unit,
    val titles: Map<String, String>,
    val byId: Map<String, TaskItem>,
)

private const val FINISHED = "finished"
private const val NOT_TASKS = "not-tasks"

@Composable
private fun SourceHeader(source: Source, status: SourceStatus, count: Int, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    val read = status.lastSuccessAt?.let { "Read ${Format.ago(it, now)}" } ?: "Not read yet"
    val asOf = status.dataAsOf?.let { " · its data from ${Format.ago(it, now)}" }.orEmpty()
    // A failed read first, in red: the rest is how it was before.
    val detail = listOfNotNull(status.error?.let { "Last read failed: $it" }, read + asOf, status.note).joinToString("\n")
    FoldingHeading(
        title = source.label,
        expanded = expanded,
        onToggle = onToggle,
        summary = "$count open",
        detail = detail,
        detailColor = if (status.error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

@Composable
private fun TaskTile(task: TaskItem, index: Int, count: Int, now: Long, zone: java.time.ZoneId, actions: TileActions) {
    val tick = actions.tick
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val overdue = task.isOpen && task.dueAt?.let { it < now } == true
    val hiddenUntil = task.availableFrom?.takeIf { it > now }
    val followUp = task.isOpen && task.extra[Merge.EXTRA_FOLLOW_UP] == "true"
    // Claude's plan for it not used (out of range, or not adding up): said, so it can be checked.
    val dropped = Enrichments.current(task)?.dropped?.takeIf { task.isOpen }
    // Your instructions' say: waiting for another task, or a due date of yours.
    val waitsFor = task.userAfter?.takeIf { task.isOpen && Instructions.waiting(task, actions.byId) }?.let { actions.titles[it] ?: it }
    val yourDue = task.isOpen && task.userDueAt != null
    // A start you gave: planned from then on, not hidden.
    val startsAt = task.userFrom?.takeIf { task.isOpen && it > now }
    ListTile(index, count, onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Pill(Format.minutes(task.effortMin))
        }
        // When it's due, first and coloured when late; the rest of its line muted, cut to one line till opened.
        Text(
            if (task.isOpen) Format.due(task.dueAt, now, zone) else task.status.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
        val line = listOfNotNull(task.kind.label, task.className, task.extra[GmailThreads.EXTRA_FROM]).joinToString(" · ")
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 4 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Folded, what's out of the ordinary as a tag; opened, in full.
        if (!expanded && (dropped != null || hiddenUntil != null || followUp || waitsFor != null || yourDue || startsAt != null)) {
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dropped != null) WarningPill("Claude's plan dropped")
                if (hiddenUntil != null) Pill("Hidden till ${Format.at(hiddenUntil, now, zone)}")
                if (followUp) Pill("Archived")
                if (waitsFor != null) Pill("Waits for another task")
                if (yourDue) Pill("Due date yours")
                if (startsAt != null) Pill("Starts ${Format.at(startsAt, now, zone)}")
            }
        }
        if (expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                hiddenUntil?.let { Detail("Hidden from the plan until ${Format.at(it, now, zone)}") }
                waitsFor?.let { Detail("Waits, as you said, until “$it” is done") }
                if (yourDue) Detail("Its due date is yours, from an instruction: ${Format.due(task.dueAt, now, zone)}")
                startsAt?.let { Detail("Can't be started before ${Format.at(it, now, zone)}, as you said: planned from then") }
                if (task.userNotATask) Detail("You said it isn't a task: take that back in Instructions")
                if (followUp) Detail("Archived in Gmail: kept for its blocks till they're done")
                dropped?.let { Detail("Claude's plan was dropped ($it): planned as one piece", MaterialTheme.colorScheme.error) }
                // The enrichment's step only while it's of the email as it is: a new message's own rules' step otherwise.
                (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { Detail("Next: $it") }
                task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { Detail("Steps and estimate by Claude", MaterialTheme.colorScheme.onSurfaceVariant) }
                task.subSteps.forEachIndexed { index, step ->
                    // A block's own dates, where it has them; an email's can be ticked off here.
                    val dates = listOfNotNull(step.from?.let { "from " + Format.at(it, now, zone) }, step.dueAt?.let { Format.due(it, now, zone) }).joinToString(", ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "• ${step.title} (${Format.minutes(step.minutes)}${if (dates.isEmpty()) "" else "; $dates"})${if (step.done) " ✓" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (task.source == Source.Gmail && task.isOpen && !step.done) TextButton(onClick = { tick(task, index, step.title) }) { Text("Done") }
                    }
                }
                if (task.detail.isNotBlank()) Detail(task.detail)
                if (task.isOpen) TextButton(onClick = { actions.instruct(task) }) { Text("Add an instruction") }
            }
        }
    }
}

@Composable
private fun Detail(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
