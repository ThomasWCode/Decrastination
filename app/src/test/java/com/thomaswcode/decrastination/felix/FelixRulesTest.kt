package com.thomaswcode.decrastination.felix

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.Fixtures.LONDON
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatState
import com.thomaswcode.decrastination.core.MessageKind
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.DayWindow
import com.thomaswcode.decrastination.core.FelixAnswer
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Plan
import com.thomaswcode.decrastination.core.Planner
import com.thomaswcode.decrastination.core.Slot
import com.thomaswcode.decrastination.core.SlotKind
import com.thomaswcode.decrastination.core.Slotter
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.core.WindowKind
import com.thomaswcode.decrastination.core.AssessLater
import com.thomaswcode.decrastination.data.ChatMigration
import com.thomaswcode.decrastination.core.FelixDraft
import com.thomaswcode.decrastination.core.FelixQuestion
import com.thomaswcode.decrastination.core.FelixRetry
import com.thomaswcode.decrastination.data.JsonStore
import com.thomaswcode.decrastination.data.PieceTimer
import com.thomaswcode.decrastination.core.FelixState
import com.thomaswcode.decrastination.core.Settings
import com.thomaswcode.decrastination.data.SlotOutcome
import com.thomaswcode.decrastination.data.SlotRecord
import com.thomaswcode.decrastination.felix.FelixRules.Sent
import com.thomaswcode.decrastination.felix.FelixPrompts.Reason
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    private fun due(now: Long, state: FelixState = FelixState(), today: List<SlotRecord> = emptyList(), sent: List<Sent> = emptyList(), quiet: Boolean = false, busy: Boolean = false, needsPhoto: Boolean = false, p: Plan = plan) =
        FelixRules.due(p, state, today, sent, settings, now, quiet, busy, needsPhoto = { needsPhoto })

    private fun record(key: String, outcome: SlotOutcome, start: Long, at: Long, earned: Boolean = true, plannedMin: Int = 30) = SlotRecord(
        date = "2026-10-12", key = key, taskId = key.substringBefore('#'), kind = Kind.Homework, label = key, plannedMin = plannedMin,
        start = start, end = start + plannedMin * 60_000L, window = WindowKind.Evening, outcome = outcome, workedMin = if (outcome.kept) plannedMin else 0, earned = earned, at = at,
    )

    private fun sent(reason: Reason, key: String?, at: Long) = Sent(reason.name, key, at)

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
    fun `a kept slot of a task he was let down about today is always answered (Q102)`() {
        // A key that isn't one in three, kept late at 21:50 after the evening's work (the plan's slots over, a piece still in today's bucket).
        val key = (1..30).map { "teams:k$it#-1#1" }.first { Math.floorMod(it.hashCode(), 3) != 0 }
        val kept = record(key, SlotOutcome.KeptLate, at("17:00"), at("21:50"))
        fun his(about: String, mood: String) = ChatMessage("f", Speaker.Felix, "that's two today. you said 17:00, so 21:30 it is.", at("17:35"), About(taskId = about), kind = MessageKind.SlotEnd, pieceKey = key, mood = mood)
        fun due(now: Long, messages: List<ChatMessage>, state: FelixState = FelixState(), sent: List<Sent> = emptyList()) =
            FelixRules.due(plan, state, listOf(kept), sent, settings, now, false, false, needsPhoto = { false }, letDown = { taskId, keptAt -> Mood.letDown(taskId, messages, now, LONDON, keptAt) })
        val firm = listOf(his(key.substringBefore('#'), "Firm"))
        assertEquals(Reason.SlotKept to kept, due(at("21:55"), firm).let { it?.reason to it?.record })
        // His check not reached: answered all the same.
        assertEquals(Reason.SlotKept, due(at("21:55"), firm, FelixState(nextCheckAt = at("22:40")))?.reason)
        // About another task, neutral, or none: nothing, as before.
        assertNull(due(at("21:55"), listOf(his("teams:other", "Firm"))))
        assertNull(due(at("21:55"), listOf(his(key.substringBefore('#'), ""))))
        assertNull(due(at("21:55"), emptyList()))
        // Said once; and not once it's stale.
        assertNull(due(at("21:57"), firm, sent = listOf(sent(Reason.SlotKept, key, at("21:55")))))
        // The day done said about it answers it: not said again as a kept slot.
        assertNull(due(at("21:57"), firm, sent = listOf(sent(Reason.DayDone, key, at("21:55")))))
        // A let-down written after it was kept (about the task's next piece, say) isn't one it recovers from.
        assertNull(due(at("21:55"), listOf(his(key.substringBefore('#'), "Firm").copy(at = at("21:52")))))
        assertNull(due(at("22:25"), firm))
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
        val ask = listOf(AssessLater("teams:c", "German reading", at("16:00")))
        fun completions(now: Long, state: FelixState = FelixState(), sent: List<Sent> = emptyList()) =
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
        val draft = FelixDraft(Reason.SlotStart.name, first.chunk!!.key, FelixAnswer("Ten minutes in: starting?"), at("17:02"))
        val now = first.start + grace
        assertEquals(true, FelixRules.fits(draft, trigger, now, lastSaidAt = at("16:00")))
        // Another piece's, too old, or with something said since, his or yours: written again.
        assertEquals(false, FelixRules.fits(draft.copy(key = "teams:b#-1#1"), trigger, now, null))
        assertEquals(false, FelixRules.fits(draft.copy(at = now - FelixRules.DRAFT_FRESH_MS - 1), trigger, now, null))
        assertEquals(false, FelixRules.fits(draft, trigger, now, lastSaidAt = at("17:05")))
        // Written from another plan (its slot moved, a piece kept since), or one not known (from before a start): written again.
        val laid = FelixRules.laid(plan, trigger, emptyList())
        assertEquals(true, FelixRules.fits(draft, trigger, now, null, laid, laid))
        assertEquals(false, FelixRules.fits(draft, trigger, now, null, laid, FelixRules.laid(plan, trigger.copy(slot = first.copy(start = first.start + 300_000L)), emptyList())))
        assertEquals(false, FelixRules.fits(draft, trigger, now, null, null, laid))
    }

    @Test
    fun `a question of the model's is put when he's free, the oldest first`() {
        val q = FelixQuestion("teams:a", "The 12th or the 19th?", at("09:00"))
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
        val yesterday = AssessLater("teams:c", "German reading", Fixtures.at("2026-10-11T16:00"))
        val today = AssessLater("teams:d", "Maths sheet", at("16:00"))
        val seen = AssessLater("teams:a", "a", Fixtures.at("2026-10-11T18:00"))
        val kept = record("teams:a#-1#1", SlotOutcome.Kept, Fixtures.at("2026-10-11T17:00"), Fixtures.at("2026-10-11T17:30")).copy(date = "2026-10-11")
        assertEquals(listOf(yesterday, today), FelixRules.toAsk(listOf(yesterday, today, seen), listOf(kept), at("19:05"), LONDON))
        // The same task's slot kept on another day saw other work.
        assertEquals(listOf(seen), FelixRules.toAsk(listOf(seen), listOf(kept.copy(date = "2026-10-12")), at("19:05"), LONDON))
    }

    @Test
    fun `a newer reading's question takes the place of the one waiting, and none takes it away`() {
        val q = FelixQuestion("teams:a", "The 12th or the 19th?", at("09:00"))
        val state = FelixState(questions = listOf(q, FelixQuestion("teams:b", "Which exercise?", at("09:05"))))
        assertEquals(listOf("Part 1 or part 2?", "Which exercise?"), state.withQuestion("teams:a", "Part 1 or part 2?", at("10:00")).questions.map { it.question })
        assertEquals(state, state.withQuestion("teams:a", "The 12th or the 19th?", at("10:00")))
        assertEquals(listOf("teams:b"), state.withQuestion("teams:a", null, at("10:00")).questions.map { it.taskId })
        assertEquals(listOf("teams:a", "teams:b", "teams:c"), state.withQuestion("teams:c", "Which?", at("10:00")).questions.map { it.taskId })
    }

    @Test
    fun `what he's sent counts the same from the chat as it did from the log's records`() {
        val key = first.chunk!!.key
        // Monday's records as the log kept them: a slot asked about twice (once silent), a kept slot's line,
        // the evening's completions, and his answer to a reply (Reply), which no rule counts.
        fun record(id: String, reason: Reason, at: Long, key: String? = null, text: String = "message") =
            JsonObject(listOfNotNull("id" to JsonPrimitive(id), "at" to JsonPrimitive(at), "reason" to JsonPrimitive(reason.name), "text" to JsonPrimitive(text), key?.let { "key" to JsonPrimitive(it) }).toMap())
        val records = listOf(
            record("r1", Reason.SlotStart, first.start + 10 * 60_000L, key),
            record("r2", Reason.SlotStart, first.start + 20 * 60_000L, key, text = ""),
            record("r3", Reason.SlotKept, at("17:40"), "teams:k1#-1#1"),
            record("r4", Reason.Completions, at("19:05")),
            record("r5", Reason.Reply, at("19:10")),
        )
        val logged = records.map { Sent(it.getValue("reason").let { r -> (r as JsonPrimitive).content }, (it["key"] as? JsonPrimitive)?.content, (it.getValue("at") as JsonPrimitive).content.toLong()) }
        // The same records, as the chat's migration makes them messages, and as his rules read them.
        val chat = JsonStore.json.decodeFromJsonElement(ChatState.serializer(), JsonObject(ChatMigration.merged(JsonObject(emptyMap()), JsonArray(records), null, null, emptyMap()) + ("version" to JsonPrimitive(2))))
        val read = FelixRules.sent(chat.messages, at("00:00"))
        assertEquals(logged.filterNot { it.reason == Reason.Reply.name }, read)
        val ask = listOf(AssessLater("teams:c", "German reading", at("16:00")))
        for (now in listOf(first.start + 25 * 60_000L, at("17:45"), at("19:30"))) {
            assertEquals(
                FelixRules.due(plan, FelixState(), emptyList(), logged, settings, now, false, false, { false }, ask, LONDON)?.reason,
                FelixRules.due(plan, FelixState(), emptyList(), read, settings, now, false, false, { false }, ask, LONDON)?.reason,
            )
        }
    }

    @Test
    fun `a message whose call didn't come back is tried again past its wait, the rest going on meanwhile`() {
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        val now = first.end + Slotter.GRACE_MS + 60_000L
        val retry = FelixRetry(Reason.SlotEnd.name, missed.key, now + FelixRules.RETRY_MS)
        assertNull(due(now, FelixState(retry = retry), today = listOf(missed))?.takeIf { it.reason == Reason.SlotEnd })
        assertEquals(Reason.SlotEnd, due(now + FelixRules.RETRY_MS, FelixState(retry = retry), today = listOf(missed))?.reason)
        // His next look comes for it, with nothing else ahead.
        val empty = Planner.plan(Planner.Input(emptyList(), at("16:00"), LONDON, settings, windows = evening))
        assertEquals(now + FelixRules.RETRY_MS, FelixRules.next(empty, FelixState(retry = retry), settings, now))
    }

    // --- A timer on a piece (D-112): his slot-start and slot-over messages about its task held ---

    private val second = plan.todaySlots.filter { it.kind == SlotKind.Work }[1]

    /** A timer on [taskId]'s piece from [startedAt], [minutes] long, stopped by hand at [stoppedAt]. */
    private fun timer(taskId: String, startedAt: Long, minutes: Int = 30, stoppedAt: Long? = null) =
        PieceTimer("$taskId#-1#1", taskId, null, null, taskId, minutes, startedAt, stoppedAt = stoppedAt)

    private fun timed(now: Long, timer: PieceTimer?, today: List<SlotRecord> = emptyList(), sent: List<Sent> = emptyList()) =
        FelixRules.due(plan, FelixState(), today, sent, settings, now, false, false, needsPhoto = { false }, timer = timer)

    @Test
    fun `a timer running on the slot's task holds its nudge, one started before the slot too, but not another task's or one stopped`() {
        val a = first.chunk!!.taskId
        val nudge = first.start + 10 * 60_000L
        assertEquals(Reason.SlotStart, timed(nudge, null)?.reason)
        assertNull(timed(nudge, timer(a, first.start + 2 * 60_000L)))
        assertNull(timed(nudge, timer(a, first.start - 10 * 60_000L)))
        assertEquals(Reason.SlotStart, timed(nudge, timer(second.chunk!!.taskId, first.start))?.reason)
        // Stopped, with nothing done on record (Start notes it, so this is a timer started before the slot): asked as ever.
        assertEquals(Reason.SlotStart, timed(nudge, timer(a, first.start - 10 * 60_000L, stoppedAt = first.start + 60_000L))?.reason)
    }

    @Test
    fun `a timer left running past its hold no longer holds a later slot's nudge`() {
        val b = second.chunk!!.taskId
        val nudge = second.start + 10 * 60_000L
        // Its zero 30 minutes before that slot began: its hold lifted as it began.
        assertEquals(Reason.SlotStart, timed(nudge, timer(b, second.start - 60 * 60_000L))?.reason)
        // Its zero at that slot's start: held till 30 minutes past it.
        assertNull(timed(nudge, timer(b, second.start - 30 * 60_000L)))
    }

    @Test
    fun `the slot-over message waits while a timer holds, then is said once, past its usual freshness`() {
        val a = first.chunk!!.taskId
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        // An hour's timer from two minutes in: its zero an hour on, the hold 30 minutes after that.
        val running = timer(a, first.start + 2 * 60_000L, minutes = 60)
        val lift = FelixRules.liftAt(running)
        assertEquals(running.endsAt + FelixRules.TIMER_HOLD_MS, lift)
        fun slotEnd(now: Long, sent: List<Sent> = emptyList()) = timed(now, running, listOf(missed), sent)?.takeIf { it.reason == Reason.SlotEnd }
        assertNull(slotEnd(missed.at + 60_000L))
        assertNull(slotEnd(lift - 1))
        assertEquals(missed, slotEnd(lift)?.record)
        assertEquals(missed, slotEnd(lift + FelixRules.FRESH_MS)?.record)
        assertNull(slotEnd(lift + FelixRules.FRESH_MS + 1))
        // Said once.
        assertNull(slotEnd(lift + 60_000L, listOf(sent(Reason.SlotEnd, missed.key, lift))))
    }

    @Test
    fun `a Stop by hand holds the slot-over message 30 minutes from the stop, or from the zero where that came first`() {
        val a = first.chunk!!.taskId
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        fun slotEnd(now: Long, timer: PieceTimer) = timed(now, timer, listOf(missed))?.takeIf { it.reason == Reason.SlotEnd }
        // Stopped before its zero, at the slot's end, the piece undone.
        val early = timer(a, first.start, minutes = 60, stoppedAt = first.end)
        assertNull(slotEnd(first.end + FelixRules.TIMER_HOLD_MS - 1, early))
        assertEquals(missed, slotEnd(first.end + FelixRules.TIMER_HOLD_MS, early)?.record)
        // Stopped twenty minutes after its zero: lifted at its zero and 30, not later.
        val late = timer(a, first.start, minutes = 30, stoppedAt = first.end + 20 * 60_000L)
        assertEquals(first.end + FelixRules.TIMER_HOLD_MS, FelixRules.liftAt(late))
        assertNull(slotEnd(first.end + FelixRules.TIMER_HOLD_MS - 1, late))
        assertEquals(missed, slotEnd(first.end + FelixRules.TIMER_HOLD_MS, late)?.record)
    }

    @Test
    fun `a miss after the hold lifts is judged by its own time, and one stale before the timer began stays stale`() {
        val a = first.chunk!!.taskId
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        // A ten-minute timer an hour before: lifted long before the miss, which is said as ever.
        val before = timer(a, first.start - 60 * 60_000L, minutes = 10)
        assertEquals(missed, timed(missed.at + 60_000L, before, listOf(missed))?.takeIf { it.reason == Reason.SlotEnd }?.record)
        // A timer begun once the miss was stale (a busy event kept it unsaid): its lift doesn't bring it back.
        val after = timer(a, missed.at + FelixRules.FRESH_MS + 60_000L, minutes = 10)
        for (now in listOf(FelixRules.liftAt(after), FelixRules.liftAt(after) + 60_000L)) {
            assertNull(timed(now, after, listOf(missed))?.takeIf { it.reason == Reason.SlotEnd })
        }
        // A timer cleared by its piece done lifts nothing: the record's judged as now (here, stale).
        assertNull(timed(FelixRules.liftAt(after), null, listOf(missed))?.takeIf { it.reason == Reason.SlotEnd })
    }

    @Test
    fun `a nudge the hold would keep isn't written ahead, the next slot's is`() {
        val a = first.chunk!!.taskId
        val running = timer(a, first.start - 30 * 60_000L)
        assertEquals(second.chunk!!.key, FelixRules.upcoming(plan, settings, at("16:40"), running)?.first?.key)
        assertEquals(first.chunk!!.key, FelixRules.upcoming(plan, settings, at("16:40"))?.first?.key)
        // Stopped, it holds no nudge: written ahead as ever.
        assertEquals(first.chunk!!.key, FelixRules.upcoming(plan, settings, at("16:40"), running.copy(stoppedAt = at("16:35")))?.first?.key)
    }

    @Test
    fun `he's told of a running timer with its piece, or in a line of its own`() {
        val running = timer(first.chunk!!.taskId, at("17:02"), minutes = 30)
        assertEquals("timer on it since 17:02, 8 min left", FelixPrompts.timerOn(running, at("17:24"), LONDON))
        assertEquals("timer on it since 17:02, its time just up", FelixPrompts.timerOn(running, at("17:32") + 30_000L, LONDON))
        assertEquals("timer on it since 17:02, 12 min over", FelixPrompts.timerOn(running, at("17:44"), LONDON))
        assertEquals("Timer running on teams:a since 17:02, 8 min left", FelixPrompts.timerLine(running, at("17:24"), LONDON))
        val now = at("17:12")
        val text = FelixPrompts.describe(Reason.SlotStart, plan, first, null, emptyList(), emptyList(), null, settings, now, LONDON, timer = running)
        assertTrue(text.lines().first { it.startsWith("Piece: ") }.endsWith(", timer on it since 17:02, 20 min left"), text)
        val other = FelixPrompts.describe(Reason.SlotStart, plan, second, null, emptyList(), emptyList(), null, settings, now, LONDON, timer = running)
        assertTrue("Timer running on teams:a since 17:02, 20 min left" in other, other)
        // Stopped, nothing's said of it.
        assertFalse("imer" in FelixPrompts.describe(Reason.SlotStart, plan, first, null, emptyList(), emptyList(), null, settings, now, LONDON, timer = running.copy(stoppedAt = now)))
        val chat = ChatPrompts.plan(plan.copy(now = now), LONDON, timer = running)
        assertTrue(", timer on it since 17:02, 20 min left" in chat.lines().first { it.startsWith("Now: ") }, chat)
    }

    @Test
    fun `a look of his is told today's breaks outside under way or to come (D-116)`() {
        val outside = Slot(SlotKind.Outside, null, at("18:00"), at("18:20"), WindowKind.Evening, pinned = false, outsideAt = 18 * 60, outsideAdded = true)
        val withBreak = plan.copy(buckets = plan.buckets.map { if (it.date == monday) it.copy(slots = (it.slots + outside).sortedBy { s -> s.start }) else it })
        fun told(now: String) = FelixPrompts.describe(Reason.Completions, withBreak, null, null, emptyList(), emptyList(), null, settings, at(now), LONDON).lines().filter { it.startsWith("Breaks outside today: ") }
        assertEquals(listOf("Breaks outside today: 18:00–18:20"), told("17:12"))
        assertEquals(listOf("Breaks outside today: 18:00–18:20 (under way)"), told("18:05"))
        assertEquals(emptyList(), told("18:25"))
    }

    @Test
    fun `a message asked for is held once a timer started meanwhile holds it`() {
        val a = first.chunk!!.taskId
        val start = FelixRules.Trigger(Reason.SlotStart, slot = first)
        val now = first.start + 10 * 60_000L
        assertFalse(FelixRules.held(start, null, now))
        assertTrue(FelixRules.held(start, timer(a, now - 5_000L), now))
        assertFalse(FelixRules.held(start, timer(second.chunk!!.taskId, now - 5_000L), now))
        assertFalse(FelixRules.held(start, timer(a, now - 5_000L, stoppedAt = now - 1_000L), now))
        // A miss: held by a timer that could have covered it, till its hold lifts; one stale before it began isn't its.
        val missed = record(first.chunk!!.key, SlotOutcome.Missed, first.start, first.end + Slotter.GRACE_MS)
        val end = FelixRules.Trigger(Reason.SlotEnd, record = missed)
        val after = timer(a, missed.at + 5_000L)
        assertTrue(FelixRules.held(end, after, missed.at + 10_000L))
        assertFalse(FelixRules.held(end, after, FelixRules.liftAt(after)))
        val late = timer(a, missed.at + FelixRules.FRESH_MS + 60_000L)
        assertFalse(FelixRules.held(end, late, late.startedAt + 10_000L))
        // His other messages are never the timer's to hold.
        assertFalse(FelixRules.held(FelixRules.Trigger(Reason.SlotKept, record = missed), after, missed.at + 10_000L))
    }
}
