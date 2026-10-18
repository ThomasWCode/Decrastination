package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.MemoryState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The stores' versions and migrations (docs/plan_v4/04-data-and-migrations.md), on a made-up store whose
 * shape has changed once: its name became a title. Each is run as the app runs it, by a [JsonStore] as
 * it loads, on files in a temporary folder.
 */
class MigrationTest {
    private val dir: File = Files.createTempDirectory("migration").toFile()
    private val file = File(dir, "toy.json")
    private var now = 1_800_000_000_000L

    /** The made-up store's shape now: its name is a title (version 2). */
    @Serializable
    data class Toy(val title: String = "", val count: Int = 0, val version: Int = 2)

    private val renamed = Migration(1) { json -> JsonObject(json - "name" + ("title" to (json["name"] ?: JsonPrimitive("")))) }

    private fun store(migrations: List<Migration> = listOf(renamed)) = JsonStore(file, Toy.serializer(), ::Toy, clock = { now }, migrations = migrations)

    @Test
    fun `a file of the shape before is brought to the shape now as it loads, the file it came from kept`() {
        file.writeText("""{"name":"Worksheet","count":3}""")
        val toy = store()
        assertEquals(Toy("Worksheet", 3, 2), toy.value)
        assertNull(toy.problem)
        // Written again in the new shape, the old kept beside it as it was, dated now.
        val written = JsonStore.json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("Worksheet", written.getValue("title").jsonPrimitive.content)
        assertEquals(2, Migration.versionOf(written))
        val kept = File(dir, "toy.json.v1")
        assertEquals("""{"name":"Worksheet","count":3}""", kept.readText())
        assertTrue(kotlin.math.abs(kept.lastModified() - now) < 2_000, "dated now")
    }

    @Test
    fun `run twice, a migration gives what it gave once`() {
        file.writeText("""{"name":"Worksheet","count":3}""")
        val once = store().value
        val after = file.readText()
        now += 60_000L
        val twice = store()
        assertEquals(once, twice.value)
        assertEquals(after, file.readText(), "read again, nothing is written")
        assertEquals("""{"name":"Worksheet","count":3}""", File(dir, "toy.json.v1").readText())
        // Upgraded twice by hand, the same.
        val v1 = JsonStore.json.parseToJsonElement("""{"name":"Worksheet","count":3}""").jsonObject
        val up = Migration.upgrade(v1, 2, listOf(renamed))!!
        assertEquals(up, Migration.upgrade(up, 2, listOf(renamed)))
    }

