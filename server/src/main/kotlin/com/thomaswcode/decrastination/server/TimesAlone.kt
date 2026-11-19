package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.data.CalendarRead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Whether a store's new text says more than its last row of history did, or differs from it by nothing but the times a sync or
 * a read keeps: when a source was last tried and last read, when a source last listed a task, when the calendar's read began,
 * and where the chat's been read to. Nine in ten of `tasks.json`'s rows were such, and nearly all of `calendar-read.json`'s
 * (counted on the box, 19 Nov 2026), each a whole copy of the store. Named fields, not every time: a slot moved or a date
 * changed is a time too, and what the history is there to bring back.
 */
internal object TimesAlone {
    /** Each store's fields that are times alone, by their path: `[]` each of a list's items, `{}` each of a map's values. */
    private val FIELDS: Map<String, List<List<String>>> = mapOf(
        "tasks.json" to listOf(listOf("sources", "{}", "lastAttemptAt"), listOf("sources", "{}", "lastSuccessAt"), listOf("tasks", "[]", "lastSeenAt")),
        CalendarRead.NAME to listOf(listOf("from")),
        "chat.json" to listOf(listOf("readAt")),
    )

    /** Whether [after] differs from [before], [name]'s texts, by more than those times: any store not named, or a text that can't be read, does. */
    fun differ(name: String, before: String, after: String): Boolean {
        val fields = FIELDS[name] ?: return before != after
        if (before == after) return false
        return runCatching { plain(before, fields) != plain(after, fields) }.getOrDefault(true)
    }

    private fun plain(text: String, fields: List<List<String>>): JsonElement =
        fields.fold(Json.parseToJsonElement(text)) { element, path -> element.without(path) }

    /** This with the field at the end of [path] taken out, wherever the path reaches. */
    private fun JsonElement.without(path: List<String>): JsonElement {
        val step = path.firstOrNull() ?: return this
        val rest = path.drop(1)
        return when {
            step == "[]" && this is JsonArray -> JsonArray(map { it.without(rest) })
            step == "{}" && this is JsonObject -> JsonObject(mapValues { (_, value) -> value.without(rest) })
            this !is JsonObject || step !in this -> this
            rest.isEmpty() -> JsonObject(this - step)
            else -> JsonObject(this + (step to getValue(step).without(rest)))
        }
    }
}
