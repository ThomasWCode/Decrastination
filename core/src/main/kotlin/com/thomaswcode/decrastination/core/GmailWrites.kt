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
    /** A finish by your word (Felix's done, the sheet's Finish), not by its work: a keep or a tick taken back doesn't take it back. */
    val explicit: Boolean = false,
    /**
     * Which of its messages were starred, and which in the inbox, before a finish changed anything, as Gmail's
     * message ids (X-GM-MSGID, an unsigned 64-bit number kept as its digits: a UIDVALIDITY change renumbers UIDs,
     * not these): what an Undo puts back.
     */
    val flagged: List<String>? = null,
    val inbox: List<String>? = null,
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

    /** The finishes that stand, by their ids (each task's newest write, a finish, not taken back): what an act's Undo can take back. */
    fun standing(writes: List<GmailWrite>): Set<String> = writes.map { it.taskId }.toSet().mapNotNullTo(HashSet()) { latest(writes, it)?.takeIf { w -> w.reverseOf == null }?.id }

    /** [taskId]'s newest write. */
    fun latest(writes: List<GmailWrite>, taskId: String): GmailWrite? = writes.lastOrNull { it.taskId == taskId }

    /**
     * [writes] with [write], a finish, journaled: to go after the grace from its [GmailWrite.at] where a finish was
     * OK'd before ([confirmed]), else waiting for your OK. Null where [write]'s task has a finish standing: pending, or
     * made with no Gmail read since ([readAt], the source's last) to show it starred again, so a second finish
     * doesn't read the conversation as the first left it and take the place of its receipt. The task's writes given
     * up are let go as this takes their place, their failure said no more, but for one a pending Undo still needs.
     */
    fun finish(writes: List<GmailWrite>, write: GmailWrite, confirmed: Boolean, readAt: Long? = null): List<GmailWrite>? {
        val last = latest(writes, write.taskId)
        if (last != null && last.reverseOf == null) {
            if (pending(last)) return null
            if (!last.gaveUp && (readAt == null || readAt <= last.doneAt!!)) return null
        }
        val needed = needed(writes)
        return writes.filterNot { it.taskId == write.taskId && it.gaveUp && it.id !in needed } + write.copy(notBefore = if (confirmed) write.at + GRACE_MS else null, reverseOf = null)
    }

    /**
     * [writes] with [taskId]'s finish taken back at [now]: one that hasn't read the conversation yet (in its grace,
     * waiting for your OK or for a connection, or given up before), so has changed nothing, dropped, and Gmail's never
     * touched (the worker, finding it gone, stops before any change); one that has, an Undo journaled after it ([id]) to
     * go at once, which puts back what it took. Null where there's nothing to take back.
     */
    fun undo(writes: List<GmailWrite>, taskId: String, now: Long, id: String, writeId: String? = null, byWork: Boolean = false): List<GmailWrite>? {
        val last = latest(writes, taskId)?.takeIf { it.reverseOf == null } ?: return null
        // Only the finish asked for ([writeId]: an act's own), and never one by your word for a keep or a tick taken back ([byWork]).
        if (writeId != null && last.id != writeId) return null
        if (byWork && last.explicit) return null
        if (last.flagged == null) return writes - last
        return writes + last.copy(id = id, at = now, notBefore = now, reverseOf = last.id, flagged = null, inbox = null, unstarred = false, archived = false, tries = 0, lastError = null, doneAt = null, gaveUp = false)
    }

    /**
     * The oldest write due at [now]: pending, past its notBefore (its grace, or a failed try's wait); each task's in the
     * order they were journaled, so an Undo goes only once the finish it takes back is done (made or given up), what it
     * puts back known, and a finish after an Undo only once that's made.
     */
    fun next(writes: List<GmailWrite>, now: Long): GmailWrite? = writes.firstOrNull { w ->
        pending(w) && w.notBefore != null && w.notBefore <= now && writes.first { pending(it) && it.taskId == w.taskId }.id == w.id &&
            (w.reverseOf == null || writes.none { it.id == w.reverseOf && pending(it) })
    }

    /** When the next write may go, for the worker's wait: null with none to go (none pending, or only finishes waiting for your OK). */
    fun wake(writes: List<GmailWrite>): Long? = writes.filter(::pending).mapNotNull { it.notBefore }.minOrNull()

    /** *Carry on* (Q18's sheet) at [now]: every finish waiting for your OK goes after the grace. */
    fun confirm(writes: List<GmailWrite>, now: Long): List<GmailWrite> = writes.map { if (awaiting(it)) it.copy(notBefore = now + GRACE_MS) else it }

    /** *Not this time*, or *Turn this off*: every finish waiting for your OK dropped, its task left open. */
    fun refuse(writes: List<GmailWrite>): List<GmailWrite> = writes.filterNot(::awaiting)

    /**
     * Finishing switched off (Settings → Sources → Gmail): every finish not yet started (waiting for your OK, in its
     * grace or for a connection, its conversation unread) dropped, its task left as it was; one started goes on, so
     * Gmail isn't left half done, and an Undo goes as it would.
     */
    fun switchedOff(writes: List<GmailWrite>): List<GmailWrite> = writes.filterNot { pending(it) && it.reverseOf == null && it.flagged == null }

    /** What an Undo [write] puts back, from the finish it takes back ([writes]): the starred messages where that unstarred them, and the inbox's where that archived them; none where it changed neither. */
    fun toRestore(writes: List<GmailWrite>, write: GmailWrite): Pair<List<String>, List<String>> {
        val finish = writes.firstOrNull { it.id == write.reverseOf } ?: return emptyList<String>() to emptyList()
        return (if (finish.unstarred) finish.flagged.orEmpty() else emptyList()) to (if (finish.archived) finish.inbox.orEmpty() else emptyList())
    }

    /** [id] changed by [change]. */
    fun with(writes: List<GmailWrite>, id: String, change: (GmailWrite) -> GmailWrite): List<GmailWrite> = writes.map { if (it.id == id) change(it) else it }

    /**
     * [id]'s try failed at [now] with [error]: tried again once its wait's over ([backoff], kept as its notBefore, so the
     * worker set going for another write doesn't cut it short), or given up at the [MAX_TRIES]th, what succeeded kept.
     */
    fun failed(writes: List<GmailWrite>, id: String, error: String, now: Long): List<GmailWrite> = with(writes, id) {
        val tries = it.tries + 1
        val gaveUp = tries >= MAX_TRIES
        it.copy(tries = tries, lastError = error.take(MAX_ERROR), notBefore = if (gaveUp) it.notBefore else now + backoff(tries), doneAt = if (gaveUp) now else null, gaveUp = gaveUp)
    }

    /** The wait after a write's [tries]th failed try: half an hour, doubled each time, to five hours at most. */
    fun backoff(tries: Int): Long = minOf(BACKOFF_MS shl (tries - 1).coerceIn(0, 4), MAX_BACKOFF_MS)

    const val BACKOFF_MS = 30L * 60_000
    const val MAX_BACKOFF_MS = 5L * 3_600_000

    /** What an error's kept to. */
    const val MAX_ERROR = 200

    /**
     * Receipts let go once [RECEIPT_MS] old at [now], as their Undo can't stand; one given up kept till it's sorted
     * ([sorted]), and one an Undo still to make takes back kept for it, as it says what to put back.
     */
    fun prune(writes: List<GmailWrite>, now: Long): List<GmailWrite> {
        val needed = needed(writes)
        return writes.filterNot { w -> w.doneAt != null && !w.gaveUp && w.id !in needed && now - w.doneAt >= RECEIPT_MS }
    }

    /** The finishes a pending Undo takes back: each kept for it, as it says what to put back. */
    private fun needed(writes: List<GmailWrite>): Set<String> = writes.filter { it.reverseOf != null && pending(it) }.mapNotNullTo(HashSet()) { it.reverseOf }

    /**
     * [writes] settled at [now], at a start and after every read: receipts let go once old ([prune]); and a task's, all
     * made before a Gmail read that began at [readAt] found it open ([open]: starred again, a new round), let go, as
     * that round's over and their Undo would put back the last one's. One given up stays till it's sorted; a task with
     * a write still to make is left as it is.
     */
    fun settle(writes: List<GmailWrite>, open: (String) -> Boolean, readAt: Long?, now: Long): List<GmailWrite> {
        val pruned = prune(writes, now)
        if (readAt == null) return pruned
        val over = pruned.groupBy { it.taskId }.filter { (taskId, its) -> its.none(::pending) && its.all { it.doneAt!! < readAt } && open(taskId) }.keys
        return pruned.filterNot { it.taskId in over && !it.gaveUp }
    }

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
            // An Undo given up as much as a finish: said, with It's sorted.
            last.gaveUp -> State.GaveUp
            last.reverseOf != null -> State.TakenBack
            awaiting(last) -> State.Awaiting
            pending(last) -> State.Going
            else -> State.Made
        }
    }

    /** [write], given up, in words: what didn't happen, and what did. */
    fun failure(write: GmailWrite): String {
        val title = "“${write.title}”"
        val why = write.lastError?.let { " ($it)" }.orEmpty()
        return when {
            write.reverseOf != null && write.unstarred && !write.archived -> "Starred $title again in Gmail but couldn't put it back in the inbox$why: it's still archived."
            write.reverseOf != null && !write.unstarred && write.archived -> "Couldn't star $title again in Gmail$why: star it there if you want it starred."
            write.reverseOf != null -> "Couldn't star $title again or put it back in the inbox in Gmail$why: it's as the finish left it."
            write.unstarred && !write.archived -> "Unstarred $title in Gmail but couldn't archive it$why: it's still in the inbox."
            !write.unstarred && write.archived -> "Archived $title in Gmail but couldn't unstar it$why: it's still starred."
            else -> "Couldn't unstar $title in Gmail$why: it's still starred."
        }
    }
}
