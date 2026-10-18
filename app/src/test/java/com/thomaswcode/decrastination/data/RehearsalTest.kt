package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.core.TaskOverrides
import com.thomaswcode.decrastination.core.overrides
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue

/**
 * The rehearsal of a release's migrations on the phone's own data (docs/plan_v4, Stages 0 and 3), before
 * it's installed: every store in a phone backup, unpacked by `scripts/rehearse.sh` into the git-ignored
 * `private/rehearsal/<name>/` (its `files/`, and the nightly backup beside them where there is one),
 * read as each file is, then as the app will read it, through [Stores] with the migrations there are,
 * on a copy; only counts and invariants are written, before and after, to `rehearsal.txt` there and
 * to the test's output, never anything the stores hold. Skipped without `-PrehearseFrom`; its
 * comparison of a file with what its store writes is tested on its own.
 */
class RehearsalTest {
    /**
     * [file] as read: its [value] (null: not there), whether it read whole, the keys and list items it holds
     * that the app doesn't keep ([dropped]), and the lists it holds, by path, with their items in all ([lists]).
     */
    private class Read(val file: String, val value: Any?, val problem: JsonStore.Unreadable?, val roundTrip: Boolean, val dropped: List<String> = emptyList(), val lists: Map<String, Int> = emptyMap())

    /** [file] in [dir] as it is: its text decoded as written, no migration run, read again the same. */
    private fun <T> before(dir: File, file: String, serializer: KSerializer<T>): Read {
        val source = File(dir, file)
        if (!source.isFile) return Read(file, null, null, roundTrip = true)
        val text = source.readText()
        // Its lists read from its JSON, not by this build's types, which drop a field a migration renames.
        val lists = runCatching { lists(JsonStore.json.parseToJsonElement(text)) }.getOrDefault(emptyMap())
        val value = runCatching { JsonStore.json.decodeFromString(serializer, text) }.getOrNull()
            ?: return Read(file, null, JsonStore.Unreadable(file, null, kept = false, at = 0), roundTrip = false, lists = lists)
        return Read(file, value, null, roundTrip = again(serializer, value) == value, lists = lists)
    }

    /**
     * [store] as the app reads it at start: through its migrations, recovered where it doesn't read whole;
     * with the keys of [migrated] (its file as the migrations leave it) that what it writes lacks.
     */
    private fun <T> after(store: JsonStore<T>, serializer: KSerializer<T>, there: Boolean, migrated: JsonElement?): Read {
        if (!there) return Read(store.name, null, null, roundTrip = true)
        val written = JsonStore.json.encodeToString(serializer, store.value)
        val writtenJson = JsonStore.json.parseToJsonElement(written)
        val dropped = migrated?.let { dropped(it, writtenJson) }.orEmpty()
        return Read(store.name, store.value, store.problem, roundTrip = JsonStore.json.decodeFromString(serializer, written) == store.value, dropped = dropped, lists = lists(writtenJson))
    }

    private fun <T> again(serializer: KSerializer<T>, value: T): T = JsonStore.json.decodeFromString(serializer, JsonStore.json.encodeToString(serializer, value))

    /**
     * The lists [store] holds and those inside their items, by path (an item as `[]`), with how many items
     * each holds in all: what a file holds counted from its JSON, so a migration's data lost shows.
     */
    private fun lists(store: JsonElement): Map<String, Int> = buildMap {
        for ((key, value) in store as? JsonObject ?: return@buildMap) {
            if (value !is JsonArray) continue
            put(key, value.size)
            for (item in value) (item as? JsonObject)?.forEach { (inner, list) -> if (list is JsonArray) merge("$key[].$inner", list.size, Int::plus) }
        }
    }.toSortedMap()

    /**
     * The keys in [file] that [written] lacks, each by its path (a list's items as `[]`), never its value;
     * and a list [written] holds fewer items of, with how many it lost.
     */
    private fun dropped(file: JsonElement, written: JsonElement, path: String = ""): List<String> = when {
        file is JsonObject && written is JsonObject -> file.flatMap { (key, value) -> written[key]?.let { dropped(value, it, "$path.$key") } ?: listOf("$path.$key") }
        // Item by item as far as both go; the file's items past the end of what's written are lost whole.
        file is JsonArray && written is JsonArray -> file.zip(written).flatMap { (a, b) -> dropped(a, b, "$path[]") } +
            if (file.size > written.size) listOf("$path[] ${file.size - written.size} of ${file.size}") else emptyList()
        else -> emptyList()
    }.distinct()

