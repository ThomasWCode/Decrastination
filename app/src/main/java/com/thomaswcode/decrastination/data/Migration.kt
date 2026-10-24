package com.thomaswcode.decrastination.data

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One step in a store's shape (docs/plan_v4/data-and-migrations.md §Versions and migrations): its
 * JSON at version [from] made its JSON at [from] + 1, before it's decoded, so a field the new class no
 * longer has can be renamed, moved or dropped. [JsonStore] runs a store's in order as it loads, and a
 * backup's parts go through the same ([Backups.decode]).
 */
class Migration(val from: Int, private val migrate: (JsonObject) -> JsonObject) {
    val to: Int get() = from + 1

    /** [json] at [from], as it is at [to], marked so. */
    fun apply(json: JsonObject): JsonObject = JsonObject(migrate(json) + (VERSION to JsonPrimitive(to)))

    companion object {
        /** Where a store's version is kept, in its state and its file: a file without one is version 1. */
        const val VERSION = "version"

        /** [json]'s version: 1 where it says none. */
        fun versionOf(json: JsonObject): Int = (json[VERSION] as? JsonPrimitive)?.intOrNull ?: 1

        /**
         * [json] brought from its version to [version] by [migrations], in order; as it is at [version].
         * Null when it's newer than [version] (a newer build's, which this one can't read), or a step on
         * the way is missing.
         */
        fun upgrade(json: JsonObject, version: Int, migrations: List<Migration>): JsonObject? {
            var at = versionOf(json)
            if (at > version) return null
            var out = json
            while (at < version) {
                out = (migrations.firstOrNull { it.from == at } ?: return null).apply(out)
                at++
            }
            return out
        }

        /** Whether [migrations] bring every version from 1 to [version], each once. */
        fun complete(version: Int, migrations: List<Migration>): Boolean =
            migrations.map { it.from }.sorted() == (1 until version).toList()
    }
}

/** A store's shape now: its [version], and what brings each older one to it. */
class Shape(val version: Int, val migrations: List<Migration>)

/**
 * Fields moved from one store's file to another's (docs/plan_v4/data-and-migrations.md §Versions and migrations, item 4): run by
 * [Stores] at start, before any store is read, on the files in its folder. The new file written first,
 * then the old one trimmed, so a stop between leaves the fields in both, never in neither; made so a
 * second run changes nothing.
 */
fun interface Move {
    /** Moves the fields in [dir]'s files: a file it found it couldn't read, set aside before it wrote over it, returned to be said as a store's is ([Stores.problems]). */
    fun run(dir: File): List<JsonStore.Unreadable>
}
