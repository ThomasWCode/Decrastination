package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.block.AnkiWatch
import com.thomaswcode.decrastination.block.Focus
import com.thomaswcode.decrastination.block.Photos
import com.thomaswcode.decrastination.core.Ids
import com.thomaswcode.decrastination.core.Replay
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.BackupService
import com.thomaswcode.decrastination.data.CalendarRead
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.OutboxState
import com.thomaswcode.decrastination.data.TaskState
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.Looks
import com.thomaswcode.decrastination.learn.Learning
import com.thomaswcode.decrastination.ports.Log
import com.thomaswcode.decrastination.protect.Protection
import com.thomaswcode.decrastination.protect.SettingsChanges
import com.thomaswcode.decrastination.sync.Syncer
import com.thomaswcode.decrastination.tasks.Instructions
import com.thomaswcode.decrastination.tasks.OwnTasks
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
data class Outcome(
    val id: String,
    val state: String,
    val why: String? = null,
    val revision: Long = 0,
    /** What takes it back, where that needs what only its owner knows ([Action.undoing]): a client can't make it itself. */
    val undo: Action? = null,
    /** What's said of it, where its owner's words are the answer ([Action.said]), which went through or not. */
    val said: String? = null,
) {
    companion object {
        const val APPLIED = "applied"
        const val DROPPED = "dropped"

        /** It ran and came to nothing, by what its owner answered ([Action.refused]): what it did stands, so it isn't a change lost. */
        const val REFUSED = "refused"
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

    /** Why it came to nothing, by the [answer] its owner gave, or null: reported as a stale one is, what it did standing, a wrong code's count among it. */
    fun refused(answer: Any?): String? = null

    /** What takes this back, by the [answer] its owner gave, or null: for a client that hears only the [Outcome]. */
    fun undoing(answer: Any?): Action? = null

    /** What's said of this, where the [answer] its owner gave is words, or null: for a client that hears only the [Outcome]. */
    fun said(answer: Any?): String? = null
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
    val backups: BackupService

    /** What merges a source's read into the tasks, the phone's read as the server's own ([ObserveSources]). */
    val sources: Syncer

    /** Time in AnkiDroid as work, as the phone saw it ([AnkiCame], [AnkiWent]). */
    val ankiWatch: AnkiWatch

    val photos: Photos

    /** The calendar as the phone last read it ([ObserveCalendar]): kept where it's planned by from it, the server; nothing on the phone, whose it is. */
    suspend fun calendarSeen(read: CalendarRead)

    /** An event asked about ([AskAboutEvent]), on record as asked where the calendar's read, the phone ([com.thomaswcode.decrastination.sync.Sync.eventAsked]); nothing on the server. */
    suspend fun eventAsked(key: String)
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

    /**
     * The commands that may have gone to the server, so none is merged into ([Merging]): those waiting at this start, as a process
     * stopped mid-send can't say, and each handed to a send since ([sending]).
     */
    private val mayHaveGone: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { outbox?.value?.commands?.forEach { add(it.id) } }

    /**
     * What's waiting in the outbox, to be sent: none of it merged into from now on, as it may reach the server as it is now. Read and
     * marked with no command let in between, so what's sent is what's marked.
     */
    suspend fun sending(): List<Command> = gate.withLock {
        outbox?.value?.commands.orEmpty().also { waiting -> waiting.forEach { mayHaveGone += it.id } }
    }

    suspend fun <R, A> act(action: A): R where A : Action, A : Answers<R> = made(action) { action.run(owners) }

    /**
     * [action], what it answers left aside: one decided on elsewhere, whichever it is. One made while another's being applied (an
     * event to ask about, found by a read the other set off) is made as that closes, as a command of its own: the gate it would
     * wait for is that one's.
     */
    suspend fun send(action: Action) {
        coroutineContext[Applying]?.let { applying ->
            applying.after += action
            return
        }
        made(action) { action.run(owners) }
    }

    private suspend fun <R> made(action: Action, run: suspend () -> R): R {
        val command = Command(Ids.next(), clock.now(), action)
        if (outbox == null || !queuing()) return replaying(command) { run() }
        check(coroutineContext[Applying] == null) { "${action::class.simpleName} made inside another command, whose gate it would wait for" }
        val after = Applying()
        try {
            return gate.withLock {
                // On the disk first, so it's sent whatever stops this process: the server's copy then brings what it made (`Replica`).
                // Into the one before it, where that takes it in and hasn't gone: what's applied here is this one's alone.
                outbox.update { state ->
                    val last = state.commands.lastOrNull()
                    val merged = last?.takeIf { it.id !in mayHaveGone }?.let { (it.action as? Merging)?.merged(action) }
                    state.copy(commands = if (last != null && merged != null) state.commands.dropLast(1) + last.copy(action = merged) else state.commands + command)
                }
                try {
                    withContext(after) { replaying(command) { run() } }
                } catch (failed: Throwable) {
                    withContext(NonCancellable) { unfinished() }
                    throw failed
                }
            }
        } finally {
            // What it set off to make, made, whatever came of it: what a read found is found all the same.
            // Each on its own, so one that fails neither takes this one's place nor stops those after it.
            withContext(NonCancellable) {
                after.after.forEach { made ->
                    runCatching { send(made) }.onFailure { Log.w(Log.TAG, "Making ${made::class.simpleName} once another closed failed: ${it::class.simpleName}") }
                }
            }
        }
    }

    /** A command being applied here, and what's to be made once it closes ([send]). */
    private class Applying : AbstractCoroutineContextElement(Applying) {
        val after = mutableListOf<Action>()

        companion object Key : CoroutineContext.Key<Applying>
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
