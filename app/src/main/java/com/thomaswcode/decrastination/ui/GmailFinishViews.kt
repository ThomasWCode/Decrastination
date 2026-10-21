package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.SettingsLimits
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.sync.GmailFinishes
import kotlinx.coroutines.launch

/**
 * What a starred email's finish here is (Stage 11), under its sheet's Do: waiting for your OK, going (with
 * Undo), made (with Undo while its receipt stands), given up (what did and didn't happen, and *It's sorted*),
 * or taken back. Nothing for one never finished here.
 */
@Composable
internal fun GmailFinishLine(graph: AppGraph, task: TaskItem) {
    val journal by graph.gmailFinishes.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val last = GmailWrites.latest(journal.writes, task.id) ?: return
    val (line, button) = when (GmailWrites.state(journal.writes, task.id)) {
        GmailWrites.State.None -> return
        GmailWrites.State.Awaiting -> "Done here: Gmail waits for your OK" to null
        GmailWrites.State.Going -> "Done: unstarring it in Gmail" to ("Undo" to suspend { graph.gmailFinishes.unfinish(task.id) })
        GmailWrites.State.Made -> "Unstarred and archived in Gmail" to ("Undo" to suspend { graph.gmailFinishes.unfinish(task.id) })
        GmailWrites.State.GaveUp -> GmailWrites.failure(last) to ("It's sorted" to suspend { graph.gmailFinishes.sorted(task.id) })
        GmailWrites.State.TakenBack -> (if (GmailWrites.pending(last)) "Taken back: starring it again in Gmail" else "Taken back: starred again in Gmail") to null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        button?.let { (label, act) -> TextButton(onClick = { scope.launch { act() } }) { Text(label) } }
    }
}

/** What finishing an email by your word said, for the sheet's line. */
internal fun finishSaid(finish: GmailFinishes.Finish): String? = when (finish) {
    GmailFinishes.Finish.Going, GmailFinishes.Finish.Pending, GmailFinishes.Finish.Awaiting -> null
    GmailFinishes.Finish.Off -> "Finishing emails in Gmail is off (Settings → Sources)"
    GmailFinishes.Finish.No -> "It isn't open to finish"
}

/**
 * The very first finish of an email asks before Gmail is touched (Q18's sheet), on the next screen of the app to
 * open: *Carry on* sends it, and every finish after it goes with the grace alone; *Not this time* drops it and asks
 * again next time; *Turn this off* drops it and turns finishing off. Nothing while none waits.
 */
@Composable
internal fun GmailOkSheet(graph: AppGraph) {
    val journal by graph.gmailFinishes.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val waiting = journal.writes.filter(GmailWrites::awaiting)
    if (waiting.isEmpty()) return
    val titles = waiting.joinToString(", ") { "“${it.title}”" }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Finish it in Gmail too?") },
        text = { Text("Finishing $titles here will unstar and archive ${if (waiting.size == 1) "its conversation" else "their conversations"} in Gmail, a minute from now (Undo on its sheet till then). Nothing's deleted or marked read. Carry on, and every finish after this goes the same way.") },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    graph.protection.changeSettings { it.copy(hintsSeen = (it.hintsSeen + GmailWrites.OK_HINT).distinct().takeLast(SettingsLimits.MAX_HINTS)) }
                    graph.gmailFinishes.confirm()
                }
            }) { Text("Carry on") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        graph.gmailFinishes.refuse()
                        graph.protection.changeSettings { it.copy(gmailFinishes = false) }
                    }
                }) { Text("Turn this off") }
                TextButton(onClick = { scope.launch { graph.gmailFinishes.refuse() } }) { Text("Not this time") }
            }
        },
    )
}
