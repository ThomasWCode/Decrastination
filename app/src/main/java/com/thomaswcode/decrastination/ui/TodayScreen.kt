package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.widget.WidgetModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The plan: the next thing to do, then each day's chunks (docs/scheduler.md §3). */
@Composable
fun TodayScreen(graph: AppGraph, activity: Activity) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
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

    // Today and tomorrow open; later days folded to their heading, which still says their load.
    var unfolded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var folded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // A long day shows its first pieces, the rest a tap away.
    var whole by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Claude's key stopped working while it's on: said first, till it's put right in Setup.
        runtime.aiUsage.keyProblem?.takeIf { settings.aiEnabled && settings.aiKeyActive }?.let { problem ->
            item(key = "key") {
                KeyProblemCard(problem) { activity.startActivity(Intent(activity, SetupActivity::class.java)) }
            }
        }
        item(key = "next") { NextCard(plan, zone, open) }
        plan.buckets.filter { it.chunks.isNotEmpty() || it.date == plan.today }.forEach { bucket ->
            val day = bucket.date.toString()
            val soon = bucket.date <= plan.today.plusDays(1)
            val expanded = if (soon) day !in folded else day in unfolded
            item(key = "day-$day") {
                DayHeader(bucket, plan.today, expanded) {
                    if (soon) folded = folded.toggle(day) else unfolded = unfolded.toggle(day)
                }
            }
            if (!expanded) return@forEach
            if (bucket.chunks.isEmpty()) {
                item(key = "empty-$day") { ListTile(0, 1) { Text("Nothing planned.", style = MaterialTheme.typography.bodyMedium) } }
            }
            val more = bucket.chunks.size > PER_DAY
            val shown = if (more && day !in whole) bucket.chunks.take(PER_DAY) else bucket.chunks
            val tiles = shown.size + if (more) 1 else 0
            itemsIndexed(shown, key = { _, it -> "$day-${it.taskId}-${it.part}" }) { index, chunk ->
                ChunkTile(chunk, index, tiles, plan.now, zone) { open(chunk) }
            }
            if (more) {
                item(key = "more-$day") {
                    ListTile(tiles - 1, tiles, onClick = { whole = whole.toggle(day) }) {
                        Text(
                            if (day in whole) "Show the first $PER_DAY only" else "Show ${bucket.chunks.size - PER_DAY} more",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (plan.events.isNotEmpty()) {
            item(key = "events") { SectionHeading("Coming up") }
            itemsIndexed(plan.events, key = { _, it -> "event-" + it.id }) { index, event ->
                ListTile(index, plan.events.size) {
                    Text(event.title, style = MaterialTheme.typography.bodyLarge)
                    event.dueAt?.let { Text(Format.at(it, plan.now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

private const val PER_DAY = 8

private fun List<String>.toggle(day: String) = if (day in this) this - day else this + day

@Composable
private fun KeyProblemCard(problem: KeyProblem, setup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Claude has stopped working", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("${problem.says}. ${problem.fix}; the rules stand in meanwhile.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = setup) { Text("Open Setup") }
        }
    }
}

@Composable
private fun NextCard(plan: Plan, zone: ZoneId, open: (Chunk) -> Unit) {
    val next = plan.next
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (next == null) {
                Text("Nothing to do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("No work is planned. New tasks appear as the sources are read.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("DO NOW", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(next.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val badge = WidgetModel.badge(next, plan.now, zone)
                if (next.urgent) WarningPill(badge) else Pill(badge)
                Pill(Format.minutes(next.minutes))
                Text(next.source.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            plan.then?.let { Text("Then: ${it.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Button(onClick = { open(next) }) { Text("Open") }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

@Composable
private fun DayHeader(bucket: DayBucket, today: LocalDate, expanded: Boolean, onToggle: () -> Unit) {
    val name = when (bucket.date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> bucket.date.format(DAY)
    }
    FoldingHeading(
        title = name,
        expanded = expanded,
        onToggle = onToggle,
        summary = "${bucket.chunks.size} piece${if (bucket.chunks.size == 1) "" else "s"}",
        detail = Format.load(bucket.plannedMin, bucket.capacityMin),
        detailColor = if (bucket.plannedMin > bucket.capacityMin) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

@Composable
private fun ChunkTile(chunk: Chunk, index: Int, count: Int, now: Long, zone: ZoneId, onClick: () -> Unit) {
    ListTile(index, count, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Urgent work marked down its edge, to be picked out at a glance.
            Box(
                Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (chunk.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
            )
            Column(Modifier.weight(1f)) {
                Text(chunk.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${WidgetModel.badge(chunk, now, zone)} · ${chunk.source.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chunk.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(Format.minutes(chunk.minutes))
        }
    }
}
