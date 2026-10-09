package com.thomaswcode.decrastination.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.learn.CalendarEvent
import com.thomaswcode.decrastination.learn.CalendarTime
import com.thomaswcode.decrastination.learn.EventJudge
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fortnight's calendar as the plan counts it: each day's events and how much of your time each
 * takes, with your instructions about days. An instruction about an event or a day is written here.
 */
class CalendarActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { AppTheme { Screen(graph) } }
    }

    /** An instruction being written: what about, and in words. */
    private data class Writing(val about: About, val what: String)

    @Composable
    private fun Screen(graph: AppGraph) {
        val instructions by graph.instructions.state.collectAsStateWithLifecycle()
        val runtime by graph.runtime.state.collectAsStateWithLifecycle()
        // Read afresh on opening, and whenever the instructions change.
        val time by produceState(graph.calendarTime, instructions) {
            CalendarTime.refresh(applicationContext)
            value = graph.calendarTime
        }
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val applied = instructions.applied
        val yours = Instructions.eventAnswers(applied)
        val answers = runtime.eventAnswers + yours
        val today = dayOf(now, zone)
        val days = (0L until DAYS).map { today.plusDays(it) }
        val allowed = remember { CalendarTime.allowed(this) }
        var writing by remember { mutableStateOf<Writing?>(null) }

        Scaffold(topBar = { BackBar("Calendar", this) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!allowed) {
                    item(key = "not-allowed") {
                        Text(
                            "The calendar isn't allowed yet: allow it in Setup. Instructions about days still work.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                }
                for (day in days) {
                    val events = time.events.filter { day in EventJudge.days(it, zone) }.sortedBy { it.start }
                    // Your instructions about this day: its limit, and its busy times.
                    val rules = applied.flatMap { it.changes }.filter { c ->
                        (c.type == ChangeType.DayLimit || c.type == ChangeType.BusyTime) &&
                            (c.date == day.toString() || (c.date == null && c.weekday == day.dayOfWeek.value))
                    }
                    item(key = "day-$day") {
                        DayHeading(day, today) { writing = Writing(About(day = day.toString()), "About ${name(day, today)}") }
                    }
                    val count = events.size + rules.size
                    if (count == 0) {
                        item(key = "empty-$day") { ListTile(0, 1) { Text("Nothing in the calendar", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    itemsIndexed(rules, key = { i, _ -> "rule-$day-$i" }) { index, rule ->
                        ListTile(index, count) {
                            Text("Your instruction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(Instructions.describe(rule, emptyMap(), zone), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                    itemsIndexed(events, key = { _, e -> "event-$day-${e.id}-${e.start}" }) { index, event ->
                        val key = EventJudge.key(event)
                        ListTile(rules.size + index, count, onClick = {
                            writing = Writing(About(eventKey = key, eventTitle = event.title, eventStart = event.start), "About “${event.title}” (every event of that name)")
                        }) {
                            // A long title (a whole note in it) cut to two lines: the event's own app has the rest.
                            Text(event.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(span(event, now, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val by = when (key) {
                                in yours -> " (your instruction)"
                                in runtime.eventAnswers -> " (your answer)"
                                else -> ""
                            }
                            Text(counted(EventJudge.judge(event, answers)) + by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        writing?.let { w ->
            InstructionDialog(w.what, onDismiss = { writing = null }) { text ->
                writing = null
                sendInstruction(this, graph, text, w.about)
            }
        }
    }

    @Composable
    private fun DayHeading(day: LocalDate, today: LocalDate, onAdd: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name(day, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("Add instruction") }
        }
    }

    private fun name(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DAY)
    }

    /** How the plan counts an event, in words. */
    private fun counted(judgement: EventJudge.Judgement): String = when (judgement) {
        EventJudge.Judgement.Free -> "You can work through it"
        EventJudge.Judgement.Busy -> "Takes all its time"
        is EventJudge.Judgement.Load -> "Takes ${Format.minutes(judgement.minutes)} of its day"
        EventJudge.Judgement.Ask -> "Not known yet: you'll be asked in the week before it"
    }

    private fun span(event: CalendarEvent, now: Long, zone: ZoneId): String =
        if (event.allDay) "All day" else "${Format.at(event.start, now, zone)} to ${Format.at(event.end, now, zone).substringAfterLast(' ')}"

    private companion object {
        const val DAYS = 14L
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    }
}
