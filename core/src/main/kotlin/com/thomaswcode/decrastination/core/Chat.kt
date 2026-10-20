package com.thomaswcode.decrastination.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Asking Claude (2.0.0, Q29), Felix since review v2 (AI-24): one chat for everything you'd say about
 * your work. You write; he answers, asks when something's missing, and puts what he does on cards
 * ([Proposal]): everything is made at once, its card's Undo taking it back (your call, 16 Oct; a new
 * task or days off waited for the card's Add or Apply before). Every word in his name is a message
 * here first, what he writes unasked among them (docs/plan_v4/05-the-chat.md): this is the live
 * window of the conversation, kept in `chat.json`, and what's past it is in [ChatArchive].
 */
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    /** When you last left the chat, or opened it from his notification: his messages after it are unread. */
    val readAt: Long = 0,
    /** His state between looks (LR-03): his next check, what he wrote ahead, what waits to be asked, the check-in's answers so far. */
    val felix: FelixState = FelixState(),
    /** The shape this is in ([com.thomaswcode.decrastination.data.Migration]): raised with each change of it. */
    val version: Int = 5,
) {
    /**
     * Your newest message still waiting for his answer: a message of his written since, unasked, doesn't
     * answer it, nor does a reply of yours taken with no call (a check-in's answer), which comes answered.
     */
    val unanswered: ChatMessage? get() = messages.lastOrNull { it.from == Speaker.You && it.answeredAt == null }
}

/** Who wrote a message: you, or Felix (the model writing as him), stored as "Claude", his name till 4.0.1. */
@Serializable
enum class Speaker {
    You,

    @SerialName("Claude")
    Felix,
}

/**
 * Why a message was written (docs/plan_v4/05-the-chat.md §1): [Chat] for yours and his answers to you;
 * else why he wrote unasked: his looks at a slot (`SlotStart`, `SlotEnd`, `SlotKept`), the day done,
 * the morning, a reading's question, how the day's completions went, the week's check-in, review and
 * summary, a calendar event's question; a reminder and a note of the app's (Stages 8a and 8b).
 */
@Serializable
enum class MessageKind { Chat, Morning, SlotStart, SlotEnd, SlotKept, DayDone, Question, Completions, CheckIn, Review, Summary, EventQuestion, Reminder, Note }

@Serializable
data class ChatMessage(
    val id: String,
    val from: Speaker,
    val text: String,
    val at: Long,
    /** What your message was written about: a task, an event or a day (from its page), or nothing in particular. His: what he wrote about. */
    val about: About = About(),
    /** Why it was written: a message of his written unasked has a kind of its own; yours answering his question with no call, its question's. */
    val kind: MessageKind = MessageKind.Chat,
    /** His, in the app's own words, with no call made (a check-in's question, the morning's list, a calendar event's question). */
    val byApp: Boolean = false,
    /** The message this one answers: a reply from his notification, one of his chips, or a swipe or Reply on a message in the chat. */
    val replyTo: String? = null,
    /** His, about a piece of the plan: its key (PL-13). */
    val pieceKey: String? = null,
    /** His: when he said he'd look again. */
    val nextCheckAt: Long? = null,
    /** His Completions message's completions (LR-05): one of its three answers goes on each. */
    val asks: List<AssessLater> = emptyList(),
    /** His, in the app's words: the button it carries under it, "review" (the week's) or "summary:<week>" (to send). */
    val opens: String? = null,
    /** His Reminder message's reminder (Stage 8a): what its *Done* and snoozes act on. */
    val reminderId: String? = null,
    /** Claude's: what it would do, each on a card. */
    val proposals: List<Proposal> = emptyList(),
    /**
     * Yours: when Claude answered it; null while it waits. His question (a check-in's, a reading's, how
     * work went, a calendar event's): when it was answered, here or elsewhere, so it leaves his notification.
     */
    val answeredAt: Long? = null,
    /** Yours, while it waits: why (Claude off, resting, a failed call). Claude's: what it suggested that the app couldn't use. */
    val note: String? = null,
    /**
     * Yours: Claude answered it, but with nothing the app could use (it declined, was cut off, or
     * couldn't be read). Each try is paid for, so it's asked again only when you tap *Try again*,
     * never by itself.
     */
    val stuck: Boolean = false,
    /** Claude's: what it remembered or forgot with it (AI-21), each with what its Undo takes back. */
    val memoryEdits: List<MemoryEdit> = emptyList(),
    /**
     * Claude's: what its actions did (a step ticked or unticked, a task done, open again or deleted, a
     * setting set), each with its Undo; and what came of the answer with nothing to undo (a time the
     * plan couldn't keep, a text read in full, something not made), a line each.
     */
    val acts: List<Act> = emptyList(),
    /** Claude's: up to three short replies you might send next, in your words (AI-24, UI-06). */
    val suggestedReplies: List<String> = emptyList(),
    /** Claude's: what he searched the web for to answer it (16 Oct), and the pages the searches found, the first few. */
    val searched: List<String> = emptyList(),
    val sources: List<WebSource> = emptyList(),
)

