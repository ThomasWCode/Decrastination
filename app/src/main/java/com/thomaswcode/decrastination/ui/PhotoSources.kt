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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    context.contentResolver.openInputStream(uri)?.use { input -> into.outputStream().use { input.copyTo(it) } } != null
}.getOrDefault(false) && into.length() > 0

/** One picture for a file: from the camera, or picked from the gallery and copied there. */
internal class PictureSource(val camera: () -> Unit, val gallery: () -> Unit)

/**
 * One picture into [file] (6.5.0), from the camera or the gallery; [onTaken] whether there's one there. A picture chosen from the
 * gallery is taken as of when it's chosen.
 */
@Composable
internal fun rememberPicture(activity: Activity, file: File, onTaken: (Boolean) -> Unit): PictureSource {
    val scope = rememberCoroutineScope()
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { onTaken(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) onTaken(false) else scope.launch { onTaken(withContext(Dispatchers.IO) { copyPicked(activity, uri, file) }) }
    }
    return PictureSource(
        camera = {
            file.parentFile?.mkdirs()
            camera.launch(FileProvider.getUriForFile(activity, "${activity.packageName}.photos", file))
        },
        gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}
