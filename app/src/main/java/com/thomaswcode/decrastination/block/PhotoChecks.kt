package com.thomaswcode.decrastination.block

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.core.Chunk
import com.thomaswcode.decrastination.enrich.AiUsage
import com.thomaswcode.decrastination.enrich.PhotoChecker
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A photo of written work, checked by the model and, if it's done, the piece ticked off. */
object PhotoChecks {

    /** Checks [photo] against [piece], records the cost, and says how it went. The photo is deleted, whatever happens. */
    suspend fun check(context: Context, graph: AppGraph, checker: PhotoChecker, photo: File, piece: Chunk): String {
        val jpeg = try {
            withContext(Dispatchers.IO) { shrink(photo) } ?: return "Couldn't read the photo."
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { photo.delete() }
        }
        val now = graph.clock.now()
        val month = AiUsage.monthOf(now, graph.clock.zone())
        // The cap checked and the cost recorded in one turn with every other model call.
        val result = graph.modelCalls.withLock {
            val settings = graph.settings.value
            if (!graph.runtime.value.aiUsage.forMonth(month).allows(settings.aiMonthlyCapGbp, settings.usdToGbp)) return "Claude's monthly cap is reached: the timer or the source will have to do."
            runCatching { checker.check(jpeg, piece.label) }
                .onFailure { Log.w(AppGraph.TAG, "The photo check failed", it) }
                .getOrElse { return "The check didn't go through (${it.message ?: "no connection"})." }
                .also { r -> graph.runtime.update { it.copy(aiUsage = it.aiUsage.forMonth(month).record(r.costUsd, r.refused, now)) } }
        }
        val verdict = result.verdict ?: return "Claude couldn't say. Try a clearer photo."
        if (!verdict.done || verdict.confidence < PhotoChecker.ACCEPT) return "Not yet: ${verdict.reason}"
        graph.focus.photoChecked(piece.taskId, piece.step, piece.minutes)
        return "Done: ${verdict.reason}"
    }

    /** The photo as a JPEG no bigger than [PhotoChecker.MAX_SIDE] on its long side. */
    private fun shrink(photo: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (long / (sample * 2) >= PhotoChecker.MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = PhotoChecker.MAX_SIDE.toDouble() / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}
