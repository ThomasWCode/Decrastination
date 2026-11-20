package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.data.BackupService

/** [folder], each file it takes noted by its size in bytes ([noted], 6.10.0): B2's key can't list what the bucket holds. */
class NotedFolder(private val folder: BackupService.Folder, private val noted: (Long) -> Unit) : BackupService.Folder by folder {
    override fun write(name: String, text: String): Boolean = folder.write(name, text).also { if (it) noted(text.toByteArray().size.toLong()) }
}
