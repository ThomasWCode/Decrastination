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
import androidx.compose.runtime.LaunchedEffect
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
import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The photo check of written work (Q16) for a screen showing pieces of the plan: one camera for the
 * screen, whichever piece it's for. [available]: Claude is on with its key. [checking]: a photo is
 * being checked, meanwhile no session is started, as both would count the piece.
 */
class PhotoCheck(val available: Boolean, val checking: Boolean, val take: (Chunk) -> Unit)

/**
 * A piece of the plan being photographed: its task, its step's place and name (or "part 1 of 2"),
 * and which of the step's sittings it is ([after], as [com.thomaswcode.decrastination.core.Sitting.after]);
 * the pages taken so far (AI-17), and when the first was: a slot of its key kept before then isn't
 * the one the photos show.
 */
private data class PhotoPiece(val taskId: String, val stepIndex: Int?, val step: String?, val pages: Int = 0, val takenAt: Long? = null, val after: Int? = null)

private val PIECE = Saver<PhotoPiece?, ArrayList<String?>>(
    save = { p -> p?.let { arrayListOf(it.taskId, it.stepIndex?.toString(), it.step, it.pages.toString(), it.takenAt?.toString(), it.after?.toString()) } },
    restore = { saved -> PhotoPiece(saved[0]!!, saved[1]?.toInt(), saved[2], saved.getOrNull(3)?.toInt() ?: 0, saved.getOrNull(4)?.toLongOrNull(), saved.getOrNull(5)?.toIntOrNull()) },
)

/**
 * The screen's photo check: a page, then "Add another page?", up to [PhotoChecker.MAX_PAGES] in one
 * check (AI-17). The piece being photographed, with its pages so far, is kept through the screen
 * being made again while the camera is open (a turn of the phone, Android needing the memory), and
 * found again in [plan] when the photos are checked (BUG-P2-011). [say] gives how it went, and for
 * which task.
 */
@Composable
fun rememberPhotoCheck(graph: AppGraph, activity: Activity, plan: Plan, say: (taskId: String, text: String) -> Unit): PhotoCheck {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val checkable = remember(settings.aiEnabled, settings.aiKeyActive) { graph.gateway.claudeKey() != null }
    val dir = remember { File(activity.cacheDir, "photos").also { it.mkdirs() } }
    fun page(n: Int) = File(dir, "check-$n.jpg")
    fun uriOf(file: File) = FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file)
    var photoFor by rememberSaveable(stateSaver = PIECE) { mutableStateOf<PhotoPiece?>(null) }
    // Asked after a page: another, or check those taken.
    var asking by rememberSaveable { mutableStateOf(false) }
    // App-wide, not this screen's: a check runs on when the screen goes (a tab changed), and its
    // outcome is shown on whichever screen is there, or the next.
    val running by PhotoChecks.running.collectAsStateWithLifecycle()
    val unseen by PhotoChecks.unseen.collectAsStateWithLifecycle()
    LaunchedEffect(unseen) {
        val (taskId, text) = unseen ?: return@LaunchedEffect
        PhotoChecks.unseen.value = null
        say(taskId, text)
    }
    /** The pages taken checked together, the piece found again in the plan as it is now. */
    fun check(wanted: PhotoPiece) {
        photoFor = null
        val pages = (1..wanted.pages).map(::page)
        val piece = plan.chunksOf(wanted.taskId).firstOrNull { it.stepIndex == wanted.stepIndex && it.step == wanted.step && it.sitting?.after == wanted.after }
        if (piece == null || !checkable) {
            pages.forEach { it.delete() }
            if (piece == null) say(wanted.taskId, "That piece has changed since the photo was taken (done, or planned again), so it wasn't checked.")
            return
        }
        say(piece.taskId, if (pages.size == 1) "Checking the photo…" else "Checking the ${pages.size} pages…")
        PhotoChecks.running.value = piece.taskId
        graph.scope.launch {
            try {
                PhotoChecks.unseen.value = piece.taskId to graph.photos.check(pages, piece, takenAt = wanted.takenAt ?: graph.clock.now())
            } finally {
                PhotoChecks.running.value = null
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val wanted = photoFor ?: return@rememberLauncherForActivityResult
        when {
            // Not to be checked: with no page yet, nothing to keep.
            !checkable || (!taken && wanted.pages == 0) -> {
                photoFor = null
                (1..PhotoChecker.MAX_PAGES).forEach { page(it).delete() }
            }
            // That page not taken: those before it are checked.
            !taken -> check(wanted)
            else -> {
                val more = wanted.copy(pages = wanted.pages + 1, takenAt = wanted.takenAt ?: graph.clock.now())
                photoFor = more
                if (more.pages >= PhotoChecker.MAX_PAGES) check(more) else asking = true
            }
        }
    }
    if (asking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                asking = false
                photoFor?.let(::check)
            },
            title = { Text("Add another page?") },
            text = { Text("Up to ${PhotoChecker.MAX_PAGES} pages go in one check.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = false
                    photoFor?.let { launcher.launch(uriOf(page(it.pages + 1))) }
                }) { Text("Add a page") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    asking = false
                    photoFor?.let(::check)
                }) { Text("Check it now") }
            },
        )
    }
    return PhotoCheck(checkable, running != null) { piece ->
        (1..PhotoChecker.MAX_PAGES).forEach { page(it).delete() }
        photoFor = PhotoPiece(piece.taskId, piece.stepIndex, piece.step, after = piece.sitting?.after)
        launcher.launch(uriOf(page(1)))
    }
}

