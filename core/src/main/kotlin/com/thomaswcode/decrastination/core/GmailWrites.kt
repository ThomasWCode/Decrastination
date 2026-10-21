package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * A write to Gmail, journaled before it's made (docs/plan_v4/10-emails-done.md, D-7 as amended): a finish
 * of [taskId]'s conversation [threadId], every starred message of it unstarred and the conversation
 * archived; or, where [reverseOf] names a finish, its Undo past the grace, which stars again and puts back
 * in the inbox only what that finish took. Once [doneAt] (made, or [gaveUp]) it's a receipt, kept while
 * its Undo can stand ([GmailWrites.prune]).
 */
@Serializable
data class GmailWrite(
    val id: String,
    /** The task it finishes, whose sheet says so. */
    val taskId: String,
    /** The task's title when it was journaled: what a failure's notification names. */
    val title: String,
    /** Gmail's conversation (X-GM-THRID): its messages are listed afresh at each write, whatever renumbered them, a message come since among them. */
    val threadId: String,
    val at: Long,
    /** When it may go: null while the very first finish waits for your OK ([GmailWrites.confirm]). */
    val notBefore: Long? = null,
    /** On an Undo: the finish it takes back. */
    val reverseOf: String? = null,
    /**
     * Which of its messages were starred, and which in the inbox, before a finish changed anything, as Gmail's
     * message ids (X-GM-MSGID: a UIDVALIDITY change renumbers UIDs, not these): what an Undo puts back.
     */
    val flagged: List<Long>? = null,
    val inbox: List<Long>? = null,
    /** Each command's own success (a finish's star taken off and inbox left; an Undo's put back), so a retry goes on from the one that failed, and a failure says what state the mailbox is in. */
    val unstarred: Boolean = false,
    val archived: Boolean = false,
    val tries: Int = 0,
    val lastError: String? = null,
    /** Made, or given up ([gaveUp]): a receipt from then on. */
    val doneAt: Long? = null,
    val gaveUp: Boolean = false,
)

/** The journal's rules (docs/plan_v4/10-emails-done.md): the grace, the first finish's OK, Undo, tries and receipts. Pure. */
object GmailWrites {
    /** How long a finish waits before it's made: an Undo within it leaves Gmail untouched (Q18: A). */
    const val GRACE_MS = 60_000L

    /** Tries before a write is given up and said (Q18: C, the app's own notification). */
    const val MAX_TRIES = 5

    /** How long a receipt is kept: as long as the act that made it can be undone (its message's month in the chat's window, at most). */
    const val RECEIPT_MS = 30L * 24 * 3_600_000

    /** The hint a first finish's OK is kept under ([Settings.hintsSeen]): every finish after it goes with the grace alone. */
    const val OK_HINT = "gmail-finish"

    /** Not yet made nor given up. */
    fun pending(write: GmailWrite): Boolean = write.doneAt == null

    /** A finish waiting for your OK: the very first. */
    fun awaiting(write: GmailWrite): Boolean = pending(write) && write.notBefore == null

    /** [taskId]'s newest write. */
    fun latest(writes: List<GmailWrite>, taskId: String): GmailWrite? = writes.lastOrNull { it.taskId == taskId }

    /**
     * [writes] with [write], a finish, journaled: to go after the grace from its [GmailWrite.at] where a finish was
     * OK'd before ([confirmed]), else waiting for your OK. Null where [write]'s task has a finish pending already, so a
     * second tick doesn't write twice.
     */
    fun finish(writes: List<GmailWrite>, write: GmailWrite, confirmed: Boolean): List<GmailWrite>? {
        val last = latest(writes, write.taskId)
        if (last != null && last.reverseOf == null && pending(last)) return null
        return writes + write.copy(notBefore = if (confirmed) write.at + GRACE_MS else null, reverseOf = null)
    }

    /**
     * [writes] with [taskId]'s finish taken back at [now]: one that hasn't read the conversation yet (in its grace,
     * waiting for your OK or for a connection, or given up before), so has changed nothing, dropped, and Gmail's never
     * touched (the worker, finding it gone, stops before any change); one that has, an Undo journaled after it ([id]) to
     * go at once, which puts back what it took. Null where there's nothing to take back.
     */
    fun undo(writes: List<GmailWrite>, taskId: String, now: Long, id: String): List<GmailWrite>? {
        val last = latest(writes, taskId)?.takeIf { it.reverseOf == null } ?: return null
        if (last.flagged == null) return writes - last
        return writes + last.copy(id = id, at = now, notBefore = now, reverseOf = last.id, flagged = null, inbox = null, unstarred = false, archived = false, tries = 0, lastError = null, doneAt = null, gaveUp = false)
    }

