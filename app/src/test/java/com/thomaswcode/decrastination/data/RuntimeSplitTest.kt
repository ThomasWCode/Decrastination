package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * runtime.json split by owner (docs/plan_v4/04-data-and-migrations.md §`runtime.json` split): every
 * earlier shape of the fixtures through the move and the migrations, as at a start, twice; a backup from before.
 */
class RuntimeSplitTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** The shapes before the split. */
    private val before = listOf("2026-10-18", "2026-10-19")

    private fun phone(shape: String): File = Files.createTempDirectory("split").toFile().also { dir ->
        dirs += dir
        Fixtures.file("stores/$shape").listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
    }

    private fun json(file: File): JsonObject = JsonStore.json.parseToJsonElement(file.readText()).jsonObject

    /** What each store holds as the app writes it: its value, as JSON. */
    private fun written(stores: Stores): Map<String, JsonElement> = mapOf(
        "session.json" to JsonStore.json.encodeToJsonElement(SessionState.serializer(), stores.session.value),
        "protection.json" to JsonStore.json.encodeToJsonElement(ProtectionState.serializer(), stores.protection.value),
        "model.json" to JsonStore.json.encodeToJsonElement(ModelState.serializer(), stores.model.value),
        "anki.json" to JsonStore.json.encodeToJsonElement(AnkiState.serializer(), stores.anki.value),
        "calendar.json" to JsonStore.json.encodeToJsonElement(CalendarState.serializer(), stores.calendar.value),
        "learning.json" to JsonStore.json.encodeToJsonElement(LearningState.serializer(), stores.learning.value),
    )

    /** [value], the runtime's [field], as [file]'s store writes it: read into its state alone and written again. */
    private fun normal(file: String, field: String, value: JsonElement): JsonElement {
        val alone = JsonObject(mapOf(field to value))
        fun <T> through(serializer: KSerializer<T>) = JsonStore.json.encodeToJsonElement(serializer, JsonStore.json.decodeFromJsonElement(serializer, alone)).jsonObject.getValue(field)
        return when (file) {
            "session.json" -> through(SessionState.serializer())
            "protection.json" -> through(ProtectionState.serializer())
            "model.json" -> through(ModelState.serializer())
            "anki.json" -> through(AnkiState.serializer())
            "calendar.json" -> through(CalendarState.serializer())
            else -> through(LearningState.serializer())
        }
    }

    @Test
    fun `each owner's fields move to its own file as they were, and the runtime keeps the rest`() {
        for (shape in before) {
            val dir = phone(shape)
            val runtime = json(File(dir, "runtime.json"))
            val calendarBefore = json(File(dir, "calendar.json"))
            val stores = Stores(dir, Reverse)
            val after = written(stores)
            for ((file, fields) in RuntimeSplit.FIELDS) {
                val now = after.getValue(file).jsonObject
                // Each field as the runtime held it, as its new store writes it (defaults written out alike).
                for (field in fields) runtime[field]?.let { was ->
                    assertEquals(normal(file, field, was), now.getValue(field), "$shape: $file $field")
                }
            }
            assertEquals(calendarBefore.getValue("written"), after.getValue("calendar.json").jsonObject.getValue("written"), shape)
            // The runtime keeps only its own, in its new shape.
            val left = json(File(dir, "runtime.json"))
            assertEquals(3, Migration.versionOf(left), shape)
            assertTrue(RuntimeSplit.FIELDS.values.flatten().none { it in left }, shape)
            assertEquals(runtime.getValue("heldNotifications"), left.getValue("heldNotifications"), shape)
            assertTrue(File(dir, "runtime.json.v2").isFile || File(dir, "runtime.json.v1").isFile, shape)
            assertTrue(File(dir, "calendar.json.v1").isFile, shape)
        }
    }

    @Test
    fun `run twice, the split leaves what it made`() {
        for (shape in before) {
            val dir = phone(shape)
            Stores(dir, Reverse)
            val names = RuntimeSplit.FIELDS.keys + "runtime.json"
            val once = names.associateWith { File(dir, it).readText() }
            val again = written(Stores(dir, Reverse))
            assertEquals(once, names.associateWith { File(dir, it).readText() }, shape)
            assertEquals(written(Stores(phone(shape), Reverse)).mapValues { it.value.toString() }, again.mapValues { it.value.toString() }, shape)
        }
    }

    @Test
    fun `a stop between the new files and the runtime's trim leaves the new files as they were written`() {
        val dir = phone(before.last())
        val runtime = File(dir, "runtime.json").readText()
        val first = written(Stores(dir, Reverse))
        // The runtime as it was, as though the start had stopped before reading it.
        File(dir, "runtime.json").writeText(runtime)
        assertEquals(first.mapValues { it.value.toString() }, written(Stores(dir, Reverse)).mapValues { it.value.toString() })
    }

    @Test
    fun `a start after one whose runtime's trim wasn't written puts nothing older back`() {
        val dir = phone(before.last())
        val runtime = File(dir, "runtime.json").readText()
        val stores = Stores(dir, Reverse)
        // Changed since the split: a reading the API refused, an unblock till a time.
        runBlocking {
            stores.model.update { it.copy(enrichFailed = it.enrichFailed + ("teams:new" to "h")) }
            stores.protection.update { it.copy(overrideUntil = 12_345L) }
        }
        val newer = written(stores)
        // The runtime as it was before its trim, as though writing the trim had failed.
        File(dir, "runtime.json").writeText(runtime)
        assertEquals(newer.mapValues { it.value.toString() }, written(Stores(dir, Reverse)).mapValues { it.value.toString() })
    }

    @Test
    fun `a calendar file that can't be read is set aside and said, the runtime's fields in a fresh one`() {
        val dir = phone(before.last())
        val bad = "{\"written\":[1,2"
        File(dir, "calendar.json").writeText(bad)
        val runtime = json(File(dir, "runtime.json"))
        val stores = Stores(dir, Reverse)
        val problem = stores.problems.single { it.file == "calendar.json" }
        assertEquals(bad, File(dir, assertNotNull(problem.copy)).readText())
        val calendar = written(stores).getValue("calendar.json").jsonObject
        for (field in RuntimeSplit.FIELDS.getValue("calendar.json")) runtime[field]?.let { assertEquals(normal("calendar.json", field, it), calendar.getValue(field), field) }
        // Said once: the next start finds the calendar whole.
        assertTrue(Stores(dir, Reverse).problems.isEmpty())
    }

    @Test
    fun `a backup from before restores its calibration, answers, spend and slots into the stores they're in now`() {
        val shape = before.last()
        val runtime = JsonStore.json.parseToJsonElement(Fixtures.text("stores/$shape/runtime.json")).jsonObject
        val shapes = Stores(phone(shape), Reverse).shapes
        val parts = JsonObject(
            mapOf(
                "format" to JsonPrimitive(7),
                "versions" to JsonObject(mapOf("runtime.json" to JsonPrimitive(2))),
                "calibration" to runtime.getValue("calibration"), "eventAnswers" to runtime.getValue("eventAnswers"),
                "aiUsage" to runtime.getValue("aiUsage"), "slots" to runtime.getValue("slots"),
            ),
        )
        val upgraded = assertNotNull(Backups.upgraded(parts, shapes))
        for (part in listOf("calibration", "eventAnswers", "aiUsage", "slots")) assertEquals(runtime.getValue(part), upgraded.getValue(part), part)
        val versions = upgraded.getValue("versions").jsonObject
        assertEquals(shapes.mapValues { JsonPrimitive(it.value.version) }.filterKeys { it in RuntimeSplit.FIELDS.keys }, versions.filterKeys { it in RuntimeSplit.FIELDS.keys })
    }

    /** A cipher for the secrets that only reverses the bytes: none of the shapes has any. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
