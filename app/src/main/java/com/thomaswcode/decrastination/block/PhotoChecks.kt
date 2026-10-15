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
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.ModelHold
import com.thomaswcode.decrastination.enrich.PhotoChecker
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
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

    /** Checks [photo] against [piece], records the cost, and says how it went. The photo is deleted, whatever happens. */
    suspend fun check(context: Context, graph: AppGraph, photo: File, piece: Chunk): String {
        if (!piece.photoCheckable) {
            withContext(NonCancellable + Dispatchers.IO) { photo.delete() }
            return "Only written homework can be checked from a photo."
        }
        val jpeg = try {
            withContext(Dispatchers.IO) { shrink(photo) } ?: return "Couldn't read the photo."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { photo.delete() }
        }
        // The cap checked and the cost recorded in one turn with every other model call.
        val result = graph.modelCalls.withLock {
            // Checked again here, just before sending: switched off, resting after a failed call
            // (perhaps one made while this waited), or no room under the cap.
            when (graph.modelHold()) {
                ModelHold.Off -> return OFF
                ModelHold.Resting -> return "Claude's last call failed, so it's left alone for an hour (Setup says why): the timer or the source will have to do."
                ModelHold.Capped -> return "Claude's monthly cap is reached: the timer or the source will have to do."
                null -> Unit
            }
            val current = graph.photoChecker() ?: return OFF
            // The month it's made in, as the cap was checked for: it may have waited past midnight.
            val now = graph.clock.now()
            val month = AiUsage.monthOf(now, graph.clock.zone())
            runCatching { current.check(jpeg, piece.label) }
                .onFailure { e ->
                    Log.w(AppGraph.TAG, "The photo check failed", e)
                    graph.modelFailed(e, month, now)
                }
                .getOrElse { return "The check didn't go through (${it.message ?: "no connection"})." }
                .also { r -> graph.modelWorked(r.costUsd, r.refused, month, now) }
        }
        val verdict = result.verdict ?: return "Claude couldn't say. Try a clearer photo."
        if (!verdict.done || verdict.confidence < PhotoChecker.ACCEPT) return "Not yet: ${verdict.reason}"
        // A session on it started while this was checked (from another screen): that counts it.
        if (graph.focus.session?.taskId == piece.taskId) return "Done, but a session on it started meanwhile: it counts the work when it ends. ${verdict.reason}"
        if (!graph.focus.photoChecked(piece.taskId, piece.step, piece.minutes, piece.stepIndex, piece.key)) return "Done, but it was already ticked off: ${verdict.reason}"
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
