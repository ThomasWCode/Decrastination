package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Enrichments
import com.thomaswcode.decrastination.core.Fact
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.NoSchool
import com.thomaswcode.decrastination.core.Rule
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.FelixPrompts
import com.thomaswcode.decrastination.learn.ReviewInput
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.fail

/**
 * Golden copies of what the model is sent (AR-01): every system prompt and schema, made from fixed
 * inputs, against `src/test/resources/prompts/`. A change to one fails here with its diff, so no
 * prompt changes unseen; when it's meant, `./gradlew testDebugUnitTest -PupdateGolden=true` writes
 * them again, to commit with it. A change to what a reading is made by (the `enrich-` files and the
 * profile) wants [ReadingPrompts.VERSION] raised as well, so the readings made by the old ones are made again.
 */
class GoldenPromptsTest {
    private val dir = listOf(File("src/test"), File("app/src/test")).firstOrNull { it.isDirectory }?.let { File(it, "resources/prompts") }
        ?: error("No src/test (run the tests from the repository or the app module)")

    /** Each golden file's name and what it holds now. */
    private fun rendered(): Map<String, String> = buildMap {
        for (job in Enrichments.Job.entries) {
            put("enrich-${job.name.lowercase()}.txt", ReadingPrompts.system(job, YEAR))
            put("enrich-${job.name.lowercase()}.schema.json", pretty(ReadingPrompts.schemaJson(job)))
        }
        put(PROFILE, Profile.block(SETTINGS, listOf(TASK), null, SCHOOL, MEMORY, NOW, Fixtures.LONDON))
        // Felix's calls' (D-120): the year out of its first line, and nothing else changed.
        put("profile-felix.txt", Profile.block(SETTINGS, listOf(TASK), null, SCHOOL, MEMORY, NOW, Fixtures.LONDON, year = false))
        fun prompt(name: String, system: String, schema: Map<String, Any>) {
            put("$name.txt", system)
            put("$name.schema.json", pretty(json(schema)))
        }
        prompt("chat", ChatPrompts.system(SETTINGS), ChatPrompts.SCHEMA)
        // With finishing emails in Gmail switched off: an email's done is then its source's, as Teams' is.
        put("chat-gmail-off.txt", ChatPrompts.system(SETTINGS.copy(gmailFinishes = false)))
        prompt("instruction", InstructionPrompts.system(YEAR), InstructionPrompts.SCHEMA)
        prompt("photo-check", PhotoChecker.system(YEAR), PhotoChecker.SCHEMA)
        prompt("review", ReviewInput.system(YEAR), ReviewInput.schema())
        prompt("felix", FelixPrompts.system(SETTINGS), FelixPrompts.SCHEMA)
        prompt("micro-plan", MicroPlans.SYSTEM, MicroPlans.SCHEMA)
        prompt("photo-tasks", PhotoTasks.SYSTEM, PhotoTasks.SCHEMA)
        prompt("summary", SummaryPrompts.SYSTEM, SummaryPrompts.SCHEMA)
        prompt("memory-tidy", MemoryTidy.system(YEAR, 10), MemoryTidy.SCHEMA)
    }

    @Test
    fun `every prompt and schema is as its golden copy`() {
        val rendered = rendered().mapValues { it.value + "\n" }
        if (System.getProperty("updateGolden") == "true") {
            dir.mkdirs()
            rendered.forEach { (name, text) -> File(dir, name).writeText(text) }
            dir.listFiles().orEmpty().filter { it.name !in rendered }.forEach { it.delete() }
            return
        }
        val problems = rendered.mapNotNull { (name, text) ->
            val file = File(dir, name)
            when {
                !file.exists() -> "$name: no golden copy"
                read(file) != text -> diff(name, read(file), text)
                else -> null
            }
        } + dir.listFiles().orEmpty().map { it.name }.filter { it !in rendered }.map { "$it: the golden copy of nothing made now" }
        if (problems.isNotEmpty()) {
            val said = problems.joinToString("\n")
            println(said)
            fail("The prompts differ from their golden copies; if that's meant, run with -PupdateGolden=true and commit them (raising ReadingPrompts.VERSION where a reading's changed):\n$said")
        }
    }

    @Test
    fun `the version is raised when what a reading is made by changes`() {
        val root = git(File("."), "rev-parse", "--show-toplevel")?.trim()
        assumeTrue("git isn't available here", root != null)
        val changed = rendered().filterKeys { it.startsWith("enrich-") || it == PROFILE }.filter { (name, text) ->
            // A copy new since HEAD has nothing to be compared with.
            val head = git(File(root!!), "show", "HEAD:" + File(dir, name).canonicalFile.relativeTo(File(root).canonicalFile).invariantSeparatorsPath)
            head != null && head.replace("\r\n", "\n") != text + "\n"
        }.keys
        if (changed.isEmpty()) return
        val prompts = File(root!!, "app/src/main/java/com/thomaswcode/decrastination/enrich/ReadingPrompts.kt").canonicalFile.relativeTo(File(root).canonicalFile).invariantSeparatorsPath
        val before = git(File(root), "show", "HEAD:$prompts")?.let { Regex("""const val VERSION = (\d+)""").find(it)?.groupValues?.get(1)?.toInt() }
        assertNotEquals(before, ReadingPrompts.VERSION, "${changed.sorted().joinToString()} changed since HEAD, where ReadingPrompts.VERSION was $before too: raise it, so the readings by the old prompts are made again")
    }

