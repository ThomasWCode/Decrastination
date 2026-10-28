package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Reminder
import kotlinx.serialization.Serializable

/**
 * What Felix keeps at your asking (docs/spec/felix.md §What he can do): notes on tasks,
 * and standing ones (D-100), and reminders with their drafts. Kept in `felix.json`, the Conversation's;
 * not on the tasks, which a merge rewrites, but by task id, which syncs and reopenings keep, and which
 * outlives a finished task's trimmed text on its record.
 */
@Serializable
data class FelixStore(
    val notes: List<Note> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it. */
    val version: Int = 2,
) {
    companion object {
        /** chat.json from version 3: 4.1.0's acts on reminders and notes, and a reminder's messages, read as they are (their fields have defaults). */
        val CHAT = Migration(3) { it }

        /** A month of the chat's archive from version 2, as [CHAT]. */
        val ARCHIVE = Migration(2) { it }

        /** chat.json from version 4: 4.1.1's acts on a piece kept or kept no more (G1), read as they are. */
        val CHAT_PIECES = Migration(4) { it }

        /** A month of the chat's archive from version 3, as [CHAT_PIECES]. */
        val ARCHIVE_PIECES = Migration(3) { it }
    }
}
