package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Change
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** A change you can make to a task yourself, with no Claude (IMP-P2-001). */
enum class OwnChange(val label: String) {
    NotATask("Not a task"),
    StartFrom("Can't start before…"),
    DueOn("Due on…"),
}

/**
 * [what] for [task]: a confirmation, or a day to pick. It's kept as an instruction and applied as
 * one is ([AppGraph.addOwnChange]), so a due date needs your dad's code while armed, as an
 * instruction's does; one whose code isn't given isn't kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnChangeDialog(graph: AppGraph, task: TaskItem, what: OwnChange, onClose: () -> Unit) {
    val zone = graph.clock.zone()
    val scope = rememberCoroutineScope()
    // Made, and waiting for a parent code, by its id: kept through the screen being made again.
    var waiting by rememberSaveable { mutableStateOf<String?>(null) }
    val about = About(taskId = task.id, taskTitle = task.title)

    fun make(change: Change, text: String) {
        scope.launch {
            val (id, applied) = graph.addOwnChange(text, about, change)
            if (applied) onClose() else waiting = id
        }
    }

    waiting?.let { id ->
        val instruction = graph.instructions.value.instructions.firstOrNull { it.id == id }
        val lines = instruction?.changes.orEmpty().joinToString("; ") { Instructions.describe(it, mapOf(task.id to task), zone) }
        ParentCodeDialog(
            what = "It applies: $lines.",
            onClose = {
                // Not applied (cancelled): not kept either, so nothing waits unseen in Instructions.
                scope.launch {
                    if (graph.instructions.value.instructions.firstOrNull { it.id == id }?.state == InstructionStatus.Understood) graph.deleteInstruction(id)
                    onClose()
                }
            },
        ) { code -> graph.applyInstructionWithCode(id, code) }
        return
    }

    when (what) {
        OwnChange.NotATask -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Not a task?") },
            text = { Text("“${task.title}” comes off the plan and the list, as an email that's only to read does. It's listed under Not tasks, and taken back in Instructions.") },
            confirmButton = { TextButton(onClick = { make(Change(ChangeType.NotATask, taskId = task.id), "Not a task (set in Tasks)") }) { Text("Not a task") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        OwnChange.StartFrom, OwnChange.DueOn -> {
            val today = Instant.ofEpochMilli(graph.clock.now()).atZone(zone).toLocalDate()
            val initial = when (what) {
                OwnChange.DueOn -> task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.takeIf { it >= today } ?: today
                else -> today.plusDays(1)
            }
            val state = rememberDatePickerState(
                initialSelectedDateMillis = utcMidnight(initial),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= utcMidnight(today)
                },
            )
            DatePickerDialog(
                onDismissRequest = onClose,
                confirmButton = {
                    TextButton(enabled = state.selectedDateMillis != null, onClick = {
                        val day = Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                        val (change, text) = ownChange(task, what, day, zone)
                        make(change, text)
                    }) { Text(if (what == OwnChange.DueOn && graph.settings.value.armed) "Next: parent code" else "Apply") }
                },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            ) {
                DatePicker(
                    state,
                    title = {
                        Text(
                            if (what == OwnChange.DueOn) "Due on" else "Can't be started before",
                            maxLines = 1,
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                        )
                    },
                    showModeToggle = false,
                )
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

/** Midnight UTC of [day], as the date picker keeps its days. */
private fun utcMidnight(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * [what] on [day], as the change it makes and its words: a start at the day's beginning; a due
 * date at the time of day it's due now (a lesson's 08:30), or 23:59 for one with none.
 */
fun ownChange(task: TaskItem, what: OwnChange, day: LocalDate, zone: ZoneId): Pair<Change, String> = when (what) {
    OwnChange.NotATask -> Change(ChangeType.NotATask, taskId = task.id) to "Not a task (set in Tasks)"
    OwnChange.StartFrom -> Change(ChangeType.StartFrom, taskId = task.id, time = day.atStartOfDay(zone).toInstant().toEpochMilli()) to
        "Can't be started before ${day.format(DAY)} (set in Tasks)"
    OwnChange.DueOn -> {
        val time = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(23, 59)
        val at = day.atTime(time).atZone(zone)
        Change(ChangeType.DueBy, taskId = task.id, time = at.toInstant().toEpochMilli()) to "Due ${at.format(DAY_TIME)} (set in Tasks)"
    }
}
