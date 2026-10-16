package com.thomaswcode.decrastination.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `what remembering did is undone, a fact gone and the one it replaced back, one forgotten back, an instruction gone or back`() {
        val (one, first) = Memories.edit(MemoryState(), listOf(Given("football", "on Tuesdays")), 1)
        val (two, second) = Memories.edit(one, listOf(Given("Football", "on Wednesdays now")), 2)
        assertEquals(Fact("football", "on Tuesdays", 1), second.single().factBefore)
        assertEquals(one.facts, Memories.undo(two, second.single())!!.facts)
        // The first can't be while the second stands: the fact isn't the one it kept.
        assertNull(Memories.undo(two, first.single()))
        val (gone, forgot) = Memories.edit(two, listOf(Given("football", "")), 3)
        assertEquals(two.facts, Memories.undo(gone, forgot.single())!!.facts)
        val (ruled, added) = Memories.edit(MemoryState(), listOf(Given(text = "be blunter", kind = Given.RULE)), 4)
        assertEquals(emptyList(), Memories.undo(ruled, added.single())!!.rules)
        val (dropped, drop) = Memories.edit(ruled, listOf(Given(topic = "Be blunter", kind = Given.RULE)), 5)
        assertEquals(ruled.rules, Memories.undo(dropped, drop.single())!!.rules)
        // A line only has nothing to undo; the lines are apply's.
        assertNull(Memories.undo(MemoryState(), MemoryEdit("Too many standing instructions to add “x”: drop one first")))
        assertEquals(listOf("Remembered: football: on Tuesdays"), first.map { it.line })
    }

    @Test
    fun `a line from before what it did was kept is undone while what it names is still as it says`() {
        val state = MemoryState(facts = listOf(Fact("piano practice", "only at home", 1)), rules = listOf(Rule("be blunter", 2)))
        val fact = Memories.editOf("Remembered: piano practice: only at home", state)!!
        assertEquals(emptyList(), Memories.undo(state, fact)!!.facts)
        assertEquals(emptyList(), Memories.undo(state, Memories.editOf("Felix will: be blunter", state)!!)!!.rules)
        assertNull(Memories.editOf("Remembered: piano practice: anywhere", state))
        assertNull(Memories.editOf("Forgot: football", state))
    }

    @Test
    fun `an undo takes only the very fact it kept, and brings back what its room pushed out`() {
        val (one, first) = Memories.edit(MemoryState(), listOf(Given("football", "on Tuesdays")), 1)
        // The same words kept again since: the newer one's to undo, not the first's.
        val (again, second) = Memories.edit(one, listOf(Given("football", "on Tuesdays")), 2)
        assertNull(Memories.undo(again, first.single()))
        assertEquals(one.facts, Memories.undo(again, second.single())!!.facts)
        // A fact that took the room of the oldest: undone, the oldest is back.
        val long = "x".repeat(290)
        val (full, _) = Memories.edit(MemoryState(), (1..20).map { Given("topic $it", long) }, 10)
        val (over, edits) = Memories.edit(full, listOf(Given("topic new", long)), 20)
        assertEquals(false, over.facts.any { it.topic == "topic 1" })
        assertEquals(listOf("topic 1"), edits.single().evicted.map { it.topic })
        assertEquals(full.facts.toSet(), Memories.undo(over, edits.single())!!.facts.toSet())
    }

    @Test
    fun `facts remembered together each bring back only what their own room pushed out, within the room`() {
        val long = "x".repeat(290)
        val (full, _) = Memories.edit(MemoryState(), (1..20).map { Given("topic $it", long) }, 10)
        val (over, edits) = Memories.edit(full, listOf(Given("new a", long), Given("new b", long)), 20)
        assertEquals(listOf("topic 1"), edits[0].evicted.map { it.topic })
        assertEquals(listOf("topic 2"), edits[1].evicted.map { it.topic })
        val back = Memories.undo(over, edits[1])!!
        assertEquals(true, back.facts.any { it.topic == "topic 2" } && back.facts.none { it.topic == "topic 1" || it.topic == "new b" })
        assertEquals(true, Memories.chars(back.facts) <= Memories.FACT_BUDGET_CHARS)
    }
}
