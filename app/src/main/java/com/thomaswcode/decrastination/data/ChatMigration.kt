package com.thomaswcode.decrastination.data

import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The chat's second shape (docs/plan_v4/04-data-and-migrations.md §The chat (v2), Stage 5a): every
 * message of Felix's in the chat, with why he wrote it, and his state between looks beside them. His
 * records move in from the log and his state from the runtime, merged by id so nothing's doubled; what
 * a message kept its own way before (why he wrote it, the lines under it) is in the fields the chat
 * keeps now. On the JSON, so it stays as it was written whatever the app's types become.
 */
object ChatMigration {
    /** The reasons a record was written for that are kinds of message of their own: the rest (his answers) are the chat's. */
    private val KINDS = setOf("Morning", "SlotStart", "SlotEnd", "SlotKept", "DayDone", "Question", "Completions", "CheckIn", "Summary")

    /** The records in the app's own words, not the model's: the check-in's questions and thanks, the summary's offer. */
    private val BY_APP = setOf("CheckIn", "Summary")

    /** The records that asked something: answered, they leave his notification. */
    private val QUESTIONS = setOf("CheckIn", "Completions", "Question")

    /** The lines under an answer that say something wasn't made or looked up. */
    private val PROBLEMS = listOf("Not made:", "Not looked up:")

    /** How near its reply's time a message of yours in the same words is the reply, sent to the chat as it was given. */
    private const val SAME_REPLY_MS = 5 * 60_000L

    /** The runtime's fields that are his state, the chat's now. */
    private val RUNTIME_FIELDS = setOf("felix", "assessLater", "checkInDraft")

    /** chat.json from version 1 on its own: its messages as the chat keeps them now, with nothing of the log's (a backup's part, or a file the move didn't reach). */
    val CHAT = Migration(1) { chat -> merged(chat, records = null, runtime = null, memory = null, titles = emptyMap()) }

    /** log.json from version 1: Felix's records gone, as they're the chat's messages now ([MOVE]). */
    val LOG = Migration(1) { log -> JsonObject(log - "felix") }

    /** runtime.json from version 1: his state gone, as it's the chat's now ([MOVE]). */
    val RUNTIME = Migration(1) { runtime -> JsonObject(runtime - RUNTIME_FIELDS) }

    /**
     * His records and state moved into the chat at start, before any store is read ([Stores]): the chat
     * written whole in its new shape first, the file it came from kept as `chat.json.v1`; the log and the
     * runtime lose them after, as each loads ([LOG], [RUNTIME]), keeping theirs as `.v1` too. A chat in the
     * new shape is moved already, so a second run changes nothing.
     */
    val MOVE = Move { dir -> move(dir) }

