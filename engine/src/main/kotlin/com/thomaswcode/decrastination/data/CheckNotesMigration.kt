package com.thomaswcode.decrastination.data

/**
 * What photo checks saw of a task's work (6.4.0): `SessionState.notes`, none in a file from before. Nothing to change; the
 * store's version is raised all the same, so an older build sets a newer file aside rather than read it without the notes.
 */
object CheckNotesMigration {
    /** session.json from version 3. */
    val SESSION = Migration(3) { it }
}
