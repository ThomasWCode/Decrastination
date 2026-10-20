package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.newStepId
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Tasks' second shape, and the instructions' (docs/plan_v4/04-data-and-migrations.md §Tasks v2, Stage 7):
 * your values in one value on the task, `mine`, each with who set it, in place of the twelve `user…`
 * fields; every step with an id and where it came from. Your values move as they were, set by you; who
 * set an instruction from before can't always be told (the chat is capped and clearable), so each is
 * yours, Felix's only where the chat still holds the card that made it, and the first lay of the
 * instructions at start gives the tasks' values that label (D-4: it only labels). The steps' origins
 * are inferred this once, as the app told them apart before: a task of your own's are its own; your
 * instruction's are yours (Felix's, where his card made it), with the ids of the instruction's steps
 * they were laid from, so laying it again keeps their ticks; the reading's (its steps' titles) are the
 * rules' or Claude's; the rest the source's. On the JSON, so it stays as it was written whatever the
 * app's types become.
 */
object TasksMigration {
    /** The twelve fields `mine` takes the place of, and its own name for each value. */
    private val PLAIN = mapOf(
        "userFrom" to "from", "userAfter" to "after", "userDueAt" to "dueAt", "userOnDay" to "onDay",
        "userAtTime" to "atTime", "userSameAs" to "sameAs", "userTitle" to "title",
    )
    private val USER = PLAIN.keys + setOf("userNotATask", "userEffortMin", "userEffortPacedFrom", "userEffortPacedHash", "userSteps")

    /** tasks.json from version 1 on its own (a backup's part, or a file the move didn't reach): its tasks in their second shape, nothing laid from instructions known. */
    val TASKS = Migration(1) { tasks -> state(tasks, laid = emptyMap()) }

    /** instructions.json from version 1 on its own: each change's steps with an id; each instruction yours. */
    val INSTRUCTIONS = Migration(1) { instructions -> instructions(instructions, felix = emptySet()) }

    /** chat.json from version 2: the tasks its acts keep for an Undo (a deleted task) in their second shape. */
    val CHAT = Migration(2) { chat -> messagesIn(chat) }

    /** A month of the chat's archive from version 1, as [CHAT]. */
    val ARCHIVE = Migration(1) { archive -> messagesIn(archive) }

    /**
     * At start, before any store is read ([Stores]): instructions.json in its second shape first, each
     * Felix's whose card the chat still holds, every step of a change given its id; then tasks.json, the
     * steps each task has laid from an instruction given that step's id. The files they came from kept
     * as `.v1`. Tasks in the new shape are moved already, so a second run changes nothing; instructions
     * already in theirs (a stop between the two) keep their ids, which the tasks then take. The
     * instructions move with no tasks there yet too (a day's limit set before any sync).
     */
    val MOVE = Move { dir ->
        move(dir)
        emptyList()
    }

    private fun move(dir: File) {
        val instructionsFile = File(dir, "instructions.json")
        val before = read(instructionsFile)
        val instructions = before?.let { if (Migration.versionOf(it) >= 2) it else JsonObject(instructions(it, felixIn(dir)) + (Migration.VERSION to JsonPrimitive(2))) }
        if (before != null && instructions !== before) write(instructionsFile, before, instructions!!)
        val tasksFile = File(dir, "tasks.json")
        val tasks = read(tasksFile) ?: return
        if (Migration.versionOf(tasks) >= 2) return
        write(tasksFile, tasks, JsonObject(state(tasks, laid(instructions)) + (Migration.VERSION to JsonPrimitive(2))))
    }

    /** [file] written as [now], [was] kept beside it as `<name>.v1` (where no copy is there yet). */
    private fun write(file: File, was: JsonObject, now: JsonObject) {
        File(file.parentFile, file.name + ".v1").takeIf { !it.exists() }?.writeText(was.toString())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(now.toString())
        JsonStore.moveOver(tmp, file)
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }

    /** The instructions Felix's cards made, by id: the chat's, and its archive's months'. */
    private fun felixIn(dir: File): Set<String> =
        made((listOf(File(dir, "chat.json")) + dir.listFiles { f -> f.isFile && f.name.startsWith("chat-archive-") && f.name.endsWith(".json") }.orEmpty()).mapNotNull(::read))

