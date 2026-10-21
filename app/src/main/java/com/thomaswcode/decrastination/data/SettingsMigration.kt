package com.thomaswcode.decrastination.data

/**
 * Settings' switch for finishing emails in Gmail (4.3.0, `gmailFinishes`) read as it is: a file from before
 * has none, and takes its default; the version's raised all the same, so an older build sets a newer file
 * aside rather than read it without the switch.
 */
object SettingsMigration {
    /** settings.json from version 1. */
    val SETTINGS = Migration(1) { it }
}