    /**
     * The oldest write due at [now]: pending, past its notBefore; an Undo only once the finish it takes back is done
     * (made or given up), so what it puts back is known.
     */
    fun next(writes: List<GmailWrite>, now: Long): GmailWrite? = writes.firstOrNull { w ->
        pending(w) && w.notBefore != null && w.notBefore <= now && (w.reverseOf == null || writes.none { it.id == w.reverseOf && pending(it) })
    }

    /** When the next write may go, for the worker's wait: null with none to go (none pending, or only finishes waiting for your OK). */
    fun wake(writes: List<GmailWrite>): Long? = writes.filter(::pending).mapNotNull { it.notBefore }.minOrNull()

    /** *Carry on* (Q18's sheet) at [now]: every finish waiting for your OK goes after the grace. */
    fun confirm(writes: List<GmailWrite>, now: Long): List<GmailWrite> = writes.map { if (awaiting(it)) it.copy(notBefore = now + GRACE_MS) else it }

    /** *Not this time*, or *Turn this off*: every finish waiting for your OK dropped, its task left open. */
    fun refuse(writes: List<GmailWrite>): List<GmailWrite> = writes.filterNot(::awaiting)

    /** What an Undo [write] puts back, from the finish it takes back ([writes]): the starred messages where that unstarred them, and the inbox's where that archived them; none where it changed neither. */
    fun toRestore(writes: List<GmailWrite>, write: GmailWrite): Pair<List<Long>, List<Long>> {
        val finish = writes.firstOrNull { it.id == write.reverseOf } ?: return emptyList<Long>() to emptyList()
        return (if (finish.unstarred) finish.flagged.orEmpty() else emptyList()) to (if (finish.archived) finish.inbox.orEmpty() else emptyList())
    }

    /** [id] changed by [change]. */
    fun with(writes: List<GmailWrite>, id: String, change: (GmailWrite) -> GmailWrite): List<GmailWrite> = writes.map { if (it.id == id) change(it) else it }

    /** [id]'s try failed at [now] with [error]: tried again later, or given up at the [MAX_TRIES]th, what succeeded kept. */
    fun failed(writes: List<GmailWrite>, id: String, error: String, now: Long): List<GmailWrite> = with(writes, id) {
        val tries = it.tries + 1
        it.copy(tries = tries, lastError = error.take(MAX_ERROR), doneAt = if (tries >= MAX_TRIES) now else null, gaveUp = tries >= MAX_TRIES)
    }

    /** What an error's kept to. */
    const val MAX_ERROR = 200

    /** Receipts let go once [RECEIPT_MS] old at [now], as their Undo can't stand; one given up kept till it's sorted ([sorted]). */
    fun prune(writes: List<GmailWrite>, now: Long): List<GmailWrite> = writes.filterNot { w -> w.doneAt != null && !w.gaveUp && now - w.doneAt >= RECEIPT_MS }

    /** *It's sorted*: [taskId]'s given-up writes let go. */
    fun sorted(writes: List<GmailWrite>, taskId: String): List<GmailWrite> = writes.filterNot { it.taskId == taskId && it.gaveUp }

    /** What a backup keeps: the receipts, as a restored chat's acts may undo them; a pending write is this phone's and this hour's. */
    fun receipts(writes: List<GmailWrite>): List<GmailWrite> = writes.filterNot(::pending)

    /** The writes given up and not yet sorted: Setup's attention mark, each task's sheet. */
    fun givenUp(writes: List<GmailWrite>): List<GmailWrite> = writes.filter { it.gaveUp }

    /** What [taskId]'s finish is, for its sheet. */
    enum class State {
        /** Never finished here, or taken back before it went. */
        None,

        /** The very first finish, waiting for your OK. */
        Awaiting,

        /** Journaled: in its grace, or waiting to be made. */
        Going,

        /** Made: unstarred and archived. */
        Made,

        /** Given up, [GmailWrites.failure] saying what did and didn't happen. */
        GaveUp,

        /** Taken back after it went: starring it again, or starred again. */
        TakenBack,
    }

    fun state(writes: List<GmailWrite>, taskId: String): State {
        val last = latest(writes, taskId) ?: return State.None
        return when {
            last.reverseOf != null -> State.TakenBack
            awaiting(last) -> State.Awaiting
            pending(last) -> State.Going
            last.gaveUp -> State.GaveUp
            else -> State.Made
        }
    }

    /** [write], given up, in words: what didn't happen, and what did. */
    fun failure(write: GmailWrite): String {
        val title = "“${write.title}”"
        val why = write.lastError?.let { " ($it)" }.orEmpty()
        return when {
            write.reverseOf != null -> "Couldn't star $title again in Gmail$why: star it there if you want it back."
            write.unstarred && !write.archived -> "Unstarred $title in Gmail but couldn't archive it$why: it's still in the inbox."
            !write.unstarred && write.archived -> "Archived $title in Gmail but couldn't unstar it$why: it's still starred."
            else -> "Couldn't unstar $title in Gmail$why: it's still starred."
        }
    }
}
