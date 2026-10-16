package com.thomaswcode.decrastination.learn

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.data.FelixRecord
import com.thomaswcode.decrastination.data.FelixState
import com.thomaswcode.decrastination.data.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.enrich.FelixPrompts.Reason
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When Felix writes (LR-03). */
class FelixRulesTest {
    private val monday = LocalDate.parse("2026-10-12")
    private val settings = Settings()
    private val evening = mapOf(monday to listOf(DayWindow(17 * 60, 22 * 60, WindowKind.Evening)))

    private fun task(id: String, minutes: Int) = TaskItem(
        id = "teams:$id", source = Source.Teams, sourceId = id, title = id, dueAt = Fixtures.at("2026-10-12T23:59"), kind = Kind.Homework,
        sourceEffortMin = minutes, firstSeenAt = Fixtures.at("2026-10-12T08:00"), lastSeenAt = Fixtures.at("2026-10-12T08:00"),
    )

    /** Monday's plan as made at 16:00: a 30-minute piece at 17:00, and a second after it. */
    private val plan = Planner.plan(Planner.Input(listOf(task("a", 30), task("b", 30)), Fixtures.at("2026-10-12T16:00"), LONDON, settings, windows = evening))
    private val first = plan.todaySlots.first { it.kind == SlotKind.Work }

    private fun at(time: String) = Fixtures.at("2026-10-12T$time")

    private fun due(now: Long, state: FelixState = FelixState(), today: List<SlotRecord> = emptyList(), sent: List<FelixRecord> = emptyList(), quiet: Boolean = false, busy: Boolean = false, needsPhoto: Boolean = false, p: com.thomaswcode.decrastination.core.Plan = plan) =
        FelixRules.due(p, state, today, sent, settings, now, quiet, busy, needsPhoto = { needsPhoto })

    private fun record(key: String, outcome: SlotOutcome, start: Long, at: Long, earned: Boolean = true, plannedMin: Int = 30) = SlotRecord(
        date = "2026-10-12", key = key, taskId = key.substringBefore('#'), kind = Kind.Homework, label = key, plannedMin = plannedMin,
        start = start, end = start + plannedMin * 60_000L, window = WindowKind.Evening, outcome = outcome, workedMin = if (outcome.kept) plannedMin else 0, earned = earned, at = at,
    )

    private fun sent(reason: Reason, key: String?, at: Long) = FelixRecord("r$at", at, reason.name, "message", key = key)

    @Test
    fun `a slot under way with nothing done is asked about after its grace, at his pace, three times at most`() {
        val key = first.chunk!!.key
        assertNull(due(first.start + 9 * 60_000L))
        assertEquals(Reason.SlotStart, due(first.start + 10 * 60_000L)?.reason)
        // Something done on its task since it began: nothing to ask.
        assertNull(due(first.start + 12 * 60_000L, FelixState(activity = mapOf(first.chunk!!.taskId to first.start + 60_000L))))
        // Asked once and his next look set: not before it, and then again; three in all.
        val asked = listOf(sent(Reason.SlotStart, key, first.start + 10 * 60_000L))
        assertNull(due(first.start + 15 * 60_000L, FelixState(nextCheckAt = first.start + 20 * 60_000L), sent = asked))
        assertEquals(Reason.SlotStart, due(first.start + 20 * 60_000L, FelixState(nextCheckAt = first.start + 20 * 60_000L), sent = asked)?.reason)
        val thrice = asked + sent(Reason.SlotStart, key, first.start + 20 * 60_000L) + sent(Reason.SlotStart, key, first.start + 25 * 60_000L)
        assertNull(due(first.start + 28 * 60_000L, sent = thrice))
    }

    @Test
    fun `quiet hours, a busy event, and a day with nothing scheduled say nothing`() {
        val now = first.start + 12 * 60_000L
        assertNull(due(now, quiet = true))
        assertNull(due(now, busy = true))
        val empty = Planner.plan(Planner.Input(emptyList(), Fixtures.at("2026-10-12T16:00"), LONDON, settings, windows = evening))
        assertNull(due(at("17:12"), p = empty))
    }

