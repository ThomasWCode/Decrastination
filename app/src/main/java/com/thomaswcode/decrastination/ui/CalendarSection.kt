package com.thomaswcode.decrastination.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.calendar.SlotCalendar
import com.thomaswcode.decrastination.learn.CalendarTime
import kotlinx.coroutines.launch

/** How to make the calendar the plan is written to: one the app made itself wouldn't leave the phone. */
private const val HOW =
    "Create a calendar called Decrastination at calendar.google.com (Settings → Add calendar → Create new calendar), wait a minute for it to appear below, then pick it. " +
        "If Google Calendar gives it default notifications, turn them off there: the app's events carry none."

/**
 * The calendars (NF-07, DX-13): the one the plan's next seven days of slots are written to, picked
 * from those events can be added to ([choosing]); and which are read for busy time, each with a
 * tick ([reading]), the one written to never. Saved at once, as they're picked.
 */
@Composable
fun CalendarSection(graph: AppGraph, activity: Activity, choosing: Boolean = true, reading: Boolean = true) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val canRead = remember(refresh) { CalendarTime.allowed(activity) }
    val canWrite = remember(refresh) { SlotCalendar.allowed(activity) }
    val calendars = remember(refresh, canRead) { runCatching { SlotCalendar.calendars(activity) }.getOrDefault(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        scope.launch { CalendarTime.refresh(activity) }
    }
    // Looked at again on coming back: a calendar made at calendar.google.com meanwhile shows.
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    fun change(change: (com.thomaswcode.decrastination.data.Settings) -> com.thomaswcode.decrastination.data.Settings) {
        scope.launch { graph.changeSettings(change) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (!canRead || !canWrite) {
            Note(if (canRead) "Writing your plan to a calendar needs the calendar's write permission." else "Choosing calendars needs the calendar's permissions.")
            TextButton(onClick = { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { Text("Allow") }
        }
        if (!canRead) return@Column
        if (choosing) {
            Text("Your plan in Google Calendar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Note(HOW)
            Choice("None: not written", settings.calendarWriteId == null) { change { it.copy(calendarWriteId = null) } }
            calendars.filter { it.writable }.forEach { calendar ->
                Choice("${calendar.name} · ${calendar.account}", settings.calendarWriteId == calendar.id) { change { it.copy(calendarWriteId = calendar.id) } }
            }
        }
        if (reading) {
            Text("Read for busy time", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            calendars.forEach { calendar ->
                if (calendar.id == settings.calendarWriteId) {
                    Note("${calendar.name}: written to, never read")
                } else {
                    val read = calendar.id !in settings.calendarsExcluded
                    Row(Modifier.fillMaxWidth().clickable { change { s -> s.copy(calendarsExcluded = if (read) s.calendarsExcluded + calendar.id else s.calendarsExcluded - calendar.id) } }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = read, onCheckedChange = null)
                        Text(calendar.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}
