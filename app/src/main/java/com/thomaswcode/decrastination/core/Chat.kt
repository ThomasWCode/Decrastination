package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Asking Claude (2.0.0, Q29), Felix since review v2 (AI-24): one chat for everything you'd say about
 * your work. You write; he answers, asks when something's missing, and puts what he does on cards
 * ([Proposal]): everything is made at once, its card's Undo taking it back (your call, 16 Oct; a new
 * task or days off waited for the card's Add or Apply before). Kept in `chat.json`.
 */
@Serializable
data class ChatState(val messages: List<ChatMessage> = emptyList()) {
    /** Your last message, if Claude hasn't answered it yet. */
    val unanswered: ChatMessage? get() = messages.lastOrNull()?.takeIf { it.from == Speaker.You && it.answeredAt == null }
}

@Serializable
enum class Speaker { You, Claude }

@Serializable
data class ChatMessage(
    val id: String,
    val from: Speaker,
    val text: String,
    val at: Long,
    /** What your message was written about: a task, an event or a day (from its page), or nothing in particular. */
    val about: About = About(),
    /** Claude's: what it would do, each on a card. */
    val proposals: List<Proposal> = emptyList(),
    /** Yours: when Claude answered it; null while it waits. */
    val answeredAt: Long? = null,
    /** Yours, while it waits: why (Claude off, resting, a failed call). Claude's: what it suggested that the app couldn't use. */
    val note: String? = null,
    /**
     * Yours: Claude answered it, but with nothing the app could use (it declined, was cut off, or
     * couldn't be read). Each try is paid for, so it's asked again only when you tap *Try again*,
     * never by itself.
     */
    val stuck: Boolean = false,
    /** Claude's, before 16 Oct: what it remembered or forgot with it (AI-21), a line each. Since, [memoryEdits]. */
    val remembered: List<String> = emptyList(),
    /** Claude's: what it remembered or forgot with it (AI-21), each with what its Undo takes back. */
    val memoryEdits: List<MemoryEdit> = emptyList(),
    /** Claude's: what it did with it beyond its cards (AI-14: a step ticked), a line each, with nothing to undo; since 16 Oct what has its Undo is in [acts]. */
    val done: List<String> = emptyList(),
    /** Claude's: what its actions did (a step ticked or unticked, a task done, open again or deleted, a setting set), each with its Undo. */
    val acts: List<Act> = emptyList(),
    /** Felix's, put here from a notification he wrote (AI-24): why he wrote it ([com.thomaswcode.decrastination.enrich.FelixPrompts.Reason]). */
    val reason: String? = null,
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
 * ([com.thomaswcode.decrastination.data.FelixSettings]; [taskId] empty).
 */
@Serializable
data class ChatAction(val type: Type, val taskId: String, val stepIndex: Int? = null, val setting: String? = null, val value: String? = null) {
    enum class Type { TickStep, Open, UntickStep, Done, Reopen, Delete, Setting }
}

/**
 * What one of the chat's actions did (16 Oct), said under its answer as [line], with what its Undo
 * needs: the [action]; the task as it was before it ([was]: deleted, or finished before it was
 * opened again), and the cards that had added a deleted one ([cards], "message|card"); a setting's
 * value before and after ([before], [after]). [undone] once Undo has taken it back.
 */
@Serializable
data class Act(
    val line: String,
    val action: ChatAction,
    val was: TaskItem? = null,
    val cards: List<String> = emptyList(),
    val before: String? = null,
    val after: String? = null,
    val undone: Boolean = false,
)

@Serializable
enum class ProposalState { Proposed, Applied, Discarded }
