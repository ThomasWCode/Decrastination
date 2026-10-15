package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.HalfDay
import com.thomaswcode.decrastination.core.NoSchool
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val SCHOOL_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/**
 * Settings' School section (PL-16): the days with no school and the half-days, each with a delete
 * (how one told to the chat is taken back), and a way to add each. Saved at once, not with the
 * settings' Save: they're the school calendar's, not settings.
 */
@Composable
fun SchoolSection(graph: AppGraph) {
    val school by graph.school.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = Instant.ofEpochMilli(graph.clock.now()).atZone(graph.clock.zone()).toLocalDate()
    // What's being added, kept through a turn of the phone: "off", then "off|<first>", "off|<first>|<last>"; or "half".
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf("") }
    fun day(text: String) = runCatching { LocalDate.parse(text).format(SCHOOL_DAY) }.getOrDefault(text)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (school.noSchool.isEmpty() && school.halfDays.isEmpty()) {
            Text(
                "No days off or half-days yet. Tell Ask Claude (\"half term is 26 to 30 October\"), or add them here: a day off is planned as a weekend is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        school.noSchool.forEach { entry ->
            val days = if (entry.from == entry.to) day(entry.from) else "${day(entry.from)} to ${day(entry.to)}"
            Entry("No school, $days" + entry.label.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()) {
                scope.launch { graph.school.update { it.without(entry) } }
            }
        }
        school.halfDays.forEach { entry ->
            Entry("Half-day, ${day(entry.date)}: ends %02d:%02d".format(Locale.UK, entry.endsAtMin / 60, entry.endsAtMin % 60)) {
                scope.launch { graph.school.update { it.without(entry) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                label = ""
                adding = "off"
            }) { Text("Add days off") }
            TextButton(onClick = { adding = "half" }) { Text("Add a half-day") }
        }
    }

    val step = adding?.split('|').orEmpty()
    when {
        adding == "half" -> DateTimeDialog("Half-day", initialDay = today, initialTime = LocalTime.of(12, 30), earliest = today, onClose = { adding = null }) { date, time ->
            val end = time ?: LocalTime.of(12, 30)
            scope.launch { graph.school.update { it.with(HalfDay(date.toString(), end.hour * 60 + end.minute)) } }
            adding = null
        }
        step.firstOrNull() == "off" && step.size == 1 -> DateTimeDialog("First day off", initialDay = today, initialTime = null, earliest = today, onClose = { adding = null }) { first, _ ->
            adding = "off|$first"
        }
        step.firstOrNull() == "off" && step.size == 2 -> {
            val first = LocalDate.parse(step[1])
            DateTimeDialog("Last day off", initialDay = first, initialTime = null, earliest = first, onClose = { adding = null }) { last, _ ->
                adding = "off|$first|$last"
            }
        }
        step.firstOrNull() == "off" && step.size == 3 -> AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(if (step[1] == step[2]) "No school, ${day(step[1])}" else "No school, ${day(step[1])} to ${day(step[2])}") },
            text = {
                OutlinedTextField(value = label, onValueChange = { label = it.take(60) }, label = { Text("What it is (half term, an INSET day), if you like") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val entry = NoSchool(step[1], step[2], label.trim())
                    scope.launch { graph.school.update { it.with(entry) } }
                    adding = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }
}

/** One row of a list saved at once (a day off, a half-day, a memory), and its delete, [action] said aloud. */
@Composable
internal fun Entry(text: String, action: String = "Delete", onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_close), contentDescription = action) }
    }
}
