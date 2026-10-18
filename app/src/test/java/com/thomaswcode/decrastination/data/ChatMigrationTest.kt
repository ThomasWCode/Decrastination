package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.MemoryEdit
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The chat's second shape (docs/plan_v4/04-data-and-migrations.md §The chat (v2), Stage 5a), from the
 * first shape's made-up fixtures (Stage 0's `fixtures/stores/2026-10-18/`), as a phone meets it at its
 * first start: Felix's records from the log and his state from the runtime moved into the chat, merged
 * by id, nothing doubled or lost; run twice, the same; the files they came from kept; a backup from
 * before brought to it the same way.
 */
class ChatMigrationTest {
    private val dir: File = Files.createTempDirectory("chat").toFile()
    private val before: File = Fixtures.file("stores/2026-10-18")

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    /** The phone's stores as at its first start with this build, on a copy of the first shape. */
    private fun start(): Stores {
        if (dir.listFiles().isNullOrEmpty()) before.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        return Stores(dir, Reverse)
    }

    private fun json(file: File): JsonObject = JsonStore.json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `his records are messages in the chat at their times, each with what it was, merged with what the chat held`() {
        val chat = start().chat.value
        val byId = chat.messages.associateBy { it.id }
        // Every record a message, by its id; the chat's own all there; your replies made messages, but the one the chat had.
        val records = (json(File(before, "log.json"))["felix"] as JsonArray).map { (it.jsonObject["id"] as JsonPrimitive).content }
        assertTrue(byId.keys.containsAll(records))
        assertTrue(byId.keys.containsAll(listOf("msg-ask", "msg-felix", "rec-start", "msg-reply", "msg-answer", "msg-stuck")))
        assertEquals(listOf("rec-checkin-reply", "rec-completions-reply", "rec-question-reply", "rec-end-reply"), chat.messages.filter { it.id.startsWith("rec-") && it.id.endsWith("-reply") }.map { it.id })
        assertEquals(6 + records.size - 2 + 4, chat.messages.size)
        // Oldest first.
        assertEquals(chat.messages.sortedBy { it.at }, chat.messages)
        // Why each was written, its kind; the app's own words marked.
        assertEquals(
            mapOf(
                "rec-morning" to MessageKind.Morning, "rec-start" to MessageKind.SlotStart, "rec-end" to MessageKind.SlotEnd, "rec-kept" to MessageKind.SlotKept,
                "rec-daydone" to MessageKind.DayDone, "rec-silent" to MessageKind.SlotStart, "rec-question" to MessageKind.Question, "rec-completions" to MessageKind.Completions,
                "rec-summary" to MessageKind.Summary, "rec-checkin" to MessageKind.CheckIn, "msg-answer" to MessageKind.Chat, "msg-felix" to MessageKind.Chat,
            ),
            records.plus("msg-felix").associateWith { byId.getValue(it).kind },
        )
        assertEquals(setOf("rec-summary", "rec-checkin"), chat.messages.filter { it.byApp }.map { it.id }.toSet())
        // A record's piece, task, next check, replies and completions on its message; its silence kept, with no words.
        val end = byId.getValue("rec-end")
        assertEquals("teams:as-sheet#1" to "teams:as-sheet", end.pieceKey to end.about.taskId)
        assertEquals(listOf("yes", "tonight"), end.suggestedReplies)
        assertEquals("teams:as-done", byId.getValue("rec-completions").asks.single().taskId)
        assertEquals("", byId.getValue("rec-silent").text)
        // In the chat already: only what it lacked filled in.
        val start = byId.getValue("rec-start")
        assertEquals("teams:as-essay#0" to 1792254720000L, start.pieceKey to start.nextCheckAt)
        assertEquals(listOf("on it", "5 min"), start.suggestedReplies)
        // Your reply, a message of yours already, marked as answering it, not doubled.
        assertEquals("rec-start", byId.getValue("msg-reply").replyTo)
        assertEquals(1, chat.messages.count { it.text == "on it now" })
        // One made of a reply: under its message, answered as it came; its question answered too, out of his notification.
        val reply = byId.getValue("rec-end-reply")
        assertEquals(Triple(Speaker.You, "tonight", "rec-end"), Triple(reply.from, reply.text, reply.replyTo))
        assertNotNull(reply.answeredAt)
        assertEquals(About(taskId = "teams:as-sheet", taskTitle = "Forces worksheet"), reply.about)
        assertNotNull(byId.getValue("rec-question").answeredAt)
        assertNull(byId.getValue("rec-end").answeredAt)
        // Yours that waited still waits; everything as read as it was.
        assertEquals("msg-stuck", chat.unanswered?.id)
        assertEquals(chat.messages.maxOf { it.at }, chat.readAt)
    }

    @Test
    fun `a message's lines of before are where the chat keeps them now`() {
        val felix = start().chat.value.messages.single { it.id == "msg-felix" }
        // Its edits kept as they were; its lines with nothing to undo after its acts, none of them a problem.
        assertEquals(3, felix.memoryEdits.size)
        assertEquals(listOf("Ticked: Questions 1 to 6, of Forces worksheet", "Deleted: Old task", "Grace: 12 min", "Read in full: “Biology poster”."), felix.acts.map { it.line })
        assertNull(felix.acts.last().action)
        assertFalse(felix.acts.last().problem)
    }

