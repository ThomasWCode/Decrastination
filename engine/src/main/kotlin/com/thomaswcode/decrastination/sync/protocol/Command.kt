package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.Replay
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A change of the state: an owner's operation with its arguments ([Action]), its id made by the client and its moment
 * when it was done (docs/spec/sync.md). The id says a repeat from a retry; the moment is what the owners read while
 * it's applied, so a command that arrives late says when it happened.
 */
@Serializable
data class Command(val id: String, val at: Long, val action: Action)

/**
 * What a screen asks of an owner, as data. Each is one owner's operation, its class beside its call ([run]), so the phone
 * and the server apply it through the same code. An action's arguments hold nothing of the device's.
 */
@Serializable
sealed interface Action {
    suspend fun run(owners: Owners): Any?
}

/** An action's answer, typed, for the screen that asked it. */
interface Answers<out R> {
    suspend fun run(owners: Owners): R
}

/** The owners an action can reach: a graph's own. */
interface Owners {
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
class Commands(private val owners: Owners, private val clock: WallClock) {
    suspend fun <R, A> act(action: A): R where A : Action, A : Answers<R> {
        val command = Command(Ids.next(), clock.now(), action)
        return replaying(command) { action.run(owners) }
    }

    /** [command], made elsewhere, applied here as it was there. */
    suspend fun apply(command: Command): Any? = replaying(command) { command.action.run(owners) }

    /** [block] reading [command]'s moment, and drawing its ids from its own. */
    private suspend fun <T> replaying(command: Command, block: suspend () -> T): T =
        withContext(Replay.current.asContextElement(Replay(command.at, command.id))) { block() }

    /** Settings as [change] leaves what's been asked for, as a change of those fields alone. */
    suspend fun changeSettings(change: (Settings) -> Settings) {
        val asked = owners.protection.asked
        act(ChangeSettings(SettingsChanges.changed(asked, change(asked))))
    }
}
