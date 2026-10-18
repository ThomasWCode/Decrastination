package com.thomaswcode.decrastination

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rules of the app's structure that can be checked by reading its code
 * (docs/plan_v4/02-target-architecture.md §The rules), a rule added as each becomes true: only the
 * notifier posts (Stage 1).
 */
class ArchitectureTest {
    private val main: File = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        ?: error("No src/main/java (run the tests from the repository or the app module)")

    /** Each of the app's source files, by its path under the package root, and its text. */
    private val sources: Map<String, String> = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).invariantSeparatorsPath.removePrefix("com/thomaswcode/decrastination/") to it.readText() }

    /** The files of [sources] that match [pattern], outside [allowed]. */
    private fun outside(pattern: Regex, allowed: Set<String>): List<String> =
        sources.filter { (path, text) -> path !in allowed && pattern.containsMatchIn(text) }.keys.sorted()

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size > 100 && "AppGraph.kt" in sources, "found ${sources.size} files")
    }

    @Test
    fun `only the notifier posts`() {
        // Android's notification manager, called by Notifier's tray alone; Channels makes the channels.
        val stray = outside(Regex("""\bNotificationManager(Compat)?\b"""), setOf("notify/Notifier.kt", "notify/Channels.kt"))
        assertTrue(stray.isEmpty(), "Android's notification manager is called outside notify/Notifier.kt: $stray")
    }
}
