package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Asking Claude (2.0.0, Q29), Felix since review v2 (AI-24): one chat for everything you'd say about
 * your work. You write; he answers, asks when something's missing, and puts what he does on cards
 * ([Proposal]): a change to a task you have is made at once, its card's Undo taking it back; a new
 * task of your own, or days off, wait for the card's Add or Apply. Kept in `chat.json`.
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
    /** Claude's: what it remembered or forgot with it (AI-21), a line each. */
    val remembered: List<String> = emptyList(),
    /** Claude's: what it did with it beyond its cards (AI-14: a step ticked), a line each. */
    val done: List<String> = emptyList(),
    /** Felix's, put here from a notification he wrote (AI-24): why he wrote it ([com.thomaswcode.decrastination.enrich.FelixPrompts.Reason]). */
    val reason: String? = null,
    /** Claude's: up to three short replies you might send next, in your words (AI-24, UI-06). */
    val suggestedReplies: List<String> = emptyList(),
)

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
)

/** Something the chat does for you beyond a change (AI-14): a step of a task ticked, or a task shown. */
@Serializable
data class ChatAction(val type: Type, val taskId: String, val stepIndex: Int? = null) {
    enum class Type { TickStep, Open }
}

@Serializable
enum class ProposalState { Proposed, Applied, Discarded }
