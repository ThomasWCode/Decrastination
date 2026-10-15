package com.thomaswcode.decrastination.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * The phone's `Documents/Decrastination` folder (LR-08, AR-07), through MediaStore: no folder to pick,
 * no permission, nothing to set. The app sees the files it made there without one, and they outlast
 * a clear of its data. Android 10 on ([available]); below it nothing is written there.
 */
object BackupFolder {
    /** Where, on the phone's shared storage. */
    const val PATH = "Documents/Decrastination/"

    /** How many nightly backups are kept: two weeks of them. */
    const val KEEP = 14

    private val NIGHTLY = Regex("""decrastination-(\d{4}-\d{2}-\d{2})\.json""")

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * [name] there with [text]: written over in place if the app made it already (a second insert of a
     * name MediaStore would call "… (1).json"), else made, pending till it's whole. Whether it was.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun write(resolver: ContentResolver, name: String, text: String): Boolean = runCatching { put(resolver, name, text) }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun put(resolver: ContentResolver, name: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val there = list(resolver).firstOrNull { it.first == name }?.second
        if (there != null) {
            resolver.openOutputStream(there, "wt")?.use { it.write(bytes) } ?: return false
            // Whole now, should a write before have left it pending.
            resolver.update(there, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection(), values) ?: return false
            val written = runCatching { resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null }.getOrDefault(false)
            if (!written) {
                // Half made: let go, so no hidden copy keeps the name.
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return true
    }

    /** The files the app made there, oldest first: each one's name and where it is. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun list(resolver: ContentResolver): List<Pair<String, Uri>> = runCatching {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        resolver.query(collection(), projection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(PATH), "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to android.content.ContentUris.withAppendedId(collection(), c.getLong(0))) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** [uri], one of the app's there, deleted. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun delete(resolver: ContentResolver, uri: Uri): Boolean = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /** Of [names], the nightly backups past the newest [keep], by the date in each name: a reset's copy never. Pure. */
    fun toPrune(names: List<String>, keep: Int = KEEP): List<String> =
        names.mapNotNull { name -> NIGHTLY.matchEntire(name)?.let { name to it.groupValues[1] } }.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** The nightly backup's name for [date] (`yyyy-MM-dd`). */
    fun nightlyName(date: String): String = "decrastination-$date.json"

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
}
