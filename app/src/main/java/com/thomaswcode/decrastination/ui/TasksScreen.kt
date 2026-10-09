package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Merge
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.SourceStatus
import com.thomaswcode.decrastination.enrich.RuleEnricher
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import kotlinx.coroutines.launch

/** Every task each source lists, with its raw detail: the debug view PLAN.md's Phase 1 asks for. */
@Composable
fun TasksScreen(graph: AppGraph) {
    val state by graph.tasks.state.collectAsStateWithLifecycle()
    val now = graph.clock.now()
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    val tick = { task: TaskItem, index: Int, title: String -> scope.launch { graph.focus.tickBlock(task.id, index, title) }; Unit }
    LazyColumn(Modifier.fillMaxWidth()) {
        for (source in Source.entries) {
            val open = state.tasks.filter { it.source == source && it.isOpen }.sortedWith(compareBy(nullsLast()) { it.dueAt })
            item(key = "header-$source") { SourceHeader(source, state.status(source), open.size, now) }
            items(open, key = { it.id }) { TaskRow(it, now, zone, tick) }
        }
        val finished = state.tasks.filter { !it.isOpen }.sortedByDescending { it.doneAt ?: it.lastSeenAt }
        if (finished.isNotEmpty()) {
            item(key = "finished") {
                Text(
                    "Finished or missed lately",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                )
            }
            items(finished, key = { "done-" + it.id }) { TaskRow(it, now, zone, tick) }
        }
    }
}

@Composable
private fun SourceHeader(source: Source, status: SourceStatus, count: Int, now: Long) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("${source.label} · $count open", style = MaterialTheme.typography.titleSmall)
            val read = status.lastSuccessAt?.let { "Read ${Format.ago(it, now)}" } ?: "Not read yet"
            val asOf = status.dataAsOf?.let { " · its data from ${Format.ago(it, now)}" }.orEmpty()
            Text(read + asOf, style = MaterialTheme.typography.bodySmall)
            status.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            status.error?.let {
                Text("Last read failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, now: Long, zone: java.time.ZoneId, tick: (TaskItem, Int, String) -> Unit) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            Text(Format.minutes(task.effortMin), style = MaterialTheme.typography.labelMedium)
        }
        val line = listOfNotNull(
            task.kind.label,
            task.className,
            task.extra[GmailThreads.EXTRA_FROM],
            if (task.isOpen) Format.due(task.dueAt, now, zone) else task.status.name,
        ).joinToString(" · ")
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        task.availableFrom?.takeIf { it > now }?.let {
            Text("Hidden from the plan until ${Format.at(it, now, zone)}", style = MaterialTheme.typography.bodySmall)
        }
        if (task.isOpen && task.extra[Merge.EXTRA_FOLLOW_UP] == "true") {
            Text("Archived in Gmail: kept for its blocks till they're done", style = MaterialTheme.typography.bodySmall)
        }
        // Claude's plan for it not used (out of range, or not adding up): said, so it can be checked.
        Enrichments.current(task)?.dropped?.takeIf { task.isOpen }?.let { why ->
            Text("Claude's plan was dropped ($why): planned as one piece", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (expanded) {
            // The enrichment's step only while it's of the email as it is: a new message's own rules' step otherwise.
            (Enrichments.current(task)?.nextStep ?: task.extra[GmailThreads.EXTRA_NEXT_STEP])?.let { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
            task.enrichment?.takeIf { it.by != RuleEnricher.BY }?.let { Text("Steps and estimate by Claude", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            if (task.detail.isNotBlank()) Text(task.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}
