package com.thomaswcode.decrastination.data

/**
 * More doors to the photo check (6.5.0): a check's kept slot (`PhotoDone.record`) and a check's pages kept with their task
 * (`SessionState.pages`), none in a file from before. Nothing to change; the store's version is raised all the same, so an older
 * build sets a newer file aside rather than read it without them.
 */
object PhotoDoorsMigration {
    /** session.json from version 4. */
    val SESSION = Migration(4) { it }
}