/**
 * [piece] done (LR-09), seen at [now]: its slot kept, earning its free time if it's short (a longer
 * one's photo earns it, or its source saying it's done, BL-08). What to say of it.
 */
internal suspend fun keepPiece(graph: AppGraph, piece: Chunk, now: Long): String {
    val by = graph.focus.earnsBy(piece)
    val kept = graph.focus.keepSlot(piece.key, seenAt = now)
    val free = Format.minutes(Credit.forSlot(piece.minutes, graph.settings.value.workMinPerFreeMin).roundToInt().coerceAtLeast(1))
    val said = when {
        !kept -> return "That piece has changed since (done, or planned again)."
        by == EarnsBy.Tick -> "Done: $free free."
        by == EarnsBy.Photo -> "Done. A photo check of it earns its $free free."
        by == EarnsBy.Source -> "Done. Its $free free comes once ${piece.source.label} says it's done."
        else -> "Done. It earns no free time: a piece this long earns with a photo check, and this isn't written work."
    }
    return listOfNotNull(said, stillPlanned(graph, piece.taskId)).joinToString(" ")
}

/**
 * What's said once a Done leaves all of [taskId]'s work done but its source's word: its last few
 * minutes stay planned till the source says it's done (planner.md), so the piece shown again isn't
 * taken for a Done that didn't take. Null for any other.
 */
internal fun stillPlanned(graph: AppGraph, taskId: String): String? {
    // As the plan takes it: your instructions' values over it, and linked work credited to it.
    val task = graph.planning.asPlanned().firstOrNull { it.id == taskId && it.isOpen } ?: return null
    if (!worked(task, graph.runtime.value.calibration.multiplierFor(task))) return null
    val next = graph.planning.plan().ordered.firstOrNull { it.taskId == taskId } ?: return null
    return "It stays planned, ${Format.minutes(next.minutes)}, until ${task.source.label} says it's done."
}

/**
 * Whether all of [task]'s work is done as the planner sees it: every step ticked, or its minutes worked,
 * what's left rounding to none as the planner rounds it ([Planner.remaining]).
 */
internal fun worked(task: TaskItem, multiplier: Double): Boolean =
    if (task.subSteps.isNotEmpty()) task.subSteps.all { it.done } else Planner.remaining(task, multiplier).roundToInt() <= 0

/**
 * What can be done with [piece], wherever it's shown (the Plan, the block screen): **Open** goes to
 * the task; **Done** keeps its slot (LR-09); **Check it's done** asks its source (**All of it done**,
 * for one of your own, is your say); **Refresh Teams** asks the Teams widget to sync; **Photo check** sends a photo
 * of written work. [say] gives how each went.
 */
@Composable
fun PieceActions(graph: AppGraph, activity: Activity, piece: Chunk, now: Long, photo: PhotoCheck, say: (String) -> Unit) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            tasks.tasks.firstOrNull { it.id == piece.taskId }?.let { task ->
                scope.launch { TaskOpener.open(activity, task)?.let(say) }
            }
        }) { Text("Open") }
        FilledTonalButton(onClick = { scope.launch { say(keepPiece(graph, piece, now)) } }) { Text("Done") }
        if (piece.source == Source.Own) {
            // Yours: nothing to ask, so all of it done is your say (2.0.0), earning no free time.
            OutlinedButton(onClick = {
                scope.launch {
                    graph.ownTasks.finishOwnTask(piece.taskId)
                    say("All of it done. It earns no free time; its pieces earned theirs.")
                }
            }) { Text("All of it done") }
        } else OutlinedButton(onClick = {
            val task = tasks.tasks.firstOrNull { it.id == piece.taskId } ?: return@OutlinedButton
            say("Asking ${task.source.label}…")
            scope.launch {
                graph.sync.sync(setOf(task.source))
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
                scope.launch { say(graph.sync.requestTeamsSync() ?: "The Teams widget is syncing Teams") }
            }) { Text("Refresh Teams") }
        }
        // Written work: a photo shows it done, once Claude is on (Q16; BL-08: a longer piece earns by it).
        if (photo.available && piece.photoCheckable) {
            OutlinedButton(enabled = !photo.checking, onClick = { photo.take(piece) }) { Text("Photo check") }
        }
    }
}
