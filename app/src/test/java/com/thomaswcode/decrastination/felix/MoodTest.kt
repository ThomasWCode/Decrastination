package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.SourceValues
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.SubStep
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.FelixPrompts.Reason
import com.thomaswcode.decrastination.felix.Mood.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Felix feels about today's work (D-119): the rule's levels and their edges. */
class MoodTest {
    private val now = Fixtures.at("2026-11-04T19:40")

    private fun at(time: String) = Fixtures.at("2026-11-04T$time")

    private fun task(id: String = "teams:essay", due: Long? = Fixtures.at("2026-11-05T09:00"), worked: Int = 0, photo: Int = 0, steps: List<SubStep> = emptyList(), real: Boolean = true) = TaskItem(
        id = id, source = Source.Teams, sourceId = id.substringAfter(':'), title = id.substringAfter(':'), dueAt = due, kind = Kind.Homework,
        sourceEffortMin = 120, workedMin = worked, photoMin = photo, subSteps = steps, firstSeenAt = at("08:00"), lastSeenAt = at("08:00"),
        // A date the reading set, the source's own none: a plan, not a deadline.
        sourceValues = if (real) null else SourceValues(Kind.Homework, dueAt = null),
    )

    /** A slot on record as the app writes it: [key]'s, laid [start] to [end], recorded [recorded] (its end's grace by default). */
    private fun record(key: String, outcome: SlotOutcome, start: String, end: String, recorded: String = end, date: String = "2026-11-04") = SlotRecord(
        date = date, key = key, taskId = key.substringBefore('#'), kind = Kind.Homework, label = key.substringAfter(':').substringBefore('#'), plannedMin = 30,
        start = Fixtures.at("${date}T$start"), end = Fixtures.at("${date}T$end"), window = WindowKind.Evening, outcome = outcome,
        workedMin = if (outcome.kept) 30 else 0, earned = outcome.kept, at = Fixtures.at("${date}T$recorded"),
    )

    private fun missed(key: String, start: String, end: String) = record(key, SlotOutcome.Missed, start, end, recorded = end)

    private fun kept(key: String, start: String, end: String) = record(key, SlotOutcome.Kept, start, end, recorded = end)

    /** His message about [key]: a nudge (SlotStart) or a miss said (SlotEnd), with [text] (blank for a silence) and the [mood] kept on it. */
    private fun his(kind: MessageKind, key: String, time: String, text: String = "you starting?", mood: String = "", date: String = "2026-11-04") =
        ChatMessage("m-$key-$time", Speaker.Felix, text, Fixtures.at("${date}T$time"), About(taskId = key.substringBefore('#')), kind = kind, pieceKey = key, mood = mood)

    private fun down(today: List<SlotRecord>, nudges: Int = 0, task: TaskItem? = null, reason: Reason = Reason.SlotEnd) =
        Mood.of(reason, today, null, nudges, task, emptyList(), now, LONDON).level

    private fun up(today: List<SlotRecord>, record: SlotRecord, messages: List<ChatMessage> = emptyList(), reason: Reason = Reason.SlotKept) =
        Mood.of(reason, today, record, 0, null, messages, now, LONDON)

    @Test
    fun `slots gone by undone, one neutral, two firm, three strong, a move none`() {
        val a = missed("teams:a#-1#1", "16:30", "17:00")
        val b = missed("teams:b#-1#1", "17:15", "17:45")
        val c = missed("teams:c#-1#1", "18:00", "18:30")
        assertEquals(Level.Neutral, down(emptyList()))
        assertEquals(Level.Neutral, down(listOf(a)))
        assertEquals(Level.Firm, down(listOf(a, b)))
        assertEquals(Level.Strong, down(listOf(a, b, c)))
        // Four is strong still; a slot moved on purpose isn't a miss, and a kept one takes nothing off.
        assertEquals(Level.Strong, down(listOf(a, b, c, missed("teams:d#-1#1", "19:00", "19:30"))))
        assertEquals(Level.Neutral, down(listOf(a, record("teams:b#-1#1", SlotOutcome.Moved, "17:15", "17:45"))))
        assertEquals(Level.Firm, down(listOf(a, b, kept("teams:c#-1#1", "18:00", "18:30"))))
    }