    @Test
    fun `a slot gone by undone is asked about once, and one ticked without the photo it needs`() {
        val missed = record("teams:a#-1#1", SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        val now = missed.at + 60_000L
        assertEquals(Reason.SlotEnd to missed, due(now, today = listOf(missed)).let { it?.reason to it?.record })
        assertNull(due(now, today = listOf(missed), sent = listOf(sent(Reason.SlotEnd, missed.key, missed.at))).takeIf { it?.reason == Reason.SlotEnd })
        // Ticked at its end without a photo, a piece that needs one: once its time is over.
        val unearned = record("teams:a#-1#1", SlotOutcome.Kept, first.start, first.end - 60_000L, earned = false)
        assertEquals(Reason.SlotEnd, due(first.end + 60_000L, today = listOf(unearned), needsPhoto = true)?.reason)
        assertNull(due(first.end + 60_000L, today = listOf(unearned), needsPhoto = false).takeIf { it?.reason == Reason.SlotEnd })
        // Hours old (the phone was off): too late to ask.
        assertNull(due(missed.at + FelixRules.FRESH_MS + 60_000L, today = listOf(missed)).takeIf { it?.reason == Reason.SlotEnd })
    }

    @Test
    fun `the day done is said once, and a kept slot every third one`() {
        val empty = Planner.plan(Planner.Input(emptyList(), Fixtures.at("2026-10-12T16:00"), LONDON, settings, windows = evening))
        val kept = record("teams:a#-1#1", SlotOutcome.Kept, first.start, first.end)
        assertEquals(Reason.DayDone, due(first.end + 60_000L, today = listOf(kept), p = empty)?.reason)
        assertNull(due(first.end + 120_000L, today = listOf(kept), sent = listOf(sent(Reason.DayDone, kept.key, first.end + 60_000L)), p = empty))
        // A kept slot with more to come: a line for one in three, by its key.
        val keys = (1..30).map { "teams:k$it#-1#1" }
        val lined = keys.filter { k -> due(at("17:40"), today = listOf(record(k, SlotOutcome.Kept, at("17:00"), at("17:35"))))?.reason == Reason.SlotKept }
        assertEquals(keys.filter { Math.floorMod(it.hashCode(), 3) == 0 }, lined)
    }

    @Test
    fun `his next look is the soonest of a slot's grace in, its end and grace, and the check he set`() {
        val grace = settings.felixGraceMin * 60_000L
        assertEquals(first.start + grace, FelixRules.next(plan, FelixState(), settings, at("16:30")))
        assertEquals(at("17:15"), FelixRules.next(plan, FelixState(nextCheckAt = at("17:15")), settings, at("17:11")))
        assertEquals(first.end + Slotter.GRACE_MS + 60_000L, FelixRules.next(plan, FelixState(), settings, first.start + grace + 1))
    }

    @Test
    fun `the day's completions no slot saw are asked about once, in the evening, at his pace`() {
        val ask = listOf(com.thomaswcode.decrastination.data.AssessLater("teams:c", "German reading", at("16:00")))
        fun completions(now: Long, state: FelixState = FelixState(), sent: List<FelixRecord> = emptyList()) =
            FelixRules.due(plan, state, emptyList(), sent, settings, now, false, false, { false }, ask, LONDON)
        assertEquals(Reason.Completions to ask, completions(at("19:05")).let { it?.reason to it?.asks })
        assertNull(completions(at("18:55")).takeIf { it?.reason == Reason.Completions })
        assertNull(completions(at("19:05"), FelixState(nextCheckAt = at("19:20"))))
        assertNull(completions(at("19:30"), sent = listOf(sent(Reason.Completions, null, at("19:05")))))
    }

    @Test
    fun `his next message is written ahead for the next slot's grace, and sent only while it fits`() {
        val grace = settings.felixGraceMin * 60_000L
        val (trigger, due) = FelixRules.upcoming(plan, settings, at("16:30"))!!
        assertEquals(Reason.SlotStart to first.chunk!!.key, trigger.reason to trigger.key)
        assertEquals(first.start + grace, due)
        val draft = com.thomaswcode.decrastination.data.FelixDraft(Reason.SlotStart.name, first.chunk!!.key, com.thomaswcode.decrastination.enrich.FelixAnswer("Ten minutes in: starting?"), at("17:02"))
        val now = first.start + grace
        assertEquals(true, FelixRules.fits(draft, trigger, now, lastSentAt = at("16:00")))
        // Another piece's, too old, or with a message of his since: written again.
        assertEquals(false, FelixRules.fits(draft.copy(key = "teams:b#-1#1"), trigger, now, null))
        assertEquals(false, FelixRules.fits(draft.copy(at = now - FelixRules.DRAFT_FRESH_MS - 1), trigger, now, null))
        assertEquals(false, FelixRules.fits(draft, trigger, now, lastSentAt = at("17:05")))
    }

    @Test
    fun `a question of the model's is put when he's free, the oldest first`() {
        val q = com.thomaswcode.decrastination.data.FelixQuestion("teams:a", "The 12th or the 19th?", at("09:00"))
        val state = FelixState(questions = listOf(q, q.copy(taskId = "teams:b")))
        assertEquals(Reason.Question to q, due(at("16:30"), state).let { it?.reason to it?.question })
        assertNull(due(at("16:30"), state.copy(nextCheckAt = at("16:40"))))
        assertNull(due(at("23:30"), state, quiet = true))
    }

    @Test
    fun `with a grace past the writing ahead, he looks when the next slot's message is to be written`() {
        val long = settings.copy(felixGraceMin = 30)
        val ahead = first.start + 30 * 60_000L - FelixRules.AHEAD_MS
        assertEquals(ahead, FelixRules.next(plan, FelixState(), long, at("16:30")))
        assertEquals(ahead, FelixRules.next(plan, FelixState(), long, first.start))
        // Then at its grace in.
        assertEquals(first.start + 30 * 60_000L, FelixRules.next(plan, FelixState(), long, ahead))
    }

    @Test
    fun `completions kept from a day he couldn't be seen are asked about, unless a slot kept that day saw them`() {
        val yesterday = com.thomaswcode.decrastination.data.AssessLater("teams:c", "German reading", Fixtures.at("2026-10-11T16:00"))
        val today = com.thomaswcode.decrastination.data.AssessLater("teams:d", "Maths sheet", at("16:00"))
        val seen = com.thomaswcode.decrastination.data.AssessLater("teams:a", "a", Fixtures.at("2026-10-11T18:00"))
        val kept = record("teams:a#-1#1", SlotOutcome.Kept, Fixtures.at("2026-10-11T17:00"), Fixtures.at("2026-10-11T17:30")).copy(date = "2026-10-11")
        assertEquals(listOf(yesterday, today), FelixRules.toAsk(listOf(yesterday, today, seen), listOf(kept), at("19:05"), LONDON))
        // The same task's slot kept on another day saw other work.
        assertEquals(listOf(seen), FelixRules.toAsk(listOf(seen), listOf(kept.copy(date = "2026-10-12")), at("19:05"), LONDON))
    }

    @Test
    fun `a newer reading's question takes the place of the one waiting, and none takes it away`() {
        val q = com.thomaswcode.decrastination.data.FelixQuestion("teams:a", "The 12th or the 19th?", at("09:00"))
        val state = FelixState(questions = listOf(q, com.thomaswcode.decrastination.data.FelixQuestion("teams:b", "Which exercise?", at("09:05"))))
        assertEquals(listOf("Part 1 or part 2?", "Which exercise?"), state.withQuestion("teams:a", "Part 1 or part 2?", at("10:00")).questions.map { it.question })
        assertEquals(state, state.withQuestion("teams:a", "The 12th or the 19th?", at("10:00")))
        assertEquals(listOf("teams:b"), state.withQuestion("teams:a", null, at("10:00")).questions.map { it.taskId })
        assertEquals(listOf("teams:a", "teams:b", "teams:c"), state.withQuestion("teams:c", "Which?", at("10:00")).questions.map { it.taskId })
    }

    @Test
    fun `a message whose call didn't come back is tried again past its wait, the rest going on meanwhile`() {
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        val now = first.end + Slotter.GRACE_MS + 60_000L
        val retry = com.thomaswcode.decrastination.data.FelixRetry(Reason.SlotEnd.name, missed.key, now + FelixRules.RETRY_MS)
        assertNull(due(now, FelixState(retry = retry), today = listOf(missed))?.takeIf { it.reason == Reason.SlotEnd })
        assertEquals(Reason.SlotEnd, due(now + FelixRules.RETRY_MS, FelixState(retry = retry), today = listOf(missed))?.reason)
        // His next look comes for it, with nothing else ahead.
        val empty = Planner.plan(Planner.Input(emptyList(), at("16:00"), LONDON, settings, windows = evening))
        assertEquals(now + FelixRules.RETRY_MS, FelixRules.next(empty, FelixState(retry = retry), settings, now))
    }
}