    @Test
    fun `a key, or a list's items, that a store doesn't write back is what it drops`() {
        fun json(text: String) = JsonStore.json.parseToJsonElement(text)
        assertEquals(emptyList<String>(), dropped(json("""{"a":[{"b":1},{"b":2}]}"""), json("""{"a":[{"b":1},{"b":2}]}""")))
        assertEquals(listOf(".a[].c"), dropped(json("""{"a":[{"b":1,"c":2}]}"""), json("""{"a":[{"b":1}]}""")))
        // A record gone from the end of a list: its keys are never compared, so the list's length tells.
        assertEquals(listOf(".a[] 1 of 2"), dropped(json("""{"a":[{"b":1},{"b":2,"c":3}]}"""), json("""{"a":[{"b":1}]}""")))
        assertEquals(listOf(".a[].d[] 2 of 3"), dropped(json("""{"a":[{"d":[1,2,3]}]}"""), json("""{"a":[{"d":[1]}]}""")))
    }

    @Test
    fun `the phone's stores read and migrated, what they hold counted before and after`() {
        val from = System.getProperty("rehearseFrom").orEmpty()
        assumeTrue("no -PrehearseFrom: the rehearsal runs from scripts/rehearse.sh", from.isNotBlank())
        val root = File(from)
        val files = File(root, "files")
        assertTrue(files.isDirectory, "$files isn't a folder: scripts/rehearse.sh unpacks a backup's files/ there")
        val reads = listOf(
            before(files, "tasks.json", TaskState.serializer()),
            before(files, "settings.json", Settings.serializer()),
            before(files, "runtime.json", RuntimeState.serializer()),
            before(files, "log.json", ActivityLog.serializer()),
            before(files, "instructions.json", InstructionState.serializer()),
            before(files, "chat.json", ChatState.serializer()),
            before(files, "school.json", SchoolCalendar.serializer()),
            before(files, "memory.json", MemoryState.serializer()),
            before(files, "calendar.json", CalendarVersions.serializer()),
        )
        // As the app will read them, on a copy of the stores alone (the secrets can't be read off the phone),
        // the copy, the phone's own data, deleted once read.
        val copy = Files.createTempDirectory("rehearsal").toFile()
        try {
            rehearse(root, files, copy, reads)
        } finally {
            copy.deleteRecursively()
        }
    }

    private fun rehearse(root: File, files: File, copy: File, reads: List<Read>) {
        // The stores alone: the folder can hold the debug commands' files too (ai-prompts.json, a list).
        STORES.map { File(files, it) }.filter { it.isFile }.forEach { it.copyTo(File(copy, it.name)) }
        val versions = copy.listFiles().orEmpty().mapNotNull { f -> runCatching { JsonStore.json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()?.let { f.name to Migration.versionOf(it) } }.toMap()
        val stores = Stores(copy, NoKeystore)
        fun there(name: String) = File(files, name).isFile
        // Each file as its migrations leave it: what the store decodes, and should write back whole.
        fun migrated(name: String): JsonElement? = runCatching {
            val json = JsonStore.json.parseToJsonElement(File(files, name).readText()) as JsonObject
            stores.shapes.getValue(name).let { Migration.upgrade(json, it.version, it.migrations) }
        }.getOrNull()
        fun <T> after(store: JsonStore<T>, serializer: KSerializer<T>, name: String) = after(store, serializer, there(name), migrated(name))
        val migrated = listOf(
            after(stores.tasks, TaskState.serializer(), "tasks.json"), after(stores.settings, Settings.serializer(), "settings.json"),
            after(stores.runtime, RuntimeState.serializer(), "runtime.json"), after(stores.log, ActivityLog.serializer(), "log.json"),
            after(stores.instructions, InstructionState.serializer(), "instructions.json"), after(stores.chat, ChatState.serializer(), "chat.json"),
            after(stores.school, SchoolCalendar.serializer(), "school.json"), after(stores.memory, MemoryState.serializer(), "memory.json"),
            after(stores.calendarVersions, CalendarVersions.serializer(), "calendar.json"),
        )
        val ran = stores.shapes.filter { (name, shape) -> (versions[name] ?: shape.version) < shape.version }.map { (name, shape) -> "$name ${versions[name]} to ${shape.version}" }
        val was = describe(reads)
        val nightly = nightly(root, stores.shapes)
        val now = describe(migrated)
        // Each store's lists before (from its file) and after (from what the app writes): a difference is data moved or lost.
        val lists = reads.zip(migrated).flatMap { (b, a) ->
            (b.lists.keys + a.lists.keys).sorted().filter { b.lists[it] != a.lists[it] }.map { "  ${b.file} ${it}: ${b.lists[it] ?: "none"} before, ${a.lists[it] ?: "none"} after" }
        }
        val report = buildString {
            appendLine("Migrations: ${ran.ifEmpty { listOf("none to run") }.joinToString("; ")}")
            appendLine("== Before, each file as it is, read by this build's types")
            append(was)
            appendLine("== After, as the app will read them")
            append(now)
            appendLine("== The lists in the files, counted from their JSON: ${reads.sumOf { it.lists.size }} before, ${migrated.sumOf { it.lists.size }} after")
            lists.forEach { appendLine(it) }
            appendLine(if (was == now && lists.isEmpty()) "== The same before and after" else "== THE COUNTS DIFFER: compare them above")
            append(nightly.report)
        }
        File(root, "rehearsal.txt").writeText(report)
        println(report)
        // What stops a release: a backup without a store every phone has (it isn't the phone's data), or a
        // store that, as the app will read it, doesn't read whole, changes read and written again, or holds
        // what the app doesn't keep; or a nightly backup beside the stores that this version wouldn't restore.
        val missing = REQUIRED.filter { file -> reads.single { it.file == file }.value == null && !File(files, file).isFile }
        assertTrue(missing.isEmpty(), "The backup has no $missing, which every phone has: not a rehearsal of its data")
        assertTrue(migrated.none { it.problem != null } && migrated.all { it.roundTrip }, "A store didn't read whole, or changed read and written again: see rehearsal.txt")
        assertTrue(migrated.all { it.dropped.isEmpty() }, "A store holds what the app doesn't keep: see rehearsal.txt")
        assertTrue(nightly.problem == null, "The nightly backup wouldn't be restored (${nightly.problem}): see rehearsal.txt")
        // And any difference in the counts, before and after (docs/plan_v4/04-data-and-migrations.md §On the real
        // data): a migration that moves, renames or merges data teaches this rehearsal where it goes, so the counts
        // are compared through the move; the check is never loosened to let one through.
        assertTrue(was == now && lists.isEmpty(), "The counts differ before and after: see rehearsal.txt")
    }

    /** No keystore off the phone: the secrets aren't copied, so it's never asked. */
    private object NoKeystore : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = error("no keystore off the phone")
        override fun decrypt(sealed: ByteArray): ByteArray = error("no keystore off the phone")
    }

