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

/**
 * One thing remembering did (AI-21), kept on the answer that did it so its Undo there takes it back
 * (your call, 16 Oct): a fact kept ([factAfter]) with the one of its topic it replaced
 * ([factBefore]), or one forgotten ([factBefore] alone); a standing instruction added
 * ([ruleAfter]) or dropped ([ruleBefore]). With none of them, a line only ("Too many…"), with
 * nothing to undo. [evicted]: the oldest facts the room for its fact pushed out, back with its
 * Undo. [line] says it as the answer's foot does; [undone] once Undo has taken it back.
 */
@Serializable
data class MemoryEdit(
    val line: String,
    val factBefore: Fact? = null,
    val factAfter: Fact? = null,
    val ruleBefore: Rule? = null,
    val ruleAfter: Rule? = null,
    val evicted: List<Fact> = emptyList(),
    val undone: Boolean = false,
)

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
    fun apply(state: MemoryState, given: List<Given>, now: Long): Pair<MemoryState, List<String>> =
        edit(state, given, now).let { (after, edits) -> after to edits.map { it.line } }

    /** [apply], with what each line did, for its Undo ([undo]). */
    fun edit(state: MemoryState, given: List<Given>, now: Long): Pair<MemoryState, List<MemoryEdit>> {
        var facts = state.facts
        var rules = state.rules
        val edits = mutableListOf<MemoryEdit>()
        for (g in given) {
            val topic = g.topic.trim().take(MAX_TOPIC)
            val text = g.text.trim().take(MAX_TEXT)
            if (g.kind == Given.RULE) {
                if (text.isEmpty()) {
                    val gone = rules.filter { same(it.text, topic) }
                    if (gone.isNotEmpty()) {
                        rules = rules - gone.toSet()
                        edits += MemoryEdit("Felix won't any more: ${gone.first().text}", ruleBefore = gone.first())
                    }
                } else if (rules.none { same(it.text, text) }) {
                    if (rules.size >= RULE_MAX) edits += MemoryEdit("Too many standing instructions to add “$text”: drop one first")
                    else {
                        val rule = Rule(text, now)
                        rules = rules + rule
                        edits += MemoryEdit("Felix will: $text", ruleAfter = rule)
                    }
                }
                continue
            }
            if (topic.isEmpty()) continue
            val had = facts.firstOrNull { same(it.topic, topic) }
            facts = facts.filterNot { same(it.topic, topic) }
            if (text.isEmpty()) {
                if (had != null) edits += MemoryEdit("Forgot: $topic", factBefore = had)
            } else {
                val fact = Fact(topic, text, now)
                facts = facts + fact
                // Within their room: the oldest go first, kept with the fact that took it, for its Undo.
                val held = facts
                while (facts.size > 1 && chars(facts) > FACT_BUDGET_CHARS) facts = facts.sortedBy { it.at }.drop(1)
                edits += MemoryEdit("Remembered: $topic: $text", factBefore = had, factAfter = fact, evicted = held - facts.toSet())
            }
        }
        while (facts.size > 1 && chars(facts) > FACT_BUDGET_CHARS) facts = facts.sortedBy { it.at }.drop(1)
        return MemoryState(facts.sortedBy { it.at }, rules) to edits
    }

    /**
     * [state] with [edit] taken back (its Undo under the answer that made it): a fact kept goes, and
     * the one it replaced is back, with those its room pushed out; one forgotten is back; an
     * instruction added goes; one dropped is back. Null if it can't be: what it did has changed since
     * (the fact rewritten, kept again or forgotten, the instruction gone), or what it would bring back
     * is there already (a newer fact of its topic, the same instruction) or has no room (the facts'
     * [FACT_BUDGET_CHARS], [RULE_MAX] instructions). Only the very fact it kept is taken: the same
     * words kept again since are a newer one's to undo.
     */
    fun undo(state: MemoryState, edit: MemoryEdit): MemoryState? {
        edit.factAfter?.let { after ->
            val now = state.facts.firstOrNull { it == after } ?: return null
            val rest = state.facts - now
            var back = rest + listOfNotNull(edit.factBefore).filter { f -> rest.none { same(it.topic, f.topic) } }
            // The one it replaced, longer than the room has left since: not undone, as it can't be whole.
            if (chars(back) > FACT_BUDGET_CHARS) return null
            // Those it pushed out, the newest first, where their topics are free still and they fit.
            for (f in edit.evicted.sortedByDescending { it.at }) {
                if (back.none { same(it.topic, f.topic) } && chars(back + f) <= FACT_BUDGET_CHARS) back = back + f
            }
            return state.copy(facts = back.sortedBy { it.at })
        }
        edit.factBefore?.let { before ->
            // Not over what the room holds: newer facts have filled it since.
            if (state.facts.any { same(it.topic, before.topic) } || chars(state.facts + before) > FACT_BUDGET_CHARS) return null
            return state.copy(facts = (state.facts + before).sortedBy { it.at })
        }
        edit.ruleAfter?.let { after ->
            val now = state.rules.firstOrNull { it == after } ?: return null
            return state.copy(rules = state.rules - now)
        }
        edit.ruleBefore?.let { before ->
            if (state.rules.any { same(it.text, before.text) } || state.rules.size >= RULE_MAX) return null
            return state.copy(rules = (state.rules + before).sortedBy { it.at })
        }
        return null
    }

    /**
     * What [line] did, for an answer from before edits were kept (16 Oct): the fact or the standing
     * instruction it names, while it's still as the line says, so its Undo forgets it (what it
     * replaced wasn't kept). Null for any other line.
     */
    fun editOf(line: String, state: MemoryState): MemoryEdit? =
        state.facts.firstOrNull { "Remembered: ${it.topic}: ${it.text}" == line }?.let { MemoryEdit(line, factAfter = it) }
            ?: state.rules.firstOrNull { "Felix will: ${it.text}" == line }?.let { MemoryEdit(line, ruleAfter = it) }

    /** The same topic, or the same instruction: trimmed, in any case. */
    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
