package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.ui.Moves.CHIP_DAY
import java.time.LocalDate
import java.time.ZoneId

/** How many days ahead a piece can be moved to. */
private const val DAYS = 14L

/**
 * A piece moved by hand (UI-04), from its piece's or its task's sheet (a long press drags it on the Day's grid, D-118, and
 * TalkBack's *Move it* opens this): a day of the next fortnight, each with its load (one after a real deadline still to
 * come can't be picked: due before then; overdue work can go to any, D-30), then this piece at a time, or all of its task
 * that day; as a change of yours, taken back in Your changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(graph: AppGraph, chunk: Chunk, plan: Plan, zone: ZoneId, onDone: (String) -> Unit, onClose: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var timing by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    // A time pins a task's next piece (PL-04): only that one can be given one; the rest go by their day.
    val next = plan.chunksOf(chunk.taskId).firstOrNull()?.key == chunk.key
    // Its task's real deadline's day, still to come, is the last its work can go to, and the day it can start the first (as the plan keeps a day).
    val task = graph.tasks.value.tasks.firstOrNull { it.id == chunk.taskId }
    val last = task?.realDeadline?.takeIf { it > graph.clock.now() }?.let { Planner.date(it, zone) }
    val first = task?.notBefore?.let { Planner.date(it, zone) }
    // Made, and said (a day kept for only other tasks it joins too, D-115); or refused, and why.
    fun moved(moved: Moves.Moved) = when (moved) {
        is Moves.Moved.Done -> onDone(moved.said)
        is Moves.Moved.Refused -> problem = moved.why
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Move: ${chunk.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (0L until DAYS).map { plan.today.plusDays(it) }.forEach { day ->
                    val bucket = plan.bucket(day)
                    FilterChip(
                        selected = chosen == day.toString(),
                        enabled = Moves.dayAllowed(task, day, graph.clock.now(), zone),
                        onClick = { chosen = day.toString() },
                        label = { Text("${if (day == plan.today) "Today" else day.format(CHIP_DAY)} · ${Format.minutes(bucket?.plannedMin ?: 0)}") },
                    )
                }
            }
            if (last != null) Text("No day after ${last.format(CHIP_DAY)}: due before then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (first != null && first > plan.today) Text("No day before ${first.format(CHIP_DAY)}: it can't be started till then.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            chosen?.let(LocalDate::parse)?.let { day ->
                if (next) {
                    Button(onClick = { timing = true }) { Text("This piece, at a time…") }
                } else {
                    Text("Only its next piece takes a time: all of it can go to a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { moved(Moves.toDay(graph, chunk, day, zone)) }) { Text("All of it, any time that day") }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (timing) {
        val day = chosen?.let(LocalDate::parse) ?: plan.today
        TimeDialog("${chunk.label}, on ${day.format(CHIP_DAY)}", 17 * 60, onClose = { timing = false }) { minute ->
            timing = false
            problem = null
            moved(Moves.toTime(graph, chunk, day, minute, zone))
        }
    }
}
