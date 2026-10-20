package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.By
import com.thomaswcode.decrastination.core.Calibration
import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionRules
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.Mine
import com.thomaswcode.decrastination.core.Notes
import com.thomaswcode.decrastination.core.Reminders
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.SlotState
import com.thomaswcode.decrastination.core.Status
import com.thomaswcode.decrastination.enrich.AiUsage
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
     * that the app doesn't keep ([dropped]), the lists it holds, by path, with their items in all ([lists]),
     * and its [json] (as the file holds it before; as the app writes it after), for what moves between stores.
     */
    private class Read(val file: String, val value: Any?, val problem: JsonStore.Unreadable?, val roundTrip: Boolean, val dropped: List<String> = emptyList(), val lists: Map<String, Int> = emptyMap(), val json: JsonObject? = null)

    /** [file] in [dir] as it is: its text decoded as written, no migration run, read again the same. */
    private fun <T> before(dir: File, file: String, serializer: KSerializer<T>): Read {
        val source = File(dir, file)
        if (!source.isFile) return Read(file, null, null, roundTrip = true)
        val text = source.readText()
        // Its lists read from its JSON, not by this build's types, which drop a field a migration renames.
        val json = runCatching { JsonStore.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val lists = json?.let(::lists).orEmpty()
        val value = runCatching { JsonStore.json.decodeFromString(serializer, text) }.getOrNull()
            ?: return Read(file, null, JsonStore.Unreadable(file, null, kept = false, at = 0), roundTrip = false, lists = lists, json = json)
        return Read(file, value, null, roundTrip = again(serializer, value) == value, lists = lists, json = json)
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
        return Read(store.name, store.value, store.problem, roundTrip = JsonStore.json.decodeFromString(serializer, written) == store.value, dropped = dropped, lists = lists(writtenJson), json = writtenJson as? JsonObject)
    }

    /** The chat's archive as the app reads it, as [after] says: its months, all of them read, as one. */
    private fun after(store: ChatArchiveStore, there: Boolean, migrated: JsonElement?): Read {
        if (!there) return Read(store.name, null, null, roundTrip = true)
        val whole = store.value
        val written = JsonStore.json.encodeToString(ChatArchive.serializer(), whole)
        val writtenJson = JsonStore.json.parseToJsonElement(written)
        val dropped = migrated?.let { dropped(it, writtenJson) }.orEmpty()
        return Read(store.name, whole, store.problems.firstOrNull(), roundTrip = JsonStore.json.decodeFromString(ChatArchive.serializer(), written) == whole, dropped = dropped, lists = lists(writtenJson), json = writtenJson as? JsonObject)
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
            before(files, "session.json", SessionState.serializer()),
            before(files, "protection.json", ProtectionState.serializer()),
            before(files, "model.json", ModelState.serializer()),
            before(files, "anki.json", AnkiState.serializer()),
            before(files, "learning.json", LearningState.serializer()),
            before(files, "log.json", ActivityLog.serializer()),
            before(files, "instructions.json", InstructionState.serializer()),
            before(files, "chat.json", ChatState.serializer()),
            before(files, "chat-archive.json", ChatArchive.serializer()),
            before(files, "school.json", SchoolCalendar.serializer()),
            before(files, "memory.json", MemoryState.serializer()),
            before(files, "felix.json", FelixStore.serializer()),
            before(files, "calendar.json", CalendarState.serializer()),
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
        // The files as the moves between stores leave them at a start, on a copy of their own (inside this one, which goes with it).
        val moved = File(copy, "moved").apply { mkdirs() }
        STORES.map { File(files, it) }.filter { it.isFile }.forEach { it.copyTo(File(moved, it.name)) }
        Stores.MOVES.forEach { it.run(moved) }
        val stores = Stores(copy, NoKeystore)
        fun there(name: String) = File(files, name).isFile || (name in SPLIT && File(files, "runtime.json").isFile)
        // Each file as the moves and its migrations leave it: what the store decodes, and should write back whole.
        fun migrated(name: String): JsonElement? = runCatching {
            val json = JsonStore.json.parseToJsonElement(File(moved, name).readText()) as JsonObject
            stores.shapes.getValue(name).let { Migration.upgrade(json, it.version, it.migrations) }
        }.getOrNull()
        fun <T> after(store: JsonStore<T>, serializer: KSerializer<T>, name: String) = after(store, serializer, there(name), migrated(name))
        val migrated = listOf(
            after(stores.tasks, TaskState.serializer(), "tasks.json"), after(stores.settings, Settings.serializer(), "settings.json"),
            after(stores.runtime, RuntimeState.serializer(), "runtime.json"),
            after(stores.session, SessionState.serializer(), "session.json"), after(stores.protection, ProtectionState.serializer(), "protection.json"),
            after(stores.model, ModelState.serializer(), "model.json"), after(stores.anki, AnkiState.serializer(), "anki.json"),
            after(stores.learning, LearningState.serializer(), "learning.json"), after(stores.log, ActivityLog.serializer(), "log.json"),
            after(stores.instructions, InstructionState.serializer(), "instructions.json"), after(stores.chat, ChatState.serializer(), "chat.json"),
            after(stores.chatArchive, there("chat-archive.json"), migrated("chat-archive.json")),
            after(stores.school, SchoolCalendar.serializer(), "school.json"), after(stores.memory, MemoryState.serializer(), "memory.json"),
            after(stores.felix, FelixStore.serializer(), "felix.json"), after(stores.calendar, CalendarState.serializer(), "calendar.json"),
        )
        val ran = stores.shapes.filter { (name, shape) -> (versions[name] ?: shape.version) < shape.version }.map { (name, shape) -> "$name ${versions[name]} to ${shape.version}" }
        val was = describe(reads)
        val nightly = nightly(root, stores.shapes)
        val now = describe(migrated)
        // Each store's lists before (from its file) and after (from what the app writes): a difference is data moved or
        // lost. Those a migration moves are compared through it instead, in the counts above ([MOVED]).
        val before = keyed(reads)
        val after = keyed(migrated)
        val lists = (before.keys + after.keys).sorted().filter { before[it] != after[it] && it !in MOVED }.map { "  $it: ${before[it] ?: "none"} before, ${after[it] ?: "none"} after" }
        // Nothing of the chat's, nor any of Felix's records, gone: every message and record by its id in the chat after.
        val gone = ids(reads, "chat.json") + records(reads.json("log.json")).mapNotNull { it.string("id") } - ids(migrated, "chat.json")
        val report = buildString {
            appendLine("Migrations: ${ran.ifEmpty { listOf("none to run") }.joinToString("; ")}")
            appendLine("== Before, each file as it is, read by this build's types")
            append(was)
            appendLine("== After, as the app will read them")
            append(now)
            appendLine("== The lists in the files, counted from their JSON: ${reads.sumOf { it.lists.size }} before, ${migrated.sumOf { it.lists.size }} after")
            lists.forEach { appendLine(it) }
            if (gone.isNotEmpty()) appendLine("== ${gone.size} messages or records of Felix's not in the chat after")
            appendLine(if (was == now && lists.isEmpty() && gone.isEmpty()) "== The same before and after" else "== THE COUNTS DIFFER: compare them above")
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
        assertTrue(was == now && lists.isEmpty() && gone.isEmpty(), "The counts differ before and after: see rehearsal.txt")
    }

    private fun List<Read>.json(file: String): JsonObject? = firstOrNull { it.file == file }?.json

    /** Each read's lists by "<file> <path>", the runtime's moved fields' under the file each went to (4.0.13), so a move compares. */
    private fun keyed(reads: List<Read>): Map<String, Int> = reads.flatMap { r ->
        r.lists.map { (path, n) ->
            val file = if (r.file == "runtime.json") RuntimeSplit.FIELDS.entries.firstOrNull { path.substringBefore("[") in it.value }?.key ?: r.file else r.file
            "$file $path" to n
        }
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

    /** The tasks as the tasks' move leaves them (4.0.13): a file of before through its migration, so your values count in both. */
    private fun tasksThrough(reads: List<Read>): TaskState? {
        val read = reads.first { it.file == "tasks.json" }
        val json = read.json ?: return read.value as TaskState?
        if (Migration.versionOf(json) >= 2) return read.value as TaskState?
        return runCatching { JsonStore.json.decodeFromJsonElement(TaskState.serializer(), TasksMigration.TASKS.apply(json)) }.getOrNull()
    }

    /** [mine] with who set each value left aside: the move labels them all yours, the first lay Felix's where his. */
    private fun unlabelled(mine: Mine): Mine = mine.copy(
        notATask = mine.notATask?.copy(by = By.You), from = mine.from?.copy(by = By.You), after = mine.after?.copy(by = By.You), dueAt = mine.dueAt?.copy(by = By.You),
        minutes = mine.minutes?.copy(by = By.You), onDay = mine.onDay?.copy(by = By.You), atTime = mine.atTime?.copy(by = By.You), sameAs = mine.sameAs?.copy(by = By.You),
        steps = mine.steps?.let { it.copy(steps = it.steps.map { s -> s.copy(id = "") }, by = By.You) }, title = mine.title?.copy(by = By.You),
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** The ids of [file]'s messages in [reads], from its JSON. */
    private fun ids(reads: List<Read>, file: String): Set<String> = reads.json(file)?.list("messages").orEmpty().mapNotNull { it.string("id") }.toSet()

    /** Felix's records in the log as its file holds them: none once they're the chat's (4.0.0). */
    private fun records(log: JsonObject?): List<JsonObject> = log?.list("felix").orEmpty()

    /**
     * The conversation and Felix's state in counts that hold through 4.0.0's move of his records and state
     * into the chat (docs/plan_v4/04-data-and-migrations.md §On the real data): his messages, his records
     * not in the chat among them (by id), with and without words; your replies to him (a record's, or
     * a message of yours answering one); the replies he offered and the completions he asked about; the
     * lines under his answers, whatever list they're in; and his state, the runtime's or the chat's.
     */
    private fun conversation(reads: List<Read>): String = buildString {
        val chat = reads.json("chat.json") ?: return@buildString
        val messages = chat.list("messages")
        val records = records(reads.json("log.json"))
        val inChat = messages.mapNotNull { it.string("id") }.toSet()
        val his = messages.filter { it.string("from") == "Claude" } + records.filter { it.string("id") !in inChat }
        val replies = records.count { it.string("replyText") != null }.takeIf { records.isNotEmpty() } ?: messages.count { it.string("from") == "You" && it.string("replyTo") != null }
        fun size(m: JsonObject, key: String) = (m[key] as? JsonArray)?.size ?: 0
        // A record's completions go on its message, in the chat already or made of it.
        val asked = messages.sumOf { size(it, "asks") } + records.sumOf { size(it, "asks") }
        appendLine("Felix's messages: ${his.size}, ${his.count { it.string("text").isNullOrBlank() }} silent; your replies to them $replies; replies offered ${his.sumOf { size(it, "suggestedReplies") + size(it, "replies") }}; completions asked about $asked")
        // A message's lines of before are among its acts and edits now: its lines with nothing to undo after its
        // acts, and what it remembered as lines where it kept no edits.
        appendLine("  acts and lines under his answers ${messages.sumOf { size(it, "acts") + size(it, "done") }}; what he remembered with them ${messages.sumOf { size(it, "memoryEdits").takeIf { n -> n > 0 } ?: size(it, "remembered") }}")
        val runtime = reads.json("runtime.json")
        val state = chat["felix"] as? JsonObject ?: runtime?.get("felix") as? JsonObject ?: JsonObject(emptyMap())
        val asks = (chat["felix"] as? JsonObject)?.let { size(it, "assessLater") } ?: runtime?.let { size(it, "assessLater") } ?: 0
        val draft = ((chat["felix"] as? JsonObject)?.get("checkInDraft") ?: runtime?.get("checkInDraft"))?.let { it !is JsonNull } == true
        appendLine("  his state: questions ${size(state, "questions")}, completions to ask about $asks, check-in under way $draft, tasks seen worked on ${(state["activity"] as? JsonObject)?.size ?: 0}, written ahead ${state["draft"].let { it != null && it !is JsonNull }}, to try again ${state["retry"].let { it != null && it !is JsonNull }}")
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
        // The split's files (4.0.13) aside: each is counted through it, by what it holds, below; read whole, it's checked after.
        val counted = reads.filter { it.file !in SPLIT }
        appendLine("Stores: ${counted.count { it.value != null }} of ${counted.size} there; read whole: ${counted.count { it.value != null && it.problem == null }}; the same written again: ${counted.count { it.value != null && it.roundTrip }}")
        counted.filter { it.problem != null }.forEach { appendLine("  ${it.file} didn't read whole${if (it.problem!!.kept) ", some of it kept" else ""}") }
        counted.filter { !it.roundTrip }.forEach { appendLine("  ${it.file} changed read and written again") }
        counted.filter { it.value == null && it.problem == null }.forEach { appendLine("  ${it.file} isn't there${if (it.file in REQUIRED) ": every phone has it" else ""}") }
        counted.filter { it.dropped.isNotEmpty() }.forEach { appendLine("  ${it.file} holds what the app doesn't keep: ${it.dropped.joinToString()}") }
        val tasks = tasksThrough(reads)
        val instructions = of<InstructionState>("instructions.json")
        if (tasks != null) {
            val all = tasks.tasks
            val open = all.filter { it.status == Status.Open }
            appendLine("Tasks: ${all.size}; open ${open.size}, done ${all.count { it.status == Status.Done }}, missed ${all.count { it.status == Status.Missed }}; by source ${all.groupingBy { it.source.name }.eachCount().toSortedMap()}")
            appendLine("  steps ${all.sumOf { it.subSteps.size }}, ticked ${all.sumOf { t -> t.subSteps.count { it.done } }}; on open tasks ${open.sumOf { it.subSteps.size }}, ticked ${open.sumOf { t -> t.subSteps.count { it.done } }}")
            appendLine("  with values of yours ${all.count { it.mine != Mine.NONE }}; your steps ${all.count { it.mine.steps?.steps != null }} (${all.sumOf { it.mine.steps?.steps.orEmpty().size }} steps); your minutes ${all.count { it.mine.minutes?.minutes != null }} (paced ${all.count { it.mine.minutes?.paced?.from != null }}); pins ${all.count { it.mine.atTime?.value != null }}; days ${all.count { it.mine.onDay != null }}; due dates ${all.count { it.mine.dueAt != null }}; not tasks ${all.count { it.hidden }}")
            appendLine("  read by Claude ${all.count { it.enrichment.let { e -> e != null && e.by != "rules" } }}, by the rules ${all.count { it.enrichment?.by == "rules" }}; with a photo ${all.count { it.photo != null }}; waiting to be rewarded ${tasks.unrewarded.size}")
            appendLine("  ids unique: ${all.map { it.id }.toSet().size == all.size}")
        }
        if (instructions != null) {
            val applied = instructions.applied
            appendLine("Instructions: ${instructions.instructions.size}; in use ${applied.size}; by state ${instructions.instructions.groupingBy { it.state.name }.eachCount().toSortedMap()}")
            if (tasks != null) {
                val ids = tasks.tasks.map { it.id }.toSet()
                val named = applied.flatMap { i -> i.changes.mapNotNull { it.taskId } }.toSet()
                val laid = InstructionRules.taskOverrides(applied)
                appendLine("  tasks they name: ${named.size}, not among the tasks ${named.count { it !in ids }}; tasks whose values aren't their instructions' ${tasks.tasks.count { unlabelled(laid[it.id] ?: Mine.NONE) != unlabelled(it.mine) }}")
            }
        }
        of<ChatState>("chat.json")?.let { chat ->
            appendLine("Chat: one waiting ${chat.unanswered != null}; ids unique ${chat.messages.map { it.id }.toSet().size == chat.messages.size}; with cards ${chat.messages.count { it.proposals.isNotEmpty() }}")
        }
        append(conversation(reads))
        of<ChatArchive>("chat-archive.json")?.let { appendLine("The chat's earlier messages: ${it.messages.size}") }
        of<ActivityLog>("log.json")?.let { log ->
            appendLine("Log: slots ${log.slots.size}, completions ${log.completions.size}, days ${log.days.size}, check-ins ${log.checkIns.size}, reviews ${log.reviews.size}, weeks ${log.weeks.size}")
        }
        reads.json("runtime.json")?.let { runtime ->
            // Each field wherever it is: runtime.json's before the split, its own file's after (RuntimeSplit).
            fun field(name: String): JsonElement? = runtime[name] ?: RuntimeSplit.FIELDS.entries.firstOrNull { name in it.value }?.let { reads.json(it.key)?.get(name) }
            fun size(name: String) = when (val f = field(name)) { is JsonArray -> f.size; is JsonObject -> f.size; else -> 0 }
            val usage = field("aiUsage")?.let { JsonStore.json.decodeFromJsonElement(AiUsage.serializer(), it) } ?: AiUsage()
            val slots = field("slots")?.let { JsonStore.json.decodeFromJsonElement(SlotState.serializer(), it) } ?: SlotState()
            val calibration = field("calibration")?.let { JsonStore.json.decodeFromJsonElement(Calibration.serializer(), it) } ?: Calibration()
            appendLine("Runtime: the month ${usage.month}, spent \$${"%.2f".format(java.util.Locale.UK, usage.spentUsd)} in ${usage.calls} calls; calls kept ${size("calls")}; Anki readings ${size("ankiReadings")}; micro-plans ${size("microPlans")}")
            appendLine("  today's slots ${slots.previous.size}, missed ${slots.missed.size}; changes waiting ${size("pending")}; held notifications ${size("heldNotifications")}; kept slots under way ${size("keeping")}; rewarded ${size("rewarded")}")
            appendLine("  your event answers ${size("eventAnswers")}, asked ${size("eventsAsked")}; calendar events written ${size("calendarWritten")}; calibration ${calibration.multipliers.size} multipliers, ${calibration.timed.size} timed")
        }
        of<MemoryState>("memory.json")?.let { appendLine("Memory: facts ${it.facts.size}, rules ${it.rules.size}") }
        of<FelixStore>("felix.json")?.let { appendLine("Felix's notes ${it.notes.size}, not deleted ${Notes.live(it.notes).size}; reminders ${it.reminders.size}, still to come ${it.reminders.count(Reminders::pending)}") }
        of<SchoolCalendar>("school.json")?.let { appendLine("School: days off ${it.noSchool.size}, half-days ${it.halfDays.size}") }
        of<CalendarState>("calendar.json")?.let { appendLine("Calendar: versions written ${it.written.size}") }
        of<Settings>("settings.json")?.let { appendLine("Settings: armed ${it.armed}, Claude ${it.aiEnabled && it.aiKeyActive}, classes ${it.classMap.size}") }
    }

    /** The newest nightly backup's [report], and its [problem]: why Import wouldn't restore it, or null (none there too). */
    private class Nightly(val report: String, val problem: String? = null)

    /** The newest nightly backup beside the stores, if there's one: read as Import reads it, its parts brought to [shapes], counted. */
    private fun nightly(root: File, shapes: Map<String, Shape>): Nightly {
        val file = root.listFiles { f -> f.name.startsWith("decrastination-") && f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name } ?: return Nightly("Nightly backup: none beside the stores\n")
        // Through Import's own read, capped as it is: a file it refuses as too big isn't one it restores.
        val bytes = file.inputStream().use { Backups.read(it) } ?: return Nightly("Nightly backup ${file.name}: bigger than Import reads (${Backups.MAX_BYTES / (1024 * 1024)} MB)\n", "bigger than Import reads")
        val backup = Backups.decode(bytes.decodeToString(), shapes) ?: return Nightly("Nightly backup ${file.name}: not one this version reads\n", "not one this version reads")
        val problem = Backups.problem(backup)
        return Nightly(buildString {
            appendLine("Nightly backup ${file.name}: format ${backup.format}; problem: ${problem ?: "none"}")
            appendLine("  instructions ${backup.instructions.size}, own tasks ${backup.ownTasks.size}, chat ${backup.chat.messages.size} and ${backup.archive.messages.size} earlier, log slots ${backup.log.slots.size} completions ${backup.log.completions.size}, memory ${backup.memory.facts.size}+${backup.memory.rules.size}")
        }, problem)
    }

    private companion object {
        /** The stores the app writes at its first start: a backup without one isn't a phone's. */
        val REQUIRED = listOf("tasks.json", "settings.json", "runtime.json", "log.json")

        /** Every store's file, the secrets aside (they can't be read off the phone). */
        val STORES = REQUIRED + listOf("instructions.json", "chat.json", "chat-archive.json", "school.json", "memory.json", "calendar.json") + listOf("session.json", "protection.json", "model.json", "anki.json", "learning.json") + listOf("felix.json")

        /** The files runtime.json's split made (4.0.13): there, through it, wherever the runtime was. */
        val SPLIT = setOf("session.json", "protection.json", "model.json", "anki.json", "learning.json")

        /**
         * The lists 4.0.0's move reshapes, by file and path: Felix's records and their lists from the log into
         * the chat's messages, the completions to ask about from the runtime into his state there, a message's
         * lines of before into its acts and edits. Compared through the move in [conversation], not as lists.
         */
        val MOVED = setOf(
            "log.json felix", "log.json felix[].replies", "log.json felix[].asks", "runtime.json assessLater",
            "chat.json messages", "chat.json messages[].suggestedReplies", "chat.json messages[].asks",
            "chat.json messages[].acts", "chat.json messages[].done", "chat.json messages[].memoryEdits", "chat.json messages[].remembered",
            // 4.0.13's: a task's steps of yours into its mine (counted in [describe], your steps).
            "tasks.json tasks[].userSteps", "tasks.json unrewarded[].userSteps",
        )
    }
}
