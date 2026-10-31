package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.InstructionState
import com.thomaswcode.decrastination.core.MemoryState
import com.thomaswcode.decrastination.core.SchoolCalendar
import com.thomaswcode.decrastination.core.Settings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer

/**
 * Every store the app keeps, made in one place (docs/plan_v4/architecture.md §`Stores`): each
 * JSON file with its state's type, its version and its migrations, and the secrets beside them. What
 * couldn't be read whole at this start is said ([problems]); each store says what a backup keeps of it
 * and which resets change it ([all]), so none can be added and left out of either; and [replace] is the
 * one way round a store's owner, for a restore or a reset ([BackupService]).
 */
class Stores(
    private val backend: StoreBackend,
    val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether settings that can't be read at all start armed: while the device admin is on, as only arming turns it on (IMP-P1-003). */
    armedIfUnreadable: () -> Boolean = { false },
    /** The folder a phone's stores are files in: its moves and its one file of the archive from before are the phone's alone. */
    dir: File? = null,
    /** Fields moved between stores, run before any is read ([Move]). */
    moves: List<Move> = emptyList(),
) {
    /** Kept as files in [dir], as a phone keeps them: its moves made first, the secrets beside them under [cipher]. */
    constructor(
        dir: File,
        cipher: SecretCipher,
        clock: () -> Long = System::currentTimeMillis,
        armedIfUnreadable: () -> Boolean = { false },
        moves: List<Move> = MOVES,
    ) : this(FileBackend(dir), SecretStore(File(dir, "secrets.bin"), cipher, clock), clock, armedIfUnreadable, dir, moves)

    /** What the moves found they couldn't read, set aside before they wrote over it: said at this start as a store's file is ([problems]). */
    private val movedAside: List<JsonStore.Unreadable> = dir?.let { folder -> moves.flatMap { it.run(folder) } }.orEmpty()

    private fun <T> store(name: String, serializer: KSerializer<T>, default: () -> T, unreadable: () -> T = default, migrations: List<Migration> = emptyList(), onWrite: (T) -> T = { it }) =
        JsonStore(backend, name, serializer, default, unreadable = unreadable, clock = clock, migrations = migrations, onWrite = onWrite)

    val tasks = store("tasks.json", TaskState.serializer(), ::TaskState, migrations = listOf(TasksMigration.TASKS, StepOrderMigration.TASKS, WeeklyMigration.TASKS, NotSameMigration.TASKS), onWrite = TaskState::withStepIds)

    // A file that can't be read at all starts armed while the device admin is on, as only arming
    // turns it on: a bad file never disarms protection (IMP-P1-003).
    val settings = store("settings.json", Settings.serializer(), ::Settings, unreadable = { Settings(armed = armedIfUnreadable()) }, migrations = listOf(SettingsMigration.SETTINGS, ZoomMigration.SETTINGS, SpeechMigration.SETTINGS))
    val runtime = store("runtime.json", RuntimeState.serializer(), ::RuntimeState, migrations = listOf(ChatMigration.RUNTIME, RuntimeSplit.RUNTIME, NotSameMigration.RUNTIME))

    /** What the blocker keeps from moment to moment, changing every half minute (Focus's). */
    val session = store("session.json", SessionState.serializer(), ::SessionState, migrations = listOf(CreditMigration.SESSION, TimerMigration.SESSION))

    /** Protection's state wherever it's asked for: changes waiting, the override, the code's lock. */
    val protection = store("protection.json", ProtectionState.serializer(), ::ProtectionState, migrations = listOf(ProtectionSplit.PROTECTION))

    /** What's only true of this phone: its device admin, the watchdog's finding, blocking forced from a PC. */
    val device = store("device.json", DeviceState.serializer(), ::DeviceState)

    /** What calling Claude keeps: the month's cost, the last calls, what was refused, the micro-plans. */
    val model = store("model.json", ModelState.serializer(), ::ModelState)

    /** What the Anki watch keeps as AnkiDroid is used. */
    val anki = store("anki.json", AnkiState.serializer(), ::AnkiState)

    /** What the app has learned about how long things take you: the calibration. */
    val learning = store("learning.json", LearningState.serializer(), ::LearningState)
    val log = store("log.json", ActivityLog.serializer(), ::ActivityLog, migrations = listOf(ChatMigration.LOG, MemoryMigration.LOG, CreditMigration.LOG))

    /** Your instructions about tasks, events and days, and what Claude read them as. */
    val instructions = store("instructions.json", InstructionState.serializer(), ::InstructionState, migrations = listOf(TasksMigration.INSTRUCTIONS, StepOrderMigration.INSTRUCTIONS, OnlyMigration.INSTRUCTIONS, OutsideMigration.INSTRUCTIONS, WeeklyMigration.INSTRUCTIONS), onWrite = InstructionState::withStepIds)

    /** Your chat with Felix, the live window of it: every message of his and yours, what became of his cards, and his state. */
    val chat = store("chat.json", ChatState.serializer(), ::ChatState, migrations = listOf(ChatMigration.CHAT, TasksMigration.CHAT, FelixStore.CHAT, FelixStore.CHAT_PIECES, MemoryMigration.CHAT, StepOrderMigration.CHAT, OnlyMigration.CHAT, OutsideMigration.CHAT, MoodMigration.CHAT, SpeechMigration.CHAT, StepActionMigration.CHAT, WeeklyMigration.CHAT))

    /**
     * The chat's messages past its window, oldest first, a month a file: each read only when it's wanted
     * (scrolling back, a reply to one of them, the window's next move, a backup, the export), not at a start.
     */
    val chatArchive = ChatArchiveStore(backend, clock)

    /**
     * 4.0.3's one archive file, still here where the split couldn't read it ([ChatArchiveStore.SPLIT]):
     * read as any store is, so it's set aside as `chat-archive.json.unreadable-<when>` and said at this
     * start ([problems]), and what could be kept of it goes into its months; let go once its copy is made.
     */
    private val unsplit: JsonStore.Unreadable? = dir?.let { File(it, ChatArchiveStore.NAME) }?.takeIf { it.isFile }?.let { file ->
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

    /** What Felix remembers (AI-21): facts, and standing instructions, each able to expire. */
    val memory = store("memory.json", MemoryState.serializer(), ::MemoryState, migrations = listOf(MemoryMigration.MEMORY))

    /** What Felix keeps at your asking: notes on tasks, standing notes, and reminders with their drafts. */
    val felix = store("felix.json", FelixStore.serializer(), ::FelixStore, migrations = listOf(MoodMigration.FELIX, SpeechMigration.FELIX))

    /** The versions of its events the app has written to calendars, so a copy of one is known; what it wrote where; your answers about events. */
    val calendar = store("calendar.json", CalendarState.serializer(), ::CalendarState, migrations = listOf(RuntimeSplit.CALENDAR))

    /** This phone's pairing with the server, and what's been sent it. */
    val sync = store("sync.json", SyncState.serializer(), ::SyncState)

    /** The writes to Gmail a finished email makes, journaled before they're made, and their receipts while their Undo stands. */
    val gmailWrites = store("gmail-writes.json", GmailWriteStore.serializer(), ::GmailWriteStore)

    private val json = listOf(tasks, settings, runtime, session, protection, device, model, anki, learning, log, instructions, chat, school, memory, felix, calendar, sync, gmailWrites)

    /**
     * A store, what a backup keeps of it ([backup]; none, and [why] says why), and the resets that
     * change it themselves ([resets]; what they set going, such as your changes laid on the tasks
     * again, aside), of which those that clear it whole take its copies too ([clears], D-94): one that
     * changes only part of it leaves them, as they hold the rest.
     */
    class Kept(val file: String, val backup: String?, val why: String? = null, val resets: Set<Reset> = emptySet(), val clears: Set<Reset> = resets) {
        init {
            require((backup == null) != (why == null)) { "$file: what a backup keeps of it, or why none" }
        }
    }

    /** Every store, the secrets among them, as [Kept] says. */
    val all: List<Kept> = listOf(
        Kept("tasks.json", backup = "your own tasks, which no source gives back, and the pairs you've said aren't the same work"),
        // A memory reset clears only what they say about you: their copies hold the rest, an unreadable one perhaps its only record.
        Kept("settings.json", backup = "the settings as asked for, a change still waiting among them", resets = setOf(Reset.Memories), clears = emptySet()),
        Kept("secrets.bin", backup = null, why = "passwords and keys stay on this phone (D-78)"),
        Kept("runtime.json", backup = null, why = "the notifications held, the daily runs and the files found unreadable are this phone's"),
        Kept("session.json", backup = "today's slots"),
        Kept("protection.json", backup = null, why = "protection is this phone's; a change still waiting goes with the settings"),
        Kept("device.json", backup = null, why = "the device admin, the watchdog's finding and forced blocking are this phone's"),
        Kept("model.json", backup = "Claude's month"),
        Kept("anki.json", backup = null, why = "what AnkiDroid showed this phone"),
        Kept("learning.json", backup = "the calibration", resets = setOf(Reset.Stats, Reset.Calibration)),
        Kept("log.json", backup = "all of it", resets = setOf(Reset.Stats)),
        Kept("instructions.json", backup = "the instructions in use (a reset's copy: every one)", resets = setOf(Reset.Instructions)),
        Kept("chat.json", backup = "all of it, Felix's state with it", resets = setOf(Reset.Chat)),
        Kept("chat-archive.json", backup = "the chat's earlier messages, while the backup has room for them, the oldest left out first", resets = setOf(Reset.Chat)),
        Kept("school.json", backup = "all of it"),
        Kept("memory.json", backup = "all of it", resets = setOf(Reset.Memories)),
        Kept("felix.json", backup = "all of it", resets = setOf(Reset.Chat)),
        Kept("calendar.json", backup = "your answers about events (what this phone wrote to its calendars means nothing to another's)"),
        Kept("sync.json", backup = null, why = "this phone's pairing with the server, and what it's sent"),
        Kept("gmail-writes.json", backup = "the receipts of writes made to Gmail, while their Undo can stand (a write still to make is this phone's)"),
    )

    /**
     * The copies kept of each store [what] clears whole let go ([all]'s [Kept.clears]; [Reset.Everything], every store a
     * reset clears whole), so what's reset isn't kept in one (D-94). The chat's archive takes its own as it's cleared ([ChatArchiveStore.clear]).
     */
    suspend fun letGoOfCopies(what: Reset) {
        val files = all.filter { kept -> kept.clears.any { what == it || what == Reset.Everything } }.mapTo(HashSet()) { it.file }
        json.filter { it.name in files }.forEach { it.letGoOfCopies() }
    }

    /** The stores the server keeps a copy of ([MIRRORED]), as they are. */
    val mirrored: List<JsonStore<*>> get() = json.filter { it.name in MIRRORED }

    /** Each JSON store's shape now, by file: what a backup's parts from it are brought to ([Backups.decode]). */
    val shapes: Map<String, Shape> get() = json.associate { it.name to Shape(it.version, it.migrations) } + (chatArchive.name to Shape(chatArchive.version, ChatArchiveStore.MIGRATIONS))

    /** Files that couldn't be read whole at this start, and where each was set aside (the archive's months, read later: [ChatArchiveStore.onProblem]). */
    val problems: List<JsonStore.Unreadable> get() = json.mapNotNull { it.problem } + movedAside + chatArchive.problems + listOfNotNull(unsplit, secrets.problem)

    /** Whether [file] is in the stores' folder: a copy one of [problems] named, which a clear or a reset may have taken since. */
    fun holds(file: String): Boolean = backend.holds(file)

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
     * The files older shapes came from (`<name>.v<n>`, [JsonStore]) let go once a week old, the chat
     * archive's among them ([ChatArchiveStore.isOldShape]): called at a start that's gone well, so
     * each is kept at least a week from its migration, and past a start.
     */
    fun letGoOfOldShapes(now: Long = clock()) {
        val names = json.map { it.name }.toSet()
        backend.letGoOfOldShapes(now - OLD_SHAPE_KEPT_MS) { it in names || it == ChatArchiveStore.NAME || ChatArchiveStore.isMonth(it) }
    }

    companion object {
        /**
         * The moves between stores this build makes at start: Felix's records and state into the chat (4.0.0); the
         * chat's archive into its months (4.0.4); your instructions' step ids onto the tasks they're laid on, and
         * Felix's instructions marked from the chat, and runtime.json split by owner (4.0.13); protection.json split, and
         * the pairs that aren't the same work to the tasks (4.13.0).
         */
        val MOVES: List<Move> = listOf(ChatMigration.MOVE, ChatArchiveStore.SPLIT, TasksMigration.MOVE, RuntimeSplit.MOVE, ProtectionSplit.MOVE, NotSameMigration.MOVE)

        /**
         * What the server holds a copy of (docs/plan_web/inventory.md §Stores), the chat's archive's months among them: not
         * `runtime.json`, `device.json` and `calendar.json`, which are this phone's, nor the sync's own, nor the Gmail journal yet.
         */
        val MIRRORED: Set<String> = setOf("tasks.json", "settings.json", "session.json", "protection.json", "model.json", "anki.json", "learning.json", "log.json", "instructions.json", "chat.json", "school.json", "memory.json", "felix.json")

        /** How long the file an older shape came from is kept after its migration. */
        const val OLD_SHAPE_KEPT_MS = 7 * 24 * 3_600_000L
    }
}