    @Test
    fun `his state is the chat's, with what waits to be asked and the check-in's answers, and the log and runtime keep none of it`() {
        val stores = start()
        val runtime = json(File(before, "runtime.json"))
        val felix = stores.chat.value.felix
        assertEquals(JsonStore.json.decodeFromJsonElement(FelixState.serializer(), runtime.getValue("felix")).copy(assessLater = felix.assessLater, checkInDraft = felix.checkInDraft), felix)
        assertEquals(1, felix.assessLater.size)
        assertEquals(4, felix.checkInDraft?.step)
        // The log and the runtime in their new shapes, without them.
        val log = json(File(dir, "log.json"))
        assertEquals(2, Migration.versionOf(log))
        assertFalse("felix" in log)
        val written = json(File(dir, "runtime.json"))
        assertTrue(listOf("felix", "assessLater", "checkInDraft").none { it in written })
        // The rest of them as they were.
        assertEquals(json(File(before, "log.json")).getValue("slots"), log.getValue("slots"))
        assertEquals(runtime.getValue("calibration"), written.getValue("calibration"))
    }

    @Test
    fun `the files they came from are kept, and a second start changes nothing`() {
        val once = start().chat.value
        for (name in listOf("chat.json", "log.json", "runtime.json")) assertEquals(File(before, name).readText(), File(dir, "$name.v1").readText(), name)
        val files = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() }
        val again = Stores(dir, Reverse)
        assertEquals(once, again.chat.value)
        assertEquals(files, dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readText() })
    }

    @Test
    fun `with a chat that can't be read, his records and state go into a fresh chat all the same, the bad file kept aside`() {
        before.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        File(dir, "chat.json").writeText("{ not json")
        val stores = Stores(dir, Reverse)
        val records = (json(File(before, "log.json"))["felix"] as JsonArray).map { (it.jsonObject["id"] as JsonPrimitive).content }
        assertTrue(stores.chat.value.messages.map { it.id }.containsAll(records))
        assertTrue(stores.chat.value.felix.checkInDraft != null || json(File(before, "runtime.json"))["checkInDraft"] == null)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("chat.json.unreadable-") }!!.size)
        assertEquals("{ not json", dir.listFiles { f -> f.name.startsWith("chat.json.unreadable-") }!!.single().readText())
    }

    @Test
    fun `a phone starting afresh has nothing moved, and a chat on its own is brought to the new shape without the log`() {
        assertEquals(ChatState(), Stores(dir, Reverse).chat.value)
        assertFalse(File(dir, "chat.json").exists())
        // A chat with no log or runtime beside it (a backup's part, say): its own messages, nothing added.
        File(dir, "chat.json").writeText(File(before, "chat.json").readText())
        val chat = Stores(dir, Reverse).chat.value
        assertEquals(listOf("msg-ask", "msg-felix", "rec-start", "msg-reply", "msg-answer", "msg-stuck"), chat.messages.map { it.id })
        assertEquals(MessageKind.SlotStart, chat.messages.single { it.id == "rec-start" }.kind)
    }

    @Test
    fun `what a message remembered before its edits were kept has its Undo while what's remembered still holds it`() {
        val chat = JsonStore.json.parseToJsonElement(
            """{"messages":[{"id":"m","from":"Claude","text":"Noted.","at":5,"remembered":["Remembered: bus: takes the 7:40","Felix will: be blunter","Forgot: football"]}]}""",
        ).jsonObject
        val memory = JsonStore.json.parseToJsonElement("""{"facts":[{"topic":"bus","text":"takes the 7:40","at":4}],"rules":[{"text":"be blunter","at":4}]}""").jsonObject
        val merged = ChatMigration.merged(chat, null, null, memory, emptyMap())
        val message = JsonStore.json.decodeFromJsonElement(ChatState.serializer(), JsonObject(merged + ("version" to JsonPrimitive(2)))).messages.single()
        val (fact, rule, forgot) = message.memoryEdits
        assertEquals("bus", fact.factAfter?.topic)
        assertEquals("be blunter", rule.ruleAfter?.text)
        assertEquals(MemoryEdit("Forgot: football"), forgot)
    }

    @Test
    fun `a backup from before is brought to the new shape as the phone's files are, its records and Felix's state moved into its chat`() {
        val stores = start()
        fun file(name: String) = json(File(before, name))
        val runtime = file("runtime.json")
        // A format 6 backup, its parts of the first shape: Felix's records in its log, his state a part of its own.
        val old = JsonObject(
            mapOf(
                "app" to JsonPrimitive(Backup.APP), "format" to JsonPrimitive(6), "exportedAt" to JsonPrimitive(1792312300000L),
                "settings" to file("settings.json"), "log" to file("log.json"), "memory" to file("memory.json"), "chat" to file("chat.json"), "school" to file("school.json"),
                "felix" to runtime.getValue("felix"), "slots" to runtime.getValue("slots"), "calibration" to runtime.getValue("calibration"),
                "versions" to JsonObject(stores.shapes.keys.associateWith { JsonPrimitive(1) }),
            ),
        )
        val backup = assertNotNull(Backups.decode(old.toString(), stores.shapes))
        assertEquals(stores.shapes.mapValues { it.value.version }, backup.versions)
        // Its chat as the phone's became, but the tasks' names (a backup holds only your own tasks) and what a backup never held.
        fun named(state: ChatState) = state.messages.map { it.copy(about = it.about.copy(taskTitle = null)) }
        assertEquals(named(stores.chat.value), named(backup.chat))
        assertEquals(stores.chat.value.felix.copy(assessLater = emptyList(), checkInDraft = null), backup.chat.felix)
        assertEquals(stores.log.value.slots, backup.log.slots)
    }

    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
