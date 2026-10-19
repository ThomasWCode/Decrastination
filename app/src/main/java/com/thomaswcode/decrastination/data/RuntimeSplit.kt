package com.thomaswcode.decrastination.data

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `runtime.json` split by owner and by how often it changes (docs/plan_v4/04-data-and-migrations.md
 * §`runtime.json` split, Stage 7): the half-minute state to `session.json`, protection's to
 * `protection.json`, the model's to `model.json`, the Anki watch's to `anki.json`, the calendar's to
 * `calendar.json` beside the versions it wrote, the calibration to `learning.json`; the rest stays. Each
 * field moves as it is, under its own name. On the JSON.
 */
object RuntimeSplit {
    /** runtime.json's fields by the file each goes to. */
    val FIELDS: Map<String, List<String>> = mapOf(
        "session.json" to listOf("credit", "slots", "photosDone", "keeping", "rewarded"),
        "protection.json" to listOf("pending", "overrideUntil", "adminArmed", "codeLock", "protection", "forceActiveUntil", "uptimeMark"),
        "model.json" to listOf("aiUsage", "calls", "enrichFailed", "droppedAlerted", "microPlans"),
        "anki.json" to listOf("ankiReadings", "ankiFrontSince", "ankiWorked"),
        "calendar.json" to listOf("calendarWritten", "calendarWrittenTo", "calendarsToClear", "eventAnswers", "eventsAsked"),
        "learning.json" to listOf("calibration"),
    )

    /** runtime.json from version 2: the fields gone to their own files ([MOVE]). */
    val RUNTIME = Migration(2) { runtime -> JsonObject(runtime - FIELDS.values.flatten().toSet()) }

    /** calendar.json from version 1: on its own (a file the move didn't reach), its versions as they were; the move brings the rest beside them. */
    val CALENDAR = Migration(1) { it }

    /**
     * At start, before any store is read ([Stores]): each owner's fields written to its file first (the
     * calendar's beside what calendar.json holds, its file of before kept as `calendar.json.v1`); runtime.json
     * loses them after, as it loads ([RUNTIME]), keeping its file of before as `.v2`. A runtime in the new
     * shape is split already, so a second run changes nothing; a stop between writes them again from it.
     */
    val MOVE = Move { dir -> move(dir) }

    private fun move(dir: File) {
        val runtime = read(File(dir, "runtime.json")) ?: return
        if (Migration.versionOf(runtime) >= 3) return
        for ((name, fields) in FIELDS) {
            val moved = fields.mapNotNull { field -> runtime[field]?.let { field to it } }.toMap()
            if (moved.isEmpty()) continue
            val file = File(dir, name)
            val now = if (name == "calendar.json") {
                val was = read(file)
                if (was != null) File(dir, "$name.v1").takeIf { !it.exists() }?.writeText(was.toString())
                JsonObject((was ?: JsonObject(emptyMap())) + moved + (Migration.VERSION to JsonPrimitive(2)))
            } else {
                JsonObject(moved + (Migration.VERSION to JsonPrimitive(1)))
            }
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(now.toString())
            JsonStore.moveOver(tmp, file)
        }
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
}
