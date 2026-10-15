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

/**
 * What an answer asks to remember (AI-21): a fact under [topic], or ([kind] "rule") a standing
 * instruction in [text]. An empty [text] forgets: the fact of that topic, or the instruction whose
 * words [topic] gives.
 */
@Serializable
data class Given(val topic: String = "", val text: String = "", val kind: String = FACT) {
    companion object {
        const val FACT = "fact"
        const val RULE = "rule"
    }
}

/** What's remembered, kept within its bounds (AI-21). Pure. */
object Memories {
    /** The facts' room: about 1 500 tokens, the profile's share of them. */
    const val FACT_BUDGET_CHARS = 6_000
    const val RULE_MAX = 20

    /** A fact's topic, and a fact's or a rule's words, at most: what [apply] keeps. */
    const val MAX_TOPIC = 60
    const val MAX_TEXT = 300

    /** What [facts] take of their room: each topic and text. */
    fun chars(facts: List<Fact>): Int = facts.sumOf { it.topic.length + it.text.length }

    /**
     * [given] laid on [state] at [now], and a line for each thing it did: "Remembered: football: on
     * Tuesdays", "Forgot: football", "Felix will: be blunter", "Felix won't any more: …". A fact
     * replaces the one of its topic (trimmed, any case); a rule is added unless it's there already;
     * facts past [FACT_BUDGET_CHARS] lose their oldest; a rule past [RULE_MAX] isn't added, and says so.
     */
    fun apply(state: MemoryState, given: List<Given>, now: Long): Pair<MemoryState, List<String>> {
        var facts = state.facts
        var rules = state.rules
        val lines = mutableListOf<String>()
        fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
        for (g in given) {
            val topic = g.topic.trim().take(MAX_TOPIC)
            val text = g.text.trim().take(MAX_TEXT)
            if (g.kind == Given.RULE) {
                if (text.isEmpty()) {
                    val gone = rules.filter { same(it.text, topic) }
                    if (gone.isNotEmpty()) {
                        rules = rules - gone.toSet()
                        lines += "Felix won't any more: ${gone.first().text}"
                    }
                } else if (rules.none { same(it.text, text) }) {
                    if (rules.size >= RULE_MAX) lines += "Too many standing instructions to add “$text”: drop one first"
                    else {
                        rules = rules + Rule(text, now)
                        lines += "Felix will: $text"
                    }
                }
                continue
            }
            if (topic.isEmpty()) continue
            val had = facts.any { same(it.topic, topic) }
            facts = facts.filterNot { same(it.topic, topic) }
            if (text.isEmpty()) {
                if (had) lines += "Forgot: $topic"
            } else {
                facts = facts + Fact(topic, text, now)
                lines += "Remembered: $topic: $text"
            }
        }
        // Within their room: the oldest go first.
        while (facts.size > 1 && chars(facts) > FACT_BUDGET_CHARS) facts = facts.sortedBy { it.at }.drop(1)
        return MemoryState(facts.sortedBy { it.at }, rules) to lines
    }
}
