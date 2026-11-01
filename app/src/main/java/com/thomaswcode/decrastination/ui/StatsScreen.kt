package com.thomaswcode.decrastination.ui

import android.content.Intent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.learn.Export
import com.thomaswcode.decrastination.learn.Stats
import com.thomaswcode.decrastination.sync.protocol.ForgetCalibration
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The last fortnight (docs/spec/learning.md): what got finished, the slots kept, the blocks, what
 * protection found, today's free time, and what the app has learned about your work. The numbers
 * as tiles to skim, the sentences behind them folded under "In full".
 */
@Composable
fun StatsScreen(graph: AppGraph) {
    val log by graph.log.state.collectAsStateWithLifecycle()
    val learning by graph.stores.learning.state.collectAsStateWithLifecycle()
    val model by graph.stores.model.state.collectAsStateWithLifecycle()
    val session by graph.stores.session.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val now by produceState(graph.clock.now()) {
        while (true) {
            delay(60_000)
            value = graph.clock.now()
        }
    }
    val zone = graph.clock.zone()
    // The period (PL-10): two weeks, six, or this term as the school calendar has it.
    var period by rememberSaveable { mutableStateOf(Stats.DAYS) }
    val school by graph.school.state.collectAsStateWithLifecycle()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = remember(school, today, period) { StatsPage.days(period, today) { school.isSchoolDay(it) } }
    val stats = remember(log, now, days) { Stats.summary(log, now, zone, days) }
    val subjects = remember(log, now, days, settings.classMap) { Stats.bySubject(log, settings.classMap, now, zone, days) }
    val heat = remember(log, now) { Stats.heat(log, now, zone) }
    val suggested = remember(heat) { Stats.suggestedWeekdayHours(heat) }
    var proposing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val learned = remember(learning.calibration) { Stats.calibrationRows(learning.calibration) }
    // A class's figure to forget (LR-06), asked first.
    var forgetting by remember { mutableStateOf<Stats.CalibrationRow?>(null) }
    val most = stats.days.maxOf { it.slotMin }.coerceAtLeast(1)
    val credit = session.credit.on(graph.focus.today(now))
    val leftMin = (graph.focus.creditLeftMs(now) / 60_000).toInt()
    val usage = model.aiUsage.forMonth(AiUsage.monthOf(now, zone))
    val claudeOff = graph.gateway.claudeKey() == null && usage.calls == 0
    val facts = StatsPage.Today((credit.earnedMs / 60_000).toInt(), (credit.spentMs / 60_000).toInt(), leftMin, usage.spentUsd, usage.calls, settings.aiMonthlyCapUsd, claudeOff)
    var allDays by rememberSaveable { mutableStateOf(false) }
    var inFull by rememberSaveable { mutableStateOf(false) }
    val shown = if (allDays) stats.days else stats.days.take(StatsPage.WEEK)

    forgetting?.let { row ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(StatsPage.FORGET_TITLE) },
            text = { Text(StatsPage.forgetDetail(row)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    row.forget?.let { key -> scope.launch { graph.act(ForgetCalibration(key)) } }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
    if (proposing && suggested != null) {
        AlertDialog(
            onDismissRequest = { proposing = false },
            title = { Text(StatsPage.HOURS_TITLE) },
            text = { Text(StatsPage.hoursDetail(suggested, settings.weekdayHours)) },
            confirmButton = {
                TextButton(onClick = {
                    proposing = false
                    scope.launch { graph.commands.changeSettings { it.copy(weekdayHours = suggested) } }
                }) { Text("Set them") }
            },
            dismissButton = { TextButton(onClick = { proposing = false }) { Text("Keep mine") } },
        )
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = FELIX_ROOM)) {
        item(key = "period") {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPage.PERIODS.forEach { (value, label) ->
                    FilterChip(selected = period == value, onClick = { period = value }, label = { Text(label) })
                }
            }
        }
        item(key = "tiles") {
            SectionHeading("The last $days days")
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPage.tiles(stats, facts).chunked(2).forEach { (left, right) -> TileRow({ StatTile(left) }, { StatTile(right) }) }
            }
        }
        item(key = "days-title") { SectionHeading("Day by day", trailing = "bars: focus time") }
        itemsIndexed(shown, key = { _, it -> it.date.toString() }) { index, day ->
            // The last row folds or unfolds the older week.
            ListTile(index, shown.size + 1) { DayRow(day, most, stats.days.first().date) }
        }
        item(key = "days-more") {
            ListTile(shown.size, shown.size + 1, onClick = { allDays = !allDays }) {
                Text(
                    if (allDays) "Show the last week only" else "Show all ${stats.days.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // By subject (PL-08): the minutes, the pieces, the deadlines met, and time against the estimates.
        if (subjects.isNotEmpty()) {
            item(key = "subjects") {
                Group("By subject") {
                    GroupTile {
                        subjects.forEach { row ->
                            Line(
                                StatsPage.subject(row),
                            )
                        }
                    }
                }
            }
        }
        // When slots are kept (PL-09): the last eight weeks by weekday and hour.
        item(key = "heat") {
            Group("When you work") {
                GroupTile {
                    HeatGrid(heat)
                    suggested?.let { w ->
                        TextButton(onClick = { proposing = true }) { Text(StatsPage.hoursLabel(w)) }
                    }
                }
            }
        }
        item(key = "export") {
            TextButton(modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                scope.launch {
                    val file = File(context.cacheDir, "exports/decrastination-$today.zip")
                    withContext(Dispatchers.IO) { Export.zip(Export.csv(log, learning.calibration, zone, graph.stores.chatArchive.value.messages + graph.chat.value.messages), file) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Export the record"))
                }
            }) { Text("Export the record as CSV") }
        }
        item(key = "learned") {
            Group("What the app has learned") {
                GroupTile {
                    if (learned.isEmpty()) {
                        Line(StatsPage.NOTHING_LEARNED)
                    } else {
                        learned.forEach { row ->
                            if (row.forget == null) {
                                Line(row.line)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 3.dp))
                                    TextButton(onClick = { forgetting = row }) { Text("Forget") }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "full") {
            FoldingHeading("In full", inFull, { inFull = !inFull })
            if (inFull) {
                Group {
                    GroupTile {
                        StatsPage.inFull(stats, facts, days, period == StatsPage.TERM).forEach { Line(it) }
                    }
                }
            }
        }
    }
}

/** Slots kept by weekday and hour (PL-09): a row a day, a cell an hour from 7 to 23, darker for more kept, red where more were missed. */
@Composable
private fun HeatGrid(heat: Map<Pair<DayOfWeek, Int>, Pair<Int, Int>>) {
    val most = heat.values.maxOfOrNull { it.first }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DayOfWeek.entries.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(day.getDisplayName(TextStyle.SHORT, Locale.UK), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                StatsPage.HEAT_HOURS.forEach { hour ->
                    val (kept, missed) = heat[day to hour] ?: (0 to 0)
                    val colour = when {
                        missed > kept -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        kept > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * kept / most)
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                }
            }
        }
        Text(StatsPage.HEAT_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two tiles side by side, as tall as the taller. */
@Composable
private fun TileRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight()) { left() }
        Box(Modifier.weight(1f).fillMaxHeight()) { right() }
    }
}

/** One number to skim: what it is, the number, and a word on it. */
@Composable
private fun StatTile(tile: StatsPage.Tile) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tile.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            tile.value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (tile.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(tile.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayRow(day: Stats.Day, most: Int, today: LocalDate) {
    val quiet = StatsPage.quiet(day)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (day.date == today) "Today" else day.date.format(DAY),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(88.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Focus minutes as a bar, against the fortnight's busiest day.
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (day.slotMin > 0) {
                    Box(Modifier.fillMaxWidth(day.slotMin / most.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            Text(
                StatsPage.day(day),
                style = MaterialTheme.typography.bodySmall,
                // An empty day fades back, so the days with something stand out.
                color = if (quiet) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(text: String, color: Color = Color.Unspecified) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(vertical = 3.dp))
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
