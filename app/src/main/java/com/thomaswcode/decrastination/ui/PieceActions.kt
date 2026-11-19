package com.thomaswcode.decrastination.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.block.Credit
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.block.TimerAlarms
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.EarnsBy
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.data.SlotRef
import com.thomaswcode.decrastination.data.SyncState
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.sync.protocol.FinishOwnTask
import com.thomaswcode.decrastination.sync.protocol.KeepSlot
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photo check of written work (Q16) for a screen showing pieces of the plan: one camera for the
 * screen, whichever piece it's for. [available]: Claude is on with its key. [checking]: a photo is
 * being checked, meanwhile no session is started, as both would count the piece.
 */
class PhotoCheck(val available: Boolean, val checking: Boolean, private val start: (Chunk, SlotRef?) -> Unit) {
    /** [piece] checked with photos of its work; with [record], the kept slot it stands for, from that slot's sheet (6.5.0). */
    fun take(piece: Chunk, record: SlotRef? = null) = start(piece, record)
}

/**
 * A piece of the plan being photographed: its task, its step's place and name (or "part 1 of 2"),
 * and which of the step's sittings it is ([after], as [com.thomaswcode.decrastination.core.Sitting.after]);
 * the pages taken so far (AI-17), and when the first was: a slot of its key kept before then isn't
 * the one the photos show. [keep]: its box unticked, its pages kept with the task (6.5.0); [record], a kept slot's check's slot.
 */
private data class PhotoPiece(
    val taskId: String,
    val stepIndex: Int?,
    val step: String?,
    val pages: Int = 0,
    val takenAt: Long? = null,
    val after: Int? = null,
    val keep: Boolean = false,
    val record: SlotRef? = null,
)

private val PIECE = Saver<PhotoPiece?, ArrayList<String?>>(
    save = { p ->
        p?.let {
            arrayListOf(it.taskId, it.stepIndex?.toString(), it.step, it.pages.toString(), it.takenAt?.toString(), it.after?.toString(), it.keep.toString(), it.record?.key, it.record?.start?.toString(), it.record?.at?.toString())
        }
    },
    restore = { saved ->
        val record = saved.getOrNull(7)?.let { key -> SlotRef(key, saved[8]!!.toLong(), saved[9]!!.toLong()) }
        PhotoPiece(saved[0]!!, saved[1]?.toInt(), saved[2], saved.getOrNull(3)?.toInt() ?: 0, saved.getOrNull(4)?.toLongOrNull(), saved.getOrNull(5)?.toIntOrNull(), saved.getOrNull(6) == "true", record)
    },
)

/**
 * The screen's photo check: *Camera* or *Gallery* and the tick box first (6.5.0), then a page, then "Add another page?", up to
 * [PhotoChecker.MAX_PAGES] in one check (AI-17), the last one taken again where it came out wrong (6.4.0), more from either, as many
 * as are left from the gallery at once. The piece being photographed, with its pages so far, is kept through the screen
 * being made again while the camera is open (a turn of the phone, Android needing the memory), and
 * found again in [plan] when the photos are checked (BUG-P2-011), or a kept slot's in the record. [say] gives how it went, and for
 * which task.
 */
