package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.Replay
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.OutboxState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A change of the state: an owner's operation with its arguments ([Action]), its id made by the client and its moment
 * when it was done (docs/spec/sync.md). The id says a repeat from a retry; the moment is what the owners read while
 * it's applied, so a command that arrives late says when it happened.
 */
@Serializable
data class Command(val id: String, val at: Long, val action: Action)

/** A client's commands, in the order it made them. */
@Serializable
data class CommandBatch(val commands: List<Command>)

/** What came of a command: [state] one of [APPLIED], [DROPPED]; why, if it was dropped; and the revision it left the state at. */
@Serializable
data class Outcome(val id: String, val state: String, val why: String? = null, val revision: Long = 0) {
    companion object {
        const val APPLIED = "applied"
        const val DROPPED = "dropped"
    }
}

/**
 * What a screen asks of an owner, as data. Each is one owner's operation, its class beside its call ([run]), so the phone
 * and the server apply it through the same code. An action's arguments hold nothing of the device's.
 */
@Serializable
sealed interface Action {
    suspend fun run(owners: Owners): Any?

    /** Why this can't be applied to the state as it now is, or null: what it's about has gone since it was made (docs/spec/sync.md §Stale commands). */
    fun stale(owners: Owners): String? = null
}

/** An action's answer, typed, for the screen that asked it. */
interface Answers<out R> {
    suspend fun run(owners: Owners): R
}

/** The owners an action can reach: a graph's own. */
interface Owners {
    val tasks: JsonStore<TaskState>
    val ownTasks: OwnTasks
    val instructions: Instructions
    val focus: Focus
    val conversation: Conversation
    val looks: Looks
    val protection: Protection
    val learning: Learning
}

/**
 * Where a screen's change goes (D-140): each is made a [Command] and applied through its owner, inside a [Replay] of it.
 * No screen calls an owner's change itself (`ArchitectureTest`).
 */
class Commands(
    private val owners: Owners,
    private val clock: WallClock,
    /** Where a command waits for the server's answer, once the two keep in step by commands. */
    private val outbox: JsonStore<OutboxState>? = null,
    private val queuing: () -> Boolean = { false },
    /** A command here threw part-way: what it left may be in a store the server's run of it won't change, as a stopped process's may. */
    private val unfinished: suspend () -> Unit = {},
) {
    /** Held while a command's recorded and applied, and while a pull replaces the stores: neither sees the other half-way. */
    private val gate = Mutex()

    /** Held across a change to the settings: [changeSettings]. */
    private val changingSettings = Mutex()

    suspend fun <R, A> act(action: A): R where A : Action, A : Answers<R> {
        val command = Command(Ids.next(), clock.now(), action)
        if (outbox == null || !queuing()) return replaying(command) { action.run(owners) }
        return gate.withLock {
            // On the disk first, so it's sent whatever stops this process: the server's copy then brings what it made (`Replica`).
            outbox.update { it.copy(commands = it.commands + command) }
            try {
                replaying(command) { action.run(owners) }
            } catch (failed: Throwable) {
                withContext(NonCancellable) { unfinished() }
                throw failed
            }
        }
    }

    /** [command], made elsewhere, applied here as it was there. */
    suspend fun apply(command: Command): Any? = replaying(command) { command.action.run(owners) }

    /**
     * [replace] with no command let in, and only with none waiting; whether it ran. What's waiting is already in the stores, and
     * a pull that doesn't touch a store it changed couldn't take it out, so applying it again on top would make it twice: it
     * goes first, at the next upload, and the pull after it lands on an empty outbox.
     */
    suspend fun replacing(replace: suspend () -> Unit): Boolean = gate.withLock {
        if (outbox?.value?.commands?.isNotEmpty() == true) return@withLock false
        replace()
        true
    }

    /** [block] reading [command]'s moment, and drawing its ids from its own. */
    private suspend fun <T> replaying(command: Command, block: suspend () -> T): T =
        withContext(Replay.current.asContextElement(Replay(command.at, command.id))) { block() }

    /**
     * Settings as [change] leaves what's been asked for, as a change of those fields alone. Read and applied as one, as [change]
     * may build on what's there, a list added to: another read between the two would lose this one's addition or that one's.
     */
    suspend fun changeSettings(change: (Settings) -> Settings) = changingSettings.withLock {
        val asked = owners.protection.asked
        act(ChangeSettings(SettingsChanges.changed(asked, change(asked))))
    }
}