    @Test
    fun `the diff shows what went and what came, with the lines around`() {
        val before = (1..10).joinToString("\n") { "line $it" }
        val after = before.replace("line 5", "line five")
        assertEquals(
            listOf("--- golden/x.txt", "+++ now/x.txt", "@@ -2,7 +2,7 @@", " line 2", " line 3", " line 4", "-line 5", "+line five", " line 6", " line 7", " line 8"),
            diff("x.txt", before, after).lines().filter { it.isNotEmpty() },
        )
    }

    private fun read(file: File) = file.readText().replace("\r\n", "\n")

    /** [before] to [after] as a unified diff, three lines around each change. */
    private fun diff(name: String, before: String, after: String): String {
        val a = before.split("\n")
        val b = after.split("\n")
        // The longest common run of lines from each point on, then the edits along it.
        val common = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            common[i][j] = if (a[i] == b[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
        }
        data class Line(val mark: Char, val text: String, val i: Int, val j: Int)
        val lines = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> lines += Line(' ', a[i++], i - 1, j++)
                i < a.size && (j == b.size || common[i + 1][j] >= common[i][j + 1]) -> lines += Line('-', a[i++], i - 1, j)
                else -> lines += Line('+', b[j++], i, j - 1)
            }
        }
        val changes = lines.indices.filter { lines[it].mark != ' ' }
        return buildString {
            appendLine("--- golden/$name")
            appendLine("+++ now/$name")
            var k = 0
            while (k < changes.size) {
                val start = maxOf(0, changes[k] - 3)
                var end = minOf(lines.size, changes[k] + 4)
                var next = k + 1
                while (next < changes.size && changes[next] - 3 <= end) end = minOf(lines.size, changes[next++] + 4)
                val hunk = lines.subList(start, end)
                appendLine("@@ -${hunk.first().i + 1},${hunk.count { it.mark != '+' }} +${hunk.first().j + 1},${hunk.count { it.mark != '-' }} @@")
                hunk.forEach { appendLine("${it.mark}${it.text}") }
                k = next
            }
        }
    }

    /** What `git` [args] prints, run in [where]; null when it can't be run or fails. */
    private fun git(where: File, vararg args: String): String? = runCatching {
        // What it says to stderr set aside (Android's ProcessBuilder has no DISCARD).
        val said = File.createTempFile("git", ".txt").apply { deleteOnExit() }
        val process = ProcessBuilder(listOf("git") + args).directory(where).redirectError(said).start()
        val out = process.inputStream.bufferedReader().readText()
        (if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) out else null).also { said.delete() }
    }.getOrNull()

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
        is List<*> -> JsonArray(value.map(::json))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun pretty(element: JsonElement) = PRETTY.encodeToString(JsonElement.serializer(), element)

    private companion object {
        const val YEAR = 12
        const val PROFILE = "profile.txt"
        val PRETTY = Json { prettyPrint = true }
        val NOW = Fixtures.at("2026-10-14T18:00")

        /** Made-up settings, school and memories, so the profile has each of its parts. */
        val SETTINGS = Settings(
            classMap = mapOf("12X/Ph1" to "Physics", "12X/Ma1" to "Maths"),
            profileLines = listOf("I work best straight after school."),
        )
        val SCHOOL = SchoolCalendar(noSchool = listOf(NoSchool("2026-10-22", "2026-10-23", "Training days")))
        val MEMORY = MemoryState(
            facts = listOf(
                Fact("football", "Training is on Tuesdays", NOW),
                // Passing: till a day, and with a task.
                Fact("cold", "Feeling rough this week", NOW, until = Fixtures.at("2026-10-19T00:00")),
                Fact("worksheet", "Can be handed in on paper", NOW, taskId = "teams:g1"),
            ),
            rules = listOf(Rule("Don't message before 8 in the morning", NOW), Rule("Not tonight", NOW, until = Fixtures.at("2026-10-15T07:00"))),
        )
        val TASK = TaskItem(
            id = "teams:g1", source = Source.Teams, sourceId = "g1", title = "Rates of reaction worksheet", detail = "",
            className = "12X/Ch1", dueAt = Fixtures.at("2026-10-16T08:30"), kind = Kind.Homework, firstSeenAt = NOW, lastSeenAt = NOW,
        )
    }
}
