package com.thomaswcode.decrastination.data

/**
 * Work that comes round every week (D-124): two changes of new types, `Weekly` and `WeeklyEnd`, each with its rule
 * (`Change.weekly`), held by the instructions and by Felix's proposals in the chat and its archive's months; a task
 * marked as a week of a rule (`TaskItem.weekly`), and each rule's last week made (`TaskState.weeksMade`). A file from
 * before has none of it; each store's version is raised all the same, so an older build sets a newer file aside rather
 * than read it without them.
 */
object WeeklyMigration {
    /** tasks.json from version 3. */
    val TASKS = Migration(3) { it }

    /** instructions.json from version 5. */
    val INSTRUCTIONS = Migration(5) { it }

    /** chat.json from version 12. */
    val CHAT = Migration(12) { it }

    /** A month of the chat's archive from version 11, as [CHAT]. */
    val ARCHIVE = Migration(11) { it }
}
