package com.thomaswcode.decrastination.server

import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.sync.Protocol
import com.thomaswcode.decrastination.sync.protocol.Command
import com.thomaswcode.decrastination.sync.protocol.CommandBatch
import com.thomaswcode.decrastination.sync.protocol.Commands
import com.thomaswcode.decrastination.sync.protocol.Outcome
import com.thomaswcode.decrastination.sync.protocol.Owners
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Commands applied to the state, each through its owner, one at a time, in the order they arrive: all of one or none of it,
 * in one transaction that takes one revision (docs/spec/server.md). A repeat of an id answers with what came of it the first
 * time, applying nothing, as a client retries until it hears back and a lost answer mustn't turn an applied command into a
 * dropped one.
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

    /** [batch] in order, each one's outcome. Not cut short by the client's going away: each is finished, and said at its retry. */
    suspend fun apply(batch: CommandBatch, device: String): List<Outcome> = withContext(NonCancellable) { batch.commands.map { one(it, device) } }

    private suspend fun one(command: Command, device: String): Outcome {
        recorded(command.id)?.let { return it }
        val before = db.currentRevision()
        val outcome = try {
            db.command {
                backend.cause = command.id
                val stale = command.action.stale(owners)
                if (stale == null) commands.apply(command)
                val revision = db.currentRevision()
                val made = if (stale == null) Outcome(command.id, Outcome.APPLIED, revision = revision) else Outcome(command.id, Outcome.DROPPED, stale, revision)
                record(command, device, made)
                made
            }
        } catch (failed: Exception) {
            // What it left in memory goes with the rollback, and it's on record as dropped, so a retry isn't tried again for ever.
            Log.w(Log.TAG, "A command failed: ${failed::class.simpleName} at ${failed.stackTrace.firstOrNull { it.className.startsWith("com.thomaswcode") }}")
            reload()
            Outcome(command.id, Outcome.DROPPED, "It couldn't be applied", db.currentRevision()).also { record(command, device, it) }
        }
        if (outcome.revision != before) committed(outcome.revision)
        return outcome
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
