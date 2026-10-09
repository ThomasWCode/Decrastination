package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.protect.Totp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Examples of what an instruction can say, for the box you write one in. */
const val INSTRUCTION_EXAMPLES = "For example: “I can't do anything on Saturday”, “I can work on my train journeys”, " +
    "“this email is just an email”, “this can't be done until Statics Prep is done”, “the due date should be Friday”."

/**
 * The box you write an instruction in, about [about] ([what] says it in words). [initial]: the
 * words of one being written again.
 */
@Composable
fun InstructionDialog(what: String, initial: String = "", onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
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

/**
 * A code from your dad's authenticator for what [what] says, seen by him as he types it.
 * [onSubmit] checks it and does it, returning what went wrong, or null.
 */
@Composable
fun ParentCodeDialog(what: String, onClose: () -> Unit, onSubmit: suspend (String) -> String?) {
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Parent code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // What the code will do, in full: a code can't say what it was given for.
                Text(what, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Protection is armed, so a change to a due date needs a code from your dad's authenticator, typed in by him, seeing this.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    code,
                    { code = it.filter(Char::isDigit).take(Totp.DIGITS) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.length == Totp.DIGITS, onClick = {
                scope.launch {
                    val result = onSubmit(code)
                    if (result == null) onClose() else message = result
                }
            }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
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
    var codeFor by remember { mutableStateOf<String?>(null) }
    var rewriting by remember { mutableStateOf(false) }
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
            instruction.changes.forEach { Text("• " + Instructions.describe(it, tasks, zone), style = MaterialTheme.typography.bodyMedium) }
            // Why taking it back was refused, where it was.
            instruction.note?.takeIf { instruction.state == InstructionStatus.Applied }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val needsCode = graph.instructionNeedsCode(instruction)
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (instruction.state) {
            InstructionStatus.Understood -> {
                Button(onClick = { scope.launch { if (!graph.applyInstruction(instruction.id)) codeFor = "apply" } }) {
                    Text(if (needsCode) "Apply with parent code" else "Apply")
                }
                OutlinedButton(onClick = { scope.launch { graph.deleteInstruction(instruction.id) } }) { Text("Discard") }
            }
            InstructionStatus.Unclear -> {
                Button(onClick = { rewriting = true }) { Text("Write again") }
                OutlinedButton(onClick = { scope.launch { graph.deleteInstruction(instruction.id) } }) { Text("Discard") }
            }
            InstructionStatus.Reading -> OutlinedButton(onClick = { scope.launch { graph.deleteInstruction(instruction.id) } }) { Text("Discard") }
            InstructionStatus.Applied -> OutlinedButton(onClick = { scope.launch { if (!graph.deleteInstruction(instruction.id)) codeFor = "delete" } }) {
                Text(if (needsCode) "Take back with parent code" else "Take back")
            }
        }
    }
    codeFor?.let { action ->
        val lines = instruction.changes.joinToString("; ") { Instructions.describe(it, tasks, zone) }
        ParentCodeDialog(
            what = if (action == "apply") "It applies: $lines." else "It takes back: $lines.",
            onClose = { codeFor = null },
        ) { code -> if (action == "apply") graph.applyInstructionWithCode(instruction.id, code) else graph.deleteInstructionWithCode(instruction.id, code) }
    }
    if (rewriting) {
        InstructionDialog(aboutLine(instruction.about, now, zone), initial = instruction.text, onDismiss = { rewriting = false }) { text ->
            rewriting = false
            scope.launch { graph.rereadInstruction(instruction.id, text) }
        }
    }
}

/** Writes an instruction about [about] and opens the instructions page, where Claude's reading shows. */
fun sendInstruction(activity: Activity, graph: AppGraph, text: String, about: About) {
    graph.scope.launch { graph.addInstruction(text, about) }
    activity.startActivity(Intent(activity, InstructionsActivity::class.java))
}

/** [time] as a day, where you are. */
fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
