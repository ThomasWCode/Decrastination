package com.thomaswcode.decrastination.data

/**
 * The day the Teams sync banner's Skip today was tapped (D-11; docs/plan_v4/data-and-migrations.md §The shapes,
 * release by release, 6.12.0). On the JSON.
 */
object TeamsSkipMigration {
    /** runtime.json from version 4: none skipped in a file from before, nothing to change. */
    val RUNTIME = Migration(4) { it }
}
