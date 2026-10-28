package com.thomaswcode.decrastination.data

/**
 * Felix's message keeps the texts he wrote beside it and didn't send (D-120), `ChatMessage.unsent` and his answer's
 * `FelixAnswer.unsent` (a draft's, and a reminder's), and the one you'd rather he had sent, `ChatMessage.preferred`. A
 * file from before has none of them, which reads as nothing beside it and nothing picked; each store's version is raised
 * all the same, so an older build sets a newer file aside rather than read it without them.
 */
object SpeechMigration {
    /** chat.json from version 10. */
    val CHAT = Migration(10) { it }

    /** A month of the chat's archive from version 9, as [CHAT]. */
    val ARCHIVE = Migration(9) { it }

    /** felix.json from version 2: a reminder's draft's answer with the texts beside it, as [CHAT]. */
    val FELIX = Migration(2) { it }
}
