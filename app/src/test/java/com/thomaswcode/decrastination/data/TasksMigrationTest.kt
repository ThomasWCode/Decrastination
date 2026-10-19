package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.ChangeType
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.StepOrigin
import com.thomaswcode.decrastination.core.TaskItem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Tasks and instructions in their second shape (docs/plan_v4/04-data-and-migrations.md §Tasks v2): every
 * earlier shape of the fixtures through the move and the migrations, as at a start, twice; the chat's kept
 * tasks; a backup from before.
 */
class TasksMigrationTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** The shapes before tasks v2. */
    private val before = listOf("2026-10-18", "2026-10-19")

    /** [shape]'s files copied to a fresh folder, as a phone holds them. */
    private fun phone(shape: String): File = Files.createTempDirectory("tasks").toFile().also { dir ->
        dirs += dir
        Fixtures.file("stores/$shape").listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
    }

    private fun tasks(stores: Stores): Map<String, TaskItem> = stores.tasks.value.tasks.associateBy { it.id }

    @Test
    fun `your values move into mine, set by you, from every shape`() {
        for (shape in before) {
            val stores = Stores(phone(shape), Reverse)
            val tasks = tasks(stores)
            val essay = tasks.getValue("teams:as-essay").mine
            assertEquals(150, essay.minutes?.minutes, shape)
            assertEquals(135, essay.minutes?.paced?.from, shape)
            assertNotNull(essay.minutes?.paced?.contentHash, shape)
            assertNotNull(essay.atTime, shape)
            val sheet = tasks.getValue("teams:as-sheet").mine
            assertEquals("Forces worksheet", sheet.title?.value, shape)
            assertNotNull(sheet.from, shape)
            assertEquals(listOf("Questions 1 to 6", "Questions 7 to 12"), sheet.steps?.steps?.map { it.title }, shape)
            val poster = tasks.getValue("powerplanner:pp-poster").mine
            assertNotNull(poster.dueAt, shape)
            assertEquals("2026-10-20", poster.onDay?.value, shape)
            assertNotNull(tasks.getValue("gmail:thr-trip").mine.after, shape)
            assertTrue(tasks.getValue("gmail:thr-news").hidden, shape)
            assertNotNull(tasks.getValue("powerplanner:pp-sheet").mine.sameAs, shape)
            // Every value moved is yours, but the steps of an instruction his card made, his.
            val values = tasks.values.flatMap { t -> with(t.mine) { listOfNotNull(notATask?.by, from?.by, after?.by, dueAt?.by, minutes?.by, onDay?.by, atTime?.by, sameAs?.by, title?.by) } }
            assertTrue(values.isNotEmpty() && values.all { it == By.You }, shape)
            assertEquals(By.Felix, poster.steps?.by, shape)
            assertEquals(By.You, sheet.steps?.by, shape)
            // What the fold of the instructions in use gives, but who set them: the first lay at start labels them.
            val folded = InstructionRules.taskOverrides(stores.instructions.value.applied)
            assertEquals(folded.getValue("teams:as-sheet").steps?.steps, sheet.steps?.steps, shape)
        }
    }

    @Test
    fun `every step gets an id and an origin, your instruction's the ids of the steps it lays`() {
        for (shape in before) {
            val stores = Stores(phone(shape), Reverse)
            val tasks = tasks(stores)
            val steps = tasks.values.flatMap { it.subSteps } + stores.tasks.value.unrewarded.flatMap { it.subSteps }
            assertTrue(steps.isNotEmpty() && steps.all { it.id.length == 8 }, shape)
            assertEquals(steps.size, steps.map { it.id }.toSet().size, "$shape: ids unique")
            fun origins(id: String) = tasks.getValue(id).subSteps.map { it.origin }.distinct()
            assertEquals(listOf(StepOrigin.Reading), origins("teams:as-essay"), shape)
            assertEquals(listOf(StepOrigin.Rules), origins("gmail:thr-trip"), shape)
            assertEquals(listOf(StepOrigin.Yours), origins("teams:as-sheet"), shape)
            assertEquals(listOf(StepOrigin.Felix), origins("powerplanner:pp-poster"), shape)
            assertEquals(listOf(StepOrigin.OwnTask), origins("own:own-1"), shape)
            assertEquals(listOf(StepOrigin.Source), origins("anki:deck-2.3"), shape)
            // A reading's own copy of its steps has the ids the task's carry.
            val essay = tasks.getValue("teams:as-essay")
            assertEquals(essay.subSteps.map { it.id }, essay.enrichment?.subSteps?.map { it.id }, shape)
            // The instructions: every step with an id, the steps laid on a task theirs; Felix's card's his.
            val applied = stores.instructions.value.applied.associateBy { it.id }
            assertTrue(stores.instructions.value.instructions.flatMap { i -> i.changes.flatMap { it.steps.orEmpty() } }.all { it.id.length == 8 }, shape)
            assertEquals(By.Felix, applied.getValue("i-felix-steps").by, shape)
            assertEquals(By.You, applied.getValue("i-your-steps").by, shape)
            assertEquals(applied.getValue("i-your-steps").changes.single { it.type == ChangeType.Steps }.steps!!.map { it.id }, tasks.getValue("teams:as-sheet").subSteps.map { it.id }, shape)
            assertEquals(applied.getValue("i-felix-steps").changes.single { it.type == ChangeType.Steps }.steps!!.map { it.id }, tasks.getValue("powerplanner:pp-poster").subSteps.map { it.id }, shape)
            // And mine's copy of them the same.
            assertEquals(tasks.getValue("teams:as-sheet").subSteps.map { it.id }, tasks.getValue("teams:as-sheet").mine.steps?.steps?.map { it.id }, shape)
            // The ticks as they were, step by step.
            val raw = JsonStore.json.parseToJsonElement(Fixtures.text("stores/$shape/tasks.json")).jsonObject
            val rawSteps = listOf("tasks", "unrewarded").flatMap { (raw[it] as? JsonArray).orEmpty() }.flatMap { (it.jsonObject["subSteps"] as? JsonArray).orEmpty() }
            assertEquals(rawSteps.map { (it.jsonObject["done"] as? JsonPrimitive)?.content == "true" }, steps.map { it.done }, shape)
            assertTrue(steps.any { it.done }, shape)
        }
    }

    @Test
    fun `run twice, the move and the migrations leave what they made, and keep what they came from`() {
        for (shape in before) {
            val dir = phone(shape)
            Stores(dir, Reverse)
            val once = listOf("tasks.json", "instructions.json", "chat.json").associateWith { File(dir, it).readText() }
            assertTrue(File(dir, "tasks.json.v1").isFile && File(dir, "instructions.json.v1").isFile, shape)
            Stores(dir, Reverse)
            assertEquals(once, once.keys.associateWith { File(dir, it).readText() }, shape)
        }
    }

    @Test
    fun `instructions made before the tasks' move keep their ids, which the tasks then take`() {
        // A stop between the two writes: the instructions in their new shape, the tasks still in the old.
        val dir = phone(before.last())
        val tasksBefore = File(dir, "tasks.json").readText()
        Stores(dir, Reverse)
        val instructions = File(dir, "instructions.json").readText()
        File(dir, "tasks.json").writeText(tasksBefore)
        val tasks = tasks(Stores(dir, Reverse))
        assertEquals(instructions, File(dir, "instructions.json").readText())
        val laid = JsonStore.json.parseToJsonElement(instructions).jsonObject["instructions"]!!.jsonArray.map { it.jsonObject }
            .single { (it["id"] as JsonPrimitive).content == "i-your-steps" }["changes"]!!.jsonArray.map { it.jsonObject }.single { (it["type"] as JsonPrimitive).content == "Steps" }["steps"]!!.jsonArray.map { (it.jsonObject["id"] as JsonPrimitive).content }
        assertEquals(laid, tasks.getValue("teams:as-sheet").subSteps.map { it.id })
    }

    @Test
    fun `the tasks the chat keeps for an Undo, and its archive's, move as the tasks do`() {
        for (shape in before) {
            val stores = Stores(phone(shape), Reverse)
            val kept = stores.chat.value.messages.flatMap { m -> m.acts.mapNotNull { it.was } }
            assertTrue(kept.isNotEmpty(), shape)
            assertTrue(kept.all { t -> t.subSteps.all { it.id.isNotEmpty() } }, shape)
        }
        // A part of a state of the old shape, as a backup holds it (its tasks alone): what isn't there left alone.
        val part = JsonObject(mapOf("tasks" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("teams:x"), "userDueAt" to JsonPrimitive(5L), "subSteps" to JsonArray(emptyList())))))))
        val moved = TasksMigration.TASKS.apply(part)
        val task = moved["tasks"]!!.jsonArray.single().jsonObject
        assertNull(task["userDueAt"])
        assertEquals(5L, (task["mine"]!!.jsonObject["dueAt"]!!.jsonObject["value"] as JsonPrimitive).content.toLong())
        assertEquals(setOf("tasks", "version"), moved.keys)
    }

    @Test
    fun `a backup from before the move is restored after it, its instructions Felix's where its chat holds their cards`() {
        val shape = before.last()
        val parts = mapOf(
            "format" to JsonPrimitive(7),
            "instructions" to JsonStore.json.parseToJsonElement(Fixtures.text("stores/$shape/instructions.json")).jsonObject["instructions"]!!,
            "chat" to JsonStore.json.parseToJsonElement(Fixtures.text("stores/$shape/chat.json")),
            "ownTasks" to JsonArray(JsonStore.json.parseToJsonElement(Fixtures.text("stores/$shape/tasks.json")).jsonObject["tasks"]!!.jsonArray.filter { (it.jsonObject["source"] as JsonPrimitive).content == "Own" }),
        )
        val shapes = Stores(phone(shape), Reverse).shapes
        val upgraded = Backups.upgraded(JsonObject(parts), shapes)!!
        val instructions = upgraded["instructions"]!!.jsonArray.map { it.jsonObject }.associateBy { (it["id"] as JsonPrimitive).content }
        assertEquals("Felix", (instructions.getValue("i-felix-steps")["by"] as JsonPrimitive).content)
        assertNull(instructions.getValue("i-your-steps")["by"])
        assertTrue(instructions.values.flatMap { i -> i["changes"]!!.jsonArray.flatMap { c -> (c.jsonObject["steps"] as? JsonArray).orEmpty() } }.all { (it.jsonObject["id"] as JsonPrimitive).content.length == 8 })
        val own = upgraded["ownTasks"]!!.jsonArray.single().jsonObject
        assertTrue(own.keys.none { it.startsWith("user") })
        assertTrue(own["subSteps"]!!.jsonArray.all { (it.jsonObject["origin"] as JsonPrimitive).content == "OwnTask" })
    }

    /** A cipher for the secrets that only reverses the bytes: none of the shapes has any. */
    private object Reverse : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
}
