package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Examples of what an instruction can say, for the box you write one in. */
const val INSTRUCTION_EXAMPLES = "For example: “I can't do anything on Saturday”, “I can work on my train journeys”, " +
    "“this email is just an email”, “this can't be done until Moments Prep is done”, “the due date should be Friday”."

/**
 * The box you write an instruction in, about [about] ([what] says it in words). [initial]: the
 * words of one being written again.
 */
@Composable
fun InstructionDialog(what: String, initial: String = "", onDismiss: () -> Unit, onSend: (String) -> Unit) {
    // Kept through the screen being made again (a turn of the phone), as a long one can be.
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instruction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(what, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                OutlinedTextField(text, { text = it }, label = { Text("In your words") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text(
                    "Claude reads it, then you check what it understood before anything changes. $INSTRUCTION_EXAMPLES",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSend(text) }) { Text("Send to Claude") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/** What an instruction is about, in words. */
fun aboutLine(about: About, now: Long, zone: ZoneId): String = when {
    about.taskId != null -> "About the task “${about.taskTitle ?: about.taskId}”"
    about.eventKey != null -> "About “${about.eventTitle ?: about.eventKey}”" + (about.eventStart?.let { ", " + Format.at(it, now, zone) } ?: "")
    about.day != null -> "About " + (runCatching { LocalDate.parse(about.day).format(DAY) }.getOrNull() ?: about.day)
    else -> "General"
}

/**
 * One instruction as a tile's content: what it's about, your words, and Claude's reading, with
 * what you can do next: apply or discard it, write it again, or take it back.
 */
@Composable
fun InstructionContent(graph: AppGraph, instruction: Instruction, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId) {
    val scope = rememberCoroutineScope()
    var rewriting by rememberSaveable { mutableStateOf(false) }
    Text(aboutLine(instruction.about, now, zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text("“${instruction.text}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 2.dp))
    when (instruction.state) {
        InstructionStatus.Reading -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (instruction.note == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(instruction.note ?: "Claude is reading it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InstructionStatus.Unclear -> Text(
            instruction.note ?: "Claude couldn't turn it into a change",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
        InstructionStatus.Understood, InstructionStatus.Applied -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (instruction.state == InstructionStatus.Applied) "In use:" else "Claude understood:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            instruction.changes.forEach { Text("• " + InstructionRules.describe(it, tasks, zone), style = MaterialTheme.typography.bodyMedium) }
            // Why taking it back was refused, where it was.
            instruction.note?.takeIf { instruction.state == InstructionStatus.Applied }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (instruction.state) {
            InstructionStatus.Understood -> {
                Button(onClick = { scope.launch { graph.instructions.applyInstruction(instruction.id) } }) { Text("Apply") }
                OutlinedButton(onClick = { scope.launch { graph.instructions.deleteInstruction(instruction.id) } }) { Text("Discard") }
            }
            InstructionStatus.Unclear -> {
                Button(onClick = { rewriting = true }) { Text("Write again") }
                OutlinedButton(onClick = { scope.launch { graph.instructions.deleteInstruction(instruction.id) } }) { Text("Discard") }
            }
            InstructionStatus.Reading -> OutlinedButton(onClick = { scope.launch { graph.instructions.deleteInstruction(instruction.id) } }) { Text("Discard") }
            InstructionStatus.Applied -> OutlinedButton(onClick = { scope.launch { graph.instructions.deleteInstruction(instruction.id) } }) { Text("Take back") }
        }
    }
    if (rewriting) {
        InstructionDialog(aboutLine(instruction.about, now, zone), initial = instruction.text, onDismiss = { rewriting = false }) { text ->
            rewriting = false
            scope.launch { graph.instructions.rereadInstruction(instruction.id, text) }
        }
    }
}

/**
 * Your words about [about] said to Claude, in the chat (Q29), which opens: Claude answers
 * there, with cards to apply. One of yours still waiting is answered with them (D-98).
 */
fun sendInstruction(activity: Activity, text: String, about: About) {
    activity.startActivity(AskActivity.intent(activity, about, text))
}

/** [time] as a day, where you are. */
fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
