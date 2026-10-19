package com.thomaswcode.decrastination.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.PhotoTasks
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * What a photo of homework was read into (NF-01): each task to check in the form and add, the
 * photo kept with it; or the photo for a task already listed instead (AI-23). [jpeg] the photo,
 * kept till this closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTasksSheet(graph: AppGraph, reading: PhotoTasks.Reading, jpeg: ByteArray, onClose: () -> Unit) {
    // The task being checked in the form, by its place; those added; the list of tasks to attach to.
    var adding by rememberSaveable { mutableStateOf<Int?>(null) }
    var added by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var attaching by rememberSaveable { mutableStateOf(false) }
    val now = graph.clock.now()
    val zone = graph.clock.zone()
    adding?.let { i ->
        val task = reading.tasks.getOrNull(i)
        if (task != null) {
            val id = rememberSaveable(i) { UUID.randomUUID().toString() }
            OwnTaskForm(graph, editing = null, initial = task, onSave = { made ->
                graph.ownTasks.saveOwnTask(id, made).also { saved ->
                    if (saved) {
                        graph.photos.attachPhoto(TaskItem.id(Source.Own, id), jpeg)
                        added = added + i
                    }
                }
            }) { adding = null }
            return
        }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("From your photo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (attaching) {
                Text("The photo goes with a task listed, and Claude reads the task again with it.", style = MaterialTheme.typography.bodyMedium)
                val open = graph.tasks.value.tasks.filter { it.isOpen && !it.hidden && com.thomaswcode.decrastination.core.Enrichments.jobFor(it) != null }
                if (open.isEmpty()) Text("None of the listed tasks is one Claude reads.", style = MaterialTheme.typography.bodyMedium)
                open.sortedBy { it.dueAt ?: Long.MAX_VALUE }.forEach { t ->
                    TextButton(onClick = {
                        graph.scope.launch { graph.photos.attachPhoto(t.id, jpeg) }
                        onClose()
                    }) { Text("${t.title} · ${t.source.label}") }
                }
                TextButton(onClick = { attaching = false }) { Text("Back") }
                return@Column
            }
            reading.question?.let { Text("Claude asks: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (reading.tasks.isEmpty()) Text("No homework could be read from it.", style = MaterialTheme.typography.bodyMedium)
            reading.tasks.forEachIndexed { i, task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(task.className, task.dueAt?.let { "due " + Format.at(it, now, zone) }, Format.minutes(task.minutes)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i in added) Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { adding = i }) { Text("Check and add") }
                }
            }
            if (reading.leftOut.isNotEmpty()) Text("Left out: " + reading.leftOut.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { attaching = true }) { Text("Attach to an existing task instead") }
        }
    }
}
