package com.thomaswcode.decrastination.data

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `runtime.json` split by owner and by how often it changes (docs/plan_v4/04-data-and-migrations.md
 * §`runtime.json` split): the half-minute state to `session.json`, protection's to
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
     * shape is split already. A file a start before wrote is left as it is, as new as the runtime's copy or
     * newer, so a start after a stop, or after one whose runtime's trim wasn't written, puts nothing older
     * back. One there that can't be read is set aside as its store would set it aside, and said.
     */
    val MOVE = Move { dir -> move(dir, System.currentTimeMillis()) }

    private fun move(dir: File, at: Long): List<JsonStore.Unreadable> {
        val runtime = read(File(dir, "runtime.json")) ?: return emptyList()
        if (Migration.versionOf(runtime) >= 3) return emptyList()
        val aside = mutableListOf<JsonStore.Unreadable>()
        for ((name, fields) in FIELDS) {
            val moved = fields.mapNotNull { field -> runtime[field]?.let { field to it } }.toMap()
            if (moved.isEmpty()) continue
            val file = File(dir, name)
            // The calendar's file is there before the split, its versions in it (version 1); the others are new.
            val version = if (name == "calendar.json") 2 else 1
            val was = read(file)
            if (was != null && Migration.versionOf(was) >= version) continue
            if (was != null) {
                File(dir, "$name.v1").takeIf { !it.exists() }?.writeText(was.toString())
            } else if (file.exists()) {
                // Set aside before the runtime's fields go into a fresh one; where it can't be, it isn't
                // written over: its store finds it and says it, and the fields stay in the runtime's file
                // of before, kept a week.
                val copy = JsonStore.alreadyAside(file) ?: JsonStore.setAside(file, at) ?: continue
                aside += JsonStore.Unreadable(name, copy.name, kept = false, at = at)
            }
            val now = JsonObject((was ?: JsonObject(emptyMap())) + moved + (Migration.VERSION to JsonPrimitive(version)))
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(now.toString())
            JsonStore.moveOver(tmp, file)
        }
        return aside
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
}
