package com.thomaswcode.decrastination.data

/**
 * Felix's message keeps the level he wrote it at (D-119, Q103), `ChatMessage.mood`, in the chat and its archive's months; and
 * his note on how he felt (`ChatMessage.feeling`, `FelixAnswer.feeling`, a reminder's draft's too) and your rating of it
 * (`rating`, `feedback`, `ratedAt`). A file from before has none of them, which reads as neutral, unnoted and unrated;
 * each store's version is raised all the same, so an older build sets a newer file aside rather than read it without them.
 */
object MoodMigration {
    /** chat.json from version 9. */
    val CHAT = Migration(9) { it }

    /** A month of the chat's archive from version 8, as [CHAT]. */
    val ARCHIVE = Migration(8) { it }

    /** felix.json from version 1: a reminder's draft's answer with his note, as [CHAT]. */
    val FELIX = Migration(1) { it }
}
