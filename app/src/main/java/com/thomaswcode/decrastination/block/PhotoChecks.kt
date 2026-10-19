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
import com.thomaswcode.decrastination.enrich.Called
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
    /**
     * The task a photo check is under way for, if one is: app-wide, so a screen made again, or
     * another one, knows (no session is started meanwhile). The check runs in the app's scope, so
     * changing tab or screen doesn't cancel a call already paid for.
     */
    val running = MutableStateFlow<String?>(null)

    /** The last check's outcome, by task, until a screen has shown it: one left while it ran shows it on coming back. */
    val unseen = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The photo as a JPEG no bigger than [PhotoChecker.MAX_SIDE] on its long side, the right way up:
     * a camera can store a portrait page as landscape pixels with a note to turn them (EXIF), which
     * decoding doesn't read, so the page went sideways (IMP-P2-014).
     */
    fun shrink(photo: File): ByteArray? {
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
