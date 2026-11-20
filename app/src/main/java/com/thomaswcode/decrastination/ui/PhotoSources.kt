package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Where a photo comes from (6.5.0): the camera, or a picture picked from the gallery by Android's photo picker, which asks no
 * permission. [extra] goes under [what], as the tick box does for a check's pages.
 */
@Composable
internal fun CameraOrGallery(title: String, what: String?, onCamera: () -> Unit, onGallery: () -> Unit, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                what?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                extra()
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onGallery) { Text("Gallery") }
                TextButton(onClick = onCamera) { Text("Camera") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The tick box beside a check's pages (6.5.0), ticked each time it's shown: ticked, the app's and the server's copies are deleted
 * once they're used; unticked, they're kept with their task, at most a fortnight. The gallery's own copy is never touched.
 */
@Composable
internal fun DeleteAfterUse(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text("Delete after use", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * [uri], a picture picked, copied into [into] as it is, its EXIF note with it, so it's turned upright and shrunk as a camera's is
 * ([com.thomaswcode.decrastination.block.PhotoChecks.shrink]); whether there's one there now.
 */
internal fun copyPicked(context: Context, uri: Uri, into: File): Boolean = runCatching {
    // Its folder made here, as the camera's path makes it: a cache cleared since leaves none.
    into.parentFile?.mkdirs()
    context.contentResolver.openInputStream(uri)?.use { input -> into.outputStream().use { input.copyTo(it) } } != null
}.getOrElse {
    // A copy cut short leaves no part of a picture to be read as one.
    into.delete()
    false
} && into.length() > 0

/** One picture for a file: from the camera, or picked from the gallery and copied there. */
internal class PictureSource(val camera: () -> Unit, val gallery: () -> Unit)

/**
 * A picture copied in from the gallery (6.5.0), by the app, not a screen, so a turn of the phone while it copies loses none: the file
 * it went to, and whether there's one there, till the screen made again takes it.
 */
internal val copiedIn = MutableStateFlow<Pair<String, Boolean>?>(null)

/**
 * One picture into [file] (6.5.0), from the camera or the gallery; [onTaken] whether there's one there. A picture chosen from the
 * gallery is taken as of when it's chosen, and copied in [scope], the app's, what came of it held in [copiedIn].
 */
@Composable
internal fun rememberPicture(activity: Activity, scope: CoroutineScope, file: File, onTaken: (Boolean) -> Unit): PictureSource {
    val copied by copiedIn.collectAsStateWithLifecycle()
    LaunchedEffect(copied) {
        val (_, taken) = copied?.takeIf { it.first == file.path } ?: return@LaunchedEffect
        copiedIn.value = null
        onTaken(taken)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { onTaken(it) }
    val app = activity.applicationContext
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onTaken(false) else scope.launch(Dispatchers.IO) { copiedIn.value = file.path to copyPicked(app, uri, file) }
    }
    return PictureSource(
        camera = {
            file.parentFile?.mkdirs()
            camera.launch(FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file))
        },
        gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}