    @Test
    fun `the second nudge on a piece is firm, the first neutral, a silence not one`() {
        val key = "teams:essay#-1#1"
        assertEquals(Level.Neutral, down(emptyList(), nudges = 0, reason = Reason.SlotStart))
        assertEquals(Level.Firm, down(emptyList(), nudges = 1, reason = Reason.SlotStart))
        // Counted from the chat: his nudges on this piece with words, today, before now; not another piece's, a silence, or yesterday's.
        val messages = listOf(
            his(MessageKind.SlotStart, key, "19:10"),
            his(MessageKind.SlotStart, key, "19:20", text = ""),
            his(MessageKind.SlotStart, "teams:maths#-1#1", "18:10"),
            his(MessageKind.SlotEnd, key, "19:30"),
            his(MessageKind.SlotStart, key, "19:10", date = "2026-11-03"),
            his(MessageKind.SlotStart, key, "19:50"),
        )
        assertEquals(1, Mood.nudges(messages, key, now, LONDON))
        assertEquals(0, Mood.nudges(messages, null, now, LONDON))
        // A nudge counts only for a slot under way: a SlotEnd goes by the day's misses.
        assertEquals(Level.Neutral, down(listOf(missed(key, "19:00", "19:30")), nudges = 1, reason = Reason.SlotEnd))
    }

