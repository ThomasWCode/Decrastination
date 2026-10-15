package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/** What Felix remembers (AI-21): facts about the student, each under a topic, and standing instructions on how he behaves. */
@Serializable
data class MemoryState(val facts: List<Fact> = emptyList(), val rules: List<Rule> = emptyList())

/** A fact worth keeping ("football is on Tuesdays"), under a short [topic] that a newer fact replaces. */
@Serializable
data class Fact(val topic: String, val text: String, val at: Long)

/** A standing instruction on how Felix behaves ("don't message before 8", "be blunter"). */
@Serializable
data class Rule(val text: String, val at: Long)
