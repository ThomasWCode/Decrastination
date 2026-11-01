package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.protocol.Action
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.Owners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Commands applied to the state, each through its owner, one at a time, in the order they arrive: all of one or none of it,
 * in one transaction that takes one revision (docs/spec/server.md). A repeat of an id answers with what came of it the first
 * time, applying nothing, as a client retries until it hears back and a lost answer mustn't turn an applied command into a
 * dropped one. A command's moment is the server's where a client's clock would decide something ([stamped]).
 */
class CommandApplier(
    private val db: Database,
    private val backend: SqliteBackend,
    private val commands: Commands,
    private val owners: Owners,
    private val clock: WallClock,
    /** Every store read again from what's committed: a failed command's changes are gone from the database, not from memory. */
    private val reload: suspend () -> Unit,
    /** A new revision, committed. */
    private val committed: (Long) -> Unit,
) {
    private val json = Json { encodeDefaults = true }

    /**
     * [batch] in order, each one's outcome. Not cut short by the client's going away: each is finished, and said at its retry. On
     * threads of its own, not the caller's: a request's thread may be waiting for this very command to close, and on a small box
     * there are few of them. A failure stops it there, thrown ([one]): the client sends all of it again, and what's gone through
     * answers as it did.
     */
    suspend fun apply(batch: CommandBatch, device: String): List<Outcome> = withContext(NonCancellable + Dispatchers.IO) { batch.commands.map { one(it, device) } }

    /**
     * [block], a change too wide for a command (a backup taken in, whose file doesn't belong on record), held as a command is: all
     * of it or none, one at a time, one revision, on threads of its own, and finished whether or not the client stays. [failed]
     * is what's answered if it fails, with nothing of it kept.
     */
    suspend fun <T> alone(cause: String, failed: T, block: suspend () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        var before = 0L
        val answer = db.commandOr(reload, { error ->
            Log.w(Log.TAG, "$cause failed: ${error::class.simpleName} at ${error.stackTrace.firstOrNull { it.className.startsWith("com.thomaswcode") }}")
            failed
        }) {
            before = db.currentRevision()
            backend.cause = cause
            block()
        }
        val now = db.currentRevision()
        if (now > before) committed(now)
        answer
    }

    /**
     * [sent] applied, and what came of it: applied, refused by its owner, or dropped as it can't apply to the state as it now is,
     * each with its reason. A failure is no answer: a lock, the disk and a bug in a release can't be told apart here, and none says
     * what came of the command, so it's nothing a client should let go of. Nothing of it's on record, so its retry is applied as the
     * first would have been, and it's thrown for the client to send again, as giving up on it would lose what only the phone has.
     * One that never goes through waits for the release that mends it.
     */
    private suspend fun one(sent: Command, device: String): Outcome {
        val command = stamped(sent, device)
        var before = 0L
        val outcome = db.commandOr(reload, { failed -> Result.failure(failed) }) {
            before = db.currentRevision()
            // Inside, not ahead of it: a retry racing its first send waits here, and finds what came of it.
            recorded(command.id)?.let { return@commandOr Result.success(it) }
            backend.cause = command.id
            var undo: Action? = null
            var said: String? = null
            val stale = command.action.stale(owners)
            val why = stale ?: commands.apply(command).let { answer ->
                undo = command.action.undoing(answer)
                said = command.action.said(answer)
                command.action.refused(answer)
            }
            val state = if (why == null) Outcome.APPLIED else if (stale != null) Outcome.DROPPED else Outcome.REFUSED
            val made = Outcome(command.id, state, why, db.currentRevision(), undo, said)
            record(command, device, made)
            Result.success(made)
        }
        // What's committed, not what it's on record at: putting a failed one right can take a revision too.
        val now = db.currentRevision()
        if (now > before) committed(now)
        return outcome.getOrThrow()
    }

    /**
     * [command] at the moment it's read as. The website's is this one: it has no queue to send late, and a browser can say
     * anything. A phone's may be earlier, as one made offline is, but never later: the moment decides a code's lock and a wait.
     */
    private fun stamped(command: Command, device: String): Command {
        val now = clock.now()
        return command.copy(at = if (device == Client.WEBSITE) now else minOf(command.at, now))
    }

    private fun recorded(id: String): Outcome? = db.read { c ->
        c.prepareStatement("SELECT outcome, why, revision FROM commands WHERE id = ?").use { q ->
            q.setString(1, id)
            q.executeQuery().use { r -> if (r.next()) Outcome(id, r.getString(1), r.getString(2), r.getLong(3)) else null }
        }
    }

    private fun record(command: Command, device: String, outcome: Outcome) {
        db.transaction { c ->
            c.prepareStatement(
                "INSERT INTO commands(id, device, protocol, payload, received_at, at, outcome, why, revision) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { q ->
                q.setString(1, command.id)
                q.setString(2, device)
                q.setInt(3, Protocol.VERSION)
                q.setString(4, json.encodeToString(Command.serializer(), command))
                q.setLong(5, clock.now())
                q.setLong(6, command.at)
                q.setString(7, outcome.state)
                q.setString(8, outcome.why)
                q.setLong(9, outcome.revision)
                q.executeUpdate()
            }
        }
    }
}
