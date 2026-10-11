package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.FocusSession
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.DayBucket
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Unplanned
import com.thomaswcode.decrastination.data.RuntimeState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.KeyProblem
import com.thomaswcode.decrastination.protect.ProtectionActivity
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
    // Your instructions' days and the calendar count too: either changing redraws it.
    val instructions by graph.instructions.state.collectAsStateWithLifecycle()
    val calendar by graph.calendarState.collectAsStateWithLifecycle()
    val plan = remember(tasks, settings, now, instructions, calendar) { graph.plan(tasks, settings, now) }
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    // How the last action went (asking a source, a photo check), and the task it's about: shown
    // with that task, on the card or in its sheet; one about none (a Teams sync) on the card.
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var messageFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            message = null
        }
    }
    val say = { taskId: String?, text: String ->
        messageFor = taskId
        message = text
    }
    val photo = rememberPhotoCheck(graph, activity, plan, say)
    /** The message about [taskId], or one about no task, where [general]. */
    fun messageOf(taskId: String, general: Boolean = false): String? = message?.takeIf { messageFor == taskId || (general && messageFor == null) }
    // A piece whose actions are open in a sheet (IMP-P1-001), by its key.
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    val trouble = trouble(tasks, runtime, now)

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
        // A source that can't be read, Teams' data stale, protection off: said first, with the way to
        // put it right, so an empty or short plan isn't taken for all there is (IMP-P1-007).
        if (trouble.isNotEmpty()) {
            item(key = "trouble") {
                TroubleCard(
                    trouble,
                    onSetup = { activity.startActivity(Intent(activity, SetupActivity::class.java)) },
                    onProtection = { activity.startActivity(Intent(activity, ProtectionActivity::class.java)) },
                    onTeams = { scope.launch { say(null, graph.requestTeamsSync() ?: "The Teams widget is syncing Teams") } },
                )
            }
        }
        item(key = "next") {
            val unread = Source.entries.filter { tasks.status(it).lastSuccessAt == null }
            NextCard(
                graph, activity, plan, runtime.session, zone, photo, ::messageOf, say,
                empty = when {
                    unread.isNotEmpty() -> "Nothing planned yet: ${unread.joinToString { it.label }} not read yet."
                    trouble.isNotEmpty() -> "Nothing planned, but not every source is up to date (above): there may be more."
                    else -> "No work is planned. New tasks appear as the sources are read."
                },
            )
        }
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
                ChunkTile(chunk, index, tiles, plan.now, zone) { sheetFor = keyOf(chunk) }
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
        // Open work the plan leaves out, each with why (IMP-P1-002): folded, as it isn't for now.
        if (plan.unplanned.isNotEmpty()) {
            val expanded = NOT_PLANNED in unfolded
            item(key = NOT_PLANNED) {
                FoldingHeading(
                    "Not planned",
                    expanded,
                    { unfolded = unfolded.toggle(NOT_PLANNED) },
                    summary = "${plan.unplanned.size}",
                    detail = "Open work left out of the plan, and why",
                )
            }
            if (expanded) {
                val lastDay = plan.buckets.lastOrNull()?.date ?: plan.today
                itemsIndexed(plan.unplanned, key = { _, it -> "unplanned-${it.taskId}-${it.reason}" }) { index, gap ->
                    UnplannedTile(gap, index, plan.unplanned.size, lastDay, plan.now, zone) {
                        tasks.tasks.firstOrNull { it.id == gap.taskId }?.let { task -> scope.launch { TaskOpener.open(activity, task)?.let { say(null, it) } } }
                    }
                }
            }
        }
    }

    // A piece's actions, as the block screen has them, without opening a blocked app first.
    sheetFor?.let { key ->
        val chunk = plan.ordered.firstOrNull { keyOf(it) == key }
        if (chunk == null) {
            // Done, or planned again: nothing to act on.
            LaunchedEffect(key) { sheetFor = null }
        } else {
            PieceSheet(graph, activity, chunk, plan.now, zone, photo, messageOf(chunk.taskId), { say(chunk.taskId, it) }) { sheetFor = null }
        }
    }
}