    /** The counts and invariants of [reads]: numbers and file names only. */
    private fun describe(reads: List<Read>): String = buildString {
        fun <T> of(file: String): T? = reads.first { it.file == file }.value?.let {
            @Suppress("UNCHECKED_CAST")
            it as T
        }
        appendLine("Stores: ${reads.count { it.value != null }} of ${reads.size} there; read whole: ${reads.count { it.value != null && it.problem == null }}; the same written again: ${reads.count { it.value != null && it.roundTrip }}")
        reads.filter { it.problem != null }.forEach { appendLine("  ${it.file} didn't read whole${if (it.problem!!.kept) ", some of it kept" else ""}") }
        reads.filter { !it.roundTrip }.forEach { appendLine("  ${it.file} changed read and written again") }
        reads.filter { it.value == null && it.problem == null }.forEach { appendLine("  ${it.file} isn't there${if (it.file in REQUIRED) ": every phone has it" else ""}") }
        reads.filter { it.dropped.isNotEmpty() }.forEach { appendLine("  ${it.file} holds what the app doesn't keep: ${it.dropped.joinToString()}") }
        val tasks = of<TaskState>("tasks.json")
        val instructions = of<InstructionState>("instructions.json")
        if (tasks != null) {
            val all = tasks.tasks
            val open = all.filter { it.status == Status.Open }
            appendLine("Tasks: ${all.size}; open ${open.size}, done ${all.count { it.status == Status.Done }}, missed ${all.count { it.status == Status.Missed }}; by source ${all.groupingBy { it.source.name }.eachCount().toSortedMap()}")
            appendLine("  steps ${all.sumOf { it.subSteps.size }}, ticked ${all.sumOf { t -> t.subSteps.count { it.done } }}; on open tasks ${open.sumOf { it.subSteps.size }}, ticked ${open.sumOf { t -> t.subSteps.count { it.done } }}")
            appendLine("  with values of yours ${all.count { it.overrides() != TaskOverrides.NONE }}; your steps ${all.count { it.userSteps != null }}; your minutes ${all.count { it.userEffortMin != null }} (paced ${all.count { it.userEffortPacedFrom != null }}); pins ${all.count { it.userAtTime != null }}")
            appendLine("  read by Claude ${all.count { it.enrichment != null && it.enrichment.by != "rules" }}, by the rules ${all.count { it.enrichment?.by == "rules" }}; with a photo ${all.count { it.photo != null }}; waiting to be rewarded ${tasks.unrewarded.size}")
            appendLine("  ids unique: ${all.map { it.id }.toSet().size == all.size}")
        }
        if (instructions != null) {
            val applied = instructions.applied
            appendLine("Instructions: ${instructions.instructions.size}; in use ${applied.size}; by state ${instructions.instructions.groupingBy { it.state.name }.eachCount().toSortedMap()}")
            if (tasks != null) {
                val ids = tasks.tasks.map { it.id }.toSet()
                val named = applied.flatMap { i -> i.changes.mapNotNull { it.taskId } }.toSet()
                val laid = Instructions.taskOverrides(applied)
                appendLine("  tasks they name: ${named.size}, not among the tasks ${named.count { it !in ids }}; tasks whose values aren't their instructions' ${tasks.tasks.count { (laid[it.id] ?: TaskOverrides.NONE) != it.overrides() }}")
            }
        }
        of<ChatState>("chat.json")?.let { chat ->
            appendLine("Chat: ${chat.messages.size} messages, his ${chat.messages.count { it.from == Speaker.Claude }}, yours ${chat.messages.count { it.from == Speaker.You }}; one waiting ${chat.unanswered != null}; ids unique ${chat.messages.map { it.id }.toSet().size == chat.messages.size}")
            appendLine("  with cards ${chat.messages.count { it.proposals.isNotEmpty() }}, acts ${chat.messages.sumOf { it.acts.size }}, memory edits ${chat.messages.sumOf { it.memoryEdits.size }}; of before: remembered ${chat.messages.count { it.remembered.isNotEmpty() }}, done ${chat.messages.count { it.done.isNotEmpty() }}, reason ${chat.messages.count { it.reason != null }}")
        }
        of<ActivityLog>("log.json")?.let { log ->
            appendLine("Log: slots ${log.slots.size}, completions ${log.completions.size}, days ${log.days.size}, check-ins ${log.checkIns.size}, reviews ${log.reviews.size}, weeks ${log.weeks.size}")
            appendLine("  Felix's records ${log.felix.size} (replied to ${log.felix.count { it.replyText != null }}, silent ${log.felix.count { it.text.isBlank() }}); by reason ${log.felix.groupingBy { it.reason }.eachCount().toSortedMap()}")
        }
        of<RuntimeState>("runtime.json")?.let { runtime ->
            appendLine("Runtime: the month ${runtime.aiUsage.month}, spent \$${"%.2f".format(java.util.Locale.UK, runtime.aiUsage.spentUsd)} in ${runtime.aiUsage.calls} calls; calls kept ${runtime.calls.size}; Anki readings ${runtime.ankiReadings.size}; micro-plans ${runtime.microPlans.size}")
            appendLine("  today's slots ${runtime.slots.previous.size}, missed ${runtime.slots.missed.size}; changes waiting ${runtime.pending.size}; held notifications ${runtime.heldNotifications.size}; Felix's questions ${runtime.felix.questions.size}; check-in draft ${runtime.checkInDraft != null}")
        }
        of<MemoryState>("memory.json")?.let { appendLine("Memory: facts ${it.facts.size}, rules ${it.rules.size}") }
        of<SchoolCalendar>("school.json")?.let { appendLine("School: days off ${it.noSchool.size}, half-days ${it.halfDays.size}") }
        of<CalendarVersions>("calendar.json")?.let { appendLine("Calendar: versions written ${it.written.size}") }
        of<Settings>("settings.json")?.let { appendLine("Settings: armed ${it.armed}, Claude ${it.aiEnabled && it.aiKeyActive}, classes ${it.classMap.size}") }
    }

