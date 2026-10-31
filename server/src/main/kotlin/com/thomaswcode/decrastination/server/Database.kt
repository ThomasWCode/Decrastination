package com.thomaswcode.decrastination.server

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The state's one file (docs/plan_web/data-and-sync.md §The database): SQLite in WAL mode, as Litestream needs it, written
 * by this process alone through one connection. A [transaction] is all of it or none of it, and takes a revision the
 * first time it writes a store: every store one transaction changes takes the same one. A [command] is one that can suspend,
 * as the owners do; a read that mustn't see one half-way goes through [read].
 */
class Database(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val lock = ReentrantLock()

    /** Reads: in WAL mode a second connection sees what's committed, and waits for nobody. An in-memory database, a test's, has only the one. */
    private val reader: Connection = if (path == MEMORY) connection else DriverManager.getConnection("jdbc:sqlite:$path")
    private val readLock = ReentrantLock()

    /** One [command] at a time: held across suspensions, which the [lock] can't be, being its thread's. */
    private val commanding = Mutex()

    /** The revision the open transaction has taken, if it's taken one. */
    private var taken: Long? = null
    private var depth = 0

    init {
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA synchronous=NORMAL")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("PRAGMA foreign_keys=ON")
            SCHEMA.forEach(s::execute)
        }
        connection.autoCommit = true
        if (reader !== connection) {
            reader.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
        }
    }

    /**
     * [block], which can suspend and change thread on the way, all of it committed or, if it throws, none of it. Everything
     * written while it runs is part of it, whichever thread writes it.
     */
    suspend fun <T> command(block: suspend () -> T): T = commanding.withLock {
        lock.withLock {
            connection.autoCommit = false
            depth = 1
        }
        try {
            val result = block()
            lock.withLock { connection.commit() }
            result
        } catch (failed: Throwable) {
            lock.withLock { connection.rollback() }
            throw failed
        } finally {
            lock.withLock {
                taken = null
                depth = 0
                connection.autoCommit = true
            }
        }
    }

    /** [block] on what's committed, one moment's view however many statements it runs. */
    fun <T> read(block: (Connection) -> T): T = if (reader === connection) {
        transactionless(block)
    } else {
        readLock.withLock {
            reader.autoCommit = false
            try {
                block(reader)
            } finally {
                reader.commit()
                reader.autoCommit = true
            }
        }
    }

    /** [block] with the connection, all of it committed or, if it throws, none of it. Inside another, it's part of that one. */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        if (depth > 0) return block(connection)
        connection.autoCommit = false
        depth = 1
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failed: Throwable) {
            connection.rollback()
            throw failed
        } finally {
            taken = null
            depth = 0
            connection.autoCommit = true
        }
    }

    /** The revision of the open transaction's writes: the next one, taken once. */
    fun revision(): Long = lock.withLock {
        check(depth > 0) { "A revision is taken inside a transaction" }
        taken ?: (currentRevision() + 1).also {
            setMeta("revision", it.toString())
            taken = it
        }
    }

    /** The revision of the last commit. */
    fun currentRevision(): Long = meta("revision")?.toLongOrNull() ?: 0L

    fun meta(key: String): String? = transactionless { c ->
        c.prepareStatement("SELECT value FROM meta WHERE key = ?").use { q ->
            q.setString(1, key)
            q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    fun setMeta(key: String, value: String) = transactionless { c ->
        c.prepareStatement("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").use { q ->
            q.setString(1, key)
            q.setString(2, value)
            q.executeUpdate()
        }
        Unit
    }

    /** [block] with the connection, in the open transaction if there's one. */
    fun <T> transactionless(block: (Connection) -> T): T = lock.withLock { block(connection) }

    override fun close() {
        if (reader !== connection) reader.close()
        connection.close()
    }

    private companion object {
        const val MEMORY = ":memory:"

        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS stores(
                name TEXT PRIMARY KEY, version INTEGER NOT NULL, revision INTEGER NOT NULL, json TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS store_history(
                revision INTEGER NOT NULL, name TEXT NOT NULL, version INTEGER NOT NULL, json TEXT NOT NULL, cause TEXT NOT NULL, at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS store_history_name ON store_history(name, revision)",
            """CREATE TABLE IF NOT EXISTS devices(
                id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER)""",
            "CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pairing_codes(code_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)",
            """CREATE TABLE IF NOT EXISTS commands(
                id TEXT PRIMARY KEY, device TEXT NOT NULL, protocol INTEGER NOT NULL, payload TEXT NOT NULL,
                received_at INTEGER NOT NULL, at INTEGER NOT NULL, outcome TEXT NOT NULL, why TEXT, revision INTEGER NOT NULL)""",
            "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}