@Composable
fun rememberPhotoCheck(graph: AppGraph, activity: Activity, plan: Plan, say: (taskId: String, text: String) -> Unit): PhotoCheck {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val checkable = remember(settings.aiEnabled, settings.aiKeyActive) { graph.claudeAskable() }
    val dir = remember { File(activity.cacheDir, "photos").also { it.mkdirs() } }
    fun page(n: Int) = File(dir, "check-$n.jpg")
    // A page taken again goes here first, so one not taken leaves the page before as it was.
    val again = File(dir, "check-again.jpg")
    fun uriOf(file: File) = FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file)
    var photoFor by rememberSaveable(stateSaver = PIECE) { mutableStateOf<PhotoPiece?>(null) }
    // Asked first: the camera or the gallery, and the tick box.
    var choosing by rememberSaveable { mutableStateOf(false) }
    // Asked after a page: another, the last again, or check those taken.
    var asking by rememberSaveable { mutableStateOf(false) }
    // The camera's open for the last page again.
    var retaking by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // App-wide, not this screen's: a check runs on when the screen goes (a tab changed), and its
    // outcome is shown on whichever screen is there, or the next.
    val running by PhotoChecks.running.collectAsStateWithLifecycle()
    val unseen by PhotoChecks.unseen.collectAsStateWithLifecycle()
    LaunchedEffect(unseen) {
        val (taskId, text) = unseen ?: return@LaunchedEffect
        PhotoChecks.unseen.value = null
        say(taskId, text)
    }
    /**
     * The pages taken checked together, the piece found again in the plan as it is now; a kept slot's, its slot in the record, the
     * check told its label and minutes with its task, as the piece left the plan when it was kept.
     */
    fun check(wanted: PhotoPiece) {
        photoFor = null
        val pages = (1..wanted.pages).map(::page)
        val record = wanted.record
        val piece = if (record != null) {
            graph.stores.log.value.slots.lastOrNull { record.names(it) }?.let { slot -> Photos.pieceOf(slot, graph.tasks.value.tasks.firstOrNull { it.id == wanted.taskId }) }
        } else {
            plan.chunksOf(wanted.taskId).firstOrNull { it.stepIndex == wanted.stepIndex && it.step == wanted.step && it.sitting?.after == wanted.after }
        }
        if (piece == null || !checkable) {
            pages.forEach { it.delete() }
            if (piece == null) say(wanted.taskId, if (record != null) Photos.SLOT_GONE else Photos.PIECE_CHANGED)
            return
        }
        say(piece.taskId, if (pages.size == 1) "Checking the photo…" else "Checking the ${pages.size} pages…")
        PhotoChecks.running.value = piece.taskId
        graph.scope.launch {
            try {
                PhotoChecks.unseen.value = piece.taskId to graph.photos.check(pages, piece, takenAt = wanted.takenAt ?: graph.clock.now(), keep = wanted.keep, record = record)
            } finally {
                PhotoChecks.running.value = null
            }
        }
    }
    /** [wanted] with [added] more pages: checked at the most a check takes, else asked again. */
    fun added(wanted: PhotoPiece, added: Int) {
        val more = wanted.copy(pages = wanted.pages + added, takenAt = wanted.takenAt ?: graph.clock.now())
        photoFor = more
        if (more.pages >= PhotoChecker.MAX_PAGES) check(more) else asking = true
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val wanted = photoFor ?: return@rememberLauncherForActivityResult
        if (retaking) {
            // In the last page's place, or that page as it was; then asked again.
            retaking = false
            if (taken && again.length() > 0) again.copyTo(page(wanted.pages), overwrite = true)
            again.delete()
            if (checkable) asking = true else photoFor = null
            return@rememberLauncherForActivityResult
        }
        when {
            // Not to be checked: with no page yet, nothing to keep.
            !checkable || (!taken && wanted.pages == 0) -> {
                photoFor = null
                (1..PhotoChecker.MAX_PAGES).forEach { page(it).delete() }
            }
            // That page not taken: those before it are checked.
            !taken -> check(wanted)
            else -> added(wanted, 1)
        }
    }
    // As many pictures as are left of a check's pages, picked at once (6.5.0), each copied in as a page.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoChecker.MAX_PAGES)) { picked ->
        val wanted = photoFor ?: return@rememberLauncherForActivityResult
        if (picked.isEmpty()) {
            if (wanted.pages == 0) photoFor = null else asking = true
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val room = picked.take(PhotoChecker.MAX_PAGES - wanted.pages)
            val copied = withContext(Dispatchers.IO) { room.withIndex().count { (i, uri) -> copyPicked(activity, uri, page(wanted.pages + 1 + i)) } }
            if (copied < room.size) say(wanted.taskId, "A picture couldn't be read: pick it again, or take it with the camera.")
            else if (picked.size > room.size) say(wanted.taskId, "Only ${room.size} more fit in one check.")
            // Copied in order: one that couldn't be read leaves the rest out, as a page missing could be the one that isn't done.
            val good = (0 until room.size).takeWhile { page(wanted.pages + 1 + it).length() > 0 }.size
            if (good == 0 && wanted.pages == 0) photoFor = null else added(wanted, good)
        }
    }
    fun camera() = photoFor?.let { launcher.launch(uriOf(page(it.pages + 1))) }
    fun gallery() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    if (choosing) {
        CameraOrGallery(
            "Check with a photo",
            "Up to ${PhotoChecker.MAX_PAGES} pages go in one check.",
            onCamera = {
                choosing = false
                camera()
            },
            onGallery = {
                choosing = false
                gallery()
            },
            onDismiss = {
                choosing = false
                photoFor = null
            },
        ) { DeleteAfterUse(photoFor?.keep != true) { ticked -> photoFor = photoFor?.copy(keep = !ticked) } }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = {
                asking = false
                photoFor?.let(::check)
            },
            title = { Text("Add another page?") },
            text = {
                Column {
                    Text("Page ${photoFor?.pages ?: 1} taken. Up to ${PhotoChecker.MAX_PAGES} pages go in one check.")
                    DeleteAfterUse(photoFor?.keep != true) { ticked -> photoFor = photoFor?.copy(keep = !ticked) }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        asking = false
                        gallery()
                    }) { Text("Gallery") }
                    TextButton(onClick = {
                        asking = false
                        camera()
                    }) { Text("Camera") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        asking = false
                        retaking = true
                        again.delete()
                        launcher.launch(uriOf(again))
                    }) { Text("Retake") }
                    TextButton(onClick = {
                        asking = false
                        photoFor?.let(::check)
                    }) { Text("Check it now") }
                }
            },
        )
    }
    return PhotoCheck(checkable, running != null) { piece, record ->
        (1..PhotoChecker.MAX_PAGES).forEach { page(it).delete() }
        again.delete()
        // The box ticked each time it's shown.
        photoFor = PhotoPiece(piece.taskId, piece.stepIndex, piece.step, after = piece.sitting?.after, record = record)
        choosing = true
    }
}

