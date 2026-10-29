package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Felix's message keeps the texts he wrote beside it and didn't send (D-120), `ChatMessage.unsent` and his answer's
 * `FelixAnswer.unsent` (a draft's, and a reminder's), and the one you'd rather he had sent, `ChatMessage.preferred`. A
 * file from before has none of them, which reads as nothing beside it and nothing picked; each store's version is raised
 * all the same, so an older build sets a newer file aside rather than read it without them. And Felix's line, where it's
 * an old default, the one now ([SETTINGS]).
 */
object SpeechMigration {
    /** chat.json from version 10. */
    val CHAT = Migration(10) { it }

    /** A month of the chat's archive from version 9, as [CHAT]. */
    val ARCHIVE = Migration(9) { it }

    /** felix.json from version 2: a reminder's draft's answer with the texts beside it, as [CHAT]. */
    val FELIX = Migration(2) { it }

    /**
     * settings.json from version 3: Felix's line, where it's one the app gave by default before ([OLD_LINES]), the default
     * now, which his voice was tuned with and his first sentence carries (D-120, at the owner's word); a line you typed is
     * kept as you typed it (D-4). A default is written to the file once the settings are saved, so a phone keeps an old one.
     */
    val SETTINGS = Migration(3) { json ->
        if ((json["felixLine"] as? JsonPrimitive)?.contentOrNull in OLD_LINES) JsonObject(json + ("felixLine" to JsonPrimitive(Settings().felixLine))) else json
    }

    /** Felix's lines by default before 4.7.0's: AI-22's first, and 4.2.2's (Q29). */
    val OLD_LINES = setOf("a strict friend who knows your week and wants the work done", "your mate who's on your case about the work")
}
