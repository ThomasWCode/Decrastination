package com.thomaswcode.decrastination

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rules of the app's structure that can be checked by reading its code
 * (docs/plan_v4/architecture.md §The rules): only the notifier posts; only the gateway
 * builds a Claude client; `core` free of Android and of the app, a module of its own; screens call
 * services, never a store's update; each store updated only by the files named for it, its owner
 * first; and the one way round a store's owner, [com.thomaswcode.decrastination.data.Stores.replace],
 * for a restore or a reset, called by the backup service alone.
 */
class ArchitectureTest {
    /** Where the app's own code is: :app's, and :engine's, what's Android-free of it (run from the repository or the app module). */
    private val roots: List<File> = listOf("src/main/java", "app/src/main/java", "../engine/src/main/kotlin", "engine/src/main/kotlin").map(::File).filter { it.isDirectory }

    /** Each of those source files, by its path under the package root, and its text. */
    private val sources: Map<String, String> = roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath.removePrefix("com/thomaswcode/decrastination/") to it.readText() }.toList()
    }.toMap()

    /** The files of [sources] that match [pattern], outside [allowed]. */
    private fun outside(pattern: Regex, allowed: Set<String>): List<String> =
        sources.filter { (path, text) -> path !in allowed && pattern.containsMatchIn(text) }.keys.sorted()

    @Test
    fun `the sources are found`() {
        assertTrue(sources.size > 100 && "AppGraph.kt" in sources, "found ${sources.size} files")
    }

    @Test
    fun `only the notifier posts`() {
        // Android's notification manager, called by Notifier's tray alone; AndroidChannels makes the channels.
        val stray = outside(Regex("""\bNotificationManager(Compat)?\b"""), setOf("notify/Notifier.kt", "notify/AndroidChannels.kt"))
        assertTrue(stray.isEmpty(), "Android's notification manager is called outside notify/Notifier.kt: $stray")
    }

    @Test
    fun `only the gateway builds a Claude client`() {
        // ModelGateway.build is the one place: the readers take the client they're given.
        val stray = outside(Regex("""\bAnthropicOkHttpClient\b"""), setOf("enrich/ModelGateway.kt"))
        assertTrue(stray.isEmpty(), "A Claude client is built outside enrich/ModelGateway.kt: $stray")
    }

    @Test
    fun `core is a module free of Android and of the app`() {
        // The compiler holds it: :core is plain Kotlin and depends on neither, so nothing in it can
        // reach them. What's checked here is that it stays so: none of core's files, by their folder or
        // their package, back in the app, where the compiler wouldn't hold them (and core would be split
        // across two modules); and nothing of Android or another module in its build but Android's lint,
        // which checks core's code as the app's (lintDebug).
        val strays = sources.filter { (path, text) -> path.startsWith("core/") || Regex("""(?m)^package com\.thomaswcode\.decrastination\.core\b""").containsMatchIn(text) }.keys
        assertTrue(strays.isEmpty(), "core's files are in the app, not in :core: $strays")
        val build = listOf(File("../core/build.gradle.kts"), File("core/build.gradle.kts")).first { it.isFile }
        val code = build.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n").replace("libs.plugins.android.lint", "")
        val named = Regex("""android|project\(""").findAll(code).map { it.value }.toList()
        assertTrue(named.isEmpty(), "core's build names Android or another module: $named")
    }

    @Test
    fun `the engine's build names no Android and no module but core`() {
        // As core's is held: what the server runs has nothing of Android, but the lint that checks it as the app's code.
        val build = listOf(File("../engine/build.gradle.kts"), File("engine/build.gradle.kts")).first { it.isFile }
        val code = build.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            .replace("libs.plugins.android.lint", "").replace("""project(":core")""", "")
        val named = Regex("""android|project\(""").findAll(code).map { it.value }.toList()
        assertTrue(named.isEmpty(), "the engine's build names Android or another module: $named")
    }

    @Test
    fun `screens call services, never a store's update`() {
        // A screen reads a store's flow and calls its owner's functions (§The rules, 4): what it changes goes through them.
        val stray = sources.filter { (path, text) -> path.startsWith("ui/") && UPDATE.containsMatchIn(text) }.keys.sorted()
        assertTrue(stray.isEmpty(), "A screen updates a store itself: $stray")
    }

    @Test
    fun `each store is written only where it's named`() {
        // Every update of a store, by the file it's in, against WRITERS; the debug commands are the PC's hands on them.
        val found = sources.filterKeys { !it.startsWith("debug/") }.flatMap { (path, text) -> UPDATE.findAll(text).map { it.groupValues[1] to path } }.toSet()
        val stray = found.filter { (store, path) -> path !in WRITERS.getValue(store) }.map { (store, path) -> "$path updates $store" }.sorted()
        assertTrue(stray.isEmpty(), "A store updated where it isn't named (call its owner, or name the file in WRITERS with why): $stray")
        // And each named file still does, so the list says what's so.
        val gone = WRITERS.flatMap { (store, paths) -> paths.filter { (store to it) !in found }.map { "$it no longer updates $store" } }.sorted()
        assertTrue(gone.isEmpty(), "Named in WRITERS, but not a writer: $gone")
    }

    @Test
    fun `the app holds no owner, no prompt and no migration`() {
        // What decides lives in :engine, where the server runs it too (D-125): what's left here touches the device.
        val app = roots.filter { it.path.endsWith("src/main/java") || it.invariantSeparatorsPath.endsWith("src/main/java") }
        val here = app.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(root).invariantSeparatorsPath.removePrefix("com/thomaswcode/decrastination/") }.toList()
        }.toSet()
        assertTrue("AppGraph.kt" in here, "found ${here.size} files of the app's")
        val decides = here.filter { Regex("""(Prompts|Answers|Migration|Stores|JsonStore)\.kt$""").containsMatchIn(it) }
        assertTrue(decides.isEmpty(), "A prompt, an answer's parser, a migration or the stores are in the app, not in :engine: $decides")
        val writers = sources.filterKeys { it in here && !it.startsWith("debug/") }.filterValues { UPDATE.containsMatchIn(it) }.keys
        val stray = writers - PHONE_WRITERS
        assertTrue(stray.isEmpty(), "A store is updated in the app outside PHONE_WRITERS (its owner belongs in :engine): $stray")
    }

    @Test
    fun `a screen's change goes through the commands, never to an owner itself`() {
        // Each is an action (D-140), so that one change is the same on the phone and on the server. AppGraph wires the owners together,
        // and the debug commands are a PC's.
        val direct = Regex("""\.(saveOwnTask|finishOwnTask|reopenOwnTask|deleteOwnTask|addOwnChange|laterToday|takeBackQuick|applyInstruction|deleteInstruction|rereadInstruction|notSame|addSchoolDays|deleteSchoolDays|keepSlot|undoKeep|tickBlock|startTimer|stopTimer|replyFromNotification|withdrawQuestion|cancelAnswer|markAnswered|unmarkAnswered|clearChat|applyProposal|undoProposal|discardProposal|undoAct|undoMemory|remember|deleteRule|deleteFact|addNote|deleteNote|endCheckInFlow|noteActivity|useParentCode|applyWithCode|cancelChange|recordCheckIn|forgetCalibration|undoReviewChange|undoTidy)\(|protection\.changeSettings|conversation\.(ask|rate|read)\(""")
        val app = roots.filter { it.invariantSeparatorsPath.endsWith("src/main/java") }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath.removePrefix("com/thomaswcode/decrastination/") }.toList()
        }.toSet()
        val stray = sources.filterKeys { it in app && it != "AppGraph.kt" && !it.startsWith("debug/") }.filterValues { direct.containsMatchIn(it) }.keys
        assertTrue(stray.isEmpty(), "A screen calls an owner's change, not a command: $stray")
    }

    @Test
    fun `only the backup service replaces a store whole`() {
        // Stores.replace goes round a store's owner: for a backup restored or a reset, and nothing else.
        val replaces = Regex("""\bstores\.replace\(""")
        val stray = outside(replaces, setOf("data/BackupService.kt"))
        assertTrue(stray.isEmpty(), "Stores.replace is called outside data/BackupService.kt: $stray")
        assertTrue(replaces.containsMatchIn(sources.getValue("data/BackupService.kt")))
    }

    private companion object {
        /** An update of a store, by the name every file holds it by: `tasks.update {`, `graph.stores.model.update(`. */
        val UPDATE = Regex("""(?<![\w.])(?:graph\.(?:stores\.)?|stores\.)?(tasks|settings|runtime|session|protection|device|model|anki|learning|log|instructions|chat|school|memory|felix|calendar|sync|outbox|writes)\.update\s*[({]""")

        /** What in the app updates a store: the phone's own runtime, the graph's start, and the watchdog's findings. */
        val PHONE_WRITERS = setOf("notify/Notifier.kt", "AppGraph.kt", "block/FocusService.kt", "learn/DailyAlarms.kt", "protect/Watchdog.kt")

        /**
         * The files that update each store (§The rules, 2: its owner, first). Where there's more than one,
         * each writes its own part of it: the tasks' sources (Sync), readings, your values and own tasks
         * laid on them, ticks and kept pieces (Focus), photos, and Felix's acts; the chat's cards from an
         * instruction or a task of your own; the half-minute state from the plan and the alarms; the
         * runtime's counts, daily runs and findings, which have no owner of their own.
         */
        val WRITERS: Map<String, Set<String>> = mapOf(
            "tasks" to setOf("sync/Sync.kt", "sync/Syncer.kt", "enrich/Readings.kt", "tasks/Instructions.kt", "tasks/OwnTasks.kt", "block/Focus.kt", "block/Photos.kt", "felix/Conversation.kt", "felix/Looks.kt"),
            "settings" to setOf("protect/Protection.kt"),
            "runtime" to setOf("notify/Notifier.kt", "AppGraph.kt", "block/FocusService.kt", "learn/Briefing.kt", "learn/DailyAlarms.kt"),
            "session" to setOf("block/Focus.kt", "block/Slots.kt", "plan/Planning.kt", "tasks/Instructions.kt"),
            "protection" to setOf("protect/Protection.kt"),
            "device" to setOf("protect/Protection.kt", "protect/Watchdog.kt"),
            "model" to setOf("enrich/ModelGateway.kt", "plan/Ahead.kt"),
            "anki" to setOf("block/AnkiWatch.kt"),
            "learning" to setOf("learn/Learning.kt", "block/AnkiWatch.kt", "AppGraph.kt"),
            "log" to setOf("learn/Learning.kt", "learn/Briefing.kt", "block/Focus.kt", "protect/Watchdog.kt"),
            "instructions" to setOf("tasks/Instructions.kt", "felix/Conversation.kt"),
            "chat" to setOf("felix/Conversation.kt", "felix/Looks.kt", "felix/Outbox.kt", "tasks/Instructions.kt", "tasks/OwnTasks.kt"),
            "school" to setOf("tasks/Instructions.kt", "felix/Conversation.kt"),
            "memory" to setOf("felix/Conversation.kt", "learn/Learning.kt"),
            "felix" to setOf("felix/Conversation.kt", "felix/Looks.kt"),
            "calendar" to setOf("calendar/CalendarWriter.kt", "sync/Sync.kt"),
            "sync" to setOf("sync/Replica.kt"),
            "outbox" to setOf("sync/protocol/Command.kt", "sync/Replica.kt"),
            "writes" to setOf("sync/GmailFinishes.kt"),
        )
    }
}
