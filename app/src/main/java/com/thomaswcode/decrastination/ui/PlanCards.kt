package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.core.WontFit
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.protect.ProtectionActivity
import com.thomaswcode.decrastination.widget.WidgetModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The cards the Day and Work screens share (UI-17): the DO NOW card and a piece's sheet, the day's
 * deadlines that won't fit, what the plan leaves out, and the trouble that makes the plan less than
 * the whole picture.
 */

internal const val MESSAGE_MS = 20_000L

/** Something that makes the plan less than the whole picture, and where it's put right. */
internal data class Trouble(val text: String, val fix: Fix) {
    enum class Fix { Protection, Setup, Teams }
}

/** As the widget's warning has them, all of them, protection first. */
internal fun trouble(tasks: TaskState, runtime: RuntimeState, now: Long): List<Trouble> = buildList {
    runtime.protection.problems.forEach { add(Trouble(it, Trouble.Fix.Protection)) }
    if (runtime.unreadable.isNotEmpty()) add(Trouble("A saved file couldn't be read: Setup says what was kept", Trouble.Fix.Setup))
    Source.SYNCED.forEach { source -> tasks.status(source).error?.let { add(Trouble("Can't read ${source.label}: $it", Trouble.Fix.Setup)) } }
    WidgetModel.teamsWarning(tasks, now)?.let { add(Trouble(it, Trouble.Fix.Teams)) }
}

@Composable
internal fun TroubleCard(trouble: List<Trouble>, onSetup: () -> Unit, onProtection: () -> Unit, onTeams: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Needs a look", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            trouble.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
            val fixes = trouble.map { it.fix }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Trouble.Fix.Protection in fixes) Button(onClick = onProtection) { Text("Protection") }
                if (Trouble.Fix.Setup in fixes) Button(onClick = onSetup) { Text("Open Setup") }
                if (Trouble.Fix.Teams in fixes) OutlinedButton(onClick = onTeams) { Text("Sync Teams") }
            }
            if (Trouble.Fix.Teams in fixes) {
                Text("Syncing Teams takes over the screen for about a minute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
internal fun KeyProblemCard(problem: KeyProblem, setup: () -> Unit) {
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
internal fun NextCard(
    graph: AppGraph,
    activity: Activity,
    plan: Plan,
    zone: ZoneId,
    photo: PhotoCheck,
    messageOf: (taskId: String, general: Boolean) -> String?,
    say: (taskId: String?, text: String) -> Unit,
    empty: String,
) {
    val next = plan.next
    val message = messageOf(next?.taskId.orEmpty(), true)
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (next == null) {
                Text("Nothing to do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                graph.focus.rewardLine(plan)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                Text(empty, style = MaterialTheme.typography.bodyMedium)
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                return@Column
            }
            Text("DO NOW", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(next.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            // Its first step (AI-15), as it's written ahead.
            graph.runtime.value.microPlans[next.key]?.first?.takeIf { it.isNotEmpty() }?.let { Text("First: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Badges(next, plan.now, zone)
            graph.focus.rewardLine(plan)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            plan.then?.let { Text("Then: ${it.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            PieceActions(graph, activity, next, plan.now, photo) { say(next.taskId, it) }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

/** Its due date (red when urgent), its minutes and its source, wrapping where the screen is narrow. */
@Composable
internal fun Badges(chunk: Chunk, now: Long, zone: ZoneId) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val badge = WidgetModel.badge(chunk, now, zone)
        if (chunk.urgent) WarningPill(badge) else Pill(badge)
        Pill(Format.minutes(chunk.minutes))
        Text(chunk.source.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A piece's actions, from a tap on it in the plan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PieceSheet(
    graph: AppGraph,
    activity: Activity,
    chunk: Chunk,
    now: Long,
    zone: ZoneId,
    photo: PhotoCheck,
    message: String?,
    say: (String) -> Unit,
    onChange: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(chunk.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${WidgetModel.badge(chunk, now, zone)} · ${Format.minutes(chunk.minutes)} · ${chunk.source.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (chunk.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Why it's on this day (PL-07).
            Text(chunk.whyLine(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone), style = MaterialTheme.typography.bodyMedium)
            // Its first steps in full (AI-15).
            graph.runtime.value.microPlans[chunk.key]?.let(com.thomaswcode.decrastination.enrich.MicroPlans::lines)?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            PieceActions(graph, activity, chunk, now, photo, say)
            // Its due date, minutes or start: on the task's own sheet (2.0.0).
            TextButton(onClick = onChange) { Text("Change its due date, minutes…") }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

/** A task the plan leaves out: its name, why, and how much of it. */
@Composable
internal fun UnplannedTile(gap: Unplanned, index: Int, count: Int, lastDay: LocalDate, now: Long, zone: ZoneId, onClick: () -> Unit) {
    val why = when (gap.reason) {
        Unplanned.Reason.NotOpen -> "Can't be started before ${gap.at?.let { Format.at(it, now, zone) } ?: "later"}, its source says"
        Unplanned.Reason.Waits -> "Waits until “${gap.after ?: "another task"}” is done, as you said"
        Unplanned.Reason.OpensLater -> "Opens ${gap.at?.let { Format.at(it, now, zone) } ?: "later"}, after the plan's last day: planned once it's within reach"
        Unplanned.Reason.NoRoom -> "No room for it by ${lastDay.format(DAY)}, the plan's last day, within each day's limits"
    }
    val pieces = if (gap.pieces > 1) "${gap.pieces} pieces, " else ""
    ListTile(index, count, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(gap.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$why · ${gap.source.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(pieces + Format.minutes(gap.minutes))
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/** A real deadline that its days can't hold (PL-02): what's left, what was free, and *What gives?* to ask Claude. */
@Composable
internal fun WontFitTile(short: WontFit, index: Int, count: Int, now: Long, zone: ZoneId, onAsk: () -> Unit) {
    ListTile(index, count) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(short.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.minutes(short.leftMin)} left, ${Format.minutes(short.freeMin)} free before ${Format.at(short.deadline, now, zone)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onAsk) { Text("What gives?") }
        }
    }
}

@Composable
internal fun ChunkTile(chunk: Chunk, time: String?, index: Int, count: Int, now: Long, zone: ZoneId, onClick: () -> Unit) {
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
                    listOfNotNull(time, WidgetModel.badge(chunk, now, zone), chunk.source.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chunk.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(Format.minutes(chunk.minutes))
        }
    }
}
