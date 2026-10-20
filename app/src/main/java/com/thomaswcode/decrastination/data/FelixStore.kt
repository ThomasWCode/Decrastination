package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Note
import com.thomaswcode.decrastination.core.Reminder
import kotlinx.serialization.Serializable

/**
 * What Felix keeps at your asking (docs/plan_v4/07-notes-and-reminders.md, Stage 8a): notes on tasks,
 * and standing ones (Q31: A), and reminders with their drafts. Kept in `felix.json`, the Conversation's;
 * not on the tasks, which a merge rewrites, but by task id, which syncs and reopenings keep, and which
 * outlives a finished task's trimmed text on its record.
 */
@Serializable
data class FelixStore(
    val notes: List<Note> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it. */
    val version: Int = 1,
) {
    companion object {
        /** chat.json from version 3: 4.1.0's acts on reminders and notes, and a reminder's messages, read as they are (their fields have defaults). */
        val CHAT = Migration(3) { it }

        /** A month of the chat's archive from version 2, as [CHAT]. */
        val ARCHIVE = Migration(2) { it }
    }
}
