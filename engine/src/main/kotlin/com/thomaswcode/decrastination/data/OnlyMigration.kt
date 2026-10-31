package com.thomaswcode.decrastination.data

/**
 * A day can be kept for only some tasks (D-115): a change of a new type, `DayOnly`, with what else may come in
 * (`alsoAllow`), held by the instructions and by Felix's proposals in the chat and its archive's months. A file from
 * before has none of it; each store's version is raised all the same, so an older build sets a newer file aside rather
 * than read it without them.
 */
object OnlyMigration {
    /** instructions.json from version 3. */
    val INSTRUCTIONS = Migration(3) { it }

    /** chat.json from version 7. */
    val CHAT = Migration(7) { it }

    /** A month of the chat's archive from version 6, as [CHAT]. */
    val ARCHIVE = Migration(6) { it }
}