    private fun move(dir: File) {
        val file = File(dir, "chat.json")
        val chat = read(file)
        // One in the new shape is moved already. One that can't be read is set aside as its store would
        // set it aside, and his records and state are moved into a fresh chat all the same, as the log and
        // the runtime are about to lose them; where it can't be set aside, it isn't written over.
        if (chat != null && Migration.versionOf(chat) >= 2) return
        if (file.exists() && chat == null && JsonStore.alreadyAside(file) == null && JsonStore.setAside(file, System.currentTimeMillis()) == null) return
        val log = read(File(dir, "log.json"))?.takeIf { Migration.versionOf(it) < 2 }
        val runtime = read(File(dir, "runtime.json"))?.takeIf { Migration.versionOf(it) < 2 }
        // A phone starting afresh: nothing to move.
        if (chat == null && log?.get("felix") == null && runtime == null) return
        val tasks = read(File(dir, "tasks.json"))
        val titles = (tasks?.get("tasks") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { t -> t.string("id")?.let { id -> id to t.string("title").orEmpty() } }.toMap()
        val moved = merged(chat ?: JsonObject(emptyMap()), log?.get("felix") as? JsonArray, runtime, read(File(dir, "memory.json")), titles)
        if (chat != null) File(dir, "chat.json.v1").takeIf { !it.exists() }?.let { file.copyTo(it) }
        val tmp = File(dir, "chat.json.tmp")
        tmp.writeText(JsonObject(moved + (Migration.VERSION to JsonPrimitive(2))).toString())
        JsonStore.moveOver(tmp, file)
    }

    private fun read(file: File): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { JsonStore.json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }

    /**
     * [chat] (version 1) with [records], the log's records of his messages, merged in, and his state from
     * [runtime] (its `felix`, `assessLater` and `checkInDraft`): each record a message of his, its id and
     * time as they were, why he wrote it its kind, its piece, task (named from [titles]), replies, next
     * check and completions with it; its reply a message of yours answering it. A record already in the
     * chat (copied there when you replied, or his answer's) fills in only what the message lacks; a reply
     * already there as a message of yours is marked as answering it, not doubled. A message's lines of
     * before go where the chat keeps them now: what it remembered, as [memory] has it, with its Undo;
     * the rest under it with nothing to undo. Oldest first; all of it read ([ChatState.readAt]).
     */
    fun merged(chat: JsonObject, records: JsonArray?, runtime: JsonObject?, memory: JsonObject?, titles: Map<String, String>): JsonObject {
        val messages = (chat["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { m -> message(m, memory) } }.toMutableList()
        val claimed = mutableSetOf<String>()
        for (record in records.orEmpty().mapNotNull { it as? JsonObject }) {
            val id = record.string("id") ?: continue
            val reason = record.string("reason").orEmpty()
            val at = record.long("at") ?: continue
            val reply = record.string("replyText")
            val repliedAt = record.long("repliedAt") ?: at
            val about = record.string("taskId")?.let { task -> JsonObject(mapOf("taskId" to JsonPrimitive(task), "taskTitle" to (titles[task]?.let(::JsonPrimitive) ?: JsonNull))) }
            val i = messages.indexOfFirst { it.string("id") == id }
            if (i >= 0) {
                // In the chat already: only what the message lacks.
                val m = messages[i]
                messages[i] = JsonObject(
                    m + listOfNotNull(
                        ("kind" to JsonPrimitive(kind(reason))).takeIf { m.string("kind") == CHAT_KIND && reason in KINDS },
                        record["key"]?.takeIf { it !is JsonNull && m.string("pieceKey") == null }?.let { "pieceKey" to it },
                        record["nextCheckAt"]?.takeIf { it !is JsonNull && m.long("nextCheckAt") == null }?.let { "nextCheckAt" to it },
                        (record["asks"] as? JsonArray)?.takeIf { it.isNotEmpty() && (m["asks"] as? JsonArray).isNullOrEmpty() }?.let { "asks" to it },
                    ),
                )
            } else {
                messages += JsonObject(
                    listOfNotNull(
                        "id" to JsonPrimitive(id),
                        "from" to JsonPrimitive("Claude"),
                        "text" to (record["text"] ?: JsonPrimitive("")),
                        "at" to JsonPrimitive(at),
                        about?.let { "about" to it },
                        "kind" to JsonPrimitive(kind(reason)),
                        "byApp" to JsonPrimitive(reason in BY_APP),
                        record["key"]?.takeIf { it !is JsonNull }?.let { "pieceKey" to it },
                        record["nextCheckAt"]?.takeIf { it !is JsonNull }?.let { "nextCheckAt" to it },
                        (record["asks"] as? JsonArray)?.let { "asks" to it },
                        // A question answered leaves his notification.
                        ("answeredAt" to JsonPrimitive(repliedAt)).takeIf { reply != null && reason in QUESTIONS },
                        (record["replies"] as? JsonArray)?.let { "suggestedReplies" to it },
                    ).toMap(),
                )
            }
            if (reply == null) continue
            // Your reply: a message of yours already (it went to the chat, sent there as it was given), or one made of it.
            val words = reply.trim()
            fun yours(m: JsonObject) = m.string("from") == "You" && m.string("id") !in claimed && m.string("text")?.trim() == words
            val after = if (i >= 0) messages.drop(i + 1).firstOrNull { yours(it) && it.string("replyTo") == null } else null
            val sent = after ?: messages.firstOrNull { yours(it) && it.string("replyTo") == null && abs((it.long("at") ?: 0L) - repliedAt) <= SAME_REPLY_MS }
            if (sent != null) {
                claimed += sent.string("id")!!
                messages[messages.indexOf(sent)] = JsonObject(sent + ("replyTo" to JsonPrimitive(id)))
            } else {
                messages += JsonObject(
                    listOfNotNull(
                        "id" to JsonPrimitive("$id-reply"),
                        "from" to JsonPrimitive("You"),
                        "text" to JsonPrimitive(reply),
                        "at" to JsonPrimitive(repliedAt),
                        about?.let { "about" to it },
                        "replyTo" to JsonPrimitive(id),
                        // Answered as it came (a check-in's answer, how work went), never waiting for a call.
                        "answeredAt" to JsonPrimitive(repliedAt),
                    ).toMap(),
                )
            }
        }
        val sorted = messages.sortedBy { it.long("at") ?: 0L }
        val felix = (runtime?.get("felix") as? JsonObject ?: JsonObject(emptyMap())) + listOfNotNull(
            runtime?.get("assessLater")?.takeIf { it is JsonArray }?.let { "assessLater" to it },
            runtime?.get("checkInDraft")?.takeIf { it is JsonObject }?.let { "checkInDraft" to it },
        )
        return JsonObject(
            chat + mapOf(
                "messages" to JsonArray(sorted),
                "readAt" to JsonPrimitive(sorted.maxOfOrNull { it.long("at") ?: 0L } ?: 0L),
                "felix" to JsonObject(felix),
            ),
        )
    }

    /**
     * A message of the first shape in the second: why he wrote it its kind; what it remembered, as lines
     * before 16 Oct, made the edits it did where [memory] still holds them (their Undo forgets them);
     * the lines under it with nothing to undo among its acts, after the acts, a problem where something
     * wasn't made or looked up.
     */
    private fun message(m: JsonObject, memory: JsonObject?): JsonObject {
        val reason = m.string("reason")
        val done = (m["done"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val remembered = (m["remembered"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val edits = (m["memoryEdits"] as? JsonArray).orEmpty()
        val acts = (m["acts"] as? JsonArray).orEmpty() + done.map { line ->
            JsonObject(mapOf("line" to JsonPrimitive(line), "problem" to JsonPrimitive(PROBLEMS.any { line.startsWith(it) })))
        }
        val memoryEdits = edits.ifEmpty { remembered.map { edit(it, memory) } }
        return JsonObject(
            m - setOf("reason", "done", "remembered") + listOfNotNull(
                "kind" to JsonPrimitive(reason?.let(::kind) ?: CHAT_KIND),
                ("byApp" to JsonPrimitive(true)).takeIf { reason in BY_APP },
                "acts" to JsonArray(acts),
                "memoryEdits" to JsonArray(memoryEdits),
            ),
        )
    }

    /** What [line] remembered, as [memory] holds it still: the fact or standing instruction it names, so its Undo forgets it; else the line alone. */
    private fun edit(line: String, memory: JsonObject?): JsonObject {
        val facts = (memory?.get("facts") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val rules = (memory?.get("rules") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        facts.firstOrNull { "Remembered: ${it.string("topic")}: ${it.string("text")}" == line }?.let { return JsonObject(mapOf("line" to JsonPrimitive(line), "factAfter" to it)) }
        rules.firstOrNull { "Felix will: ${it.string("text")}" == line }?.let { return JsonObject(mapOf("line" to JsonPrimitive(line), "ruleAfter" to it)) }
        return JsonObject(mapOf("line" to JsonPrimitive(line)))
    }

    private const val CHAT_KIND = "Chat"

    private fun kind(reason: String): String = if (reason in KINDS) reason else CHAT_KIND

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
