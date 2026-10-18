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
 * The rehearsal of a release's migrations on the phone's own data (docs/plan_v4, Stages 0 and 3), before
 * it's installed: every store in a phone backup, unpacked by `scripts/rehearse.sh` into the git-ignored
 * `private/rehearsal/<name>/` (its `files/`, and the nightly backup beside them where there is one),
 * read as each file is, then as the app will read it, through [Stores] with the migrations there are,
 * on a copy; only counts and invariants are written, before and after, to `rehearsal.txt` there and
 * to the test's output, never anything the stores hold. Skipped without `-PrehearseFrom`.
 */
class RehearsalTest {
    private class Read(val file: String, val value: Any?, val problem: JsonStore.Unreadable?, val roundTrip: Boolean)

    /** [file] in [dir] as it is: its text decoded as written, no migration run, read again the same. */
    private fun <T> before(dir: File, file: String, serializer: KSerializer<T>): Read {
        val source = File(dir, file)
        if (!source.isFile) return Read(file, null, null, roundTrip = true)
        val value = runCatching { JsonStore.json.decodeFromString(serializer, source.readText()) }.getOrNull()
            ?: return Read(file, null, JsonStore.Unreadable(file, null, kept = false, at = 0), roundTrip = false)
        return Read(file, value, null, roundTrip = again(serializer, value) == value)
    }

    /** [store] as the app reads it at start: through its migrations, recovered where it doesn't read whole. */
    private fun <T> after(store: JsonStore<T>, serializer: KSerializer<T>, there: Boolean): Read =
        if (!there) Read(store.name, null, null, roundTrip = true) else Read(store.name, store.value, store.problem, roundTrip = again(serializer, store.value) == store.value)

    private fun <T> again(serializer: KSerializer<T>, value: T): T = JsonStore.json.decodeFromString(serializer, JsonStore.json.encodeToString(serializer, value))

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
        // As the app will read them, on a copy of the stores alone (the secrets can't be read off the phone).
        val copy = Files.createTempDirectory("rehearsal").toFile()
        files.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { it.copyTo(File(copy, it.name)) }
        val versions = copy.listFiles().orEmpty().associate { it.name to Migration.versionOf(JsonStore.json.parseToJsonElement(it.readText()) as kotlinx.serialization.json.JsonObject) }
        val stores = Stores(copy, NoKeystore)
        fun there(name: String) = File(files, name).isFile
        val migrated = listOf(
            after(stores.tasks, TaskState.serializer(), there("tasks.json")), after(stores.settings, Settings.serializer(), there("settings.json")),
            after(stores.runtime, RuntimeState.serializer(), there("runtime.json")), after(stores.log, ActivityLog.serializer(), there("log.json")),
            after(stores.instructions, InstructionState.serializer(), there("instructions.json")), after(stores.chat, ChatState.serializer(), there("chat.json")),
            after(stores.school, SchoolCalendar.serializer(), there("school.json")), after(stores.memory, MemoryState.serializer(), there("memory.json")),
            after(stores.calendarVersions, CalendarVersions.serializer(), there("calendar.json")),
        )
        val ran = stores.shapes.filter { (name, shape) -> (versions[name] ?: shape.version) < shape.version }.map { (name, shape) -> "$name ${versions[name]} to ${shape.version}" }
        val was = describe(reads)
        val now = describe(migrated)
        val report = buildString {
            appendLine("Migrations: ${ran.ifEmpty { listOf("none to run") }.joinToString("; ")}")
            appendLine("== Before, each file as it is")
            append(was)
            appendLine("== After, as the app will read them")
            append(now)
            appendLine(if (was == now) "== The same before and after" else "== THE COUNTS DIFFER: compare them above")
            append(nightly(root, stores.shapes))
        }
        File(root, "rehearsal.txt").writeText(report)
        println(report)
        // What stops a release: a store that doesn't read whole as the app will read it, or loses something read and written again.
        assertTrue(migrated.none { it.problem != null } && migrated.all { it.roundTrip }, "A store didn't read whole, or changed read and written again: see rehearsal.txt")
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

    /** The newest nightly backup beside the stores, if there's one: read as Import reads it, its parts brought to [shapes], counted. */
    private fun nightly(root: File, shapes: Map<String, Shape>): String {
        val file = root.listFiles { f -> f.name.startsWith("decrastination-") && f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name } ?: return "Nightly backup: none beside the stores\n"
        val backup = Backups.decode(file.readText(), shapes) ?: return "Nightly backup ${file.name}: not one this version reads\n"
        return buildString {
            appendLine("Nightly backup ${file.name}: format ${backup.format}; problem: ${Backups.problem(backup) ?: "none"}")
            appendLine("  instructions ${backup.instructions.size}, own tasks ${backup.ownTasks.size}, chat ${backup.chat.messages.size}, log slots ${backup.log.slots.size} completions ${backup.log.completions.size} Felix ${backup.log.felix.size}, memory ${backup.memory.facts.size}+${backup.memory.rules.size}")
        }
    }
}
