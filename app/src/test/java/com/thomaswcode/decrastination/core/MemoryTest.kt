package com.thomaswcode.decrastination.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** What Felix remembers (AI-21). */
class MemoryTest {
    @Test
    fun `a fact replaces the one of its topic, and an empty text forgets it`() {
        val (one, said) = Memories.apply(MemoryState(), listOf(Given("football", "on Tuesdays")), 1)
        assertEquals(listOf(Fact("football", "on Tuesdays", 1)), one.facts)
        assertEquals(listOf("Remembered: football: on Tuesdays"), said)
        // The same topic, any case: replaced.
        val (two, _) = Memories.apply(one, listOf(Given("Football", "on Wednesdays now")), 2)
        assertEquals(listOf(Fact("Football", "on Wednesdays now", 2)), two.facts)
        val (gone, lines) = Memories.apply(two, listOf(Given("football ", "")), 3)
        assertEquals(emptyList(), gone.facts)
        assertEquals(listOf("Forgot: football"), lines)
        // Forgetting what isn't there says nothing.
        assertEquals(emptyList(), Memories.apply(gone, listOf(Given("chess", "")), 4).second)
    }

    @Test
    fun `a rule is added once, forgotten by its words, and refused past twenty`() {
        val rule = Given(text = "don't message before 8", kind = Given.RULE)
        val (one, said) = Memories.apply(MemoryState(), listOf(rule, rule), 1)
        assertEquals(listOf(Rule("don't message before 8", 1)), one.rules)
        assertEquals(listOf("Felix will: don't message before 8"), said)
        val (none, lines) = Memories.apply(one, listOf(Given(topic = "Don't message before 8", kind = Given.RULE)), 2)
        assertEquals(emptyList(), none.rules)
        assertEquals(listOf("Felix won't any more: don't message before 8"), lines)
        val full = MemoryState(rules = (1..Memories.RULE_MAX).map { Rule("rule $it", it.toLong()) })
        val (still, refused) = Memories.apply(full, listOf(Given(text = "one more", kind = Given.RULE)), 99)
        assertEquals(Memories.RULE_MAX, still.rules.size)
        assertEquals(listOf("Too many standing instructions to add “one more”: drop one first"), refused)
    }

    @Test
    fun `facts past their room lose the oldest first`() {
        val long = "x".repeat(290)
        val many = (1..30).map { Given("topic $it", long) }
        val (kept, _) = Memories.apply(MemoryState(), many.take(10), 1)
        val (after, _) = Memories.apply(kept, many.drop(10), 2)
        assertEquals(true, after.facts.sumOf { it.topic.length + it.text.length } <= Memories.FACT_BUDGET_CHARS)
        // The first ten, oldest, went first.
        assertEquals(false, after.facts.any { it.topic == "topic 1" })
        assertEquals(true, after.facts.any { it.topic == "topic 30" })
    }
}
