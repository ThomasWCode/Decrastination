package com.thomaswcode.decrastination.block

import android.util.Log
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.ChatAction
import com.thomaswcode.decrastination.core.ChatActs
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.TaskPhoto
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.enrich.Called
import com.thomaswcode.decrastination.enrich.ModelCalls
import com.thomaswcode.decrastination.enrich.ModelEffort
import com.thomaswcode.decrastination.enrich.ModelGateway
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.PhotoChecker
import com.thomaswcode.decrastination.enrich.PhotoTaskReader
import com.thomaswcode.decrastination.enrich.PhotoTasks
import com.thomaswcode.decrastination.enrich.Profile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photos (docs/plan_v4): a photo of a task's work
 * kept with it and read with it (AI-23, [attachPhoto]); a photo of homework read into tasks of your own
 * (NF-01, [readPhotoOf]); the photos no task holds let go ([sweepPhotos]); and a photo of written work
 * checked, its piece ticked off if it's done (AI-16, [check]). Built like [Focus]: its stores, the
 * clock, the gateway, the focus, its folder and small callbacks.
 */
class Photos(
    private val tasks: JsonStore<TaskState>,
    private val settings: JsonStore<Settings>,
    /** The chat, read for a deleted task its Undo can still bring back, photo and all. */
    private val chat: JsonStore<ChatState>,
    private val clock: WallClock,
    private val scope: CoroutineScope,
    private val gateway: ModelGateway,
    private val focus: Focus,
    /** Where tasks' photos are kept (AI-23): the app's own files, one a task. */
    private val photos: File,
    /** Who the student is, as each call sends it (AI-05). */
    private val profile: () -> Profile.Block,
    /** [taskId] read again with its photo, where it's one the model reads ([com.thomaswcode.decrastination.enrich.Readings.enrichNow]). */
    private val read: suspend (taskId: String) -> Unit,
) {
    /**
     * [jpeg], a photo of [taskId]'s work (AI-23), kept with it (in place of one before) and read
     * with it: its reading redone now, where it's one the model reads.
     */
    suspend fun attachPhoto(taskId: String, jpeg: ByteArray) {
        val file = File(photos, taskId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
        withContext(Dispatchers.IO) {
            photos.mkdirs()
            file.writeBytes(jpeg)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).take(8).joinToString("") { "%02x".format(it) }
        tasks.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(photo = TaskPhoto(file.path, hash, clock.now())) else it }) }
        scope.launch { runCatching { read(taskId) }.onFailure { Log.w(AppGraph.TAG, "Reading a task with its photo failed", it) } }
    }

    /** [jpeg] read into tasks of your own to check (NF-01): the reading, or why there's none. */
    suspend fun readPhotoTasks(jpeg: ByteArray): Pair<PhotoTasks.Reading?, String?> {
        val reader = gateway.client(ModelEffort.Job.PhotoToTask)?.let { PhotoTaskReader(it, profile = profile()) } ?: return null to "Claude is off: add it with New task."
        val now = clock.now()
        return when (val called = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.PhotoToTask, "a photo of homework") { r: PhotoTaskReader.Result -> r.usage to ModelCalls.outcome(r.refused, r.reading == null) }, cost = { r: PhotoTaskReader.Result -> r.costUsd to r.refused }) { reader.read(jpeg, now, clock.zone()) }) {
            is Called.Held -> null to "Claude can't be asked now: add it with New task."
            is Called.Failed -> null to "The photo couldn't be read (${called.error.message ?: "no connection"})."
            is Called.Answered -> called.value.reading?.let { it to null } ?: (null to if (called.value.refused) "Claude declined to read it." else "Claude couldn't read the photo: try a clearer one.")
        }
    }

    /**
     * A photo of homework read into tasks (NF-01): held here, not by a screen, so turning the phone or
     * leaving Work while the paid call runs loses nothing. [busy] while it's read; then what it read,
     * with the photo for the tasks made from it, or why not.
     */
    class PhotoReading(val busy: Boolean, val reading: PhotoTasks.Reading? = null, val jpeg: ByteArray? = null, val why: String? = null)

    /** The photo of homework being read, or read and not yet seen through (NF-01). */
    val photoReading = MutableStateFlow<PhotoReading?>(null)

    /** [file], a photo of homework just taken, read into tasks in the app's scope (NF-01), [photoReading] saying how it goes; the file let go. */
    fun readPhotoOf(file: File) {
        if (photoReading.value?.busy == true) return
        photoReading.value = PhotoReading(busy = true)
        scope.launch {
            val done = runCatching {
                val jpeg = withContext(Dispatchers.IO) { PhotoChecks.shrink(file) }
                if (jpeg == null) {
                    PhotoReading(busy = false, why = "Couldn't read the photo.")
                } else {
                    val (got, why) = readPhotoTasks(jpeg)
                    PhotoReading(busy = false, reading = got, jpeg = jpeg.takeIf { got != null }, why = why)
                }
            }.getOrElse { PhotoReading(busy = false, why = "The photo couldn't be read (${it.message ?: "no connection"}).") }
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            photoReading.value = done
        }
    }

    /** The photos no task holds any more let go (AI-23): a task done and gone takes its photo with it. */
    fun sweepPhotos() {
        // A task the chat deleted keeps its photo while its Undo can bring it back: no other act's, nor one made again since.
        val messages = chat.value.messages
        val undoable = messages.flatMap { m ->
            m.acts.filter { it.action?.type == ChatAction.Type.Delete && ChatActs.canUndo(it, tasks.value.tasks, settings.value, messages) }
                .mapNotNull { it.was?.photo?.path }
        }
        val held = tasks.value.tasks.mapNotNull { it.photo?.path }.toSet() + undoable
        photos.listFiles()?.filter { it.path !in held }?.forEach { it.delete() }
    }

    /**
     * Checks [pages], the photos of [piece]'s work (AI-17), against it and its task (AI-16), records
     * the cost, and says how it went. The photos are deleted, whatever happens.
     */
    suspend fun check(pages: List<File>, piece: Chunk, takenAt: Long = clock.now()): String {
        // [takenAt], when its first page was taken: a slot of its key kept before then isn't the one it shows.
        if (!piece.photoCheckable) {
            withContext(NonCancellable + Dispatchers.IO) { pages.forEach { it.delete() } }
            return "Only written homework can be checked from a photo."
        }
        val jpegs = try {
            // Every page, or the check isn't made: one left out could be the one that shows it isn't done.
            val read = withContext(Dispatchers.IO) { pages.map { PhotoChecks.shrink(it) } }
            if (read.isEmpty() || read.any { it == null }) return if (pages.size > 1) "A page couldn't be read: take the photos again." else "Couldn't read the photo."
            read.filterNotNull()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { pages.forEach { it.delete() } }
        }
        // Its room taken with every other model call's, then sent (AC-05).
        val current = gateway.client(ModelEffort.Job.PhotoCheck)?.let { PhotoChecker(it, profile = profile()) } ?: return OFF
        val about = PhotoChecker.context(piece, tasks.value.tasks.firstOrNull { it.id == piece.taskId })
        val result = when (val called = gateway.call(interactive = true, record = ModelCalls.OnRecord(ModelEffort.Job.PhotoCheck, piece.label) { r: PhotoChecker.Result -> r.usage to ModelCalls.outcome(r.refused, r.verdict == null) }, cost = { r: PhotoChecker.Result -> r.costUsd to r.refused }) { current.check(jpegs, about) }) {
            is Called.Held -> return when (called.hold) {
                ModelHold.Off -> OFF
                ModelHold.Resting -> "Claude's key isn't working (Setup says why): tick it Done, or the source will have to do."
                ModelHold.Capped -> "Claude's monthly cap is reached: tick it Done, or the source will have to do."
            }
            is Called.Failed -> {
                Log.w(AppGraph.TAG, "The photo check failed", called.error)
                return "The check didn't go through (${called.error.message ?: "no connection"})."
            }
            is Called.Answered -> called.value
        }
        val verdict = result.verdict ?: return "Claude couldn't say. Try a clearer photo."
        if (!verdict.done || verdict.confidence < PhotoChecker.ACCEPT) return "Not yet: ${verdict.reason}"
        if (!focus.photoChecked(piece.taskId, piece.step, piece.minutes, piece.stepIndex, piece.key, takenAt)) return "Done, but it was already ticked off: ${verdict.reason}"
        return "Done: ${verdict.reason}"
    }

    private companion object {
        const val OFF = "Claude is off: the timer or the source will have to do."
    }
}