/** A page a web search found ([ChatMessage.sources]): its title and where it is. */
@Serializable
data class WebSource(val title: String, val url: String)

/**
 * The conversation before the live window ([ChatState]), oldest first: every message kept for you to
 * read, never sent to the model, shown only on scrolling back. Kept in `chat-archive.json`.
 */
@Serializable
data class ChatArchive(
    val messages: List<ChatMessage> = emptyList(),
    /** The shape this is in ([com.thomaswcode.decrastination.data.Migration]): raised with each change of it. */
    val version: Int = 4,
)

/** The conversation read as a thread. Pure. */
object ChatHistory {
    /** How long after a message of his a message of yours with no [ChatMessage.replyTo] is taken as your reply to it. */
    const val REPLY_WITHIN_MS = 10 * 60_000L

    /** His own messages in [messages], written unasked (not his answers to you), as they were kept on record. */
    fun his(messages: List<ChatMessage>): List<ChatMessage> = messages.filter { it.from == Speaker.Felix && it.kind != MessageKind.Chat }

    /**
     * Your reply to [message], his, in [messages]: the message of yours that says it answers it
     * ([ChatMessage.replyTo]); or else the next of yours within [REPLY_WITHIN_MS] that answers nothing
     * else, where his [message] is the last of his with words before it.
     */
    fun replyOf(message: ChatMessage, messages: List<ChatMessage>): ChatMessage? {
        messages.firstOrNull { it.from == Speaker.You && it.replyTo == message.id }?.let { return it }
        val next = messages.filter { it.from == Speaker.You && it.replyTo == null && it.at > message.at && it.at - message.at <= REPLY_WITHIN_MS }.minByOrNull { it.at } ?: return null
        val lastOfHis = messages.filter { it.from == Speaker.Felix && it.text.isNotBlank() && it.at <= next.at }.maxByOrNull { it.at }
        return next.takeIf { lastOfHis?.id == message.id }
    }
}

/**
 * One thing Claude would do: add [task], a task of your own, or make [changes] (as an instruction
 * does). [made]: the task or instruction it made, once applied, so it can be undone.
 */
@Serializable
data class Proposal(
    val id: String,
    val task: OwnTask? = null,
    val changes: List<Change> = emptyList(),
    /**
     * [changes] in words as Claude proposed them, against the tasks as they were then: once made, a
     * task's estimate is the new one, or the task gone (a card undone), and the words would say so.
     */
    val lines: List<String> = emptyList(),
    val state: ProposalState = ProposalState.Proposed,
    /** Made as it was given (AI-24): a change to what exists, applied at once, Undo taking it back. */
    val instant: Boolean = false,
    val made: String? = null,
    /**
     * Your own tasks its changes were made on, by id, as they were before: what *Undo* puts back, as
     * no instruction holds those changes to take back.
     */
    val before: Map<String, OwnTask> = emptyMap(),
    /** Why applying it didn't work, if it didn't. */
    val note: String? = null,
    /** A task to show you (AI-14: the chat's open), by id: a card with its Open. */
    val open: String? = null,
    /**
     * The school calendar as it was before its days off and half-days went in (PL-16), and as they
     * left it: what *Undo* takes back, as no instruction holds them either.
     */
    val schoolBefore: SchoolCalendar? = null,
    val schoolAfter: SchoolCalendar? = null,
)

/**
 * Something the chat does for you beyond a change (AI-14): a step of a task ticked, or a task shown;
 * since 16 Oct (your call: he changes everything) a step unticked, a task of yours done, open again
 * or deleted, and a setting about the work set, [setting] to [value]
 * ([FelixSettings]; [taskId] empty); since 4.1.0 a reminder set, moved or cancelled, and a note
 * added or deleted, the reminder's or the note's id its [value] ([taskId] its task's, or empty); since
 * 4.1.1 a piece kept as Day's Done keeps it, or kept no more, its key its [value] (G1); and a source
 * asked to read, its name its [setting], the task asked about its [taskId] (G2: said, with no Undo).
 */
