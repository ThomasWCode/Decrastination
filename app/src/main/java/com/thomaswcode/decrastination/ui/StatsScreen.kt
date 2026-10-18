package com.thomaswcode.decrastination.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Blocklist
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.learn.Stats
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The last fortnight (docs/spec/learning.md): what got finished, the slots kept, the blocks, what
 * protection found, today's free time, and what the app has learned about your work. The numbers
 * as tiles to skim, the sentences behind them folded under "In full".
 */
@Composable
fun StatsScreen(graph: AppGraph) {
    val log by graph.log.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
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
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val termDays = remember(school, today) { (java.time.temporal.ChronoUnit.DAYS.between(Stats.termStart(today) { school.isSchoolDay(it) }, today) + 1).toInt().coerceAtLeast(1) }
    val days = if (period == TERM) termDays else period
    val stats = remember(log, now, days) { Stats.summary(log, now, zone, days) }
    val subjects = remember(log, now, days, settings.classMap) { Stats.bySubject(log, settings.classMap, now, zone, days) }
    val heat = remember(log, now) { Stats.heat(log, now, zone) }
    val suggested = remember(heat) { Stats.suggestedWeekdayHours(heat) }
    var proposing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val learned = remember(runtime.calibration) { Stats.calibrationRows(runtime.calibration) }
    // A class's figure to forget (LR-06), asked first.
    var forgetting by remember { mutableStateOf<Stats.CalibrationRow?>(null) }
    val most = stats.days.maxOf { it.slotMin }.coerceAtLeast(1)
    val credit = runtime.credit.on(graph.focus.today(now))
    val leftMin = (graph.focus.creditLeftMs(now) / 60_000).toInt()
    val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(now, zone))
    val claudeOff = graph.gateway.claudeKey() == null && usage.calls == 0
    val top = stats.topBlocked.joinToString { (target, times) -> "${Blocklist.NAMES[target] ?: target} $times" }
    var allDays by rememberSaveable { mutableStateOf(false) }
    var inFull by rememberSaveable { mutableStateOf(false) }
    val shown = if (allDays) stats.days else stats.days.take(WEEK)

    forgetting?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget this?") },
            text = { Text("${row.line.substringBefore(':')}: what the app has learned goes, and its work so far won't teach it again. Its kind's figure stands for it meanwhile.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    forgetting = null
                    row.forget?.let { key -> scope.launch { graph.forgetCalibration(key) } }
                }) { Text("Forget") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { forgetting = null }) { Text("Keep") } },
        )
    }
    if (proposing && suggested != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { proposing = false },
            title = { Text("Weekday hours from your slots?") },
            text = { Text("From ${Format.clock(suggested.startMin)} to ${Format.clock(suggested.endMin)}, where most of your kept slots were on weekdays. Now ${Format.clock(settings.weekdayHours.startMin)} to ${Format.clock(settings.weekdayHours.endMin)}.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    proposing = false
                    scope.launch { graph.changeSettings { it.copy(weekdayHours = suggested) } }
                }) { Text("Set them") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { proposing = false }) { Text("Keep mine") } },
        )
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = FELIX_ROOM)) {
        item(key = "period") {
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Stats.DAYS to "2 weeks", SIX_WEEKS to "6 weeks", TERM to "This term").forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(selected = period == value, onClick = { period = value }, label = { Text(label) })
                }
            }
        }
        item(key = "tiles") {
            SectionHeading("The last $days days")
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TileRow(
                    { StatTile("Finished", "${stats.completions}", if (stats.dated > 0) "${stats.onTime} of ${stats.dated} dated on time" else "none with a deadline") },
                    { StatTile("Slots kept", "${stats.slotsKept} of ${stats.slotsPlanned}", "earned ${stats.slotsEarned}, ${Format.minutes(stats.slotMin)} worked") },
                )
                TileRow(
                    { StatTile("Blocked", "${stats.blocks}", stats.topBlocked.firstOrNull()?.let { (target, _) -> "most: ${Blocklist.NAMES[target] ?: target}" } ?: "nothing") },
                    {
                        StatTile(
                            "Protection",
                            if (stats.protectionProblems == 0) "Fine" else "${stats.protectionProblems}",
                            if (stats.protectionProblems == 0) "no problems found" else "problems, ${stats.protectionRepaired} put right",
                            warn = stats.protectionProblems > stats.protectionRepaired,
                        )
                    },
                )
                TileRow(
                    { StatTile("Free time left today", Format.minutes(leftMin), "earned ${Format.minutes((credit.earnedMs / 60_000).toInt())}, spent ${Format.minutes((credit.spentMs / 60_000).toInt())}") },
                    {
                        StatTile(
                            "Claude this month",
                            if (claudeOff) "Off" else "$%.2f".format(Locale.UK, usage.spentUsd),
                            if (claudeOff) "nothing sent" else "${usage.calls} call${if (usage.calls == 1) "" else "s"}" + (settings.aiMonthlyCapUsd?.let { ", cap $$it" } ?: ""),
                        )
                    },
                )
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
                                "${row.subject}: ${Format.minutes(row.minutes)} in ${row.pieces} piece${if (row.pieces == 1) "" else "s"}" +
                                    (if (row.dated > 0) ", ${row.onTime} of ${row.dated} on time" else "") +
                                    (row.ratio?.let { ", %.1f× the estimates (from %d)".format(Locale.UK, it, row.timed) } ?: ""),
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
                        androidx.compose.material3.TextButton(onClick = { proposing = true }) { Text("Set weekday hours from this (${Format.clock(w.startMin)}–${Format.clock(w.endMin)})") }
                    }
                }
            }
        }
        item(key = "export") {
            androidx.compose.material3.TextButton(modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                scope.launch {
                    val file = java.io.File(context.cacheDir, "exports/decrastination-$today.zip")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.thomaswcode.decrastination.learn.Export.zip(com.thomaswcode.decrastination.learn.Export.csv(log, runtime.calibration, zone, graph.stores.chatArchive.value.messages + graph.chat.value.messages), file) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(send, "Export the record"))
                }
            }) { Text("Export the record as CSV") }
        }
        item(key = "learned") {
            Group("What the app has learned") {
                GroupTile {
                    if (learned.isEmpty()) {
                        Line("Nothing yet: it learns from finished work, at Sunday's review.")
                    } else {
                        learned.forEach { row ->
                            if (row.forget == null) {
                                Line(row.line)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 3.dp))
                                    androidx.compose.material3.TextButton(onClick = { forgetting = row }) { Text("Forget") }
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
                        Line("${stats.completions} finished" + if (stats.dated > 0) "; ${stats.onTime} of the ${stats.dated} with a deadline by it." else ".")
                        Line("${stats.slotsKept} slot${if (stats.slotsKept == 1) "" else "s"} kept of ${stats.slotsPlanned}, ${stats.slotsEarned} earning free time: ${Format.minutes(stats.slotMin)} worked.")
                        if (stats.photoChecks > 0) Line("${stats.photoChecks} piece${if (stats.photoChecks == 1) "" else "s"} a photo check found done.")
                        Line("Blocked ${stats.blocks} time${if (stats.blocks == 1) "" else "s"}" + if (top.isNotEmpty()) ": $top." else ".")
                        Line(
                            when {
                                stats.protectionProblems == 0 -> "Protection: no problems found."
                                else -> "Protection: problems found ${stats.protectionProblems} time${if (stats.protectionProblems == 1) "" else "s"}, put right ${stats.protectionRepaired}."
                            },
                        )
                        Line("Free time today: earned ${Format.minutes((credit.earnedMs / 60_000).toInt())}, spent ${Format.minutes((credit.spentMs / 60_000).toInt())}: ${Format.minutes(leftMin)} left.")
                        Line("Shown: the last $days days" + if (period == TERM) ", this term." else ".")
                        Line(
                            "Claude this month: " + if (claudeOff) {
                                "off: nothing sent."
                            } else {
                                "$%.2f".format(Locale.UK, usage.spentUsd) + (settings.aiMonthlyCapUsd?.let { " of $$it" } ?: "") + " in ${usage.calls} call${if (usage.calls == 1) "" else "s"}."
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val WEEK = 7

/** The periods but two weeks (PL-10): six weeks, and this term (its days counted as the calendar has them). */
private const val SIX_WEEKS = 42
private const val TERM = -1

/** Slots kept by weekday and hour (PL-09): a row a day, a cell an hour from 7 to 23, darker for more kept, red where more were missed. */
@Composable
private fun HeatGrid(heat: Map<Pair<java.time.DayOfWeek, Int>, Pair<Int, Int>>) {
    val most = heat.values.maxOfOrNull { it.first }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        java.time.DayOfWeek.entries.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
                (7..23).forEach { hour ->
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
        Text("7:00 to midnight, the last ${Stats.HEAT_WEEKS} weeks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun StatTile(label: String, value: String, note: String, warn: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayRow(day: Stats.Day, most: Int, today: LocalDate) {
    val quiet = day.completions == 0 && day.slotMin == 0 && day.blocks == 0
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
                listOfNotNull(
                    "${day.completions} done",
                    Format.minutes(day.slotMin).takeIf { day.slotMin > 0 }?.let { "$it in slots" },
                    "${day.blocks} block${if (day.blocks == 1) "" else "s"}".takeIf { day.blocks > 0 },
                    when (day.planDone) {
                        true -> "plan done"
                        false -> "plan not done"
                        null -> null
                    },
                ).joinToString(" · "),
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
