package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Felix's line brought to the default his voice was tuned with, where it's one of the app's old ones (D-120). */
class SpeechMigrationTest {
    private fun line(json: JsonObject) = json.getValue("felixLine").jsonPrimitive.content

    private fun settings(line: String?) = JsonObject(listOfNotNull("version" to JsonPrimitive(3), line?.let { "felixLine" to JsonPrimitive(it) }).toMap())

    @Test
    fun `an old default line becomes the default now, and one you typed stays as you typed it`() {
        for (old in SpeechMigration.OLD_LINES) {
            val moved = SpeechMigration.SETTINGS.apply(settings(old))
            assertEquals(Settings().felixLine to 4, line(moved) to Migration.versionOf(moved))
        }
        assertEquals("my strict friend", line(SpeechMigration.SETTINGS.apply(settings("my strict friend"))))
        // None stored: the default's taken as the file's read, nothing written in its place.
        assertEquals(null, SpeechMigration.SETTINGS.apply(settings(null))["felixLine"])
    }
}