@Serializable
data class ChatAction(val type: Type, val taskId: String, val stepIndex: Int? = null, val setting: String? = null, val value: String? = null) {
    enum class Type { TickStep, Open, UntickStep, Done, Reopen, Delete, Setting, SetReminder, MoveReminder, CancelReminder, AddNote, DeleteNote, KeepPiece, UnkeepPiece, Sync }
}

/**
 * What one of the chat's actions did (16 Oct), said under its answer as [line], with what its Undo
 * needs: the [action]; the task as it was before it ([was]: deleted, or finished before it was
 * opened again), and the cards that had added a deleted one ([cards], "message|card"); a setting's
 * value before and after ([before], [after]); a piece's kept record's time ([after], G1: the one a keep made, or the one an unkeep took back), and what one taken back was kept with ([before]: its extra minutes, its photo, its source's word, and its piece's minutes as the plan laid it again, its own and its step's other sittings'); a reminder before it was moved ([reminder]). [undone]
 * once Undo has taken it back. With no [action],
 * a line saying what came of the answer, with nothing to undo; a [problem] where something wasn't
 * made or looked up.
 */
@Serializable
data class Act(
    val line: String,
    val action: ChatAction? = null,
    val was: TaskItem? = null,
    val cards: List<String> = emptyList(),
    val before: String? = null,
    val after: String? = null,
    val undone: Boolean = false,
    val problem: Boolean = false,
    /** A reminder as it was before a move ([ChatAction.Type.MoveReminder]): the due and words its Undo puts back. */
    val reminder: Reminder? = null,
) {
    companion object {
        /** How a [problem]'s line starts where a card of the answer couldn't be made, and where a search he asked for wasn't looked up: each said apart in the message's label ([ChatSummary.line]). */
        const val NOT_MADE = "Not made: "
        const val NOT_LOOKED_UP = "Not looked up: "
    }
}

/**
 * What's said under a message of his, folded behind a chevron at its bubble's corner, and the label that
 * says it in a line (docs/plan_v4/05-the-chat.md §6). Pure.
 */
object ChatSummary {
    /**
     * Whether [message] has anything under its words to fold (Q4: A): its cards but a task to open (the
     * point of a message that shows you one), what it did and said of it, what it remembered, what was
     * searched and found, what was left out. A message that's just talk has none: no chevron, no label.
     */
    fun folds(message: ChatMessage): Boolean =
        message.proposals.any { it.open == null } || message.acts.isNotEmpty() || message.memoryEdits.isNotEmpty() ||
            message.searched.isNotEmpty() || message.sources.isNotEmpty() || message.note != null

    /**
     * How many things under [message] went wrong (Q5: A): a card not made, a search not looked up, what was
     * left out; and a card from before everything was made as it came whose making failed, said only on it.
     * Any, and its chevron is red.
     */
    fun problems(message: ChatMessage): Int = message.acts.count { it.problem } + failedBefore(message) + if (message.note != null) 1 else 0

    /** Cards from before everything was made as it came (16 Oct) whose making failed: their note says why, with no line of their own. */
    private fun failedBefore(message: ChatMessage): Int =
        message.proposals.count { !it.instant && it.open == null && it.note != null && it.state != ProposalState.Applied }

    /** A label's words: what he did ([did], "Changes: 2 · Remembered · Searched") and what went wrong ([wrong], in red, "1 not made"), each part only where it applies. */
    data class Line(val did: String, val wrong: String) {
        val isEmpty: Boolean get() = did.isEmpty() && wrong.isEmpty()
    }

    /**
     * [message]'s label (Q5: A): "Changes" counting what he made that still stands, each one thing he
     * did (a change, a task added, an action: a step ticked, a setting); "Remembered" where he
     * remembered or forgot something; "Searched" where he looked something up; and what wasn't made,
     * looked up or used.
     */
    fun line(message: ChatMessage): Line {
        val made = message.proposals.filter { it.open == null && it.state == ProposalState.Applied }.sumOf { if (it.task != null) 1 else it.changes.size }
        val changes = made + message.acts.count { it.action != null && !it.undone }
        val notMade = message.acts.count { it.problem && it.line.startsWith(Act.NOT_MADE) } + failedBefore(message)
        return Line(
            listOfNotNull(
                "Changes: $changes".takeIf { changes > 0 },
                "Remembered".takeIf { message.memoryEdits.any { !it.undone } },
                "Searched".takeIf { message.searched.isNotEmpty() },
            ).joinToString(" · "),
            listOfNotNull(
                "$notMade not made".takeIf { notMade > 0 },
                "not looked up".takeIf { message.acts.any { it.problem && it.line.startsWith(Act.NOT_LOOKED_UP) } },
                "left out".takeIf { message.note != null },
            ).joinToString(" · "),
        )
    }
}

