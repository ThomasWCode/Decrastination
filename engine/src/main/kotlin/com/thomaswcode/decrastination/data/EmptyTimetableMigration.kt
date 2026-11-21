package com.thomaswcode.decrastination.data

/**
 * An empty timetable held back while the last one is kept (D-165; docs/plan_v4/data-and-migrations.md §The shapes,
 * release by release, 6.11.0). On the JSON.
 */
object EmptyTimetableMigration {
    /** tasks.json from version 5: none held back in a file from before, nothing to change. */
    val TASKS = Migration(5) { it }
}
