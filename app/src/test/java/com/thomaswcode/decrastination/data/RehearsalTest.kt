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
 * The rehearsal of a release's migrations on the phone's own data (docs/plan_v4, Stage 0), before it's
 * installed: every store in a phone backup, unpacked by `scripts/rehearse.sh` into the git-ignored
 * `private/rehearsal/<name>/` (its `files/`, and the nightly backup beside them where there is one),
 * read with the code as it is; then only counts and invariants are written, to `rehearsal.txt` there
 * and to the test's output, never anything the stores hold. Skipped without `-PrehearseFrom`; its
 * comparison of a file with what its store writes is tested on its own.
 */
class RehearsalTest {
    /** [file] as read: its [value] (null: not there), whether it read whole, and the keys and list items it holds that the app doesn't keep ([dropped]). */
    private class Read(val file: String, val value: Any?, val problem: JsonStore.Unreadable?, val roundTrip: Boolean, val dropped: List<String> = emptyList())

    private fun <T> read(dir: File, file: String, serializer: KSerializer<T>, default: () -> T): Read {
        val source = File(dir, file)
        if (!source.isFile) return Read(file, null, null, roundTrip = true)
        // Read from a copy: the backup itself is never written, whatever the store does with a bad file;
        // and the copy, the phone's own data, goes once it's read.
        val temp = Files.createTempDirectory("rehearsal").toFile()
        try {
            val copy = File(temp, file)
            source.copyTo(copy)
            val store = JsonStore(copy, serializer, default)
            val value = store.value
            val written = JsonStore.json.encodeToString(serializer, value)
            val again = JsonStore.json.decodeFromString(serializer, written)
            // The file against what the store writes of it: a key in the one and not the other is data the app doesn't keep.
            val dropped = runCatching { dropped(JsonStore.json.parseToJsonElement(source.readText()), JsonStore.json.parseToJsonElement(written)) }.getOrDefault(emptyList())
            return Read(file, value, store.problem, roundTrip = again == value, dropped = dropped)
        } finally {
            temp.deleteRecursively()
        }
    }

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
    fun `the phone's stores read, and what they hold is counted`() {
        val from = System.getProperty("rehearseFrom").orEmpty()
        assumeTrue("no -PrehearseFrom: the rehearsal runs from scripts/rehearse.sh", from.isNotBlank())
        val root = File(from)
        val files = File(root, "files")
        assertTrue(files.isDirectory, "$files isn't a folder: scripts/rehearse.sh unpacks a backup's files/ there")
        val reads = listOf(
            read(files, "tasks.json", TaskState.serializer(), ::TaskState),
            read(files, "settings.json", Settings.serializer(), ::Settings),
            read(files, "runtime.json", RuntimeState.serializer(), ::RuntimeState),
            read(files, "log.json", ActivityLog.serializer(), ::ActivityLog),
            read(files, "instructions.json", InstructionState.serializer(), ::InstructionState),
            read(files, "chat.json", ChatState.serializer(), ::ChatState),
            read(files, "school.json", SchoolCalendar.serializer(), ::SchoolCalendar),
            read(files, "memory.json", MemoryState.serializer(), ::MemoryState),
            read(files, "calendar.json", CalendarVersions.serializer(), ::CalendarVersions),
        )
        val nightly = nightly(root)
        val report = describe(reads) + nightly.report
        File(root, "rehearsal.txt").writeText(report)
        println(report)
        // What stops a release: a backup without a store every phone has (it isn't the phone's data), a store
        // that doesn't read whole, that changes read and written again, or that holds what the app doesn't keep;
        // or a nightly backup beside the stores that this version wouldn't restore.
        val missing = REQUIRED.filter { file -> reads.single { it.file == file }.value == null }
        assertTrue(missing.isEmpty(), "The backup has no $missing, which every phone has: not a rehearsal of its data")
        assertTrue(reads.none { it.problem != null } && reads.all { it.roundTrip }, "A store didn't read whole, or changed read and written again: see rehearsal.txt")
        assertTrue(reads.all { it.dropped.isEmpty() }, "A store holds what the app doesn't keep: see rehearsal.txt")
        assertTrue(nightly.problem == null, "The nightly backup wouldn't be restored (${nightly.problem}): see rehearsal.txt")
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
        reads.filter { it.value == null }.forEach { appendLine("  ${it.file} isn't there${if (it.file in REQUIRED) ": every phone has it" else ""}") }
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

    /** The newest nightly backup beside the stores, if there's one: read as Import reads it, counted. */
    private fun nightly(root: File): Nightly {
        val file = root.listFiles { f -> f.name.startsWith("decrastination-") && f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name } ?: return Nightly("Nightly backup: none beside the stores\n")
        val backup = Backups.decode(file.readText()) ?: return Nightly("Nightly backup ${file.name}: not one this version reads\n", "not one this version reads")
        val problem = Backups.problem(backup)
        return Nightly(buildString {
            appendLine("Nightly backup ${file.name}: format ${backup.format}; problem: ${problem ?: "none"}")
            appendLine("  instructions ${backup.instructions.size}, own tasks ${backup.ownTasks.size}, chat ${backup.chat.messages.size}, log slots ${backup.log.slots.size} completions ${backup.log.completions.size} Felix ${backup.log.felix.size}, memory ${backup.memory.facts.size}+${backup.memory.rules.size}")
        }, problem)
    }

    private companion object {
        /** The stores the app writes at its first start: a backup without one isn't a phone's. */
        val REQUIRED = listOf("tasks.json", "settings.json", "runtime.json", "log.json")
    }
}