@Serializable
enum class ProposalState { Proposed, Applied, Discarded }

/** Whether the chat's actions can still be taken back (16 Oct), as their Undo shows and makes them. Pure. */
object ChatActs {

    /**
     * Whether [act] can still be taken back, with [tasks], [settings], the chat's [messages] and Felix's
     * [notes] and [reminders] as they are, [messageId] the message holding it (where known: an act can read
     * the same as an earlier one), at [now] (where given, a reminder brought back is one that would still come, its
     * task gone by its source's last read, [readAt]): a step ticked still done (one of yours, or one done by its tick, it finished, too); one unticked still to do;
     * a task done still done; one opened again still as that left it ([asReopened]); one deleted
     * still gone, and none of the cards that had added it made again since (Redo makes it anew); a
     * setting still as it was set; a reminder set or moved still unsent, not snoozed nor cancelled, and
     * not moved again since; one cancelled still cancelled, with room for it among those still to come; a
     * note added still there; one deleted still deleted, and kept, with room for it on its task; a piece
     * kept still kept by the record it made, its key's newest among today's [kept] (each key's newest
     * record's time), one kept no more still not, its task open, the record it took back today's (from
     * [dayStart] on: past midnight its key names another day's piece).
     */
    fun canUndo(act: Act, tasks: List<TaskItem>, settings: Settings, messages: List<ChatMessage>, notes: List<Note> = emptyList(), reminders: List<Reminder> = emptyList(), kept: Map<String, Long> = emptyMap(), messageId: String? = null, now: Long? = null, dayStart: Long? = null, readAt: (Source) -> Long? = { null }): Boolean {
        if (act.undone) return false
        val a = act.action ?: return false
        val task = tasks.firstOrNull { it.id == a.taskId }
        val step = a.stepIndex?.let { task?.subSteps?.getOrNull(it) }
        val reminder = reminders.firstOrNull { it.id == a.value }
        val note = notes.firstOrNull { it.id == a.value }
        return when (a.type) {
            ChatAction.Type.Open -> false
            ChatAction.Type.TickStep -> task != null && step?.done == true && (task.isOpen || task.source == Source.Own || task.tickDone) && tickedBy(act, task.id, a.stepIndex!!, step, kept)
            ChatAction.Type.UntickStep -> task?.isOpen == true && step?.done == false
            ChatAction.Type.Done -> task?.isOpen == false && (task.source == Source.Own || task.tickDone)
            ChatAction.Type.Reopen -> task != null && act.was != null && asReopened(task, act.was)
            ChatAction.Type.Delete -> act.was != null && task == null && !redone(act.cards, messages)
            ChatAction.Type.Setting -> act.before != null && FelixSettings.field(a.setting.orEmpty())?.get(settings) == act.after
            ChatAction.Type.SetReminder -> reminder != null && Reminders.unsent(reminder) && !movedSince(act, messages, messageId)
            ChatAction.Type.MoveReminder -> reminder != null && act.reminder != null && Reminders.unsent(reminder) && !movedSince(act, messages, messageId) &&
                comes(reminder.copy(text = act.reminder.text, due = act.reminder.due, taskId = act.reminder.taskId), tasks, now, readAt)
            // Brought back only where there's room for it: not past the reminders still to come, nor a task's notes, kept.
            ChatAction.Type.CancelReminder -> reminder?.cancelledAt != null && reminders.count(Reminders::pending) < Reminders.MAX_PENDING && comes(reminder.copy(cancelledAt = null), tasks, now, readAt)
            ChatAction.Type.AddNote -> note != null && note.deletedAt == null
            ChatAction.Type.DeleteNote -> note?.deletedAt != null && Notes.hasRoom(note, notes)
            // One it finished (one of yours, or one done by its tick, its last piece) too: its Undo opens it again, as a tick's does.
            ChatAction.Type.KeepPiece -> act.after?.toLongOrNull()?.let { at -> kept[a.value] == at && task != null && (task.isOpen || finishedBy(task, at)) } == true
            // Kept again since it took one back (a newer record of its key) bars it; an older box's record under the key doesn't.
            ChatAction.Type.UnkeepPiece -> act.after?.toLongOrNull()?.let { removed -> removed >= (dayStart ?: Long.MIN_VALUE) && kept[a.value]?.let { it < removed } ?: true } == true && task?.isOpen == true
            ChatAction.Type.Sync -> false
        }
    }

