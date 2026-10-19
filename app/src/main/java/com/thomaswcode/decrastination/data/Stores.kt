package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Every store the app keeps, made in one place (docs/plan_v4/02-target-architecture.md §Stores): each
 * JSON file with its state's type, its version and its migrations, and the secrets beside them. What
 * couldn't be read whole at this start is said ([problems]); each store says what a backup keeps of it
 * and which resets change it ([all]), so none can be added and left out of either; and [replace] is the
 * one way round a store's owner, for a restore or a reset ([BackupService]).
 */
class Stores(
    private val dir: File,
    cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether settings that can't be read at all start armed: while the device admin is on, as only arming turns it on (IMP-P1-003). */
    armedIfUnreadable: () -> Boolean = { false },
    /** Fields moved between stores, run before any is read ([Move]). */
    moves: List<Move> = MOVES,
) {
    init {
        moves.forEach { it.run(dir) }
    }

    private fun <T> store(name: String, serializer: kotlinx.serialization.KSerializer<T>, default: () -> T, unreadable: () -> T = default, migrations: List<Migration> = emptyList(), onWrite: (T) -> T = { it }) =
        JsonStore(File(dir, name), serializer, default, unreadable = unreadable, clock = clock, migrations = migrations, onWrite = onWrite)

    val tasks = store("tasks.json", TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS), onWrite = TaskState::withStepIds)

    // A file that can't be read at all starts armed while the device admin is on, as only arming
    // turns it on: a bad file never disarms protection (IMP-P1-003).
    val settings = store("settings.json", Settings.serializer(), ::Settings, unreadable = { Settings(armed = armedIfUnreadable()) })
    val secrets = SecretStore(File(dir, "secrets.bin"), cipher, clock)
    val runtime = store("runtime.json", RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME))
    val log = store("log.json", ActivityLog.serializer(), ::ActivityLog, migrations = listOf(ChatMigration.LOG))

    /** Your instructions about tasks, events and days, and what Claude read them as. */
    val instructions = store("instructions.json", InstructionState.serializer(), ::InstructionState, migrations = listOf(TasksMigration.INSTRUCTIONS), onWrite = InstructionState::withStepIds)

    /** Your chat with Felix, the live window of it: every message of his and yours, what became of his cards, and his state. */
    val chat = store("chat.json", ChatState.serializer(), ::ChatState, migrations = listOf(ChatMigration.CHAT, TasksMigration.CHAT))

    /**
     * The chat's messages past its window, oldest first, a month a file: each read only when it's wanted
     * (scrolling back, a reply to one of them, the window's next move, a backup, the export), not at a start.
     */
    val chatArchive = ChatArchiveStore(dir, clock)

    /**
     * 4.0.3's one archive file, still here where the split couldn't read it ([ChatArchiveStore.SPLIT]):
     * read as any store is, so it's set aside as `chat-archive.json.unreadable-<when>` and said at this
     * start ([problems]), and what could be kept of it goes into its months; let go once its copy is made.
     */
    private val unsplit: JsonStore.Unreadable? = File(dir, ChatArchiveStore.NAME).takeIf { it.isFile }?.let { file ->
        val whole = JsonStore(file, ChatArchive.serializer(), ::ChatArchive, clock = clock, migrations = ChatArchiveStore.MIGRATIONS)
        runBlocking {
            chatArchive.add(whole.value.messages)
            // Let go only where it was read whole, or read and set aside with what was kept written: not where
            // its read failed (it may be good) or its copy did (it's all there is of it).
            if (whole.problem == null || whole.keepRecovered()) file.delete()
        }
        whole.problem
    }

    /** Days with no school and half-days (PL-16), told to the chat or added in Settings. */
    val school = store("school.json", SchoolCalendar.serializer(), ::SchoolCalendar)

    /** What Felix remembers (AI-21): facts, and standing instructions. */
    val memory = store("memory.json", MemoryState.serializer(), ::MemoryState)

    /** The versions of its events the app has written to calendars, so a copy of one is known. */
    val calendarVersions = store("calendar.json", CalendarVersions.serializer(), ::CalendarVersions)

    private val json = listOf(tasks, settings, runtime, log, instructions, chat, school, memory, calendarVersions)

    /**
     * A store, what a backup keeps of it ([backup]; none, and [why] says why), and the resets that
     * change it themselves ([resets]; what they set going, such as your changes laid on the tasks
     * again, aside).
     */
    class Kept(val file: String, val backup: String?, val why: String? = null, val resets: Set<Reset> = emptySet()) {
        init {
            require((backup == null) != (why == null)) { "$file: what a backup keeps of it, or why none" }
        }
    }

    /** Every store, the secrets among them, as [Kept] says. */
    val all: List<Kept> = listOf(
        Kept("tasks.json", backup = "your own tasks, which no source gives back"),
        Kept("settings.json", backup = "the settings as asked for, a change still waiting among them", resets = setOf(Reset.Memories)),
        Kept("secrets.bin", backup = null, why = "passwords and keys stay on this phone (D-78)"),
        Kept("runtime.json", backup = "the calibration, your calendar answers, Claude's month and today's slots", resets = setOf(Reset.Stats, Reset.Calibration)),
        Kept("log.json", backup = "all of it", resets = setOf(Reset.Stats)),
        Kept("instructions.json", backup = "the instructions in use", resets = setOf(Reset.Instructions)),
        Kept("chat.json", backup = "all of it, Felix's state with it", resets = setOf(Reset.Chat)),
        Kept("chat-archive.json", backup = "the chat's earlier messages, while the backup has room for them, the oldest left out first", resets = setOf(Reset.Chat)),
        Kept("school.json", backup = "all of it"),
        Kept("memory.json", backup = "all of it", resets = setOf(Reset.Memories)),
        Kept("calendar.json", backup = null, why = "the versions this phone wrote to its calendars mean nothing to another's"),
    )

    /** Each JSON store's shape now, by file: what a backup's parts from it are brought to ([Backups.decode]). */
    val shapes: Map<String, Shape> get() = json.associate { it.name to Shape(it.version, it.migrations) } + (chatArchive.name to Shape(chatArchive.version, ChatArchiveStore.MIGRATIONS))

    /** Files that couldn't be read whole at this start, and where each was set aside (the archive's months, read later: [ChatArchiveStore.onProblem]). */
    val problems: List<JsonStore.Unreadable> get() = json.mapNotNull { it.problem } + chatArchive.problems + listOfNotNull(unsplit, secrets.problem)

    /** What was kept of each file that didn't read whole written over it, now its problem is on record ([JsonStore.keepRecovered]). */
    suspend fun keepRecovered() {
        json.forEach { it.keepRecovered() }
        chatArchive.keepRecovered()
    }

    /**
     * [store] made [value] whole: the one way round its owner, for a backup restored or a reset
     * ([BackupService] alone calls it; the architecture test holds it so).
     */
    suspend fun <T> replace(store: JsonStore<T>, value: T): T = store.update { value }

    /** [store] made what [transform] makes of it, as [replace]: a restore's merge, a reset of part of it. */
    suspend fun <T> replace(store: JsonStore<T>, transform: (T) -> T): T = store.update(transform)

    /** The chat's archive made [value] whole, as [replace]. */
    suspend fun replace(store: ChatArchiveStore, value: ChatArchive): ChatArchive =
        if (value.messages.isEmpty()) value.also { store.clear() } else store.update { value }

    /** The chat's archive made what [transform] makes of it, as [replace]. */
    suspend fun replace(store: ChatArchiveStore, transform: (ChatArchive) -> ChatArchive): ChatArchive = store.update(transform)

    /**
     * The files older shapes came from (`<name>.v<n>`, [JsonStore]) let go once a week old: called at a
     * start that's gone well, so each is kept at least a week from its migration, and past a start.
     */
    fun letGoOfOldShapes(now: Long = clock()) {
        val names = json.map { it.name }.toSet()
        dir.listFiles { f -> f.isFile && f.name.substringBeforeLast(".v") in names && f.name.substringAfterLast(".v").toIntOrNull() != null }.orEmpty()
            .filter { now - it.lastModified() >= OLD_SHAPE_KEPT_MS }
            .forEach { it.delete() }
    }

    companion object {
        /**
         * The moves between stores this build makes at start: Felix's records and state into the chat (4.0.0); the
         * chat's archive into its months (4.0.4); your instructions' step ids onto the tasks they're laid on, and
         * Felix's instructions marked from the chat (4.0.13).
         */
        val MOVES: List<Move> = listOf(ChatMigration.MOVE, ChatArchiveStore.SPLIT, TasksMigration.MOVE)

        /** How long the file an older shape came from is kept after its migration. */
        const val OLD_SHAPE_KEPT_MS = 7 * 24 * 3_600_000L
    }
}
