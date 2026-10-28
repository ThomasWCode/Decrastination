package com.thomaswcode.decrastination.data

/**
 * Felix's message keeps the level he wrote it at (D-119, Q6): `ChatMessage.mood`, in the chat and its archive's months. A
 * file from before has none, which reads as neutral; each store's version is raised all the same, so an older build sets a
 * newer file aside rather than read it without it.
 */
object MoodMigration {
    /** chat.json from version 9. */
    val CHAT = Migration(9) { it }

    /** A month of the chat's archive from version 8, as [CHAT]. */
    val ARCHIVE = Migration(8) { it }
}
