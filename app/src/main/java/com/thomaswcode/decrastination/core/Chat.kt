package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Asking Claude (2.0.0, Q29): one chat for everything you'd say about your work. You write; Claude
 * answers, asks when something's missing, and puts what it would do on cards ([Proposal]): a new
 * task of your own, or changes to your tasks and days. Nothing changes until you tap a card's Add
 * or Apply. Kept in `chat.json`.
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
    val made: String? = null,
    /** Why applying it didn't work, if it didn't. */
    val note: String? = null,
)

@Serializable
enum class ProposalState { Proposed, Applied, Discarded }
