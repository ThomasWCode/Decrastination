package com.thomaswcode.decrastination.data

/**
 * A chat action on a step names the step as Felix was shown it, its title and its id where it had one
 * (`ChatAction.stepTitle`, `stepId`, 4.9.4), so it lands on that step wherever it is now, or on none. A file from
 * before has neither, which reads as an act naming its place alone, its line telling its step ([ChatActs.stepOf]);
 * each store's version is raised all the same, so an older build sets a newer file aside rather than read it without them.
 */
object StepActionMigration {
    /** chat.json from version 11. */
    val CHAT = Migration(11) { it }

    /** A month of the chat's archive from version 10, as [CHAT]. */
    val ARCHIVE = Migration(10) { it }
}
