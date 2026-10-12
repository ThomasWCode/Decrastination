package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.core.OwnTasks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Midnight UTC of [day], as the date picker keeps its days. */
private fun utcMidnight(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * A day picked, then, given [initialTime], a time of day: [title] says what for. Days before
 * [earliest] can't be picked. The day picked is kept through the screen being made again (a turn of
 * the phone) while the time is picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(
    title: String,
    initialDay: LocalDate,
    initialTime: LocalTime?,
    earliest: LocalDate?,
    onClose: () -> Unit,
    onPick: (LocalDate, LocalTime?) -> Unit,
) {
    var day by rememberSaveable { mutableStateOf<Long?>(null) }
    val picked = day
    if (picked == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcMidnight(initialDay),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = earliest == null || utcTimeMillis >= utcMidnight(earliest)
            },
        )
        DatePickerDialog(
            onDismissRequest = onClose,
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    val chosen = Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    if (initialTime == null) onPick(chosen, null) else day = chosen.toEpochDay()
                }) { Text(if (initialTime == null) "Done" else "Next") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        ) {
            DatePicker(
                state,
                title = { Text(title, maxLines = 1, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
                showModeToggle = false,
            )
        }
        return
    }
    val time = initialTime ?: LocalTime.of(23, 59)
    val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$title, at") },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalDate.ofEpochDay(picked), LocalTime.of(state.hour, state.minute)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { day = null }) { Text("Back") } },
    )
}

/** How long something takes, in hours and minutes, from [initial], at least [least]; [note] says a word more. */
@Composable
fun MinutesDialog(title: String, initial: Int, note: String?, least: Int = 1, onClose: () -> Unit, onPick: (Int) -> Unit) {
    var hours by rememberSaveable { mutableStateOf((initial / 60).toString()) }
    var minutes by rememberSaveable { mutableStateOf((initial % 60).toString()) }
    val total = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
    val ok = total in least..OwnTasks.MINUTES.last && (minutes.toIntOrNull() ?: 0) < 60
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        hours,
                        { hours = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hours") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        minutes,
                        { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!ok) Text("From ${Format.minutes(least)} to ${OwnTasks.MINUTES.last / 60} hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onPick(total) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
