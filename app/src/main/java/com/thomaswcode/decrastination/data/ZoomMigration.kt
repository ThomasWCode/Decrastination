package com.thomaswcode.decrastination.data

/**
 * The Day's grid keeps its zoom in the settings (4.6.0, `dayHourDp`): a file from before has none, and takes its default;
 * the version's raised all the same, so an older build sets a newer file aside rather than read it without it.
 */
object ZoomMigration {
    /** settings.json from version 2. */
    val SETTINGS = Migration(2) { it }
}
