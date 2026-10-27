package com.thomaswcode.decrastination.data

/**
 * Felix and you move, skip and add breaks outside (D-116): changes of three new types, `OutsideAt`, `NoOutside` and
 * `OutsideAdded`, with a break's own time (`outsideMin`), held by the instructions and by Felix's proposals in the chat and
 * its archive's months. A file from before has none of it; each store's version is raised all the same, so an older build
 * sets a newer file aside rather than read it without them.
 */
object OutsideMigration {
    /** instructions.json from version 4. */
    val INSTRUCTIONS = Migration(4) { it }

    /** chat.json from version 8. */
    val CHAT = Migration(8) { it }

    /** A month of the chat's archive from version 7, as [CHAT]. */
    val ARCHIVE = Migration(7) { it }
}
