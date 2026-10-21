package com.thomaswcode.decrastination.sync

import com.thomaswcode.decrastination.core.GmailFinish
import com.thomaswcode.decrastination.core.GmailWrite
import com.thomaswcode.decrastination.core.GmailWrites
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WallClock
import com.thomaswcode.decrastination.data.GmailWriteStore
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.sources.SourceUnavailable
import com.thomaswcode.decrastination.sources.gmail.GmailWriter
import com.thomaswcode.decrastination.sources.gmail.ImapClient
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Finishing a starred email here (docs/plan_v4/10-emails-done.md, D-7 as amended): the write to Gmail journaled
 * first, in `gmail-writes.json` ([writes], which this alone writes); made after its grace by [GmailWriteWorker]
 * through a [GmailWriter], each command's success kept as it comes; taken back within the grace by dropping it,
 * after it by a write that puts back what it took; given up after [GmailWrites.MAX_TRIES] tries and said in the
 * app's own notification. Nothing is marked done here: a Gmail-only read after the write closes the task, as an
 * email unstarred by hand is closed (D-10). Built like Focus: its store, the clock and small ports.
 */
class GmailFinishes(
    private val writes: JsonStore<GmailWriteStore>,
    private val settings: () -> Settings,
    /** The task of an id as the plan takes it (your values laid on it): null where there's none. */
    private val task: (String) -> TaskItem?,
    /** The pace learned for a task: what its minutes come to in the plan. */
    private val multiplier: (TaskItem) -> Double,
    private val clock: WallClock,
    private val ports: Ports,
    /** When the last Gmail read that worked began: a finish made before it, its task open after it, was starred again. */
    private val readAt: () -> Long? = { null },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** What Android does for it. */
    interface Ports {
        /** The writes' worker set to run at [at], or once there's a connection after it, in place of any set before. */
        fun schedule(at: Long)

        /** As [schedule], but only where no worker is set or running. */
        fun ensureScheduled(at: Long)

        /** A Gmail-only read asked for now, so a finished email's task closes as its source says. */
        fun syncGmail()

        /** [write] given up, said in the app's own notification (Q18: C, Q27) in [words], opening its task's sheet. */
        fun gaveUp(write: GmailWrite, words: String)

        /** [write]'s notification taken away: sorted. */
        fun sorted(write: GmailWrite)
    }

    /** What a finish came to. */
    enum class Finish {
        /** The switch is off (Settings → Sources → Gmail): nothing written, as before. */
        Off,

        /** Not an open email, or, by its work, with work left: nothing written. */
        No,

        /** The very first: journaled, waiting for your OK on the next screen to open. */
        Awaiting,

        /** Journaled, to go after the grace. */
        Going,

        /** One's pending already. */
        Pending,
    }

    /** The journal as it is, for the screens. */
    val state: StateFlow<GmailWriteStore> get() = writes.state

    val value: List<GmailWrite> get() = writes.value.writes

    /**
     * [taskId] finished: by your word ([explicit]: Felix's `done`, the sheet's *Finish*), any open email; by its work
     * (a slot kept, a step ticked), only once all of it's done ([GmailFinish.due]). Journaled, and its worker set
     * for the grace's end; the very first waits for your OK instead ([confirm]).
     */
    suspend fun finish(taskId: String, explicit: Boolean): Finish {
        val s = settings()
        if (!s.gmailFinishes) return Finish.Off
        val task = task(taskId) ?: return Finish.No
        val finishing = if (explicit) GmailFinish.finishable(task) else GmailFinish.due(task, multiplier(task))
        if (!finishing) return Finish.No
        val confirmed = GmailWrites.OK_HINT in s.hintsSeen
        val now = clock.now()
        var made = false
        var gone = emptyList<GmailWrite>()
        writes.update { store ->
            GmailWrites.finish(store.writes, GmailWrite(newId(), task.id, task.title, task.sourceId, now, explicit = explicit), confirmed, readAt())
                ?.let { journal ->
                    made = true
                    gone = store.writes.filter { w -> w.gaveUp && journal.none { it.id == w.id } }
                    store.copy(writes = GmailWrites.prune(journal, now))
                } ?: store
        }
        if (!made) return Finish.Pending
        // One given up that this takes the place of: its notification taken away, as It's sorted does.
        gone.forEach(ports::sorted)
        if (!confirmed) return Finish.Awaiting
        // The worker set for the first write due, this one's grace or one before it.
        GmailWrites.wake(value)?.let(ports::schedule)
        return Finish.Going
    }

    /**
     * [taskId]'s finish taken back: dropped where it hasn't read the conversation, so Gmail's never touched; after
     * that, a write that puts back what it took, set going at once. Only [writeId] where it's given (an act's own
     * finish, not a newer one); and, [byWork] (a keep or a tick taken back), never one by your word. Whether there
     * was one to take back.
     */
    suspend fun unfinish(taskId: String, writeId: String? = null, byWork: Boolean = false): Boolean {
        val now = clock.now()
        var changed = false
        writes.update { store -> GmailWrites.undo(store.writes, taskId, now, newId(), writeId, byWork)?.let { changed = true; store.copy(writes = it) } ?: store }
        if (changed) GmailWrites.wake(value)?.let(ports::schedule)
        return changed
    }

    /**
     * [taskId]'s work taken back (a keep or a hand tick undone): its finish by its work taken back, but only where its
     * work isn't all done now ([GmailFinish.workDone]: a sitting that ticked nothing taken back leaves every step
     * ticked); one by your word stays.
     */
    suspend fun unworked(taskId: String): Boolean {
        val task = task(taskId)
        if (task != null && GmailFinish.workDone(task, multiplier(task))) return false
        return unfinish(taskId, byWork = true)
    }

    /** [taskId]'s finish that stands, by its id: what an act of his made, for its Undo to take back that one. */
    fun latestFinish(taskId: String): String? = GmailWrites.latest(value, taskId)?.takeIf { it.reverseOf == null }?.id

    /**
     * The journal settled ([GmailWrites.settle]), at a start and after every read: receipts let go once old, or once a
     * Gmail read that began after them finds their email starred again.
     */
    suspend fun settle() {
        val now = clock.now()
        val readAt = readAt()
        writes.update { store -> store.copy(writes = GmailWrites.settle(store.writes, { id -> task(id)?.isOpen == true }, readAt, now)) }
    }

    /**
     * The worker set again for what's journaled and due: at a start, as a stop may have come between a finish's
     * journal and its worker. One set or running already is left as it is: WorkManager may have started the app to
     * run it, and a failed try's wait isn't cut short.
     */
    fun rearm() {
        GmailWrites.wake(value)?.let(ports::ensureScheduled)
    }

    /**
     * *Carry on* (Q18's sheet): every finish waiting for your OK goes after the grace from now. Kept in the hints by the
     * screen. With the switch turned off meanwhile, they're dropped instead ([switchedOff]).
     */
    suspend fun confirm() {
        if (!settings().gmailFinishes) return switchedOff()
        val now = clock.now()
        writes.update { it.copy(writes = GmailWrites.confirm(it.writes, now)) }
        GmailWrites.wake(value)?.let(ports::schedule)
    }

    /** *Not this time*, or *Turn this off*: every finish waiting for your OK dropped, its task left as it was. */
    suspend fun refuse() {
        writes.update { it.copy(writes = GmailWrites.refuse(it.writes)) }
    }

    /** Switched off (Settings → Sources → Gmail): every finish not yet started dropped ([GmailWrites.switchedOff]). */
    suspend fun switchedOff() {
        writes.update { it.copy(writes = GmailWrites.switchedOff(it.writes)) }
    }

    /** *It's sorted*: [taskId]'s writes given up let go, and their notification. */
    suspend fun sorted(taskId: String) {
        val gone = value.filter { it.taskId == taskId && it.gaveUp }
        if (gone.isEmpty()) return
        writes.update { it.copy(writes = GmailWrites.sorted(it.writes, taskId)) }
        gone.forEach(ports::sorted)
    }

    /**
     * Every write due now made through [writer], oldest first, each command's success kept as it comes; one that fails
     * waits its backoff in the journal ([GmailWrites.failed]) while the rest go on. Then the worker's next run set for
     * the next to go (a grace still running, a failed try's wait). Whether a try failed, to be tried again.
     */
    suspend fun write(writer: GmailWriter): Boolean {
        // Switched off meanwhile: a finish not yet started isn't.
        if (!settings().gmailFinishes) switchedOff()
        var again = false
        while (true) {
            val next = GmailWrites.next(value, clock.now()) ?: break
            // A worker stopped (a newer time set, the connection gone) isn't a try that failed: what it made is kept, the rest goes next time.
            val failed = try {
                make(next, writer)
                continue
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (e: Exception) {
                e
            }
            writes.update { it.copy(writes = GmailWrites.failed(it.writes, next.id, words(failed), clock.now())) }
            val after = value.firstOrNull { it.id == next.id }
            if (after?.gaveUp == true) ports.gaveUp(after, GmailWrites.failure(after)) else again = true
        }
        GmailWrites.wake(value)?.let(ports::schedule)
        return again
    }

    /**
     * [write] made: a finish lists its conversation afresh, keeps which of it was starred and in the inbox before
     * changing anything (stopping there where it's been taken back meanwhile), then unstars and archives; an Undo
     * puts back what its finish took. Each command skipped where a try before made it. The Gmail read asked for after.
     */
    private suspend fun make(write: GmailWrite, writer: GmailWriter) {
        if (write.reverseOf != null) {
            val (starred, inbox) = GmailWrites.toRestore(value, write)
            if (starred.isNotEmpty() || inbox.isNotEmpty()) writer.open { session ->
                val uids = session.messages(session.thread(write.threadId)).associate { it.id to it.uid }
                if (!write.unstarred) changed(session, starred.mapNotNull(uids::get), GmailWriter.Change.Star) && kept(write.id) { it.copy(unstarred = true) }
                if (!write.archived) changed(session, inbox.mapNotNull(uids::get), GmailWriter.Change.Inbox) && kept(write.id) { it.copy(archived = true) }
            }
        } else {
            val going = writer.open { session ->
                val messages = session.messages(session.thread(write.threadId))
                val flagged = write.flagged ?: messages.filter { it.starred }.map { it.id }
                val inbox = write.inbox ?: messages.filter { it.inbox }.map { it.id }
                // What's there kept before anything changes: an Undo from now puts it back. Gone meanwhile, nothing changes.
                if (write.flagged == null && !kept(write.id) { it.copy(flagged = flagged, inbox = inbox) }) return@open false
                val uids = messages.associate { it.id to it.uid }
                if (!write.unstarred) changed(session, flagged.mapNotNull(uids::get), GmailWriter.Change.Unstar) && kept(write.id) { it.copy(unstarred = true) }
                if (!write.archived) changed(session, inbox.mapNotNull(uids::get), GmailWriter.Change.Archive) && kept(write.id) { it.copy(archived = true) }
                true
            }
            if (!going) return
        }
        kept(write.id) { it.copy(doneAt = clock.now(), lastError = null) }
        ports.syncGmail()
    }

    /** [change] made to [uids] in [session]: true, unless every one was refused (where there were any). */
    private fun changed(session: GmailWriter.Session, uids: List<Long>, change: GmailWriter.Change): Boolean {
        if (uids.isEmpty()) return true
        if (session.store(uids, change).size == uids.size) throw IOException("Gmail refused the change to every message")
        return true
    }

    /** Write [id] changed by [change], where it's still journaled: whether it was. */
    private suspend fun kept(id: String, change: (GmailWrite) -> GmailWrite): Boolean {
        var there = false
        writes.update { store -> if (store.writes.none { it.id == id }) store else store.copy(writes = GmailWrites.with(store.writes, id, change)).also { there = true } }
        return there
    }

    /** What went wrong, in a few words: never a subject or an address. */
    private fun words(error: Throwable): String = when (error) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "no connection"
        is SourceUnavailable -> error.message ?: "Gmail isn't set up"
        is ImapClient.CommandFailed -> if (error.message.orEmpty().startsWith("LOGIN")) "Gmail refused the login" else "Gmail refused the change"
        else -> error.message ?: error::class.simpleName ?: "it failed"
    }
}