    /** Whether [r], brought back, would still come at [now]: not let go at once, its task finished, gone or with no deadline ([Reminders.letGo]). */
    private fun comes(r: Reminder, tasks: List<TaskItem>, now: Long?, readAt: (Source) -> Long?): Boolean = now == null || Reminders.letGo(listOf(r), tasks.associateBy { it.id }, now, readAt).isEmpty()

    /**
     * Whether [task], finished, was finished by a piece of it kept at [at] (G1): one of yours, or one done by its
     * tick, its finish then, as the app finishes it at once; not one finished another way since.
     */
    fun finishedBy(task: TaskItem, at: Long): Boolean =
        (task.source == Source.Own || task.tickDone) && !task.isOpen && task.doneAt?.let { it in at..at + FINISHED_BY_MS } == true

    /** How soon after a piece's keep its task's finish is that keep's. */
    private const val FINISHED_BY_MS = 60_000L

    /**
     * Whether [step] is still done by [act]'s tick ([Act.after]): one kept as its piece while the record it made is its
     * key's newest among today's [kept], so a keep since (in its slot, or from Day) is another's to take back; one by
     * hand while the step's still ticked by hand, which a later tick by hand from Day looks the same as (a step keeps
     * no time of its tick), and is taken back with it. An act from before this says nothing, and is as before.
     */
    fun tickedBy(act: Act, taskId: String, index: Int, step: SubStep, kept: Map<String, Long>): Boolean = when (val after = act.after) {
        null -> true
        TICKED_BY_HAND -> step.byHand
        else -> after.toLongOrNull()?.let { at -> kept[Chunk.keyOf(taskId, index, 0)] == at } ?: true
    }

    /** A tick's [Act.after] where it was by hand, not a keep of its piece ([tickedBy]). */
    const val TICKED_BY_HAND = "hand"

    /**
     * Whether a message after the one holding [act] in [messages] ([messageId], else the first holding one
     * like it) moves its reminder again, by an act not undone: taking [act] back then would put back a time or
     * words since changed.
     */
    private fun movedSince(act: Act, messages: List<ChatMessage>, messageId: String? = null): Boolean {
        val id = act.action?.value ?: return false
        val at = messageId?.let { m -> messages.indexOfFirst { it.id == m } } ?: messages.indexOfFirst { act in it.acts }
        if (at < 0) return false
        return messages.drop(at + 1).any { m -> m.acts.any { it.action?.type == ChatAction.Type.MoveReminder && it.action.value == id && !it.undone } }
    }

    /**
     * The ids that an act in [messages] not undone can still bring back: a deleted note's, a cancelled
     * reminder's, and a deleted task's (its act keeps the task). What they name is kept while they can
     * ([Notes.prune], [Reminders.prune]).
     */
    fun held(messages: List<ChatMessage>): Set<String> = messages.flatMap { it.acts }.filter { !it.undone }.mapNotNullTo(HashSet()) { act ->
        val a = act.action ?: return@mapNotNullTo null
        when (a.type) {
            ChatAction.Type.DeleteNote, ChatAction.Type.CancelReminder -> a.value
            ChatAction.Type.Delete -> a.taskId.takeIf { act.was != null }
            else -> null
        }
    }

    /**
     * Whether [task] is as opening [was] again left it ([OwnTaskRules.reopened]): open, its steps' marks
     * and its minutes worked unchanged. Anything done on it since stands, and the Undo is gone.
     */
    fun asReopened(task: TaskItem, was: TaskItem): Boolean {
        val left = OwnTaskRules.reopened(was)
        return task.isOpen && task.subSteps == left.subSteps && task.workedMin == left.workedMin && task.photoMin == left.photoMin
    }

    /** Whether any of [cards] ("message|card") is made again (added): a deleted task's Undo would then make a second. */
    fun redone(cards: List<String>, messages: List<ChatMessage>): Boolean = cards.any { key ->
        val (message, card) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        messages.firstOrNull { it.id == message }?.proposals?.firstOrNull { it.id == card }?.state == ProposalState.Applied
    }
}
