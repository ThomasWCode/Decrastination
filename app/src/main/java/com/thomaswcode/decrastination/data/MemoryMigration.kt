package com.thomaswcode.decrastination.data

/**
 * What's remembered can expire: a fact's and a rule's until, and a fact's task, read as they are in every
 * store that keeps a fact or a rule: memory.json itself, the chat's and its archive's memory edits under his
 * answers, and the week's reviews' tidies in the log. A file from before has none of the fields; each store's
 * version is raised all the same, so an older build sets a newer file aside rather than read it without them.
 */
object MemoryMigration {
    /** memory.json from version 1. */
    val MEMORY = Migration(1) { it }

    /** chat.json from version 5: its answers' memory edits. */
    val CHAT = Migration(5) { it }

    /** A month of the chat's archive from version 4, as [CHAT]. */
    val ARCHIVE = Migration(4) { it }

    /** log.json from version 2: its weekly reviews' tidies. */
    val LOG = Migration(2) { it }
}
