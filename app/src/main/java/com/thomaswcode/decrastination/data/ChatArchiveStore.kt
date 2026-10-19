package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.ChatArchive
import com.thomaswcode.decrastination.core.ChatMessage
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The chat's archive (D-96): the messages moved out of its live window, a month a file
 * (`chat-archive-<yyyy-MM>.json`, by each message's own time in UTC), each a [ChatArchive], oldest
 * first. A month's file is read only when it's wanted ([JsonStore] read lazily), so the archive's
 * size costs nothing at a start: the window's next move reads only the month it moves into, scrolling
 * back the newest months as far as it goes, a reply to an old message the months back to it, and a
 * backup, a restore, a reset and the export all of them. As a whole it's still `chat-archive.json`
 * ([NAME]): one [ChatArchive] in a backup, in the shape it had.
 */
class ChatArchiveStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    /** What it is as a whole, in a backup and among the stores' shapes. */
    val name: String = NAME

    /** Its shape now: [ChatArchive]'s. */
    val version: Int = ChatArchive().version

    private val months = ConcurrentHashMap<String, JsonStore<ChatArchive>>()

    /** Held while it's written: a month's move, a question answered, all of it replaced. */
    private val writing = Mutex()

    private val _changes = MutableStateFlow(0L)

    /** Raised at each write, so what's shown of it is read again. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** What to do with a month's file found unreadable as it's read, past the start: put on record as the start's are. */
    @Volatile
    var onProblem: ((JsonStore.Unreadable) -> Unit)? = null

    /** Its months' files, oldest first, by their names: none read. */
    private fun files(): List<String> = dir.listFiles { f -> f.isFile && MONTH_FILE.matches(f.name) }.orEmpty().map { it.name }.sorted()

    private fun month(file: String): JsonStore<ChatArchive> = months.computeIfAbsent(file) {
        JsonStore(File(dir, file), ChatArchive.serializer(), ::ChatArchive, clock = clock, lazily = true).also { store -> store.onProblem = { onProblem?.invoke(it) } }
    }

    /** Whether it holds any message: by its files' sizes, where they aren't read. */
    val holdsAny: Boolean get() = files().any { month(it).holdsAny }

    /** All of it, oldest first: every month read. */
    val value: ChatArchive get() = ChatArchive(files().flatMap { month(it).value.messages })

    /** Its newest [n] messages, oldest first: the months read back from the newest as far as they take. */
    fun recent(n: Int): List<ChatMessage> {
        val got = ArrayDeque<List<ChatMessage>>()
        var count = 0
        for (file in files().asReversed()) {
            if (count >= n) break
            val messages = month(file).value.messages
            got.addFirst(messages)
            count += messages.size
        }
        return got.flatten().takeLast(n)
    }

    /** Message [id]: the months read back from the newest till it's found. */
    fun find(id: String): ChatMessage? {
        for (file in files().asReversed()) month(file).value.messages.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /** How many of its newest messages are to be shown for [id] to be the oldest of them ([recent]); null where it isn't there. */
    fun back(id: String): Int? {
        var after = 0
        for (file in files().asReversed()) {
            val messages = month(file).value.messages
            val at = messages.indexOfFirst { it.id == id }
            if (at >= 0) return after + messages.size - at
            after += messages.size
        }
        return null
    }

    /**
     * [moved] added, each to its month's file; any there already (a move cut short, D-96) not again, but
     * as it's moved now, the window's copy being the newer (a question of his answered there since). Only
     * their months are read.
     */
    suspend fun add(moved: List<ChatMessage>): Unit = writing.withLock {
        if (moved.isEmpty()) return@withLock
        for ((file, messages) in moved.groupBy(::fileOf)) {
            month(file).update { a ->
                val there = a.messages.mapTo(HashSet()) { it.id }
                val byId = messages.associateBy { it.id }
                a.copy(messages = a.messages.map { byId[it.id] ?: it } + messages.filter { it.id !in there })
            }
        }
        _changes.update { it + 1 }
    }

    /** Message [id] marked answered at [at], where it's there and waiting: the months read back from the newest till it's found. */
    suspend fun answered(id: String, at: Long): Unit = writing.withLock {
        for (file in files().asReversed()) {
            val store = month(file)
            if (store.value.messages.none { it.id == id }) continue
            if (store.value.messages.any { it.id == id && it.answeredAt == null }) {
                store.update { a -> a.copy(messages = a.messages.map { if (it.id == id && it.answeredAt == null) it.copy(answeredAt = at) else it }) }
                _changes.update { it + 1 }
            }
            return@withLock
        }
    }

    /**
     * All of it made what [transform] makes of it (a restore, a reset, *Clear the chat*): written back a
     * month a file, and a month left with nothing its file let go.
     */
    suspend fun update(transform: (ChatArchive) -> ChatArchive): ChatArchive = writing.withLock {
        val was = value
        val next = transform(was)
        if (next.messages == was.messages) return@withLock next
        val byMonth = next.messages.groupBy(::fileOf)
        for (file in (files() + byMonth.keys).toSortedSet()) {
            val messages = byMonth[file].orEmpty()
            month(file).update { it.copy(messages = messages) }
            if (messages.isEmpty()) {
                months.remove(file)
                File(dir, file).delete()
            }
        }
        _changes.update { it + 1 }
        next
    }

    /** Its months' files that couldn't be read whole, of those read so far. */
    val problems: List<JsonStore.Unreadable> get() = months.values.mapNotNull { it.problem }

    /** What was kept of each month's file that didn't read whole written over it, now its problem's on record. */
    suspend fun keepRecovered() = months.values.forEach { it.keepRecovered() }

    companion object {
        /** The archive as a whole: in a backup, and the one file 4.0.0 to 4.0.3 kept it in. */
        const val NAME = "chat-archive.json"

        private val MONTH_FILE = Regex("""chat-archive-\d{4}-\d{2}\.json""")

        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.UK)

        /** The file of [message]'s month, by its time in UTC: the same on any phone, in any zone. */
        fun fileOf(message: ChatMessage): String = "chat-archive-${Instant.ofEpochMilli(message.at).atOffset(ZoneOffset.UTC).format(MONTH)}.json"

        /**
         * The one file 4.0.0 to 4.0.3 kept the archive in split into its months (4.0.4): each month
         * written, and only then the file let go, so a stop between splits it again, each message once.
         * One that can't be read is left for [Stores] to read as any store's file: set aside and said, what
         * can be kept of it split.
         */
        val SPLIT = Move { dir ->
            val whole = File(dir, NAME)
            if (!whole.isFile) return@Move
            val archive = runCatching { JsonStore.json.decodeFromString(ChatArchive.serializer(), whole.readText()) }.getOrNull() ?: return@Move
            runBlocking { ChatArchiveStore(dir).add(archive.messages) }
            whole.delete()
        }
    }
}