    /** The newest nightly backup's [report], and its [problem]: why Import wouldn't restore it, or null (none there too). */
    private class Nightly(val report: String, val problem: String? = null)

    /** The newest nightly backup beside the stores, if there's one: read as Import reads it, its parts brought to [shapes], counted. */
    private fun nightly(root: File, shapes: Map<String, Shape>): Nightly {
        val file = root.listFiles { f -> f.name.startsWith("decrastination-") && f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name } ?: return Nightly("Nightly backup: none beside the stores\n")
        val backup = Backups.decode(file.readText(), shapes) ?: return Nightly("Nightly backup ${file.name}: not one this version reads\n", "not one this version reads")
        val problem = Backups.problem(backup)
        return Nightly(buildString {
            appendLine("Nightly backup ${file.name}: format ${backup.format}; problem: ${problem ?: "none"}")
            appendLine("  instructions ${backup.instructions.size}, own tasks ${backup.ownTasks.size}, chat ${backup.chat.messages.size}, log slots ${backup.log.slots.size} completions ${backup.log.completions.size} Felix ${backup.log.felix.size}, memory ${backup.memory.facts.size}+${backup.memory.rules.size}")
        }, problem)
    }

    private companion object {
        /** The stores the app writes at its first start: a backup without one isn't a phone's. */
        val REQUIRED = listOf("tasks.json", "settings.json", "runtime.json", "log.json")

        /** Every store's file, the secrets aside (they can't be read off the phone). */
        val STORES = REQUIRED + listOf("instructions.json", "chat.json", "school.json", "memory.json", "calendar.json")
    }
}
