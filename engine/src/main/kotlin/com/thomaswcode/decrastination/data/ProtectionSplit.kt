package com.thomaswcode.decrastination.data

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `protection.json` split (docs/plan_v4/data-and-migrations.md §The shapes, release by release, 4.13.0):
 * what's only true of this phone goes to `device.json`, what holds wherever protection is asked for
 * stays. On the JSON.
 */
object ProtectionSplit {
    /** The fields that go, by their name there. */
    val FIELDS: Map<String, String> = mapOf("adminArmed" to "adminArmed", "protection" to "watchdog", "forceActiveUntil" to "forceActiveUntil")

    /** protection.json from version 1: the phone's own fields gone to device.json ([MOVE]). */
    val PROTECTION = Migration(1) { protection -> JsonObject(protection - FIELDS.keys) }

    /**
     * At start, before any store is read ([Stores]): the fields written to device.json first; protection.json
     * loses them after, as it loads ([PROTECTION]), keeping its file of before as `.v1`. A device.json a
     * start before wrote is left as it is, as new as what's here or newer, so a start after a stop, or
     * after one whose trim wasn't written, puts nothing older back. One there that can't be read is set
     * aside as its store would set it aside, and said.
     */
    val MOVE = Move { dir -> move(dir, System.currentTimeMillis()) }

    private fun move(dir: File, at: Long): List<JsonStore.Unreadable> {
        val protection = read(File(dir, "protection.json")) ?: return emptyList()
        if (Migration.versionOf(protection) >= 2) return emptyList()
        val moved = FIELDS.mapNotNull { (from, to) -> protection[from]?.let { to to it } }.toMap()
        if (moved.isEmpty()) return emptyList()
        val file = File(dir, "device.json")
        if (read(file) != null) return emptyList()
        val aside = if (file.exists()) {
            // Where it can't be set aside it isn't written over: its store finds it and says it, and the fields stay
            // in protection.json's file of before, kept a week.
            val copy = JsonStore.alreadyAside(file) ?: JsonStore.setAside(file, at) ?: return emptyList()
            listOf(JsonStore.Unreadable(file.name, copy.name, kept = false, at = at))
        } else {
            emptyList()
        }
        val tmp = File(dir, "device.json.tmp")
        tmp.writeText(JsonObject(moved + (Migration.VERSION to JsonPrimitive(1))).toString())
        JsonStore.moveOver(tmp, file)
        return aside
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
}
