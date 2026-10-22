package com.thomaswcode.decrastination.data

/**
 * A step can say it doesn't need the steps before it done first (D-109): a task's steps say so in
 * tasks.json, and in the chat's acts and proposals and its archive's months, which keep tasks for an
 * Undo; an instruction's steps in instructions.json. A file from before has none of it, each step read
 * as needing the ones before it; each store's version is raised all the same, so an older build sets a
 * newer file aside rather than read it without them.
 */
object StepOrderMigration {
    /** tasks.json from version 2. */
    val TASKS = Migration(2) { it }

    /** instructions.json from version 2. */
    val INSTRUCTIONS = Migration(2) { it }

    /** chat.json from version 6. */
    val CHAT = Migration(6) { it }

    /** A month of the chat's archive from version 5, as [CHAT]. */
    val ARCHIVE = Migration(5) { it }
}
