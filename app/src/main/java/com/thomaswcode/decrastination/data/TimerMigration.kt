package com.thomaswcode.decrastination.data

/**
 * A timer on a piece (4.4.0, D-112): `SessionState.timer`, none in a file from before. Nothing to
 * change; the store's version is raised all the same, so an older build sets a newer file aside rather than
 * read it without the timer.
 */
object TimerMigration {
    /** session.json from version 2. */
    val SESSION = Migration(2) { it }
}
