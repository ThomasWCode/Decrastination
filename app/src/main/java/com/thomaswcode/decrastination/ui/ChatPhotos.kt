package com.thomaswcode.decrastination.ui

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.block.PhotoChecks
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatPhoto
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photos waiting to go with your next message (6.8.0), by hash, their files in [draftDir]: held by the app, not a screen, so a
 * turn of the phone loses none.
 */
internal val chatDraft = MutableStateFlow<List<String>>(emptyList())

/** Where a photo waits till its message goes: the cache, which no sweep of the app's photos reaches. */
internal fun draftDir(context: Context): File = File(context.cacheDir, "chat-draft")

/**
 * The chat's photo button (6.8.0): Camera or Gallery, as a check's are, the picture shrunk as a check's page is and added to
 * [chatDraft], [ChatPhoto.MAX] at most; [onAdded] once one's in. Stays composed with the chat, as its launchers are its own.
 */
@Composable
internal fun rememberChatPhoto(graph: AppGraph, activity: Activity, name: String, onAdded: () -> Unit): () -> Unit {
    val shot = remember { File(activity.cacheDir, "photos/chat.jpg") }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val app = activity.applicationContext
    val picture = rememberPicture(activity, graph.scope, shot) { taken ->
        if (!taken) {
            shot.delete()
        } else {
            graph.scope.launch(Dispatchers.IO) {
                val jpeg = PhotoChecks.shrink(shot).also { shot.delete() } ?: return@launch
                val hash = Photos.hashOf(jpeg)
                draftDir(app).apply { mkdirs() }.resolve("$hash.jpg").writeBytes(jpeg)
                chatDraft.update { (it + hash).distinct().take(ChatPhoto.MAX) }
                withContext(Dispatchers.Main) { onAdded() }
            }
        }
    }
    if (choosing) {
        CameraOrGallery("A photo for $name", "Sent with your next message, ${ChatPhoto.MAX} at most.", onCamera = {
            choosing = false
            picture.camera()
        }, onGallery = {
            choosing = false
            picture.gallery()
        }, onDismiss = { choosing = false })
    }
    return { choosing = true }
}

/** [hash] taken out of the photos waiting to go, and its file with it. */
internal fun forgetDraft(context: Context, hash: String) {
    chatDraft.update { it - hash }
    File(draftDir(context), "$hash.jpg").delete()
}

/**
 * [hash], a photo waiting to go, kept as the app's chat photo (6.8.0), where the message naming it finds it and it's sent to the server
 * ahead of it: whether it is. Its waiting copy stays till the message is kept, so one that can't be is back in the box with it.
 */
internal fun keepDrafted(graph: AppGraph, context: Context, hash: String): Boolean {
    if (graph.photos.chatPhotoFile(hash).isFile) return true
    val jpeg = runCatching { File(draftDir(context), "$hash.jpg").readBytes() }.getOrNull() ?: return false
    return runCatching { graph.photos.keepChatPhoto(hash, jpeg) }.getOrDefault(false)
}

/**
 * The photos waiting to go (6.8.0), small, each with its ✕, and the tick box, *Delete after use* ([deleting]), ticked each time
 * they're shown.
 */
@Composable
internal fun DraftPhotos(context: Context, hashes: List<String>, deleting: Boolean, onDeleting: (Boolean) -> Unit) {
    Column(Modifier.padding(bottom = 6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hashes, key = { it }) { hash ->
                Box {
                    val bitmap by produceState<ImageBitmap?>(null, hash) { value = withContext(Dispatchers.IO) { chatThumb(File(draftDir(context), "$hash.jpg")) } }
                    bitmap?.let { Image(it, contentDescription = "A photo to send", modifier = Modifier.height(DRAFT_THUMB.dp)) }
                        ?: Box(Modifier.size((DRAFT_THUMB * 3 / 4).dp, DRAFT_THUMB.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    IconButton(onClick = { forgetDraft(context, hash) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Not this one", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        DeleteAfterUse(deleting, onDeleting)
    }
}

/**
 * A message's photos in its bubble (6.8.0): each while its message needs it, from the file here or, sent from the website, fetched
 * from the server and kept here; once it's gone, Felix's line of it in its place.
 */
@Composable
internal fun MessagePhotos(graph: AppGraph, message: ChatMessage, now: Long) {
    message.photos.forEach { photo ->
        val live = photo.live(message, now)
        val bitmap by produceState<ImageBitmap?>(null, photo.hash, live) {
            value = if (!live) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    val file = graph.photos.chatPhotoFile(photo.hash)
                    if (!file.isFile) graph.replica.fetchChatPhoto(photo.hash)?.let { graph.photos.keepChatPhoto(photo.hash, it) }
                    chatThumb(file)
                }
            }
        }
        bitmap?.let { Image(it, contentDescription = photo.note.ifBlank { "A photo" }, modifier = Modifier.padding(bottom = 6.dp).widthIn(max = SHOWN_WIDTH.dp).clip(RoundedCornerShape(10.dp))) }
            ?: Text(
                if (photo.note.isBlank()) "[photo]" else "[photo: ${photo.note}]",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = 6.dp),
            )
    }
}

/** How tall a photo waiting to go is drawn. */
private const val DRAFT_THUMB = 64

/** How wide a message's photo is drawn at most. */
private const val SHOWN_WIDTH = 240

/** [file], a chat photo, read small: a quarter of it each way is enough for a bubble. */
private fun chatThumb(file: File): ImageBitmap? = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull()