    /** The instructions [chats]' cards made, by id. */
    private fun made(chats: List<JsonObject>): Set<String> = chats.flatMap { chat ->
        (chat["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.flatMap { m ->
            (m["proposals"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("made") }
        }
    }.toSet()

    /**
     * A backup's instructions (its part of instructions.json) as the move leaves them at a start: those
     * the chat it carries ([chats]: its chat, its archive) still holds the cards of marked Felix's, every
     * step with an id ([Backups.upgraded]).
     */
    fun backedUp(instructions: JsonArray, chats: List<JsonObject>): JsonArray =
        instructions(JsonObject(mapOf("instructions" to instructions)), made(chats))["instructions"] as JsonArray

    /** [state]'s instructions in their second shape: each change's steps with an id, those [felix] made his. */
    private fun instructions(state: JsonObject, felix: Set<String>): JsonObject {
        val all = (state["instructions"] as? JsonArray ?: return state).map { element ->
            val i = element as? JsonObject ?: return@map element
            val changes = (i["changes"] as? JsonArray)?.map { c ->
                val change = c as? JsonObject ?: return@map c
                val steps = change["steps"] as? JsonArray ?: return@map change
                JsonObject(change + ("steps" to JsonArray(steps.map { s -> (s as? JsonObject)?.let(::withId) ?: s })))
            }
            JsonObject(i + listOfNotNull(changes?.let { "changes" to JsonArray(it) }, ("by" to JsonPrimitive("Felix")).takeIf { i.string("id") in felix }))
        }
        return JsonObject(state + ("instructions" to JsonArray(all)))
    }

    /** Each task's steps as its instructions in use lay them (the last of them to give its steps), with who gave them. */
    private fun laid(instructions: JsonObject?): Map<String, Pair<JsonArray, String>> {
        val applied = (instructions?.get("instructions") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.string("state") == "Applied" }.sortedBy { it.long("appliedAt") ?: it.long("at") ?: 0L }
        val out = HashMap<String, Pair<JsonArray, String>>()
        for (i in applied) for (c in (i["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
            val task = c.string("taskId") ?: continue
            if (c.string("type") == "Steps") (c["steps"] as? JsonArray)?.let { out[task] = it to (i.string("by") ?: "You") }
        }
        return out
    }

    private fun state(state: JsonObject, laid: Map<String, Pair<JsonArray, String>>): JsonObject = JsonObject(
        state + listOfNotNull(
            (state["tasks"] as? JsonArray)?.let { "tasks" to JsonArray(it.map { t -> (t as? JsonObject)?.let { task(it, laid) } ?: t }) },
            (state["unrewarded"] as? JsonArray)?.let { "unrewarded" to JsonArray(it.map { t -> (t as? JsonObject)?.let { task(it, emptyMap()) } ?: t }) },
        ),
    )

    /** The messages of a chat or a month of its archive, the tasks their acts keep in their second shape. */
    private fun messagesIn(chat: JsonObject): JsonObject {
        val messages = chat["messages"] as? JsonArray ?: return chat
        return JsonObject(
            chat + ("messages" to JsonArray(
                messages.map { m ->
                    val message = m as? JsonObject ?: return@map m
                    val acts = message["acts"] as? JsonArray ?: return@map message
                    JsonObject(message + ("acts" to JsonArray(acts.map { a -> (a as? JsonObject)?.let { act -> (act["was"] as? JsonObject)?.let { JsonObject(act + ("was" to task(it, emptyMap()))) } ?: act } ?: a })))
                },
            )),
        )
    }

    /**
     * One task in its second shape: its `user…` fields as `mine`, set by you; its steps with an id and an
     * origin ([laid]: the steps an instruction lays on it, with their ids, and who gave them).
     */
    fun task(t: JsonObject, laid: Map<String, Pair<JsonArray, String>>): JsonObject {
        // Moved already (a task the move gave its second shape, read again by a backup's migration).
        if ("mine" in t && USER.none { it in t }) return t
        val yours = t["userSteps"] as? JsonArray
        val instruction = t.string("id")?.let { laid[it] }
        // Your steps: the ids of the instruction's steps they were laid from, by place, where it's the one that laid them.
        val ownSteps = yours?.mapIndexed { n, s ->
            val step = s as? JsonObject ?: return@mapIndexed s
            val fromInstruction = instruction?.first?.getOrNull(n) as? JsonObject
            if (fromInstruction != null && fromInstruction.string("title")?.trim() == step.string("title")?.trim()) JsonObject(step + ("id" to (fromInstruction["id"] ?: JsonPrimitive(newStepId())))) else withId(step)
        }
        val by = instruction?.second?.takeIf { yours != null } ?: "You"
        val mine = buildMap<String, JsonElement> {
            if ((t["userNotATask"] as? JsonPrimitive)?.booleanOrNull == true) put("notATask", set(JsonPrimitive(true)))
            for ((field, name) in PLAIN) t[field]?.takeIf { it !is JsonNull }?.let { put(name, set(it)) }
            t["userEffortMin"]?.takeIf { it !is JsonNull }?.let { minutes ->
                val paced = t["userEffortPacedFrom"]?.takeIf { it !is JsonNull }?.let { from ->
                    JsonObject(listOfNotNull("from" to from, t["userEffortPacedHash"]?.takeIf { it !is JsonNull }?.let { "contentHash" to it }).toMap())
                }
                put("minutes", JsonObject(listOfNotNull("minutes" to minutes, "by" to JsonPrimitive("You"), paced?.let { "paced" to it }).toMap()))
            }
            ownSteps?.let { put("steps", JsonObject(mapOf("steps" to JsonArray(it), "by" to JsonPrimitive(by)))) }
        }
        val enrichment = t["enrichment"] as? JsonObject
        val read = (enrichment?.get("subSteps") as? JsonArray).orEmpty()
        val steps = (t["subSteps"] as? JsonArray).orEmpty()
        val readingsOrigin = if (enrichment?.string("by") == "rules") "Rules" else "Reading"
        val titles = steps.map { (it as? JsonObject)?.string("title") }
        val fromReading = steps.isNotEmpty() && read.map { (it as? JsonObject)?.string("title") } == titles
        // The reading's steps on the task keep the ids its own copy of them is given, by place.
        val readIds = read.map { (it as? JsonObject)?.string("id") ?: newStepId() }
        val ownIds = ownSteps.orEmpty().mapNotNull { (it as? JsonObject)?.string("title")?.trim()?.let { title -> title to it.string("id") } }
        val ownTurns = HashMap<String, Int>()
        val newSteps = steps.mapIndexed { n, s ->
            val step = s as? JsonObject ?: return@mapIndexed s
            val origin = when {
                t.string("source") == "Own" -> "OwnTask"
                yours != null -> if (by == "Felix") "Felix" else "Yours"
                fromReading -> readingsOrigin
                else -> "Source"
            }
            val id = step.string("id") ?: when (origin) {
                "Yours", "Felix" -> step.string("title")?.trim()?.let { title ->
                    val turn = ownTurns.merge(title, 1, Int::plus)!! - 1
                    ownIds.filter { it.first == title }.getOrNull(turn)?.second
                }
                "Reading", "Rules" -> readIds.getOrNull(n)
                else -> null
            } ?: newStepId()
            JsonObject(step + mapOf("id" to JsonPrimitive(id), "origin" to JsonPrimitive(origin)))
        }
        val newEnrichment = enrichment?.let { e ->
            (e["subSteps"] as? JsonArray)?.let { list ->
                JsonObject(e + ("subSteps" to JsonArray(list.mapIndexed { n, s -> (s as? JsonObject)?.let { JsonObject(it + mapOf("id" to JsonPrimitive(it.string("id") ?: readIds[n]), "origin" to JsonPrimitive(readingsOrigin))) } ?: s })))
            } ?: e
        }
        return JsonObject(
            t - USER + listOfNotNull(
                "mine" to JsonObject(mine),
                ("subSteps" to JsonArray(newSteps)).takeIf { "subSteps" in t },
                newEnrichment?.let { "enrichment" to it },
            ),
        )
    }

    /** A value of yours, set by you. */
    private fun set(value: JsonElement): JsonObject = JsonObject(mapOf("value" to value, "by" to JsonPrimitive("You")))

    /** A step with an id: its own, or a new one. */
    private fun withId(step: JsonObject): JsonObject = if (step.string("id") != null) step else JsonObject(step + ("id" to JsonPrimitive(newStepId())))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() || key != "id" }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
