package com.thomaswcode.decrastination.ui

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
 * The last fortnight (PLAN.md Phase 6): what got finished, the focus sessions, the blocks, what
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
    val stats = remember(log, now) { Stats.summary(log, now, zone) }
    val learned = remember(runtime.calibration) { Stats.calibrationLines(runtime.calibration) }
    val most = stats.days.maxOf { it.focusMin }.coerceAtLeast(1)
    val credit = runtime.credit.on(graph.focus.today(now))
    val leftMin = (graph.focus.creditLeftMs(now) / 60_000).toInt()
    val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(now, zone))
    val claudeOff = graph.claudeKey() == null && usage.calls == 0
    val top = stats.topBlocked.joinToString { (target, times) -> "${Blocklist.NAMES[target] ?: target} $times" }
    var allDays by rememberSaveable { mutableStateOf(false) }
    var inFull by rememberSaveable { mutableStateOf(false) }
    val days = if (allDays) stats.days else stats.days.take(WEEK)

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "tiles") {
            SectionHeading("The last ${Stats.DAYS} days")
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TileRow(
                    { StatTile("Finished", "${stats.completions}", if (stats.dated > 0) "${stats.onTime} of ${stats.dated} dated on time" else "none with a deadline") },
                    { StatTile("Focus", Format.minutes(stats.focusMin), "${stats.sessions} session${if (stats.sessions == 1) "" else "s"}, ${stats.sessionsFinished} to the end") },
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
        itemsIndexed(days, key = { _, it -> it.date.toString() }) { index, day ->
            // The last row folds or unfolds the older week.
            ListTile(index, days.size + 1) { DayRow(day, most, stats.days.first().date) }
        }
        item(key = "days-more") {
            ListTile(days.size, days.size + 1, onClick = { allDays = !allDays }) {
                Text(
                    if (allDays) "Show the last week only" else "Show all ${stats.days.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item(key = "learned") {
            Group("What the app has learned") {
                GroupTile {
                    if (learned.isEmpty()) {
                        Line("Nothing yet: it learns from finished work, at Sunday's review.")
                    } else {
                        learned.forEach { Line(it) }
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
                        Line("${stats.sessions} focus session${if (stats.sessions == 1) "" else "s"}, ${stats.sessionsFinished} run to the end: ${Format.minutes(stats.focusMin)} in all.")
                        if (stats.photoChecks > 0) Line("${stats.photoChecks} piece${if (stats.photoChecks == 1) "" else "s"} a photo check found done.")
                        Line("Blocked ${stats.blocks} time${if (stats.blocks == 1) "" else "s"}" + if (top.isNotEmpty()) ": $top." else ".")
                        Line(
                            when {
                                stats.protectionProblems == 0 -> "Protection: no problems found."
                                else -> "Protection: problems found ${stats.protectionProblems} time${if (stats.protectionProblems == 1) "" else "s"}, put right ${stats.protectionRepaired}."
                            },
                        )
                        Line("Free time today: earned ${Format.minutes((credit.earnedMs / 60_000).toInt())}, spent ${Format.minutes((credit.spentMs / 60_000).toInt())}: ${Format.minutes(leftMin)} left.")
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
    val quiet = day.completions == 0 && day.focusMin == 0 && day.blocks == 0
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
                if (day.focusMin > 0) {
                    Box(Modifier.fillMaxWidth(day.focusMin / most.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
            Text(
                listOfNotNull(
                    "${day.completions} done",
                    Format.minutes(day.focusMin).takeIf { day.focusMin > 0 }?.let { "$it focus" },
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