private const val MESSAGE_MS = 20_000L
private const val NOT_PLANNED = "not-planned"

/** A piece told apart from every other in the plan: its task, its place, its step. */
private fun keyOf(chunk: Chunk) = "${chunk.taskId}#${chunk.part}#${chunk.stepIndex}#${chunk.step}"

/** Something that makes the plan less than the whole picture, and where it's put right. */
private data class Trouble(val text: String, val fix: Fix) {
    enum class Fix { Protection, Setup, Teams }
}

/** As the widget's warning has them, all of them, protection first. */
private fun trouble(tasks: TaskState, runtime: RuntimeState, now: Long): List<Trouble> = buildList {
    runtime.protection.problems.forEach { add(Trouble(it, Trouble.Fix.Protection)) }
    if (runtime.unreadable.isNotEmpty()) add(Trouble("A saved file couldn't be read: Setup says what was kept", Trouble.Fix.Setup))
    Source.entries.forEach { source -> tasks.status(source).error?.let { add(Trouble("Can't read ${source.label}: $it", Trouble.Fix.Setup)) } }
    WidgetModel.teamsWarning(tasks, now)?.let { add(Trouble(it, Trouble.Fix.Teams)) }
}

@Composable
private fun TroubleCard(trouble: List<Trouble>, onSetup: () -> Unit, onProtection: () -> Unit, onTeams: () -> Unit) {
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
private fun NextCard(
    graph: AppGraph,
    activity: Activity,
    plan: Plan,
    session: FocusSession?,
    zone: ZoneId,
    photo: PhotoCheck,
    messageOf: (taskId: String, general: Boolean) -> String?,
    say: (taskId: String?, text: String) -> Unit,
    empty: String,
) {
    // A session under way: the piece it's on, as the block screen shows it.
    val next = session?.let { plan.chunksOf(it.taskId).firstOrNull() } ?: plan.next
    val message = messageOf(next?.taskId.orEmpty(), true)
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session != null) SessionLine(graph, session)
            if (next == null) {
                Text("Nothing to do", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(empty, style = MaterialTheme.typography.bodyMedium)
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                return@Column
            }
            Text("DO NOW", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(next.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Badges(next, plan.now, zone)
            plan.then?.takeIf { session == null }?.let { Text("Then: ${it.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            PieceActions(graph, activity, next, plan.now, photo) { say(next.taskId, it) }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

/** Its due date (red when urgent), its minutes and its source, wrapping where the screen is narrow. */
@Composable
private fun Badges(chunk: Chunk, now: Long, zone: ZoneId) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val badge = WidgetModel.badge(chunk, now, zone)
        if (chunk.urgent) WarningPill(badge) else Pill(badge)
        Pill(Format.minutes(chunk.minutes))
        Text(chunk.source.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** "Focus: Statics Prep, 12:34 left", by the clock that ends the session, every second. */
@Composable
private fun SessionLine(graph: AppGraph, session: FocusSession) {
    val left by produceState(session.leftMs(graph.clock.now(), graph.clock.uptime()), session) {
        while (true) {
            value = session.leftMs(graph.clock.now(), graph.clock.uptime())
            delay(1_000)
        }
    }
    val seconds = (left / 1000).coerceAtLeast(0)
    Text(
        "Focus: ${session.label}, %d:%02d left".format(Locale.UK, seconds / 60, seconds % 60),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A piece's actions, from a tap on it in the plan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PieceSheet(
    graph: AppGraph,
    activity: Activity,
    chunk: Chunk,
    now: Long,
    zone: ZoneId,
    photo: PhotoCheck,
    message: String?,
    say: (String) -> Unit,
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
            PieceActions(graph, activity, chunk, now, photo, say)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

/** A task the plan leaves out: its name, why, and how much of it. */
@Composable
private fun UnplannedTile(gap: Unplanned, index: Int, count: Int, lastDay: LocalDate, now: Long, zone: ZoneId, onClick: () -> Unit) {
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
