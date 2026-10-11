package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.block.Sessions
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Source
import java.io.File
import kotlinx.coroutines.launch

/**
 * The photo check of written work (Q16) for a screen showing pieces of the plan: one camera for the
 * screen, whichever piece it's for. [available]: Claude is on with its key. [checking]: a photo is
 * being checked, meanwhile no session is started, as both would count the piece.
 */
class PhotoCheck(val available: Boolean, val checking: Boolean, val take: (Chunk) -> Unit)

/** A piece of the plan being photographed: its task, and its step's place and name (or "part 1 of 2"). */
private data class PhotoPiece(val taskId: String, val stepIndex: Int?, val step: String?)

private val PIECE = Saver<PhotoPiece?, ArrayList<String?>>(
    save = { p -> p?.let { arrayListOf(it.taskId, it.stepIndex?.toString(), it.step) } },
    restore = { (taskId, index, step) -> PhotoPiece(taskId!!, index?.toInt(), step) },
)

/**
 * The screen's photo check. The piece being photographed is kept through the screen being made
 * again while the camera is open (a turn of the phone, Android needing the memory), and found again
 * in [plan] when the photo comes back (BUG-P2-011). [say] gives how it went, and for which task.
 */
@Composable
fun rememberPhotoCheck(graph: AppGraph, activity: Activity, plan: Plan, say: (taskId: String, text: String) -> Unit): PhotoCheck {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val checker = remember(settings.aiEnabled, settings.aiKeyActive) { graph.photoChecker() }
    val file = remember { File(activity.cacheDir, "photos/check.jpg").also { it.parentFile?.mkdirs() } }
    val uri = remember { FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file) }
    var photoFor by rememberSaveable(stateSaver = PIECE) { mutableStateOf<PhotoPiece?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val wanted = photoFor
        photoFor = null
        if (!taken || wanted == null || checker == null) {
            // Not to be checked (cancelled): not kept either.
            file.delete()
            return@rememberLauncherForActivityResult
        }
        val piece = plan.chunksOf(wanted.taskId).firstOrNull { it.stepIndex == wanted.stepIndex && it.step == wanted.step }
        if (piece == null) {
            file.delete()
            say(wanted.taskId, "That piece has changed since the photo was taken (done, or planned again), so it wasn't checked.")
            return@rememberLauncherForActivityResult
        }
        say(piece.taskId, "Checking the photo…")
        checking = true
        scope.launch {
            try {
                say(piece.taskId, PhotoChecks.check(activity, graph, file, piece))
            } finally {
                checking = false
            }
        }
    }
    return PhotoCheck(checker != null, checking) { piece ->
        photoFor = PhotoPiece(piece.taskId, piece.stepIndex, piece.step)
        launcher.launch(uri)
    }
}

/**
 * What can be done with [piece], wherever it's shown (the Plan, the block screen): **Open** goes to
 * the task; **Start** runs a focus session on it (**Stop** ends the one running); **Check it's done**
 * asks its source; **Refresh Teams** asks the Teams widget to sync; **Photo check** sends a photo
 * of written work. [say] gives how each went.
 */
@Composable
fun PieceActions(graph: AppGraph, activity: Activity, piece: Chunk, now: Long, photo: PhotoCheck, say: (String) -> Unit) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val runtime by graph.runtime.state.collectAsStateWithLifecycle()
    val session = runtime.session
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            tasks.tasks.firstOrNull { it.id == piece.taskId }?.let { task ->
                scope.launch { TaskOpener.open(activity, task)?.let(say) }
            }
        }) { Text("Open") }
        if (session == null) {
            // Not before it's available (an Anki deck's next cards at 04:00).
            FilledTonalButton(enabled = piece.startable(now) && !photo.checking, onClick = {
                scope.launch { Sessions.start(activity, piece.taskId, piece.label, piece.step, piece.minutes, piece.box, piece.stepIndex) }
            }) { Text("Start ${Format.minutes(minOf(piece.minutes, Focus.MAX_SESSION_MIN))}") }
        } else {
            OutlinedButton(onClick = { scope.launch { Sessions.end(activity, early = true) } }) { Text("Stop session") }
        }
        OutlinedButton(onClick = {
            val task = tasks.tasks.firstOrNull { it.id == piece.taskId } ?: return@OutlinedButton
            say("Asking ${task.source.label}…")
            scope.launch {
                graph.syncer.sync(setOf(task.source))
                val still = graph.tasks.value.tasks.firstOrNull { it.id == task.id }?.isOpen == true
                say(
                    when {
                        !still -> "Done: ${task.source.label} agrees."
                        task.source == Source.Teams -> "Teams still lists it as not handed in. If you've handed it in, tap Refresh Teams."
                        else -> "${task.source.label} still lists it as open."
                    },
                )
            }
        }) { Text("Check it's done") }
        if (piece.source == Source.Teams) {
            OutlinedButton(onClick = {
                scope.launch { say(graph.requestTeamsSync() ?: "The Teams widget is syncing Teams") }
            }) { Text("Refresh Teams") }
        }
        // Written work: a photo instead of the timer, once Claude is on (Q16). Not during a
        // session: the two would count the same piece twice.
        if (photo.available && session == null && piece.photoCheckable) {
            OutlinedButton(enabled = !photo.checking, onClick = { photo.take(piece) }) { Text("Photo check") }
        }
    }
}
