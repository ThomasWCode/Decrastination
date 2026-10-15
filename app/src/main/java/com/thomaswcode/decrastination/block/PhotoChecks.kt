package com.thomaswcode.decrastination.block

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.PhotoChecker
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A photo of written work, checked by the model and, if it's done, the piece ticked off. */
object PhotoChecks {
    private const val OFF = "Claude is off: the timer or the source will have to do."

    /**
     * The task a photo check is under way for, if one is: app-wide, so a screen made again, or
     * another one, knows (no session is started meanwhile). The check runs in the app's scope, so
     * changing tab or screen doesn't cancel a call already paid for.
     */
    val running = MutableStateFlow<String?>(null)

    /** The last check's outcome, by task, until a screen has shown it: one left while it ran shows it on coming back. */
    val unseen = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * Checks [photos], the pages of [piece]'s work (AI-17), against it and its task (AI-16), records
     * the cost, and says how it went. The photos are deleted, whatever happens.
     */
    suspend fun check(context: Context, graph: AppGraph, photos: List<File>, piece: Chunk): String {
        // When it was taken: a slot of its key kept before then isn't the one it shows.
        val takenAt = graph.clock.now()
        if (!piece.photoCheckable) {
            withContext(NonCancellable + Dispatchers.IO) { photos.forEach { it.delete() } }
            return "Only written homework can be checked from a photo."
        }
        val jpegs = try {
            withContext(Dispatchers.IO) { photos.mapNotNull { shrink(it) } }.takeIf { it.isNotEmpty() } ?: return "Couldn't read the photo."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { photos.forEach { it.delete() } }
        }
        // Its room taken with every other model call's, then sent (AC-05).
        val current = graph.photoChecker() ?: return OFF
        val about = PhotoChecker.context(piece, graph.tasks.value.tasks.firstOrNull { it.id == piece.taskId })
        val result = when (val called = graph.callModel(interactive = true, cost = { r: PhotoChecker.Result -> r.costUsd to r.refused }) { current.check(jpegs, about) }) {
            is AppGraph.Called.Held -> return when (called.hold) {
                ModelHold.Off -> OFF
                ModelHold.Resting -> "Claude's key isn't working (Setup says why): tick it Done, or the source will have to do."
                ModelHold.Capped -> "Claude's monthly cap is reached: tick it Done, or the source will have to do."
            }
            is AppGraph.Called.Failed -> {
                Log.w(AppGraph.TAG, "The photo check failed", called.error)
                return "The check didn't go through (${called.error.message ?: "no connection"})."
            }
            is AppGraph.Called.Answered -> called.value
        }
        val verdict = result.verdict ?: return "Claude couldn't say. Try a clearer photo."
        if (!verdict.done || verdict.confidence < PhotoChecker.ACCEPT) return "Not yet: ${verdict.reason}"
        if (!graph.focus.photoChecked(piece.taskId, piece.step, piece.minutes, piece.stepIndex, piece.key, takenAt)) return "Done, but it was already ticked off: ${verdict.reason}"
        return "Done: ${verdict.reason}"
    }

    /**
     * The photo as a JPEG no bigger than [PhotoChecker.MAX_SIDE] on its long side, the right way up:
     * a camera can store a portrait page as landscape pixels with a note to turn them (EXIF), which
     * decoding doesn't read, so the page went sideways (IMP-P2-014).
     */
    private fun shrink(photo: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (long / (sample * 2) >= PhotoChecker.MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = PhotoChecker.MAX_SIDE.toDouble() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
        val turn = runCatching { turnOf(ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) }.getOrDefault(Turn.NONE)
        val matrix = Matrix().apply {
            if (turn.mirrored) postScale(-1f, 1f)
            postRotate(turn.degrees.toFloat())
        }
        val sized = if (turn == Turn.NONE) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    /** What to do to a photo's pixels to stand it upright: mirror them left to right (first), then turn them [degrees] clockwise. */
    data class Turn(val degrees: Int, val mirrored: Boolean) {
        companion object {
            val NONE = Turn(0, false)
        }
    }

    /**
     * What an EXIF orientation says to do, all eight of them: the camera that took it is whichever
     * app answers, and a front camera's can be mirrored.
     */
    fun turnOf(orientation: Int): Turn = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Turn(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> Turn(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Turn(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> Turn(270, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> Turn(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> Turn(90, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> Turn(270, false)
        else -> Turn.NONE
    }
}
