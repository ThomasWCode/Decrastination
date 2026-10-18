package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskOverrides
import com.thomaswcode.decrastination.core.overrides
import com.thomaswcode.decrastination.enrich.FelixPrompts
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

/**
 * Every store's shapes as they've been, read with the app's own serializers (docs/plan_v4, Stage 0).
 * Each folder of `fixtures/stores/` is a shape a phone's files may be in, made up, never taken from
 * the phone: every later migration is tested from every one of them. The newest is the shape now:
 * read whole and strictly, back to the same JSON, with every field of every type in it filled
 * somewhere, so a migration from it meets each field.
 */
class StoresDecodeTest {
    /** A store's file, read and written by [serializer], with its state's type erased so the stores can be listed together. */
    private class Store(val file: String, val serializer: KSerializer<Any?>, val default: () -> Any?)

    @Suppress("UNCHECKED_CAST")
    private fun <T> store(file: String, serializer: KSerializer<T>, default: () -> T) = Store(file, serializer as KSerializer<Any?>, default)

    private val stores = listOf(
        store("tasks.json", TaskState.serializer(), ::TaskState),
        store("settings.json", Settings.serializer(), ::Settings),
        store("runtime.json", RuntimeState.serializer(), ::RuntimeState),
        store("log.json", ActivityLog.serializer(), ::ActivityLog),
        store("instructions.json", InstructionState.serializer(), ::InstructionState),
        store("chat.json", ChatState.serializer(), ::ChatState),
        store("school.json", SchoolCalendar.serializer(), ::SchoolCalendar),
        store("memory.json", MemoryState.serializer(), ::MemoryState),
        store("calendar.json", CalendarVersions.serializer(), ::CalendarVersions),
    )

    /** Each shape's folder, oldest first: their names are the dates they were made. */
    private val shapes: List<File> = Fixtures.file("stores").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

    private val now: File get() = shapes.last()

    private val strict = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    /** Only what differs from its default: what's "filled". */
    private val filledOnly = Json { encodeDefaults = false; explicitNulls = false }

    /** [store] read from [dir] as the app reads it: by a [JsonStore] on a copy, so a file it can't read whole shows. */
    private fun read(store: Store, dir: File): Pair<Any?, JsonStore.Unreadable?> {
        val copy = Files.createTempDirectory("stores").toFile()
        try {
            File(dir, store.file).copyTo(File(copy, store.file))
            val read = JsonStore(File(copy, store.file), store.serializer, store.default)
            return read.value to read.problem
        } finally {
            copy.deleteRecursively()
        }
    }

    /** The store kept in [file]. */
    private fun store(file: String): Store = stores.single { it.file == file }

    @Test
    fun `there's a shape, and every store is in each`() {
        assertTrue(shapes.isNotEmpty(), "no fixtures/stores/<date>/")
        for (dir in shapes) for (store in stores) assertTrue(File(dir, store.file).isFile, "${dir.name}/${store.file} is missing")
    }

    @Test
    fun `every shape's every store reads whole`() {
        for (dir in shapes) for (store in stores) {
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
            assertEquals(strict.parseToJsonElement(text), strict.encodeToJsonElement(store.serializer, value), "${store.file} isn't the shape now: re-encoded, it differs")
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
        val missing = fields.flatMap { (type, names) -> (names - found[type].orEmpty() - LEFT_AT_DEFAULT[type].orEmpty()).map { "$type.$it" } }.sorted()
        assertTrue(missing.isEmpty(), "Left at their defaults, so no migration would be tested on them:\n" + missing.joinToString("\n"))
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
        assertNotNull(byId.getValue("teams:as-sheet").userSteps)
        assertNotNull(byId.getValue("powerplanner:pp-poster").userSteps)
        assertTrue(byId.getValue("own:own-1").subSteps.isNotEmpty())
        assertTrue(byId.getValue("anki:deck-2.3").let { it.derived && it.subSteps.isNotEmpty() })
        // A paced total, and every one of your values on some task.
        assertTrue(byId.getValue("teams:as-essay").let { it.userEffortPacedFrom != null && it.userEffortPacedHash != null && it.userEffortMin != null })
        val yours = state.tasks.flatMap { t ->
            listOfNotNull("notATask".takeIf { t.userNotATask }, "from".takeIf { t.userFrom != null }, "after".takeIf { t.userAfter != null }, "dueAt".takeIf { t.userDueAt != null },
                "onDay".takeIf { t.userOnDay != null }, "atTime".takeIf { t.userAtTime != null }, "sameAs".takeIf { t.userSameAs != null }, "steps".takeIf { t.userSteps != null }, "title".takeIf { t.userTitle != null })
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
        val laid = Instructions.taskOverrides(state.applied)
        for (task in tasks) assertEquals(laid[task.id] ?: TaskOverrides.NONE, task.overrides(), "${task.id}'s values aren't its instructions'")
    }

    @Test
    fun `the chat holds the fields of before, and the log Felix's records of every reason`() {
        val chat = read(store("chat.json"), now).first as ChatState
        assertTrue(chat.messages.any { it.remembered.isNotEmpty() } && chat.messages.any { it.done.isNotEmpty() } && chat.messages.any { it.reason != null })
        assertEquals(Speaker.You, chat.unanswered?.from)
        val log = read(store("log.json"), now).first as ActivityLog
        assertEquals(FelixPrompts.Reason.entries.map { it.name }.toSet(), log.felix.map { it.reason }.toSet())
        assertTrue(log.felix.count { it.replyText != null } >= 3)
        // A record in the chat by its id (copied when you replied to it), and an answer's under its chat id.
        assertTrue(log.felix.map { it.id }.toSet().containsAll(listOf("rec-start", "msg-answer")) && chat.messages.map { it.id }.containsAll(listOf("rec-start", "msg-answer")))
        // A card of Felix's that made an instruction in use.
        val made = chat.messages.flatMap { it.proposals }.mapNotNull { it.made }
        assertTrue("i-felix-steps" in made)
    }

    private companion object {
        /**
         * Fields left at their defaults on purpose, by type: a value there would make the shape
         * incoherent (a Felix answer with no message), or the default is all a field can be.
         */
        val LEFT_AT_DEFAULT: Map<String, Set<String>> = mapOf(
            // A check-in's last answer is never in its draft: given, the check-in is kept and the draft goes.
            "com.thomaswcode.decrastination.learn.CheckInFlow.Draft" to setOf("energy"),
        )
    }
}