/** [piece] done (LR-09), seen at [now]. What to say of it. */
internal suspend fun keepPiece(graph: AppGraph, piece: Chunk, now: Long): String = graph.pieceSays.kept(piece, now) { graph.act(it) }

/** What's said once a Done leaves all of [taskId]'s work done but its source's word ([PieceSays.stillPlanned]). */
internal fun stillPlanned(graph: AppGraph, taskId: String): String? = graph.pieceSays.stillPlanned(taskId)

/**
 * The clock [timer]'s time is told by on screen: read afresh at each of its whole minutes from its start, as its
 * minutes left turn then, rather than the screen's own minute, which can be from before the Start.
 */
@Composable
internal fun rememberTimerNow(graph: AppGraph, timer: PieceTimer?): Long {
    val now by produceState(graph.clock.now(), timer) {
        while (true) {
            value = graph.clock.now()
            val t = timer ?: break
            delay(60_000L - Math.floorMod(value - t.startedAt, 60_000L))
        }
    }
    return now
}

/** All of your own [taskId] done: nothing to ask, so it's your say, earning no free time. [say] gives how it went. */
internal suspend fun allDone(graph: AppGraph, taskId: String, say: (String) -> Unit) {
    graph.act(FinishOwnTask(taskId))
    say("All of it done. It earns no free time; its pieces earned theirs.")
}

/** [task] asked of its source (a sync of that source alone). [say] gives that it's asking, then what it says. */
internal suspend fun checkDone(graph: AppGraph, task: TaskItem, say: (String) -> Unit) {
    // The server's to read once the phone sends commands (D-133), every quarter hour: nothing's read here to say of it.
    if (graph.stores.sync.value.mode == SyncState.COMMANDS && task.source in Source.SERVERS) {
        say("The server reads ${task.source.label} itself, every quarter hour: it's checked then.")
        return
    }
    say("Asking ${task.source.label}…")
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

/**
 * What can be done with [piece], wherever it's shown (Day, the block screen): **Open** goes to
 * the task; **Start** runs a timer on it, and **Stop** stops the one on it, its time over the buttons
 * (D-112); **Done** keeps its slot (LR-09); **Check it's done** asks its source (**All of it done**,
 * for one of your own, is your say); **Refresh Teams** asks the Teams widget to sync; **Photo check** sends a photo
 * of written work. [say] gives how each went.
 */
@Composable
fun PieceActions(graph: AppGraph, activity: Activity, piece: Chunk, now: Long, photo: PhotoCheck, say: (String) -> Unit) {
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val session by graph.stores.session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The timer running on this piece, if it's this one's.
    val timer = session.timer?.takeIf { it.running && it.key == piece.key }
    val timerNow = rememberTimerNow(graph, timer)
    timer?.let { Text(timerText(it, timerNow), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            tasks.tasks.firstOrNull { it.id == piece.taskId }?.let { task ->
                scope.launch { TaskOpener.open(activity, task)?.let(say) }
            }
        }) { Text("Open") }
        if (timer != null) {
            FilledTonalButton(onClick = { scope.launch { say(TimerAlarms.stop(activity)) } }) { Text("Stop") }
        } else {
            OutlinedButton(onClick = { scope.launch { say(TimerAlarms.start(activity, piece.taskId, piece.stepIndex, piece.key)) } }) { Text("Start") }
        }
        FilledTonalButton(onClick = { scope.launch { say(keepPiece(graph, piece, now)) } }) { Text("Done") }
        if (piece.source == Source.Own) {
            // Yours: nothing to ask, so all of it done is your say, earning no free time.
            OutlinedButton(onClick = { scope.launch { allDone(graph, piece.taskId, say) } }) { Text("All of it done") }
        } else OutlinedButton(onClick = {
            val task = tasks.tasks.firstOrNull { it.id == piece.taskId } ?: return@OutlinedButton
            scope.launch { checkDone(graph, task, say) }
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
