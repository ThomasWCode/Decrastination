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
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import org.junit.Assume.assumeTrue

/**
 * The rehearsal of a release's migrations on the phone's own data (docs/plan_v4, Stage 0), before it's
 * installed: every store in a phone backup, unpacked by `scripts/rehearse.sh` into the git-ignored
 * `private/rehearsal/<name>/` (its `files/`, and the nightly backup beside them where there is one),
 * read with the code as it is; then only counts and invariants are written, to `rehearsal.txt` there
 * and to the test's output, never anything the stores hold. Skipped without `-PrehearseFrom`.
 */
class RehearsalTest {
    private class Read(val file: String, val value: Any?, val problem: JsonStore.Unreadable?, val roundTrip: Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(dir: File, file: String, serializer: KSerializer<T>, default: () -> T): Read {
        val source = File(dir, file)
        if (!source.isFile) return Read(file, null, null, roundTrip = true)
        // Read from a copy: the backup itself is never written, whatever the store does with a bad file.
        val copy = File(Files.createTempDirectory("rehearsal").toFile(), file)
        source.copyTo(copy)
        val store = JsonStore(copy, serializer, default)
        val value = store.value
        val again = JsonStore.json.decodeFromString(serializer, JsonStore.json.encodeToString(serializer, value))
        return Read(file, value, store.problem, roundTrip = again == value)
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
        val report = describe(reads) + nightly(root)
        File(root, "rehearsal.txt").writeText(report)
        println(report)
        // What stops a release: a store that doesn't read whole, or loses something read and written again.
        assertTrue(reads.none { it.problem != null } && reads.all { it.roundTrip }, "A store didn't read whole, or changed read and written again: see rehearsal.txt")
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

    /** The newest nightly backup beside the stores, if there's one: read as Import reads it, counted. */
    private fun nightly(root: File): String {
        val file = root.listFiles { f -> f.name.startsWith("decrastination-") && f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name } ?: return "Nightly backup: none beside the stores\n"
        val backup = Backups.decode(file.readText()) ?: return "Nightly backup ${file.name}: not one this version reads\n"
        return buildString {
            appendLine("Nightly backup ${file.name}: format ${backup.format}; problem: ${Backups.problem(backup) ?: "none"}")
            appendLine("  instructions ${backup.instructions.size}, own tasks ${backup.ownTasks.size}, chat ${backup.chat.messages.size}, log slots ${backup.log.slots.size} completions ${backup.log.completions.size} Felix ${backup.log.felix.size}, memory ${backup.memory.facts.size}+${backup.memory.rules.size}")
        }
    }
}
