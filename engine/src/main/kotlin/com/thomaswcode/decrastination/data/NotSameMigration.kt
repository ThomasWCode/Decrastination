package com.thomaswcode.decrastination.data

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The pairs you've said aren't the same work (DX-15), from `runtime.json`, which is this phone's, to
 * `tasks.json`, which is the tasks' (docs/plan_v4/data-and-migrations.md §The shapes, release by
 * release, 4.13.0). On the JSON.
 */
object NotSameMigration {
    private const val FIELD = "notSame"

    /** tasks.json from version 4: none in a file the move didn't reach (a backup's part), nothing to change. */
    val TASKS = Migration(4) { it }

    /** runtime.json from version 3: the pairs gone to tasks.json ([MOVE]). */
    val RUNTIME = Migration(3) { runtime -> JsonObject(runtime - FIELD) }

    /**
     * At start, before any store is read ([Stores]): the pairs written to tasks.json first, as a field beside the
     * rest, its version left for its own migrations; runtime.json loses them after, as it loads ([RUNTIME]),
     * keeping its file of before as `.v3`. A tasks.json that holds them already is left as it is. One that can't
     * be read isn't written over: its store sets it aside, and the pairs stay in the runtime's file of before.
     */
    val MOVE = Move { dir -> move(dir) }

    private fun move(dir: File): List<JsonStore.Unreadable> {
        val runtime = read(File(dir, "runtime.json")) ?: return emptyList()
        if (Migration.versionOf(runtime) >= 4) return emptyList()
        val pairs = runtime[FIELD] as? JsonArray ?: return emptyList()
        if (pairs.isEmpty()) return emptyList()
        val file = File(dir, "tasks.json")
        val tasks = read(file) ?: if (file.exists()) return emptyList() else JsonObject(emptyMap())
        if (FIELD in tasks) return emptyList()
        val tmp = File(dir, "tasks.json.tmp")
        tmp.writeText(JsonObject(tasks + (FIELD to pairs)).toString())
        JsonStore.moveOver(tmp, file)
        return emptyList()
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
}
