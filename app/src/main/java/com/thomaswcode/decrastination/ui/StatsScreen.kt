package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * protection found, today's free time, and what the app has learned about your work.
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

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "totals") {
            Title("The last ${Stats.DAYS} days")
            Line("${stats.completions} finished" + if (stats.dated > 0) "; ${stats.onTime} of the ${stats.dated} with a deadline by it." else ".")
            Line("${stats.sessions} focus session${if (stats.sessions == 1) "" else "s"}, ${stats.sessionsFinished} run to the end: ${Format.minutes(stats.focusMin)} in all.")
            val top = stats.topBlocked.joinToString { (target, times) -> "${Blocklist.NAMES[target] ?: target} $times" }
            Line("Blocked ${stats.blocks} time${if (stats.blocks == 1) "" else "s"}" + if (top.isNotEmpty()) ": $top." else ".")
            Line(
                when {
                    stats.protectionProblems == 0 -> "Protection: no problems found."
                    else -> "Protection: problems found ${stats.protectionProblems} time${if (stats.protectionProblems == 1) "" else "s"}, put right ${stats.protectionRepaired}."
                },
            )
        }
        item(key = "days-title") { Title("Day by day") }
        items(stats.days, key = { it.date.toString() }) { day -> DayRow(day, most, stats.days.first().date) }
        item(key = "today") {
            Title("Free time today")
            val credit = runtime.credit.on(graph.focus.today(now))
            Line("Earned ${Format.minutes((credit.earnedMs / 60_000).toInt())}, spent ${Format.minutes((credit.spentMs / 60_000).toInt())}: ${Format.minutes((graph.focus.creditLeftMs(now) / 60_000).toInt())} left.")
        }
        item(key = "learned") {
            Title("What the app has learned")
            if (learned.isEmpty()) {
                Line("Nothing yet: it learns from finished work, at Sunday's review.")
            } else {
                learned.forEach { Line(it) }
            }
        }
        item(key = "claude") {
            Title("Claude this month")
            val usage = runtime.aiUsage.forMonth(AiUsage.monthOf(now, zone))
            Line(
                if (graph.claudeKey() == null && usage.calls == 0) {
                    "Off: nothing sent."
                } else {
                    "£%.2f of £%d in %d call%s.".format(Locale.UK, usage.spentGbp(settings.usdToGbp), settings.aiMonthlyCapGbp, usage.calls, if (usage.calls == 1) "" else "s")
                },
            )
        }
    }
}

@Composable
private fun DayRow(day: Stats.Day, most: Int, today: LocalDate) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (day.date == today) "Today" else day.date.format(DAY), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Column(Modifier.weight(1f)) {
            // Focus minutes as a bar, against the fortnight's busiest day.
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
