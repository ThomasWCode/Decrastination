package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.widget.WidgetModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The plan: the next thing to do, then each day's chunks (docs/scheduler.md §3). */
@Composable
fun TodayScreen(graph: AppGraph, activity: Activity) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    // The plan moves with the clock as well as the data: deadlines pass, the evening runs out.
    val now by produceState(graph.clock.now()) {
        while (true) {
            delay(60_000)
            value = graph.clock.now()
        }
    }
    val plan = remember(tasks, settings, now) { graph.plan(tasks, settings, now) }
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    val open = { chunk: Chunk ->
        tasks.tasks.firstOrNull { it.id == chunk.taskId }?.let { task ->
            scope.launch { TaskOpener.open(activity, task)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() } }
        }
        Unit
    }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item(key = "next") { NextCard(plan, zone, open) }
        plan.buckets.filter { it.chunks.isNotEmpty() || it.date == plan.today }.forEach { bucket ->
            item(key = "day-${bucket.date}") { DayHeader(bucket, plan.today) }
            if (bucket.chunks.isEmpty()) {
                item(key = "empty-${bucket.date}") {
                    Text("Nothing planned.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            items(bucket.chunks, key = { "${bucket.date}-${it.taskId}-${it.part}" }) { ChunkRow(it, plan.now, zone) { open(it) } }
        }
        if (plan.events.isNotEmpty()) {
            item(key = "events") { SectionTitle("Coming up") }
            items(plan.events, key = { "event-" + it.id }) { event ->
                Text(
                    listOfNotNull(event.title, event.dueAt?.let { Format.at(it, plan.now, zone) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NextCard(plan: Plan, zone: ZoneId, open: (Chunk) -> Unit) {
    val next = plan.next
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (next == null) {
                Text("Nothing to do", style = MaterialTheme.typography.headlineSmall)
                Text("No work is planned. New tasks appear as the sources are read.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("Do", style = MaterialTheme.typography.labelLarge)
            Text(next.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(WidgetModel.badge(next, plan.now, zone), Format.minutes(next.minutes), next.source.label).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (next.overdue || next.behind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            plan.then?.let { Text("Then: ${it.label}", style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(next) }) { Text("Open") }
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@Composable
private fun DayHeader(bucket: DayBucket, today: LocalDate) {
    val name = when (bucket.date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> bucket.date.format(DAY)
    }
    val load = Format.load(bucket.plannedMin, bucket.capacityMin)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(
            load,
            style = MaterialTheme.typography.bodySmall,
            color = if (bucket.plannedMin > bucket.capacityMin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ChunkRow(chunk: Chunk, now: Long, zone: ZoneId, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(chunk.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${chunk.source.label} · ${WidgetModel.badge(chunk, now, zone)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (chunk.overdue || chunk.behind) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Format.minutes(chunk.minutes), style = MaterialTheme.typography.labelLarge)
    }
}
