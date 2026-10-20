package com.thomaswcode.decrastination.core

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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

    @Test
    fun `a fact forgotten isn't brought back past the room newer facts have filled since`() {
        val long = "x".repeat(290)
        val (full, _) = Memories.edit(MemoryState(), (1..20).map { Given("topic $it", long) }, 10)
        val (gone, forgot) = Memories.edit(full, listOf(Given("topic 1", "")), 20)
        assertEquals(full.facts.toSet(), Memories.undo(gone, forgot.single())!!.facts.toSet())
        val (filled, _) = Memories.edit(gone, listOf(Given("newer", long)), 30)
        assertNull(Memories.undo(filled, forgot.single()))
    }

    // --- Memory that expires (10a) ---

    private val now = Fixtures.at("2026-10-20T21:00")
    private val trip = TaskItem(id = "gmail:trip", source = Source.Gmail, sourceId = "trip", title = "Trip consent form", kind = Kind.Admin, firstSeenAt = now, lastSeenAt = now)
    private val listed = mapOf(trip.id to trip.title)

    @Test
    fun `a fact or a rule for a while carries its until, a date through that day or a moment, and says so`() {
        val (state, edits) = Memories.edit(
            MemoryState(),
            listOf(
                Given("cold", "feeling rough", until = "2026-10-25"),
                Given(text = "don't message me tonight", kind = Given.RULE, until = "2026-10-21T07:00"),
                // An until that doesn't read: none, as before.
                Given("football", "on Tuesdays", until = "Sunday"),
            ),
            now, LONDON, listed,
        )
        assertEquals(Fact("cold", "feeling rough", now, until = Fixtures.at("2026-10-26T00:00")), state.facts.first { it.topic == "cold" })
        assertEquals(Rule("don't message me tonight", now, until = Fixtures.at("2026-10-21T07:00")), state.rules.single())
        assertNull(state.facts.first { it.topic == "football" }.until)
        assertEquals(
            listOf("Remembered: cold: feeling rough, until Sun 25 Oct", "Felix will: don't message me tonight, until Wed 21 Oct 07:00", "Remembered: football: on Tuesdays"),
            edits.map { it.line },
        )
    }

    @Test
    fun `an until gone by is refused, saying so, and one more than a year ahead is cut to a year`() {
        val (state, edits) = Memories.edit(MemoryState(), listOf(Given("cold", "feeling rough", until = "2026-10-19")), now, LONDON)
        assertEquals(MemoryState(), state)
        assertEquals(listOf("Not remembered: cold: feeling rough, as Mon 19 Oct has gone by"), edits.map { it.line })
        val (far, _) = Memories.edit(MemoryState(), listOf(Given("exam year", "it's a big one", until = "2028-06-30")), now, LONDON)
        assertEquals(now + Memories.MAX_UNTIL_MS, far.facts.single().until)
        // Forgetting by topic needs no until: one given with a past until still forgets.
        val (kept, _) = Memories.edit(MemoryState(), listOf(Given("cold", "feeling rough", until = "2026-10-25")), now, LONDON)
        assertEquals(emptyList(), Memories.edit(kept, listOf(Given("cold", "", until = "2026-10-19")), now, LONDON).first.facts)
    }

    @Test
    fun `a fact with a task keeps it where the task is listed, and is kept without it, said, where it isn't`() {
        val (state, edits) = Memories.edit(
            MemoryState(),
            listOf(Given("room", "sharing with a friend", taskId = trip.id), Given("length", "the essay can run over", taskId = "teams:unknown")),
            now, LONDON, listed,
        )
        assertEquals(trip.id, state.facts.first { it.topic == "room" }.taskId)
        assertNull(state.facts.first { it.topic == "length" }.taskId)
        assertEquals(
            listOf("Remembered: room: sharing with a friend, with “Trip consent form”", "Remembered: length: the essay can run over (its task isn't listed, so it's kept without one)"),
            edits.map { it.line },
        )
    }

    @Test
    fun `the same rule for another while takes the new until, and its undo brings the old back`() {
        val (one, _) = Memories.edit(MemoryState(), listOf(Given(text = "be blunter", kind = Given.RULE)), 1, LONDON)
        val (two, edits) = Memories.edit(one, listOf(Given(text = "Be blunter", kind = Given.RULE, until = "2026-10-25")), now, LONDON)
        assertEquals(listOf(Rule("Be blunter", now, until = Fixtures.at("2026-10-26T00:00"))), two.rules)
        assertEquals("Felix will: Be blunter, until Sun 25 Oct", edits.single().line)
        assertEquals(one.rules, Memories.undo(two, edits.single())!!.rules)
        // The same until again: nothing to do.
        assertEquals(emptyList(), Memories.edit(two, listOf(Given(text = "be blunter", kind = Given.RULE, until = "2026-10-25")), now, LONDON).second)
    }

    @Test
    fun `an undo compares the fact whole, its until and its task too`() {
        val (state, edits) = Memories.edit(MemoryState(), listOf(Given("cold", "feeling rough", until = "2026-10-25")), now, LONDON)
        assertEquals(MemoryState(), Memories.undo(state, edits.single())!!.copy(version = 2))
        // Kept again since for another while: it's that one's to undo, not this.
        val (again, _) = Memories.edit(state, listOf(Given("cold", "feeling rough", until = "2026-10-27")), now + 1, LONDON)
        assertNull(Memories.undo(again, edits.single()))
    }

    @Test
    fun `what has run out is forgotten, by its until, a fortnight after its task finished, once its task's delete can't be undone`() {
        val day = 24 * 3_600_000L
        val essay = trip.copy(id = "teams:essay", source = Source.Teams, sourceId = "essay", title = "Essay")
        val facts = listOf(
            Fact("cold", "feeling rough", now, until = now + day),
            Fact("for good", "football on Tuesdays", now),
            Fact("room", "sharing with a friend", now, taskId = trip.id),
            Fact("length", "it can run over", now, taskId = essay.id),
            Fact("own", "for a task of theirs", now, taskId = "own:gone"),
        )
        val state = MemoryState(facts, listOf(Rule("not tonight", now, until = now + 10 * 3_600_000L), Rule("be blunter", now)))
        val tasks = listOf(trip, essay).associateBy { it.id }
        // Nothing run out yet: the very same state, so nothing is written; the deleted own task's delete still undoable.
        assertSame(state, Memories.expired(state, tasks, now, held = setOf("own:gone")))
        // A day on: the cold and tonight's rule gone; the own task's delete no longer undoable, its fact gone too.
        val dayOn = Memories.expired(state, tasks, now + day)
        assertEquals(listOf("for good", "room", "length"), dayOn.facts.map { it.topic })
        assertEquals(listOf("be blunter"), dayOn.rules.map { it.text })
        // The essay finished: its fact stays a fortnight after, then goes.
        val finished = tasks + (essay.id to essay.copy(status = Status.Done, doneAt = now + day))
        assertEquals(listOf("for good", "room", "length"), Memories.expired(dayOn, finished, now + 14 * day).facts.map { it.topic })
        assertEquals(listOf("for good", "room"), Memories.expired(dayOn, finished, now + 16 * day).facts.map { it.topic })
        // A source's task not listed: kept till its source has been read since the fact was kept, then gone.
        val unlisted = mapOf(essay.id to essay)
        assertEquals(listOf("for good", "room", "length"), Memories.expired(dayOn, unlisted, now + 2 * day) { null }.facts.map { it.topic })
        assertEquals(listOf("for good", "length"), Memories.expired(dayOn, unlisted, now + 2 * day) { now + day }.facts.map { it.topic })
        // Twice, the same.
        assertEquals(dayOn, Memories.expired(dayOn, tasks, now + day))
    }
}