    @Test
    fun `a migration that fails on what a file holds sets it aside as one that can't be read, and starts from what reads of it`() {
        file.writeText("""{"name":"Worksheet","count":3}""")
        val broken = Migration(1) { json -> JsonObject(json + ("title" to json.getValue("missing"))) }
        val toy = store(listOf(broken))
        // Not a crash at every start: the file aside, its problem said, what reads of it kept (its count; no title).
        assertEquals(Toy(count = 3), toy.value)
        assertNotNull(toy.problem)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("toy.json.unreadable") }, "set aside")
    }

    @Test
    fun `a file of the shape now isn't migrated, and none is kept`() {
        file.writeText("""{"title":"Essay","count":1,"version":2}""")
        assertEquals(Toy("Essay", 1), store().value)
        assertFalse(File(dir, "toy.json.v1").exists())
    }

    @Test
    fun `a file newer than this build can read is set aside, what reads of it kept in this build's shape`() {
        file.writeText("""{"title":"Essay","count":1,"colour":"red","version":3}""")
        val toy = store()
        val problem = assertNotNull(toy.problem)
        assertTrue(problem.kept)
        // Its version this build's: written back, it isn't found newer again at the next start.
        assertEquals(Toy("Essay", 1, 2), toy.value)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("toy.json.unreadable-") })
    }

    @Test
    fun `a migrated file that doesn't read whole is recovered from its new shape, as one of the shape now would be`() {
        file.writeText("""{"name":"Worksheet","count":"three"}""")
        val toy = store()
        assertNotNull(toy.problem)
        assertEquals(Toy("Worksheet", 0, 2), toy.value)
    }

    @Test
    fun `a store's migrations must bring every version to its own, each once`() {
        assertFailsWith<IllegalArgumentException> { store(migrations = emptyList()) }
        assertFailsWith<IllegalArgumentException> { store(migrations = listOf(renamed, Migration(1) { it })) }
    }

    @Test
    fun `the files older shapes came from go a week after their migration, not before`() {
        val stores = Stores(dir, Reverse, clock = { now })
        val old = File(dir, "tasks.json.v1").apply { writeText("{}"); setLastModified(now - Stores.OLD_SHAPE_KEPT_MS - 1_000) }
        val recent = File(dir, "chat.json.v1").apply { writeText("{}"); setLastModified(now - 60_000) }
        val other = File(dir, "notes.v1").apply { writeText("{}"); setLastModified(now - Stores.OLD_SHAPE_KEPT_MS - 1_000) }
        stores.letGoOfOldShapes(now)
        assertFalse(old.exists())
        assertTrue(recent.exists() && other.exists())
    }

    @Test
    fun `fields moved between stores are moved before any is read, and moving them again changes nothing`() {
        File(dir, "old.json").writeText("""{"facts":[{"topic":"bus","text":"Takes the bus","at":1}]}""")
        // A made-up move: the facts of old.json into memory.json, the new file written first, the old trimmed after.
        val move = Move { folder ->
            val from = File(folder, "old.json")
            val facts = from.takeIf { it.exists() }?.let { JsonStore.json.parseToJsonElement(it.readText()).jsonObject["facts"] } ?: return@Move
            File(folder, "memory.json").writeText(JsonObject(mapOf("facts" to facts)).toString())
            from.writeText("{}")
        }
        val stores = Stores(dir, Reverse, clock = { now }, moves = listOf(move))
        assertEquals(listOf(Fact("bus", "Takes the bus", 1)), stores.memory.value.facts)
        assertEquals(stores.memory.value, Stores(dir, Reverse, clock = { now }, moves = listOf(move)).memory.value)
    }

    @Test
    fun `a backup of a store's shape before is brought to the shape now as it's read`() {
        // As if memory.json's version 2 wrote its topics in capitals, and runtime.json's doubled the month's spend.
        val shapes = mapOf(
            "memory.json" to Shape(2, listOf(Migration(1) { json -> JsonObject(json + ("facts" to JsonArray(json.getValue("facts").let { it as JsonArray }.map { f -> JsonObject(f.jsonObject + ("topic" to JsonPrimitive(f.jsonObject.getValue("topic").jsonPrimitive.content.uppercase()))) }))) })),
            "runtime.json" to Shape(2, listOf(Migration(1) { json -> JsonObject(json + ("aiUsage" to JsonObject(json.getValue("aiUsage").jsonObject + ("spentUsd" to JsonPrimitive(json.getValue("aiUsage").jsonObject.getValue("spentUsd").jsonPrimitive.content.toDouble() * 2))))) })),
        )
        val backup = Backup(exportedAt = now, settings = Settings(), log = ActivityLog(), memory = MemoryState(listOf(Fact("bus", "Takes the bus", 1))), aiUsage = com.thomaswcode.decrastination.enrich.AiUsage(month = "2027-01", spentUsd = 1.5))
        val read = assertNotNull(Backups.decode(Backups.encode(backup), shapes))
        assertEquals("BUS", read.memory.facts.single().topic)
        assertEquals(3.0, read.aiUsage.spentUsd, 1e-9)
        assertEquals(mapOf("memory.json" to 2, "runtime.json" to 2), read.versions.filterKeys { it in shapes })
        // One that says its parts are of the shape now isn't brought again; one newer than the build isn't read.
        val current = Backups.encode(backup.copy(versions = mapOf("memory.json" to 2, "runtime.json" to 2)))
        assertEquals("bus", Backups.decode(current, shapes)!!.memory.facts.single().topic)
        assertNull(Backups.decode(Backups.encode(backup.copy(versions = mapOf("memory.json" to 3))), shapes))
        // Nor is it read with its part under a name this build doesn't know: restored, the part would be lost.
        val renamed = JsonStore.json.parseToJsonElement(Backups.encode(backup.copy(versions = mapOf("memory.json" to 3)))).jsonObject.let { b -> JsonObject(b - "memory" + ("memories" to b.getValue("memory"))) }
        assertNull(Backups.decode(renamed.toString(), shapes))
        // A store it doesn't carry (the calendar's record) can be newer: none of its data is in it, so it's read.
        val calendarNewer = Backups.encode(backup.copy(versions = mapOf("memory.json" to 2, "runtime.json" to 2, "calendar.json" to 5)))
        assertNotNull(Backups.decode(calendarNewer, shapes + ("calendar.json" to Shape(1, emptyList()))))
        // A migration that fails on a part makes the backup one this build can't read, not an exception.
        val failing = mapOf("memory.json" to Shape(2, listOf(Migration(1) { error("fails on what it's given") })))
        assertNull(Backups.decode(Backups.encode(backup), failing))
        // With no shapes given (every store at version 1), it reads as it always did.
        assertEquals(backup.memory, Backups.decode(Backups.encode(backup), emptyMap())!!.memory)
    }

    /** A cipher for the secrets that only reverses the bytes: no keystore on a PC. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
