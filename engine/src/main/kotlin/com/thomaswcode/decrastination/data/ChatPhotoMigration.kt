package com.thomaswcode.decrastination.data

/**
 * Photos in the chat (6.8.0): a message of yours holds its photos (`ChatMessage.photos`), each a hash, whether it's kept and
 * Felix's note of it; and his answer can keep one with a task (`ChatAction.Type.KeepPhoto`). A file from before has none of it;
 * each store's version is raised all the same, so an older build sets a newer file aside rather than read it without them.
 */
object ChatPhotoMigration {
    /** chat.json from version 13. */
    val CHAT = Migration(13) { it }

    /** A month of the chat's archive from version 12, as [CHAT]. */
    val ARCHIVE = Migration(12) { it }
}
