package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.learn.AnkiReading
import kotlinx.serialization.Serializable

/** What the Anki watch keeps as AnkiDroid is used (NF-03). Kept in `anki.json`. */
@Serializable
data class AnkiState(
    /** The decks' counts read as AnkiDroid came and went (NF-03): the last [com.thomaswcode.decrastination.learn.AnkiProgress.KEEP]. */
    val ankiReadings: List<AnkiReading> = emptyList(),
    /** Since when AnkiDroid has been in front, read coming in; null when it isn't. */
    val ankiFrontSince: Long? = null,
    /** AnkiDroid time on deck tasks today not in a kept slot yet (NF-03): the visits before the one that finishes their cards. */
    val ankiWorked: AnkiWorked = AnkiWorked(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)

/** Minutes in AnkiDroid on each deck task on [date] (NF-03), kept till the visit that finishes its cards. */
@Serializable
data class AnkiWorked(val date: String = "", val minutes: Map<String, Int> = emptyMap()) {
    /** As it stands on [date]: a new day starts empty. */
    fun on(date: String): AnkiWorked = if (this.date == date) this else AnkiWorked(date)

    fun plus(taskId: String, add: Int): AnkiWorked = copy(minutes = minutes + (taskId to (minutes[taskId] ?: 0) + add))

    fun without(taskId: String): AnkiWorked = copy(minutes = minutes - taskId)
}
