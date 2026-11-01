package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.data.StoreBackend
import com.thomaswcode.decrastination.sync.Protocol
import java.sql.Connection

/**
 * The stores as rows (docs/plan_web/data-and-sync.md §The store backend): each store's current text in `stores`, and
 * every text it's had in `store_history`, which is what the phone's `.v<n>` and `.unreadable-` files are there: its older
 * shapes are rows already, so keeping one writes nothing more, and one that couldn't be read is marked. A write joins
 * the [Database.transaction] it's called in, or makes one of its own.
 */
class SqliteBackend(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) : StoreBackend {
    /** Why the writes of the moment are made: a command's id, or who seeded them. */
    @Volatile
    var cause: String = SERVER

    override fun read(name: String): String? = db.transactionless { c -> c.single("SELECT json FROM stores WHERE name = ?", name) }

    /**
     * Whether a store can write itself. Not in mirror mode, where what's kept is what the phone sent ([keep]): an older shape is
     * read up to date in memory, its row left as it came, so the hash the phone's told is the one it sent, and no route can
     * change the state.
     */
    @Volatile
    var writable: Boolean = true

    override fun write(name: String, version: Int, text: String) = write(name, version, text) { text }

    override fun write(name: String, version: Int, text: String, remade: (String?) -> String?) {
        if (!writable) return
        val by = writer
        db.writing(name, remake = { remade(read(name))?.let { keep(name, version, it, by) } }) { keep(name, version, text, by) }
    }

    /** Why a write made now is made: the open command's, or the server's own work. */
    private val writer: String get() = if (db.inACommand) cause else SERVER

    /** A revision made by a job, outside any command: heard by the website as a command's is. */
    @Volatile
    var written: (Long) -> Unit = {}

    /** [text] kept as [name], with its history, whatever [writable] says: a seed's. */
    fun keep(name: String, version: Int, text: String, cause: String = this.cause) {
        val revision = db.transaction { c ->
            val revision = db.revision()
            val now = clock()
            c.prepareStatement(
                """INSERT INTO stores(name, version, revision, json, updated_at) VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(name) DO UPDATE SET version = excluded.version, revision = excluded.revision, json = excluded.json, updated_at = excluded.updated_at""",
            ).use { q ->
                q.setString(1, name)
                q.setInt(2, version)
                q.setLong(3, revision)
                q.setString(4, text)
                q.setLong(5, now)
                q.executeUpdate()
            }
            history(c, revision, name, version, text, cause, now)
            revision
        }
        if (!db.inACommand && db.notNested) written(revision)
    }

    override fun names(): List<String> = db.transactionless { c ->
        c.prepareStatement("SELECT name FROM stores ORDER BY name").use { q ->
            q.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }
    }

    override fun holds(name: String): Boolean = read(name) != null

    /** Every store held, by its hash. */
    fun hashes(): Map<String, String> = db.transactionless { c ->
        c.prepareStatement("SELECT name, json FROM stores").use { q ->
            q.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1), Protocol.hash(r.getString(2))) } }
        }
    }

    /**
     * Not a row gone, which would tell a client nothing: `/state` says what's changed, and can't say what isn't there. It's left
     * [empty] at a new revision, which a client takes as it takes any change, so a chat cleared on one goes from the others.
     */
    override fun delete(name: String, empty: String) {
        if (!writable) return
        val version = db.transactionless { c -> c.single("SELECT version FROM stores WHERE name = ?", name) } ?: return
        val by = writer
        db.writing(name) { keep(name, version.toInt(), empty, by) }
    }

    /** Nothing to do: the row of the text before is there already. */
    override fun keepShape(name: String, from: Int, now: Long) = Unit

    override fun alreadyAside(name: String): String? = db.transactionless { c ->
        val now = read(name) ?: return@transactionless null
        c.prepareStatement("SELECT revision FROM store_history WHERE name = ? AND cause = ? AND json = ? ORDER BY revision DESC LIMIT 1").use { q ->
            q.setString(1, name)
            q.setString(2, UNREADABLE)
            q.setString(3, now)
            q.executeQuery().use { if (it.next()) copyName(name, it.getLong(1)) else null }
        }
    }

    /** Nothing kept while a command's open that this isn't part of: a store's read can't wait for one, and the next read asks again. */
    override fun setAside(name: String, now: Long): String? = db.ifFree {
        db.transaction { c ->
            val text = read(name) ?: return@transaction null
            val revision = db.revision()
            history(c, revision, name, 0, text, UNREADABLE, now)
            copyName(name, revision)
        }
    }

    override fun letGoOfCopies(of: (String) -> Boolean) {
        db.writing(COPIES) {
            db.transaction { c ->
                val names = c.prepareStatement("SELECT DISTINCT name FROM store_history").use { q ->
                    q.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                }
                names.filter(of).forEach { name ->
                    c.prepareStatement("DELETE FROM store_history WHERE name = ?").use { q ->
                        q.setString(1, name)
                        q.executeUpdate()
                    }
                }
            }
        }
    }

    /** Nothing to do: `store_history` is trimmed by its own rule ([trimHistory]). */
    override fun letGoOfOldShapes(before: Long, of: (String) -> Boolean) = Unit

    /** The history older than [before] let go, but each store's newest [keep] rows. */
    fun trimHistory(before: Long, keep: Int = HISTORY_KEPT) {
        db.transaction { c ->
            c.prepareStatement(
                """DELETE FROM store_history WHERE at < ? AND rowid NOT IN (
                     SELECT rowid FROM (SELECT rowid, row_number() OVER (PARTITION BY name ORDER BY revision DESC) AS n FROM store_history) WHERE n <= ?)""",
            ).use { q ->
                q.setLong(1, before)
                q.setInt(2, keep)
                q.executeUpdate()
            }
        }
    }

    private fun history(c: Connection, revision: Long, name: String, version: Int, text: String, cause: String, at: Long) {
        c.prepareStatement("INSERT INTO store_history(revision, name, version, json, cause, at) VALUES(?, ?, ?, ?, ?, ?)").use { q ->
            q.setLong(1, revision)
            q.setString(2, name)
            q.setInt(3, version)
            q.setString(4, text)
            q.setString(5, cause)
            q.setLong(6, at)
            q.executeUpdate()
        }
    }

    private fun Connection.single(sql: String, argument: String): String? = prepareStatement(sql).use { q ->
        q.setString(1, argument)
        q.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    private fun copyName(name: String, revision: Long) = "$name.history-$revision"

    companion object {
        private const val UNREADABLE = "unreadable"

        /** Why the server's own work writes. */
        private const val SERVER = "server"

        /** What [Database.writing] calls a change to no one store. */
        private const val COPIES = ""

        /** The rows of a store's history kept however old they are. */
        const val HISTORY_KEPT = 20

        /** How long a store's history is kept. */
        const val HISTORY_MS = 90 * 24 * 3_600_000L
    }
}
