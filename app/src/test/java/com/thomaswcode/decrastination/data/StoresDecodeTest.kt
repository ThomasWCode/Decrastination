package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.felix.FelixPrompts
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every store's shapes as they've been, read with the app's own serializers (docs/plan_v4, Stage 0).
 * Each folder of `fixtures/stores/` is a shape a phone's files may be in, made up, never taken from
 * the phone: every later migration is tested from every one of them, each read as the app reads it at
 * a start, through [Stores] (its moves, then each store's migrations). The newest is the shape now:
 * read whole and strictly, back to the same JSON, with every field of every type in it filled
 * somewhere, so a migration from it meets each field.
 */
class StoresDecodeTest {
    /**
     * A store's file, read and written by [serializer], with its state's type erased so the stores can be
     * listed together; in every shape from [since] on (a shape's folder is named for its date).
     */
    private class Store(val file: String, val serializer: KSerializer<Any?>, val default: () -> Any?, val since: String)

    @Suppress("UNCHECKED_CAST")
    private fun <T> store(file: String, serializer: KSerializer<T>, default: () -> T, since: String = FIRST) = Store(file, serializer as KSerializer<Any?>, default, since)

    private val stores = listOf(
        store("tasks.json", TaskState.serializer(), ::TaskState),
        store("settings.json", Settings.serializer(), ::Settings),
        store("runtime.json", RuntimeState.serializer(), ::RuntimeState),
        store("log.json", ActivityLog.serializer(), ::ActivityLog),
        store("instructions.json", InstructionState.serializer(), ::InstructionState),
        store("chat.json", ChatState.serializer(), ::ChatState),
        store("chat-archive.json", ChatArchive.serializer(), ::ChatArchive, since = "2026-10-19"),
        store("school.json", SchoolCalendar.serializer(), ::SchoolCalendar),
        store("memory.json", MemoryState.serializer(), ::MemoryState),
        store("calendar.json", CalendarVersions.serializer(), ::CalendarVersions),
    )

    /** The stores there are in the shape of [dir]. */
    private fun storesIn(dir: File) = stores.filter { it.since <= dir.name }

    /** Each shape's folder, oldest first: their names are the dates they were made. */
    private val shapes: List<File> = Fixtures.file("stores").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

    private val now: File get() = shapes.last()

    private val strict = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    /** Only what differs from its default: what's "filled". */
    private val filledOnly = Json { encodeDefaults = false; explicitNulls = false }

    /**
     * [store] read from [dir] as the app reads it at a start: by [Stores] on a copy of the whole shape, its
     * moves made and each store's migrations run, so a file it can't read whole shows.
     */
    private fun read(store: Store, dir: File): Pair<Any?, JsonStore.Unreadable?> {
        val copy = Files.createTempDirectory("stores").toFile()
        try {
            dir.listFiles()!!.forEach { it.copyTo(File(copy, it.name)) }
            val stores = Stores(copy, Reverse)
            // The chat's archive, a month a file: all of it read, then what couldn't be.
            if (store.file == stores.chatArchive.name) return stores.chatArchive.value.let { it to stores.chatArchive.problems.firstOrNull() }
            val read = stores.let { s -> listOf(s.tasks, s.settings, s.runtime, s.log, s.instructions, s.chat, s.school, s.memory, s.calendarVersions) }.single { it.name == store.file }
            return read.value to read.problem
        } finally {
            copy.deleteRecursively()
        }
    }

    /** A cipher for the secrets that only reverses the bytes: none of the shapes has any. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    /** The store kept in [file]. */
    private fun store(file: String): Store = stores.single { it.file == file }

    @Test
    fun `there's a shape, and every store is in each from its first`() {
        assertTrue(shapes.isNotEmpty(), "no fixtures/stores/<date>/")
        for (dir in shapes) for (store in storesIn(dir)) assertTrue(File(dir, store.file).isFile, "${dir.name}/${store.file} is missing")
    }

    @Test
    fun `every shape's every store reads whole`() {
        for (dir in shapes) for (store in storesIn(dir)) {
            val (value, problem) = read(store, dir)
            assertNull(problem, "${dir.name}/${store.file} didn't read whole")
            assertTrue(value != store.default(), "${dir.name}/${store.file} read as nothing")
        }
    }

    @Test
    fun `the shape now reads strictly, and back to the same`() {
        for (store in stores) {
            val text = File(now, store.file).readText()
            val value = strict.decodeFromString(store.serializer, text)
            val file = strict.parseToJsonElement(text)
            val again = unsaid(file, strict.encodeToJsonElement(store.serializer, value))
            assertEquals(file, again, "${store.file} isn't the shape now: re-encoded, it differs")
        }
    }

    @Test
    fun `the shape now fills every field of every type in it`() {
        // A type in several stores (a task in tasks.json, and as a chat act's deleted task) is filled once, anywhere.
        val found = mutableMapOf<String, MutableSet<String>>()
        val fields = mutableMapOf<String, List<String>>()
        for (store in stores) {
            val value = strict.decodeFromString(store.serializer, File(now, store.file).readText())
            walk(filledOnly.encodeToJsonElement(store.serializer, value), store.serializer.descriptor, found, fields)
        }
        // Each store's version is its shape's, the default till its first migration: defined, not filled.
        val roots = stores.map { it.serializer.descriptor.serialName }.toSet()
        val missing = fields.flatMap { (type, names) -> (names - found[type].orEmpty() - LEFT_AT_DEFAULT[type].orEmpty() - (if (type in roots) setOf(Migration.VERSION) else emptySet())).map { "$type.$it" } }.sorted()
        assertTrue(missing.isEmpty(), "Left at their defaults, so no migration would be tested on them:\n" + missing.joinToString("\n"))
    }

    /**
     * [again] without the versions [file] doesn't say: a state of version 1 may say none
     * ([Migration.versionOf]), at the root or nested (a chat card's school calendar), and written
     * again says 1.
     */
    private fun unsaid(file: JsonElement, again: JsonElement): JsonElement = when {
        file is JsonObject && again is JsonObject ->
            JsonObject(again.filterNot { (key, value) -> key == Migration.VERSION && key !in file && value == JsonPrimitive(1) }.mapValues { (key, value) -> file[key]?.let { unsaid(it, value) } ?: value })
        file is JsonArray && again is JsonArray -> JsonArray(again.mapIndexed { i, value -> file.getOrNull(i)?.let { unsaid(it, value) } ?: value })
        else -> again
    }

    /** The fields of each class [element] holds that aren't their defaults, into [found]; every class's fields into [fields]. */
    private fun walk(element: JsonElement, descriptor: SerialDescriptor, found: MutableMap<String, MutableSet<String>>, fields: MutableMap<String, List<String>>) {
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val obj = element as? JsonObject ?: return
                val type = descriptor.serialName.removeSuffix("?")
                fields[type] = (0 until descriptor.elementsCount).map(descriptor::getElementName)
                val seen = found.getOrPut(type) { mutableSetOf() }
                for (i in 0 until descriptor.elementsCount) {
                    val value = obj[descriptor.getElementName(i)]?.takeIf { it !is JsonNull } ?: continue
                    seen += descriptor.getElementName(i)
                    walk(value, descriptor.getElementDescriptor(i), found, fields)
                }
            }
            StructureKind.LIST -> (element as? JsonArray)?.forEach { walk(it, descriptor.getElementDescriptor(0), found, fields) }
            StructureKind.MAP -> (element as? JsonObject)?.values?.forEach { walk(it, descriptor.getElementDescriptor(1), found, fields) }
            // Raw JSON (a waiting change's value, SettingsChanges) is a value with no fields of its own; any
            // other polymorphic type is said, not passed over unchecked.
            is PolymorphicKind -> if (descriptor.serialName != "kotlinx.serialization.json.JsonElement") fail("${descriptor.serialName} is polymorphic, which this check doesn't walk: teach it, or leave it out on purpose")
            else -> Unit
        }
    }

    @Test
    fun `the tasks are from every source, with steps of each kind, each of your values, and finished ones`() {
        val state = read(store("tasks.json"), now).first as TaskState
        assertEquals(Source.entries.toSet(), state.tasks.map { it.source }.toSet())
        val byId = state.tasks.associateBy { it.id }
        // Steps of each kind: the reading's, the rules', yours, Felix's (by an instruction), your own task's, the source's.
        assertTrue(byId.getValue("teams:as-essay").let { t -> t.enrichment?.by != "rules" && t.subSteps.map { it.title } == t.enrichment?.subSteps?.map { it.title } })
        assertEquals("rules", byId.getValue("gmail:thr-trip").enrichment?.by)
        assertNotNull(byId.getValue("teams:as-sheet").mine.steps?.steps)
        assertNotNull(byId.getValue("powerplanner:pp-poster").mine.steps?.steps)
        assertTrue(byId.getValue("own:own-1").subSteps.isNotEmpty())
        assertTrue(byId.getValue("anki:deck-2.3").let { it.derived && it.subSteps.isNotEmpty() })
        // A paced total, and every one of your values on some task.
        assertTrue(byId.getValue("teams:as-essay").let { it.mine.minutes?.paced?.from != null && it.mine.minutes?.paced?.contentHash != null && it.mine.minutes?.minutes != null })
        val yours = state.tasks.flatMap { t ->
            listOfNotNull("notATask".takeIf { t.mine.notATask != null }, "from".takeIf { t.mine.from?.value != null }, "after".takeIf { t.mine.after?.value != null }, "dueAt".takeIf { t.mine.dueAt?.value != null },
                "onDay".takeIf { t.mine.onDay?.value != null }, "atTime".takeIf { t.mine.atTime?.value != null }, "sameAs".takeIf { t.mine.sameAs?.value != null }, "steps".takeIf { t.mine.steps?.steps != null }, "title".takeIf { t.mine.title?.value != null })
        }.toSet()
        assertEquals(setOf("notATask", "from", "after", "dueAt", "onDay", "atTime", "sameAs", "steps", "title"), yours)
        // Finished: done, missed; an email's extras.
        assertEquals(setOf(Status.Open, Status.Done, Status.Missed), state.tasks.map { it.status }.toSet())
        assertTrue(byId.getValue("gmail:thr-trip").extra.keys.containsAll(listOf("messageId", "uids", "uidValidity", "textReadTo", "attachments")))
    }

    @Test
    fun `the instructions change every way there is, each the tasks' values carry`() {
        val state = read(store("instructions.json"), now).first as InstructionState
        assertEquals(ChangeType.entries.toSet(), state.instructions.flatMap { i -> i.changes.map { it.type } }.toSet())
        assertEquals(InstructionStatus.entries.toSet(), state.instructions.map { it.state }.toSet())
        // What's in use is what the tasks carry: laid again, nothing on them would change.
        val tasks = (read(store("tasks.json"), now).first as TaskState).tasks
        val laid = InstructionRules.taskOverrides(state.applied)
        for (task in tasks) assertEquals(laid[task.id] ?: Mine.NONE, task.mine, "${task.id}'s values aren't its instructions'")
    }

    @Test
    fun `the first shape's chat holds the fields of before, and its log Felix's records of every reason`() {
        // On its JSON: the app's types no longer have them.
        val first = shapes.first { it.name == FIRST }
        fun json(name: String) = strict.parseToJsonElement(File(first, name).readText()).jsonObject
        val messages = json("chat.json").getValue("messages").jsonArray.map { it.jsonObject }
        assertTrue(listOf("remembered", "done").all { field -> messages.any { it[field]?.jsonArray?.isNotEmpty() == true } } && messages.any { it["reason"] !is JsonNull })
        val records = json("log.json").getValue("felix").jsonArray.map { it.jsonObject }
        assertEquals(FelixPrompts.Reason.entries.map { it.name }.toSet(), records.map { it.getValue("reason").jsonPrimitive.content }.toSet())
        assertTrue(records.count { it["replyText"] !is JsonNull } >= 3)
        // A record in the chat by its id (copied when you replied to it), and an answer's under its chat id.
        val ids = messages.map { it.getValue("id").jsonPrimitive.content }
        assertTrue(records.map { it.getValue("id").jsonPrimitive.content }.containsAll(listOf("rec-start", "msg-answer")) && ids.containsAll(listOf("rec-start", "msg-answer")))
        assertTrue(listOf("felix", "assessLater", "checkInDraft").all { it in json("runtime.json") })
    }

    @Test
    fun `the chat now holds every message of his, of every kind there was a record of, your replies under them, his state and its earlier messages`() {
        val chat = read(store("chat.json"), now).first as ChatState
        assertEquals(Speaker.You, chat.unanswered?.from)
        val his = chat.messages.filter { it.from == Speaker.Felix }
        assertEquals(FelixPrompts.Reason.entries.map { it.name }.filter { it != FelixPrompts.Reason.Reply.name }.toSet() + MessageKind.Chat.name, his.map { it.kind.name }.toSet())
        assertTrue(chat.messages.count { it.from == Speaker.You && it.replyTo != null } >= 4)
        assertTrue(chat.felix.checkInDraft != null && chat.felix.assessLater.isNotEmpty() && chat.readAt > 0)
        // A card of Felix's that made an instruction in use.
        assertTrue("i-felix-steps" in chat.messages.flatMap { it.proposals }.mapNotNull { it.made })
        assertTrue((read(store("chat-archive.json"), now).first as ChatArchive).messages.isNotEmpty())
    }

    private companion object {
        /** The first shape: Stage 0's. */
        const val FIRST = "2026-10-18"

        /**
         * Fields left at their defaults on purpose, by type: a value there would make the shape
         * incoherent (a Felix answer with no message), or the default is all a field can be.
         */
        val LEFT_AT_DEFAULT: Map<String, Set<String>> = mapOf(
            // A check-in's last answer is never in its draft: given, the check-in is kept and the draft goes.
            "com.thomaswcode.decrastination.core.CheckInDraft" to setOf("energy"),
        )
    }
}