    @Test
    fun `a real deadline close with nothing done raises a let-down a step, never makes one`() {
        val close = task(due = now + 20 * 3_600_000L)
        val a = missed("teams:a#-1#1", "16:30", "17:00")
        val b = missed("teams:b#-1#1", "17:15", "17:45")
        // A first nudge with nothing missed stays neutral: the planner lays most work the evening before it's due.
        assertEquals(Level.Neutral, down(emptyList(), task = close, reason = Reason.SlotStart))
        // A first miss with it: firm; a second nudge with it: strong; two missed with it: strong; three: strong still.
        assertEquals(Level.Firm, down(listOf(a), task = close))
        assertEquals(Level.Strong, down(emptyList(), nudges = 1, task = close, reason = Reason.SlotStart))
        assertEquals(Level.Strong, down(listOf(a, b), task = close))
        // 23 hours off counts, 25 doesn't; one gone by doesn't; a date that's only a plan never does.
        assertEquals(Level.Firm, down(listOf(a), task = task(due = now + 23 * 3_600_000L)))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = now + 25 * 3_600_000L)))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = now - 3_600_000L)))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = now + 20 * 3_600_000L, real = false)))
    }

    @Test
    fun `nothing done means no minutes worked or in a photo and no step ticked, by hand or not`() {
        val a = missed("teams:a#-1#1", "16:30", "17:00")
        val due = now + 20 * 3_600_000L
        assertEquals(Level.Firm, down(listOf(a), task = task(due = due, steps = listOf(SubStep("Plan", 30), SubStep("Write", 90)))))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = due, worked = 10)))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = due, photo = 15)))
        // Two of four ticked by hand, no minutes: something's done.
        val ticked = listOf(SubStep("Read", 20, done = true, byHand = true), SubStep("Plan", 20, done = true, byHand = true), SubStep("Write", 60), SubStep("Check", 20))
        assertEquals(Level.Neutral, down(listOf(a), task = task(due = due, steps = ticked)))
    }

    @Test
    fun `a missed piece made up is proud alone, pleased with another miss or make-up`() {
        val key = "teams:essay#-1#1"
        // Kept late at 20:00, its 17:00 slot's miss replaced, as Focus.keep writes it; his SlotEnd about it.
        val madeUp = record(key, SlotOutcome.KeptLate, "17:00", "17:30", recorded = "19:35")
        val saidMissed = his(MessageKind.SlotEnd, key, "17:35", text = "essay slot's gone. what happened?")
        assertTrue(Mood.madeUp(madeUp, listOf(saidMissed), LONDON))
        val alone = up(listOf(madeUp), madeUp, listOf(saidMissed))
        assertEquals(Level.Proud, alone.level)
        assertEquals(listOf("the missed piece of essay made up at 19:35, and no other miss today"), alone.because)
        // Another task's still missed, or made up too: pleased.
        assertEquals(Level.Pleased, up(listOf(madeUp, missed("teams:maths#-1#1", "18:00", "18:30")), madeUp, listOf(saidMissed)).level)
        val other = record("teams:maths#-1#1", SlotOutcome.KeptLate, "18:00", "18:30", recorded = "19:10")
        val both = listOf(saidMissed, his(MessageKind.SlotEnd, other.key, "18:35"))
        assertEquals(Level.Pleased, up(listOf(other, madeUp), madeUp, both).level)
        // Two made up and nothing else, the day done: pleased, not every slot kept.
        assertEquals(Level.Pleased, up(listOf(other, madeUp), madeUp, both, Reason.DayDone).level)
        // A later kept slot of another task, that day: pleased, and the make-up isn't its own.
        val later = kept("teams:maths#-1#1", "19:00", "19:30")
        assertEquals(Level.Pleased, up(listOf(madeUp, later), later, listOf(saidMissed)).level)
        // Kept late within its slot's time with no SlotEnd: only late, not a make-up.
        val late = record(key, SlotOutcome.KeptLate, "19:00", "19:30", recorded = "19:28")
        assertFalse(Mood.madeUp(late, emptyList(), LONDON))
        assertEquals(Level.Pleased, up(listOf(late), late).level)
        // Kept after its end, but with no SlotEnd of his that day: not one either.
        assertFalse(Mood.madeUp(madeUp, listOf(his(MessageKind.SlotEnd, key, "17:35", date = "2026-11-03")), LONDON))
    }

    @Test
    fun `every slot kept is proud only on the day done, from two`() {
        val a = kept("teams:a#-1#1", "16:30", "17:00")
        val b = kept("teams:b#-1#1", "17:15", "17:45")
        val all = up(listOf(a, b), b, reason = Reason.DayDone)
        assertEquals(Level.Proud, all.level)
        assertEquals(listOf("every slot today kept, 2 of 2"), all.because)
        assertEquals(Level.Pleased, up(listOf(b), b, reason = Reason.DayDone).level)
        // With work still to come, never every slot: pleased.
        assertEquals(Level.Pleased, up(listOf(a, b), b).level)
        // A slot kept after a miss earlier: pleased, the miss said.
        val after = up(listOf(missed("teams:c#-1#1", "16:00", "16:30"), b), b)
        assertEquals(Level.Pleased, after.level)
        assertEquals(listOf("this slot kept", "1 still missed today"), after.because)
        // A day done with a miss in it: pleased.
        assertEquals(Level.Pleased, up(listOf(missed("teams:c#-1#1", "16:00", "16:30"), a, b), b, reason = Reason.DayDone).level)
    }

    @Test
    fun `each reason leans its way, and the chat as today's newest record does`() {
        val a = missed("teams:a#-1#1", "16:30", "17:00")
        val b = missed("teams:b#-1#1", "17:15", "17:45")
        val k = kept("teams:c#-1#1", "18:00", "18:30")
        val strong = listOf(a, b, missed("teams:d#-1#1", "18:45", "19:15"))
        for (reason in Reason.entries) {
            val level = Mood.of(reason, strong, k, 1, null, emptyList(), now, LONDON).level
            val expected = when (reason) {
                Reason.SlotStart, Reason.SlotEnd -> Level.Strong
                Reason.SlotKept, Reason.DayDone -> Level.Pleased
                else -> Level.Neutral
            }
            assertEquals(expected, level, reason.name)
        }
        // The chat: down where the newest is a miss, up where it's kept, neutral with none; a move passed over.
        assertEquals(Level.Firm, Mood.of(null, listOf(a, k, missed("teams:d#-1#1", "18:45", "19:15")), null, 0, null, emptyList(), now, LONDON).level)
        assertEquals(Level.Pleased, Mood.of(null, listOf(a, b, k), null, 0, null, emptyList(), now, LONDON).level)
        assertEquals(Level.Neutral, Mood.of(null, emptyList(), null, 0, null, emptyList(), now, LONDON).level)
        assertEquals(Level.Pleased, Mood.of(null, listOf(a, k, record("teams:e#-1#1", SlotOutcome.Moved, "19:00", "19:30", recorded = "19:31")), null, 0, null, emptyList(), now, LONDON).level)
        // Nothing kept to be pleased about: neutral.
        assertEquals(Level.Neutral, Mood.of(Reason.SlotKept, listOf(a), a, 0, null, emptyList(), now, LONDON).level)
    }

    @Test
    fun `his line, none at neutral, in a person's words with nothing of what it rests on (D-120)`() {
        val close = task(due = Fixtures.at("2026-11-05T09:00"))
        val strong = Mood.of(Reason.SlotEnd, listOf(missed("teams:a#-1#1", "16:30", "17:00"), missed("teams:essay#-1#1", "19:00", "19:30")), null, 0, close, emptyList(), now, LONDON)
        assertEquals("How you feel, at most: properly fed up", Mood.line(strong))
        // What it rests on is kept for the scenarios' report, not told him.
        assertEquals(listOf("2 slots missed today", "its task's due tomorrow 09:00, a real deadline, and nothing's done on it yet"), strong.because)
        assertEquals("How you feel, at most: not impressed", Mood.line(Mood.of(Reason.SlotStart, emptyList(), null, 1, null, emptyList(), now, LONDON)))
        assertEquals(listOf("really pleased", "pleased", "", "not impressed", "properly fed up"), Mood.Level.entries.map(Mood::words))
        assertNull(Mood.line(Mood.NEUTRAL))
        // Kept on his message: its level's name, none at neutral or for a silence.
        assertEquals("Strong", Mood.kept(strong, "three gone today."))
        assertEquals("", Mood.kept(strong, " "))
        assertEquals("", Mood.kept(Mood.NEUTRAL, "slot's gone. what happened?"))
    }

    @Test
    fun `he was let down about a task today by the level kept on his message`() {
        val key = "teams:essay#-1#1"
        assertTrue(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotEnd, key, "17:35", "that's two today.", mood = "Firm")), now, LONDON))
        assertTrue(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotStart, key, "17:10", "you said 17:00.", mood = "Strong")), now, LONDON))
        // Neutral, pleased, another task's, a silence's, yesterday's: not.
        assertFalse(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotEnd, key, "17:35", "slot's gone. what happened?")), now, LONDON))
        assertFalse(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotKept, key, "17:35", "nice.", mood = "Pleased")), now, LONDON))
        assertFalse(Mood.letDown("teams:maths", listOf(his(MessageKind.SlotEnd, key, "17:35", "that's two today.", mood = "Firm")), now, LONDON))
        assertFalse(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotEnd, key, "17:35", "", mood = "Firm")), now, LONDON))
        assertFalse(Mood.letDown("teams:essay", listOf(his(MessageKind.SlotEnd, key, "17:35", "that's two today.", mood = "Firm", date = "2026-11-03")), now, LONDON))
        // Only one before the piece was kept: one after it is about what came next.
        val firm = listOf(his(MessageKind.SlotEnd, key, "17:35", "that's two today.", mood = "Firm"))
        assertTrue(Mood.letDown("teams:essay", firm, now, LONDON, keptAt = at("17:40")))
        assertFalse(Mood.letDown("teams:essay", firm, now, LONDON, keptAt = at("17:30")))
    }
}
