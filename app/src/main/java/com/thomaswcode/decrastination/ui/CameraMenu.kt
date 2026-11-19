package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.KeptPage
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.data.SlotRef
import com.thomaswcode.decrastination.sources.gmail.GmailThreads
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

/** A task's photo of the work can be added (AI-23): an open one Claude reads, as its own sheet offers it. */
internal fun takesPhoto(task: TaskItem): Boolean = task.isOpen && Enrichments.jobFor(task, GmailThreads::textRead) != null

/**
 * Work's camera button (6.5.0): what the photo's for, as a sheet of three. *New tasks from a photo* (NF-01, as the button did);
 * *Check work with a photo*, now's piece first, today's other written pieces, then the slots ticked *Done* that can still earn
 * ([KeptActions]); *Add a photo to a task*, the tasks whose own sheet offers it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraMenu(
    graph: AppGraph,
    plan: Plan,
    tasks: List<TaskItem>,
    now: Long,
    photo: PhotoCheck,
    onNewTasks: () -> Unit,
    onTask: (String) -> Unit,
    onClose: () -> Unit,
) {
    val log by graph.stores.log.state.collectAsStateWithLifecycle()
    val zone = graph.clock.zone()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val byId = tasks.associateBy { it.id }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ListTile(0, 3, onClick = onNewTasks) {
                Text("New tasks from a photo", style = MaterialTheme.typography.bodyLarge)
                Text("Homework photographed, read into tasks of your own to check", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ListTile(1, 3, onClick = { open = if (open == CHECK) null else CHECK }) {
                Text("Check work with a photo", style = MaterialTheme.typography.bodyLarge, fontWeight = if (open == CHECK) FontWeight.SemiBold else null)
                Text("Written work shown done earns its free time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open == CHECK) {
                val slots = plan.todayBucket?.slots.orEmpty().filter { it.kind == SlotKind.Work }
                fun startOf(piece: Chunk) = slots.firstOrNull { it.chunk?.key == piece.key }?.start
                // Now's piece first, then today's others by their times.
                val pieces = plan.todayBucket?.chunks.orEmpty().filter { it.photoCheckable }
                    .sortedWith(compareBy({ p -> slots.none { it.chunk?.key == p.key && it.start <= now && now < it.end } }, { startOf(it) ?: Long.MAX_VALUE }))
                val kept = log.slots.filter { KeptActions.of(it, byId[it.taskId], plan.today.toString()).photo }
                when {
                    !photo.available -> Hint("Claude can't be asked now: a photo check needs it.")
                    pieces.isEmpty() && kept.isEmpty() -> Hint("No written work today, and no slot ticked Done that can still earn.")
                }
                if (photo.available) {
                    pieces.forEachIndexed { i, piece ->
                        ListTile(i, pieces.size + kept.size, onClick = {
                            onClose()
                            photo.take(piece)
                        }) {
                            Text(piece.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(startOf(piece)?.let { "Today at " + Instant.ofEpochMilli(it).atZone(zone).format(CLOCK) } ?: "Today, no time yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    kept.forEachIndexed { i, record ->
                        ListTile(pieces.size + i, pieces.size + kept.size, onClick = {
                            onClose()
                            Photos.pieceOf(record, byId[record.taskId])?.let { photo.take(it, SlotRef.of(record)) }
                        }) {
                            Text(record.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text("Ticked Done at ${Instant.ofEpochMilli(record.at).atZone(zone).format(CLOCK)}, not earning yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ListTile(2, 3, onClick = { open = if (open == TASK) null else TASK }) {
                Text("Add a photo to a task", style = MaterialTheme.typography.bodyLarge, fontWeight = if (open == TASK) FontWeight.SemiBold else null)
                Text("Kept with it, and Claude reads it again with it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open == TASK) {
                val takes = tasks.filter(::takesPhoto).sortedBy { it.dueAt ?: Long.MAX_VALUE }
                if (takes.isEmpty()) Hint("No open task Claude reads.")
                takes.forEachIndexed { i, task ->
                    ListTile(i, takes.size, onClick = { onTask(task.id) }) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        if (task.photo != null) Text("Has a photo: this one in its place", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

private const val CHECK = "check"
private const val TASK = "task"

/**
 * A photo of [taskId]'s work (AI-23), from Work's camera menu (6.5.0): from the camera or the gallery, kept with it and read with it,
 * as its own sheet's button does. Stays composed till the picture comes back, as its launchers are its own.
 */
@Composable
internal fun TaskPhoto(graph: AppGraph, activity: Activity, taskId: String, say: (String) -> Unit, onClose: () -> Unit) {
    val shot = remember { File(activity.cacheDir, "photos/task.jpg") }
    var choosing by rememberSaveable { mutableStateOf(true) }
    val picture = rememberPicture(activity, shot) { taken ->
        if (taken) {
            graph.scope.launch {
                val jpeg = withContext(Dispatchers.IO) { PhotoChecks.shrink(shot).also { shot.delete() } }
                if (jpeg == null) {
                    say("Couldn't read the photo.")
                } else {
                    graph.photos.attachPhoto(taskId, jpeg)
                    say(if (graph.claudeAskable()) "Photo kept: Claude reads the task again with it." else "Photo kept: it's read with the task once Claude is on.")
                }
            }
        } else {
            shot.delete()
        }
        onClose()
    }
    if (choosing) {
        CameraOrGallery("A photo of the work", "Kept with the task, and Claude reads it again with it.", onCamera = {
            choosing = false
            picture.camera()
        }, onGallery = {
            choosing = false
            picture.gallery()
        }, onDismiss = onClose)
    }
}

/**
 * A check's pages kept with a task (6.5.0), its box unticked: small, in the order they were taken, and till when they're kept at
 * most ([KeptPage.KEPT_MS]); each sent again with the task's next check where its pages leave room.
 */
@Composable
internal fun KeptPages(graph: AppGraph, kept: List<KeptPage>, zone: ZoneId) {
    Text("Pages kept", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val first = Instant.ofEpochMilli(kept.minOf { it.at } + KeptPage.KEPT_MS).atZone(zone).format(DATE)
    Text(
        "${kept.size} page${if (kept.size == 1) "" else "s"} of its checks, sent again with its next; deleted when it's done, or from $first.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(kept, key = { it.hash }) { page ->
            val bitmap by produceState<ImageBitmap?>(null, page.hash) { value = withContext(Dispatchers.IO) { thumbnail(graph.photos.pageFile(page.hash)) } }
            bitmap?.let { Image(it, contentDescription = "A page kept", modifier = Modifier.height(THUMB.dp)) }
                ?: Box(Modifier.size((THUMB * 3 / 4).dp, THUMB.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

/** How tall a kept page's picture is drawn. */
private const val THUMB = 96

/** [file], a page kept, read small: an eighth of it each way is enough for a picture this size. */
private fun thumbnail(file: File): ImageBitmap? = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull()
